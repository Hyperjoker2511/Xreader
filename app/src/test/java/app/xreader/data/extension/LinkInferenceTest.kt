package app.xreader.data.extension

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
 * Add-from-link (Amendment 7 gate): heuristic inference over fixture
 * HTML, then the full probe — fetch → infer → mandatory test-drive —
 * for the pass case, the login case, the chapter-link case, and the
 * plain-failure case.
 */
class LinkInferenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── inference over documents ────────────────────────────────────

    @Test
    fun `book page yields title chapters and reverse flag`() {
        val doc = Jsoup.parse(MoonSite.BOOK_PAGE, MoonSite.BOOK_URL)
        val analysis = LinkInference.analyseBookPage(doc, MoonSite.BOOK_URL)
        assertNotNull(analysis)
        analysis!!
        assertEquals("Star Fall", analysis.title)
        assertEquals(3, analysis.chapterCount)
        assertTrue("newest-first list detected", analysis.newestFirst)
        assertEquals(MoonSite.CHAPTER_1_URL, analysis.firstChapterUrl)
        assertTrue(analysis.coverUrl!!.endsWith("star.jpg"))
    }

    @Test
    fun `chapter page yields the readable block and next link`() {
        val doc = Jsoup.parse(MoonSite.CHAPTER_PAGE, MoonSite.CHAPTER_1_URL)
        val analysis = LinkInference.analyseChapterPage(doc, MoonSite.CHAPTER_1_URL)
        assertNotNull(analysis)
        analysis!!
        assertTrue(analysis.textLength >= 200)
        assertTrue(analysis.preview.contains("The star fell"))
        assertNotNull(analysis.nextPageSelector)
        // The inferred selector must actually select the content.
        val selected = doc.select(analysis.contentSelector)
        assertTrue(selected.isNotEmpty())
        assertTrue(selected.first()!!.text().contains("The star fell"))
    }

    @Test
    fun `login page is detected`() {
        val doc = Jsoup.parse(MoonSite.LOGIN_PAGE, MoonSite.LOGIN_URL)
        assertTrue(LinkInference.needsLogin(doc, MoonSite.LOGIN_URL))
        val bookDoc = Jsoup.parse(MoonSite.BOOK_PAGE, MoonSite.BOOK_URL)
        assertTrue(!LinkInference.needsLogin(bookDoc, MoonSite.BOOK_URL))
    }

    @Test
    fun `sparse page yields no book analysis`() {
        val doc = Jsoup.parse(MoonSite.SPARSE_PAGE, "https://blog.example/post")
        assertNull(LinkInference.analyseBookPage(doc, "https://blog.example/post"))
        assertNull(LinkInference.analyseChapterPage(doc, "https://blog.example/post"))
    }

    @Test
    fun `synthesised selectors avoid generated class names`() {
        val doc = Jsoup.parse(
            """<div class="css-1x2y3z4w abc123def456"><p id="real">x</p></div>""",
            "https://x.example/"
        )
        val selector = SelectorSynthesizer.forElement(doc.selectFirst("p")!!)
        assertTrue(selector.contains("#real"))
        assertTrue(!selector.contains("css-1x2y3z4w"))
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
    fun `probe of a book page passes the test drive`() = runBlocking {
        val outcome = prober(MoonSite.transport()).probe(MoonSite.BOOK_URL)
        assertTrue("expected Ready, got $outcome", outcome is LinkProber.Outcome.Ready)
        val draft = (outcome as LinkProber.Outcome.Ready).draft
        assertEquals("MoonRead", draft.config.name)
        assertEquals("Star Fall", draft.testDrive.bookTitle)
        assertEquals(3, draft.testDrive.chapterCount)
        assertTrue(draft.testDrive.chapterTextPreview.contains("The star fell"))
        assertEquals("novels host", "moonread.example", draft.host)
    }

    @Test
    fun `probe of a chapter page finds its book page`() = runBlocking {
        val outcome = prober(MoonSite.transport()).probe(MoonSite.CHAPTER_1_URL)
        assertTrue("expected Ready, got $outcome", outcome is LinkProber.Outcome.Ready)
    }

    @Test
    fun `probe of a login-walled page asks for login`() = runBlocking {
        val outcome = prober(MoonSite.transport()).probe(MoonSite.LOGIN_URL)
        assertTrue(outcome is LinkProber.Outcome.LoginRequired)
    }

    @Test
    fun `probe of an unreadable page fails plainly`() = runBlocking {
        val outcome = prober(MoonSite.transport()).probe("https://blog.example/post")
        assertTrue(outcome is LinkProber.Outcome.Failed)
        val message = (outcome as LinkProber.Outcome.Failed).message
        assertTrue(message.contains("couldn't spot"))
    }

    @Test
    fun `url normalisation adds a scheme and rejects junk`() {
        assertEquals(
            "https://moonread.example/novel/star-fall",
            LinkProber.normaliseUrl("moonread.example/novel/star-fall")
        )
        assertNull(LinkProber.normaliseUrl("not a url"))
        assertNull(LinkProber.normaliseUrl(""))
    }
}

