package app.xreader.data.migration

import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.db.SourceType
import app.xreader.data.sync.SyncKeys

/**
 * Applies a parsed backup to the library (PLAN §8). The rules, in
 * plain words:
 *
 *  - A book whose title+author already exists here is NOT duplicated
 *    — its chapters' read marks are merged in by chapter address.
 *  - A new book arrives with its metadata and its full chapter list
 *    (read marks included); chapter *text* is not in any backup —
 *    chapters download on demand like any source book's.
 *  - The book's source is matched BY NAME against the sources
 *    installed here. Matched books link to that source. Unmatched
 *    books still arrive — unlinked, listed by name in the report —
 *    for the reader to re-link from Browse / Add-from-link.
 *  - Reading position is restored to the last read chapter.
 *  - Categories are reported, not applied: Xreader has no shelves
 *    yet, and the report says so rather than dropping them quietly.
 */
class MigrationService(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val progressDao: ReadingProgressDao,
    private val extensionDao: InstalledExtensionDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class Preview(
        val totalBooks: Int,
        val matchedBooks: Int,
        val unmatchedTitles: List<String>,
        val totalChapters: Int,
        val readChapters: Int,
        val alreadyPresent: Int
    )

    data class Report(
        val formatName: String,
        val addedBooks: Int,
        val alreadyPresent: Int,
        val chaptersImported: Int,
        val chaptersRead: Int,
        val matchedBooks: Int,
        val unmatchedTitles: List<String>,
        val categoriesNoted: List<String>
    ) {
        /** The plain-words result the studio shows, line by line. */
        fun lines(): List<String> = buildList {
            add("From a $formatName:")
            add("$addedBooks new book(s) added to your library.")
            if (alreadyPresent > 0) {
                add("$alreadyPresent book(s) were already here — their read marks were merged in.")
            }
            add("$chaptersImported chapter(s) arrived, $chaptersRead of them marked read.")
            if (matchedBooks > 0) {
                add("$matchedBooks book(s) were linked to a source you have installed.")
            }
            if (unmatchedTitles.isNotEmpty()) {
                add(
                    "${unmatchedTitles.size} book(s) couldn't be linked to a source " +
                        "— they're in your library; add their site from Browse or " +
                        "\"Add from link\" to download their chapters."
                )
            }
            if (categoriesNoted.isNotEmpty()) {
                add(
                    "The backup had categories (${categoriesNoted.joinToString(", ")}). " +
                        "Xreader doesn't have shelves yet, so books arrived uncategorised."
                )
            }
            add("Tracking, ratings and app settings from the old app aren't carried over.")
        }
    }

    /** What an import WOULD do — the studio's preview step. */
    suspend fun preview(library: MigratedLibrary): Preview {
        val extensions = extensionDao.allNow()
        val existing = existingKeys()
        var matched = 0
        val unmatched = mutableListOf<String>()
        var already = 0
        for (book in library.books) {
            if (bookKey(book) in existing) already++
            if (matchExtension(book, extensions) != null) {
                matched++
            } else if (book.sourceName.isNotBlank() || book.url.isNotBlank()) {
                unmatched += book.title
            }
        }
        return Preview(
            totalBooks = library.books.size,
            matchedBooks = matched,
            unmatchedTitles = unmatched,
            totalChapters = library.books.sumOf { it.chapters.size },
            readChapters = library.books.sumOf { b -> b.chapters.count { it.read } },
            alreadyPresent = already
        )
    }

    suspend fun apply(library: MigratedLibrary): AppResult<Report> {
        val extensions = extensionDao.allNow()
        val now = clock()
        var added = 0
        var already = 0
        var chaptersImported = 0
        var chaptersRead = 0
        var matched = 0
        val unmatched = mutableListOf<String>()

        for (migrated in library.books) {
            val existing = findExisting(migrated)
            if (existing != null) {
                already++
                chaptersRead += mergeReadState(existing, migrated)
                continue
            }
            val extension = matchExtension(migrated, extensions)
            if (extension != null) {
                matched++
            } else if (migrated.sourceName.isNotBlank() || migrated.url.isNotBlank()) {
                unmatched += migrated.title
            }
            val orderedChapters = orderChapters(migrated.chapters)
            val bookId = bookDao.insert(
                Book(
                    title = migrated.title,
                    author = migrated.author,
                    description = migrated.description,
                    sourceType = SourceType.EXTENSION,
                    extensionId = extension?.id,
                    remoteId = migrated.url.takeIf { it.isNotBlank() },
                    totalChapters = orderedChapters.size,
                    addedAt = now,
                    updatedAt = now,
                    finished = orderedChapters.isNotEmpty() &&
                        orderedChapters.all { it.read }
                )
            )
            added++
            var lastReadIndex = -1
            orderedChapters.forEachIndexed { index, chapter ->
                chapterDao.insert(
                    Chapter(
                        bookId = bookId,
                        index = index,
                        title = chapter.name.ifBlank { "Chapter ${index + 1}" },
                        remoteId = chapter.url.takeIf { it.isNotBlank() },
                        read = chapter.read,
                        updatedAt = now
                    )
                )
                chaptersImported++
                if (chapter.read) {
                    chaptersRead++
                    lastReadIndex = index
                }
            }
            if (lastReadIndex >= 0) {
                val chapterRows = chapterDao.forBook(bookId)
                val anchor = chapterRows.getOrNull(lastReadIndex)
                if (anchor != null) {
                    progressDao.upsert(
                        ReadingProgress(
                            bookId = bookId,
                            chapterId = anchor.id,
                            chapterIndex = lastReadIndex,
                            charOffset = 0,
                            fraction = if (orderedChapters.isEmpty()) {
                                0f
                            } else {
                                (lastReadIndex + 1).toFloat() / orderedChapters.size
                            },
                            scrollFraction = 0f,
                            updatedAt = now,
                            deviceId = ""
                        )
                    )
                }
            }
        }

        return AppResult.Ok(
            Report(
                formatName = library.formatName,
                addedBooks = added,
                alreadyPresent = already,
                chaptersImported = chaptersImported,
                chaptersRead = chaptersRead,
                matchedBooks = matched,
                unmatchedTitles = unmatched,
                categoriesNoted = library.categories
            )
        )
    }

    // -- matching -----------------------------------------------------------------

    /** Name matching, honestly heuristic: the backup's source name
     * against the installed extension's display name or package id,
     * normalised the same way book keys are. */
    fun matchExtension(
        book: MigratedBook,
        extensions: List<InstalledExtension>
    ): InstalledExtension? {
        if (book.sourceName.isBlank()) return null
        val wanted = SyncKeys.normalise(book.sourceName)
        return extensions.firstOrNull { extension ->
            SyncKeys.normalise(extension.displayName) == wanted ||
                SyncKeys.normalise(extension.packageId).contains(wanted) ||
                wanted.contains(SyncKeys.normalise(extension.displayName))
        }
    }

    private fun bookKey(book: MigratedBook): String =
        SyncKeys.normalise(book.title) + "|" + SyncKeys.normalise(book.author)

    private suspend fun existingKeys(): Set<String> =
        bookDao.allBooks().map { book ->
            SyncKeys.normalise(book.title) + "|" + SyncKeys.normalise(book.author)
        }.toSet()

    private suspend fun findExisting(migrated: MigratedBook): Book? =
        bookDao.allBooks().firstOrNull { book ->
            SyncKeys.normalise(book.title) == SyncKeys.normalise(migrated.title) &&
                SyncKeys.normalise(book.author) == SyncKeys.normalise(migrated.author)
        }

    /** Merges a backup's read marks into an existing book's chapters
     * (matched by chapter address, falling back to position).
     * Returns how many chapters were newly marked read. */
    private suspend fun mergeReadState(book: Book, migrated: MigratedBook): Int {
        if (migrated.chapters.none { it.read }) return 0
        val local = chapterDao.forBook(book.id)
        if (local.isEmpty()) return 0
        val ordered = orderChapters(migrated.chapters)
        var newlyRead = 0
        ordered.forEachIndexed { position, migratedChapter ->
            if (!migratedChapter.read) return@forEachIndexed
            val target = local.firstOrNull {
                !migratedChapter.url.isBlank() && it.remoteId == migratedChapter.url
            } ?: local.getOrNull(position)
            if (target != null && !target.read) {
                chapterDao.setRead(target.id, true)
                newlyRead++
            }
        }
        return newlyRead
    }

    /** Reading order: by chapter number when the backup has them,
     * else by the source's own order, else the file's order. */
    private fun orderChapters(chapters: List<MigratedChapter>): List<MigratedChapter> =
        when {
            chapters.all { it.number != null } -> chapters.sortedBy { it.number }
            chapters.all { it.sourceOrder != null } ->
                chapters.sortedBy { it.sourceOrder }
            else -> chapters
        }
}
