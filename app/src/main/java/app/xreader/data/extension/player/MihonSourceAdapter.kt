package app.xreader.data.extension.player

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.normaliseHost
import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.copy
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.net.URI
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import app.xreader.extensionapi.Source as FacadeSource
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceBookPage
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceChapterContent
import app.xreader.extensionapi.SourceException
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source as MihonSource

/**
 * K4 (Keiyoushi player): one live Mihon source presented as a
 * facade [FacadeSource], so the whole existing pipeline —
 * catalogue, search, book sync, chapter fetch, downloads —
 * consumes a Keiyoushi source exactly like a declarative or
 * JS source, through [app.xreader.data.extension.ExtensionService]'s
 * guarded call path. The plan's adapter (§K4), with the donor
 * shapes kept: the Mihon model objects are mapped field by
 * field, never re-parsed.
 *
 * Identity: the facade [id] is the namespaced id
 * "keiyoushi:<packageName>:<mihonId>" (the same key the player
 * manager's live registry uses), so Mihon Long ids can never
 * collide with another package's or with a native source's.
 * A book's remote id is the source's own [SManga.url]; a
 * chapter's remote id is its [SChapter.url].
 *
 * Every Mihon entry-point call runs inside
 * [PlayerNamespaceElement] — K2/K3's bracketing rule, carried
 * across suspension and thread hops by a coroutine context
 * element (a Mihon call's segments resume on OkHttp threads;
 * the namespace must be in force on whichever thread a
 * segment runs on, because the injected Application reads it
 * per call). The facade is blocking by contract; since
 * v1.5.4 (WS-1 item 2) each call bridges through the
 * interruptible bridge executor in [callMihon] — a hard
 * deadline whose expiry, or the caller's cancellation,
 * stops the work itself, not just the waiting.
 *
 * Errors: anything a source throws is converted HERE to a
 * facade [SourceException] with plain words (HTTP codes
 * named, linkage/script-engine gaps worded by
 * [PlayerFailureWords], a source's own readable message kept)
 * — a stack trace or a raw linkage error never crosses into
 * the guarded path, whose own catch only handles Exceptions.
 */