/** A second fixture site, structurally different from FixtureSite —
 * the prober has never "seen" its layout in any config. */
object MoonSite {
    const val HOST = "moonread.example"
    const val BASE = "https://moonread.example"
    const val BOOK_URL = "$BASE/novel/star-fall"
    const val CHAPTER_1_URL = "$BASE/novel/star-fall/chapter-1"
    const val LOGIN_URL = "$BASE/login"

    val chapterText = (1..5).joinToString("\n") {
        "The star fell over the valley in part $it, and everyone in the village looked up at once."
    }

    const val BOOK_PAGE = """
        <html><head>
          <title>Star Fall - MoonRead</title>
          <meta property="og:title" content="Star Fall"/>
          <meta property="og:site_name" content="MoonRead"/>
          <meta property="og:image" content="https://moonread.example/covers/star.jpg"/>
          <meta name="author" content="Sky Writer"/>
        </head><body>
        <header><nav><a href="/">Home</a></nav></header>
        <form action="/search" method="get"><input type="text" name="q"/></form>
        <h1>Star Fall</h1>
        <ul id="chapter-list">
          <li><a href="/novel/star-fall/chapter-3">Chapter 3: Impact</a></li>
          <li><a href="/novel/star-fall/chapter-2">Chapter 2: Falling</a></li>
          <li><a href="/novel/star-fall/chapter-1">Chapter 1: The Sky</a></li>
        </ul>
        </body></html>
    """

    val CHAPTER_PAGE = """
        <html><head><title>Chapter 1 - Star Fall</title></head><body>
        <nav class="topnav"><a href="/">Home</a> <a href="/novel/star-fall">Table of Contents</a></nav>
        <h1>Chapter 1: The Sky</h1>
        <div id="chapter-content">
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        <div class="chapter-nav">
          <a class="next-btn" href="/novel/star-fall/chapter-2">Next Chapter</a>
        </div>
        </body></html>
    """.trimIndent()

    const val LOGIN_PAGE = """
        <html><head><title>Sign in - MoonRead</title></head><body>
        <form action="/login" method="post">
          <input type="text" name="user"/>
          <input type="password" name="pass"/>
          <button>Sign in</button>
        </form>
        </body></html>
    """

    const val SPARSE_PAGE = """
        <html><head><title>A blog post</title></head><body>
        <article><p>Just a short blog post about gardening and weather.</p></article>
        </body></html>
    """

    private fun chapterPage(n: Int, title: String) = """
        <html><head><title>Chapter $n</title></head><body>
        <h1>Chapter $n: $title</h1>
        <div id="chapter-content">
          ${chapterText.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        </body></html>
    """.trimIndent()

    fun transport(): FakeTransport {
        val pages = mapOf(
            BOOK_URL to BOOK_PAGE,
            CHAPTER_1_URL to CHAPTER_PAGE,
            "$BASE/novel/star-fall/chapter-2" to chapterPage(2, "Falling"),
            "$BASE/novel/star-fall/chapter-3" to chapterPage(3, "Impact"),
            LOGIN_URL to LOGIN_PAGE,
            "https://blog.example/post" to SPARSE_PAGE,
            "$BASE/search?q=the" to """
                <html><body><div class="grid">
                  <a href="/novel/star-fall"><img src="/covers/star.jpg"/>Star Fall</a>
                  <a href="/novel/moon-rise"><img src="/covers/moon.jpg"/>Moon Rise</a>
                </div></body></html>
            """.trimIndent(),
            BASE to """
                <html><body><div class="grid">
                  <a href="/novel/star-fall"><img src="/covers/star.jpg"/>Star Fall</a>
                  <a href="/novel/moon-rise"><img src="/covers/moon.jpg"/>Moon Rise</a>
                  <a href="/novel/sun-set"><img src="/covers/sun.jpg"/>Sun Set</a>
                </div></body></html>
            """.trimIndent()
        )
        return FakeTransport { request ->
            val body = pages[request.url]
            if (body == null) {
                app.xreader.core.error.AppResult.Ok(
                    FakeTransport.response(404, "not found")
                )
            } else {
                FakeTransport.ok(body)
            }
        }
    }
}
