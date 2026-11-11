package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostHttp
import app.xreader.extensionapi.HostResponse
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.5.12 (Front 1 — novel sources) gate:
 *
 *  - S1: a chapter CONTENT fetch never enters full-render
 *    escalation (fetchRendered entries on the content road
 *    = 0; static + fetch-bridge roads allowed), while the
 *    catalogue road of the same source keeps its escalation.
 *  - S2 + S4: an expired-token chapter open spends at most
 *    ONE FULL-RENDER WebView round-trip (harvest visits /
 *    mint page loads), the chapter still loads after the
 *    mint, and the declarative road calls the coordinator's
 *    SUSPEND variants (asserted by construction over the
 *    production source, plus direct suspend-variant
 *    behaviour proofs). Gate-fix note (Group B): the
 *    recovery chain's TERMINAL in-context chapter fetch is
 *    a JS fetch() inside the already-parked page, not a
 *    full render, so it is NOT charged against the
 *    full-render budget and remains available after a mint
 *    — see the second-refusal test below.
 *  - S3: BrightNovel's endpoint [] against its declared
 *    floor fires the suspicious-empty tier (never a
 *    proven-complete 0), and a non-empty endpoint still
 *    parses normally.
 */
class V1512Front1GateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        RenderHealth.reset()
        ReadTokenCoordinators.clearForTests()
        FenrirHarvestSink.clearForTests()
    }

    // ── shared fakes ───────────────────────────────────────

    /** A rendered fetcher counting every road, with a
     * re-entrancy tripwire: Main-resident operations must
     * never overlap (the render mutex serialises them; a
     * nested/overlapping entry would be the starvation
     * shape S2's yield clause forbids). */
    private class CountingRenderer(
        var renderedBody: (String) -> String = { "<html><body></body></html>" },
        var bridgeBody: (String) -> String? = { null },
        var inContextHandler: ((String) -> HostResponse?)? = null
    ) : RenderedPageFetcher {
        val renderCalls = AtomicInteger(0)
        val bridgeCalls = AtomicInteger(0)
        val inContextCalls = AtomicInteger(0)
        val harvestCalls = AtomicInteger(0)
        val activeOps = AtomicInteger(0)
        val maxActiveOps = AtomicInteger(0)

        private fun enter() {
            val now = activeOps.incrementAndGet()
            maxActiveOps.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        }

        private fun exit() {
            activeOps.decrementAndGet()
        }

        override suspend fun fetchRendered(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions,
            waitForSelector: String?
        ): AppResult<HostResponse> {
            enter()
            return try {
                renderCalls.incrementAndGet()
                AppResult.Ok(
                    HostResponse(200, renderedBody(url), finalUrl = url)
                )
            } finally {
                exit()
            }
        }

        override suspend fun fetchViaBridge(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions
        ): AppResult<HostResponse>? {
            enter()
            return try {
                bridgeCalls.incrementAndGet()
                bridgeBody(url)?.let {
                    AppResult.Ok(HostResponse(200, it, finalUrl = url))
                }
            } finally {
                exit()
            }
        }

        override suspend fun fetchInContext(
            url: String,
            parkUrl: String,
            sessionKey: String?,
            permissions: ExtensionPermissions,
            method: String,
            body: String?,
            headers: Map<String, String>
        ): AppResult<HostResponse>? {
            enter()
            return try {
                inContextCalls.incrementAndGet()
                inContextHandler?.invoke(url)?.let { AppResult.Ok(it) }
            } finally {
                exit()
            }
        }

        override suspend fun harvestTokenInContext(
            parkUrl: String,
            sessionKey: String?,
            permissions: ExtensionPermissions,
            waitMs: Long
        ): TokenHarvestResult? {
            enter()
            return try {
                harvestCalls.incrementAndGet()
                null
            } finally {
                exit()
            }
        }

        /** Every Main-resident round-trip this fetcher ran. */
        fun mainResidentRoundTrips(): Int =
            renderCalls.get() + inContextCalls.get() + harvestCalls.get()
    }

    private suspend fun runtimeReady(): RenderRuntime {
        val runtime = RenderRuntime(
            MutableStateFlow(true), MutableStateFlow(PerfTier.FULL)
        )
        runtime.appInForeground = true
        var waitedMs = 0L
        while (!runtime.gate().masterEnabled() && waitedMs < 2_000L) {
            delay(10)
            waitedMs += 10
        }
        assertTrue(runtime.gate().masterEnabled())
        return runtime
    }

    private val shellPage =
        "<html><head><title>Shell</title></head><body>" +
            "<div id=\"app\"></div>" +
            "<script src=\"/app.js\"></script>" +
            "</body></html>"

    // ── S1, fetcher level ──────────────────────────────────

    @Test
    fun `S1 capped content fetch never renders - shell answered by the bridge`() =
        runBlocking {
            val renderer = CountingRenderer(
                bridgeBody = { "<html><body>BRIDGE-CONTENT</body></html>" }
            )
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                FakeTransport { AppResult.Ok(FakeTransport.response(200, shellPage)) },
                renderedFetcher = renderer,
                renderRuntime = runtimeReady()
            )
            val key = "v1512-f1-cap-a"
            val perms = ExtensionPermissions(listOf("cap.example"))
            val client = factory.clientFor(key, "cap", perms)
            val fetcher = factory.escalatingFetcherOrNull(
                client, key, RenderPurpose.SOURCE,
                needsRendering = { true },
                permissions = { perms },
                rateLimiter = RateLimiter(minIntervalMs = 0)
            )!!
            val result = withContext(ContentFetchCap) {
                fetcher.get("https://cap.example/series/x/chapter-1")
            }
            val ok = result as? AppResult.Ok
                ?: throw AssertionError("expected the bridge answer, got $result")
            assertEquals("BRIDGE-CONTENT", ok.value.body.substringAfter("<body>").substringBefore("</body>"))
            assertEquals(
                "the content road must never enter full-render escalation",
                0, renderer.renderCalls.get()
            )
            assertEquals("the bridge is the capped road's escalation", 1, renderer.bridgeCalls.get())
            Unit
        }

    @Test
    fun `S1 control - the same fetch without the cap renders first`() =
        runBlocking {
            val renderer = CountingRenderer(
                renderedBody = { "<html><body>RENDERED</body></html>" }
            )
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                FakeTransport { AppResult.Ok(FakeTransport.response(200, shellPage)) },
                renderedFetcher = renderer,
                renderRuntime = runtimeReady()
            )
            val key = "v1512-f1-cap-b"
            val perms = ExtensionPermissions(listOf("cap.example"))
            val client = factory.clientFor(key, "cap", perms)
            val fetcher = factory.escalatingFetcherOrNull(
                client, key, RenderPurpose.SOURCE,
                needsRendering = { true },
                permissions = { perms },
                rateLimiter = RateLimiter(minIntervalMs = 0)
            )!!
            val result = fetcher.get("https://cap.example/series")
            assertTrue(result is AppResult.Ok)
            assertEquals(
                "uncapped needs-rendering fetches still render first (the control)",
                1, renderer.renderCalls.get()
            )
            Unit
        }

    @Test
    fun `S1 capped fetch of a healthy static page touches neither render nor bridge`() =
        runBlocking {
            val renderer = CountingRenderer()
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                FakeTransport {
                    AppResult.Ok(
                        FakeTransport.response(
                            200,
                            "<html><body><div class=\"content\">" +
                                "<p>Plain static chapter prose, served directly.</p>" +
                                "</div></body></html>"
                        )
                    )
                },
                renderedFetcher = renderer,
                renderRuntime = runtimeReady()
            )
            val key = "v1512-f1-cap-c"
            val perms = ExtensionPermissions(listOf("cap.example"))
            val client = factory.clientFor(key, "cap", perms)
            val fetcher = factory.escalatingFetcherOrNull(
                client, key, RenderPurpose.SOURCE,
                needsRendering = { false },
                permissions = { perms },
                rateLimiter = RateLimiter(minIntervalMs = 0)
            )!!
            val result = withContext(ContentFetchCap) {
                fetcher.get("https://cap.example/series/x/chapter-2")
            }
            assertTrue(result is AppResult.Ok)
            assertEquals(0, renderer.renderCalls.get())
            assertEquals(0, renderer.bridgeCalls.get())
            Unit
        }

    // ── S1, source level (the Continue whole-path shape) ───

    private fun shellSourceConfig() = DeclarativeConfig(
        id = "v1512-shell-source",
        name = "Shell Source",
        baseUrl = "https://shell.example",
        needsRendering = true,
        popular = ListSpec(
            url = "https://shell.example/popular",
            results = "div.result",
            title = "a.t",
            link = "a.t@href"
        ),
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "ul.chapters > li",
                name = "a",
                link = "a@href"
            )
        ),
        chapter = ChapterSpec(content = "div.content")
    )

    @Test
    fun `S1 Continue whole-path - chapter content completes with zero full renders while catalogue still renders`() =
        runBlocking {
            val catalogueHtml =
                "<html><body><div class=\"result\">" +
                    "<a class=\"t\" href=\"/book/one\">Book One</a>" +
                    "</div></body></html>"
            val chapterHtml =
                "<html><body><div class=\"content\">" +
                    "<p>The bridged chapter prose the reader shows.</p>" +
                    "</div></body></html>"
            val renderer = CountingRenderer(
                renderedBody = { url ->
                    if (url.endsWith("/popular")) catalogueHtml else chapterHtml
                },
                bridgeBody = { chapterHtml }
            )
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                FakeTransport { AppResult.Ok(FakeTransport.response(200, shellPage)) },
                renderedFetcher = renderer,
                renderRuntime = runtimeReady()
            )
            val perms = ExtensionPermissions(listOf("shell.example"))
            val context = factory.contextFor(
                extensionRowId = 7,
                packageId = "v1512.shell",
                label = "Shell Source",
                permissions = { perms },
                needsRendering = { true }
            )
            val source = DeclarativeSource(shellSourceConfig(), context)

            // The catalogue road keeps its escalation: the
            // shell catalogue is read through a full render.
            val page = source.popular(1)
            assertEquals(1, page.books.size)
            assertEquals("Book One", page.books[0].title)
            assertTrue(
                "catalogue must still escalate to a render",
                renderer.renderCalls.get() >= 1
            )

            // The Continue tap: the chapter content completes
            // (through the bridge) with the content road
            // entering full-render escalation ZERO times.
            renderer.renderCalls.set(0)
            val content = source.chapterContent(
                SourceChapter(
                    id = "https://shell.example/book/one/chapter-1",
                    name = "Chapter 1"
                )
            )
            assertTrue(
                "chapter prose must arrive: ${content.html}",
                content.html.contains("The bridged chapter prose")
            )
            assertEquals(
                "content road full-render entries must be 0",
                0, renderer.renderCalls.get()
            )
            assertTrue(
                "the bridge is the road that served the chapter",
                renderer.bridgeCalls.get() >= 1
            )
            assertTrue(
                "Main-resident ops never overlap",
                renderer.maxActiveOps.get() <= 1
            )
            Unit
        }

    // ── S2 + S4, the token road ────────────────────────────

    private val fenrirHost = "fenrir.example"
    private val fenrirChapterUrl =
        "https://fenrir.example/api/series/absolute-regression/chapters/5"
    private val fenrirMintUrl = "https://fenrir.example/api/read-token"

    private fun fenrirConfig(id: String) = DeclarativeConfig(
        id = id,
        name = "Fenrir Gate",
        baseUrl = "https://fenrir.example",
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "a", name = "@text", link = "@href"
            )
        ),
        chapter = ChapterSpec(
            content = "body",
            ajax = ChapterTextAjax(
                urlTemplate = "https://fenrir.example/api" +
                    "/series/{seg-3}/chapters/{seg-1}",
                jsonContentField = "content"
            )
        ),
        signInNeeded = true,
        credentials = CredentialSpec(
            operationToken = OperationTokenSpec(
                mintUrl = fenrirMintUrl,
                mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}",
                checkUrlTemplate =
                    "https://fenrir.example/series/{seg-3}/{seg-1}"
            )
        )
    )

    /** A Fenrir-shaped wire: the chapter endpoint answers
     * locked until the Bearer minted IN CONTEXT arrives; the
     * OkHttp mint is refused outright (the post-solve shape
     * that forces the WebView mint road). */
    private fun fenrirTransport(): FakeTransport = FakeTransport { request ->
        when {
            request.url == fenrirMintUrl ->
                AppResult.Ok(
                    FakeTransport.response(
                        403, "{\"challenge_required\":true}"
                    )
                )
            request.url == fenrirChapterUrl -> {
                val auth = request.headers.entries.firstOrNull {
                    it.key.equals("Authorization", ignoreCase = true)
                }?.value
                if (auth == "Bearer wv-tok") {
                    FakeTransport.ok(
                        "{\"content\":\"<p>Chapter five opens " +
                            "after the mint.</p>\"," +
                            "\"content_format\":\"html\"}"
                    )
                } else {
                    FakeTransport.ok(
                        "{\"content\":\"\",\"content_format\":\"locked\"," +
                            "\"remint_chapter_id\":\"uuid-5\"}"
                    )
                }
            }
            else -> AppResult.Ok(FakeTransport.response(404, "nope"))
        }
    }

    @Test
    fun `S2 expired-token open spends one WebView round-trip and the chapter loads after the mint`() {
        val renderer = CountingRenderer(
            inContextHandler = { url ->
                if (url == fenrirMintUrl) {
                    HostResponse(
                        200,
                        "{\"token\":\"wv-tok\",\"expires_at\":9999999999}"
                    )
                } else {
                    null
                }
            }
        )
        val source = DeclarativeSource(
            fenrirConfig("v1512-fenrir-one"),
            testContext(
                fenrirTransport(), tmp.newFolder(),
                ExtensionPermissions(domains = listOf(fenrirHost))
            ),
            readTokenStore = testSessionStore(),
            sessionAccess = DeclarativeSource.RenderedSessionAccess(
                fetcher = renderer,
                sessionKey = "v1512-fenrir-one",
                permissions = ExtensionPermissions(domains = listOf(fenrirHost)),
                hasRecentSolve = { true }
            )
        )
        val content = source.chapterContent(
            SourceChapter(id = fenrirChapterUrl, name = "Chapter 5")
        )
        assertTrue(
            "the chapter must load after the mint: ${content.html}",
            content.html.contains("Chapter five opens after the mint")
        )
        assertEquals(
            "at most ONE Main-resident round-trip per chapter open " +
                "(harvest visits + in-context mints + in-context fetches)",
            1, renderer.mainResidentRoundTrips()
        )
        assertEquals(
            "the one round-trip is the in-context mint (no harvest visit)",
            0, renderer.harvestCalls.get()
        )
        assertEquals(1, renderer.inContextCalls.get())
        assertTrue(
            "Main-resident ops never overlap",
            renderer.maxActiveOps.get() <= 1
        )
    }

    @Test
    fun `S2 second refusal in the same open - terminal in-context fetch still runs after the mint, no second full render`() {
        // RE-DERIVED (v1.5.12 gate fix, Group B): this test
        // originally pinned the S2 budget as forbidding the
        // in-context chapter fetch after a WebView mint.
        // That contract was over-broad and is rescoped: the
        // budget counts FULL-RENDER round-trips (harvest
        // visits / mint page loads — the 60–105 s
        // main-resident loads), while the terminal
        // in-context fetch is a JS fetch() inside the page
        // the fetcher already parks on (warm reuse, no
        // reload) and is Fenrir's established v1.5.8
        // recovery road (refusal → mint → second refusal →
        // in-context fetch serves the prose;
        // V158FenrirInContextChapterFixture). A speculative
        // cap must not amputate that proven road. So in the
        // scenario below — locked, mint succeeds in context,
        // retry answers a protected payload — the escalation
        // MUST now run and serve the chapter, a second mint
        // must NOT run, and no full-render round-trip
        // (render / harvest) may be spent at all.
        val transport = FakeTransport { request ->
            when {
                request.url == fenrirMintUrl ->
                    AppResult.Ok(
                        FakeTransport.response(
                            403, "{\"challenge_required\":true}"
                        )
                    )
                request.url == fenrirChapterUrl -> {
                    val auth = request.headers.entries.firstOrNull {
                        it.key.equals("Authorization", ignoreCase = true)
                    }?.value
                    if (auth == "Bearer wv-tok") {
                        // Readable format, protected payload:
                        // a 240-char spaceless blob — past
                        // ProseSanity's 200-char judging floor
                        // with total blob dominance, so
                        // ChapterServeGate refuses it.
                        FakeTransport.ok(
                            "{\"content\":\"<p>" +
                                "xY9aB3".repeat(40) +
                                "</p>\",\"content_format\":\"html\"}"
                        )
                    } else {
                        FakeTransport.ok(
                            "{\"content\":\"\"," +
                                "\"content_format\":\"locked\"," +
                                "\"remint_chapter_id\":\"uuid-5\"}"
                        )
                    }
                }
                else -> AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        val renderer = CountingRenderer(
            inContextHandler = { url ->
                if (url == fenrirMintUrl) {
                    HostResponse(
                        200,
                        "{\"token\":\"wv-tok\",\"expires_at\":9999999999}"
                    )
                } else {
                    // The terminal in-context CHAPTER fetch
                    // answers prose here — under the rescoped
                    // budget it MUST be attempted (see the
                    // re-derivation note above).
                    HostResponse(
                        200,
                        "{\"content\":\"<p>in-context prose</p>\"," +
                            "\"content_format\":\"html\"}"
                    )
                }
            }
        )
        val source = DeclarativeSource(
            fenrirConfig("v1512-fenrir-two"),
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf(fenrirHost))
            ),
            readTokenStore = testSessionStore(),
            sessionAccess = DeclarativeSource.RenderedSessionAccess(
                fetcher = renderer,
                sessionKey = "v1512-fenrir-two",
                permissions = ExtensionPermissions(domains = listOf(fenrirHost)),
                hasRecentSolve = { true }
            )
        )
        val content = source.chapterContent(
            SourceChapter(id = fenrirChapterUrl, name = "Chapter 5")
        )
        assertTrue(
            "the terminal in-context fetch must serve the chapter: ${content.html}",
            content.html.contains("in-context prose")
        )
        assertEquals(
            "exactly the in-context mint + the terminal in-context fetch " +
                "(no second mint, no second fetch)",
            2, renderer.inContextCalls.get()
        )
        assertEquals(
            "no FULL-RENDER round-trip may be spent (renders + harvest visits)",
            0, renderer.renderCalls.get() + renderer.harvestCalls.get()
        )
        assertTrue(
            "Main-resident ops never overlap",
            renderer.maxActiveOps.get() <= 1
        )
    }

    @Test
    fun `S4 suspend variants carry the whole token lifecycle`() = runBlocking {
        val spec = OperationTokenSpec(
            mintUrl = fenrirMintUrl,
            mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}"
        )
        val http = object : HostHttp {
            override fun get(
                url: String, headers: Map<String, String>
            ): HostResponse = HostResponse(200, "")

            override fun post(
                url: String, body: String, contentType: String,
                headers: Map<String, String>
            ): HostResponse = HostResponse(
                200, "{\"token\":\"susp-tok\",\"expires_at\":9999999999}"
            )
        }
        val coordinator = ReadTokenCoordinator(spec, store = null)
        val scope = "scope-suspend"
        assertEquals(null, coordinator.currentTokenAwaited(scope))
        val outcome = coordinator.tokenAwaited(http, scope, "5")
        assertTrue(outcome is ReadTokenCoordinator.MintOutcome.Ready)
        assertEquals(
            "susp-tok",
            (outcome as ReadTokenCoordinator.MintOutcome.Ready).token
        )
        assertEquals(
            "susp-tok",
            coordinator.currentTokenAwaited(scope)?.value
        )
        // Harvest + generation-guarded invalidation, suspend forms.
        coordinator.noteIssuedAwaited(
            mapOf("x-read-token-issued" to "susp-tok-2"), scope
        )
        val harvested = coordinator.currentTokenAwaited(scope)
        assertEquals("susp-tok-2", harvested?.value)
        coordinator.invalidateAwaited(scope, harvested!!.generation + 99)
        assertEquals(
            "a stale generation must not revoke the held token",
            "susp-tok-2", coordinator.currentTokenAwaited(scope)?.value
        )
        coordinator.invalidateAwaited(scope, harvested.generation)
        assertEquals(null, coordinator.currentTokenAwaited(scope))
        coordinator.clearAwaited()
        // The blocking wrappers still delegate to the same
        // machinery (non-coroutine callers keep working).
        val blocking = coordinator.token(http, scope, "5")
        assertTrue(blocking is ReadTokenCoordinator.MintOutcome.Ready)
        assertNotNull(coordinator.currentToken(scope))
        coordinator.clear()
        assertEquals(null, coordinator.currentToken(scope))
    }

    @Test
    fun `S4 by construction - the declarative road calls only the suspend coordinator variants`() {
        // By-construction assertion (the gate's own terms):
        // the production declarative road's token call sites
        // are the Awaited variants; no blocking coordinator
        // call remains in DeclarativeSource. The file is read
        // from the module working directory gradle tests run
        // in (app/), with a source-tree fallback.
        val candidates = listOf(
            File("src/main/java/app/xreader/data/extension/DeclarativeSource.kt"),
            File("app/src/main/java/app/xreader/data/extension/DeclarativeSource.kt"),
            File("../app/src/main/java/app/xreader/data/extension/DeclarativeSource.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "DeclarativeSource.kt not found from " +
                    File(".").absolutePath
            )
        val text = file.readText()
        assertTrue(
            "the road must call tokenAwaited",
            text.contains("coordinator.tokenAwaited(")
        )
        assertTrue(
            "the road must call currentTokenAwaited",
            text.contains("currentTokenAwaited(")
        )
        for (blockingCall in listOf(
            "coordinator.token(", "coordinator?.token(",
            ".currentToken(chapterScope)", ".noteIssued(",
            ".invalidate(chapterScope"
        )) {
            assertFalse(
                "blocking coordinator call still on the road: $blockingCall",
                text.contains(blockingCall)
            )
        }
    }

    // ── S3, BrightNovel ────────────────────────────────────

    private fun brightNovelSource(
        endpointBody: String,
        transport: FakeTransport? = null
    ): DeclarativeSource {
        val config = BundledDefaults.configs()
            .first { it.id == "bundled-brightnovel" }
        val wire = transport ?: FakeTransport { request ->
            when {
                request.url.contains("/chapters/free") ->
                    FakeTransport.ok(endpointBody)
                request.url.contains("/series/") ->
                    FakeTransport.ok(
                        "<html><head><title>BrightNovels</title></head>" +
                            "<body><div id=\"app\"></div></body></html>"
                    )
                else -> AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        return DeclarativeSource(
            config,
            testContext(
                wire, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("brightnovels.com"))
            )
        )
    }

    private val brightBook = SourceBook(
        id = "https://brightnovels.com/series/the-dullard",
        title = "The Dullard"
    )

    @Test
    fun `S3 the shipped BrightNovel config declares its suspicious-empty floor`() {
        val config = BundledDefaults.configs()
            .first { it.id == "bundled-brightnovel" }
        assertTrue(
            "the floor (totalCount) must be declared so the hint can fire",
            (config.details.chapters.totalCount ?: 0) > 0
        )
        assertEquals(
            "the definitions epoch must have moved so installed rows receive the fix",
            3, BundledDefaults.BUNDLED_DEFINITIONS_EPOCH
        )
    }

    @Test
    fun `S3 endpoint empty against the floor fires suspicious-empty instead of proven-complete zero`() {
        val source = brightNovelSource("{\"chapters\":[]}")
        val failure = runCatching {
            source.detailsAndChapters(brightBook)
        }.exceptionOrNull()
        assertTrue(
            "an endpoint [] must fail honestly, not succeed with 0; got: $failure",
            failure is SourceException
        )
        assertTrue(
            "the suspicious-empty tier must be the one that fires; got: ${failure?.message}",
            failure!!.message.orEmpty()
                .contains(DeclarativeSource.ENDPOINT_SUSPICIOUS_EMPTY_MARKER)
        )
    }

    @Test
    fun `S3 a non-empty BrightNovel endpoint still parses normally`() {
        val body = """
            {"chapters":[
              {"name":"Chapter 2: The Road","slug":"chapter-2-the-road",
               "updated_at":"2026-01-02","is_premium":false},
              {"name":"Chapter 1: Awakening","slug":"chapter-1-awakening",
               "updated_at":"2026-01-01","is_premium":false}
            ]}
        """.trimIndent()
        val source = brightNovelSource(body)
        val (_, chapters) = source.detailsAndChapters(brightBook)
        assertEquals(2, chapters.size)
        assertEquals(
            "https://brightnovels.com/series/the-dullard/chapter-1-awakening",
            chapters[0].id
        )
        assertEquals(
            "https://brightnovels.com/series/the-dullard/chapter-2-the-road",
            chapters[1].id
        )
    }
}