class MihonSourceAdapter(
    /** The Keiyoushi package this source came from — the
     * player namespace every call is bracketed with. */
    val packageName: String,
    /** "keiyoushi:<packageName>:<mihonId>". */
    val namespacedId: String,
    /** The live Mihon source, from the player's registry. */
    val mihon: MihonSource,
    /** The extension's index contentWarning, mapped by
     * [app.xreader.data.extension.KeiyoushiIndex] — stamped
     * per source so the ONE content preference governs player
     * sources exactly like native ones. */
    val contentRating: ContentRating,
    /** v1.5.8 (B4): the kind from the extension's OWN type —
     * the construction site (ExtensionService's player load)
     * passes TEXT when the wrapped source is a [NovelSource],
     * MANGA otherwise. The default derives the same verdict
     * from [mihon] so a directly-built adapter is never
     * stamped with the opposite kind either. (Pre-fix this
     * was a hardcoded MANGA getter: every installed Keiyoushi
     * NOVEL extension — 77 of 122 English donor extensions —
     * was filed as manga and routed to the page reader.) */
    val kind: MediaKind =
        if (mihon is NovelSource) MediaKind.TEXT else MediaKind.MANGA,
    /** v1.5.4 (WS-1 item 2): the bridge deadline for one Mihon
     * call — injectable so gate tests can run short deadlines;
     * production keeps the pinned §0.2 value, aligned with the
     * player client's own callTimeout (30/30/120, unchanged). */
    private val bridgeTimeoutMs: Long = BRIDGE_CALL_TIMEOUT_MS,
    /** v1.5.4 (WS-1 item 7b, closed at integration): sink for
     * the cover hosts this source's own results carry —
     * production wires KeiyoushiPlayerManager.noteCoverHosts,
     * which folds them into the package's derived permission
     * domains (a cover on an unrelated CDN host was otherwise
     * refused by construction and never landed as a local
     * file). Default: nowhere (tests, non-player use). */
    private val onCoverHostsSeen: (Set<String>) -> Unit = {}
) : FacadeSource {

    /** The source's own Mihon id (the registry's suffix). */
    val mihonId: Long get() = mihon.id

    init {
        // v1.5.6 (§5.2, M2): this source owns the page URLs
        // it registers with the image bridge; when a
        // registration is evicted, the fetcher re-resolves
        // through here instead of ever serving the page
        // headerless. The resolver lives on the bridge keyed
        // by the namespaced id, so a rebuilt adapter over
        // the same source simply re-stamps the same seam.
        PlayerImageBridge.registerResolver(namespacedId) { url ->
            reresolvePage(url)
        }
    }

    /** v1.5.8 (B4): the kind the extension's own type declared
     * at construction — see [kind]. No longer hardcoded: a
     * NovelSource-backed adapter reads TEXT here, and
     * [ExtensionService.mediaKindOf] governs routing from it. */
    val mediaKind: MediaKind get() = kind

    /** The wrapped source as a novel source, when it is one. */
    private val novelSource: NovelSource? get() = mihon as? NovelSource

    override val id: String = namespacedId

    override val name: String get() = mihon.name

    override val lang: String
        get() = (mihon as? CatalogueSource)?.lang ?: "multi"

    override val baseUrl: String
        get() = (mihon as? HttpSource)?.baseUrl ?: ""

    // ── listings ────────────────────────────────────────────

    override fun search(query: String, page: Int): SourceBookPage =
        callMihon {
            // K5: the catalogue's filter sheet applies its
            // FilterList onto the adapter (see [applyFilters]);
            // until it does, search runs on the source's
            // default (empty) filter set, exactly as before.
            mapMangasPage(
                mihon.getSearchManga(page, query, appliedFilterList ?: FilterList())
            )
        }

    override fun popular(page: Int): SourceBookPage =
        callMihon { mapMangasPage(mihon.getPopularManga(page)) }

    override fun latest(page: Int): SourceBookPage =
        callMihon {
            mapMangasPage(
                if (mihon.supportsLatest) {
                    mihon.getLatestUpdates(page)
                } else {
                    // The facade's own convention (a native
                    // source with no latest list falls back to
                    // its popular list).
                    mihon.getPopularManga(page)
                }
            )
        }

    // ── book + chapters ─────────────────────────────────────

    override fun details(book: SourceBook): SourceBook =
        details(book, stableKey = null)

    /**
     * v1.5.5 (§1): details with the caller's STABLE key for
     * the single-flight registry (see [runMangaUpdate]) —
     * the stored Book's Room id when the book is persisted,
     * so a URL the donor mutates mid-call can never split
     * one book into two registry keys (Amendments, Hole B).
     */
    fun details(book: SourceBook, stableKey: String?): SourceBook =
        mapDetails(
            runMangaUpdate(
                book, fetchDetails = true, fetchChapters = false,
                stableKey = stableKey
            ).manga,
            book
        )

    override fun chapters(book: SourceBook): List<SourceChapter> =
        chapters(book, stableKey = null)

    /** v1.5.5 (§1): chapters with a stable registry key —
     * see [details]. */
    fun chapters(
        book: SourceBook,
        stableKey: String?
    ): List<SourceChapter> = mapChapters(
        runMangaUpdate(
            book, fetchDetails = false, fetchChapters = true,
            stableKey = stableKey
        ).chapters
    )

    /**
     * v1.5.5 (§1 — the fused book-page call, the donor's own
     * shape): ONE `getMangaUpdate(fetchDetails = true,
     * fetchChapters = true)` whose [SMangaUpdate] is split
     * into the facade's two products. This is the ONLY way
     * a book page may read a Keiyoushi source: the pre-fix
     * split (a details call and a chapters call launched
     * concurrently by the sync layer) overlapped inside the
     * donor base class, whose final `getMangaUpdate`
     * `check(...)`s against ANY in-flight call for the same
     * manga and sank the whole refresh (the v1.5.4 phone
     * proof: Asura/Madara at 0 chapters). If the fused call
     * throws, both halves surface that same failure — the
     * donor's atomic semantics; the sync layer still APPLIES
     * the two halves independently once the result lands.
     */
    fun detailsAndChapters(
        book: SourceBook,
        stableKey: String? = null
    ): Pair<SourceBook, List<SourceChapter>> {
        val update = runMangaUpdate(
            book, fetchDetails = true, fetchChapters = true,
            stableKey = stableKey
        )
        return mapDetails(update.manga, book) to
            mapChapters(update.chapters)
    }

    /** The details mapping, shared by [details] and the
     * fused call (extracted verbatim from the pre-v1.5.5
     * details() body). */
    private fun mapDetails(manga: SManga, book: SourceBook): SourceBook {
        val out = SourceBook(
            id = manga.url.takeIf { it.isNotBlank() } ?: book.id,
            title = manga.title.takeIf { it.isNotBlank() } ?: book.title,
            author = manga.author?.takeIf { it.isNotBlank() }
                ?: book.author,
            coverUrl = manga.thumbnail_url
                ?.takeIf { it.isNotBlank() }
                ?.let { absolutise(it) }
                ?: book.coverUrl,
            description = manga.description?.takeIf { it.isNotBlank() }
                ?: book.description
        )
        noteCoverHosts(listOf(out))
        return out
    }

    /** The chapters mapping, shared by [chapters] and the
     * fused call (extracted verbatim from the pre-v1.5.5
     * chapters() body). */
    private fun mapChapters(
        chapters: List<SChapter>
    ): List<SourceChapter> =
        readingOrder(chapters).map { chapter ->
            // v1.5.11 (transplant, Verdict 1 — Gemini hunk 1A):
            // snapshot the FULL donor chapter into the
            // companion retained-chapters map BEFORE mapping.
            // ExtensionService.loadExtension deliberately skips
            // its adapter cache for Keiyoushi lookups, so a
            // fresh adapter instance is built per lookup and
            // instance-held state dies with it; the companion
            // map survives, so the extension's own SChapter —
            // memo, true date_upload, scanlator, number —
            // can be restored verbatim at getPageList time
            // (see [schapterOf]) even when this process never
            // persisted the chapter. Keyed by the namespaced
            // source id + the chapter's own url (the facade id).
            val snapshot = SChapter.create().apply {
                copyFrom(chapter)
                // v1.5.11 (gate fix): the snapshot restores
                // VERBATIM at [schapterOf] time, so it must
                // carry the scanlator in the same normalised
                // shape the facade mapping below applies
                // (the donor's own rule: blank → null,
                // trimmed) — a raw padded credit otherwise
                // bypassed that seam on the write-back road
                // and reached getPageList untrimmed. Only the
                // scanlator is normalised here: the memo (and
                // every other field) stays byte-faithful to
                // the extension's own chapter.
                scanlator = chapter.scanlator
                    ?.ifBlank { null }?.trim()
            }
            synchronized(retainedChapters) {
                retainedChapters[chapterCacheKey(chapter.url)] = snapshot
            }
            SourceChapter(
                id = chapter.url,
                name = chapter.name,
                // TRUE chapter number, from the model —
                // never the row's position (the app's
                // chapter-identity rule). Mihon's -1 (or
                // any negative) means "unknown" and maps
                // to null, not to a fabricated number.
                number = chapter.chapter_number
                    .takeIf { it >= 0f },
                // Epoch millis as text: the chapter-date
                // renderer parses all-digit values as
                // instants, so the relative wording stays
                // honest ("3 days ago", never a guess).
                dateText = chapter.date_upload
                    .takeIf { it > 0 }?.toString(),
                // v1.5.8 (E1): the scanlator, with the donor's
                // own normalisation — blank credits are no
                // credit, and the stored value is trimmed.
                scanlator = chapter.scanlator
                    ?.ifBlank { null }?.trim(),
                // v1.5.11 (transplant, Verdict 1): the memo —
                // the donor SChapter.memo JsonObject as its
                // JSON text, persisted on the chapter row
                // (schema v12) so a cold-process reader open
                // restores it too. An empty object is honest
                // absence (null), never a fabricated "{}".
                // v1.5.12 (M4, Lane 6 AT-RISK 2): the persisted
                // payload ALSO carries the extension's true
                // chapter_number under the host-namespaced key
                // (see [memoTextWithChapterNumber]) — the Room
                // row has no number column, so this is the only
                // persisted carrier that lets a cold rebuild
                // restore the true number for an unnumbered
                // title ("Prologue") instead of -1.
                memo = memoTextWithChapterNumber(
                    chapter.memo,
                    chapter.chapter_number.takeIf { it >= 0f }
                )
            )
        }

    // ── v1.5.5 (§1): the per-manga single-flight registry ──

    /**
     * Every `getMangaUpdate`-bound entry ([details],
     * [chapters], [detailsAndChapters]) funnels through
     * here, so no two donor calls for the same manga can
     * EVER overlap — the donor base class rejects any
     * overlap with `IllegalStateException`, whatever the
     * flags (V154-PROOF-FINDING-MANGA-CONCURRENCY §1.1).
     *
     * Shape (spec §1 + Amendments Hole B + Amendments 2
     * red-flag 3):
     * - The registry lives on the COMPANION, not the
     *   instance: the service rebuilds adapter instances on
     *   every resolve (Keiyoushi handles are uncached), all
     *   wrapping the SAME live Mihon source — an instance
     *   registry would let a book-page refresh and a library
     *   refresh resolved separately overlap exactly as
     *   before. Keys carry the namespaced source id.
     * - Key: the caller's stable key (the stored Book's
     *   Room id, threaded by SourceBookSync) when present,
     *   else (sourceId, book.id) — both snapshotted at
     *   entry, and BOTH aliases are registered for a keyed
     *   call, so an unkeyed caller for the same manga still
     *   finds the in-flight call. A defensive
     *   [SManga.copy] is what the donor receives (it may
     *   mutate manga.url in place; the key is already a
     *   snapshot String either way).
     * - A caller whose flags are a SUBSET of the in-flight
     *   call's flags JOINS it and takes its half; a caller
     *   whose needs are not a subset QUEUES (slice-poll,
     *   below) until the in-flight call completes, then
     *   starts its own.
     * - The shared call runs in the adapter-owned
     *   [updateScope] (a SupervisorScope — never a caller's
     *   scope). Waiters are reference-counted on the call
     *   record: a waiter that is cancelled or hits its
     *   deadline simply stops waiting (decrement) and
     *   surfaces its own cancellation/timeout failure — it
     *   NEVER cancels the shared call out from under other
     *   waiters. The shared call runs to completion
     *   detached, bounded by the bridge deadline inside
     *   [callMihon] itself.
     * - Discipline: the registry lock is a plain monitor
     *   held only for map bookkeeping (microseconds, never
     *   across a wait — Amendments Hole A: no bridge thread
     *   is ever held waiting for the registry). The WAIT is
     *   the same slice-poll [callMihon] uses: poll the
     *   shared future in [CALLER_POLL_MS] slices against
     *   the caller job published in [BridgeCallContext] and
     *   a deadline, on the CALLER's thread (the facade is
     *   blocking; the caller is already parked here, not on
     *   a bridge thread).
     */
    private fun runMangaUpdate(
        book: SourceBook,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        stableKey: String?
    ): SMangaUpdate {
        val callerJob = BridgeCallContext.currentJob()
        // Key snapshot at entry (Hole B): the url half is
        // the facade book id string as it stands NOW; the
        // donor call below receives a copy, so a mid-call
        // url mutation cannot move the key.
        val urlAlias = "url:$namespacedId:${book.id}"
        val stableAlias = stableKey?.let { "book:$namespacedId:$it" }
        val aliases = listOfNotNull(stableAlias, urlAlias)
        val mangaSnapshot = smangaOf(book)
        // v1.5.8 (A1): NO waiter deadline on a getMangaUpdate-
        // class call — the donor imposes none (its per-call
        // OkHttp clocks are the only deadlines), and Xreader's
        // 120 s bridge envelope is what killed slow-by-design
        // chapter walks (Comix). A waiter still stops waiting
        // when its own caller is cancelled; the shared call
        // itself is never deadline-cancelled.
        // v1.5.11 (point 10): the wait is bounded by the
        // finite ceiling instead of literal Long.MAX_VALUE —
        // see [effectiveWaitDeadlineNanos]. The ceiling is
        // not the old 120 s envelope and does not
        // deadline-cancel the shared call's WORK on IO
        // callers within any realistic walk; it exists so no
        // wait can park forever, and so a main-thread waiter
        // fails fast instead of ANRing.
        var waitDeadlineNanos = effectiveWaitDeadlineNanos(Long.MAX_VALUE)
        while (true) {
            var joined: SharedUpdateCall? = null
            var blocking: SharedUpdateCall? = null
            var launched: SharedUpdateCall? = null
            synchronized(updateRegistryLock) {
                var existing = aliases.firstNotNullOfOrNull {
                    updateCallsByAlias[it]
                }
                if (existing != null && existing.outcome.isDone) {
                    // Completed but its cleanup has not run
                    // yet: reap it here rather than join or
                    // queue behind a finished call.
                    existing.aliases.forEach { alias ->
                        if (updateCallsByAlias[alias] === existing) {
                            updateCallsByAlias.remove(alias)
                        }
                    }
                    existing = null
                }
                val live = existing
                when {
                    live == null -> {
                        val call = SharedUpdateCall(
                            aliases = aliases,
                            fetchDetails = fetchDetails,
                            fetchChapters = fetchChapters
                        )
                        call.waiters = 1
                        aliases.forEach {
                            updateCallsByAlias[it] = call
                        }
                        joined = call
                        launched = call
                    }
                    live.covers(fetchDetails, fetchChapters) -> {
                        live.waiters++
                        joined = live
                    }
                    else -> blocking = live
                }
            }
            val launchedCall = launched
            if (launchedCall != null) {
                // v1.5.8 (A1): the wait horizon stays unbounded
                // (see the deadline note at entry) — a launched
                // call changes nothing about it.
                startSharedUpdate(launchedCall, mangaSnapshot)
            }
            val call = joined
            if (call != null) {
                try {
                    return awaitSharedUpdate(
                        call, callerJob, waitDeadlineNanos
                    )
                } finally {
                    synchronized(updateRegistryLock) {
                        call.waiters--
                    }
                }
            }
            // Not a subset of the in-flight call: queue
            // behind it, then loop and start our own.
            awaitQueueSlot(
                blocking!!, callerJob, waitDeadlineNanos
            )
        }
    }

    /** Launch the shared donor call in the adapter-owned
     * scope. The call publishes ITS OWN job in
     * [BridgeCallContext], so [callMihon]'s cancellation
     * hook answers to the shared call's lifetime, never to
     * any waiter's — a waiter leaving cannot stop it. */
    private fun startSharedUpdate(
        call: SharedUpdateCall,
        mangaSnapshot: SManga
    ) {
        updateScope.launch {
            val sharedJob = currentCoroutineContext()[Job]
            try {
                val update = BridgeCallContext.withJob(sharedJob) {
                    // v1.5.8 (A1): unbounded — no BRIDGE_CALL_
                    // TIMEOUT_MS envelope over getMangaUpdate.
                    callMihon(bounded = false) {
                        mihon.getMangaUpdate(
                            // Defensive copy (Hole B): the
                            // donor may mutate manga.url in
                            // place on the object it is given.
                            manga = mangaSnapshot.copy(),
                            chapters = emptyList(),
                            fetchDetails = call.fetchDetails,
                            fetchChapters = call.fetchChapters
                        )
                    }
                }
                call.outcome.complete(update)
            } catch (t: Throwable) {
                call.outcome.completeExceptionally(t)
            } finally {
                synchronized(updateRegistryLock) {
                    call.aliases.forEach { alias ->
                        if (updateCallsByAlias[alias] === call) {
                            updateCallsByAlias.remove(alias)
                        }
                    }
                }
            }
        }
    }

    /** Poll the shared call's future in slices against the
     * caller's job and [deadlineNanos] — [callMihon]'s wait
     * discipline, verbatim in shape. Expiry or caller
     * cancellation surfaces as this WAITER's failure; the
     * shared call is untouched. */
    private fun awaitSharedUpdate(
        call: SharedUpdateCall,
        callerJob: Job?,
        deadlineNanos: Long
    ): SMangaUpdate {
        while (true) {
            if (callerJob?.isCancelled == true) {
                throw asSourceFailure(
                    CancellationException(
                        "the caller stopped waiting (cancelled)"
                    )
                )
            }
            val remainingMs = if (deadlineNanos == Long.MAX_VALUE) {
                // v1.5.8 (A1): unbounded wait — poll in slices
                // against the caller's job only.
                CALLER_POLL_MS
            } else {
                TimeUnit.NANOSECONDS.toMillis(
                    deadlineNanos - System.nanoTime()
                )
            }
            if (remainingMs <= 0L) {
                throw asSourceFailure(
                    CancellationException(
                        "bridge deadline ${bridgeTimeoutMs}ms exceeded"
                    )
                )
            }
            try {
                return call.outcome.get(
                    minOf(remainingMs, CALLER_POLL_MS),
                    TimeUnit.MILLISECONDS
                )
            } catch (e: TimeoutException) {
                // Slice elapsed — re-check and keep waiting.
            } catch (e: ExecutionException) {
                throw asSourceFailure(e.cause ?: e)
            } catch (e: CancellationException) {
                throw asSourceFailure(e)
            } catch (e: java.util.concurrent.CancellationException) {
                // The shared future itself was cancelled
                // (nothing cancels it today; belt and braces).
                throw asSourceFailure(e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw asSourceFailure(e)
            }
        }
    }

    /** The queue wait for a caller whose flags are NOT a
     * subset of the in-flight call's: poll its completion
     * in slices (same job/deadline checks as
     * [awaitSharedUpdate]); the caller then loops and
     * starts its own call. */
    private fun awaitQueueSlot(
        blocking: SharedUpdateCall,
        callerJob: Job?,
        deadlineNanos: Long
    ) {
        while (!blocking.outcome.isDone) {
            if (callerJob?.isCancelled == true) {
                throw asSourceFailure(
                    CancellationException(
                        "the caller stopped waiting (cancelled)"
                    )
                )
            }
            val remainingMs = if (deadlineNanos == Long.MAX_VALUE) {
                CALLER_POLL_MS
            } else {
                TimeUnit.NANOSECONDS.toMillis(
                    deadlineNanos - System.nanoTime()
                )
            }
            if (remainingMs <= 0L) {
                throw asSourceFailure(
                    CancellationException(
                        "bridge deadline ${bridgeTimeoutMs}ms exceeded"
                    )
                )
            }
            try {
                Thread.sleep(minOf(remainingMs, CALLER_POLL_MS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw asSourceFailure(e)
            }
        }
    }

    /**
     * Reading order from a Mihon chapter list. Sources return
     * chapters newest-first; when EVERY chapter carries a
     * true number the number is the order (stable sort —
     * equal numbers keep the source's order), otherwise the
     * list is reversed into oldest-first. Position is never
     * turned into a number either way; the numbers ride
     * separately on each mapped chapter.
     */
    private fun readingOrder(chapters: List<SChapter>): List<SChapter> =
        if (chapters.isNotEmpty() &&
            chapters.all { it.chapter_number >= 0f }
        ) {
            chapters.sortedBy { it.chapter_number }
        } else {
            chapters.reversed()
        }

    // ── chapter pages ───────────────────────────────────────

    override fun chapterContent(
        chapter: SourceChapter
    ): SourceChapterContent {
        // v1.5.8 (B4, OD1a): a NOVEL extension's chapter leg is
        // TEXT, served through the extension's own
        // fetchPageText over its page list — the road the
        // donor's HttpPageLoader takes for NovelSource. The
        // prose rides the facade's ordinary content shape, NO
        // page record is stashed (pagesOf stays null), and the
        // fetch seam's manga guard — keyed on this adapter's
        // TEXT kind — lets it through to the novel reader's
        // prose conversion. Never a forced page-list.
        val novel = novelSource
        if (novel != null) {
            return callMihon(bounded = false) {
                val pages = mihon.getPageList(schapterOf(chapter))
                if (pages.isEmpty()) {
                    throw SourceException("the chapter pages weren't found")
                }
                val parts = ArrayList<String>(pages.size)
                for (page in pages) {
                    parts += novel.fetchPageText(page)
                }
                val text = parts.joinToString("\n")
                if (text.isBlank()) {
                    throw SourceException("the chapter text wasn't found")
                }
                SourceChapterContent(html = text, title = chapter.name)
            }
        }
        // v1.5.8 (A1): unbounded — the page-list leg loses the
        // 120 s envelope too (F1 M6); the reader's own loading
        // state is cancellable by leaving the page.
        // v1.5.11 (point 10): "unbounded" no longer means
        // limitless — callMihon's wait carries the finite
        // ceiling (and the main-thread bound) so a main-thread
        // arrival can never park unbounded; see
        // [effectiveWaitDeadlineNanos].
        return callMihon(bounded = false) {
            val pages = mihon.getPageList(schapterOf(chapter))
            if (pages.isEmpty()) {
                throw SourceException("the chapter pages weren't found")
            }
            val resolved = resolvePages(chapter.id, pages)
            synchronized(pagesCache) {
                pagesCache[chapterCacheKey(chapter.id)] = resolved
            }
            SourceChapterContent(
                // The honest string form of an image chapter for
                // any consumer that only speaks the facade (the
                // declarative engine's manga shape, mirrored):
                // the pages as an <img> fragment, in order. The
                // fetch path reads the record itself via [pagesOf].
                html = resolved.pages.joinToString("\n") { page ->
                    "<img src=\"" +
                        page.url.replace("&", "&amp;")
                            .replace("\"", "&quot;") +
                        "\"/>"
                },
                title = chapter.name
            )
        }
    }

    /**
     * Turn Mihon [Page]s into the [ChapterPages] record the
     * page downloader (and the non-Keiyoushi pipeline road)
     * already consumes.
     *
     * v1.5.11 (transplant, Verdict 1 — Gemini hunk 1B, from
     * the downloaded With file): page image URLs resolve
     * LAZILY. A page that already carries [Page.imageUrl]
     * keeps it; a blank one is NOT resolved at chapter open —
     * instead it is registered under a deterministic per-page
     * key `"$base#xreaderPage=${page.index}"` (base = the
     * absolutised page url, falling back to the chapter id),
     * and the real [HttpSource.getImageUrl] call moves into
     * [pageBytes], firing only when that page's bytes are
     * actually requested — the donor HttpPageLoader shape,
     * ending the 588× sequential getImageUrl storm the
     * pre-v1.5.11 eager loop fired at chapter open. (The
     * v1.5.8 A5 per-page fault tolerance moves with it: a
     * getImageUrl failure is now a per-page byte failure in
     * [pageBytes], never a chapter failure.) The retained
     * [Page] is keyed by that same key, so the byte road
     * finds it again.
     */
    private fun resolvePages(
        chapterId: String,
        pages: List<Page>
    ): ChapterPages {
        val out = ArrayList<ChapterPage>(pages.size)
        for (page in pages) {
            val directImage = page.imageUrl?.takeIf { it.isNotBlank() }
                ?.let { absolutise(it) }
            val keyUrl = if (directImage != null) {
                page.imageUrl = directImage
                directImage
            } else {
                val base = page.url.takeIf { it.isNotBlank() }
                    ?.let { absolutise(it) }
                    ?: absolutise(chapterId)
                "$base#xreaderPage=${page.index}"
            }
            out += ChapterPage(url = keyUrl)
            synchronized(retainedPages) {
                retainedPages[pageCacheKey(keyUrl)] = page
            }
            // v1.5.0 (steer B1) + v1.5.6 (§5.2, M2): register
            // the page with the image bridge as OWNED by this
            // source, so the download road (and any remaining
            // bridge consumer) serves it through THIS source's
            // own image request, and an evicted registration
            // re-resolves through this adapter (see
            // [reresolvePage]) instead of degrading to a
            // headerless fetch. The v1.5.11 reader road does
            // NOT travel this bridge — its HttpPageLoader
            // calls [getImage] on the retained Page directly.
            PlayerImageBridge.registerOwned(keyUrl, namespacedId) { url ->
                (pageBytes(url) as? AppResult.Ok)?.value
            }
        }
        // K6: stamp the record with the Referer the online
        // reader should present for these pages. Downloads
        // fetch through the source's own image request (its
        // headers ride along), but the reader's Coil path can
        // only carry what the record holds — and the value
        // Mihon sources put in their imageRequest Referer is
        // their own base URL, so that is the honest stamp.
        // A source with no base URL (a plain Source, not an
        // HttpSource) leaves it null, exactly as before.
        val referer = baseUrl.takeIf { it.isNotBlank() }?.let {
            if (it.endsWith("/")) it else "$it/"
        }
        return ChapterPages(out, referer = referer)
    }

    /** The page record of a chapter read through this
     * adapter, or null when the chapter isn't cached (evicted
     * / never read) — the fetch path treats null as "no page
     * record", exactly like the declarative engine's. */
    fun pagesOf(chapterId: String): ChapterPages? =
        synchronized(pagesCache) { pagesCache[chapterCacheKey(chapterId)] }

    /**
     * v1.5.6 (§5.2, M2): the bridge's re-resolution seam for
     * one of this source's page URLs whose registration was
     * evicted. Tier 1 (cheap, no page-list round-trip): the
     * retained Mihon [Page] is still here — simply
     * re-register the byte fetch. Tier 2: the retained page
     * is gone too, but a cached chapter record names the
     * chapter the URL belongs to — re-resolve that chapter
     * (its [chapterContent] re-registers every page). True
     * when the URL is registered again afterwards.
     */
    suspend fun reresolvePage(url: String): Boolean {
        if (PlayerImageBridge.knows(url)) return true
        val retained = synchronized(retainedPages) {
            retainedPages[pageCacheKey(url)]
        }
        if (retained != null) {
            PlayerImageBridge.registerOwned(url, namespacedId) { u ->
                (pageBytes(u) as? AppResult.Ok)?.value
            }
            return true
        }
        val prefix = "$namespacedId|"
        val chapterId = synchronized(pagesCache) {
            pagesCache.entries.firstOrNull { (key, record) ->
                key.startsWith(prefix) &&
                    record.pages.any { it.url == url }
            }?.key?.removePrefix(prefix)
        } ?: return false
        return runCatching {
            chapterContent(SourceChapter(id = chapterId, name = ""))
            PlayerImageBridge.knows(url)
        }.getOrDefault(false)
    }

    /**
     * v1.5.6 (§5.3, M3): refresh a stored chapter's page
     * registrations after a cold process start. The stored
     * page RECORD is not re-downloaded — when this process
     * has never resolved the chapter, its page list is
     * re-resolved through the source (which re-registers
     * every page with the bridge); when the record is
     * already cached in-process, the registrations are
     * already live and there is nothing to do. True when
     * the chapter's pages are registered afterwards.
     */
    fun refreshChapterPages(chapter: SourceChapter): Boolean {
        if (pagesOf(chapter.id) != null) return true
        return runCatching {
            chapterContent(chapter)
            pagesOf(chapter.id) != null
        }.getOrDefault(false)
    }

    /**
     * The download road for one page's bytes: the fetch runs
     * through the source's OWN image request
     * ([HttpSource.getImage] — its imageRequest with its
     * headers, executed on its client, whose cookie jar is
     * the player's session bridge: the same merged per-host
     * session resolution the source's own traffic gets).
     * Returns null when [url] is not a page this adapter
     * resolved (or the source has no HTTP image path) — the
     * caller then uses its default byte path, as for any
     * other source kind.
     */
    suspend fun pageBytes(url: String): AppResult<ByteArray>? {
        val page = synchronized(retainedPages) {
            retainedPages[pageCacheKey(url)]
        } ?: return null
        val http = mihon as? HttpSource ?: return null
        return try {
            val bytes = bracketed {
                // v1.5.11 (hunk 1B): LAZY image-url resolution —
                // a page registered under its #xreaderPage= key
                // still carries no image address; resolve it
                // NOW, at byte-request time, through the
                // source's own image-url path (the donor
                // HttpPageLoader's per-page shape), and write
                // the absolutised address back onto the
                // retained Page so later requests skip it.
                if (page.imageUrl.isNullOrBlank()) {
                    val resolvedUrl = http.getImageUrl(page)
                        .takeIf { it.isNotBlank() }
                        ?: page.url
                    page.imageUrl = absolutise(resolvedUrl)
                }
                http.getImage(page).use { response ->
                    response.body?.bytes()
                        ?: throw SourceException(
                            "the page image came back empty"
                        )
                }
            }
            if (bytes.isEmpty()) {
                AppResult.Err(
                    AppError.ExtensionFailed(
                        name, "the page image came back empty"
                    )
                )
            } else {
                AppResult.Ok(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val failure = asSourceFailure(t)
            AppResult.Err(
                AppError.ExtensionFailed(
                    name,
                    failure.message ?: "the page image couldn't be fetched"
                )
            )
        }
    }

    // ── enrichment + K5 doors ───────────────────────────────

    override fun related(book: SourceBook): List<SourceBook>? {
        val catalogue = mihon as? CatalogueSource ?: return null
        if (!catalogue.supportsRelatedMangas) return null
        return callMihon {
            try {
                catalogue.fetchRelatedMangaList(smangaOf(book))
                    .map { it.toFacadeBook() }
            } catch (e: UnsupportedOperationException) {
                // The contract's own "not offered" answer —
                // the facade's null, not a failure.
                null
            }
        }
    }

    /**
     * The source's own filter list — K5's filter sheet reads
     * it from here and edits the returned objects in place
     * (the donor model: a FilterList's filters ARE the state).
     * Nothing is applied to searches until the sheet hands
     * the list back through [applyFilters].
     */
    fun filterList(): FilterList = callMihon { mihon.getFilterList() }

    /** The filter set the sheet last applied — the exact
     * list instance the next search passes to the source.
     * Held on the adapter (one adapter per catalogue visit,
     * resolved once by the catalogue's ViewModel), so filter
     * state lives and dies with the visit: reopening the
     * source starts from the source's defaults again. */
    @Volatile
    private var appliedFilterList: FilterList? = null

    /** K5: the filter sheet's Apply — searches from now on
     * run with [filters] until another list is applied. */
    fun applyFilters(filters: FilterList) {
        appliedFilterList = filters
    }

    /** The currently applied filter list, or null when the
     * sheet has never applied one this visit. */
    fun appliedFilters(): FilterList? = appliedFilterList

    // ── mapping helpers ─────────────────────────────────────

    private fun mapMangasPage(page: MangasPage): SourceBookPage {
        val mapped = SourceBookPage(
            books = page.mangas.map { it.toFacadeBook() },
            hasNextPage = page.hasNextPage
        )
        noteCoverHosts(mapped.books)
        return mapped
    }

    /** Report the hosts of the covers these books carry —
     * the derivation input for the package's permission
     * domains (item 7b). Relative covers resolve against the
     * source's own host and normalise to nothing here.
     * Best-effort: a sink failure must never fail the read it
     * rode on. */
    private fun noteCoverHosts(books: List<SourceBook>) {
        val hosts = books.mapNotNull { it.coverUrl }
            .map { it.normaliseHost() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (hosts.isNotEmpty()) {
            runCatching { onCoverHostsSeen(hosts) }
        }
    }

    private fun SManga.toFacadeBook(): SourceBook = SourceBook(
        id = url,
        title = title,
        author = author?.takeIf { it.isNotBlank() },
        coverUrl = thumbnail_url?.takeIf { it.isNotBlank() }
            ?.let { absolutise(it) },
        description = description?.takeIf { it.isNotBlank() }
    )

    /** Rebuild the Mihon manga a call needs from the facade
     * book: the url is the identity every Mihon entry point
     * keys on; the display fields ride along so a source
     * that echoes them back loses nothing. */
    private fun smangaOf(book: SourceBook): SManga =
        SManga.create().apply {
            url = book.id
            title = book.title
            author = book.author
            description = book.description
            thumbnail_url = book.coverUrl
            initialized = true
        }

    /**
     * The facade chapter back to the donor chapter a Mihon
     * entry point receives — the donor's `Chapter.toSChapter()`
     * conversion, Xreader-shaped.
     *
     * v1.5.11 (transplant, Verdict 1 — Gemini hunk 1C): the
     * RETAINED SNAPSHOT wins. When [mapChapters] saw this
     * chapter this process, the extension's own SChapter —
     * memo, true date_upload, scanlator, number — is restored
     * verbatim (a copy, so the donor call may mutate it).
     * Only when no snapshot exists (a cold process opening a
     * shelf book) is a fresh SChapter rebuilt from the facade
     * fields — and that road now restores EVERY field the
     * facade carries: the stored dateText is epoch-millis
     * text on this road (mapChapters' own convention), so it
     * round-trips to date_upload, and the memo JSON text
     * persisted on the chapter row (schema v12) parses back
     * into the JsonObject the extension minted. Pre-v1.5.11
     * this rebuild handed the extension chapter_number = −1,
     * scanlator = null, date_upload = 0 and an EMPTY memo —
     * the degradation Asura's "Refresh Chapter List" throw
     * answered.
     */
    private fun schapterOf(chapter: SourceChapter): SChapter {
        synchronized(retainedChapters) {
            retainedChapters[chapterCacheKey(chapter.id)]
        }?.let { retained ->
            return SChapter.create().apply { copyFrom(retained) }
        }
        return SChapter.create().apply {
            url = chapter.id
            name = chapter.name
            // v1.5.12 (M4, Lane 6 AT-RISK 2): the PERSISTED
            // number wins over the caller's number — every
            // cold rebuild site (reader binding, registration
            // refresh, fetcher) derives its number from the
            // chapter TITLE, which is -1/null for an unnumbered
            // title ("Prologue", named specials), while
            // [mapChapters] persisted the extension's true
            // number inside the memo payload. The caller's
            // number is the fallback; -1 only when neither
            // knows.
            chapter_number = persistedChapterNumber(chapter.memo)
                ?: chapter.number ?: -1f
            // v1.5.8 (E1): the round trip back — an extension
            // may key behaviour on the credit it supplied.
            scanlator = chapter.scanlator
                ?.ifBlank { null }?.trim()
            date_upload = chapter.dateText?.toLongOrNull() ?: 0L
            // v1.5.11: the memo round trip back — parse the
            // persisted JSON text into the donor JsonObject.
            // A corrupt/absent text leaves the create()
            // default (empty object): honest absence, and a
            // parse failure must never fail the page call.
            // v1.5.12 (M4): the host's number key is STRIPPED
            // on the way back — the extension receives its own
            // memo entries verbatim, exactly as it minted
            // them; the key is host bookkeeping in the
            // persisted payload only.
            chapter.memo?.takeIf { it.isNotBlank() }?.let { text ->
                runCatching {
                    Json.parseToJsonElement(text) as? JsonObject
                }.getOrNull()?.let { parsed ->
                    memo = JsonObject(
                        parsed.filterKeys { it != MEMO_CHAPTER_NUMBER_KEY }
                    )
                }
            }
        }
    }

    // ── v1.5.11 (transplant): the donor HttpSource seam ─────
    // The transplanted HttpPageLoader (komikku/loader) binds
    // to this adapter exactly where the donor's binds to its
    // HttpSource: getPageList / getImageUrl / getImage, as
    // plain suspend calls on the caller's IO context — the
    // donor has no bridge executor, and neither does this
    // road. Each call is namespace-bracketed via [bracketed]
    // (the same bracket [pageBytes] has always used); the
    // facade's blocking bridge (callMihon) is NOT crossed.

    /**
     * v1.5.17 (F1): the chapter's EFFECTIVE memo verdict —
     * true when the SChapter [schapterOf] would hand the
     * extension carries an EMPTY memo. This is the heal
     * trigger's exact test (V1513-LANE-A §5): the retained
     * snapshot is consulted first (a chapter-list fetch this
     * process already restored the state — no heal needed),
     * and the cold rebuild parses the persisted row text and
     * strips the host's number key, so a legacy row whose
     * memo is NULL — or whose payload carries only the host
     * key — reads missing even though the rebuilt object is
     * an (empty) JsonObject, never null. The reader roads
     * consult this before `getPageList` and run ONE
     * chapter-list refresh when it reads true (see
     * [MangaMemoHeal]).
     */
    fun effectiveMemoMissing(chapter: SourceChapter): Boolean =
        schapterOf(chapter).memo.isEmpty()

    /** Donor `Source.getPageList`: the chapter's Mihon pages,
     * from the fully-restored SChapter ([schapterOf]). */
    suspend fun getPageList(chapter: SourceChapter): List<Page> =
        bracketed {
            val pages = mihon.getPageList(schapterOf(chapter))
            if (pages.isEmpty()) {
                throw SourceException("the chapter pages weren't found")
            }
            pages
        }

    /** Donor `HttpSource.getImageUrl`: resolve [page]'s image
     * address through the source's own image-url path, write
     * the absolutised address back onto the page (the donor
     * loader does the same assignment), and return it. */
    suspend fun getImageUrl(page: Page): String {
        val http = mihon as? HttpSource
            ?: throw SourceException("the source has no page images")
        val resolved = bracketed {
            http.getImageUrl(page).takeIf { it.isNotBlank() }
                ?: page.url
        }
        val absolute = absolutise(resolved)
        page.imageUrl = absolute
        return absolute
    }

    /**
     * v1.5.12 (M5, Lane 6 AT-RISK 3): ONE session bracket for
     * a whole page load. [getImageUrl] and [getImage] are
     * each [bracketed] on their own (one
     * [PlayerWebViewSync.around] — store read/seed/flush +
     * harvest/vault merge — PER CALL), so a loader that calls
     * both paid two vault round-trips per page (≈1,176 on a
     * 588-page chapter). A caller that opens this bracket
     * around the pair pays ONE: [PlayerWebViewSync.around] is
     * re-entrant, so the per-call brackets inside detect the
     * bracket already in force and run without a second
     * seed/harvest. Seed-before / harvest-after semantics are
     * preserved per page; the session sync itself is never
     * skipped — callers that do NOT open this bracket keep
     * the per-call behaviour exactly.
     */
    suspend fun <T> withPageLoadBracket(block: suspend () -> T): T =
        bracketed(block)

    /** Donor `HttpSource.getImage`: one page's bytes through
     * the source's OWN image request (its imageRequest with
     * its headers, on its client). Throws in plain words on
     * an empty answer, like [pageBytes]. */
    suspend fun getImage(page: Page): ByteArray {
        val http = mihon as? HttpSource
            ?: throw SourceException("the source has no page images")
        val bytes = bracketed {
            http.getImage(page).use { response ->
                response.body?.bytes()
                    ?: throw SourceException(
                        "the page image came back empty"
                    )
            }
        }
        if (bytes.isEmpty()) {
            throw SourceException("the page image came back empty")
        }
        return bytes
    }

    /** Absolutise a possibly-relative address against the
     * source's base url (Mihon models mix absolute urls,
     * root-relative paths and protocol-relative hosts). */
    private fun absolutise(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url
        }
        val base = baseUrl
        if (base.isBlank()) return url
        return runCatching { URI(base).resolve(url).toString() }
            .getOrElse {
                base.trimEnd('/') + "/" + url.trimStart('/')
            }
    }

    // ── the call bridge ─────────────────────────────────────

    /**
     * v1.5.11 (point 10, ANR bound): the effective deadline
     * for a nominally unbounded wait. A requested finite
     * deadline passes through untouched. An "unbounded"
     * request (Long.MAX_VALUE — the bounded = false call
     * classes) resolves to a finite ceiling from NOW:
     * [UNBOUNDED_WAIT_CEILING_MS] for an ordinary (IO) waiter,
     * or [MAIN_THREAD_WAIT_BOUND_MS] when the waiting thread
     * is the Android main thread. The research convicted the
     * literal unbounded sleep-poll (MANGA-PAGES D7): a
     * main-thread arrival parked the UI for the full duration
     * of a stalled source call — a system ANR by construction.
     * Production facade calls are provably off-Main (the
     * guarded seam runs them on IO), so the main bound only
     * ever fires on a call-site bug, converting an ANR into a
     * plain-words failure; the IO ceiling leaves slow
     * donor-shaped walks (A1) their room while making every
     * wait in this adapter finite. The 50 ms poll slice
     * ([CALLER_POLL_MS]) is unchanged.
     */
    private fun effectiveWaitDeadlineNanos(
        requestedDeadlineNanos: Long
    ): Long {
        if (requestedDeadlineNanos != Long.MAX_VALUE) {
            return requestedDeadlineNanos
        }
        val boundMs = if (isMainThreadWait()) {
            MAIN_THREAD_WAIT_BOUND_MS
        } else {
            UNBOUNDED_WAIT_CEILING_MS
        }
        return System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(boundMs)
    }

    /** True when the CURRENT thread is the Android main
     * thread. Best-effort: off-Android (JVM gate tests, where
     * the Looper stubs throw) this reads false — the ordinary
     * ceiling then applies, exactly the pre-v1.5.11 behaviour
     * for those callers. */
    private fun isMainThreadWait(): Boolean = runCatching {
        val mainLooper = android.os.Looper.getMainLooper()
        mainLooper != null &&
            android.os.Looper.myLooper() == mainLooper
    }.getOrDefault(false)

    /**
     * Run one Mihon entry-point call: bracketed with this
     * package's namespace (the element re-applies it after
     * every suspension/thread hop), bridged to the facade's
     * blocking shape, with every failure converted to plain
     * words by [asSourceFailure].
     *
     * v1.5.4 (WS-1 item 2 — the interruptible bridge, donor
     * rule D2): the call itself runs on the shared bridge
     * executor (see [bridgeExecutor]); the CALLER awaits the
     * future with the bridge deadline. Timeout — or the
     * caller's own coroutine being cancelled (the job the seam
     * published in [BridgeCallContext], e.g. the book-page
     * bound firing) — cancels the work BOTH ways: the inner
     * coroutine job is cancelled (which cancels the source's
     * in-flight OkHttp calls, the cooperative half) AND the
     * worker thread is interrupted (which releases latch /
     * blocking waits that never suspend, the other half).
     * The caller never re-joins a stuck worker: a worker that
     * ignores both is bounded by the source's own client
     * timeouts (30/30/120) and simply occupies its slot
     * until it ends — the bridge's parallelism is capped
     * (v1.5.5: a limitedParallelism view over Dispatchers.IO,
     * see [bridgeExecutor]), so threads never accumulate
     * across timeouts.
     */
    /**
     * @param bounded v1.5.8 (A1): when false, NO whole-call
     * bridge deadline is imposed — the donor's per-call OkHttp
     * clocks (connect 30 / read 30 / call 120 s) are the only
     * deadlines, exactly as in Komikku. Used by the two call
     * classes the plan names: `getMangaUpdate` (via the
     * registry) and `chapterContent` (the page-list leg, whose
     * N sequential image-url fetches the 120 s envelope killed
     * one layer deeper — F1 M6). Caller cancellation still
     * stops the work both ways; only the clock is gone. Every
     * other bridged call (catalogue, details-class singles,
     * search) keeps the envelope.
     */
    private fun <T> callMihon(
        bounded: Boolean = true,
        block: suspend () -> T
    ): T {
        val work = BridgeWork { bracketed(block) }
        val future: Future<T> = try {
            bridgeExecutor.submit<T> { work.run() }
        } catch (e: RuntimeException) {
            // Rejection (bounded queue full) surfaces as a call
            // failure, never as a silent wait (§5.4).
            throw asSourceFailure(e)
        }
        // Caller-coroutine cancellation must reach the work:
        // the seam (ExtensionService.guarded) publishes the
        // calling coroutine's job for exactly this hop. A
        // caller with no published job (a direct facade call
        // from a plain thread) is bounded by the deadline
        // alone.
        //
        // The wait POLLS the future in short slices against
        // the caller job's state. invokeOnCompletion cannot
        // carry this signal on its own: it fires when the job
        // COMPLETES, and a job parked in the blocking wait
        // below completes only after the wait ends — the hook
        // alone left a cancelled caller parked for the whole
        // bridge deadline (the v1.5.4 gate's 8b failure). The
        // job's cancelled STATE is visible the moment
        // cancellation is requested, so the wait watches for
        // it and stops the work itself; the hook stays as the
        // completion-time backstop.
        val callerJob = BridgeCallContext.currentJob()
        val cancellation = callerJob?.invokeOnCompletion { cause ->
            if (cause != null) work.cancel(future)
        }
        try {
            val deadlineNanos = if (bounded) {
                System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(bridgeTimeoutMs)
            } else {
                // v1.5.11 (point 10): nominally unbounded, in
                // fact ceiling-bounded (and main-thread-bounded
                // far tighter) — see [effectiveWaitDeadlineNanos].
                effectiveWaitDeadlineNanos(Long.MAX_VALUE)
            }
            while (true) {
                if (callerJob?.isCancelled == true) {
                    work.cancel(future)
                    throw asSourceFailure(
                        CancellationException(
                            "the caller stopped waiting (cancelled)"
                        )
                    )
                }
                val remainingMs = if (deadlineNanos == Long.MAX_VALUE) {
                    CALLER_POLL_MS
                } else {
                    TimeUnit.NANOSECONDS.toMillis(
                        deadlineNanos - System.nanoTime()
                    )
                }
                if (remainingMs <= 0L) {
                    work.cancel(future)
                    throw asSourceFailure(
                        CancellationException(
                            "bridge deadline ${bridgeTimeoutMs}ms exceeded"
                        )
                    )
                }
                try {
                    return future.get(
                        minOf(remainingMs, CALLER_POLL_MS),
                        TimeUnit.MILLISECONDS
                    )
                } catch (e: TimeoutException) {
                    // Slice elapsed — re-check the deadline
                    // and the caller's job, then keep waiting.
                }
            }
        } catch (e: ExecutionException) {
            throw asSourceFailure(e.cause ?: e)
        } catch (e: CancellationException) {
            // The future was cancelled (the caller-job hook
            // above) or this thread's wait was cancelled —
            // either way the call is over, worded like the
            // source's own timeout machinery always was.
            work.cancel(future)
            throw asSourceFailure(e)
        } catch (e: InterruptedException) {
            work.cancel(future)
            Thread.currentThread().interrupt()
            throw asSourceFailure(e)
        } finally {
            cancellation?.dispose()
        }
    }

    /**
     * One bridged call's worker body + its two stop levers.
     * [run] executes on a bridge thread; [cancel] is callable
     * from any thread (deadline expiry, caller cancellation).
     */
    private class BridgeWork<T>(
        private val block: suspend () -> T
    ) {
        @Volatile
        private var innerJob: Job? = null

        fun run(): T = runBlocking {
            innerJob = coroutineContext[Job]
            try {
                block()
            } finally {
                innerJob = null
            }
        }

        /** Stop the work: cancel the inner coroutine (reaches
         * suspending OkHttp awaits) and interrupt the worker
         * thread via the future (releases blocking waits). */
        fun cancel(future: Future<*>) {
            innerJob?.cancel()
            future.cancel(true)
        }
    }

    /** The namespace-bracketed suspend segment (also the
     * shape [pageBytes] uses directly — it is already in a
     * coroutine when the service calls it).
     *
     * v1.5.0 (Part B): the bracket also carries the WebView
     * cookie sync ([PlayerWebViewSync]) for this source's
     * host — an extension that runs the site's own JS client
     * in its own WebView (the Comix technique) starts from
     * the saved session and hands its earned cookies back
     * to the player's jar, the donor's observable behaviour
     * over Xreader's store-backed rule. */
    private suspend fun <T> bracketed(block: suspend () -> T): T {
        val host = baseUrl.takeIf { it.isNotBlank() }
            ?.let { runCatching { URI(it).host }.getOrNull() }
        return PlayerWebViewSync.around(listOfNotNull(host)) {
            withContext(PlayerNamespaceElement(packageName)) { block() }
        }
    }

    /**
     * Plain words for anything a source can throw, in the
     * app's error shapes: HTTP answers named by code, the
     * player's known gaps (missing class, script engine,
     * OkHttp version) worded by [PlayerFailureWords], a lost
     * site as a lost site, and a source's own readable
     * message kept as-is. Nothing raw crosses.
     */
    private fun asSourceFailure(t: Throwable): SourceException =
        when {
            t is SourceException -> t
            t is HttpException ->
                SourceException("the site answered HTTP ${t.code}")
            t is CancellationException ->
                SourceException("the source took too long to answer")
            isPlayerGap(t) ->
                SourceException(PlayerFailureWords.describe(t).reason)
            t is IOException ->
                SourceException("the site couldn't be reached")
            else -> SourceException(
                t.message?.takeIf { it.isNotBlank() }?.take(300)
                    ?: "the source failed (${t.javaClass.simpleName})"
            )
        }

    /** The gaps [PlayerFailureWords] has wording for: a class
     * the app doesn't carry (anywhere in the chain), or the
     * script-engine stub's named refusal. */
    private fun isPlayerGap(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { e ->
            PlayerFailureWords.missingClassOf(e) != null ||
                (e is UnsupportedOperationException &&
                    e.message?.contains("JavaScriptEngine") == true)
        }

    // ── retained state ──────────────────────────────────────
    // v1.5.11 (transplant, Verdict 1 — sift adaptation 2): the
    // retained maps live on the COMPANION (below), not the
    // instance. Keiyoushi lookups deliberately skip the
    // adapter cache in ExtensionService.loadExtension, so a
    // fresh adapter instance is built per lookup and
    // instance-held caches died with it — the very churn the
    // retained SChapter snapshots (hunk 1A) exist to survive.
    // Plain companion maps + named bound constants stand in
    // for Gemini's invented CompanionCache. Every key is
    // namespaced by this source's id ([chapterCacheKey] /
    // [pageCacheKey]), so sources sharing the companion maps
    // can never read each other's entries. All access stays
    // synchronized on the map itself, as before.

    /** The pagesCache key for a chapter: namespaced source id
     * + the chapter's facade id (its SChapter.url). */
    private fun chapterCacheKey(chapterId: String): String =
        "$namespacedId|$chapterId"

    /** The retainedPages key for a page url (or its lazy
     * #xreaderPage= registration key): namespaced the same
     * way. The bridge registration itself keeps the RAW url —
     * only this adapter-internal map is namespaced. */
    private fun pageCacheKey(url: String): String =
        "$namespacedId|$url"

    companion object {
        /**
         * v1.5.12 (M4, Lane 6 AT-RISK 2): the host-namespaced
         * key under which a chapter's true number rides inside
         * its persisted memo payload (the chapters.memo column
         * — opaque JSON the host already persists per chapter;
         * the Room row has NO number column and schema stays
         * v12). Namespaced (`xreader.*`, the donor memo's own
         * key convention) so it can never collide with a key
         * an extension mints for itself; if an extension DID
         * mint this exact key, its value stands (the payload
         * is the extension's first — see
         * [memoTextWithChapterNumber]).
         */
        const val MEMO_CHAPTER_NUMBER_KEY = "xreader.chapter_number"

        /**
         * v1.5.12 (M4): the persisted memo payload for one
         * chapter — the extension's own memo entries verbatim,
         * plus [MEMO_CHAPTER_NUMBER_KEY] carrying the true
         * [chapterNumber] when the extension knows one (>= 0)
         * and did not already mint that key itself. Null when
         * the result would be an empty object (the v1.5.11
         * honest-absence rule: no number known AND no memo
         * minted = null, never a fabricated "{}").
         */
        fun memoTextWithChapterNumber(
            memo: JsonObject,
            chapterNumber: Float?
        ): String? {
            val number = chapterNumber?.takeIf { it >= 0f }
            if (number == null) {
                return memo.takeIf { it.isNotEmpty() }?.toString()
            }
            if (memo.containsKey(MEMO_CHAPTER_NUMBER_KEY)) {
                return memo.toString()
            }
            val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(memo)
            merged[MEMO_CHAPTER_NUMBER_KEY] =
                kotlinx.serialization.json.JsonPrimitive(number)
            return JsonObject(merged).toString()
        }

        /**
         * v1.5.12 (M4): the true chapter number a persisted
         * memo payload carries (see [MEMO_CHAPTER_NUMBER_KEY]),
         * or null when the payload is absent, corrupt, or
         * carries no usable number — the caller's own number
         * (title parse) is then the fallback, exactly as
         * before. Negative values are not numbers (the donor's
         * -1 = unknown), so they read as absent too.
         */
        fun persistedChapterNumber(memoText: String?): Float? {
            val text = memoText?.takeIf { it.isNotBlank() } ?: return null
            val parsed = runCatching {
                Json.parseToJsonElement(text) as? JsonObject
            }.getOrNull() ?: return null
            val element = parsed[MEMO_CHAPTER_NUMBER_KEY]
                as? kotlinx.serialization.json.JsonPrimitive ?: return null
            return element.content.toFloatOrNull()?.takeIf { it >= 0f }
        }

        /** v1.5.4 (§0.2, pinned): the bridge deadline for one
         * Mihon call — aligned with the player client's
         * callTimeout (NetworkHelper's 30/30/120, unchanged). */
        const val BRIDGE_CALL_TIMEOUT_MS = 120_000L

        /** How often the bridge wait re-checks its deadline
         * and the caller job's cancelled state (see callMihon). */
        const val CALLER_POLL_MS = 50L

        const val MAX_CACHED_CHAPTERS = 24
        const val MAX_RETAINED_PAGES = 2048

        /** v1.5.5 (§1, Amendments Hole A): the ONE bridge
         * executor, shared by every adapter instance.
         * v1.5.4's fixed 4-thread ThreadPoolExecutor becomes
         * a `Dispatchers.IO.limitedParallelism(16)` view —
         * bridge work draws on the shared IO pool's threads
         * (daemon-equivalent: the pool never holds the
         * process), capped at 16 concurrent bridged calls,
         * so adapter rebuilds and repeated timeouts can
         * never accumulate threads.
         *
         * Rejection choice (documented per spec): a
         * limitedParallelism view has NO bounded queue and
         * no AbortPolicy — submissions queue on the IO
         * dispatcher instead of rejecting. The
         * [DispatcherExecutorService] wrapper restores the
         * submit/Future shape [callMihon] has always used
         * (a FutureTask, whose cancel(true) interrupts the
         * runner — the bridge's interrupt lever survives
         * intact); a submission that itself throws (the
         * dispatcher refusing the task) still surfaces as a
         * call failure via callMihon's catch, never as a
         * silent wait, and a task queued past its deadline
         * is cancelled by the same deadline path as before,
         * so nothing can wait unboundedly. */
        val bridgeExecutor: ExecutorService = DispatcherExecutorService(
            Dispatchers.IO.limitedParallelism(BRIDGE_PARALLELISM)
                .asExecutor()
        )

        /** v1.5.5 (§1): concurrent bridged Mihon calls. */
        const val BRIDGE_PARALLELISM = 16

        // ── v1.5.5 (§1): the single-flight registry state ──
        // Companion-held (see [runMangaUpdate]): adapter
        // instances are rebuilt per resolve over the same
        // live source, so the registry must outlive them.

        /** Monitor for registry bookkeeping ONLY — held for
         * map reads/writes, never across a wait. */
        private val updateRegistryLock = Any()

        /** Registry alias → the in-flight shared update.
         * A call registers under every alias it carries
         * (stable key alias + url alias), so any later
         * caller for the same manga finds it by either. */
        private val updateCallsByAlias =
            HashMap<String, SharedUpdateCall>()

        /** The adapter-owned scope shared update calls run
         * in (Amendments 2, red-flag 3): a SupervisorScope
         * on Dispatchers.IO, never a caller's scope, so a
         * caller navigating away can never cancel another
         * caller's chapters. */
        private val updateScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // ── v1.5.11 (transplant): companion retained maps ──
        // See the retained-state note at the instance seam:
        // these survive adapter-instance churn; every key is
        // namespaced per source. Access is synchronized on
        // the map at every call site.

        /** Page records of recent chapterContent() calls,
         * keyed by [chapterCacheKey] (namespaced chapter id)
         * — bounded access-ordered LRU, the declarative
         * engine's shape (a miss costs a re-read; callers
         * already treat null that way). */
        private val pagesCache =
            object : LinkedHashMap<String, ChapterPages>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, ChapterPages>
                ): Boolean = size > MAX_CACHED_CHAPTERS
            }

        /** Resolved Mihon [Page]s by [pageCacheKey] (the
         * page's final image url, or its lazy #xreaderPage=
         * registration key) — the download road's way back to
         * the source's image request. Bounded like
         * [pagesCache]; an evicted page simply falls back to
         * the caller's default byte path. */
        private val retainedPages =
            object : LinkedHashMap<String, Page>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Page>
                ): Boolean = size > MAX_RETAINED_PAGES
            }

        /** v1.5.11 (hunk 1A): full donor SChapter snapshots
         * taken at chapter-list time, keyed by
         * [chapterCacheKey] — the memo-carrying originals
         * [schapterOf] restores at page-list time. Bounded
         * like the other retained maps; an evicted snapshot
         * simply falls back to the persisted-row rebuild
         * (schema v12 memo), which is the same state one
         * process later. */
        private val retainedChapters =
            object : LinkedHashMap<String, SChapter>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, SChapter>
                ): Boolean = size > MAX_RETAINED_CHAPTERS
            }

        /** v1.5.11: the retained-snapshot bound (see
         * [retainedChapters]). */
        const val MAX_RETAINED_CHAPTERS = 2048

        /** v1.5.11 (point 10, ANR bound): the finite ceiling
         * over a nominally "unbounded" bridge wait (the
         * bounded = false call classes — getMangaUpdate and
         * chapterContent). The donor imposes no whole-call
         * envelope and slow chapter walks legitimately run
         * for minutes, so the ceiling is generous; what it
         * ends is the LITERAL unboundedness the research
         * convicted (V1511-RESEARCH-MANGA-PAGES D7): no
         * caller wait in this adapter can park forever. */
        const val UNBOUNDED_WAIT_CEILING_MS = 600_000L

        /** v1.5.11 (point 10): the wait bound when the waiting
         * thread IS the Android main thread — below the
         * system's ~5 s input-dispatch ANR threshold, so a
         * main-thread arrival (a call-site bug: every
         * production facade call runs on IO through the
         * guarded seam) fails in plain words instead of
         * parking the UI into a system ANR. */
        const val MAIN_THREAD_WAIT_BOUND_MS = 4_500L
    }

    /**
     * v1.5.5 (§1): one in-flight shared `getMangaUpdate`
     * for one manga. [outcome] carries the donor's atomic
     * result (or its failure) to every waiter; [waiters]
     * is the reference count of callers currently parked
     * on it (guarded by the companion's registry lock) —
     * bookkeeping that proves the sharing, never a cancel
     * lever: the call completes detached regardless.
     */
    private class SharedUpdateCall(
        val aliases: List<String>,
        val fetchDetails: Boolean,
        val fetchChapters: Boolean
    ) {
        val outcome = CompletableFuture<SMangaUpdate>()

        var waiters: Int = 0

        /** True when this call's flags cover a caller's
         * needs — the subset-join rule. */
        fun covers(needDetails: Boolean, needChapters: Boolean): Boolean =
            (!needDetails || fetchDetails) &&
                (!needChapters || fetchChapters)
    }
}

