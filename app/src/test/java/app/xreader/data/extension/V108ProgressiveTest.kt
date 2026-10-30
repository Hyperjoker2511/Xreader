package app.xreader.data.extension

import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.8: progressive chapter loading. A paginated book opens
 * after its first chunk and the list grows only on demand —
 * the walk is resumable, a failed page is never mistaken for
 * the end of the book, and the stored reading list is always
 * a true prefix (chapter 1 first, positions never move).
 *
 * The fixture is novelight-shaped: 12 chapters, site page
 * size 5, the book page carrying the newest slice plus a
 * pagination select declaring 3 pages; the endpoint's page 1
 * is the book page's own slice (the walk starts at page 2
 * ascending, or descends from the last page when reversed).
 */
class V108ProgressiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    object ProgSite {
        const val HOST = "prog.example"
        const val BASE = "https://prog.example"
        const val REV_URL = "$BASE/book/prog"
        const val ASC_URL = "$BASE/book/prog-asc"

        private fun row(n: Int, book: String) =
            "<a class='chapter' href='/book/$book/chapter-$n'>" +
                "<span class='title'>Chapter $n</span></a>"

        private fun rows(book: String, ns: List<Int>) =
            ns.joinToString("") { row(it, book) }

        private fun json(html: String) = "{\"html\": \"$html\"}"

        fun pages(failPage2: Boolean = false): Map<String, String> {
            val revBook = """
                <html><head>
                  <title>Prog Tale</title>
                  <meta name="description" content="A progressive tale."/>
                </head><body>
                <h1>Prog Tale</h1>
                <script>var BOOK_ID = "77";</script>
                <select id="select-pagination-chapter">
                  <option value="1">1</option>
                  <option value="2">2</option>
                  <option value="3">3</option>
                </select>
                <div class="chapters">${rows("prog", listOf(12, 11, 10, 9, 8))}</div>
                </body></html>
            """.trimIndent()
            val ascBook = """
                <html><head>
                  <title>Prog Asc</title>
                  <meta name="description" content="An ascending tale."/>
                </head><body>
                <h1>Prog Asc</h1>
                <script>var BOOK_ID = "78";</script>
                <div class="chapters">${rows("prog-asc", listOf(1, 2, 3, 4, 5))}</div>
                </body></html>
            """.trimIndent()
            return mapOf(
                REV_URL to revBook,
                ASC_URL to ascBook,
                "$BASE/ajax/pages?book_id=77&page=2" to
                    json(rows("prog", listOf(7, 6, 5, 4, 3))),
                "$BASE/ajax/pages?book_id=77&page=3" to
                    json(rows("prog", listOf(2, 1))),
                "$BASE/ajax/pages?book_id=78&page=2" to
                    json(rows("prog-asc", listOf(6, 7, 8, 9, 10))),
                "$BASE/ajax/pages?book_id=78&page=3" to
                    json(rows("prog-asc", listOf(11, 12)))
            ).let { base ->
                if (!failPage2) {
                    base
                } else {
                    base + ("$BASE/ajax/pages?book_id=77&page=2" to "boom")
                }
            }
        }

        fun transport(failPage2: Boolean = false): FakeTransport {
            val all = pages(failPage2)
            return FakeTransport { request ->
                val body = all[request.url]
                when {
                    failPage2 &&
                        request.url == "$BASE/ajax/pages?book_id=77&page=2" ->
                        FakeTransport.response(500, "boom")
                    body == null -> FakeTransport.response(404, "not found")
                    else -> FakeTransport.ok(body)
                }
            }
        }

