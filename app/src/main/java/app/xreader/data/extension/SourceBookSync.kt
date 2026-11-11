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
import app.xreader.data.extension.player.MihonSourceAdapter
import app.xreader.data.files.BookStorage
import app.xreader.data.net.HttpTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /** v1.3.3 (scope S6): a stored book opened with repair
     * symptoms (see [StoredRepairPolicy]) is handed to the
     * container's background repair runner through here —
     * the open itself still returns instantly from the
     * device, exactly as the v1.0.8 rule demands. */
    private val repairScheduler: (Book) -> Unit = {},
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** v1.5.4 (WS-1 item 3): the ONE deadline over a whole
     * book-page load — details, chapters AND the rebind ladder
     * inside [refresh]. Injectable so gate tests run short
     * bounds (§0.2); production keeps the pinned value. */
    private val pageLoadTimeoutMs: Long = BOOK_PAGE_LOAD_TIMEOUT_MS,
    /** v1.5.26: whether the caller is background/bulk work —
     * the refresh worker, Check-for-updates, the version
     * sweeps and the repair runner all run inside
     * RenderRuntime.asBackgroundWork (AppContainer wires this
     * provider to it). A background [refresh] of a PLAYER book
     * fetches DETAILS ONLY and never launches the extension's
     * chapter walk: a Keiyoushi extension announces a slow
     * first chapter walk with its OWN toast ("Chapter List
     * loading takes time for first time" — the Comix exhibit,
     * V157-PROOF-FINDING-4), and an Android toast draws over
     * whatever screen is foreground — the walk may only run
     * where a person is actually waiting for that book's
     * chapters (its book page, its reader), and neither of
     * those is background work. Default false (tests, older
     * embeddings): foreground behaviour, exactly as before. */
    private val backgroundWork: () -> Boolean = { false }
) {

    /** v1.0.8: progressive chapter walks are serialised — the
     * reader trigger and the list trigger can ask at the same
     * moment, and two walks growing one list would race the
     * (bookId, index) unique index. Walks are short; a single
     * mutex is the honest cost. */
    private val walkMutex = Mutex()

    /** v1.5.39 (item 1): one mutex per book serialising a
     * refresh's plan+apply section — the read of the stored
     * list, the plan computed from it, the book-row stamp and
     * [applyPlan]. Two refreshes of the SAME book (the book
     * page's own refresh racing the startup StoredBookRepair
     * sweep was the convicted pair — V1538-CRASH-FORENSICS)
     * can no longer both plan from the same pre-state and
     * replay one plan onto the other's landed rows. Different
     * books never wait on each other. The map grows by one
     * entry per refreshed book per process — mutexes are
     * small and book ids are bounded by the library's size. */
    private val bookPlanMutexes =
        java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private fun bookPlanMutex(bookId: Long): Mutex =
        bookPlanMutexes.computeIfAbsent(bookId) { Mutex() }

    /** v1.5.8 (A1): the sync-owned supervisor scope the player
     * completion consumer lives in. A player chapter walk that
     * outlives the book-page bound completes HERE — detached
     * from any caller's scope, exactly like the adapter's own
     * update scope — and its consumer applies the chapters to
     * Room when the surviving registry call lands, so the
     * already-open book page (which observes Room) shows them
     * with no second refresh and no restarted walk. */
    private val completionScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** v1.5.10 (B1): the sync layer's single-flight for
     * player book-page fetches, keyed by the persisted book's
     * stable key — at most ONE [PendingBookPage] per
     * in-flight walk. See startBookPageFetch's player branch
     * for the duplicate-consumer race this prevents. The
     * entry clears when the shared fetch completes, so the
     * next refresh starts a genuinely new walk. */
    private val inFlightPlayerPages =
        java.util.concurrent.ConcurrentHashMap<
            String, PendingBookPage
            >()

    /** v1.5.37 (combined build, S7): the same single-flight
     * for declarative/live book-page fetches — see
     * [startDeclarativeBookPageFetch]. Before this, every
     * refresh minted a fresh pending, so the book page's
     * automatic head heal and a manual refresh landing
     * together ran TWO full pagination walks of the same
     * big book concurrently, each paying the full serial
     * page cost. */
    private val inFlightDeclarativePages =
        java.util.concurrent.ConcurrentHashMap<
            String, PendingBookPage
            >()

    /** v1.5.37 (combined build, S7): session memory of
     * automatic head heals: book id → the head stamp the
     * book's chapter list carried when a heal was last
     * attempted. The book page's heal consults it so a heal
     * that did NOT restore the head never re-fires on later
     * page entries this session (the per-ViewModel flag it
     * replaces re-armed on every entry — BIG-BOOK-REFRESH-
     * FORENSICS Q8c, the endless "Refreshing…" on entry);
     * a heal whose refresh CHANGED the head leaves the
     * stamp behind the list, so a later entry may heal
     * again. Manual refresh is never gated by this. */
    private val headHealStamps =
        java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** The head stamp of a stored chapter list: its size
     * plus its newest row's canonical id — it changes
     * exactly when the list's head or length changes. */
    fun headStampOf(chapters: List<Chapter>): String {
        val newest = chapters.maxByOrNull { it.index }
        return "${chapters.size}:" + (newest?.let {
            ChapterIdentity.canonicalKey(it.remoteId ?: "")
        } ?: "")
    }

    /** True when an automatic heal was already attempted
     * against exactly this head — the heal is spent for
     * the session until the head changes. */
    fun headHealSpent(bookId: Long, currentStamp: String): Boolean =
        headHealStamps[bookId] == currentStamp

    /** Record an automatic heal attempt against the head
     * the list carries NOW (before its refresh runs). */
    fun noteHeadHealAttempt(bookId: Long, currentStamp: String) {
        headHealStamps[bookId] = currentStamp
    }

    /** v1.5.10 (B1): the page-side chapter-wait registry —
     * process-scoped (it lives on this singleton, not on any
     * page), so a wait's age survives leaving and returning
     * to the book page. [refresh] records an entry when it
     * begins awaiting a chapters-pending book; the entry
     * clears when chapters land (refresh success OR the
     * player completion consumer's apply — both existing
     * landing points write here; neither's logic changes).
     * The clock is the sync's injectable [nowMs]. */
    val chapterWaits = ChapterWaitRegistry { nowMs() }

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
                    // v1.5.2 (§14.1): the listing's cover URL is
                    // the canonical cover field, stored at once.
                    coverUrl = ref.coverUrl,
                    // v1.5.2 (§7): tier-1 kind from THIS book's
                    // listing evidence; null stays UNKNOWN.
                    kind = ref.kind,
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
        val storedChapters = chapterDao.forBook(stored.id)
        if (storedChapters.isNotEmpty()) {
            // v1.3.3 (scope S6): …but a stored book showing
            // repair symptoms (its rows disagree with its own
            // stated total; its cover is missing) is scheduled
            // for the background canonical re-sync, so a
            // legacy store heals itself instead of being
            // carried forever (V132-CHECK 11/14).
            if (StoredRepairPolicy.needsRepair(
                    stored, storedChapters.size
                )
            ) {
                repairScheduler(stored)
            }
            return AppResult.Ok(stored)
        }
        // Details-only: ONE fetch, merged like a refresh's
        // details half (blank fields never erase stored ones).
        // A failed fetch still opens the book — the screen
        // shows its own chapter error + Retry (the v1.0.1
        // resilience rule, kept); the preview simply keeps
        // the catalogue row's title/cover until then.
        val details = withContext(Dispatchers.IO) {
            service.guarded(handle) { source ->
                // v1.5.5 (§1): a player source's details read
                // joins the adapter's single-flight registry
                // under the stored row's stable key, so an
                // open racing a refresh of the same book can
                // never overlap the donor call.
                (source as? MihonSourceAdapter)
                    ?.details(ref, stored.id.toString())
                    ?: source.details(ref)
            }
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
                number = ChapterNumbers.parse(row.title),
                dateText = row.dateText,
                // v1.5.8 (E1): the stored credit rides the
                // rebuild, so the round trip loses nothing.
                scanlator = row.scanlator,
                // v1.5.11 (transplant, Verdict 1): the stored
                // memo rides it too.
                memo = row.memo
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
        if (book.extensionId == null) return Enrichment()
        val (_, remoteId) = SourceBookIds.decode(book.remoteId)
            ?: return Enrichment()
        // v1.5.10 (B5): book-aware resolution, as in refresh().
        val resolved = when (val found = service.resolveBookSource(book)) {
            is AppResult.Err -> return Enrichment()
            is AppResult.Ok -> found.value
        }
        val ref = SourceBook(
            id = remoteId,
            title = book.title,
            // v1.5.3 (finding #3, item 8): the stored row's
            // cover rides every ref this sync builds, so a
            // details read can always fall back to the cover
            // the catalogue row carried.
            coverUrl = book.coverUrl
        )
        val reviews = service.guarded(resolved) { it.reviews(ref) }
            .getOrNull()
        val comments = service.guarded(resolved) { it.comments(ref) }
            .getOrNull()
        val related = service.guarded(resolved) { it.related(ref) }
            .getOrNull()
        return Enrichment(reviews, comments, related)
    }

    /**
     * Re-pull details + chapter list for an EXTENSION book.
     *
     * v1.5.4 (WS-1 item 3): the whole load runs under the ONE
     * page bound ([pageLoadTimeoutMs]) — details, chapters AND
     * the rebind ladder inside it (donor rule D4: the ladder
     * never becomes stacked unbounded retries outside the
     * deadline). On expiry the caller gets an honest Err, so
     * the book page lands in its existing failure state
     * (CHAPTERS_UNAVAILABLE) instead of spinning forever; the
     * bound's cancellation reaches the underlying work through
     * the seam and the bridge (items 1/2), so the load STOPS,
     * not just the waiting.
     */
    suspend fun refresh(book: Book, handle: SourceHandle? = null): AppResult<Book> {
        // v1.5.8 (A1): the bound is SPLIT for player sources —
        // [refreshWithinBound]'s player branch keeps the bound
        // on the details half, awaits the chapters deferred
        // OUTSIDE it (the deferred lives in [completionScope],
        // only the WAIT is bounded), and on expiry returns
        // details-landed + chapters-loading with a completion
        // consumer registered — so the whole-load wrapper must
        // not also sit over a player refresh, or its expiry
        // would convert that honest loading state into an Err.
        // Every other source keeps the ONE whole-load bound.
        // v1.5.10 (B5): book-aware resolution — a stale stored
        // row id heals by stable identity instead of failing
        // the open (see ExtensionService.resolveBookSource).
        val resolvedHandle = handle ?: run {
            service.resolveBookSource(book).getOrNull()
        }
        // v1.5.10 (B1): record the page-side wait BEFORE the
        // await begins — the honest-state bound is measured
        // from the refresh start. A re-join (Retry, or a
        // return to the page) keeps the ORIGINAL start, so a
        // wait that already exceeded the bound reads honest
        // on return instead of restarting its clock.
        chapterWaits.begin(book.id)
        // v1.5.39 (item 1): containment — a storage-layer
        // failure inside the refresh (the convicted class: a
        // UNIQUE-constraint throw out of the chapter table)
        // becomes an honest Err the callers already know how
        // to land, never an uncaught throw out of a
        // view-model launch (V1538-CRASH-FORENSICS §4).
        // Cancellation is not an SQLException and still
        // propagates untouched.
        val outcome: AppResult<Book> = try {
            if (resolvedHandle?.source is MihonSourceAdapter) {
                refreshWithinBound(book, resolvedHandle)
            } else {
                withTimeoutOrNull(pageLoadTimeoutMs) {
                    refreshWithinBound(book, handle)
                } ?: AppResult.Err(
                    AppError.ExtensionFailed(
                        "the source",
                        "the book page took too long to load, so the load " +
                            "was stopped — try again"
                    )
                )
            }
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            AppResult.Err(
                AppError.StorageFailed("saving the chapter list")
            )
        } catch (e: java.sql.SQLException) {
            AppResult.Err(
                AppError.StorageFailed("saving the chapter list")
            )
        }
        // v1.5.10 (B1): chapters landing clears the wait —
        // landing point 1 of 2 (the in-bound refresh success).
        // The player bound-expiry return carries NO chapters
        // yet (its consumer is landing point 2, below), so it
        // correctly leaves the entry standing. A failed
        // refresh also leaves it: the wait's age is the truth
        // the page renders until chapters actually arrive.
        if (outcome is AppResult.Ok &&
            chapterDao.forBook(book.id).isNotEmpty()
        ) {
            chapterWaits.markLanded(book.id)
        }
        return outcome
    }

    /**
     * v1.5.37 (combined build, S3): the head-check delta
     * refresh. ONE book-page fetch (the source's head
     * snapshot: details + the page's own chapter rows + the
     * totals the page states) is compared against the store:
     *
     * - UNCHANGED: the newest stored chapter rides the
     *   page's seed, the seed adds nothing, and no total the
     *   page states disagrees with the store → details are
     *   applied and the refresh settles. One request total,
     *   where a big book's full walk costs one request per
     *   list page, serially (BIG-BOOK-REFRESH-FORENSICS Q3).
     * - GROWN AT THE HEAD: the seed's extra rows are exactly
     *   the growth the page's stated total accounts for →
     *   they merge through the no-drop plan (appended after
     *   the stored list, nothing dropped, nothing
     *   renumbered) and the count stamp advances by the
     *   insert count. Completeness, walk cursors and the
     *   definition epoch carry over untouched — the delta
     *   certifies nothing beyond the head it saw.
     *
     * Null — take the full path — whenever the head-check
     * can't prove either verdict: no stored rows yet, a
     * store synced under an older definition epoch (the
     * full path re-earns the stamp), no usable seed on the
     * page, the newest stored chapter absent from the page
     * (the head moved sideways: renumbering, removals), or
     * a stated total the seed can't account for (the
     * truncated-tail disease: the page states 1,663, the
     * store holds 950, the seed shows no growth — only a
     * full walk can rebuild that list).
     */
    private suspend fun tryHeadDeltaRefresh(
        book: Book,
        resolved: SourceHandle,
        ref: SourceBook
    ): AppResult<Book>? {
        val stored = chapterDao.forBook(book.id)
        if (stored.isEmpty()) return null
        val epoch = definitionEpochFor(book.extensionId ?: return null)
        if (epoch != null && book.syncedEpoch != epoch) return null
        val snapshot = when (
            val result = service.guarded(resolved) { source ->
                when (source) {
                    is DeclarativeSource -> source.headSnapshot(ref)
                    is LiveSource -> source.headSnapshot(ref)
                    else -> null
                }
            }
        ) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> null
        } ?: return null
        val newestStoredId = stored.maxByOrNull { it.index }?.let {
            ChapterIdentity.canonicalKey(it.remoteId ?: "")
        } ?: return null
        val seedIds = snapshot.seed.mapTo(HashSet()) {
            ChapterIdentity.canonicalKey(it.id)
        }
        if (newestStoredId !in seedIds) return null
        // v1.5.39 (item 1): the details merge lands first (it
        // may fetch a cover — network never runs inside the
        // lock); the plan+apply section then runs under the
        // book's mutex against a FRESH read of the stored
        // list, with the seed's invariants re-proven against
        // that read. A concurrent refresh that landed in
        // between simply re-routes this attempt to the full
        // path (null) instead of replaying a stale plan onto
        // its rows (V1538-CRASH-FORENSICS §4).
        val settled = applyDetails(book, snapshot.details)
        return bookPlanMutex(book.id).withLock {
            val fresh = chapterDao.forBook(book.id)
            if (fresh.isEmpty()) return@withLock null
            val freshNewestId = fresh.maxByOrNull { it.index }?.let {
                ChapterIdentity.canonicalKey(it.remoteId ?: "")
            } ?: return@withLock null
            if (freshNewestId !in seedIds) return@withLock null
            val plan = withContext(Dispatchers.Default) {
                noDropPlan(fresh, snapshot.seed)
            }
            val exact = snapshot.statedTotal
            if (plan.inserts.isEmpty()) {
                if (exact != null && exact != fresh.size) {
                    return@withLock null
                }
                val capacity = snapshot.statedCapacity
                if (capacity != null && fresh.size > capacity) {
                    return@withLock null
                }
                DiagnosticLog.record(
                    "sync",
                    "delta-unchanged book=${book.id} stored=${fresh.size}"
                )
                return@withLock AppResult.Ok(settled)
            }
            if (exact != null && exact != fresh.size + plan.inserts.size) {
                return@withLock null
            }
            val grown = settled.copy(
                totalChapters = fresh.size + plan.inserts.size
            )
            bookDao.update(grown)
            applyPlan(book.id, plan, nowMs())
            DiagnosticLog.record(
                "sync",
                "delta-grown book=${book.id} +${plan.inserts.size}" +
                    " -> ${fresh.size + plan.inserts.size}"
            )
            AppResult.Ok(grown)
        }
    }

    private suspend fun refreshWithinBound(
        book: Book,
        handle: SourceHandle? = null
    ): AppResult<Book> {
        val extensionId = book.extensionId
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    SourceBookIds.decode(book.remoteId)?.first ?: "extension",
                    "this book didn't come from a source"
                )
            )
        val (sourceId, remoteId) = SourceBookIds.decode(book.remoteId)
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    "extension #$extensionId", "the book's source link is damaged"
                )
            )
        // v1.5.10 (B5): book-aware resolution (stable identity
        // + heal on a row-id miss), as in refresh() above.
        val resolved = handle ?: when (val found = service.resolveBookSource(book)) {
            is AppResult.Err -> return found
            is AppResult.Ok -> found.value
        }
        val ref = SourceBook(
            id = remoteId,
            title = book.title,
            author = book.author.ifBlank { null },
            description = book.description.ifBlank { null },
            // v1.5.3 (finding #3, item 8): the row-built ref
            // carries the book's stored cover URL — without
            // it, a refresh whose details read came back
            // coverless had nothing to fall back to and the
            // cover heal never even attempted its fetch.
            coverUrl = book.coverUrl
        )
        var effectiveBook = book
        // v1.5.26: the background gate (see [backgroundWork]).
        // A bulk/background refresh of a player book lands its
        // DETAILS only and returns: no fused fetch is started,
        // so no chapter walk launches and no extension toast
        // can fire over an unrelated screen. The stored chapter
        // list and its stamps stand exactly as they were; the
        // walk runs on the book's next foreground load.
        if (resolved.source is MihonSourceAdapter && backgroundWork()) {
            return when (
                val detailsOnly = service.guarded(resolved) { source ->
                    (source as MihonSourceAdapter)
                        .details(ref, book.id.toString())
                }
            ) {
                is AppResult.Ok -> AppResult.Ok(
                    applyDetails(effectiveBook, detailsOnly.value)
                )
                is AppResult.Err -> detailsOnly
            }
        }
        // v1.5.37 (combined build, S3): the head-check delta.
        // A declarative/live refresh first tries ONE book-page
        // fetch (see tryHeadDeltaRefresh): when the stored
        // list's head is provably current, the refresh settles
        // there — no pagination walk at all. Anything the
        // head-check can't prove falls through to the full
        // fused fetch below, exactly as before. Player books
        // never enter: their fused lane is untouched.
        if (resolved.source is DeclarativeSource ||
            resolved.source is LiveSource
        ) {
            val delta = tryHeadDeltaRefresh(book, resolved, ref)
            if (delta != null) return delta
        }
        var details: SourceBook
        var chapters: List<app.xreader.extensionapi.SourceChapter>
        // v1.5.4 (WS-1 item 5): both halves start together;
        // details is awaited FIRST and — including its cover
        // fetch — applied and persisted as soon as IT lands,
        // independent of the chapters outcome: a chapters
        // failure, and even the page bound cutting the
        // chapters half off, leaves the details half landed.
        // (Pre-fix, the pair was a sequential all-or-nothing:
        // a chapters failure discarded details the page had
        // already fetched.) applyDetails is idempotent, so
        // the normal path below re-applying it costs nothing.
        val outcomes = if (resolved.source is MihonSourceAdapter) {
            // v1.5.8 (A1): the player branch's split bound. The
            // fused fetch is started in [completionScope] — NOT
            // in a scope this refresh owns — so the deferred
            // (and the adapter registry call beneath it)
            // SURVIVES this refresh returning. Only the WAIT is
            // bounded by [pageLoadTimeoutMs]: details are
            // awaited (and applied) first inside the bound; if
            // the bound expires with chapters still in flight,
            // the completion consumer is registered on the
            // surviving deferred and refresh returns the
            // details-landed book with chapters still loading —
            // not Err, not a completeness stamp.
            val pending = with(completionScope) {
                startBookPageFetch(
                    resolved, ref, stableKey = book.id.toString()
                )
            }
            val awaited = withTimeoutOrNull(pageLoadTimeoutMs) {
                val detailsFirst = pending.details.await()
                if (detailsFirst is AppResult.Ok) {
                    effectiveBook = applyDetails(effectiveBook, detailsFirst.value)
                }
                detailsFirst to pending.chapters.await()
            }
            if (awaited == null) {
                registerPlayerCompletionConsumer(book.id, pending)
                return AppResult.Ok(bookDao.getById(book.id) ?: effectiveBook)
            }
            awaited
        } else {
            supervisorScope {
                // v1.5.5 (§1): this fetch is for a PERSISTED book —
                // its Room id is the registry's stable key
                // (Amendments, Hole B).
                val pending = startBookPageFetch(
                    resolved, ref, stableKey = book.id.toString()
                )
                try {
                    val detailsFirst = pending.details.await()
                    if (detailsFirst is AppResult.Ok) {
                        effectiveBook = applyDetails(effectiveBook, detailsFirst.value)
                    }
                    detailsFirst to pending.chapters.await()
                } catch (e: CancellationException) {
                    // The supervisor scope does not propagate
                    // this cancellation to the halves — left
                    // alone, a parked chapters call would hold
                    // the scope (and the refresh) open until its
                    // own bridge deadline, and the item-3 page
                    // bound would never land. Stop them, then
                    // let the cancellation stand.
                    // v1.5.37 (S7): a JOINED pending is another
                    // refresh's walk — this caller's bound must
                    // not cancel it; the walk settles for its
                    // owner, whose own bound governs it.
                    if (!pending.joinedFlight) pending.cancel()
                    throw e
                }
            }
        }
        val detailsOutcome = outcomes.first
        val chaptersOutcome = outcomes.second
        when {
            detailsOutcome is AppResult.Ok &&
                chaptersOutcome is AppResult.Ok -> {
                details = detailsOutcome.value
                chapters = chaptersOutcome.value
            }
            detailsOutcome is AppResult.Ok -> {
                // Chapters failed; details + cover already
                // landed above. The chapters failure is the
                // refresh's verdict.
                return chaptersOutcome as AppResult.Err
            }
            else -> {
                // v1.5.0 (steer A6): the re-link ladder's
                // second rung — the bound BOOK address itself
                // is dead (the series moved or was re-posted
                // under a new release). Before the failure is
                // allowed to stand, title-search re-bind to a
                // same-title release that actually has
                // chapters (the winner must at least cover
                // the chapters this book already holds). Only
                // a dead-book-shaped error climbs the ladder —
                // a rate limit or a network failure is not a
                // moved book and keeps its honest error.
                val pairError = (detailsOutcome as AppResult.Err).error
                val detail = pairError.detail?.lowercase().orEmpty()
                val deadBook = "wasn't found" in detail ||
                    "not found" in detail ||
                    "http 404" in detail ||
                    "no longer" in detail
                val rebound = if (deadBook) {
                    rebindToFullerRelease(
                        resolved, book, sourceId, remoteId,
                        minChapters = maxOf(
                            1, chapterDao.forBook(book.id).size
                        )
                    )
                } else {
                    null
                }
                if (rebound == null) return AppResult.Err(pairError)
                effectiveBook = rebound.first
                details = rebound.second.first
                chapters = rebound.second.second
            }
        }
        // v1.5.0 (A5): MULTI-RELEASE BINDING. Some sites carry
        // one series in several releases (multiple publishers
        // — the Comix exhibit: the opened release had 0
        // chapters while a sibling release carried the full
        // run). A book whose bound release resolves to ZERO
        // chapters while the book has no stored chapters is
        // bound to the wrong release: search the source for
        // the same title ONCE, and if a sibling release
        // actually has chapters, rebind the book to the
        // fullest one (same local row, same progress — only
        // the release address changes) and read on that
        // release. A 0-chapter resolution while a sibling has
        // chapters is a failed binding, never a success.
        if (chapters.isEmpty() && chapterDao.forBook(book.id).isEmpty()) {
            val rebound = rebindToFullerRelease(resolved, book, sourceId, remoteId)
            if (rebound != null) {
                effectiveBook = rebound.first
                details = rebound.second.first
                chapters = rebound.second.second
            }
        }
        // v1.5.0 (steer A7): zero chapters is never stored
        // as success — and never as destruction either. A
        // refresh that came back with an EMPTY list while
        // the book already holds chapters keeps the stored
        // list and its totals exactly as they are (a walled
        // or drifted fetch must not erase a library); only
        // the details half lands.
        val storedChapters = chapterDao.forBook(book.id)
        if (chapters.isEmpty() && storedChapters.isNotEmpty()) {
            val detailsOnly = applyDetails(effectiveBook, details)
            return AppResult.Ok(detailsOnly)
        }
        val now = nowMs()
        val withDetails = applyDetails(effectiveBook, details)
        // v1.3.3 (V132-CHECK 21): a refresh whose walk was
        // cut by the time budget gathered a PARTIAL list —
        // it lands (reading beats nothing), but the book
        // must not claim completeness it didn't reach: the
        // flag stays false and a later demand resumes the
        // list instead of trusting the truncated count.
        val walkTruncated = when (val src = resolved.source) {
            is DeclarativeSource -> src.lastWalkTruncated
            // v1.5.7 (Finding 5): a Live book's walk runs on an
            // ephemeral declarative delegate — its truncation
            // flag reaches the sync through the passthrough.
            // (Pre-fix the cast above missed Live entirely, so
            // a truncated Live walk also read as complete.)
            is LiveSource -> src.lastWalkTruncated
            else -> false
        }
        // v1.5.8 (D2 rule 3): WHICH ROAD supplied this walk's
        // rows — the provenance the completeness gate below
        // now requires, read beside the truncation flag (Live
        // through its delegate passthrough, like the flag).
        // A FALLBACK-substituted or UNPROVEN harvest testifies
        // nothing about this book: it is stored as NO chapters
        // (the apply below is skipped entirely), never
        // drop-reconciled, and neither completeness nor the
        // total is stamped from it — the stored list and its
        // stamps stand exactly as they were, and only the
        // details half of this refresh lands.
        val walkSuppliedBy = when (val src = resolved.source) {
            is DeclarativeSource -> src.lastWalkSuppliedBy
            is LiveSource -> src.lastWalkSuppliedBy
            else -> null
        }
        val provenanceUnproven =
            walkSuppliedBy == ChapterSuppliedBy.FALLBACK ||
                walkSuppliedBy == ChapterSuppliedBy.UNPROVEN
        // v1.5.7 (Finding 5, PRIMARY — the completeness gate):
        // whether THIS fetched list is PROVEN complete decides
        // whether [ChapterReconciler.reconcile]'s drop
        // semantics may run at all. reconcile drops every
        // disposable stored row the fetched list doesn't
        // mention — safe only against a proven-complete list:
        //  - Declarative / Live: proven exactly when the
        //    chapter walk was NOT cut by its time budget. A
        //    truncated walk of a reversed (newest-first)
        //    source — the Novelight family — is missing the
        //    OLDEST chapters, and the pre-fix refresh
        //    reconciled it anyway, DELETING the head of the
        //    stored list (and firing unattended through
        //    StoredBookRepair's repair refreshes, so a heal
        //    could re-truncate what it was restoring).
        //  - Player (MihonSourceAdapter): proven by the donor
        //    contract — the fused detailsAndChapters call is
        //    ONE getMangaUpdate whose failure surfaces as an
        //    Err above (never a partial list), and it returns
        //    the manga's full chapter list: this path has no
        //    window, page parameter or budget walk that could
        //    produce a partial list.
        //  - JS / Dex / any other Source: NEVER proven — the
        //    extension API's chapters() returns a bare list
        //    with no total count and no completeness flag,
        //    and the code behind it is arbitrary, so a
        //    windowed list is indistinguishable from a
        //    complete one at this seam.
        // An unproven list lands through [noDropPlan]: it can
        // add and alias chapters, it can never delete the
        // beginning of a book.
        val listProvenComplete = when (resolved.source) {
            // v1.5.8 (D2 rule 3): provenance first — a
            // fallback/unproven harvest is never proven,
            // however un-truncated its walk was.
            is DeclarativeSource, is LiveSource ->
                !provenanceUnproven && !walkTruncated
            // v1.5.8 (A3): a player list is proven complete ONLY
            // when it is non-empty. The fused call's atomicity
            // proves a non-empty list is the manga's full list;
            // an EMPTY answer proves nothing — stamping it
            // complete (the pre-fix hard-true) froze an open
            // question as a verdict and the book never retried.
            is MihonSourceAdapter -> chapters.isNotEmpty()
            else -> false
        }
        // v1.5.39 (item 1): the whole plan+apply section —
        // the stored-list read, the plan computed from it,
        // the book-row stamps and applyPlan — runs under the
        // book's mutex, with `existing` read INSIDE the lock.
        // Two refreshes of one book can no longer plan from
        // the same pre-state (V1538-CRASH-FORENSICS §4). The
        // fetches that produced [chapters] and [withDetails]
        // stay outside: the lock guards the merge, not the
        // network.
        val updated = bookPlanMutex(book.id).withLock {
            val existing = chapterDao.forBook(book.id)
            // v1.0.2 (R03): chapter identity goes through the single
            // reconciler — multi-key matching (remoteId → canonical id →
            // position+title → number → title), so a source changing its
            // chapter URLs aliases the EXISTING rows instead of inserting
            // a colliding second list (the "chapter isn't in the book
            // anymore" defect). Stale rows with user content survive.
            // v1.5.16: the plan computation NEVER runs on the
            // caller's dispatcher. refresh reaches this function
            // from main-confined scopes, and the reconcile's
            // matching loop over a large list is CPU-heavy — on
            // the main thread it starved input until Android
            // ANR'd (device record 2026-11-06: KomikkuReaderActivity
            // input timeout, main inside ChapterReconciler via
            // this call). The inputs are immutable snapshots —
            // [existing] is a fresh list of all-val Chapter rows
            // from the DAO, [chapters] the fetched all-val
            // SourceChapter list — and reconcile/noDropPlan are
            // pure planners, so the plan is computed on
            // Dispatchers.Default. Everything before and after
            // (fetches, DB writes, stamps, state) is unchanged.
            val plan = withContext(Dispatchers.Default) {
                if (listProvenComplete) {
                    ChapterReconciler.reconcile(existing, chapters)
                } else {
                    noDropPlan(existing, chapters)
                }
            }
            // v1.0.8: a full refresh IS the complete list — the
            // progressive walk state resets.
            // v1.5.5 (§2, epoch hygiene): a refresh that COMPLETES
            // the list stamps the definition epoch it was proven
            // under; a truncated walk keeps the old stamp, so its
            // completeness stays distrusted until a full sync
            // lands. Non-bundled sources carry no epoch and keep
            // whatever stamp they had (the field is never read
            // for them).
            val refreshEpoch = definitionEpochFor(extensionId)
            val stamped = withDetails.copy(
                // A proven list's size IS the book's total. An
                // unproven list's size is not — the merged store
                // (stored rows + genuinely new ones) is. Pre-fix, a
                // truncated refresh stamped the partial count, so
                // the book's rows disagreed with its own stated
                // total: the exact symptom StoredRepairPolicy
                // repairs on, re-created by the repair refresh
                // itself.
                // v1.5.8 (A3): an EMPTY player update skips the
                // total stamp entirely — the stored total (or its
                // unstamped absence) stands, like completeness.
                totalChapters = when {
                    // v1.5.8 (D2 rule 3): a fallback/unproven pass
                    // stamps NO total — the stored total stands.
                    provenanceUnproven -> withDetails.totalChapters
                    resolved.source is MihonSourceAdapter &&
                        chapters.isEmpty() -> withDetails.totalChapters
                    listProvenComplete -> chapters.size
                    else -> existing.size + plan.inserts.size
                },
                // The flag keeps its v1.3.3 meaning — it gates the
                // progressive-walk machinery only declarative
                // sources have. The DROP gate is listProvenComplete
                // above, not this flag.
                // v1.5.8 (A3): an EMPTY player update skips the
                // completeness stamp too — the book stays an open
                // question and the next open retries (A2 makes it).
                chapterListComplete =
                    if (provenanceUnproven) {
                        // v1.5.8 (D2 rule 3): never stamped from a
                        // fallback/unproven pass.
                        withDetails.chapterListComplete
                    } else if (resolved.source is MihonSourceAdapter &&
                        chapters.isEmpty()
                    ) {
                        withDetails.chapterListComplete
                    } else {
                        !walkTruncated
                    },
                // v1.5.8 (D2 rule 3): an unproven pass does not
                // reset the walk cursor either — it proved nothing
                // about where the list stands.
                chapterWalkNextPage = if (provenanceUnproven) {
                    withDetails.chapterWalkNextPage
                } else {
                    0
                },
                chapterWalkTotalPages = if (provenanceUnproven) {
                    withDetails.chapterWalkTotalPages
                } else {
                    0
                },
                chapterWalkPageSize = if (provenanceUnproven) {
                    withDetails.chapterWalkPageSize
                } else {
                    0
                },
                syncedEpoch = if (!walkTruncated && !provenanceUnproven &&
                    refreshEpoch != null
                ) {
                    refreshEpoch
                } else {
                    withDetails.syncedEpoch
                }
            )
            bookDao.update(stamped)
            // v1.5.8 (D2 rule 3): a fallback/unproven harvest is
            // stored as NO chapters — its rows never reach the
            // chapter table at all (not even through the no-drop
            // merge, which would still INSERT furniture rows).
            if (!provenanceUnproven) {
                applyPlan(book.id, plan, now)
            }
            stamped
        }
        // v1.3.0 delta: failover maps build in the BACKGROUND —
        // never on the path that opened or refreshed the book.
        failoverScheduler(updated)
        return AppResult.Ok(updated)
    }

    /**
     * v1.5.8 (A1): register the completion consumer for a
     * player book-page fetch whose chapters outlived the page
     * bound. When the surviving deferred lands, the chapters
     * are applied EXACTLY as the in-bound path applies them —
     * details merge, reconcile, stamps — under A3's rules (an
     * empty completion stamps nothing). A failed completion
     * applies nothing: the next open re-joins or retries, and
     * A3's seam record already carries the failure's wording.
     */
    private fun registerPlayerCompletionConsumer(
        bookId: Long,
        pending: PendingBookPage
    ) {
        // v1.5.10 (B1): one consumer per pending read — a
        // re-joining refresh's detach must not register a
        // second one (see PendingBookPage's flag).
        if (!pending.completionConsumerRegistered
                .compareAndSet(false, true)
        ) {
            return
        }
        completionScope.launch {
            val chaptersOutcome = runCatching {
                pending.chapters.await()
            }.getOrNull() ?: return@launch
            val detailsOutcome = runCatching {
                pending.details.await()
            }.getOrNull() ?: return@launch
            if (detailsOutcome is AppResult.Ok &&
                chaptersOutcome is AppResult.Ok
            ) {
                runCatching {
                    applyCompletedPlayerChapters(
                        bookId, detailsOutcome.value, chaptersOutcome.value
                    )
                }
                // v1.5.10 (B1): chapters landing clears the
                // wait — landing point 2 of 2 (the detached
                // completion consumer's apply).
                if (chapterDao.forBook(bookId).isNotEmpty()) {
                    chapterWaits.markLanded(bookId)
                }
            }
        }
    }

    /** The completion consumer's apply (A1): the in-bound
     * refresh's chapter half, run late. Mirrors its rules —
     * an empty list never erases a stored one, and under A3
     * an empty completion skips both stamps entirely. */
    private suspend fun applyCompletedPlayerChapters(
        bookId: Long,
        details: SourceBook,
        chapters: List<app.xreader.extensionapi.SourceChapter>
    ) {
        val book = bookDao.getById(bookId) ?: return
        val withDetails = applyDetails(book, details)
        if (chapters.isEmpty()) {
            // A3: details landed; completeness/total unstamped.
            return
        }
        val now = nowMs()
        // v1.5.39 (item 1): the same per-book serialisation
        // as the refresh paths — this consumer applies a
        // plan+apply for the same book and races them by the
        // same shape (V1538-CRASH-FORENSICS §4).
        val updated = bookPlanMutex(bookId).withLock {
            val existing = chapterDao.forBook(bookId)
            val plan = ChapterReconciler.reconcile(existing, chapters)
            val stamped = withDetails.copy(
                totalChapters = chapters.size,
                chapterListComplete = true,
                chapterWalkNextPage = 0,
                chapterWalkTotalPages = 0,
                chapterWalkPageSize = 0
            )
            bookDao.update(stamped)
            applyPlan(bookId, plan, now)
            stamped
        }
        failoverScheduler(updated)
    }

    /**
     * v1.5.7 (Finding 5): the NO-DROP merge for a fetched
     * chapter list that is NOT proven complete (the
     * [refreshWithinBound] completeness gate). The plan comes
     * from [ChapterReconciler.reconcilePartial] — the
     * append-only planner, which never drops a stored row —
     * with its positions remapped before it lands:
     *
     * reconcilePartial assigns FETCHED positions as stored
     * indices, which is exact for its designed input — a
     * reading-order PREFIX that re-confirms stored rows from
     * position 0 (the progressive chunk road, untouched). An
     * unproven full-refresh list may instead be a
     * reading-order SUFFIX: a truncated newest-first walk
     * gathered the newest chapters and never reached the
     * oldest, so its fetched position 0 is NOT the book's
     * chapter 1. Landing those positions verbatim would move
     * the newest chapters onto the head rows' indices —
     * colliding with the untouched head rows on the unique
     * (bookId, index) index and corrupting the order even
     * where it didn't collide. So:
     *  - a matched row KEEPS its stored index (a partial
     *    fetch can alias a chapter — new title / URL / date —
     *    but it cannot prove a new global position for it);
     *  - a genuinely new chapter APPENDS after the stored
     *    list's last index, in fetched order (for the
     *    truncated newest-first walk those new chapters ARE
     *    the book's newest, so the tail is their true home;
     *    any other placement settles on the next
     *    proven-complete refresh, whose reconcile re-indexes
     *    the whole list atomically).
     * Stored rows the plan doesn't mention stay exactly as
     * they are, and the stored list's index contiguity is
     * preserved whenever it had it — the book page's
     * ChapterListIntegrity invariant composes with this merge
     * instead of being re-broken by it.
     */
    private fun noDropPlan(
        existing: List<Chapter>,
        chapters: List<app.xreader.extensionapi.SourceChapter>
    ): ChapterReconciler.Plan {
        val partial = ChapterReconciler.reconcilePartial(existing, chapters)
        if (partial.updates.isEmpty() && partial.inserts.isEmpty()) {
            return partial
        }
        var nextIndex = (existing.maxOfOrNull { it.index } ?: -1) + 1
        return partial.copy(
            updates = partial.updates.map { update ->
                update.copy(newIndex = update.row.index)
            },
            inserts = partial.inserts.map { insert ->
                insert.copy(index = nextIndex++)
            }
        )
    }

    /** The two halves of one book-page read, STARTED together
     * and awaited independently (v1.5.4, WS-1 item 5): either
     * half may land while the other fails or is cut off, and
     * [refresh] awaits details FIRST so its apply lands even
     * while chapters are still in flight. */
    private class PendingBookPage(
        val details: Deferred<AppResult<SourceBook>>,
        val chapters: Deferred<AppResult<List<app.xreader.extensionapi.SourceChapter>>>,
        /** The declarative/live branch's shared one-fetch
         * read, when the two halves are views over it:
         * cancelling the views alone would leave it running. */
        val shared: Deferred<*>? = null,
        /** v1.5.37 (combined build, S7): true on the VIEW of
         * a shared pending handed to a caller that JOINED an
         * in-flight fetch another refresh started — its page
         * bound must not cancel the shared walk out from
         * under the other caller (see
         * [startDeclarativeBookPageFetch]). */
        val joinedFlight: Boolean = false
    ) {
        /** A joined view of this pending: the same
         * deferreds, marked so the joiner's page bound never
         * cancels the shared fetch. */
        fun asJoined(): PendingBookPage =
            if (joinedFlight) this else PendingBookPage(
                details = details,
                chapters = chapters,
                shared = shared,
                joinedFlight = true
            )
        /** v1.5.10 (B1): the player completion consumer is
         * registered AT MOST ONCE per pending read. Every
         * refresh that detaches at the page bound reaches the
         * registration site; because startBookPageFetch
         * single-flights the pending per in-flight walk (see
         * inFlightPlayerPages), a re-joining refresh (Retry)
         * detaches from THIS SAME pending — this guard is
         * what stops its detach registering a second consumer
         * whose landing would race the first's apply. */
        val completionConsumerRegistered =
            java.util.concurrent.atomic.AtomicBoolean(false)
        /** Stop everything this pending read has in flight.
         * A supervisor scope does NOT propagate its caller's
         * cancellation to its children, so the page bound
         * (item 3) cancels the halves explicitly here:
         * cancelling a half's deferred reaches the player
         * bridge through guarded's published job and
         * interrupts the worker; a declarative half's
         * in-flight fetch stays bounded by its client's own
         * timeouts, as before. */
        fun cancel() {
            shared?.cancel()
            details.cancel()
            chapters.cancel()
        }
    }

    /** Start the one details+chapters read [refresh] and the
     * multi-release rebind share: the combined one-fetch read
     * for declarative/live sources (one document, two
     * products — the halves share its outcome), the ONE fused
     * call for player sources, the two-call read for the
     * remaining (JS/Dex) source kinds.
     *
     * v1.5.4 (item 5a): for player/JS/Dex sources the two
     * calls run CONCURRENTLY — pre-fix they were a ladder
     * (details, THEN chapters), so a slow or hanging chapters
     * call starved a details result that was already there,
     * and each call paid the other's latency. guarded()
     * converts failures to Err values, so neither child can
     * fail the other; the only cancellation that reaches them
     * is the caller's (the page bound), which stops both.
     *
     * v1.5.5 (§1) — the player branch is SUPERSEDED: for a
     * Keiyoushi source the two calls are the SAME donor
     * operation (`getMangaUpdate`), and the donor forbids
     * their overlap outright (its base class check()s
     * against any in-flight call for the same manga — the
     * v1.5.4 proof failure). The player branch therefore
     * starts ONE fused adapter call as a shared Deferred,
     * exactly like the declarative branch's one-fetch read:
     * the two halves are separately awaitable views over it
     * (details still applies first, independently of the
     * chapters outcome, once the result lands), and if the
     * fused call throws, both halves surface that same
     * failure — the donor's atomic semantics. JS/Dex sources
     * keep the item-5a concurrent pair: their details and
     * chapters calls are genuinely independent fetches.
     * [stableKey] is the persisted Book's Room id when the
     * caller has one (refresh passes its book's id; the
     * rebind's candidate fetches pass null — candidates are
     * other releases, not this row). */
    private fun CoroutineScope.startBookPageFetch(
        resolved: SourceHandle,
        ref: SourceBook,
        stableKey: String? = null
    ): PendingBookPage =
        if (resolved.source is MihonSourceAdapter) {
            startPlayerBookPageFetch(resolved, ref, stableKey)
        } else if (resolved.source is DeclarativeSource ||
            resolved.source is LiveSource
        ) {
            startDeclarativeBookPageFetch(resolved, ref, stableKey)
        } else {
            PendingBookPage(
                details = async { service.guarded(resolved) { it.details(ref) } },
                chapters = async { service.guarded(resolved) { it.chapters(ref) } }
            )
        }

    /** The player branch of [startBookPageFetch], as a
     * block-bodied helper so the single-flight can return
     * the shared pending directly.
     *
     * v1.5.10 (B1): ONE pending per in-flight player walk
     * (see [inFlightPlayerPages]). startBookPageFetch used
     * to mint a fresh PendingBookPage on EVERY refresh: a
     * re-joining refresh (Retry) then held a DIFFERENT
     * pending than the detached first refresh, each detach
     * registered its own completion consumer, and when the
     * walk landed the consumers raced the same apply —
     * double-landing chapters (gate:
     * V1510ChapterWaitBoundTest, a 2-chapter completion
     * landing as 3–4 rows). The adapter's registry already
     * single-flights the source call beneath; the consumer
     * lives up here, so the pending it hangs off must
     * single-flight too. Unkeyed fetches (the rebind's
     * candidates) never share. */
    private fun CoroutineScope.startPlayerBookPageFetch(
        resolved: SourceHandle,
        ref: SourceBook,
        stableKey: String?
    ): PendingBookPage {
        if (stableKey != null) {
            inFlightPlayerPages[stableKey]?.let { return it }
        }
        val both = async {
            val result = service.guarded(resolved) { source ->
                (source as MihonSourceAdapter)
                    .detailsAndChapters(ref, stableKey)
            }
            // v1.5.8 (A3): every book-page sync outcome is
            // recorded at this guarded seam.
            recordBookPageSyncOutcome(result)
            result
        }
        val pending = PendingBookPage(
            details = async { both.await().map { it.first } },
            chapters = async { both.await().map { it.second } },
            shared = both
        )
        if (stableKey != null) {
            val raced = inFlightPlayerPages.putIfAbsent(
                stableKey, pending
            )
            if (raced != null) {
                // Another refresh started this walk first:
                // cancel our duplicate wrappers (the adapter
                // registry call beneath survives — it is
                // keyed, not owned by the wrapper) and hand
                // back the winner's pending.
                pending.cancel()
                return raced
            }
            both.invokeOnCompletion {
                inFlightPlayerPages.remove(stableKey, pending)
            }
        }
        return pending
    }

    /** The declarative/live branch of [startBookPageFetch],
     * as a block-bodied helper so the single-flight can
     * return the shared pending directly.
     *
     * v1.5.37 (combined build, S7): ONE pending per in-flight
     * declarative/live walk (see [inFlightDeclarativePages]),
     * mirroring the player registry above. A caller that
     * finds its book's walk already running JOINS it and
     * receives a joined view of the winner's pending — the
     * joiner's own page bound then never cancels the shared
     * walk (see refresh's await). Unkeyed fetches (the
     * rebind's candidates) never share. */
    private fun CoroutineScope.startDeclarativeBookPageFetch(
        resolved: SourceHandle,
        ref: SourceBook,
        stableKey: String?
    ): PendingBookPage {
        if (stableKey != null) {
            inFlightDeclarativePages[stableKey]?.let {
                return it.asJoined()
            }
        }
        // v1.2.0 (A2): Live sources take the ONE-fetch
        // combined read too — they have had a
        // detailsAndChapters all along; falling back to
        // separate details() + chapters() calls fetched
        // the book page once per call on top of the
        // derivation fetch.
        val both = async {
            val result = service.guarded(resolved) { source ->
                when (source) {
                    is DeclarativeSource -> source.detailsAndChapters(ref)
                    is LiveSource -> source.detailsAndChapters(ref)
                    else -> error("unreachable: guarded source kind changed")
                }
            }
            // v1.5.8 (A3): every book-page sync outcome is
            // recorded at this guarded seam.
            recordBookPageSyncOutcome(result)
            result
        }
        val pending = PendingBookPage(
            details = async { both.await().map { it.first } },
            chapters = async { both.await().map { it.second } },
            shared = both
        )
        if (stableKey != null) {
            val raced = inFlightDeclarativePages.putIfAbsent(
                stableKey, pending
            )
            if (raced != null) {
                // Another refresh started this walk first:
                // cancel our duplicate (a plain client fetch,
                // bounded by its own timeouts) and hand back
                // the winner's pending, joined.
                pending.cancel()
                return raced.asJoined()
            }
            both.invokeOnCompletion {
                inFlightDeclarativePages.remove(stableKey, pending)
            }
        }
        return pending
    }

    /**
     * v1.5.8 (A3): record ONE book-page sync outcome in the
     * DiagnosticLog at the guarded seam — tag `sync`, the
     * AppError detail VERBATIM on failure. Pre-fix the three
     * realities (threw / returned empty / never ran) were
     * flattened into one headline pill and nothing at this
     * seam was recorded anywhere; now the record exists for
     * K5's surfaces to show. No behaviour change beyond the
     * record.
     */
    private fun recordBookPageSyncOutcome(
        result: AppResult<Pair<SourceBook, List<app.xreader.extensionapi.SourceChapter>>>
    ) {
        when (result) {
            is AppResult.Ok -> DiagnosticLog.record(
                "sync",
                "book page fetch landed: ${result.value.second.size} chapters"
            )
            is AppResult.Err -> DiagnosticLog.record(
                "sync",
                "book page fetch failed",
                result.error.detail ?: result.error.toString()
            )
        }
    }

    /** Both outcomes of a started book-page read, awaited
     * together (the rebind's shape — it needs the pair).
     * The rebind's candidates are NOT the persisted row, so
     * they fetch with no stable key (the registry falls
     * back to the (sourceId, url) key). */
    private suspend fun fetchBookPageOutcomes(
        resolved: SourceHandle,
        ref: SourceBook
    ): Pair<AppResult<SourceBook>, AppResult<List<app.xreader.extensionapi.SourceChapter>>> =
        supervisorScope {
            val pending = startBookPageFetch(resolved, ref, stableKey = null)
            try {
                pending.details.await() to pending.chapters.await()
            } catch (e: CancellationException) {
                // Same bound discipline as refresh's own
                // await above: the supervisor scope would
                // otherwise hold the caller open on a parked
                // half (this ladder runs inside the refresh
                // bound).
                pending.cancel()
                throw e
            }
        }

    /**
     * v1.5.0 (A5): the multi-release rebind. Searches the
     * source for the book's own title, tries up to two
     * same-title candidates at OTHER addresses, and returns
     * the rebound book row (new release address persisted,
     * same local id) with the fullest candidate's
     * details+chapters — or null when no sibling release has
     * chapters either (the book then keeps its honest
     * 0-chapter state on its original release).
     */
    private suspend fun rebindToFullerRelease(
        resolved: SourceHandle,
        book: Book,
        sourceId: String,
        failedRemoteId: String,
        minChapters: Int = 1
    ): Pair<Book, Pair<SourceBook, List<app.xreader.extensionapi.SourceChapter>>>? {
        val search = withContext(Dispatchers.IO) {
            service.guarded(resolved) { it.search(book.title, 1) }
        }
        val results = (search as? AppResult.Ok)?.value?.books
            ?: return null
        val wanted = releaseTitleKey(book.title)
        val candidates = results.filter { candidate ->
            candidate.id != failedRemoteId &&
                releaseTitleKey(candidate.title) == wanted &&
                wanted.isNotEmpty()
        }.take(2)
        var best: Triple<SourceBook, SourceBook,
            List<app.xreader.extensionapi.SourceChapter>>? = null
        for (candidate in candidates) {
            val fetched = fetchBookPageOutcomes(resolved, candidate)
            // A rebind candidate counts only with BOTH halves
            // in hand (the pre-v1.5.4 pair rule, kept under
            // item 5's independent outcomes).
            val candidateDetails =
                (fetched.first as? AppResult.Ok)?.value ?: continue
            val candidateChapters =
                (fetched.second as? AppResult.Ok)?.value ?: continue
            if (candidateChapters.size >= minChapters &&
                (best == null || candidateChapters.size > best.third.size)
            ) {
                best = Triple(candidate, candidateDetails, candidateChapters)
            }
        }
        val winner = best ?: return null
        val rebound = book.copy(
            remoteId = SourceBookIds.encode(sourceId, winner.first.id)
        )
        bookDao.update(rebound)
        return rebound to (winner.second to winner.third)
    }

    /** Same-series releases share a title modulo case,
     * spacing and punctuation ("The Greatest Estate
     * Developer" from two publishers is one series). */
    private fun releaseTitleKey(title: String): String =
        title.lowercase().filter { it.isLetterOrDigit() }

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
        // v1.5.2 (§14.1): store the canonical URL whenever the
        // details carry one — the file fetch may fail, the URL
        // must not be thrown away with it.
        if (!coverUrl.isNullOrBlank() && updated.coverUrl != coverUrl) {
            updated = updated.copy(coverUrl = coverUrl)
        }
        // v1.5.2 (§7): tier-1 evidence at catalogue/book refresh
        // re-files the book (the ONE tier-1 trigger) — routed
        // through BookKind152.applyTier1 (tier 1 always overrides).
        updated = updated.copy(
            kind = BookKind152.applyTier1(updated.kind, details.kind)
        )
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
     * drops or kept-stale moves).
     *
     * v1.5.39 (item 1): REPLAY-SAFE. Inside the transaction,
     * after this plan's own drops and moves have landed, the
     * insert list is re-checked against the table as it NOW
     * stands: an insert whose chapter already landed (same
     * canonical id at its target index — the mark of a plan
     * computed from the same pre-state by a refresh that
     * finished first) is dropped, and an insert whose target
     * index is held by a DIFFERENT chapter is reassigned to
     * append after the current maximum. A stale plan can
     * therefore never throw on the (bookId, index) unique
     * index nor duplicate a row (V1538-CRASH-FORENSICS §4).
     * ChapterDao.insert stays ABORT — never REPLACE, which
     * would change row ids and orphan downloads/progress.
     * `internal` so the JVM gate can drive the fixture
     * directly (unit tests are a friend module). */
    internal suspend fun applyPlan(
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
                    update.row.dateText != update.newDateText ||
                    update.row.scanlator != update.newScanlator ||
                    update.row.memo != update.newMemo
                if (changed) {
                    chapterDao.update(
                        update.row.copy(
                            index = update.newIndex,
                            title = update.newTitle,
                            remoteId = update.newRemoteId,
                            updatedAt = now,
                            dateText = update.newDateText,
                            // v1.5.8 (E1): the credit lands on
                            // the row with the rest of the alias.
                            scanlator = update.newScanlator,
                            // v1.5.11 (transplant, Verdict 1): the
                            // memo lands with it — the donor's
                            // DB round-trip for SChapter.memo.
                            memo = update.newMemo
                        )
                    )
                }
            }
            // v1.5.39 (item 1): the replay filter (see the
            // doc above) — the table is re-read HERE, inside
            // the transaction, after this plan's own drops
            // and moves have landed, so the check sees the
            // true occupants of every target index.
            val landedByIndex = chapterDao.forBook(bookId)
                .associateBy { it.index }
            var appendCursor = maxOf(
                landedByIndex.keys.maxOrNull() ?: -1,
                plan.inserts.maxOfOrNull { it.index } ?: -1
            ) + 1
            val safeInserts = plan.inserts.mapNotNull { insert ->
                val occupant = landedByIndex[insert.index]
                when {
                    occupant == null -> insert
                    ChapterIdentity.canonicalKey(
                        occupant.remoteId ?: ""
                    ) == ChapterIdentity.canonicalKey(
                        insert.fetched.id
                    ) -> null // already landed — drop the replay
                    else -> insert.copy(index = appendCursor++)
                }
            }
            safeInserts.forEach { insert ->
                chapterDao.insert(
                    Chapter(
                        bookId = bookId,
                        index = insert.index,
                        title = insert.fetched.name,
                        remoteId = insert.fetched.id,
                        updatedAt = now,
                        dateText = insert.fetched.dateText,
                        // v1.5.8 (E1): the credit lands at birth.
                        scanlator = insert.fetched.scanlator,
                        // v1.5.11 (transplant, Verdict 1): the
                        // memo lands at birth too.
                        memo = insert.fetched.memo
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

    /**
     * v1.5.5 (§2, epoch hygiene): the definition epoch a
     * book's source row CURRENTLY declares. Bundled-provenance
     * rows (packageId "bundled.*") carry the app-wide bundled
     * definitions epoch; every other provenance — learned,
     * link-added, repo-installed, player — has NO definition
     * epoch anywhere in the app, so it is treated as
     * always-current (null here) and the epoch rule never
     * engages for it.
     */
    private suspend fun definitionEpochFor(extensionId: Long): Int? {
        val row = extensionDao.getById(extensionId) ?: return null
        return if (row.packageId.startsWith("bundled.")) {
            BundledDefaults.BUNDLED_DEFINITIONS_EPOCH
        } else {
            null
        }
    }

    private suspend fun ensureChaptersLocked(
        book: Book,
        atLeast: Int
    ): AppResult<Book> {
        var fresh = bookDao.getById(book.id) ?: return AppResult.Err(
            AppError.StorageFailed("finding the book")
        )
        // v1.5.5 (§2, epoch hygiene): a book last completed
        // under an OLDER definition epoch counts as NOT
        // complete, and its saved walk cursor is discarded
        // BEFORE the sync proceeds — the definitions it was
        // walked under are gone, so neither the completeness
        // stamp nor the cursor position means anything. The
        // stored chapter ROWS are never deleted by this.
        val epoch = fresh.extensionId?.let { definitionEpochFor(it) }
        if (epoch != null && fresh.syncedEpoch != epoch &&
            (fresh.chapterListComplete ||
                fresh.chapterWalkNextPage != 0 ||
                fresh.chapterWalkTotalPages != 0 ||
                fresh.chapterWalkPageSize != 0)
        ) {
            fresh = fresh.copy(
                chapterListComplete = false,
                chapterWalkNextPage = 0,
                chapterWalkTotalPages = 0,
                chapterWalkPageSize = 0
            )
            bookDao.update(fresh)
        }
        if (fresh.chapterListComplete) return AppResult.Ok(fresh)
        val existing = chapterDao.forBook(fresh.id)
        if (existing.size >= atLeast) return AppResult.Ok(fresh)
        if (fresh.extensionId == null) return AppResult.Ok(fresh)
        val (_, remoteId) = SourceBookIds.decode(fresh.remoteId)
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    "extension", "the book's source link is damaged"
                )
            )
        // v1.5.10 (B5): book-aware resolution, as in refresh().
        val resolved = when (
            val found = service.resolveBookSource(fresh)
        ) {
            is AppResult.Err -> return found
            is AppResult.Ok -> found.value
        }
        // v1.5.8 (A2): a player book with NO stored chapters
        // and an incomplete stamp routes to refresh() BEFORE
        // the non-declarative early return below — a player
        // source has no chunks, the full list IS the first
        // chunk, and the no-op Ok must never be the last word
        // on an unfetched book. (chapterListComplete already
        // returned above, so reaching here means incomplete.)
        if (resolved.source is MihonSourceAdapter && existing.isEmpty()) {
            return refresh(fresh, resolved)
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
            description = fresh.description.ifBlank { null },
            // v1.5.3 (finding #3, item 8): same cover carry as
            // the refresh ref — the progressive road's details
            // read gets the stored cover as its fallback too.
            coverUrl = fresh.coverUrl
        )
        val have = existing.sortedBy { it.index }.map { row ->
            SourceChapter(
                id = row.remoteId ?: "",
                name = row.title,
                number = ChapterNumbers.parse(row.title),
                dateText = row.dateText,
                // v1.5.8 (E1): the rebuild reads the credit back.
                scanlator = row.scanlator,
                // v1.5.11 (transplant, Verdict 1): and the memo.
                memo = row.memo
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
        // v1.5.8 (D2 rule 3): the chunk's PROVENANCE governs
        // its stamps — the chunk is the only object that
        // crosses into this seam, and Builder 2 copied the
        // supplying road onto it at every construction site.
        // A FALLBACK-substituted or UNPROVEN chunk is stored
        // as NO chapters: its rows never reach the table, the
        // walk cursor does not advance (a later demand
        // retries the same ground), and neither completeness
        // nor the total is stamped from its size — the stored
        // list and its stamps stand exactly as they were.
        val provenanceUnproven =
            chunk.suppliedBy == ChapterSuppliedBy.FALLBACK ||
                chunk.suppliedBy == ChapterSuppliedBy.UNPROVEN
        val effectiveComplete = chunk.complete && !provenanceUnproven
        // v1.5.5 (§2, epoch hygiene): a chunk that COMPLETES
        // the list stamps the current definition epoch; a
        // partial chunk leaves the stamp alone (its list is
        // not yet proven under this epoch).
        val chunkEpoch = updated.extensionId?.let {
            definitionEpochFor(it)
        }
        updated = updated.copy(
            totalChapters = if (provenanceUnproven) {
                updated.totalChapters
            } else {
                chunk.chapters.size
            },
            updatedAt = now,
            chapterListComplete = if (provenanceUnproven) {
                updated.chapterListComplete
            } else {
                chunk.complete
            },
            chapterWalkNextPage = if (provenanceUnproven) {
                updated.chapterWalkNextPage
            } else {
                chunk.walk.nextPage
            },
            chapterWalkTotalPages = if (provenanceUnproven) {
                updated.chapterWalkTotalPages
            } else {
                chunk.walk.totalPages
            },
            chapterWalkPageSize = if (provenanceUnproven) {
                updated.chapterWalkPageSize
            } else {
                chunk.walk.pageSize
            },
            syncedEpoch = if (effectiveComplete && chunkEpoch != null) {
                chunkEpoch
            } else {
                updated.syncedEpoch
            }
        )
        val coverUrl = chunk.details?.coverUrl
        if (!coverUrl.isNullOrBlank() && updated.coverUrl != coverUrl) {
            updated = updated.copy(coverUrl = coverUrl)
        }
        val chunkKind = chunk.details?.kind
        updated = updated.copy(
            kind = BookKind152.applyTier1(updated.kind, chunkKind)
        )
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
        // v1.5.8 (D2 rule 3): the FULL reconcile (with drop
        // semantics) runs only on a proven-complete chunk —
        // effectiveComplete already excludes fallback/unproven
        // provenance, whose rows are not stored at all.
        if (!provenanceUnproven) {
            val plan = if (effectiveComplete) {
                ChapterReconciler.reconcile(existing, chunk.chapters)
            } else {
                ChapterReconciler.reconcilePartial(existing, chunk.chapters)
            }
            applyPlan(book.id, plan, now)
        }
        if (effectiveComplete) {
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

    /** v1.5.2 (§14.2): fetchCover refusals are TYPED — "the source
     * provides a cover and we failed" is a distinguishable state,
     * never the same shape as "no cover exists". */
    sealed interface CoverFetch {
        data class Ok(val relativePath: String) : CoverFetch
        data object NoUrl : CoverFetch
        data object RefusedPermission : CoverFetch
        data object HttpError : CoverFetch
        data object Empty : CoverFetch
        data object TooLarge : CoverFetch
        data object NoSourceRow : CoverFetch
        fun refusalName(): String? = when (this) {
            is Ok -> null
            NoUrl -> "NO_URL"
            RefusedPermission -> "REFUSED_PERMISSION"
            HttpError -> "HTTP_ERROR"
            Empty -> "EMPTY"
            TooLarge -> "TOO_LARGE"
            NoSourceRow -> "NO_SOURCE_ROW"
        }
    }

    /** The last refusal class per book id (in-memory record; the
     * book record's coverUrl distinguishes "has URL, fetch failed"
     * from "no cover exists" durably). */
    val coverRefusals = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * v1.5.2 fix (FIX-10, §14.3): the §8 deferred phase's cover
     * backfill leg — fetch the stored canonical URL's file for a
     * library book that has the URL but no file. True when a file
     * now exists. Refusals stay recorded by fetchCoverTyped.
     */
    suspend fun backfillCoverFile(book: Book): Boolean {
        val url = book.coverUrl?.takeIf { it.isNotBlank() } ?: return false
        if (book.coverPath != null &&
            runCatching { storage.resolve(book.coverPath!!).exists() }.getOrDefault(false)
        ) return false // file already present: nothing backfilled
        val path = fetchCover(book, url) ?: return false
        bookDao.update(book.copy(coverPath = path))
        return true
    }

    private suspend fun fetchCover(book: Book, coverUrl: String): String? {
        val typed = fetchCoverTyped(book, coverUrl)
        if (typed is CoverFetch.Ok) return typed.relativePath
        typed.refusalName()?.let { coverRefusals[book.id] = it }
        return null
    }

    private suspend fun fetchCoverTyped(book: Book, coverUrl: String?): CoverFetch {
        if (coverUrl.isNullOrBlank()) return CoverFetch.NoUrl
        val row = extensionDao.getById(book.extensionId ?: return CoverFetch.NoSourceRow)
            ?: return CoverFetch.NoSourceRow
        val permissions = ExtensionPermissions.parse(row.permissionsJson)
        val host = CookieParsing.hostOf(coverUrl) ?: return CoverFetch.RefusedPermission
        if (!permissions.allows(host)) return CoverFetch.RefusedPermission
        val response = when (
            val result = transport.execute(HttpTransport.Request(url = coverUrl))
        ) {
            is AppResult.Err -> return CoverFetch.HttpError
            is AppResult.Ok -> result.value
        }
        if (response.code !in 200..299) {
            response.body?.close()
            return CoverFetch.HttpError
        }
        // v1.3.0 delta (item 44): the body read runs on IO —
        // never on the caller's thread. On a trickling
        // connection this read is exactly where a main-thread
        // caller used to park until the system killed the app.
        val bytes = withContext(Dispatchers.IO) {
            response.body?.use { it.readBytes() }
        } ?: return CoverFetch.Empty
        if (bytes.isEmpty()) return CoverFetch.Empty
        if (bytes.size > 4 * 1024 * 1024) return CoverFetch.TooLarge
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
            CoverFetch.Ok(relative)
        }.getOrElse { CoverFetch.HttpError }
    }

    companion object {
        /** v1.5.4 (§0.2, pinned): the book-page load bound —
         * one deadline over a whole [refresh], rebind ladder
         * included (WS-1 item 3, donor rule D2/D4). */
        const val BOOK_PAGE_LOAD_TIMEOUT_MS = 75_000L

        /** v1.5.10 (B1): the book page's HONEST-WAIT bound,
         * measured from the start of a chapter refresh (the
         * [ChapterWaitRegistry] entry's start). Past it, the
         * page stops claiming "Finding chapters…" and shows
         * the honest slow-source state + Retry — while the
         * shared chapter call itself is NEVER cancelled or
         * restarted (the fence: no waiter deadline lives in
         * the shared call). B4's reader stall surfaces import
         * this same constant. Defined beside
         * [BOOK_PAGE_LOAD_TIMEOUT_MS] per the build map. */
        const val CHAPTER_PENDING_UI_BOUND_MS = 60_000L

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
    fun apply(current: Book, details: SourceBook): Book {
        val merged = current.copy(
            title = details.title.ifBlank { current.title },
            author = details.author?.ifBlank { null } ?: current.author,
            description = details.description?.ifBlank { null } ?: current.description
        )
        // v1.5.9 (E-1): the healed remote id rides the same
        // merge — see [rehostedRemoteId] for the exact shape
        // that may overwrite the stored address. Every other
        // case leaves remoteId byte-identical.
        val healed = rehostedRemoteId(current.remoteId, details.id)
            ?: return merged
        return merged.copy(remoteId = healed)
    }

    /**
     * v1.5.9 (E-1, Finding 2 §5.1 storage persistence): the
     * walk-time heal in DeclarativeSource returns the healed
     * remote id on its details read, but pre-fix nothing ever
     * persisted it — [Book.remoteId] changed only at openBook
     * creation and in the rebind ladder, so a healed book
     * re-healed (or re-failed) on every refresh forever.
     *
     * The stored column is the [SourceBookIds] encoding
     * ("<sourceId>|<book id>"); the BOOK ID inside it is what
     * the heal rewrites. This returns the replacement column
     * value when — and ONLY when — the heal's exact output
     * shape holds:
     *  - the stored book id and the incoming [incomingId] are
     *    both absolute http(s) URLs (a non-URL id — a player
     *    source's relative key like "/manga/alpha", a JS/Dex
     *    locator — can never be a re-host, in either
     *    direction);
     *  - their path, query and fragment are EQUAL (the book's
     *    identity survives untouched);
     *  - so the two differ at most in scheme + authority —
     *    a pure re-host, exactly what
     *    DeclarativeSource.healedStoredBookUrl produces.
     * Equal ids are a no-op (null), as is every other shape:
     * path- or query-differing ids are DIFFERENT books, never
     * an overwrite. A stored value that carries no encoding
     * (a raw id in the column) is judged as the raw id itself
     * and replaced raw, so the rule holds for either storage
     * shape.
     */
    internal fun rehostedRemoteId(
        storedEncoded: String?,
        incomingId: String
    ): String? {
        if (storedEncoded.isNullOrBlank()) return null
        val decoded = SourceBookIds.decode(storedEncoded)
        val storedId = decoded?.second ?: storedEncoded
        if (storedId == incomingId) return null
        if (!isPureRehost(storedId, incomingId)) return null
        return if (decoded != null) {
            SourceBookIds.encode(decoded.first, incomingId)
        } else {
            incomingId
        }
    }

    /** True when [incoming] is [stored] re-hosted: both
     * absolute http(s) URLs whose path, query and fragment
     * agree, so any difference sits in scheme + authority
     * alone. */
    private fun isPureRehost(stored: String, incoming: String): Boolean {
        val a = absoluteHttpUri(stored) ?: return false
        val b = absoluteHttpUri(incoming) ?: return false
        return a.path == b.path &&
            a.query == b.query &&
            a.fragment == b.fragment
    }

    private fun absoluteHttpUri(value: String): URI? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.authority.isNullOrBlank()) return null
        return uri
    }
}

/**
 * v1.5.10 (B1): the page-side chapter-wait registry — a
 * process-scoped record of chapter waits the book page has
 * begun, aged by an injectable clock. It exists because the
 * shared chapter call carries NO waiter deadline by design
 * (donor semantics, the B1 fence): the bound lives here, on
 * the page side, and "detaching" means the page's state
 * machine stops deriving "finding" from the await — the
 * consumer keeps running detached and its landing still
 * applies through Room.
 *
 * Entries survive leaving/returning because the registry
 * lives on the SourceBookSync singleton, not on a page
 * (process death → the next open re-registers; plan R-C
 * accepts this). [begin] is a RE-JOIN, never a restart: an
 * existing entry keeps its original start, so Retry and
 * page-returns never reset the honesty clock, and nothing
 * here ever starts a second walk.
 */
class ChapterWaitRegistry(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    enum class State { WAITING }

    data class WaitEntry(
        val bookId: Long,
        val startedAtMs: Long,
        val state: State = State.WAITING
    )

    private val entries =
        java.util.concurrent.ConcurrentHashMap<Long, WaitEntry>()

    /** Record a wait's start for [bookId]. If an entry
     * already stands, its ORIGINAL start is kept (re-join
     * semantics — see the class doc). */
    fun begin(bookId: Long) {
        entries.putIfAbsent(bookId, WaitEntry(bookId, clock()))
    }

    /** Chapters landed for [bookId]: the wait is over. */
    fun markLanded(bookId: Long) {
        entries.remove(bookId)
    }

    fun entry(bookId: Long): WaitEntry? = entries[bookId]

    fun startedAtMs(bookId: Long): Long? = entries[bookId]?.startedAtMs

    /** Age of the standing wait at [nowMs], or null when no
     * wait stands for the book. */
    fun ageMs(bookId: Long, nowMs: Long = clock()): Long? =
        entries[bookId]?.let { nowMs - it.startedAtMs }

    /** The B1 verdict: a wait stands AND has reached the
     * honest-state bound. */
    fun isStalled(
        bookId: Long,
        nowMs: Long = clock(),
        boundMs: Long = SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS
    ): Boolean = ageMs(bookId, nowMs)?.let { it >= boundMs } ?: false

    /** Test seam: drop every entry. */
    fun clear() = entries.clear()
}

/**
 * v1.5.10 (B4): the reader stall seam — the same bound as
 * B1 ([SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS], imported
 * by both reader roads), applied to a chapter-open wait
 * (Komikku page list, novel chapter text). Pure decision +
 * the DiagnosticLog stall record every bound-crossing stall
 * writes (source id, awaited item, started-at, still-running
 * flag), so Copy details can SAY a stall happened. No
 * deadline is added to any shared call — leaving the reader
 * cancels the wait exactly as it always has.
 */
object ReaderStall {
    const val STALL_MESSAGE = "Still loading — this chapter is taking long"

    fun isStalled(
        startedAtMs: Long,
        nowMs: Long,
        boundMs: Long = SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS
    ): Boolean = nowMs - startedAtMs >= boundMs

    /** Write the stall record. Returns true when a record
     * was written (the wait had crossed the bound). */
    fun recordIfStalled(
        sourceId: String,
        awaited: String,
        startedAtMs: Long,
        nowMs: Long,
        stillRunning: Boolean = true,
        boundMs: Long = SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS
    ): Boolean {
        if (!isStalled(startedAtMs, nowMs, boundMs)) return false
        DiagnosticLog.record(
            "stall",
            "reader stall: source $sourceId still awaiting $awaited " +
                "after ${nowMs - startedAtMs} ms (bound $boundMs ms)",
            "startedAt=$startedAtMs stillRunning=$stillRunning"
        )
        return true
    }
}
