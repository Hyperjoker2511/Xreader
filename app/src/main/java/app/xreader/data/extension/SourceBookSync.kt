package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.ChapterSourceMap
import app.xreader.data.db.ChapterSourceMapDao
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.SourceType
import app.xreader.data.download.FailoverOrder
import app.xreader.data.files.BookStorage
import app.xreader.data.net.HttpTransport
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges sources and the library (PLAN §5 wiring): opening a book
 * from a source catalogue creates/finds its Book row, pulls details +
 * the chapter list into Room (existing chapters keep their downloaded
 * content — matching is by remoteId), fetches the cover through the
 * whitelist, and builds chapter_source_map alternates against every
 * other installed source that carries the same book, so Phase 2's
 * failover engine has real data to heal from.
 */
class SourceBookSync(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val sourceMapDao: ChapterSourceMapDao,
    private val extensionDao: InstalledExtensionDao,
    private val service: ExtensionService,
    private val storage: BookStorage,
    private val transport: HttpTransport,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    /** Open (or create) the library book for a catalogue [ref]. */
    suspend fun openBook(handle: SourceHandle, ref: SourceBook): AppResult<Book> {
        val encoded = SourceBookIds.encode(handle.source.id, ref.id)
        var book = bookDao.findByExtensionRemote(handle.extension.id, encoded)
        if (book == null) {
            val now = nowMs()
            val id = bookDao.insert(
                Book(
                    title = ref.title,
                    author = ref.author ?: "",
                    description = ref.description ?: "",
                    sourceType = SourceType.EXTENSION,
                    extensionId = handle.extension.id,
                    remoteId = encoded,
                    addedAt = now,
                    updatedAt = now
                )
            )
            book = bookDao.getById(id)
                ?: return AppResult.Err(
                    AppError.StorageFailed("saving the book")
                )
        }
        // v1.0.1 source-book identity fix: the row EXISTS, so the
        // tap that opened it has succeeded — a failed chapter refresh
        // must not turn into "book not available". The detail page
        // sees the book, auto-loads its chapters, and shows a plain
        // error + Retry in the chapters section if the site is down.
        return when (val refreshed = refresh(book, handle)) {
            is AppResult.Err -> AppResult.Ok(bookDao.getById(book.id) ?: book)
            is AppResult.Ok -> AppResult.Ok(bookDao.getById(book.id) ?: refreshed.value)
        }
    }

    /** Provider enrichment (v1.0.1): reviews / comments / related
     * books when the book's source offers them. Null = the source
     * doesn't offer that section at all (the UI says so plainly);
     * an empty list = offered, but nothing there yet. */
    data class Enrichment(
        val reviews: List<app.xreader.extensionapi.SourceReview>? = null,
        val comments: List<app.xreader.extensionapi.SourceComment>? = null,
        val related: List<SourceBook>? = null
    )

    suspend fun enrichment(book: Book): Enrichment {
        val extensionId = book.extensionId ?: return Enrichment()
        val (sourceId, remoteId) = SourceBookIds.decode(book.remoteId)
            ?: return Enrichment()
        val resolved = when (val found = service.source(extensionId, sourceId)) {
            is AppResult.Err -> return Enrichment()
            is AppResult.Ok -> found.value
        }
        val ref = SourceBook(id = remoteId, title = book.title)
        val reviews = service.guarded(resolved) { it.reviews(ref) }
            .getOrNull()
        val comments = service.guarded(resolved) { it.comments(ref) }
            .getOrNull()
        val related = service.guarded(resolved) { it.related(ref) }
            .getOrNull()
        return Enrichment(reviews, comments, related)
    }

    /** Re-pull details + chapter list for an EXTENSION book. */
    suspend fun refresh(book: Book, handle: SourceHandle? = null): AppResult<Book> {
        val extensionId = book.extensionId
            ?: return AppResult.Err(
                AppError.ExtensionFailed("source", "this book didn't come from a source")
            )
        val (sourceId, remoteId) = SourceBookIds.decode(book.remoteId)
            ?: return AppResult.Err(
                AppError.ExtensionFailed("source", "the book's source link is damaged")
            )
        val resolved = handle ?: when (val found = service.source(extensionId, sourceId)) {
            is AppResult.Err -> return found
            is AppResult.Ok -> found.value
        }
        val ref = SourceBook(
            id = remoteId,
            title = book.title,
            author = book.author.ifBlank { null },
            description = book.description.ifBlank { null }
        )
        val pair = withContext(Dispatchers.IO) {
            if (resolved.source is DeclarativeSource) {
                service.guarded(resolved) {
                    (it as DeclarativeSource).detailsAndChapters(ref)
                }
            } else {
                val details = service.guarded(resolved) { it.details(ref) }
                when (details) {
                    is AppResult.Err -> details
                    is AppResult.Ok -> service.guarded(resolved) { it.chapters(ref) }
                        .map { chapters -> details.value to chapters }
                }
            }
        }
        val (details, chapters) = when (pair) {
            is AppResult.Err -> return pair
            is AppResult.Ok -> pair.value
        }
        val now = nowMs()
        // v1.0.1 synopsis-vanish fix: merge onto the CURRENT row,
        // re-read at write time (the passed-in snapshot may predate
        // another writer — the BookFileSync lesson), and never let a
        // blank fetched field erase a stored one. A source page that
        // momentarily renders without its synopsis block must not
        // wipe the synopsis the library already has.
        val current = bookDao.getById(book.id) ?: book
        var updated = BookInfoMerge.apply(current, details).copy(
            totalChapters = chapters.size,
            updatedAt = now
        )
        // Cover: fetch once (or when the book has none yet).
        val coverUrl = details.coverUrl
        if (updated.coverPath == null && !coverUrl.isNullOrBlank()) {
            fetchCover(updated, coverUrl)?.let { relative ->
                updated = updated.copy(coverPath = relative)
            }
        }
        bookDao.update(updated)

        // v1.0.2 (R03): chapter identity goes through the single
        // reconciler — multi-key matching (remoteId → canonical id →
        // position+title → number → title), so a source changing its
        // chapter URLs aliases the EXISTING rows instead of inserting
        // a colliding second list (the "chapter isn't in the book
        // anymore" defect). Stale rows with user content survive.
        val existing = chapterDao.forBook(book.id)
        val plan = ChapterReconciler.reconcile(existing, chapters)
        plan.dropped.forEach { chapterDao.delete(it) }
        // Two-phase index moves: park every moving row at a unique
        // negative index first, so the unique (bookId, index) index
        // can never collide mid-swap.
        val moves = (plan.updates + plan.keptStale)
            .filter { it.row.index != it.newIndex }
        moves.forEachIndexed { i, update ->
            chapterDao.update(update.row.copy(index = -1_000_000 - i))
        }
        (plan.updates + plan.keptStale).forEach { update ->
            val changed = update.row.index != update.newIndex ||
                update.row.title != update.newTitle ||
                update.row.remoteId != update.newRemoteId
            if (changed) {
                chapterDao.update(
                    update.row.copy(
                        index = update.newIndex,
                        title = update.newTitle,
                        remoteId = update.newRemoteId,
                        updatedAt = now
                    )
                )
            }
        }
        plan.inserts.forEach { insert ->
            chapterDao.insert(
                Chapter(
                    bookId = book.id,
                    index = insert.index,
                    title = insert.fetched.name,
                    remoteId = insert.fetched.id,
                    updatedAt = now
                )
            )
        }
        runCatching { buildFailoverMaps(updated, chapters) }
        return AppResult.Ok(updated)
    }

    /** Match this book's chapters against other sources' copies. */
    private suspend fun buildFailoverMaps(
        book: Book,
        primaryChapters: List<app.xreader.extensionapi.SourceChapter>
    ) {
        val rows = chapterDao.forBook(book.id)
        val others = service.allSources()
            .filter { it.extension.id != book.extensionId }
        for (other in others) {
            val page = withContext(Dispatchers.IO) {
                service.guarded(other) { it.search(book.title, 1) }
            }.getOrNull() ?: continue
            val candidate = page.books.firstOrNull {
                ChapterMatcher.normaliseTitle(it.title) ==
                    ChapterMatcher.normaliseTitle(book.title)
            } ?: continue
            val altChapters = withContext(Dispatchers.IO) {
                service.guarded(other) { it.chapters(candidate) }
            }.getOrNull() ?: continue
            val matches = ChapterMatcher.match(primaryChapters, altChapters)
                .filter { it.confidence >= ChapterMatcher.AUTO_THRESHOLD }
            for (match in matches) {
                val row = rows.getOrNull(match.primaryIndex) ?: continue
                val already = sourceMapDao.forChapter(row.id)
                    .any { it.altExtensionId == other.extension.id }
                if (already) continue
                val alt = altChapters[match.altIndex]
                sourceMapDao.upsert(
                    ChapterSourceMap(
                        chapterId = row.id,
                        altExtensionId = other.extension.id,
                        altRemoteId = FailoverOrder.joinLocator(
                            other.extension.displayName,
                            SourceBookIds.encodeAltLocator(other.source.id, alt.id)
                        ),
                        matchConfidence = match.confidence,
                        verified = false
                    )
                )
            }
        }
    }

    /** Download a cover through the same whitelist the extension lives
     * under; returns the BookStorage-relative path, or null. */
    private suspend fun fetchCover(book: Book, coverUrl: String): String? {
        val row = extensionDao.getById(book.extensionId ?: return null) ?: return null
        val permissions = ExtensionPermissions.parse(row.permissionsJson)
        val host = CookieParsing.hostOf(coverUrl) ?: return null
        if (!permissions.allows(host)) return null
        val response = when (
            val result = transport.execute(HttpTransport.Request(url = coverUrl))
        ) {
            is AppResult.Err -> return null
            is AppResult.Ok -> result.value
        }
        if (response.code !in 200..299) {
            response.body?.close()
            return null
        }
        val bytes = response.body?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return null
        val extension = when {
            coverUrl.lowercase().contains(".png") -> "png"
            coverUrl.lowercase().contains(".webp") -> "webp"
            else -> "jpg"
        }
        val relative = storage.coverRelative(book.id, extension)
        return runCatching {
            val file = storage.resolve(relative)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            relative
        }.getOrNull()
    }
}

/**
 * The book-info merge rule (v1.0.1): fetched details land on the
 * stored row, field by field, and a blank fetched value NEVER
 * erases a stored one. Pure — the regression test for "synopsis
 * shows once, then is gone" pins this exact function: fetch →
 * persist → reopen shows the synopsis from the row alone, and a
 * later blank fetch leaves it untouched.
 */
object BookInfoMerge {
    fun apply(current: Book, details: SourceBook): Book = current.copy(
        title = details.title.ifBlank { current.title },
        author = details.author?.ifBlank { null } ?: current.author,
        description = details.description?.ifBlank { null } ?: current.description
    )
}
