package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (F1 + F2): chapter-list pagination — the engine
 * walks a book's paging endpoint until the list is complete —
 * and catalogue paging through the {page} substitution.
 *
 * The headline test runs over the REAL novelight capture
 * ([NovelightCaptureSite]): the book page's own 50 chapters,
 * the endpoint's real pages 2 and 16, and synthesised middle
 * pages in the same markup. The live book's numbering reaches
 * 760 with one number missing, so the complete list is 759 —
 * the test pins the real arithmetic.
 */
class V1062ChapterListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── F1: the full walk over the real capture ────────────────

    private fun synthPageJson(numbers: List<Int>): String {
        val rows = numbers.joinToString("\n") { n ->
            "<a href=\"/book/chapter/${700_000 + n}\" class=\"chapter \">" +
                "<div class=\"title\">$n chapter </div>" +
                "<div class=\"chapter-info\">" +
                "<span class=\"author\">KimSU</span>" +
                "</div></a>"
        }
        val escaped = rows
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"html": "$escaped"}"""
    }

    private fun captureTransport(
        requestedPages: MutableList<Int>
    ): FakeTransport {
        val prefix = "${NovelightCaptureSite.BASE}/book/ajax/" +
            "chapter-pagination?book_id=402&page="
        return FakeTransport { request ->
            val url = request.url
            when {
                url == NovelightCaptureSite.BOOK_URL ->
                    FakeTransport.ok(NovelightCaptureSite.BOOK_PAGE)
                url.startsWith(prefix) -> {
                    val page = url.removePrefix(prefix).toInt()
                    requestedPages += page
                    when (page) {
                        2 -> FakeTransport.ok(
                            NovelightCaptureSite.PAGE_2_RESPONSE
                        )
                        in 3..15 -> {
                            val top = 660 - (page - 3) * 50
                            FakeTransport.ok(
                                synthPageJson((0..49).map { top - it })
                            )
                        }
                        16 -> FakeTransport.ok(
                            NovelightCaptureSite.PAGE_16_RESPONSE
                        )
                        else -> AppResult.Ok(
                            FakeTransport.response(404, "no such page")
                        )
                    }
                }
                else -> AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
    }

    @Test
    fun `the walk assembles the complete chapter list from the real capture`() {
        val requestedPages = mutableListOf<Int>()
        val entry = KnownSites.entryFor(NovelightCaptureSite.HOST)!!
        val config = KnownSites.fullConfig(
            entry, "link-capture", "Novelight", NovelightCaptureSite.BASE
        )!!
        val context = testContext(
            captureTransport(requestedPages), tmp.newFolder(),
            ExtensionPermissions(domains = listOf(NovelightCaptureSite.HOST))
        )
        val source = DeclarativeSource(config, context)
        val chapters = source.chapters(
            SourceBook(id = NovelightCaptureSite.BOOK_URL, title = "")
        )
        // 50 (book page) + 50 (page 2) + 13 × 50 (pages 3–15)
        // + 9 (the short last page) = 759 unique chapters.
        assertEquals(759, chapters.size)
        assertEquals(759, chapters.map { it.id }.distinct().size)
        // Reading order: chapter 1 first, chapter 760 last.
        assertTrue(chapters.first().id.endsWith("/178682"))
        assertTrue(chapters.first().name.contains("1 chapter"))
        assertTrue(chapters.last().name.contains("760 chapter"))
        // The walk fetched pages 2..16 and stopped at the short
        // page — page 17 was never asked for.
        assertEquals((2..16).toList(), requestedPages)
    }

    // ── F1: the walk's stop conditions (synthetic mini-site) ──

    private fun miniConfig(): DeclarativeConfig = DeclarativeConfig(
        id = "mini",
        name = "Mini",
        baseUrl = MINI_BASE,
        details = DetailsSpec(
            title = "h1",
            chapters = ChapterListSpec(
                list = "ul.chapters > li",
                name = "a",
                link = "a@href",
                reverse = true,
                pagination = ChapterListPagination(
                    urlTemplate = "$MINI_BASE/list?book={bookId}&page={page}",
                    bookIdPattern = "BOOK_ID = \"(\\d+)\"",
                    jsonHtmlField = null
                )
            )
        ),
        chapter = ChapterSpec(content = "div.content")
    )

    private fun miniBookPage(withId: Boolean, rows: String): String = """
        <html><head><title>Mini Book</title></head><body>
        <h1>Mini Book</h1>
        ${if (withId) "<script>const BOOK_ID = \"7\"</script>" else ""}
        <ul class="chapters">$rows</ul>
        </body></html>
    """.trimIndent()

    private fun row(n: Int): String =
        "<li><a href=\"/c/$n\">Chapter $n</a></li>"

    private fun fragment(rows: String): String = "<ul>$rows</ul>"

    private fun miniSource(
        bookPage: String,
        pages: Map<Int, AppResult<app.xreader.data.net.HttpTransport.Response>>,
        requestedPages: MutableList<Int>
    ): DeclarativeSource {
        val transport = FakeTransport { request ->
            val url = request.url
            when {
                url == "$MINI_BASE/book" -> FakeTransport.ok(bookPage)
                url.startsWith("$MINI_BASE/list?") -> {
                    val page = url.substringAfter("page=").toInt()
                    requestedPages += page
                    pages[page]
                        ?: AppResult.Ok(FakeTransport.response(404, "none"))
                }
                else -> AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("mini.example"))
        )
        return DeclarativeSource(miniConfig(), context)
    }

    @Test
    fun `the walk stops when a page repeats rows already seen`() {
        val requested = mutableListOf<Int>()
        val source = miniSource(
            bookPage = miniBookPage(withId = true, rows = row(3) + row(2)),
            pages = mapOf(
                2 to FakeTransport.ok(fragment(row(3) + row(2)))
            ),
            requestedPages = requested
        )
        val chapters = source.chapters(
            SourceBook(id = "$MINI_BASE/book", title = "")
        )
        assertEquals(2, chapters.size)
        assertEquals(listOf(2), requested)
    }

    @Test
    fun `the walk keeps the rows gathered before a page fails`() {
        val requested = mutableListOf<Int>()
        val source = miniSource(
            bookPage = miniBookPage(withId = true, rows = row(3) + row(2)),
            pages = mapOf(
                2 to FakeTransport.ok(fragment(row(1))),
                3 to AppResult.Ok(FakeTransport.response(500, "boom"))
            ),
            requestedPages = requested
        )
        val chapters = source.chapters(
            SourceBook(id = "$MINI_BASE/book", title = "")
        )
        // Chapter 1 arrived on page 2; page 3's failure cost
        // nothing already gathered.
        assertEquals(3, chapters.size)
        assertTrue(chapters.first().id.endsWith("/c/1"))
        assertEquals(listOf(2, 3), requested)
    }

    @Test
    fun `a book page without its id is not walked`() {
        val requested = mutableListOf<Int>()
        val source = miniSource(
            bookPage = miniBookPage(withId = false, rows = row(2) + row(1)),
            pages = emptyMap(),
            requestedPages = requested
        )
        val chapters = source.chapters(
            SourceBook(id = "$MINI_BASE/book", title = "")
        )
        assertEquals(2, chapters.size)
        assertTrue(requested.isEmpty())
    }

    // ── F2: catalogue paging through the engine ────────────────

    @Test
    fun `a paging catalogue serves different books per page`() {
        val paging = FixtureSite.config().popular!!.copy(
            url = "${FixtureSite.BASE}/popular?page={page}"
        )
        val config = FixtureSite.config().copy(popular = paging)
        val pageTwo = """
            <html><head><title>Popular 2</title></head><body>
            <div class="results">
              <div class="result">
                <a class="t" href="/book/third-tale">Third Tale</a>
                <span class="a">Jo Scribe</span>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            when (request.url) {
                "${FixtureSite.BASE}/popular?page=1" ->
                    FakeTransport.ok(FixtureSite.pages()[FixtureSite.BASE + "/popular"]!!)
                "${FixtureSite.BASE}/popular?page=2" ->
                    FakeTransport.ok(pageTwo)
                else -> AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
        val context = testContext(transport, tmp.newFolder())
        val source = DeclarativeSource(config, context)
        val first = source.popular(1)
        val second = source.popular(2)
        assertEquals(
            listOf("Sword Saga", "Other Tale"), first.books.map { it.title }
        )
        assertEquals(listOf("Third Tale"), second.books.map { it.title })
        assertTrue(
            transport.requests.any {
                it.url == "${FixtureSite.BASE}/popular?page=2"
            }
        )
    }

    private companion object {
        const val MINI_BASE = "https://mini.example"
    }
}
