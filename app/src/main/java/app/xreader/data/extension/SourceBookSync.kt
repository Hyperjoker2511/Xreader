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
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** v1.2.0 (audit D10): runs a block of DAO writes as ONE
     * database transaction, so a chapter-list refresh lands
     * whole or not at all instead of committing (and
     * re-emitting every observer) per row. AppContainer wires
     * Room's withTransaction in the DownloadController pattern;
     * tests and other embeddings default to running the block
     * directly, exactly as before. Declared BEFORE [nowMs] on
     * purpose: a trailing lambda binds to the LAST function-type
     * parameter, and existing callers may pass the clock that
     * way. NOTE for the coordinator: AppContainer's
     * SourceBookSync construction still needs its
     * `inTransaction = { block -> database.withTransaction(block) }`
     * line — without it this defaults to the direct run. */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit =
        { block -> block() },
    /** v1.3.0 delta (V120-CHECK 44/49): failover-map building
     * searches EVERY other installed source — on a slow
     * network that storm of calls must never sit on the
     * book-open path. The container wires a background
     * runner; the sync only ASKS for the maps to be built.
     * Default (tests, older embeddings): build nothing here;
     * the reader's resolver works primary-only until maps
     * exist. */
    private val failoverScheduler: (Book) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    /** v1.0.8: progressive chapter walks are serialised — the
     * reader trigger and the list trigger can ask at the same
     * moment, and two walks growing one list would race the
     * (bookId, index) unique index. Walks are short; a single
     * mutex is the honest cost. */
    private val walkMutex = Mutex()

    /** Open (or create) the book row for a catalogue [ref].
     *
     * v1.3.0 delta (V120-CHECK 44/45/47/49 — the delta open
     * contract): a tap NEVER writes a Library entry and NEVER
     * waits on a chapter list. The row is created as a PREVIEW
     * (inLibrary = false — invisible on the shelf); only the
     * book's DETAILS are fetched here (one small request), so
     * first paint is metadata-fast even on a near-dead network.
     * The chapter list is the book screen's own job: its
     * auto-load pulls the first chunk for progressive sources
     * (ensureChapters bootstraps via chaptersFirst) or the
     * full refresh for the rest, behind an already-painted
     * screen. An open interrupted at any point leaves at most
     * this invisible preview — the shelf stays clean, and the
     * next tap reuses the row. Membership (inLibrary = true)
     * begins only at an explicit act: Add to library, the
     * first chapter read, or a download (see markInLibrary).
     */
    suspend fun openBook(handle: SourceHandle, ref: SourceBook): AppResult<Book> {
        val encoded = SourceBookIds.encode(handle.source.id, ref.id)
        var book = bookDao.findByExtensionRemote(handle.extension.id, encoded)
        if (book == null) {
            val now = nowMs()
            val declarative = handle.source as? DeclarativeSource
            val id = bookDao.insert(
                Book(
                    title = ref.title,
                    author = ref.author ?: "",
                    description = ref.description ?: "",
                    sourceType = SourceType.EXTENSION,
                    extensionId = handle.extension.id,
                    remoteId = encoded,
                    addedAt = now,
                    updatedAt = now,
                    inLibrary = false,
                    // Progressive sources load by chunks from
                    // the book screen; the flag must say so or
                    // the screen would run a FULL refresh. Every
                    // other source keeps the complete-on-open
                    // contract its auto-load expects.
                    chapterListComplete =
                        declarative?.supportsProgressiveChapters() != true
                )
            )
            book = bookDao.getById(id)
                ?: return AppResult.Err(
                    AppError.StorageFailed("saving the book")
                )
        }
        val stored = bookDao.getById(book.id) ?: book
        // A book that already has chapters opens from the
        // device — no network at all (the v1.0.8 rule, kept).
        if (chapterDao.forBook(stored.id).isNotEmpty()) {
            return AppResult.Ok(stored)
        }
        // Details-only: ONE fetch, merged like a refresh's
        // details half (blank fields never erase stored ones).
        // A failed fetch still opens the book — the screen
        // shows its own chapter error + Retry (the v1.0.1
        // resilience rule, kept); the preview simply keeps
        // the catalogue row's title/cover until then.
        val details = withContext(Dispatchers.IO) {
            service.guarded(handle) { it.details(ref) }
        }
        if (details is AppResult.Ok) {
            applyDetails(stored, details.value)
        }
        return AppResult.Ok(bookDao.getById(stored.id) ?: stored)
    }

    /** The explicit Add (item 45): the catalogue's "Add to
     * library" — find-or-create the row (no network) and make
     * it a Library member in one step. */
    suspend fun addToLibrary(
        handle: SourceHandle,
        ref: SourceBook
    ): AppResult<Book> {
        val opened = openBook(handle, ref)
        val book = when (opened) {
            is AppResult.Err -> return opened
            is AppResult.Ok -> opened.value
        }
        markInLibrary(book.id)
        return AppResult.Ok(bookDao.getById(book.id) ?: book)
    }

    /** Flip a row to Library membership (idempotent). Called
     * by the explicit Add, the reader's first progress save,
     * and the download actions — the three acts that mean
     * "this book is mine". */
    suspend fun markInLibrary(bookId: Long) {
        val book = bookDao.getById(bookId) ?: return
        if (!book.inLibrary) {
            bookDao.update(book.copy(inLibrary = true))
        }
    }

    /** Ask for this book's failover maps to be built in the
     * background (the container's runner does the work off
     * every user path). */
    fun scheduleFailoverMaps(book: Book) {
        failoverScheduler(book)
    }

    /** Build failover maps NOW (the background runner's entry
     * point; also the pre-delta behaviour for direct callers). */
    suspend fun buildFailoverMapsNow(book: Book) {
        val chapters = chapterDao.forBook(book.id).map { row ->
            SourceChapter(
                id = row.remoteId ?: "",
                name = row.title,
                number = ChapterNumbers.parse(row.title)
            )
        }
        if (chapters.isEmpty()) return
        runCatching { buildFailoverMaps(book, chapters) }
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
            if (resolved.source is DeclarativeSource ||
                resolved.source is LiveSource
            ) {
                // v1.2.0 (A2): Live sources take the ONE-fetch
                // combined read too — they have had a
                // detailsAndChapters all along; falling back to
                // separate details() + chapters() calls fetched
                // the book page once per call on top of the
                // derivation fetch.
                service.guarded(resolved) { source ->
                    when (source) {
                        is DeclarativeSource -> source.detailsAndChapters(ref)
                        is LiveSource -> source.detailsAndChapters(ref)
                        else -> error("unreachable: guarded source kind changed")
                    }
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
        val withDetails = applyDetails(book, details)
        // v1.0.8: a full refresh IS the complete list — the
        // progressive walk state resets.
        val updated = withDetails.copy(
            totalChapters = chapters.size,
            chapterListComplete = true,
            chapterWalkNextPage = 0,
            chapterWalkTotalPages = 0,
            chapterWalkPageSize = 0
        )
        bookDao.update(updated)

        // v1.0.2 (R03): chapter identity goes through the single
        // reconciler — multi-key matching (remoteId → canonical id →
        // position+title → number → title), so a source changing its
        // chapter URLs aliases the EXISTING rows instead of inserting
        // a colliding second list (the "chapter isn't in the book
        // anymore" defect). Stale rows with user content survive.
        val existing = chapterDao.forBook(book.id)
        val plan = ChapterReconciler.reconcile(existing, chapters)
        applyPlan(book.id, plan, now)
        // v1.3.0 delta: failover maps build in the BACKGROUND —
        // never on the path that opened or refreshed the book.
        failoverScheduler(updated)
        return AppResult.Ok(updated)
    }

    /** Merge fetched DETAILS onto the stored row and persist:
     * the details half of a refresh, shared with the decoupled
     * openBook (item 49). Blank fetched fields never erase
     * stored ones (the v1.0.1 synopsis-vanish rule), and the
     * cover heals/fetches exactly as a refresh's did. */
    private suspend fun applyDetails(
        book: Book,
        details: SourceBook
    ): Book {
        val current = bookDao.getById(book.id) ?: book
        var updated = BookInfoMerge.apply(current, details).copy(
            updatedAt = nowMs()
        )
        // Cover: fetch once (or when the book has none yet).
        // v1.0.6.3 (P4 heal): a coverPath whose FILE is gone from
        // disk (cleaner apps, storage moves) counts as no cover —
        // otherwise the book shows its letter tile forever, since
        // the fetch below only ever ran for a null path.
        val coverUrl = details.coverUrl
        val storedCover = updated.coverPath
        if (storedCover != null &&
            !runCatching { storage.resolve(storedCover).exists() }.getOrDefault(false)
        ) {
            updated = updated.copy(coverPath = null)
        }
        if (updated.coverPath == null && !coverUrl.isNullOrBlank()) {
            fetchCover(updated, coverUrl)?.let { relative ->
                updated = updated.copy(coverPath = relative)
            }
        }
        bookDao.update(updated)
        return bookDao.getById(book.id) ?: updated
    }

    /** Apply a reconciler plan to the chapter table. Two-phase
     * index moves: park every moving row at a unique negative
     * index first, so the unique (bookId, index) index can never
     * collide mid-swap. Shared by the full refresh and the
     * progressive chunk merge (whose plans simply carry no
     * drops or kept-stale moves). */
    private suspend fun applyPlan(
        bookId: Long,
        plan: ChapterReconciler.Plan,
        now: Long
    ) {
        // v1.2.0 (audit D10): the whole plan — drops, the
        // two-phase index park + rewrite, and the inserts —
        // commits as ONE transaction. Observers see the list
        // before or after the refresh, never half-moved, and a
        // multi-thousand-chapter refresh stops paying a commit
        // (plus a whole-list Flow emission) per row.
        inTransaction {
            plan.dropped.forEach { chapterDao.delete(it) }
            val moves = (plan.updates + plan.keptStale)
                .filter { it.row.index != it.newIndex }
            moves.forEachIndexed { i, update ->
                chapterDao.update(update.row.copy(index = -1_000_000 - i))
            }
            (plan.updates + plan.keptStale).forEach { update ->
                val changed = update.row.index != update.newIndex ||
                    update.row.title != update.newTitle ||
                    update.row.remoteId != update.newRemoteId ||
                    update.row.dateText != update.newDateText
                if (changed) {
                    chapterDao.update(
                        update.row.copy(
                            index = update.newIndex,
                            title = update.newTitle,
                            remoteId = update.newRemoteId,
                            updatedAt = now,
                            dateText = update.newDateText
                        )
                    )
                }
            }
            plan.inserts.forEach { insert ->
                chapterDao.insert(
                    Chapter(
                        bookId = bookId,
                        index = insert.index,
                        title = insert.fetched.name,
                        remoteId = insert.fetched.id,
                        updatedAt = now,
                        dateText = insert.fetched.dateText
                    )
                )
            }
        }
    }

    /**
     * v1.0.8: grow a partially loaded chapter list to at least
     * [atLeast] chapters — the ONLY way a progressive list
     * grows after its first chunk. Demand-driven by design
     * (the user's rule: some days he reads one chapter; the
     * app must not load the whole book behind his back). A
     * complete book, a non-source book, or a book whose list
     * already reaches [atLeast] returns untouched. Passing
     * [Int.MAX_VALUE] completes the list (Download-all's
     * explicit ask).
     */
    suspend fun ensureChapters(book: Book, atLeast: Int): AppResult<Book> =
        walkMutex.withLock { ensureChaptersLocked(book, atLeast) }

    /** Grow the list until it is complete (Download-all). */
    suspend fun completeChapters(book: Book): AppResult<Book> =
        ensureChapters(book, Int.MAX_VALUE)

    private suspend fun ensureChaptersLocked(
        book: Book,
        atLeast: Int
    ): AppResult<Book> {
        val fresh = bookDao.getById(book.id) ?: return AppResult.Err(
            AppError.StorageFailed("finding the book")
        )
        if (fresh.chapterListComplete) return AppResult.Ok(fresh)
        val existing = chapterDao.forBook(fresh.id)
        if (existing.size >= atLeast) return AppResult.Ok(fresh)
        val extensionId = fresh.extensionId ?: return AppResult.Ok(fresh)
        val (sourceId, remoteId) = SourceBookIds.decode(fresh.remoteId)
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    "source", "the book's source link is damaged"
                )
            )
        val resolved = when (
            val found = service.source(extensionId, sourceId)
        ) {
            is AppResult.Err -> return found
            is AppResult.Ok -> found.value
        }
        val declarative = resolved.source as? DeclarativeSource
            ?: return AppResult.Ok(fresh)
        if (!declarative.supportsProgressiveChapters()) {
            return AppResult.Ok(fresh)
        }
        val ref = SourceBook(
            id = remoteId,
            title = fresh.title,
            author = fresh.author.ifBlank { null },
            description = fresh.description.ifBlank { null }
        )
        val have = existing.sortedBy { it.index }.map { row ->
            SourceChapter(
                id = row.remoteId ?: "",
                name = row.title,
                number = ChapterNumbers.parse(row.title),
                dateText = row.dateText
            )
        }
        val walk = DeclarativeSource.ChapterWalk(
            nextPage = fresh.chapterWalkNextPage,
            totalPages = fresh.chapterWalkTotalPages,
            pageSize = fresh.chapterWalkPageSize
        )
        // v1.3.0 delta: an UNSTARTED walk (a preview row whose
        // first chunk never landed — the decoupled openBook no
        // longer fetches it) bootstraps through chaptersFirst;
        // chaptersMore with a zeroed cursor would report the
        // empty list "complete" without fetching a page.
        val unstarted = walk.nextPage == 0 && walk.totalPages == 0 &&
            walk.pageSize == 0
        if (unstarted) {
            val first = withContext(Dispatchers.IO) {
                service.guarded(resolved) {
                    (it as DeclarativeSource)
                        .chaptersFirst(ref, atLeast)
                }
            }
            return when (first) {
                is AppResult.Err -> first
                is AppResult.Ok ->
                    AppResult.Ok(applyChunk(fresh, first.value))
            }
        }
        val chunk = withContext(Dispatchers.IO) {
            service.guarded(resolved) {
                (it as DeclarativeSource)
                    .chaptersMore(ref, have, atLeast, walk)
            }
        }
        return when (chunk) {
            is AppResult.Err -> chunk
            is AppResult.Ok -> AppResult.Ok(applyChunk(fresh, chunk.value))
        }
    }

    /** Merge one progressive chunk into the library: details
     * (first chunk only), the cover, and the append-only
     * partial reconcile. The walk state lands on the Book row
     * so the next demand resumes exactly here. Failover maps
     * build only once the list is complete — mapping a
     * prefix against other sources would write half a map. */
    private suspend fun applyChunk(
        book: Book,
        chunk: DeclarativeSource.ChapterChunk
    ): Book {
        val now = nowMs()
        val current = bookDao.getById(book.id) ?: book
        var updated = if (chunk.details != null) {
            BookInfoMerge.apply(current, chunk.details)
        } else {
            current
        }
        updated = updated.copy(
            totalChapters = chunk.chapters.size,
            updatedAt = now,
            chapterListComplete = chunk.complete,
            chapterWalkNextPage = chunk.walk.nextPage,
            chapterWalkTotalPages = chunk.walk.totalPages,
            chapterWalkPageSize = chunk.walk.pageSize
        )
        val coverUrl = chunk.details?.coverUrl
        val storedCover = updated.coverPath
        if (storedCover != null &&
            !runCatching { storage.resolve(storedCover).exists() }
                .getOrDefault(false)
        ) {
            updated = updated.copy(coverPath = null)
        }
        if (updated.coverPath == null && !coverUrl.isNullOrBlank()) {
            fetchCover(updated, coverUrl)?.let { relative ->
                updated = updated.copy(coverPath = relative)
            }
        }
        bookDao.update(updated)
        val existing = chapterDao.forBook(book.id)
        // v1.3.2 (items 64/80, scope B/R3): the COMPLETION
        // AUDIT. A chunk that completes the list settles it
        // with the FULL reconcile, not the append-only
        // partial: stale rows left by older roads (a DOM-era
        // duplicate of an API chapter, a form-variant URL)
        // are matched by identity or dropped when disposable,
        // so the stored list ends up == the source's list
        // instead of accumulating a residue no partial merge
        // could ever remove (NovelBuddy: 3,253 stored rows
        // against the API's own 3,229). Partial chunks keep
        // the append-only merge — a prefix must never drop.
        val plan = if (chunk.complete) {
            ChapterReconciler.reconcile(existing, chunk.chapters)
        } else {
            ChapterReconciler.reconcilePartial(existing, chunk.chapters)
        }
        applyPlan(book.id, plan, now)
        if (chunk.complete) {
            // v1.3.0 delta: background, never on this path.
            failoverScheduler(updated)
        }
        return bookDao.getById(book.id) ?: updated
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
        // v1.3.0 delta (item 44): the body read runs on IO —
        // never on the caller's thread. On a trickling
        // connection this read is exactly where a main-thread
        // caller used to park until the system killed the app.
        val bytes = withContext(Dispatchers.IO) {
            response.body?.use { it.readBytes() }
        } ?: return null
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

    companion object {
        /** v1.0.8: how many chapters a progressive book opens
         * with (the user's number). A site page is one request
         * and usually carries more rows than this — the chunk
         * grows in whole pages, never in sliced rows. */
        const val FIRST_CHUNK_MIN = 10

        /** v1.0.8: how close to the loaded edge the reader /
         * chapter list may get before the next chunk loads. */
        const val EDGE_TRIGGER = 20

        /** v1.0.8: how far past the current position a demand
         * load aims, so reading never waits at the edge. */
        const val EDGE_TARGET = 60
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
