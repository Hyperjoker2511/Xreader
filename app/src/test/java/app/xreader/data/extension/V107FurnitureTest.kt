package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
