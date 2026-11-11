package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (D2 / F6): the same shipped BrightNovel definition
 * and the same furniture-laden book page as the furniture
 * fixture — but the endpoint answers TWO real chapter
 * records. The harvest must be EXACTLY those two rows
 * (built through the declared link template), with zero
 * DOM leakage: the endpoint road wins outright, and
 * provenance conformance keeps every row under the book's
 * declared chapter subtree.
 */
class V158BrightNovelEndpointWinsFixture {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookUrl = "https://brightnovels.com/series/the-dullard"
    private val endpointUrl =
        "https://brightnovels.com/series/the-dullard/chapters/free" +
            "?loaded=0&sort_order=desc"

    private fun bookPageHtml(): String {
        val dataPage = "{&quot;component&quot;:&quot;Series/Show&quot;," +
            "&quot;props&quot;:{&quot;series&quot;:{&quot;title&quot;:" +
            "&quot;The Dullard&quot;}},&quot;url&quot;:&quot;/series/" +
            "the-dullard&quot;,&quot;version&quot;:&quot;1&quot;}"
        return """
            <html><head><title>The Dullard</title></head><body>
            <div id="app" data-page="$dataPage"></div>
            <nav><a href="$bookUrl/chapter-9">Latest Chapter</a></nav>
            <div class="card">
              <a href="https://brightnovels.com/series/other-book">
                Other Book <span>Nov 4, 2026</span></a>
            </div>
            <a href="https://brightnovels.com/series?genre=Fantasy">Fantasy</a>
            <a href="https://brightnovels.com/authors/mary">Mary</a>
            </body></html>
        """.trimIndent()
    }

    @Test
    fun `endpoint rows are the harvest, with zero dom leakage`() {
        val transport = FakeTransport { request ->
            when (request.url) {
                bookUrl -> FakeTransport.ok(bookPageHtml())
                endpointUrl -> FakeTransport.ok(
                    "{\"chapters\":[" +
                        "{\"name\":\"Chapter 1\",\"slug\":\"chapter-1\"," +
                        "\"updated_at\":\"2026-11-01\"}," +
                        "{\"name\":\"Chapter 2\",\"slug\":\"chapter-2\"," +
                        "\"updated_at\":\"2026-11-02\"}]}"
                )
                else -> AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val config = BundledSingles.brightNovel(
            "bundled-brightnovel", "BrightNovel"
        )
        val source = DeclarativeSource(
            config,
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("brightnovels.com"))
            )
        )
        val rows = source.chapters(
            SourceBook(id = bookUrl, title = "The Dullard")
        )
        assertEquals(
            setOf(
                "https://brightnovels.com/series/the-dullard/chapter-1",
                "https://brightnovels.com/series/the-dullard/chapter-2"
            ),
            rows.map { it.id }.toSet()
        )
        assertEquals(
            ChapterSuppliedBy.ENDPOINT, source.lastWalkSuppliedBy
        )
    }
}
