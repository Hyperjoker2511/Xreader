package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The heuristic proposer (v1.0.4, D15): density + clustering over
 * fixture pages [LinkInference] cannot read — bare-number chapter
 * links in wrapper spans, a synopsis in an unnamed plain block —
 * plus its safe-selector choices, and the full probe: a site only
 * the proposer can read still has to pass the mandatory test-drive
 * before the probe calls it Ready.
 */
class HeuristicProposerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── the book page ───────────────────────────────────────────────

    @Test
    fun `bare number chapter links defeat the primary inference`() {
        val doc = Jsoup.parse(OddSite.BOOK_PAGE, OddSite.BOOK_URL)
        assertNull(LinkInference.analyseBookPage(doc, OddSite.BOOK_URL))
    }

    @Test
    fun `proposer reads title chapters and synopsis from the same page`() {
        val doc = Jsoup.parse(OddSite.BOOK_PAGE, OddSite.BOOK_URL)
        val analysis = HeuristicProposer.analyseBookPage(doc, OddSite.BOOK_URL)
        assertNotNull(analysis)
        analysis!!
        assertEquals("Quiet Star", analysis.title)
        assertEquals(3, analysis.chapterCount)
        assertEquals(OddSite.CHAPTER_3_URL, analysis.firstChapterUrl)
        assertEquals("a@text", analysis.chapterNameSpec)
        assertEquals("a@href", analysis.chapterLinkSpec)
        // The list selector must select exactly the three rows.
        assertEquals(3, doc.select(analysis.chapterListSelector).size)
        // The density-scored synopsis re-selects the blurb block.
        val synopsis = doc.selectFirst(analysis.descriptionSpec!!)
        assertNotNull(synopsis)
        assertTrue(synopsis!!.text().contains("quiet star burns"))
    }

    // ── synopsis scoring ────────────────────────────────────────────

    @Test
    fun `synopsis name bonus beats a longer unnamed block`() {
        val summary = "A named summary block that is long enough to " +
            "qualify on its own, telling the shape of the story in " +
            "one breath and no more than that."
        val longer = "An unnamed block of plain text that rambles on " +
            "for considerably longer than the summary above, filling " +
            "space with words about the weather, the roads, the " +
            "price of salt in the harbour towns and other matters " +
            "of no consequence to anyone at all in particular."
        assertTrue(summary.length >= HeuristicProposer.MIN_SYNOPSIS_CHARS)
        assertTrue(longer.length > summary.length)
        val doc = Jsoup.parse(
            "<html><body>" +
                "<div class=\"summary\">$summary</div>" +
                "<div>$longer</div>" +
                "</body></html>",
            "https://odd.example/book/x"
        )
        val spec = HeuristicProposer.synopsisSpec(doc)
        assertNotNull(spec)
        assertTrue(doc.selectFirst(spec!!)!!.text().contains("named summary"))
    }

    @Test
    fun `synopsis falls back to the longest block without name hints`() {
        val winner = "The longest block of story-shaped text on the " +
            "page, carrying the actual synopsis of the book in " +
            "plain words that keep going well past the minimum " +
            "length the scorer asks for before it pays attention."
        val doc = Jsoup.parse(
            "<html><body>" +
                "<div>Short menu words and links.</div>" +
                "<div id=\"story\">$winner</div>" +
                "</body></html>",
            "https://odd.example/book/x"
        )
        val spec = HeuristicProposer.synopsisSpec(doc)
        assertEquals("#story", spec)
        assertTrue(doc.selectFirst(spec!!)!!.text().contains("longest block"))
    }

    @Test
    fun `page without a qualifying block has no synopsis`() {
        val doc = Jsoup.parse(
            "<html><body><div>Too short.</div></body></html>",
            "https://odd.example/book/x"
        )
        assertNull(HeuristicProposer.synopsisSpec(doc))
    }

    // ── safe selector ───────────────────────────────────────────────

    @Test
    fun `safe selector prefers id then class then structure`() {
        val doc = Jsoup.parse(
            "<html><body>" +
                "<p id=\"main\">one</p>" +
                "<p class=\"active lead-para\">two</p>" +
                "<section><p>three</p></section>" +
                "</body></html>",
            "https://odd.example/"
        )
        assertEquals(
            "#main",
            HeuristicProposer.safeSelector(doc.selectFirst("p#main")!!)
        )
        // "active" is skipped — it marks state, not identity.
        assertEquals(
            ".lead-para",
            HeuristicProposer.safeSelector(doc.select("p")[1])
        )
        val bare = doc.select("section p").first()!!
        val structural = HeuristicProposer.safeSelector(bare)
        assertTrue(structural.isNotBlank())
        assertEquals(bare, doc.selectFirst(structural))
    }

    // ── the full probe ──────────────────────────────────────────────

    private fun prober(transport: FakeTransport): LinkProber {
        val filesDir = tmp.newFolder()
        val factory = HostContextFactory(
            ExtensionStore(filesDir), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    @Test
    fun `probe passes the test drive on a proposer-only site`() = runBlocking {
        val outcome = prober(OddSite.transport()).probe(OddSite.BOOK_URL)
        assertTrue("expected Ready, got $outcome", outcome is LinkProber.Outcome.Ready)
        val draft = (outcome as LinkProber.Outcome.Ready).draft
        assertEquals("Quiet Star", draft.testDrive.bookTitle)
        assertEquals(3, draft.testDrive.chapterCount)
        assertTrue(draft.testDrive.chapterTextPreview.contains("salt wind"))
        // The density synopsis made it into the saved rules…
        assertEquals(".blurb", draft.config.details.description)
        // …and search is honestly flagged as not set up.
        assertTrue(draft.config.searchNeedsSetup)
    }
}

/** A site the primary inference can't read: chapter links whose
 * text is a bare number and whose URLs end in a single digit, rows
 * wrapped in spans, a synopsis in an unnamed plain div. */
private object OddSite {
    const val BASE = "https://odd.example"
    const val BOOK_URL = "$BASE/book/quiet-star"
    const val CHAPTER_3_URL = "$BASE/book/quiet-star/3"

    val BOOK_PAGE = """
        <html><head>
          <title>Quiet Star - Odd Reads</title>
          <meta property="og:image" content="$BASE/covers/quiet.jpg"/>
        </head><body>
        <h1>Quiet Star</h1>
        <div class="blurb">A quiet star burns over the salt flats, and the cartographer who mapped it has vanished without a trace. Her apprentice crosses the flats with an unfinished atlas, a stubborn mule, and a sealed letter.</div>
        <div class="toc">
          <span><a href="/book/quiet-star/3">3</a></span>
          <span><a href="/book/quiet-star/2">2</a></span>
          <span><a href="/book/quiet-star/1">1</a></span>
        </div>
        </body></html>
    """.trimIndent()

    private val chapterText =
        "The salt wind came before the dawn, and the apprentice " +
            "climbed the last dune with the atlas held tight against " +
            "her chest. Below her the flats shone like a frozen sea. " +
            "She opened the letter with numb fingers and read the " +
            "first line twice, because the words did not believe " +
            "themselves. The star, her teacher wrote, was not a star " +
            "at all, and the silence everyone waited for had already " +
            "begun years ago, quietly, in the observatory cellar."

    fun transport(): FakeTransport {
        val pages = mapOf(
            BOOK_URL to BOOK_PAGE,
            CHAPTER_3_URL to """
                <html><head><title>3</title></head><body>
                <div class="content"><p>$chapterText</p></div>
                </body></html>
            """.trimIndent()
        )
        return FakeTransport { request ->
            val body = pages[request.url]
            if (body == null) {
                AppResult.Ok(FakeTransport.response(404, "not found"))
            } else {
                FakeTransport.ok(body)
            }
        }
    }
}
