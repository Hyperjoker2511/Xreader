package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeExtensionRepoDao
import app.xreader.data.FakeInstalledExtensionDao
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6 Stage B pins: the browser view (WB) and the row-lock
 * sampling fix (WT).
 *
 * WB: the pure decision table (RenderPolicy), the settle rule
 * (RenderSettle), the site-check markers (SiteCheck), the config
 * marker's backward compatibility, and the routing contracts of
 * plan W6.4 — a marked source makes ZERO static requests, an
 * unmarked source on a normal site makes ZERO renders, an
 * unmarked source on a shell site escalates once (and records
 * the TR4 notice), a JS-shell site is added end-to-end through
 * the browser view and saved marked, and the master switch off
 * reproduces the pre-v1.0.6 shell refusal word for word.
 *
 * WT: a declarative source whose proof book's chapters are
 * name-clean but ROW-locked gets the tester's all-locked limit
 * verdict (WORKING_WITH_LIMITS), not a text failure.
 */
class V106StageBTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── helpers ──────────────────────────────────────────────────

    private fun runtime(
        master: Boolean = true,
        tier: PerfTier = PerfTier.FULL
    ): RenderRuntime {
        val runtime = RenderRuntime(
            MutableStateFlow(master), MutableStateFlow(tier)
        )
        runtime.appInForeground = true
        return runtime
    }

    /** RenderRuntime mirrors its flows asynchronously; wait for
     * the gate to show the value the test set. */
    private suspend fun runtimeWithMaster(master: Boolean): RenderRuntime {
        val runtime = runtime(master = master)
        var waitedMs = 0L
        while (runtime.gate().masterEnabled() != master && waitedMs < 2_000L) {
            delay(10)
            waitedMs += 10
        }
        assertEquals(master, runtime.gate().masterEnabled())
        return runtime
    }

    private fun factory(
        transport: FakeTransport,
        renderer: FakeRenderedFetcher? = null,
        runtime: RenderRuntime? = null,
        onRuntimeShell: (Long) -> Unit = {}
    ): HostContextFactory = HostContextFactory(
        ExtensionStore(tmp.newFolder()),
        testSessionStore(),
        transport,
        renderedFetcher = renderer,
        renderRuntime = runtime,
        onRuntimeShell = onRuntimeShell
    )

    /** v1.0.8.1: the chikari-shaped test config now mirrors the
     * real rendered shapes (and the production lane's specs) —
     * bare-anchor cards and chapter rows, <article> prose. */
    private fun chikariConfig(marked: Boolean) = DeclarativeConfig(
        id = "chikari",
        name = "Chikari",
        baseUrl = ChikariSite.BASE,
        popular = ListSpec(
            url = ChikariSite.BASE,
            results = "a[href*='/novels/']",
            title = "img@alt",
            link = "@href",
            cover = "img@src"
        ),
        details = DetailsSpec(
            title = "h1",
            chapters = ChapterListSpec(
                list = "a[href~='/novels/[^/]+/[0-9]+$']",
                name = "@text",
                link = "@href",
                reverse = true
            )
        ),
        chapter = ChapterSpec(content = "article"),
        needsRendering = marked
    )

    private fun chikariRenderer() = FakeRenderedFetcher(
        ChikariSite.renderedPages(), ChikariSite::renderedFallback
    )

    // ── RenderPolicy: the decision table (W2/W5) ────────────────

    @Test
    fun `render policy decision table`() {
        fun inputs(
            purpose: RenderPurpose = RenderPurpose.SOURCE,
            needsRendering: Boolean = false,
            tier: PerfTier = PerfTier.FULL,
            userDriven: Boolean = true,
            backgroundWork: Boolean = false,
            masterEnabled: Boolean = true
        ) = RenderInputs(
            purpose, needsRendering, tier, userDriven,
            backgroundWork, masterEnabled
        )

        // The master switch and background work veto everything.
        assertEquals(
            RenderMode.STATIC_ONLY,
            RenderPolicy.mode(inputs(needsRendering = true, masterEnabled = false))
        )
        assertEquals(
            RenderMode.STATIC_ONLY,
            RenderPolicy.mode(inputs(needsRendering = true, backgroundWork = true))
        )
        // A marked source renders first; an unmarked one escalates.
        assertEquals(
            RenderMode.RENDER_ALWAYS,
            RenderPolicy.mode(inputs(needsRendering = true))
        )
        assertEquals(
            RenderMode.RENDER_ON_SHELL,
            RenderPolicy.mode(inputs(needsRendering = false))
        )
        // Probes escalate even for a marked config (Repair proves
        // the flip through a drive, it doesn't assume it).
        assertEquals(
            RenderMode.RENDER_ON_SHELL,
            RenderPolicy.mode(
                inputs(purpose = RenderPurpose.PROBE, needsRendering = true)
            )
        )
        // MINIMAL renders only while a person is driving (Q1).
        assertEquals(
            RenderMode.STATIC_ONLY,
            RenderPolicy.mode(
                inputs(
                    tier = PerfTier.MINIMAL, userDriven = false,
                    needsRendering = true
                )
            )
        )
        assertEquals(
            RenderMode.RENDER_ALWAYS,
            RenderPolicy.mode(
                inputs(
                    tier = PerfTier.MINIMAL, userDriven = true,
                    needsRendering = true
                )
            )
        )
        // The escalation half of the triggers.
        assertTrue(
            RenderPolicy.shouldEscalate(
                RenderMode.RENDER_ON_SHELL, isShell = true, alreadyRendered = false
            )
        )
        assertFalse(
            RenderPolicy.shouldEscalate(
                RenderMode.RENDER_ON_SHELL, isShell = true, alreadyRendered = true
            )
        )
        assertFalse(
            RenderPolicy.shouldEscalate(
                RenderMode.STATIC_ONLY, isShell = true, alreadyRendered = false
            )
        )
        assertFalse(
            RenderPolicy.shouldEscalate(
                RenderMode.RENDER_ON_SHELL, isShell = false, alreadyRendered = false
            )
        )
        assertTrue(RenderPolicy.rendersFirst(RenderMode.RENDER_ALWAYS))
        assertFalse(RenderPolicy.rendersFirst(RenderMode.RENDER_ON_SHELL))
    }

    // ── RenderSettle (W5.3) ──────────────────────────────────────

    @Test
    fun `settle rule waits for a stable complete page`() {
        fun sample(
            ready: String = "complete",
            text: Int = 500,
            anchors: Int = 40,
            selector: Int? = null,
            elapsed: Long = 0L
        ) = RenderSettleSample(ready, text, anchors, selector, elapsed)

        assertFalse(RenderSettle.isSettled(emptyList()))
        assertFalse(RenderSettle.isSettled(listOf(sample(elapsed = 5_000))))
        // Two identical polls, complete, past the minimum dwell.
        assertTrue(
            RenderSettle.isSettled(
                listOf(sample(elapsed = 800), sample(elapsed = 1_300))
            )
        )
        // Still growing between polls — not settled.
        assertFalse(
            RenderSettle.isSettled(
                listOf(
                    sample(text = 300, elapsed = 800),
                    sample(text = 500, elapsed = 1_300)
                )
            )
        )
        // Stable but too soon after load.
        assertFalse(
            RenderSettle.isSettled(
                listOf(sample(elapsed = 400), sample(elapsed = 800))
            )
        )
        // Stable but the document never completed.
        assertFalse(
            RenderSettle.isSettled(
                listOf(
                    sample(ready = "interactive", elapsed = 800),
                    sample(ready = "interactive", elapsed = 1_600)
                )
            )
        )
        // The selector early-exit: one poll is enough once the
        // wanted element exists and loading has been left behind.
        assertTrue(
            RenderSettle.isSettled(
                listOf(sample(ready = "interactive", selector = 12, elapsed = 300))
            )
        )
        // Tier budgets (W5.3).
        assertEquals(45_000L, RenderSettle.hardTimeoutMs(PerfTier.MINIMAL))
        assertEquals(30_000L, RenderSettle.hardTimeoutMs(PerfTier.STANDARD))
        assertEquals(30_000L, RenderSettle.hardTimeoutMs(PerfTier.FULL))
    }

    // ── RenderSettle v1.0.8.1: content-aware settling ───────

    @Test
    fun `settle rule refuses a stable loading spinner`() {
        fun spinner(elapsed: Long) = RenderSettleSample(
            readyState = "complete", textLength = 50,
            anchorCount = 1, selectorCount = null, elapsedMs = elapsed
        )
        // Perfectly stable, complete, past the dwell — but the
        // page is still inside the shell band: NOT settled. (The
        // chikari.moe failure: the spinner sat unchanged from
        // ~2 s while the data arrived at ~8 s.)
        assertFalse(
            RenderSettle.isSettled(listOf(spinner(1_300), spinner(1_700)))
        )
        assertFalse(
            RenderSettle.isSettled(listOf(spinner(9_000), spinner(9_400)))
        )
        // The data arrives; the grown page stabilises and settles.
        fun content(elapsed: Long) = RenderSettleSample(
            readyState = "complete", textLength = 5_000,
            anchorCount = 150, selectorCount = null, elapsedMs = elapsed
        )
        assertTrue(
            RenderSettle.isSettled(
                listOf(
                    spinner(1_300), spinner(1_700),
                    content(8_100), content(8_500)
                )
            )
        )
        // Either band clearing is content: a prose page with
        // thousands of characters but almost no links settles.
        fun prose(elapsed: Long) = RenderSettleSample(
            readyState = "complete", textLength = 5_000,
            anchorCount = 2, selectorCount = null, elapsedMs = elapsed
        )
        assertTrue(
            RenderSettle.isSettled(listOf(prose(1_300), prose(1_700)))
        )
    }

    @Test
    fun `settle rule honours a wait-for selector until its cap`() {
        fun waiting(elapsed: Long) = RenderSettleSample(
            readyState = "complete", textLength = 5_000,
            anchorCount = 150, selectorCount = 0, elapsedMs = elapsed
        )
        // Content-shaped and stable, but the caller's selector
        // has not matched: not settled.
        assertFalse(
            RenderSettle.isSettled(listOf(waiting(1_300), waiting(1_700)))
        )
        // Past the wait cap the stability rule takes over — a
        // selector that never matches must not hang the render.
        assertTrue(
            RenderSettle.isSettled(
                listOf(waiting(25_400), waiting(25_800))
            )
        )
        // The moment the selector matches, the early exit fires.
        assertTrue(
            RenderSettle.isSettled(
                listOf(
                    RenderSettleSample(
                        readyState = "interactive", textLength = 300,
                        anchorCount = 9, selectorCount = 3,
                        elapsedMs = 500
                    )
                )
            )
        )
    }

    // ── SiteCheck (W4.1) ─────────────────────────────────────────

    @Test
    fun `site check recognises challenge pages and leaves book pages alone`() {
        val challenge = Jsoup.parse(
            "<html><body><form action=\"/cdn-cgi/challenge-platform\">" +
                "</form><p>Just a moment...</p></body></html>",
            "https://x.example/book"
        )
        assertTrue(SiteCheck.hasChallengeMarkers(challenge))
        assertTrue(SiteCheck.needsSiteCheck(challenge, "https://x.example/book"))

        val bookPage = Jsoup.parse(NovelightSite.BOOK_PAGE, NovelightSite.BOOK_URL)
        assertFalse(SiteCheck.hasChallengeMarkers(bookPage))
        assertFalse(SiteCheck.needsSiteCheck(bookPage, NovelightSite.BOOK_URL))

        // A plain sign-in form is a site check too (via needsLogin).
        val login = Jsoup.parse(
            "<html><body><form action=\"/login\" method=\"post\">" +
                "<input type=\"password\" name=\"pw\"/></form></body></html>",
            "https://x.example/book"
        )
        assertTrue(SiteCheck.needsSiteCheck(login, "https://x.example/book"))
        assertFalse(SiteCheck.hasChallengeMarkers(login))
    }

    // ── The config marker decodes old files unmarked (W1.3) ─────

    @Test
    fun `pre-v1_0_6 config files decode with rendering unmarked`() {
        val config = DeclarativeConfig(
            id = "x",
            name = "X",
            baseUrl = "https://x.example",
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "body")
        )
        val encoded = DeclarativeFingerprints.encode(listOf(config))
        assertTrue(encoded.contains("\"needsRendering\""))
        // A file written before the field existed: strip it and
        // the config must still decode, unmarked.
        val legacy = encoded.replace(
            Regex(""",\s*"needsRendering":(true|false)"""), ""
        )
        val decoded = DeclarativeFingerprints.decode(legacy)
        assertEquals(1, decoded.size)
        assertFalse(decoded[0].needsRendering)
        // And a marked config round-trips marked.
        val marked = DeclarativeFingerprints.decode(
            DeclarativeFingerprints.encode(
                listOf(config.copy(needsRendering = true))
            )
        )
        assertTrue(marked[0].needsRendering)
    }

    // ── Routing contracts (W6.4) ────────────────────────────────

    @Test
    fun `marked source renders everything and never touches the static client`() =
        runBlocking {
            val transport = ChikariSite.transport()
            val renderer = chikariRenderer()
            val factory = factory(transport, renderer, runtime())
            val context = factory.contextFor(
                extensionRowId = 7L,
                packageId = "link.chikari.moe",
                label = "Chikari",
                permissions = {
                    ExtensionPermissions(domains = listOf(ChikariSite.HOST))
                },
                needsRendering = { true }
            )
            val source = DeclarativeSource(chikariConfig(marked = true), context)
            val books = source.popular(5).books
            assertTrue(books.isNotEmpty())
            assertTrue(renderer.calls.isNotEmpty())
            assertTrue(
                "a marked source must make zero static requests",
                transport.requests.isEmpty()
            )
        }

    @Test
    fun `unmarked source on a shell site escalates once and records the notice`() =
        runBlocking {
            val transport = ChikariSite.transport()
            val renderer = chikariRenderer()
            val notices = mutableListOf<Long>()
            val factory = factory(
                transport, renderer, runtime(),
                onRuntimeShell = { notices += it }
            )
            val context = factory.contextFor(
                extensionRowId = 9L,
                packageId = "link.chikari.moe",
                label = "Chikari",
                permissions = {
                    ExtensionPermissions(domains = listOf(ChikariSite.HOST))
                }
            )
            val source = DeclarativeSource(chikariConfig(marked = false), context)
            val chapters = source.chapters(
                SourceBook(id = ChikariSite.BOOK_URL, title = "Shadow Slave")
            )
            assertEquals(3, chapters.size)
            assertTrue(renderer.calls.isNotEmpty())
            // The static attempt happened first (that's how the
            // shell was detected) — and TR4 recorded the row.
            assertTrue(
                transport.requests.any { it.url == ChikariSite.BOOK_URL }
            )
            assertEquals(listOf(9L), notices)
        }

    @Test
    fun `unmarked source on a normal site never renders`() = runBlocking {
        val transport = NovelightSite.transport()
        val renderer = FakeRenderedFetcher()
        val factory = factory(transport, renderer, runtime())
        val context = factory.contextFor(
            extensionRowId = 3L,
            packageId = "link.novelight.net",
            label = "Novelight",
            permissions = {
                ExtensionPermissions(domains = listOf(NovelightSite.HOST))
            }
        )
        val entry = KnownSites.entryFor(NovelightSite.HOST)!!
        val config = KnownSites.fullConfig(
            entry, "nl-test", "Novelight", NovelightSite.BASE
        )!!
        val source = DeclarativeSource(config, context)
        val chapters = source.chapters(
            SourceBook(id = NovelightSite.BOOK_URL, title = "Lord of Talents")
        )
        // The fixture book page carries exactly 5 unique chapter
        // rows — the count the rest of the suite pins for this
        // fixture (V105ProbeTest, V1051LaneGateTest,
        // V106StageATest). The live site's newest-50 list is not
        // what this fixture models.
        assertEquals(5, chapters.size)
        assertTrue(renderer.calls.isEmpty())
        assertTrue(transport.requests.isNotEmpty())
    }

    // ── TR1 end-to-end: add-from-link through the browser view ──

    @Test
    fun `a javascript site is added through the browser view and saved marked`() =
        runBlocking {
            val transport = ChikariSite.transport()
            val renderer = chikariRenderer()
            val factory = factory(transport, renderer, runtime())
            val prober = LinkProber(factory, testSessionStore())
            val outcome = prober.probe(ChikariSite.BOOK_URL)
            val ready = outcome as? LinkProber.Outcome.Ready
                ?: throw AssertionError("expected Ready, was $outcome")
            assertTrue(
                "the saved source must carry the browser-view marker",
                ready.draft.config.needsRendering
            )
            assertTrue(ready.draft.testDrive.chapterCount >= 3)
            assertTrue(ready.draft.testDrive.chapterTextLength > 0)
            assertTrue(renderer.calls.isNotEmpty())
            assertTrue(
                ready.stageLines.any { it.proposer == "browser view" }
            )
        }

    @Test
    fun `master switch off reproduces the plain shell refusal`() = runBlocking {
        val transport = ChikariSite.transport()
        val renderer = chikariRenderer()
        val factory = factory(
            transport, renderer, runtimeWithMaster(master = false)
        )
        val prober = LinkProber(factory, testSessionStore())
        val outcome = prober.probe(ChikariSite.BOOK_URL)
        val failed = outcome as? LinkProber.Outcome.Failed
            ?: throw AssertionError("expected Failed, was $outcome")
        // The pre-v1.0.6 A9 refusal, word for word (W6.4).
        assertEquals(
            "This site loads its pages with JavaScript — Xreader can't read it yet, " +
                "so no source was added. The page only exists after the site's own " +
                "scripts run, which no automatic detector can follow.",
            failed.message
        )
        assertTrue(renderer.calls.isEmpty())
    }

    // ── WT: row-locked sampling ─────────────────────────────────

    @Test
    fun `row-locked chapters give the tester the all-locked limit verdict`() =
        runBlocking {
            val transport = NovelightSite.transportAllLocked()
            val store = ExtensionStore(tmp.newFolder())
            val sessionStore = testSessionStore()
            val factory = HostContextFactory(store, sessionStore, transport)
            val extDao = FakeInstalledExtensionDao()
            val service = ExtensionService(
                extDao, store, factory, FakeDexLoader()
            ) { FakeJsRuntime() }
            val manager = RepoManager(
                FakeExtensionRepoDao(), extDao, store, transport,
                service, sessionStore
            )
            val entry = KnownSites.entryFor(NovelightSite.HOST)!!
            val config = KnownSites.fullConfig(
                entry, "wt-test", "Novelight", NovelightSite.BASE
            )!!
            val saved = manager.saveLinkSource(config, NovelightSite.HOST)
            val row = (saved as AppResult.Ok).value
            val handle = (service.sourcesOf(row.id) as AppResult.Ok)
                .value.first()
            val verdict = ContractTester(service).test(handle)
            // The proof book's chapters are name-clean but every
            // row carries the site's lock badge: sampling must
            // subtract them all and land on the limit verdict —
            // before WT this read empty AJAX text and failed.
            assertEquals(
                SourceHealthState.WORKING_WITH_LIMITS, verdict.state
            )
            val text = verdict.results.first {
                it.capability == SourceCapability.TEXT
            }
            assertTrue(text.limitOnly)
            assertTrue(text.detail.contains("paid or locked"))
        }

    // ── W4.3: the book page's site-check door ───────────────────

    @Test
    fun `chapters section offers the site check door only for a marked source in trouble`() {
        fun section(count: Int, needs: Boolean) =
            app.xreader.ui.book.ChaptersSection.state(
                chapterCount = count,
                chaptersLoading = false,
                chaptersError = null,
                isExtensionBook = true,
                remoteId = null,
                isLinkSource = true,
                needsSiteCheck = needs
            )
        assertTrue(section(count = 0, needs = true).canSiteCheck)
        assertFalse(section(count = 0, needs = false).canSiteCheck)
        // A book with chapters shows the list, never the doors.
        assertFalse(section(count = 5, needs = true).canSiteCheck)
    }

    // ── Problem details (W3.5) ──────────────────────────────────

    @Test
    fun `problem details name the browser view only when asked`() {
        val plain = ProblemDetails.format(
            "chikari.moe", "Chapter lists", "boom", "1.0.6"
        )
        assertFalse(plain.contains("Reading mode"))
        val marked = ProblemDetails.format(
            "chikari.moe", "Chapter lists", "boom", "1.0.6",
            browserView = true
        )
        assertTrue(marked.contains("Reading mode: browser view"))
    }
}
