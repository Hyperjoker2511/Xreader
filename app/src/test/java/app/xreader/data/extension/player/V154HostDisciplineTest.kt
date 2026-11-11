package app.xreader.data.extension.player

import android.app.Application
import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.FakeDexLoader
import app.xreader.data.extension.FakeJsRuntime
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.SourceBookSync
import app.xreader.data.extension.testSessionStore
import app.xreader.data.extension.testVault
import app.xreader.data.files.BookStorage
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.4 WS-1 item 8 — the manga host discipline proofs
 * (8a, 8b, 8d + item 5's acceptance). Every proof carries its
 * own hard timeout (§0.4.3): a hang is a test failure reported
 * by the timeout, never a parked gate.
 *
 * The Main dispatcher in 8a/8d is a dedicated single thread
 * (installed via Dispatchers.setMain, the suite's established
 * mechanism) standing in for the Android main thread, so the
 * device deadlock's structure is real: the calling coroutine
 * runs ON that thread, and anything it parks waiting for a
 * Main-posted continuation deadlocks exactly as on device —
 * while the test thread stays free to observe the timeout.
 *
 * Production shape throughout: [PlayerWebViewSync] INSTALLED
 * as AppContainer installs it (AppContainer.kt:1229 — not the
 * test rig's usual no-op) and a session cookie present for
 * the source's host (the seed trigger).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V154HostDisciplineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "eu.kanade.tachiyomi.extension.en.fakepanels"
    private val namespaced = "keiyoushi:$pkg:777"

    private val mainExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "v154-main").apply { isDaemon = true }
    }
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()
    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private lateinit var source: ProbeSource
    private lateinit var service: ExtensionService
    private lateinit var bookDao: FakeBookDao
    private lateinit var chapterDao: FakeChapterDao
    private lateinit var extDao: FakeInstalledExtensionDao
    private var rowId: Long = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        source = ProbeSource()
        extDao = FakeInstalledExtensionDao()
        val store = ExtensionStore(tmp.newFolder())
        val sessionStore = testSessionStore()
        val factory = HostContextFactory(
            store, sessionStore, FakeTransport { FakeTransport.ok("") }
        )
        service = ExtensionService(
            extDao, store, factory, FakeDexLoader(),
            keiyoushiLiveSources = { mapOf(namespaced to source) },
            keiyoushiRatingProvider = { ContentRating.MIXED },
            keiyoushiPlayerEnabled = { true },
            sessionStore = sessionStore
        ) { FakeJsRuntime() }
        bookDao = FakeBookDao()
        chapterDao = FakeChapterDao()
        // Production-shaped WebView cookie sync + the seed
        // trigger: a session cookie for the source's host.
        val vault = testVault()
        val storeWithCookie = SessionStore(vault)
        PlayerWebViewSync.install(
            RuntimeEnvironment.getApplication(), storeWithCookie, vault
        )
        runBlocking {
            storeWithCookie.mergeCookies(
                SessionStoreCookieJar.PLAYER_SESSION_KEY,
                "panels.test",
                mapOf("session" to "s-1")
            )
            rowId = extDao.insert(
                InstalledExtension(
                    repoId = 1,
                    displayName = "Fake Panels",
                    packageId = pkg,
                    versionName = "1.6.5",
                    versionCode = 106005,
                    kind = app.xreader.data.db.ExtensionKind.KEIYOUSHI,
                    artifactPath = null,
                    sha256 = "abc",
                    apiLevel = 16,
                    languages = "en",
                    sourceIds = "777",
                    enabled = true,
                    trustState = TrustState.TRUSTED,
                    permissionsJson = ExtensionPermissions(
                        domains = listOf("panels.test"),
                        canUseNetwork = true,
                        dexOptIn = true
                    ).encode(),
                    installedAt = 0,
                    updatedAt = 0
                )
            )
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    private fun sync(
        pageLoadTimeoutMs: Long = SourceBookSync.BOOK_PAGE_LOAD_TIMEOUT_MS
    ): SourceBookSync = SourceBookSync(
        bookDao = bookDao,
        chapterDao = chapterDao,
        sourceMapDao = FakeChapterSourceMapDao(),
        extensionDao = extDao,
        service = service,
        storage = BookStorage(tmp.newFolder()),
        transport = FakeTransport { request ->
            if (request.url.contains("/covers/")) {
                FakeTransport.ok("fake-cover-bytes")
            } else {
                FakeTransport.ok("")
            }
        },
        nowMs = { 1_000L },
        pageLoadTimeoutMs = pageLoadTimeoutMs
    )

    private suspend fun bookRow(): Book {
        val id = bookDao.insert(
            Book(
                title = "Alpha",
                author = "",
                description = "",
                sourceType = SourceType.EXTENSION,
                extensionId = rowId,
                remoteId = SourceBookIds.encode(namespaced, "/manga/alpha"),
                addedAt = 0,
                updatedAt = 0,
                inLibrary = true
            )
        )
        return bookDao.getById(id)!!
    }

    /** Run [block] on a side thread and fail (not hang) when
     * it outlives [seconds] — §0.4.3's own-timeout discipline
     * for calls that could park the calling thread itself. */
    private fun <T> runWithHardTimeout(seconds: Long, block: () -> T): T {
        val pool = Executors.newSingleThreadExecutor()
        try {
            return pool.submit<T> { block() }.get(seconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            fail("call did not return within ${seconds}s (${e.javaClass.simpleName})")
            throw e
        } finally {
            pool.shutdownNow()
        }
    }

    // ── 8a: production sync, Main-attempted facade call ─────

    @Test
    fun item8a_facadeCallFromMainWithProductionSyncCompletes() {
        val attempt = mainScope.async {
            val handle = (service.source(rowId, namespaced) as AppResult.Ok).value
            service.guarded(handle) { it.popular(1) }
        }
        val result = runBlocking { withTimeoutOrNull(20_000) { attempt.await() } }
        if (result == null) {
            fail(
                "8a: facade call from Main with production PlayerWebViewSync " +
                    "installed did not complete within 20 s (deadlock)"
            )
        }
        assertTrue(result is AppResult.Ok)
        assertEquals(1, (result as AppResult.Ok).value.books.size)
    }

    // ── 8d: enrichment-from-Main deadlock proof ─────────────

    @Test
    fun item8d_enrichmentFromMainCompletes() {
        val book = runBlocking { bookRow() }
        val attempt = mainScope.async { sync().enrichment(book) }
        val result = runBlocking { withTimeoutOrNull(20_000) { attempt.await() } }
        if (result == null) {
            fail(
                "8d: enrichment from Main with production PlayerWebViewSync " +
                    "installed did not complete within 20 s (deadlock)"
            )
        }
        assertNotNull(result!!.related)
        assertEquals(1, result.related!!.size)
        assertEquals("Beta", result.related!!.single().title)
    }

    // ── item 5 acceptance: independence of the halves ───────

    // v1.5.5 (§1): item 5's two tests are REWRITTEN for the
    // fused contract. Pre-fix they asserted the split call's
    // landing independence (details land even when the
    // chapters CALL fails or is cut off). Under the donor's
    // atomic one-call shape there is no separate chapters
    // call to fail: a fused call that throws surfaces that
    // failure on BOTH halves, and a fused call cut off by
    // the bound lands NEITHER half — item 5's intent (apply
    // independence) survives only once a result has landed,
    // which the v155 rendezvous tests below prove. What
    // these two tests pin now is the fused failure shape
    // itself: atomic, honest, and never a guard violation.
    //
    // v1.5.10 (I6b): the bound test below is re-derived once
    // more, against v1.5.8's A1 split bound, which superseded
    // the v1.5.5 shape it asserted: for a PLAYER source the
    // page bound no longer converts to an Err at all —
    // SourceBookSync.refresh's player branch bounds only the
    // WAIT, returns the book (details-landed or stored) with
    // chapters still loading, and registers the completion
    // consumer that lands the chapters when the surviving
    // shared call finishes (SourceBookSync.kt: the A1 player
    // branch + registerPlayerCompletionConsumer). The test
    // now pins THAT: Ok at the bound, the shared call alive
    // and uninterrupted, and the consumer landing the
    // chapters in Room after the source is released.

    @Test
    fun item5_fusedUpdateFailureSurfacesOnBothHalves() {
        source.chaptersFailure = RuntimeException("Chapter list moved")
        val book = runBlocking { bookRow() }
        val result = runBlocking { sync().refresh(book) }
        assertTrue(result is AppResult.Err)
        // Exactly ONE donor call carried the whole book
        // page, and its failure is the refresh's verdict —
        // no half-applied state from a call that threw.
        assertEquals(1, source.updateCalls.get())
        val stored = runBlocking { bookDao.getById(book.id) }!!
        assertEquals("", stored.author)
        assertTrue(
            runBlocking { chapterDao.forBook(book.id) }.isEmpty()
        )
    }

    @Test
    fun item5_boundCutsTheWaiterNotTheSharedUpdate() {
        source.hangChapters = true
        val book = runBlocking { bookRow() }
        val result = runWithHardTimeout(20) {
            runBlocking { sync(pageLoadTimeoutMs = 2_000).refresh(book) }
        }
        // v1.5.8 (A1): the player bound cuts the WAIT, not the
        // refresh — refresh returns Ok (the stored book, its
        // chapters still loading) and the completion consumer
        // is registered on the surviving deferred. (The
        // v1.5.5-era assertion expected Err here; A1's split
        // bound superseded it — see the item-5 header.)
        assertTrue(result is AppResult.Ok)
        // Amendments 2 (red-flag 3): the bound cut the
        // WAITER, not the shared call — the donor call is
        // still parked in the fake, uninterrupted, running
        // detached in the adapter's own scope.
        assertTrue(source.inFlight.contains("/manga/alpha"))
        assertTrue(
            "a cut-off waiter must never interrupt the shared call",
            !source.chaptersInterrupted.get()
        )
        // Releasing the source lets the detached call finish;
        // the A1 completion consumer then lands the chapters
        // in Room with no second refresh (the already-open
        // page would see them by observation).
        source.hangChapters = false
        source.releaseHang()
        val landed = runWithHardTimeout(20) {
            runBlocking {
                withTimeoutOrNull(15_000) {
                    while (chapterDao.forBook(book.id).isEmpty()) {
                        delay(50)
                    }
                    chapterDao.forBook(book.id)
                }
            }
        }
        assertNotNull(
            "the completion consumer never landed the chapters",
            landed
        )
        assertEquals(1, landed!!.size)
        assertEquals("Chapter 1", landed.single().title)
        // A follow-up chapters read then succeeds (joining it
        // or starting fresh), proving the registry cleared.
        val handle = runBlocking {
            (service.source(rowId, namespaced) as AppResult.Ok).value
        }
        val chapters = runWithHardTimeout(20) {
            runBlocking {
                service.guarded(handle) {
                    it.chapters(
                        app.xreader.extensionapi.SourceBook(
                            id = "/manga/alpha", title = "Alpha"
                        )
                    )
                }
            }
        }
        assertTrue(chapters is AppResult.Ok)
        assertEquals(1, (chapters as AppResult.Ok).value.size)
    }

    // ── 8b: the never-returning source ──────────────────────

    @Test
    fun item8b_neverReturningSourceDeadlineStopsWorkAndPoolSurvives() {
        source.hangChapters = true
        val book = runBlocking { bookRow() }
        // (i) the page-level call returns at the injected
        // deadline — not a hang. v1.5.10 (I6b): since v1.5.8
        // (A1) the PLAYER bound returns the book with its
        // chapters still loading (an Ok — the honest state
        // is carried by the pending chapters + the registered
        // completion consumer), not the v1.5.5-era Err this
        // assertion was written against; the never-returning
        // source still cannot park the page past its bound.
        val started = System.nanoTime()
        val result = runWithHardTimeout(20) {
            runBlocking { sync(pageLoadTimeoutMs = 1_500).refresh(book) }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result is AppResult.Ok)
        assertTrue(
            "page call must end near its 1.5 s bound, took ${elapsedMs}ms",
            elapsedMs < 15_000
        )
        assertTrue(
            "no chapters may land while the source never answers",
            runBlocking { chapterDao.forBook(book.id) }.isEmpty()
        )
        // (ii) v1.5.5 (§1, Amendments 2 red-flag 3) — the
        // assertion's MEANING inverts: the page call was a
        // fused update running as a SHARED call in the
        // adapter-owned scope, so the bound stops the WAITER
        // and the underlying donor call is deliberately NOT
        // interrupted out from under any other waiter — it
        // is still parked in the source, bounded by the
        // bridge deadline inside the call itself. (Pre-fix
        // this asserted the bridge worker's interruption;
        // that discipline still governs NON-shared bridged
        // calls, whose deadline path is unchanged.)
        assertTrue(
            "the shared update must still be in flight after the bound",
            source.inFlight.contains("/manga/alpha")
        )
        assertTrue(
            "the shared update must not be interrupted by a waiter's bound",
            !source.chaptersInterrupted.get()
        )
        // Release the source: the detached call completes,
        // the registry drains, and the executor is observed
        // unpoisoned by the calls below.
        source.releaseHang()
        // The A1 completion consumer lands the released call's
        // chapters in Room (the same landing the bound test
        // above proves) — the detached call's work is not lost.
        val landed = runWithHardTimeout(20) {
            runBlocking {
                withTimeoutOrNull(15_000) {
                    while (chapterDao.forBook(book.id).isEmpty()) {
                        delay(50)
                    }
                    chapterDao.forBook(book.id)
                }
            }
        }
        assertNotNull(
            "the completion consumer never landed the chapters",
            landed
        )
        // (iii) the executor is not poisoned: a subsequent
        // call through the same adapter succeeds (and the
        // chapters call itself works once the source answers).
        source.hangChapters = false
        val handle = runBlocking {
            (service.source(rowId, namespaced) as AppResult.Ok).value
        }
        val popular = runBlocking { service.guarded(handle) { it.popular(1) } }
        assertTrue(popular is AppResult.Ok)
        val chapters = runBlocking {
            service.guarded(handle) {
                it.chapters(
                    app.xreader.extensionapi.SourceBook(
                        id = "/manga/alpha", title = "Alpha"
                    )
                )
            }
        }
        assertTrue(chapters is AppResult.Ok)
        assertEquals(1, (chapters as AppResult.Ok).value.size)
    }

    // ── v1.5.5 (§1): the rendezvous proofs ─────────────────
    // Against the donor-faithful guarded fake above. Test
    // (i) is constructed to FAIL on the pre-fix tree: the
    // sync layer launched details and chapters as two
    // concurrent getMangaUpdate calls, the fake's entry
    // delay holds the first inside the guard while the
    // second enters, the donor check() throws, and the
    // refresh sinks with 0 chapters (the v1.5.4 device
    // failure). Post-fix the whole page is one fused call.

    @Test
    fun v155_refreshAgainstGuardedSourceLandsChaptersInOneFusedCall() {
        val book = runBlocking { bookRow() }
        val result = runWithHardTimeout(30) {
            runBlocking { sync().refresh(book) }
        }
        assertTrue(
            "refresh against the guarded source must succeed, was $result",
            result is AppResult.Ok
        )
        // ONE donor call carried details + chapters.
        assertEquals(1, source.updateCalls.get())
        val stored = runBlocking { bookDao.getById(book.id) }!!
        assertEquals("Some Author", stored.author)
        assertEquals(1, stored.totalChapters)
        val chapters = runBlocking { chapterDao.forBook(book.id) }
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters.single().title)
    }

    @Test
    fun v155_twoConcurrentRefreshesOfOneBookBothComplete() {
        val book = runBlocking { bookRow() }
        val sync = sync()
        val outcomes = runWithHardTimeout(40) {
            runBlocking {
                withTimeoutOrNull(30_000) {
                    coroutineScope {
                        val first = async { sync.refresh(book) }
                        val second = async { sync.refresh(book) }
                        first.await() to second.await()
                    }
                }
            }
        }
        assertNotNull("both refreshes must finish inside 30 s", outcomes)
        val (first, second) = outcomes!!
        assertTrue("first refresh must succeed, was $first", first is AppResult.Ok)
        assertTrue("second refresh must succeed, was $second", second is AppResult.Ok)
        // The two refreshes shared or serialised the donor
        // call — never overlapped it (the guard would have
        // thrown): between one fused call (joined) and two
        // (queued) calls total.
        assertTrue(
            "expected 1–2 donor calls for two refreshes, saw ${source.updateCalls.get()}",
            source.updateCalls.get() in 1..2
        )
        val stored = runBlocking { bookDao.getById(book.id) }!!
        assertEquals(1, stored.totalChapters)
        val chapters = runBlocking { chapterDao.forBook(book.id) }
        assertTrue(chapters.any { it.title == "Chapter 1" })
    }

    @Test
    fun v155_fusedAdapterCallUnderGuardLandsBothHalves() {
        val handle = runBlocking {
            (service.source(rowId, namespaced) as AppResult.Ok).value
        }
        val result = runWithHardTimeout(20) {
            runBlocking {
                service.guarded(handle) { source ->
                    (source as MihonSourceAdapter).detailsAndChapters(
                        app.xreader.extensionapi.SourceBook(
                            id = "/manga/alpha", title = "Alpha"
                        ),
                        stableKey = "155-fused"
                    )
                }
            }
        }
        assertTrue(result is AppResult.Ok)
        val (details, chapters) = (result as AppResult.Ok).value
        assertEquals("Some Author", details.author)
        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters.single().name)
        assertEquals(1, source.updateCalls.get())
    }

    /** HttpSource fake: canned details, knob-driven chapters.
     * HttpSource carries supportsRelatedMangas = true (the
     * donor default for HTTP sources) — K1's gate condition. */
    private class ProbeSource(
        override val id: Long = 777L
    ) : HttpSource() {

        override val name: String = "Fake Panels"
        override val lang: String = "en"
        override val baseUrl: String = "https://panels.test"
        override val supportsLatest: Boolean = true

        @Volatile
        var chaptersFailure: Throwable? = null

        @Volatile
        var hangChapters: Boolean = false

        val chaptersInterrupted = AtomicBoolean(false)
        private val hangLatch = CountDownLatch(1)

        // ── v1.5.5 (§1): the donor guard, reproduced ──────
        // KeiSource.getMangaUpdate is a FINAL override that
        // check()s against an in-flight set keyed by
        // manga.url (flags do NOT exempt a call) and throws
        // the donor's exact message on any overlap
        // (V154-PROOF-FINDING-MANGA-CONCURRENCY §1.1). The
        // pre-v1.5.5 fake overrode getMangaUpdate with an
        // instant in-memory answer and no guard, which is
        // why the gate never saw the collision (§1.4).

        /** How many getMangaUpdate calls entered, total. */
        val updateCalls = AtomicInteger(0)

        /** Urls with a getMangaUpdate call in flight NOW. */
        val inFlight = ConcurrentHashMap.newKeySet<String>()

        /** Release a hangChapters park (the latch is one-way:
         * once released, later calls no longer hang). */
        fun releaseHang() {
            hangLatch.countDown()
        }

        companion object {
            /** The rendezvous window inside getMangaUpdate —
             * long enough that two halves launched together
             * always overlap in the guard. */
            const val ENTRY_DELAY_MS = 200L
        }

        override suspend fun getPopularManga(page: Int): MangasPage =
            MangasPage(
                listOf(
                    SManga(
                        url = "/manga/alpha", title = "Alpha",
                        thumbnail_url = "/covers/alpha.jpg"
                    )
                ),
                hasNextPage = false
            )

        override suspend fun getLatestUpdates(page: Int): MangasPage =
            getPopularManga(page)

        override suspend fun getSearchManga(
            page: Int, query: String, filters: FilterList
        ): MangasPage = getPopularManga(page)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean
        ): SMangaUpdate {
            updateCalls.incrementAndGet()
            // The donor guard, faithfully (see above): ANY
            // overlap for the same manga.url throws, whatever
            // the flags — the v1.5.4 device failure verbatim.
            check(inFlight.add(manga.url)) {
                "getMangaUpdate must not be called concurrently for same manga"
            }
            try {
                // The rendezvous window: a real donor call is
                // a live network fetch lasting at least tens
                // of milliseconds; this entry delay makes two
                // concurrently launched halves actually
                // collide inside the guard instead of passing
                // in sequence by luck (the §1.8 rendezvous).
                delay(ENTRY_DELAY_MS)
                if (fetchChapters) {
                    chaptersFailure?.let { throw it }
                    if (hangChapters) {
                        // Parked until releaseHang() (or an
                        // interrupt frees it).
                        try {
                            hangLatch.await()
                        } catch (e: InterruptedException) {
                            chaptersInterrupted.set(true)
                            throw e
                        }
                    }
                }
                val details = if (fetchDetails) {
                    SManga(
                        url = manga.url,
                        title = manga.title,
                        author = "Some Author",
                        description = "A story.",
                        thumbnail_url = "/covers/alpha-hd.jpg",
                        initialized = true
                    )
                } else {
                    manga
                }
                val list = if (fetchChapters) {
                    listOf(
                        SChapter(
                            name = "Chapter 1", url = "/ch/1",
                            date_upload = 1700000001000, chapter_number = 1f
                        )
                    )
                } else {
                    emptyList()
                }
                return SMangaUpdate(details, list)
            } finally {
                inFlight.remove(manga.url)
            }
        }

        override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> =
            listOf(
                SManga(
                    url = "/manga/beta", title = "Beta",
                    thumbnail_url = "/covers/beta.jpg"
                )
            )

        override suspend fun getPageList(chapter: SChapter): List<Page> =
            listOf(Page(0, url = "/read/p1", imageUrl = "https://cdn.test/1.jpg"))
    }
}
