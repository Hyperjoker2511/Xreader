package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.7 — the wetriedtls lesson, pinned.
 *
 * An app-built site (Next.js shape, modelled on the real
 * wetriedtls.com captures of 2026-10-30) serves a book page
 * whose static HTML carries details + synopsis but whose
 * chapter list only exists after the site's scripts run. The
 * pre-fix checker settled for a rail of OTHER BOOKS as the
 * "chapter list" and the book's own synopsis as "chapter
 * text", and reported "It works". These tests pin the three
 * parts of the fix: the furniture guard (pure + at the drive),
 * the failure's rendered-retry flag, and the forced rendered
 * probe that reads the real list and marks the source.
 */
class V107FurnitureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── the site ─────────────────────────────────────────────

    object WetriedSite {
        const val HOST = "appbooks.example"
        const val BASE = "https://appbooks.example"
        const val SLUG = "the-eternal-sword"
        const val BOOK_URL = "$BASE/series/$SLUG"

        fun chapterUrl(n: Int) = "$BOOK_URL/chapter-$n"
        fun otherUrl(i: Int) = "$BASE/series/other-book-$i"

        private fun synopsis(seed: String) =
            "Long ago, the sword saint of $seed climbed the " +
                "ninth peak and did not come down. His disciples " +
                "waited through three winters, telling stories of " +
                "his blade to keep the fire alive. When at last a " +
                "stranger arrived carrying the saint's broken " +
                "sword, every sect in the riverlands understood " +
                "that the old age had ended. This is the story of " +
                "the apprentice who picked up the pieces, crossed " +
                "the flooded provinces, and learned why the saint " +
                "had thrown his sword into the sea. It is told in " +
                "full, chapter by chapter, without mercy for the " +
                "reader's sleep, because some tales refuse to be " +
                "shortened and this is one of them."

        private fun chapterProse(n: Int) = (1..4).joinToString("\n") {
            "The rain over the pass had teeth in chapter $n, " +
                "and the apprentice pulled his cloak tighter as " +
                "he climbed. Below him the river had taken the " +
                "old road, so he followed the goat path his " +
                "master once described, counting the stone " +
                "markers and thinking about hot soup."
        }

        private fun rail(): String = (1..13).joinToString("\n") { i ->
            "<div class=\"book-card\">" +
                "<a href=\"/series/other-book-$i\">" +
                "<span>Other Book $i</span></a></div>"
        }

        private fun seriesPage(
            title: String,
            slug: String,
            withChapters: Boolean
        ): String {
            val rows = if (withChapters) {
                "<ul class=\"chapter-rows\">" +
                    (1..5).joinToString("\n") { n ->
                        "<li class=\"chapter-row\">" +
                            "<a href=\"/series/$slug/chapter-$n\">" +
                            "Chapter $n</a></li>"
                    } + "</ul>"
            } else {
                ""
            }
            return """
                <html><head><title>AppBooks</title>
                <meta property="og:title" content="$title">
                </head><body>
                <header class="brand">AppBooks</header>
                <main class="book">
                  <div class="poster"><img src="/covers/$slug.jpg"></div>
                  <h1>$title</h1>
                  <div class="score">4.91</div>
                  <div class="synopsis"><p>${synopsis(title)}</p></div>
                  <div class="facts"><span>Total chapters</span><span>312</span></div>
                  <a class="start" href="/series/$slug/chapter-1">Start reading</a>
                  <div class="chapter-panel">$rows</div>
                  <section class="rail"><h2>Readers also enjoyed</h2>
                  ${rail()}
                  </section>
                </main></body></html>
            """.trimIndent()
        }

        val BOOK_PAGE_STATIC = seriesPage("The Eternal Sword", SLUG, false)
        val BOOK_PAGE_RENDERED = seriesPage("The Eternal Sword", SLUG, true)

        private fun otherPage(i: Int) =
            seriesPage("Other Book $i", "other-book-$i", false)

        private fun chapterPage(n: Int): String = """
            <html><head><title>Chapter $n - The Eternal Sword</title></head>
            <body>
            <h1>Chapter $n</h1>
            <div class="chapter-text">
            ${chapterProse(n).split("\n").joinToString("\n") { "<p>$it</p>" }}
            </div>
            </body></html>
        """.trimIndent()

        private val HOME_PAGE = """
            <html><head><title>AppBooks</title></head><body>
            <main class="home"><section class="rail">
            ${rail()}
            </section></main></body></html>
        """.trimIndent()

        val staticPages: Map<String, String> by lazy {
            val pages = mutableMapOf(
                BOOK_URL to BOOK_PAGE_STATIC,
                BASE to HOME_PAGE,
                "$BASE/" to HOME_PAGE
            )
            for (i in 1..13) pages[otherUrl(i)] = otherPage(i)
            for (n in 1..5) pages[chapterUrl(n)] = chapterPage(n)
            pages
        }

        fun transportServing(bookPage: String): FakeTransport =
            FakeTransport { request ->
                val body = if (request.url == BOOK_URL) {
                    bookPage
                } else {
                    staticPages[request.url]
                }
                if (body == null) {
                    AppResult.Ok(FakeTransport.response(404, "not found"))
                } else {
                    FakeTransport.ok(body)
                }
            }

        fun transport(): FakeTransport = transportServing(BOOK_PAGE_STATIC)

        fun renderedFallback(url: String): String? = staticPages[url]
    }

    // ── harness (mirrors V106StageBTest) ─────────────────────

    private suspend fun runtime(): RenderRuntime {
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

    private fun factory(
        transport: FakeTransport,
        renderer: FakeRenderedFetcher? = null,
        runtime: RenderRuntime? = null
    ): HostContextFactory = HostContextFactory(
        ExtensionStore(tmp.newFolder()),
        testSessionStore(),
        transport,
        renderedFetcher = renderer,
        renderRuntime = runtime
    )

    /** The config the old inference effectively proposed for
     * this site: the rail of other books as the "chapter list",
     * the synopsis block as the "chapter text". */
    private fun furnitureConfig() = DeclarativeConfig(
        id = "appbooks",
        name = "AppBooks",
        baseUrl = WetriedSite.BASE,
        popular = ListSpec(
            url = WetriedSite.BASE,
            results = "div.book-card a",
            title = "@text",
            link = "@href"
        ),
        details = DetailsSpec(
            title = "h1",
            cover = ".poster img@src",
            description = ".synopsis",
            chapters = ChapterListSpec(
                list = "div.book-card a",
                name = "@text",
                link = "@href"
            )
        ),
        chapter = ChapterSpec(content = ".synopsis")
    )

    // ── the pure guard ───────────────────────────────────────

    @Test
    fun `book-shaped chapter rows judge as furniture, real lists never do`() {
        val book = WetriedSite.BOOK_URL
        val furniture = (1..13).map { WetriedSite.otherUrl(it) }
        assertTrue(LinkProber.chapterRowsAreBookPages(book, furniture))

        // Novelight shape: chapters live UNDER the book path.
        assertFalse(
            LinkProber.chapterRowsAreBookPages(
                "https://novelight.example/book/sword-god",
                (101..113).map { "https://novelight.example/book/sword-god/$it" }
            )
        )
        // A pasted CHAPTER link: its siblings contain the slug.
        assertFalse(
            LinkProber.chapterRowsAreBookPages(
                WetriedSite.chapterUrl(1),
                (2..9).map { WetriedSite.chapterUrl(it) }
            )
        )
        // The real wetriedtls rows contain the slug too.
        assertFalse(
            LinkProber.chapterRowsAreBookPages(
                book, (1..5).map { WetriedSite.chapterUrl(it) }
            )
        )
        // Too few rows to judge; single-segment book URL.
        assertFalse(
            LinkProber.chapterRowsAreBookPages(book, furniture.take(2))
        )
        assertFalse(
            LinkProber.chapterRowsAreBookPages(
                "https://x.example/mybook", furniture
            )
        )
        // A stray self-row among real chapters is ignored.
        assertFalse(
            LinkProber.chapterRowsAreBookPages(
                book,
                listOf(book) + (1..5).map { WetriedSite.chapterUrl(it) }
            )
        )
    }

    // ── the guard at the drive ───────────────────────────────

    @Test
    fun `a furniture drive now fails instead of passing`() = runBlocking {
        val prober = LinkProber(
            factory(WetriedSite.transport()), testSessionStore()
        )
        val result = prober.testDrive(
            furnitureConfig(), WetriedSite.BOOK_URL
        )
        val err = result as? AppResult.Err
            ?: throw AssertionError("expected Err, was $result")
        val reason = (err.error as? app.xreader.core.error.AppError.BadFile)
            ?.reason ?: ""
        assertTrue(
            "the failure must name the furniture, was: $reason",
            reason.contains("other books")
        )
    }

    @Test
    fun `a real chapter list still passes its drive`() = runBlocking {
        val prober = LinkProber(
            factory(WetriedSite.transportServing(WetriedSite.BOOK_PAGE_RENDERED)),
            testSessionStore()
        )
        val realConfig = furnitureConfig().copy(
            details = furnitureConfig().details.copy(
                chapters = ChapterListSpec(
                    list = "li.chapter-row a",
                    name = "@text",
                    link = "@href"
                )
            ),
            chapter = ChapterSpec(content = ".chapter-text")
        )
        val result = prober.testDrive(realConfig, WetriedSite.BOOK_URL)
        val ok = result as? AppResult.Ok
            ?: throw AssertionError("expected Ok, was $result")
        assertEquals(5, ok.value.chapterCount)
        assertTrue(ok.value.chapterTextLength > 200)
    }

    // ── the probe: flag + forced render ──────────────────────

    @Test
    fun `a plain probe fails and offers the rendered retry`() = runBlocking {
        val renderer = FakeRenderedFetcher(
            mapOf(WetriedSite.BOOK_URL to WetriedSite.BOOK_PAGE_RENDERED),
            WetriedSite::renderedFallback
        )
        val prober = LinkProber(
            factory(WetriedSite.transport(), renderer, runtime()),
            testSessionStore()
        )
        val outcome = prober.probe(WetriedSite.BOOK_URL)
        val failed = outcome as? LinkProber.Outcome.Failed
            ?: throw AssertionError("expected Failed, was $outcome")
        assertTrue(failed.renderWorthTrying)
        assertTrue(
            "the static page is not a shell — nothing may render yet",
            renderer.calls.isEmpty()
        )
    }

    @Test
    fun `the forced rendered probe reads the real list and marks the source`() =
        runBlocking {
            val renderer = FakeRenderedFetcher(
                mapOf(WetriedSite.BOOK_URL to WetriedSite.BOOK_PAGE_RENDERED),
                WetriedSite::renderedFallback
            )
            val prober = LinkProber(
                factory(WetriedSite.transport(), renderer, runtime()),
                testSessionStore()
            )
            val outcome = prober.probe(WetriedSite.BOOK_URL, forceRender = true)
            val ready = outcome as? LinkProber.Outcome.Ready
                ?: throw AssertionError("expected Ready, was $outcome")
            assertEquals(5, ready.draft.testDrive.chapterCount)
            assertTrue(ready.draft.testDrive.chapterTextLength > 200)
            assertTrue(
                "a source proven through the browser view is marked",
                ready.draft.config.needsRendering
            )
            assertTrue(renderer.calls.isNotEmpty())
        }

    @Test
    fun `no renderer wired means no retry is offered`() = runBlocking {
        val prober = LinkProber(
            factory(WetriedSite.transport()), testSessionStore()
        )
        val outcome = prober.probe(WetriedSite.BOOK_URL)
        val failed = outcome as? LinkProber.Outcome.Failed
            ?: throw AssertionError("expected Failed, was $outcome")
        assertFalse(failed.renderWorthTrying)
    }

    // ── the chikari shape (v1.0.7 draw-check fix) ────────────
    // The HOME page is a JS shell, so the plain pass renders
    // it; the book it walks into is unreadable statically but
    // NOT shell-shaped, so its own fetcher never escalates.
    // The failure must STILL offer the rendered retry — an
    // earlier version of the flag spent the retry because the
    // home page had rendered (on-device 2026-10-30: chikari's
    // home pasted, "No browser").

    @Test
    fun `a rendered home page does not spend the book's rendered chance`() =
        runBlocking {
            val prober = LinkProber(
                factory(
                    ShellHomeSite.transport(),
                    ShellHomeSite.renderer(),
                    runtime()
                ),
                testSessionStore()
            )
            val outcome = prober.probe(ShellHomeSite.HOME_URL)
            val failed = outcome as? LinkProber.Outcome.Failed
                ?: throw AssertionError("expected Failed, was $outcome")
            assertTrue(
                "the book page never rendered, so the retry stands",
                failed.renderWorthTrying
            )
        }

    @Test
    fun `the forced pass walks the rendered home into the rendered book`() =
        runBlocking {
            val prober = LinkProber(
                factory(
                    ShellHomeSite.transport(),
                    ShellHomeSite.renderer(),
                    runtime()
                ),
                testSessionStore()
            )
            val outcome = prober.probe(
                ShellHomeSite.HOME_URL, forceRender = true
            )
            val ready = outcome as? LinkProber.Outcome.Ready
                ?: throw AssertionError("expected Ready, was $outcome")
            assertEquals(4, ready.draft.testDrive.chapterCount)
            assertTrue(
                ready.draft.testDrive.chapterTextLength > 200
            )
        }

    // ── TEMPORARY staged diagnostics (gate run isolates the
    // failing stage of the forced walk-in; removed once known)
    @Test
    fun `diag D1 listing analysis reads the rendered home`() {
        val doc = org.jsoup.Jsoup.parse(
            ShellHomeSite.RENDERED_HOME, ShellHomeSite.HOME_URL
        )
        val listing = LinkInference.analyseListingPage(
            doc, ShellHomeSite.HOME_URL, null
        )
        assertNotNull("listing analysis was null", listing)
        assertEquals(4, listing!!.count)
    }

    @Test
    fun `diag D2 book analysis reads the rendered book`() {
        val doc = org.jsoup.Jsoup.parse(
            ShellHomeSite.RENDERED_BOOK, ShellHomeSite.BOOK_URL
        )
        val analysis = LinkInference.analyseBookPage(
            doc, ShellHomeSite.BOOK_URL
        )
        assertNotNull("book analysis was null", analysis)
        assertEquals(4, analysis!!.chapterCount)
    }

    @Test
    fun `diag D3 forced probe of the book url directly`() =
        runBlocking {
            val prober = LinkProber(
                factory(
                    ShellHomeSite.transport(),
                    ShellHomeSite.renderer(),
                    runtime()
                ),
                testSessionStore()
            )
            val outcome = prober.probe(
                ShellHomeSite.BOOK_URL, forceRender = true
            )
            assertTrue(
                "expected Ready, was $outcome",
                outcome is LinkProber.Outcome.Ready
            )
        }

    // Ladder over the D3 failure's own record — each rung is
    // a yes/no question the run list answers by name.
    private suspend fun d3Failure(): LinkProber.Outcome.Failed {
        val prober = LinkProber(
            factory(
                ShellHomeSite.transport(),
                ShellHomeSite.renderer(),
                runtime()
            ),
            testSessionStore()
        )
        val outcome = prober.probe(
            ShellHomeSite.BOOK_URL, forceRender = true
        )
        return outcome as? LinkProber.Outcome.Failed
            ?: throw AssertionError("not a failure: $outcome")
    }

    @Test
    fun `diag D4 the inference proposer was tried`() = runBlocking {
        assertTrue(d3Failure().proposersTried.contains("inference"))
    }

    @Test
    fun `diag D5 the drive died at the chapter list step`() =
        runBlocking {
            assertTrue(
                d3Failure().stageLines.any {
                    it.step == "chapter list"
                }
            )
        }

    @Test
    fun `diag D6 the drive died at the chapter text step`() =
        runBlocking {
            assertTrue(
                d3Failure().stageLines.any {
                    it.step == "chapter text"
                }
            )
        }

    @Test
    fun `diag D7 the failure is the furniture guard`() = runBlocking {
        assertTrue(
            d3Failure().message.contains("other books")
        )
    }

    @Test
    fun `diag D8 the failure is the feed shape gate`() = runBlocking {
        assertTrue(
            d3Failure().message.contains("look like posts")
        )
    }

    @Test
    fun `diag D9 the failure is the empty chapter list`() =
        runBlocking {
            assertTrue(
                d3Failure().message.contains("couldn't build a chapter list")
            )
        }

    /** A shell home page, a rendered listing, a book that
     * only exists after rendering. */
    object ShellHomeSite {
        const val BASE = "https://shellhome.example"
        const val HOME_URL = "$BASE/"
        const val BOOK_URL = "$BASE/series/the-rendered-sword"

        fun chapterUrl(n: Int) = "$BOOK_URL/chapter-$n"

        val SHELL_HOME = """
            <!DOCTYPE html><html><head><title>ShellBooks</title>
            </head><body>
            <div id="app"></div>
            <script src="/assets/app.js"></script>
            <script src="/assets/vendor.js"></script>
            </body></html>
        """.trimIndent()

        val RENDERED_HOME = """
            <!DOCTYPE html><html><head><title>ShellBooks</title>
            </head><body>
            <div class="brand">ShellBooks</div>
            <div class="grid">
            <div class="book-card"><a href="$BOOK_URL"><img src="/covers/rs.jpg"></a><a class="t" href="$BOOK_URL">The Rendered Sword</a></div>
            <div class="book-card"><a href="$BASE/series/other-tale-1"><img src="/covers/o1.jpg"></a><a class="t" href="$BASE/series/other-tale-1">Other Tale One</a></div>
            <div class="book-card"><a href="$BASE/series/other-tale-2"><img src="/covers/o2.jpg"></a><a class="t" href="$BASE/series/other-tale-2">Other Tale Two</a></div>
            <div class="book-card"><a href="$BASE/series/other-tale-3"><img src="/covers/o3.jpg"></a><a class="t" href="$BASE/series/other-tale-3">Other Tale Three</a></div>
            </div>
            </body></html>
        """.trimIndent()

        /** Real text (never shell-detected) but no readable
         * book in it: no title heading, no chapter list. */
        val STATIC_BOOK = """
            <!DOCTYPE html><html><head>
            <title>The Rendered Sword — ShellBooks</title>
            </head><body>
            <header>ShellBooks · Sign in · Register · Search</header>
            <main>
            <p>ShellBooks is a reading platform for serialised
            fiction, updated daily by its authors.</p>
            <p>Browse thousands of stories across every genre,
            follow your favourites and pick up where you left
            off on any device.</p>
            </main>
            <footer>About · Terms · Privacy · Contact</footer>
            </body></html>
        """.trimIndent()

        val RENDERED_BOOK = """
            <!DOCTYPE html><html><head>
            <title>The Rendered Sword — ShellBooks</title>
            </head><body>
            <h1 class="book-title">The Rendered Sword</h1>
            <div class="poster"><img src="/covers/rs.jpg"></div>
            <p class="synopsis">A blacksmith's apprentice finds a
            sword that only appears when the moon is rendered
            full, and every lord in the province wants it melted
            down before it can choose its bearer.</p>
            <ul class="chapter-rows">
            ${(1..4).joinToString("") { n ->
                "<li class=\"chapter-row\"><a href=\"" +
                    chapterUrl(n) + "\">Chapter $n</a></li>"
            }}
            </ul>
            </body></html>
        """.trimIndent()

        fun chapterPage(n: Int): String {
            val prose = (1..6).joinToString("") { i ->
                "<p>The bell over the forge rang for the $n-th " +
                    "time that morning, paragraph $i, and Maren " +
                    "counted the strokes the way her master had " +
                    "taught her: slowly, and without hope of " +
                    "finishing before the light changed over the " +
                    "yard and the apprentices came in from the " +
                    "rain with their boots singing on the stones.</p>"
            }
            return "<!DOCTYPE html><html><body>" +
                "<article class=\"chapter-text\">$prose</article>" +
                "</body></html>"
        }

        fun transport() = FakeTransport { request ->
            val url = request.url
            when {
                url == HOME_URL || url == BASE ->
                    FakeTransport.ok(SHELL_HOME)
                url == BOOK_URL -> FakeTransport.ok(STATIC_BOOK)
                url.startsWith("$BOOK_URL/chapter-") ->
                    FakeTransport.ok(
                        chapterPage(
                            url.substringAfterLast("chapter-")
                                .toIntOrNull() ?: 1
                        )
                    )
                else -> app.xreader.core.error.AppResult.Ok(
                    FakeTransport.response(404, "not found")
                )
            }
        }

        fun renderer() = FakeRenderedFetcher { url ->
            when {
                url == HOME_URL || url == BASE -> RENDERED_HOME
                url == BOOK_URL -> RENDERED_BOOK
                // A forced pass renders EVERY fetch, chapter
                // text included — the fake browser must serve
                // the chapter pages too, or the test-drive's
                // text step can never pass.
                url.startsWith("$BOOK_URL/chapter-") ->
                    chapterPage(
                        url.substringAfterLast("chapter-")
                            .toIntOrNull() ?: 1
                    )
                else -> null
            }
        }
    }
}
