package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * WS3 learner-level fixes (site-batch Phase A): one durable test
 * per failure class from SITE-BATCH-A-WS2.md, built from the REAL
 * captured structures with SYNTHETIC text (invented titles and
 * prose — no site copy). Each test failed before its fix.
 */
class Ws3LearnerFixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    private fun transportOf(pages: Map<String, String>): FakeTransport =
        FakeTransport { request ->
            val body = pages[request.url]
            if (body == null) {
                AppResult.Ok(FakeTransport.response(404, "not found"))
            } else {
                FakeTransport.ok(body)
            }
        }

    private val prose: String = (1..6).joinToString("\n") {
        "The invented hero crossed the quiet valley in part $it, and the " +
            "whole village watched the strange lights move over the hills."
    }

    private fun prosePage(title: String): String = """
        <html><head><title>$title</title></head><body>
        <h1>$title</h1>
        <div class="content">
          ${prose.split("\n").joinToString("\n") { "<p>$it</p>" }}
        </div>
        </body></html>
    """.trimIndent()

    // ── A: login-wall false positive ────────────────────────────

    @Test
    fun `A - book page with a login modal in chrome is not a login wall`() {
        val html = """
            <html><head><title>Invented Tale</title></head><body>
            <div class="modal" id="login-modal">
              <form action="/login" method="post">
                <input type="text" name="user"/>
                <input type="password" name="pass"/>
              </form>
            </div>
            <h1>Invented Tale</h1>
            <ul class="chapters">
              <li><a href="/novel/invented-tale/chapter-3">Chapter 3</a></li>
              <li><a href="/novel/invented-tale/chapter-2">Chapter 2</a></li>
              <li><a href="/novel/invented-tale/chapter-1">Chapter 1</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://modal.example/novel/invented-tale")
        assertFalse(
            "public page with login chrome must not read as a wall",
            LinkInference.needsLogin(doc, "https://modal.example/novel/invented-tale")
        )
    }

    @Test
    fun `A - content-rich page with a login modal is not a login wall`() {
        val links = (1..10).joinToString("\n") {
            """<a href="/novel/book-$it"><img src="/c$it.jpg"/>Book $it</a>"""
        }
        val html = """
            <html><head><title>Home</title></head><body>
            <div class="login-box"><form action="/login" method="post">
              <input type="password" name="pass"/></form></div>
            <div class="grid">$links</div>
            <div class="about"><p>${"A public catalogue page with plenty of readable text. ".repeat(12)}</p></div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://modal.example/")
        assertFalse(LinkInference.needsLogin(doc, "https://modal.example/"))
    }

    @Test
    fun `A - a bare login page is still a login wall`() {
        val html = """
            <html><head><title>Sign in</title></head><body>
            <form action="/login" method="post">
              <input type="text" name="user"/>
              <input type="password" name="pass"/>
              <button>Sign in</button>
            </form>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://modal.example/login")
        assertTrue(LinkInference.needsLogin(doc, "https://modal.example/login"))
    }

    // ── F: next-chapter link read as next-page ──────────────────

    @Test
    fun `F - a next chapter footer link is not an in-chapter next page`() {
        val html = """
            <html><head><title>Chapter 1</title></head><body>
            <h1>Chapter 1</h1>
            <div class="content">
              ${prose.split("\n").joinToString("\n") { "<p>$it</p>" }}
            </div>
            <footer>
              <a href="/novel/invented-tale/chapter-2">Next Chapter</a>
            </footer>
            </body></html>
        """.trimIndent()
        val url = "https://next.example/novel/invented-tale/chapter-1"
        val doc = Jsoup.parse(html, url)
        val analysis = LinkInference.analyseChapterPage(doc, url)
        assertNotNull(analysis)
        assertNull(
            "the next-CHAPTER link must not become the next-PAGE selector",
            analysis!!.nextPageSelector
        )
    }

    @Test
    fun `F - a true page two of the same chapter still counts as next page`() {
        val html = """
            <html><head><title>Chapter 1</title></head><body>
            <h1>Chapter 1</h1>
            <div class="content">
              ${prose.split("\n").joinToString("\n") { "<p>$it</p>" }}
            </div>
            <footer>
              <a href="/novel/invented-tale/chapter-2">Next Chapter</a>
              <a href="/novel/invented-tale/chapter-1?page=2">Next</a>
            </footer>
            </body></html>
        """.trimIndent()
        val url = "https://next.example/novel/invented-tale/chapter-1"
        val doc = Jsoup.parse(html, url)
        val analysis = LinkInference.analyseChapterPage(doc, url)
        assertNotNull(analysis)
        val selector = analysis!!.nextPageSelector
        assertNotNull("the same-chapter page-2 link is a real next page", selector)
        val target = doc.select(selector!!).first()
        assertNotNull(target)
        assertTrue(target!!.attr("href").contains("page=2"))
    }

    // ── G: cross-host contamination ─────────────────────────────

    private val coolBookPage = """
        <html><head><title>Invented Cool Tale</title>
        <meta property="og:title" content="Invented Cool Tale"/></head><body>
        <h1>Invented Cool Tale</h1>
        <div class="chapter-item-list">
          <div class="chp-item"><a href="https://ads.example.net/click/1">Chapter 1</a></div>
          <div class="chp-item"><a href="/novel/invented/chapter-1">Chapter 1</a></div>
          <div class="chp-item"><a href="/novel/invented/chapter-2">Chapter 2</a></div>
          <div class="chp-item"><a href="/novel/invented/chapter-3">Chapter 3</a></div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `G - a foreign host ad row never becomes a chapter`() {
        val url = "https://cool.example/novel/invented"
        val doc = Jsoup.parse(coolBookPage, url)
        val analysis = LinkInference.analyseBookPage(doc, url)
        assertNotNull(analysis)
        assertEquals(3, analysis!!.chapterCount)
        assertEquals(
            "https://cool.example/novel/invented/chapter-1",
            analysis.firstChapterUrl
        )
    }

    @Test
    fun `G - the engine drops foreign host chapter rows`() {
        val url = "https://cool.example/novel/invented"
        val transport = transportOf(mapOf(url to coolBookPage))
        val context = testContext(
            transport, tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf("cool.example"))
        )
        val config = DeclarativeConfig(
            id = "cool", name = "Cool", baseUrl = "https://cool.example",
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "div.chp-item", name = "a@text", link = "a@href"
                )
            ),
            chapter = ChapterSpec(content = "div.content")
        )
        val chapters = DeclarativeSource(config, context)
            .chapters(SourceBook(id = url, title = "Invented Cool Tale"))
        assertEquals(3, chapters.size)
        assertTrue(chapters.all { it.id.startsWith("https://cool.example/") })
    }

    // ── B: cluster depth and priority ───────────────────────────

    @Test
    fun `B - table rows two levels up form the chapter cluster`() {
        val rows = (1..4).joinToString("\n") { n ->
            """<tr><td><a href="/fiction/9/invented/chapter/100$n/chapter-$n">Chapter $n: Part $n</a></td><td>Jan $n</td></tr>"""
        }
        val html = """
            <html><head><title>Invented Table Tale</title>
            <meta property="og:title" content="Invented Table Tale"/></head><body>
            <h1>Invented Table Tale</h1>
            <table class="toc"><tbody>$rows</tbody></table>
            </body></html>
        """.trimIndent()
        val url = "https://table.example/fiction/9/invented"
        val doc = Jsoup.parse(html, url)
        val analysis = LinkInference.analyseBookPage(doc, url)
        assertNotNull("the tr-level cluster must be found", analysis)
        assertEquals(4, analysis!!.chapterCount)
        assertTrue(analysis.firstChapterUrl.contains("chapter/1001"))
    }

    @Test
    fun `B - a small direct sibling group never beats a larger wrapper cluster`() {
        val widget = (2019..2022).joinToString("\n") { y ->
            """<a href="/release/$y/">$y</a>"""
        }
        val rows = (1..6).joinToString("\n") { n ->
            """<div class="row"><a href="/novel/invented/chapter-$n">Chapter $n</a></div>"""
        }
        val html = """
            <html><head><title>Invented Widget Tale</title>
            <meta property="og:title" content="Invented Widget Tale"/></head><body>
            <h1>Invented Widget Tale</h1>
            <ul class="widget">$widget</ul>
            <div class="list">$rows</div>
            </body></html>
        """.trimIndent()
        val url = "https://widget.example/novel/invented"
        val doc = Jsoup.parse(html, url)
        val analysis = LinkInference.analyseBookPage(doc, url)
        assertNotNull(analysis)
        assertEquals(
            "the 6-row chapter cluster beats the 4-item widget",
            6, analysis!!.chapterCount
        )
        assertTrue(analysis.firstChapterUrl.contains("chapter-1"))
    }

    @Test
    fun `B - per item parents with a shared container one level higher still cluster`() {
        val groups = (1..4).joinToString("\n") { n ->
            """<div class="group"><ul><li><a href="/novel/invented/$n">Chapter $n</a></li></ul></div>"""
        }
        val html = """
            <html><head><title>Invented Deep Tale</title>
            <meta property="og:title" content="Invented Deep Tale"/></head><body>
            <h1>Invented Deep Tale</h1>
            <div class="dir">$groups</div>
            </body></html>
        """.trimIndent()
        val url = "https://deep.example/novel/invented"
        val doc = Jsoup.parse(html, url)
        val analysis = LinkInference.analyseBookPage(doc, url)
        assertNotNull("the depth-3 cluster must be found", analysis)
        assertEquals(4, analysis!!.chapterCount)
    }

    // ── C2: selector synthesis drops item-unique classes ────────

    @Test
    fun `C2 - a list selector drops the class only the first item carries`() {
        val html = """
            <html><body><div class="list">
              <div class="item top-1"><span>First</span></div>
              <div class="item"><span>Second</span></div>
              <div class="item"><span>Third</span></div>
              <div class="item"><span>Fourth</span></div>
            </div></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://rank.example/")
        val first = doc.selectFirst("div.item")!!
        val selector = SelectorSynthesizer.forList(first)
        assertFalse("rank class leaked into the selector: $selector",
            selector.contains("top-1"))
        assertEquals(4, doc.select(selector).size)
    }

    // ── M: challenge phrase in the title ────────────────────────

    @Test
    fun `M - a challenge phrase that lives only in the title is detected`() {
        val html = """
            <html><head><title>Just a moment...</title></head>
            <body><div class="vb-custom-page"><p>Please wait</p></div></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://blocked.example/")
        assertTrue(SiteCheck.hasChallengeMarkers(doc))
    }

    // ── E: breadcrumb title ─────────────────────────────────────

    @Test
    fun `E - a breadcrumb h1 does not beat the books own title heading`() {
        val html = """
            <html><head><title>Read The Invented Title online - Example</title></head><body>
            <ol class="breadcrumb">
              <li><a href="/">Home</a></li>
              <li class="active"><h1><a href="/genre/fantasy">Fantasy</a></h1></li>
            </ol>
            <div class="books"><div class="desc"><h3 class="title">The Invented Title</h3></div></div>
            <ul class="chapters">
              <li><a href="/invented/chapter-1">Chapter 1</a></li>
              <li><a href="/invented/chapter-2">Chapter 2</a></li>
              <li><a href="/invented/chapter-3">Chapter 3</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val url = "https://crumb.example/invented.html"
        val doc = Jsoup.parse(html, url)
        val analysis = LinkInference.analyseBookPage(doc, url)
        assertNotNull(analysis)
        assertEquals("The Invented Title", analysis!!.title)
    }

    // ── C + J: drive-level verdicts ─────────────────────────────

    private fun driveConfig(popularResults: String?): DeclarativeConfig =
        DeclarativeConfig(
            id = "drive", name = "Drive", baseUrl = "https://drive.example",
            popular = popularResults?.let {
                ListSpec(
                    url = "https://drive.example/",
                    results = it, title = "a@text", link = "a@href"
                )
            },
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "ul.chapters > li", name = "a@text", link = "a@href"
                )
            ),
            chapter = ChapterSpec(content = "div.content")
        )

    private val driveBookPage = """
        <html><head><title>Invented Drive Tale</title></head><body>
        <h1>Invented Drive Tale</h1>
        <ul class="chapters">
          <li><a href="/novel/invented/chapter-1">Chapter 1</a></li>
          <li><a href="/novel/invented/chapter-2">Chapter 2</a></li>
          <li><a href="/novel/invented/chapter-3">Chapter 3</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private fun driveTransport(home: String, chapter: String): FakeTransport =
        transportOf(
            mapOf(
                "https://drive.example/" to home,
                "https://drive.example/novel/invented" to driveBookPage,
                "https://drive.example/novel/invented/chapter-1" to chapter,
                "https://drive.example/novel/invented/chapter-2" to chapter,
                "https://drive.example/novel/invented/chapter-3" to chapter
            )
        )

    @Test
    fun `C - announcement posts with year old stamps judge as a feed`() = runBlocking {
        val home = """
            <html><body><div class="posts">
              <div class="post"><a href="/announcements/1">Site News 1 year ago</a></div>
              <div class="post"><a href="/announcements/2">Big Update 2 years ago</a></div>
              <div class="post"><a href="/announcements/3">Maintenance Notes 1 year ago</a></div>
            </div></body></html>
        """.trimIndent()
        val result = prober(driveTransport(home, prosePage("Chapter 1")))
            .testDrive(driveConfig("div.post"), "https://drive.example/novel/invented")
        assertTrue(
            "announcement posts must fail the list-shape gate, got $result",
            result is AppResult.Err
        )
    }

    @Test
    fun `C - a genre index judges as a feed not a catalogue`() = runBlocking {
        val home = """
            <html><body><div class="genres">
              <div class="g"><a href="/genre/action">Action</a></div>
              <div class="g"><a href="/genre/comedy">Comedy</a></div>
              <div class="g"><a href="/genre/drama">Drama</a></div>
            </div></body></html>
        """.trimIndent()
        val result = prober(driveTransport(home, prosePage("Chapter 1")))
            .testDrive(driveConfig("div.g"), "https://drive.example/novel/invented")
        assertTrue(
            "genre links must fail the list-shape gate, got $result",
            result is AppResult.Err
        )
    }

    @Test
    fun `J - an image chapter passes the drive as an image chapter`() = runBlocking {
        val images = (1..8).joinToString("\n") { n ->
            """<img src="/pages/p$n.jpg"/>"""
        }
        val chapter = """
            <html><head><title>Chapter 1</title></head><body>
            <div class="content"><p>Start</p>$images<p>End</p></div>
            </body></html>
        """.trimIndent()
        val home = "<html><body><p>home</p></body></html>"
        val result = prober(driveTransport(home, chapter))
            .testDrive(driveConfig(null), "https://drive.example/novel/invented")
        assertTrue(
            "an image-dominant chapter must pass the drive, got $result",
            result is AppResult.Ok
        )
    }

    // ── D(i): pattern completion through the probe ──────────────

    @Test
    fun `D - a windowed strict pattern list completes to the implied total`() = runBlocking {
        val base = "https://window.example"
        val bookUrl = "$base/novel/invented-tale"
        val bookPage = """
            <html><head><title>Invented Window Tale</title>
            <meta property="og:title" content="Invented Window Tale"/></head><body>
            <h1>Invented Window Tale</h1>
            <div class="latest"><a href="/novel/invented-tale/chapter-9">Chapter 9</a></div>
            <ul class="chapters">
              <li><a href="/novel/invented-tale/chapter-1">Chapter 1</a></li>
              <li><a href="/novel/invented-tale/chapter-2">Chapter 2</a></li>
              <li><a href="/novel/invented-tale/chapter-3">Chapter 3</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val pages = mutableMapOf(bookUrl to bookPage)
        for (n in 1..9) {
            pages["$bookUrl/chapter-$n"] = prosePage("Chapter $n")
        }
        // v1.2.3: the catalogue gate needs a book list on the
        // site — the home page carries the /novel/ shelf.
        pages[base] = """
            <html><body><div class="shelf">
              <div class="card"><a href="/novel/invented-tale">Invented Window Tale</a></div>
              <div class="card"><a href="/novel/second-tale">Second Tale</a></div>
              <div class="card"><a href="/novel/third-tale">Third Tale</a></div>
              <div class="card"><a href="/novel/fourth-tale">Fourth Tale</a></div>
            </div></body></html>
        """.trimIndent()
        val outcome = prober(transportOf(pages)).probe(bookUrl)
        assertTrue("expected Ready, got $outcome", outcome is LinkProber.Outcome.Ready)
        val drive = (outcome as LinkProber.Outcome.Ready).draft.testDrive
        assertEquals(
            "the visible window of 3 completes to the implied 9",
            9, drive.chapterCount
        )
    }

    // ── D(i): Madara family candidate ───────────────────────────

    @Test
    fun `D - a Madara page inference cannot read is driven through its family shape`() = runBlocking {
        val base = "https://madara.example"
        val bookUrl = "$base/novel/invented-tale/"
        val bookPage = """
            <html><head><title>Invented Madara Tale</title>
            <meta name="generator" content="Madara"/></head>
            <body class="wp-manga"><div class="wp-manga">
            <div class="post-title"><h1>Invented Madara Tale</h1></div>
            <div class="summary__content"><p>An invented tale of quiet valleys.</p></div>
            <ul class="wp-manga-chapter-list">
              <li class="wp-manga-chapter"><a href="/novel/invented-tale/part-three/">The End</a></li>
              <li class="wp-manga-chapter"><a href="/novel/invented-tale/part-two/">The Middle</a></li>
              <li class="wp-manga-chapter"><a href="/novel/invented-tale/part-one/">The Beginning</a></li>
            </ul>
            </div></body></html>
        """.trimIndent()
        val chapterPage = """
            <html><head><title>Part</title></head><body>
            <div class="reading-content">
              ${prose.split("\n").joinToString("\n") { "<p>$it</p>" }}
            </div>
            </body></html>
        """.trimIndent()
        val listing = """
            <html><body>
            <div class="page-item-detail"><div class="post-title"><a href="$bookUrl">Invented Madara Tale</a></div></div>
            </body></html>
        """.trimIndent()
        val pages = mapOf(
            bookUrl to bookPage,
            "$base/novel/invented-tale/part-one/" to chapterPage,
            "$base/novel/invented-tale/part-two/" to chapterPage,
            "$base/novel/invented-tale/part-three/" to chapterPage,
            "$base/page/1/?s=&post_type=wp-manga" to listing
        )
        val outcome = prober(transportOf(pages)).probe(bookUrl)
        assertTrue("expected Ready via the family candidate, got $outcome",
            outcome is LinkProber.Outcome.Ready)
        val drive = (outcome as LinkProber.Outcome.Ready).draft.testDrive
        assertEquals(3, drive.chapterCount)
    }

    // ── D(ii): the all-chapters hop ─────────────────────────────

    @Test
    fun `D - a book page with only an all chapters link is read through the hop`() = runBlocking {
        val base = "https://hop.example"
        val bookUrl = "$base/novel/invented-saga"
        val bookPage = """
            <html><head><title>Invented Saga</title>
            <meta property="og:title" content="Invented Saga"/></head><body>
            <h1>Invented Saga</h1>
            <p class="synopsis">A short invented synopsis of a saga in a quiet valley.</p>
            <a class="all" href="/novel/invented-saga/chapters">All Chapters</a>
            </body></html>
        """.trimIndent()
        val chaptersPage = """
            <html><head><title>Invented Saga - Chapters</title></head><body>
            <h1>Invented Saga</h1>
            <ul class="chapters">
              <li><a href="/novel/invented-saga/chapter-1">Chapter 1</a></li>
              <li><a href="/novel/invented-saga/chapter-2">Chapter 2</a></li>
              <li><a href="/novel/invented-saga/chapter-3">Chapter 3</a></li>
            </ul>
            </body></html>
        """.trimIndent()
        val pages = mutableMapOf(
            bookUrl to bookPage,
            "$bookUrl/chapters" to chaptersPage
        )
        // v1.2.3: the catalogue gate needs a book list on the
        // site — the home page carries the /novel/ shelf.
        pages[base] = """
            <html><body><div class="shelf">
              <div class="card"><a href="/novel/invented-saga">Invented Saga</a></div>
              <div class="card"><a href="/novel/second-saga">Second Saga</a></div>
              <div class="card"><a href="/novel/third-saga">Third Saga</a></div>
            </div></body></html>
        """.trimIndent()
        for (n in 1..3) pages["$bookUrl/chapter-$n"] = prosePage("Chapter $n")
        val outcome = prober(transportOf(pages)).probe(bookUrl)
        assertTrue("expected Ready via the chapters hop, got $outcome",
            outcome is LinkProber.Outcome.Ready)
        val drive = (outcome as LinkProber.Outcome.Ready).draft.testDrive
        assertEquals(3, drive.chapterCount)
    }

    // ── B + H: table book page probes as a book, not a chapter ──

    @Test
    fun `H - a table listed book page with a long synopsis probes as a book`() = runBlocking {
        val base = "https://order.example"
        val bookUrl = "$base/fiction/9/invented"
        val rows = (1..4).joinToString("\n") { n ->
            """<tr><td><a href="/fiction/9/invented/chapter/100$n/part-$n">Chapter $n: Part $n</a></td><td>Jan $n</td></tr>"""
        }
        val synopsis = "An invented synopsis long enough to look like prose. ".repeat(10)
        val bookPage = """
            <html><head><title>Invented Order Tale</title>
            <meta property="og:title" content="Invented Order Tale"/></head><body>
            <h1>Invented Order Tale</h1>
            <div class="synopsis"><p>$synopsis</p></div>
            <table class="toc"><tbody>$rows</tbody></table>
            </body></html>
        """.trimIndent()
        val pages = mutableMapOf(bookUrl to bookPage)
        for (n in 1..4) {
            pages["$bookUrl/chapter/100$n/part-$n"] = prosePage("Chapter $n")
        }
        // v1.2.3: the catalogue gate needs a book list on the
        // site — the home page carries the /fiction/9/ shelf.
        pages[base] = """
            <html><body><div class="shelf">
              <div class="card"><a href="/fiction/9/invented">Invented Order Tale</a></div>
              <div class="card"><a href="/fiction/9/second">Second Order Tale</a></div>
              <div class="card"><a href="/fiction/9/third">Third Order Tale</a></div>
              <div class="card"><a href="/fiction/9/fourth">Fourth Order Tale</a></div>
            </div></body></html>
        """.trimIndent()
        val outcome = prober(transportOf(pages)).probe(bookUrl)
        assertTrue("expected Ready, got $outcome", outcome is LinkProber.Outcome.Ready)
    }

    @Test
    fun `H - an unplaceable book shaped page does not claim to be a chapter`() = runBlocking {
        val url = "https://order.example/novel/invented-book"
        val synopsis = "An invented synopsis long enough to look like prose. ".repeat(10)
        val page = """
            <html><head><title>Invented Book</title></head><body>
            <nav><a href="/">Home</a></nav>
            <h1>Invented Book</h1>
            <div class="synopsis"><p>$synopsis</p></div>
            </body></html>
        """.trimIndent()
        val outcome = prober(transportOf(mapOf(url to page))).probe(url)
        assertTrue(outcome is LinkProber.Outcome.Failed)
        val message = (outcome as LinkProber.Outcome.Failed).message
        assertFalse(
            "a book-shaped page must not be reported as a chapter: $message",
            message.contains("opened a chapter")
        )
        assertTrue(message.contains("couldn't find a chapter list"))
    }

    @Test
    fun `H - a real chapter link keeps the chapter wording`() = runBlocking {
        val url = "https://order.example/novel/invented-book/chapter-1"
        val page = """
            <html><head><title>Chapter 1</title></head><body>
            <nav><a href="/">Home</a></nav>
            <h1>Chapter 1</h1>
            <div class="content">
              ${prose.split("\n").joinToString("\n") { "<p>$it</p>" }}
            </div>
            </body></html>
        """.trimIndent()
        val outcome = prober(transportOf(mapOf(url to page))).probe(url)
        assertTrue(outcome is LinkProber.Outcome.Failed)
        val message = (outcome as LinkProber.Outcome.Failed).message
        assertTrue(
            "a chapter-shaped link keeps its precise copy: $message",
            message.contains("opened a chapter")
        )
    }

    @Suppress("unused")
    private fun unused(chapter: SourceChapter) = chapter
}