        fun config(
            reverse: Boolean,
            totalPagesSelector: String?
        ): DeclarativeConfig {
            val list = ListSpec(
                url = "$BASE/popular",
                results = "div.result",
                title = "a.t",
                link = "a.t@href"
            )
            return DeclarativeConfig(
                id = "prog",
                name = "Prog",
                lang = "en",
                baseUrl = BASE,
                search = list,
                popular = list,
                latest = null,
                details = DetailsSpec(
                    title = "h1",
                    author = null,
                    description = "meta[name=description]@content",
                    cover = null,
                    chapters = ChapterListSpec(
                        list = ".chapters a.chapter",
                        name = ".title@text",
                        link = "@href",
                        reverse = reverse,
                        pagination = ChapterListPagination(
                            urlTemplate =
                                "$BASE/ajax/pages?book_id={bookId}&page={page}",
                            bookIdPattern = "BOOK_ID\\s*=\\s*\"(\\d+)\"",
                            jsonHtmlField = "html",
                            totalPagesSelector = totalPagesSelector
                        )
                    )
                ),
                chapter = ChapterSpec(
                    content = "div.content",
                    title = null,
                    nextPage = null
                )
            )
        }
    }

    private fun source(
        transport: FakeTransport,
        reverse: Boolean,
        totalPagesSelector: String?
    ): DeclarativeSource {
        val context = testContext(
            transport,
            tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf(ProgSite.HOST))
        )
        return DeclarativeSource(
            ProgSite.config(reverse, totalPagesSelector), context
        )
    }

    private fun have(book: String, ns: List<Int>): List<SourceChapter> =
        ns.map {
            SourceChapter(
                id = "${ProgSite.BASE}/book/$book/chapter-$it",
                name = "Chapter $it",
                number = it.toFloat()
            )
        }

    @Test
    fun `reversed first chunk starts at reading chapter one`() = runBlocking {
        val source = source(ProgSite.transport(), true, "#select-pagination-chapter")
        val chunk = source.chaptersFirst(
            SourceBook(id = ProgSite.REV_URL, title = "Prog Tale"), 2
        )
        // The last site page holds the oldest chapters: the
        // reading prefix [1, 2] — not the newest slice.
        assertEquals(listOf(1f, 2f), chunk.chapters.map { it.number })
        assertFalse(chunk.complete)
        assertEquals(2, chunk.walk.nextPage)
        assertEquals(3, chunk.walk.totalPages)
        assertEquals("Prog Tale", chunk.details?.title)
    }

    @Test
    fun `reversed walk grows the prefix then completes with the book page slice`() =
        runBlocking {
            val source = source(
                ProgSite.transport(), true, "#select-pagination-chapter"
            )
            val book = SourceBook(id = ProgSite.REV_URL, title = "Prog Tale")
            val first = source.chaptersFirst(book, 2)
            val grown = source.chaptersMore(
                book, have("prog", listOf(1, 2)), 7, first.walk
            )
            assertEquals((1..7).map { it.toFloat() }, grown.chapters.map { it.number })
            assertFalse(grown.complete)
            assertEquals(1, grown.walk.nextPage)
            val done = source.chaptersMore(
                book, have("prog", (1..7).toList()), 12, grown.walk
            )
            assertEquals(
                (1..12).map { it.toFloat() }, done.chapters.map { it.number }
            )
            assertTrue(done.complete)
            assertEquals(0, done.walk.nextPage)
            // No duplicates anywhere in the completed list.
            assertEquals(12, done.chapters.map { it.id }.toSet().size)
        }

    @Test
    fun `reversed first chunk of ten completes a small book`() = runBlocking {
        val source = source(ProgSite.transport(), true, "#select-pagination-chapter")
        val chunk = source.chaptersFirst(
            SourceBook(id = ProgSite.REV_URL, title = "Prog Tale"), 10
        )
        assertEquals(12, chunk.chapters.size)
        assertEquals(1f, chunk.chapters.first().number)
        assertTrue(chunk.complete)
    }

    @Test
    fun `a failed page keeps the cursor and the chapters`() = runBlocking {
        val source = source(
            ProgSite.transport(failPage2 = true), true, "#select-pagination-chapter"
        )
        val book = SourceBook(id = ProgSite.REV_URL, title = "Prog Tale")
        val first = source.chaptersFirst(book, 2)
        val after = source.chaptersMore(
            book, have("prog", listOf(1, 2)), 7, first.walk
        )
        assertEquals(listOf(1f, 2f), after.chapters.map { it.number })
        assertFalse(after.complete)
        assertEquals(2, after.walk.nextPage)
    }

    @Test
    fun `ascending first chunk is the book slice plus one page`() = runBlocking {
        val source = source(ProgSite.transport(), false, null)
        val chunk = source.chaptersFirst(
            SourceBook(id = ProgSite.ASC_URL, title = "Prog Asc"), 10
        )
        assertEquals((1..10).map { it.toFloat() }, chunk.chapters.map { it.number })
        assertFalse(chunk.complete)
        assertEquals(3, chunk.walk.nextPage)
    }

    @Test
    fun `ascending walk completes on the short last page`() = runBlocking {
        val source = source(ProgSite.transport(), false, null)
        val book = SourceBook(id = ProgSite.ASC_URL, title = "Prog Asc")
        val first = source.chaptersFirst(book, 10)
        val done = source.chaptersMore(
            book, have("prog-asc", (1..10).toList()), 12, first.walk
        )
        assertEquals(
            (1..12).map { it.toFloat() }, done.chapters.map { it.number }
        )
        assertTrue(done.complete)
    }

    @Test
    fun `progressive support matrix`() {
        val transport = ProgSite.transport()
        assertTrue(
            source(transport, true, "#select-pagination-chapter")
                .supportsProgressiveChapters()
        )
        assertTrue(
            source(transport, false, null).supportsProgressiveChapters()
        )
        // A reversed list without a page-count selector cannot
        // start from the last page — it stays complete-on-open.
        assertFalse(
            source(transport, true, null).supportsProgressiveChapters()
        )
    }
}
