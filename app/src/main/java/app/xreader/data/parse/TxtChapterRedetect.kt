package app.xreader.data.parse

import androidx.room.withTransaction
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * MR-6 Item 7 (SPEC-MR6-TXT-2026-11-25): Moon+'s `PrefChapters`
 * in kind — re-detect a local TXT book's chapters in place.
 *
 * Xreader stores parsed chapter files, not decoded source text,
 * so re-detection re-reads the source via `Book.filePath`, re-runs
 * the parser (current settings, current encoding choice), replaces
 * the chapter rows + content files in one transaction, and
 * migrates place / bookmarks / highlights across the
 * re-segmentation. Nothing is silently dropped: a mark whose
 * chapter no longer exists is kept against the nearest surviving
 * chapter by offset, and the outcome counts are returned for the
 * confirmation the user sees (and the build log records).
 *
 * Two migration modes, per the spec:
 * - [Mode.REDETECT]: place is kept at the same sentence by global
 *   char offset (chapter-local offsets summed over the book, in
 *   stored-content coordinates on both sides of the swap).
 * - [Mode.REDECODE] (Item 5's encoding change): the decoded text
 *   changes length, so place restarts at chapter 1 — honestly, and
 *   the UI says so; bookmarks/highlights are kept by chapter index
 *   with offsets clamped into the new chapter text, and the UI
 *   says positions are approximate.
 */
class TxtChapterRedetect(
    private val database: XreaderDatabase,
    private val storage: BookStorage,
    private val registry: ParserRegistry,
    private val workRoot: File
) {

    enum class Mode { REDETECT, REDECODE }

    data class Outcome(
        val oldCount: Int,
        val newCount: Int,
        val placeKept: Boolean,
        val bookmarksMoved: Int,
        val highlightsMoved: Int
    )

    sealed interface Result {
        data class Done(val outcome: Outcome) : Result

        /** `filePath` null or the source file gone — the door's
         * file-gone rule; no re-decode is attempted. */
        data object SourceUnavailable : Result

        data class Failed(val reason: String) : Result
    }

    private val json = Json { encodeDefaults = false }

    suspend fun redetect(book: Book, mode: Mode): Result = withContext(Dispatchers.IO) {
        val filePath = book.filePath ?: return@withContext Result.SourceUnavailable
        val source = storage.resolve(filePath)
        if (!source.isFile) return@withContext Result.SourceUnavailable
        // TXT books re-parse through the keyed parser: the
        // per-file encoding override (Item 5) is consulted and
        // the detected charset recorded against this book's
        // filePath — never a staging path.
        val format = book.localFormat ?: BookFormat.TXT
        val parser: BookParser? = if (format == BookFormat.TXT) {
            registry.txtParser(filePath)
        } else {
            registry.parserFor(format)
        }
        if (parser == null) {
            return@withContext Result.Failed("no parser for this book")
        }

        val chapterDao = database.chapterDao()
        val workDir = File(workRoot, "redetect-${book.id}-${System.nanoTime()}")
        workDir.mkdirs()
        // Content files moved into place during the landing; the
        // swap AND the migration commit as one transaction, so if
        // anything fails the rows roll back whole and the moved
        // files are removed again — a failed re-detection leaves
        // the book exactly as it was.
        val movedFiles = mutableListOf<File>()
        try {
            val oldChapters = chapterDao.forBook(book.id)
            val oldLengths = oldChapters.map { chapterTextLength(it) }
            val oldCumulative = cumulative(oldLengths)

            val parsed = when (val result = parser.parse(source, workDir) {}) {
                is AppResult.Ok -> result.value
                is AppResult.Err -> return@withContext Result.Failed(
                    (result.error as? app.xreader.core.error.AppError.ParseFailed)?.reason
                        ?: "the file couldn't be re-read"
                )
            }

            // Land the new chapters (ChapterLanding's shape,
            // mirrored) and migrate place/marks across — one
            // transaction for the whole swap.
            val now = System.currentTimeMillis()
            val newChapters = mutableListOf<Chapter>()
            var outcome: Outcome? = null
            database.withTransaction {
                oldChapters.forEach { chapterDao.delete(it) }
                parsed.chapters.forEachIndexed { index, meta ->
                    val chapterId = chapterDao.insert(
                        Chapter(
                            bookId = book.id,
                            index = index,
                            title = meta.title,
                            wordCount = meta.wordCount,
                            downloadState = DownloadState.READY,
                            updatedAt = now
                        )
                    )
                    val relative = storage.chapterContentRelative(book.id, chapterId)
                    val stagedFile = File(parsed.chaptersDir, meta.contentFileName)
                    val dest = storage.resolve(relative)
                    dest.parentFile?.mkdirs()
                    if (stagedFile.isFile) {
                        if (!stagedFile.renameTo(dest)) {
                            stagedFile.copyTo(dest, overwrite = true)
                            stagedFile.delete()
                        }
                        movedFiles += dest
                    }
                    chapterDao.setContent(
                        chapterId, relative, meta.wordCount, DownloadState.READY
                    )
                    newChapters += Chapter(
                        id = chapterId,
                        bookId = book.id,
                        index = index,
                        title = meta.title,
                        wordCount = meta.wordCount,
                        downloadState = DownloadState.READY,
                        updatedAt = now,
                        contentPath = relative
                    )
                }
                val newLengths = newChapters.map { chapterTextLength(it) }
                val newCumulative = cumulative(newLengths)
                outcome = migrate(
                    book, mode, oldChapters, oldCumulative,
                    newChapters, newCumulative, newLengths
                )
            }
            // Old content files go only after the swap committed.
            oldChapters.forEach { old ->
                old.contentPath?.let { runCatching { storage.resolve(it).delete() } }
            }
            Result.Done(outcome!!)
        } catch (e: Exception) {
            movedFiles.forEach { runCatching { it.delete() } }
            Result.Failed(
                e.message?.takeIf { it.isNotBlank() }
                    ?: "the re-detection couldn't finish"
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Migrates place, bookmarks and highlights; returns the
     * confirmation counts. */
    private suspend fun migrate(
        book: Book,
        mode: Mode,
        oldChapters: List<Chapter>,
        oldCumulative: List<Int>,
        newChapters: List<Chapter>,
        newCumulative: List<Int>,
        newLengths: List<Int>
    ): Outcome {
        val progressDao = database.readingProgressDao()
        val bookmarkDao = database.bookmarkDao()
        val highlightDao = database.highlightDao()
        var placeKept = false
        var bookmarksMoved = 0
        var highlightsMoved = 0

        fun locate(global: Int): Pair<Int, Int> {
            if (newChapters.isEmpty()) return 0 to 0
            var index = newCumulative.indexOfLast { it <= global }
            if (index < 0) index = 0
            val local = (global - newCumulative[index])
                .coerceIn(0, newLengths.getOrElse(index) { 0 })
            return index to local
        }

        val progress = progressDao.getForBook(book.id)
        if (progress != null && newChapters.isNotEmpty()) {
            if (mode == Mode.REDECODE) {
                progressDao.upsert(
                    progress.copy(
                        chapterId = newChapters.first().id,
                        chapterIndex = 0,
                        charOffset = 0,
                        fraction = 0f,
                        scrollFraction = 0f,
                        locatorJson = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val global = (oldCumulative.getOrElse(progress.chapterIndex) { 0 } +
                    progress.charOffset)
                val (newIndex, newOffset) = locate(global)
                placeKept = true
                progressDao.upsert(
                    progress.copy(
                        chapterId = newChapters[newIndex].id,
                        chapterIndex = newIndex,
                        charOffset = newOffset,
                        fraction = if (newLengths[newIndex] > 0) {
                            newOffset.toFloat() / newLengths[newIndex]
                        } else {
                            0f
                        },
                        scrollFraction = if (newLengths[newIndex] > 0) {
                            newOffset.toFloat() / newLengths[newIndex]
                        } else {
                            0f
                        },
                        locatorJson = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        val oldIndexById = oldChapters.associate { it.id to it.index }
        for (bookmark in bookmarkDao.forBook(book.id)) {
            if (newChapters.isEmpty()) break
            if (mode == Mode.REDECODE) {
                val newIndex = bookmark.chapterIndex.coerceIn(0, newChapters.lastIndex)
                val newOffset = bookmark.charOffset
                    .coerceIn(0, newLengths.getOrElse(newIndex) { 0 })
                if (newIndex != bookmark.chapterIndex) bookmarksMoved++
                bookmarkDao.update(
                    bookmark.copy(
                        chapterId = newChapters[newIndex].id,
                        chapterIndex = newIndex,
                        charOffset = newOffset,
                        locatorJson = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val oldIndex = oldIndexById[bookmark.chapterId] ?: bookmark.chapterIndex
                val global = (oldCumulative.getOrElse(oldIndex) { 0 } + bookmark.charOffset)
                val (newIndex, newOffset) = locate(global)
                if (newIndex != oldIndex) bookmarksMoved++
                bookmarkDao.update(
                    bookmark.copy(
                        chapterId = newChapters[newIndex].id,
                        chapterIndex = newIndex,
                        charOffset = newOffset,
                        locatorJson = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        for (highlight in highlightDao.forBook(book.id)) {
            if (newChapters.isEmpty()) break
            if (mode == Mode.REDECODE) {
                val newIndex = highlight.chapterIndexOr(oldIndexById)
                    .coerceIn(0, newChapters.lastIndex)
                val length = newLengths.getOrElse(newIndex) { 0 }
                val start = highlight.startOffset.coerceIn(0, length)
                val end = highlight.endOffset.coerceIn(start, length)
                if (newIndex != highlight.chapterIndexOr(oldIndexById)) highlightsMoved++
                highlightDao.update(
                    highlight.copy(
                        chapterId = newChapters[newIndex].id,
                        startOffset = start,
                        endOffset = end,
                        locatorJson = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                val oldIndex = oldIndexById[highlight.chapterId]
                    ?: highlight.chapterIndexOr(oldIndexById)
                val globalStart = (oldCumulative.getOrElse(oldIndex) { 0 } +
                    highlight.startOffset)
                val globalEnd = (oldCumulative.getOrElse(oldIndex) { 0 } +
                    highlight.endOffset)
                val (newIndex, newStart) = locate(globalStart)
                val chapterStart = newCumulative.getOrElse(newIndex) { 0 }
                val length = newLengths.getOrElse(newIndex) { 0 }
                val newEnd = (globalEnd - chapterStart).coerceIn(newStart, length)
                if (newIndex != oldIndex) highlightsMoved++
                highlightDao.update(
                    highlight.copy(
                        chapterId = newChapters[newIndex].id,
                        startOffset = newStart,
                        endOffset = newEnd,
                        locatorJson = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }

        return Outcome(
            oldCount = oldChapters.size,
            newCount = newChapters.size,
            placeKept = placeKept,
            bookmarksMoved = bookmarksMoved,
            highlightsMoved = highlightsMoved
        )
    }

    private fun app.xreader.data.db.Highlight.chapterIndexOr(
        oldIndexById: Map<Long, Int>
    ): Int = oldIndexById[chapterId] ?: 0

    /** A stored chapter's flattened text length — the same
     * [ChapterText] flattening the reader's offsets are kept in. */
    private fun chapterTextLength(chapter: Chapter): Int {
        val path = chapter.contentPath ?: return 0
        return runCatching {
            val file = storage.resolve(path)
            if (!file.isFile) 0
            else ChapterText(
                json.decodeFromString(ChapterContent.serializer(), file.readText())
            ).length
        }.getOrDefault(0)
    }

    private fun cumulative(lengths: List<Int>): List<Int> {
        val out = ArrayList<Int>(lengths.size)
        var sum = 0
        for (length in lengths) {
            out += sum
            sum += length
        }
        return out
    }
}
