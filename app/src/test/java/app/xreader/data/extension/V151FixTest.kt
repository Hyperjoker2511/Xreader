package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Chapter
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import app.xreader.ui.reader.RelinkResolution
import app.xreader.ui.sources.SearchSourceTimeoutException
import app.xreader.ui.sources.StreamingSearch
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.1: the fix round's pins —
 *  - item 2: RenderHealth's strike/cooldown/attempt table;
 *    the search fan-out's per-source timeout; a suppressed
 *    (search) fetch never touches the render tier; a
 *    never-finishing render is bounded by the fetch budget;
 *    cancelling the caller cancels the render job.
 *  - item 5: one failed render strikes the source onto the
 *    WebView fetch bridge; ad/tracker host matching.
 *  - item 4: the re-link ladder's resolution table.
 *  - item 6: the Browse-manga visibility seam — a player
 *    source whose rating the provider answers (the durable
 *    stamp) passes the default content filter; the SAME
 *    source with an unanswered rating fails closed, which
 *    is the exact v1.5.0 field condition (cold catalogue
 *    cache after a restart) the stamp removes.
 */
class V151FixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun resetRenderHealth() {
        RenderHealth.reset()
    }

    // ── item 2/5: RenderHealth ─────────────────────────────

    @Test
    fun `one failed render strikes the source for the cooldown`() {
        val t0 = 1_000_000L
        assertTrue(RenderHealth.renderAllowed("s", t0))
        RenderHealth.noteAttempt("s", t0)
        RenderHealth.recordFailure("s", t0)
        assertFalse(RenderHealth.renderAllowed("s", t0 + 1_000L))
        assertFalse(
            RenderHealth.renderAllowed(
                "s", t0 + RenderHealth.COOLDOWN_MS - 1
            )
        )
        assertTrue(
            RenderHealth.renderAllowed(
                "s", t0 + RenderHealth.COOLDOWN_MS + 1
            )
        )
        // Another source is never struck by this one's failure.
        assertTrue(RenderHealth.renderAllowed("other", t0 + 1_000L))
    }

    @Test
    fun `a successful render clears the strike`() {
        val t0 = 2_000_000L
        RenderHealth.recordFailure("s", t0)
        assertFalse(RenderHealth.renderAllowed("s", t0 + 10L))
        RenderHealth.recordSuccess("s")
        assertTrue(RenderHealth.renderAllowed("s", t0 + 10L))
    }

    @Test
    fun `render attempts are capped per window`() {
        val t0 = 3_000_000L
        repeat(RenderHealth.MAX_ATTEMPTS_PER_WINDOW) {
            RenderHealth.noteAttempt("s", t0 + it)
        }
        assertFalse(
            RenderHealth.renderAllowed(
                "s", t0 + RenderHealth.MAX_ATTEMPTS_PER_WINDOW
            )
        )
        assertTrue(
            RenderHealth.renderAllowed(
                "s", t0 + RenderHealth.ATTEMPT_WINDOW_MS + 1
            )
        )
    }

    // ── item 5: ad / tracker hosts ─────────────────────────

    @Test
    fun `ad and tracker hosts match by suffix only`() {
        assertTrue(AdBlockHosts.isBlocked("doubleclick.net"))
        assertTrue(AdBlockHosts.isBlocked("ads.doubleclick.net"))
        assertTrue(AdBlockHosts.isBlocked("www.google-analytics.com"))
        assertTrue(AdBlockHosts.isBlocked("cdn.taboola.com"))
        assertFalse(AdBlockHosts.isBlocked("example.com"))
        assertFalse(AdBlockHosts.isBlocked("doubleclick.net.evil.example"))
        assertFalse(AdBlockHosts.isBlocked("notdoubleclick.net"))
        assertFalse(AdBlockHosts.isBlocked(""))
    }

    // ── item 2: fetcher containment ────────────────────────

    private class FakeStatic(var body: String) : PageFetcher {
        val calls = mutableListOf<String>()
        override suspend fun get(
            url: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> {
            calls += url
            return AppResult.Ok(HostResponse(200, body, finalUrl = url))
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> =
            AppResult.Ok(HostResponse(200, "", finalUrl = url))
    }

    /** A renderer scripted per test: renders can fail, hang
     * forever (recording their own cancellation), and the
     * bridge can be made to answer. */
    private class ScriptedRenderer(
        var renderBody: String? = null,
        var hang: Boolean = false,
        var bridgeBody: String? = null
    ) : RenderedPageFetcher {
        val renderCalls = mutableListOf<String>()
        val bridgeCalls = mutableListOf<String>()
        var renderCancelled = false

        override suspend fun fetchRendered(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions,
            waitForSelector: String?
        ): AppResult<HostResponse> {
            renderCalls += url
            if (hang) {
                try {
                    delay(600_000)
                } catch (e: CancellationException) {
                    renderCancelled = true
                    throw e
                }
            }
            val body = renderBody ?: return AppResult.Err(
                AppError.ProviderDown(
                    "browser view",
                    retryable = true,
                    detail = "the page never finished building itself"
                )
            )
            return AppResult.Ok(HostResponse(200, body, finalUrl = url))
        }

        override suspend fun fetchViaBridge(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions
        ): AppResult<HostResponse>? {
            bridgeCalls += url
            val body = bridgeBody ?: return null
            return AppResult.Ok(HostResponse(200, body, finalUrl = url))
        }
    }

    private val shellBody = """
        <html><head><title>Home</title>
        <script type="module" src="/assets/index.js"></script>
        </head><body><div id="app-root"></div></body></html>
    """.trimIndent()

    private fun gate() = RenderGate(
        masterEnabled = { true },
        tier = { PerfTier.FULL },
        userDriven = { true },
        backgroundWork = { false }
    )

    private fun escalating(
        static: FakeStatic,
        rendered: ScriptedRenderer,
        sessionKey: String,
        budgetMs: () -> Long = { 60_000L }
    ) = EscalatingPageFetcher(
        staticFetcher = static,
        renderedFetcher = rendered,
        sessionKey = sessionKey,
        purpose = RenderPurpose.SOURCE,
        needsRendering = { false },
        permissions = {
            ExtensionPermissions(domains = listOf("site.example"))
        },
        rateLimiter = RateLimiter(minIntervalMs = 0),
        gate = gate(),
        budgetMs = budgetMs
    )

    @Test
    fun `a suppressed fetch never touches the render tier`() =
        runBlocking {
            val static = FakeStatic(shellBody)
            val renderer = ScriptedRenderer(renderBody = "<html/>")
            val fetcher = escalating(static, renderer, "v151-suppressed")
            val result = withContext(RenderSuppression) {
                fetcher.get("https://site.example/")
            }
            assertTrue(result is AppResult.Ok)
            assertEquals(
                shellBody, (result as AppResult.Ok).value.body
            )
            assertTrue(
                "search must not render",
                renderer.renderCalls.isEmpty()
            )
            assertTrue(renderer.bridgeCalls.isEmpty())
        }

    @Test
    fun `one failed render strikes the source onto the bridge`() =
        runBlocking {
            val static = FakeStatic(shellBody)
            val renderer = ScriptedRenderer(
                renderBody = null,
                bridgeBody = "<html><body>bridge catalogue</body></html>"
            )
            val fetcher = escalating(static, renderer, "v151-strike")
            val first = fetcher.get("https://site.example/")
            assertTrue(first is AppResult.Ok)
            assertEquals(
                "<html><body>bridge catalogue</body></html>",
                (first as AppResult.Ok).value.body
            )
            assertEquals(1, renderer.renderCalls.size)
            assertEquals(1, renderer.bridgeCalls.size)
            // Second open inside the strike: NO second render —
            // the bridge answers directly.
            val second = fetcher.get("https://site.example/")
            assertTrue(second is AppResult.Ok)
            assertEquals(
                "a struck source must not re-render",
                1, renderer.renderCalls.size
            )
            assertEquals(2, renderer.bridgeCalls.size)
        }

    @Test
    fun `a never-finishing render is bounded by the fetch budget`() =
        runBlocking {
            val static = FakeStatic(shellBody)
            val renderer = ScriptedRenderer(hang = true)
            val fetcher = escalating(
                static, renderer, "v151-hang",
                budgetMs = { 300L }
            )
            val result = withTimeout(10_000) {
                fetcher.get("https://site.example/")
            }
            assertTrue(result is AppResult.Err)
        }

    @Test
    fun `cancelling the caller cancels the render job`() = runBlocking {
        val static = FakeStatic(shellBody)
        val renderer = ScriptedRenderer(hang = true)
        val fetcher = escalating(
            static, renderer, "v151-cancel",
            budgetMs = { 600_000L }
        )
        val job = async { fetcher.get("https://site.example/") }
        delay(200)
        job.cancel()
        // Awaiting a cancelled deferred rethrows its cancellation —
        // that IS the contract (the fetch cancels, it does not
        // swallow); what this test pins is the teardown below.
        runCatching { withTimeout(5_000) { job.await() } }
        assertTrue(
            "the render must die with its caller",
            renderer.renderCancelled
        )
    }

    // ── item 2: the search fan-out ─────────────────────────

    @Test
    fun `streaming search settles a hung source as its own failure`() =
        runBlocking {
            val results = mutableMapOf<String, Result<String>>()
            var progress = 0
            StreamingSearch.run(
                items = listOf("slow", "fast"),
                parallelism = 2,
                perItemTimeoutMs = 150,
                search = { item ->
                    if (item == "slow") delay(600_000)
                    "hits-$item"
                },
                onResult = { item, result -> results[item] = result },
                onProgress = { done, _ -> progress = done }
            )
            assertEquals(2, progress)
            assertEquals("hits-fast", results["fast"]!!.getOrNull())
            val slow = results["slow"]!!
            assertTrue(slow.isFailure)
            assertTrue(
                slow.exceptionOrNull() is SearchSourceTimeoutException
            )
        }

    // ── item 4: the ladder's resolution ────────────────────

    private fun chapter(index: Int, number: Int) = Chapter(
        bookId = 1,
        index = index,
        title = "Chapter $number",
        remoteId = "https://site.example/ch/$number",
        updatedAt = 0
    )

    @Test
    fun `relink resolution prefers position then number then chapter one`() {
        val display = listOf(chapter(0, 1), chapter(1, 2), chapter(2, 3))
        assertEquals("Chapter 2", RelinkResolution.pick(display, 1)!!.title)
        // The canonical number resolves a moved release (the
        // open aimed at the 7th chapter; position 6 doesn't
        // exist in the refreshed list).
        val moved = listOf(chapter(0, 7), chapter(1, 8), chapter(2, 9))
        assertEquals(7, RelinkResolution.pick(moved, 6)!!.title.let {
            it.removePrefix("Chapter ").toInt()
        })
        // Nothing by number or position: chapter 1 is the
        // Start Reading target wherever it sits.
        val newestFirst = listOf(chapter(0, 3), chapter(1, 2), chapter(2, 1))
        assertEquals(1, RelinkResolution.pick(newestFirst, 5)!!.title.let {
            it.removePrefix("Chapter ").toInt()
        })
        // A genuinely vanished chapter resolves to nothing.
        assertNull(RelinkResolution.pick(moved, 40))
    }

    @Test
    fun `relink resolution puts the exact stable id before the number`() {
        // The refreshed list re-sorted AND renumbered: the
        // chapter the reader meant is found by its own
        // remote id, not by where it used to sit.
        val refreshed = listOf(
            chapter(0, 11).copy(remoteId = "https://site.example/ch/11"),
            chapter(1, 12).copy(remoteId = "https://site.example/ch/12"),
            chapter(2, 13).copy(remoteId = "https://site.example/ch/13")
        )
        val picked = RelinkResolution.pick(
            refreshed,
            index = 0,
            intendedKey = app.xreader.data.extension.ChapterIdentity
                .canonicalKey("https://site.example/ch/12"),
            intendedNumber = 99f,
            intendedTitle = "No Such Chapter"
        )
        assertEquals("Chapter 12", picked!!.title)
    }

    @Test
    fun `renderer death is not a challenge or sign-in classification`() {
        // Item 2 (ChatGPT sift): the browser-death failure
        // must settle as a plain failure — the session-need
        // classifier keys on challenge markers/phrases, and
        // the death detail carries none.
        assertFalse(
            SiteCheck.isChallengeDetail(
                "the browser view stopped working"
            )
        )
        assertFalse(
            SiteCheck.isChallengeDetail(
                "the browser view couldn't load that page in time"
            )
        )
    }

    // ── item 6: the Browse-manga visibility seam ───────────

    private class FakeCatalogueSource : CatalogueSource {
        override val id: Long = 777L
        override val name: String = "Fake Panels"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true
        override suspend fun getPopularManga(page: Int): MangasPage =
            MangasPage(
                listOf(SManga.create().apply {
                    url = "/series/one"
                    title = "Series One"
                }),
                false
            )

        override suspend fun getLatestUpdates(page: Int): MangasPage =
            MangasPage(emptyList(), false)

        override suspend fun getSearchManga(
            page: Int,
            query: String,
            filters: FilterList
        ): MangasPage = MangasPage(emptyList(), false)

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean
        ): SMangaUpdate = SMangaUpdate(manga, chapters)

        override suspend fun getPageList(
            chapter: SChapter
        ): List<Page> = emptyList()
    }

    private fun keiyoushiService(
        extDao: app.xreader.data.FakeInstalledExtensionDao,
        ratingProvider: suspend (String) -> ContentRating?
    ): ExtensionService {
        val store = ExtensionStore(tmp.newFolder())
        val transport = app.xreader.data.FakeTransport {
            app.xreader.data.FakeTransport.ok("")
        }
        val factory = HostContextFactory(
            store, testSessionStore(), transport
        )
        return ExtensionService(
            extDao, store, factory, FakeDexLoader(),
            keiyoushiLiveSources = { _ ->
                mapOf(
                    "keiyoushi:eu.kanade.tachiyomi.extension.en.fakepanels:777"
                        to FakeCatalogueSource()
                )
            },
            keiyoushiRatingProvider = ratingProvider
        ) { FakeJsRuntime() }
    }

    private fun keiyoushiRow() = InstalledExtension(
        repoId = 9,
        displayName = "Fake Panels",
        packageId = "eu.kanade.tachiyomi.extension.en.fakepanels",
        versionName = "1.6.5",
        versionCode = 106005,
        kind = ExtensionKind.KEIYOUSHI,
        artifactPath = null,
        sha256 = "abc",
        apiLevel = 16,
        languages = "en",
        sourceIds = "777",
        enabled = true,
        trustState = TrustState.TRUSTED,
        permissionsJson = "{}",
        installedAt = 0,
        updatedAt = 0
    )

    @Test
    fun `an installed ready player source is visible when its rating is answered`() =
        runBlocking {
            val extDao = app.xreader.data.FakeInstalledExtensionDao()
            extDao.insert(keiyoushiRow())
            val service = keiyoushiService(extDao) { ContentRating.SAFE }
            val handles = service.sourcesOf(1L).getOrNull()
            assertNotNull(handles)
            assertEquals(1, handles!!.size)
            val rating = service.contentRatingOf(handles.first())
            assertEquals(ContentRating.SAFE, rating)
            // The Browse list's exact filter under the default
            // preference: the source SHOWS.
            assertTrue(
                ContentAccess.allows(
                    rating, ContentVisibility.HIDE_ADULT
                )
            )
            // And its catalogue call routes to the player.
            val page = handles.first().source.popular(1)
            assertEquals(1, page.books.size)
            assertEquals("Series One", page.books.first().title)
        }

    @Test
    fun `an unanswered player rating is visible pending — the v152 settled ruling`() =
        runBlocking {
            // v1.5.2 (§8.4 ruling i) SUPERSEDES the v1.5.1
            // fail-closed assertion that used to live here: an
            // unanswered rating is UNKNOWN — the source stays
            // VISIBLE (badge state), never hidden as ADULT.
            val extDao = app.xreader.data.FakeInstalledExtensionDao()
            extDao.insert(keiyoushiRow())
            val service = keiyoushiService(extDao) { null }
            val handles = service.sourcesOf(1L).getOrNull()
            assertNotNull(handles)
            val rating = service.contentRatingOf(handles!!.first())
            assertEquals(ContentRating.SAFE, rating)
            assertTrue(
                ContentAccess.allows(
                    rating, ContentVisibility.HIDE_ADULT
                )
            )
        }
}