/**
 * v1.5.5 (§1): an [ExecutorService] face over a coroutine
 * dispatcher's [Executor] (see the bridgeExecutor comment
 * for the choice). Only execute/submit semantics are real
 * — the underlying dispatcher is process-lifetime, so the
 * shutdown surface is a formality that flips the flag and
 * refuses later submissions. AbstractExecutorService
 * supplies submit() as a FutureTask, preserving the
 * bridge's Future.cancel(true) interrupt lever.
 */
private class DispatcherExecutorService(
    private val delegate: Executor
) : AbstractExecutorService() {

    @Volatile
    private var shutdown = false

    override fun execute(command: Runnable) {
        if (shutdown) {
            throw RejectedExecutionException(
                "the mihon bridge executor is shut down"
            )
        }
        delegate.execute(command)
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): List<Runnable> {
        shutdown = true
        return emptyList()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit
    ): Boolean = true
}

/**
 * v1.5.4 (WS-1 item 2): the calling coroutine's [Job], published
 * by the seam for the duration of one facade call.
 *
 * The facade is blocking by contract (D2 pins the ABI), so a
 * Mihon call's caller sits on a thread awaiting a future — plain
 * coroutine cancellation cannot cross that hop on its own. The
 * seam ([app.xreader.data.extension.ExtensionService.guarded],
 * the one door every facade call passes through) publishes its
 * coroutine's job here, on the thread that will invoke the
 * facade; the adapter's bridge reads it on that same thread
 * before submitting its work and registers the cancellation
 * hook there. The value follows the THREAD, deliberately — the
 * same discipline as [PlayerContextState]'s namespace.
 */
