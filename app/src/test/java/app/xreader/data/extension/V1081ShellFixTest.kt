package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.8.1: the app-shell fix round — pins for the pieces the
 * JVM suite CAN see (the WebView fetcher itself is device-bound):
 * the text-bearing anchor rescan in spec resolution, and (in
 * V106StageBTest) the content-aware settle rule. The chikari
 * lane pins live with the refreshed ChikariSite fixture.
 */
class V1081ShellFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Text-bearing anchor rescan (Problem 2) ───────────────

    /** A catalogue whose cards lead with a TEXTLESS cover link
     * — the shape that starved the inferred `a@text` title spec
     * in the v1.0.8 gate saga and on chikari.moe's cards. */
    private object CoverFirstCards {
        const val HOST = "cards.example"
        const val BASE = "https://cards.example"

        val HOME = """
            <html><body><div class="shelf">
              <div class="card">
                <a class="cover" href="/book/alpha"><img src="/covers/alpha.jpg"/></a>
                <a class="title" href="/book/alpha">Alpha Rising</a>
              </div>
              <div class="card">
                <a class="cover" href="/book/beta"><img src="/covers/beta.jpg"/></a>
                <a class="title" href="/book/beta">Beta Falls</a>
              </div>
              <div class="card">
                <a class="cover" href="/book/gamma"><img src="/covers/gamma.jpg"/></a>
                <a class="title" href="/book/gamma">Gamma Drift</a>
              </div>
            </div></body></html>
        """.trimIndent()

        fun transport(): FakeTransport = FakeTransport { request ->
            if (request.url == BASE || request.url == "$BASE/") {
                FakeTransport.ok(HOME)
            } else {
                AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }

        fun config() = DeclarativeConfig(
            id = "cards",
            name = "Cards",
            baseUrl = BASE,
            popular = ListSpec(
                url = BASE,
                results = ".card",
                title = "a@text",
                link = "a@href",
                cover = "img@src"
            ),
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul li", name = "a@text", link = "a@href"
                )
            ),
            chapter = ChapterSpec(content = "article")
        )
    }

    @Test
    fun `listing titles come from the text-bearing anchor not the cover link`() =
        runBlocking {
            val context = testContext(
                CoverFirstCards.transport(), tmp.newFolder(),
                permissions = ExtensionPermissions(
                    domains = listOf(CoverFirstCards.HOST)
                )
            )
            val source = DeclarativeSource(CoverFirstCards.config(), context)
            val page = source.popular(1)
            assertEquals(3, page.books.size)
            assertEquals(
                listOf("Alpha Rising", "Beta Falls", "Gamma Drift"),
                page.books.map { it.title }
            )
            assertEquals(
                "${CoverFirstCards.BASE}/book/alpha", page.books.first().id
            )
            assertTrue(
                page.books.all { it.coverUrl?.endsWith(".jpg") == true }
            )
        }

    // ── The chikari lane + pattern completion ────────────────

    private fun laneConfig(): DeclarativeConfig {
        val entry = KnownSites.entryFor(ChikariSite.HOST)!!
        return KnownSites.fullConfig(
            entry, "chikari", "Chikari", ChikariSite.BASE
        )!!
    }

    @Test
    fun `lane completes the chapter list from the stated total`() =
        runBlocking {
            // The fetch layer hands back the rendered book page
            // (the lane is marked needsRendering; the pattern
            // logic itself is fetch-agnostic).
            val transport = FakeTransport { request ->
                if (request.url == ChikariSite.BOOK_URL) {
                    FakeTransport.ok(ChikariSite.RENDERED_BOOK_PAGE)
                } else {
                    AppResult.Ok(FakeTransport.response(404, "not found"))
                }
            }
            val context = testContext(
                transport, tmp.newFolder(),
                permissions = ExtensionPermissions(
                    domains = listOf(ChikariSite.HOST)
                )
            )
            val source = DeclarativeSource(laneConfig(), context)
            val (book, chapters) = source.detailsAndChapters(
                SourceBook(
                    id = ChikariSite.BOOK_URL, title = "Shadow Slave"
                )
            )
            assertEquals("Shadow Slave", book.title)
            assertEquals("GuiltyThree", book.author)
            // The page renders 3 rows but states 7 chapters —
            // the list is the full 1..7 in reading order, and
            // the similar-novels link never becomes a chapter.
            assertEquals(7, chapters.size)
            assertEquals(
                (1..7).map { "${ChikariSite.BOOK_URL}/$it" },
                chapters.map { it.id }
            )
            assertEquals(
                (1..7).map { "Chapter $it" },
                chapters.map { it.name }
            )
        }

    // ── The lane through the prober (the v1.0.8 draw-check
    //    failures, one per paste shape) ───────────────────────

    private fun runtime(): RenderRuntime {
        val runtime = RenderRuntime(
            MutableStateFlow(true), MutableStateFlow(PerfTier.FULL)
        )
        runtime.appInForeground = true
        return runtime
    }

    private suspend fun runtimeReady(): RenderRuntime {
        val runtime = runtime()
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
        renderer: FakeRenderedFetcher?,
        runtime: RenderRuntime
    ): HostContextFactory = HostContextFactory(
        ExtensionStore(tmp.newFolder()),
        testSessionStore(),
        transport,
        renderedFetcher = renderer,
        renderRuntime = runtime
    )

    private suspend fun probeChikari(
        pasted: String
    ): LinkProber.Outcome {
        val prober = LinkProber(
            factory(
                ChikariSite.transport(),
                FakeRenderedFetcher(
                    ChikariSite.renderedPages(),
                    ChikariSite::renderedFallback
                ),
                runtimeReady()
            ),
            testSessionStore()
        )
        return prober.probe(pasted)
    }

    @Test
    fun `a chikari book link is added through its lane`() = runBlocking {
        val outcome = probeChikari(ChikariSite.BOOK_URL)
        val ready = outcome as? LinkProber.Outcome.Ready
        assertTrue("expected Ready, was $outcome", ready != null)
        val draft = ready!!.draft
        assertTrue(draft.config.needsRendering)
        assertEquals(
            "{bookUrl}/{n}",
            draft.config.details.chapters.urlPattern
        )
        assertEquals("Shadow Slave", draft.testDrive.bookTitle)
        // The drive itself saw the completed list: the page
        // renders 3 rows, the book has 7 chapters.
        assertEquals(7, draft.testDrive.chapterCount)
        assertTrue(draft.testDrive.chapterTextLength > 0)
    }

    @Test
    fun `a chikari home link is added through its lane`() = runBlocking {
        val outcome = probeChikari(ChikariSite.BASE)
        val ready = outcome as? LinkProber.Outcome.Ready
        assertTrue("expected Ready, was $outcome", ready != null)
        assertEquals(
            ChikariSite.BOOK_URL, ready!!.draft.bookUrl
        )
        assertEquals("Shadow Slave", ready.draft.testDrive.bookTitle)
    }

    @Test
    fun `a chikari chapter link is added through its lane`() =
        runBlocking {
            val outcome = probeChikari(ChikariSite.CHAPTER_1_URL)
            val ready = outcome as? LinkProber.Outcome.Ready
            assertTrue("expected Ready, was $outcome", ready != null)
            assertEquals(
                ChikariSite.BOOK_URL, ready!!.draft.bookUrl
            )
        }
}