object BridgeCallContext {

    private val current = ThreadLocal<Job?>()

    /** The job published for the call in flight on this
     * thread, or null when the caller is not a coroutine the
     * seam knows about. */
    fun currentJob(): Job? = current.get()

    /** Run [block] with [job] published on this thread,
     * restoring whatever was there before (nesting-safe). */
    fun <T> withJob(job: Job?, block: () -> T): T {
        val previous = current.get()
        if (job == null) current.remove() else current.set(job)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}

/**
 * K4: installs the player's per-extension namespace on
 * whichever thread a coroutine segment runs on. Mihon calls
 * suspend and resume across OkHttp dispatcher threads; the
 * injected Application resolves its namespace from the
 * CURRENT thread on every call ([PlayerContextState]), so a
 * plain `withNamespace` around the outer call is not enough —
 * the element re-applies the namespace after every hop and
 * restores the thread's previous value on suspension.
 */
internal class PlayerNamespaceElement(
    private val namespace: String
) : ThreadContextElement<String?> {

    companion object Key : CoroutineContext.Key<PlayerNamespaceElement>

    override val key: CoroutineContext.Key<PlayerNamespaceElement>
        get() = Key

    override fun updateThreadContext(
        context: CoroutineContext
    ): String? = PlayerContextState.swapNamespace(namespace)

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: String?
    ) {
        PlayerContextState.swapNamespace(oldState)
    }
}
