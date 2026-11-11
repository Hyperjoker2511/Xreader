package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (D3): where D2 rule 1 lets the item-85 DOM
 * fallback survive — the DOM spec IS a declared, SCOPED
 * road — the substitution LOGS itself: one DiagnosticLog
 * line naming the declared endpoint road, the substituted
 * row count and the source page, written inside the
 * fallback branch. The walk's provenance reads FALLBACK so
 * the sync seam never stamps it proven-complete.
 */
class V158FallbackLoggingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://api.example"
    private val bookUrl = "$base/series/my-book"
    private val endpointUrl = "$base/series/my-book/chapters?loaded=0"

    @Before
    fun setUp() {
        DiagnosticLog.clear()
    }

    private fun scopedConfig() = DeclarativeConfig(
        id = "v158-fallback-log",
        name = "Scoped Fallback",
        baseUrl = base,
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "div.chapters a",
                name = "@text",
                link = "@href",
                pagination = ChapterListPagination(
                    urlTemplate = "$base/series/{bookSlug}/chapters?loaded=0",
                    bookIdPattern = "",
                    jsonArrayPath = "chapters",
                    pageSize = 100
                ),
                jsonNameField = "name",
                jsonLinkField = "slug",
                jsonLinkTemplate = "$base/series/{bookSlug}/{value}"
            )
        ),
        chapter = ChapterSpec(content = "div.content")
    )

    @Test
    fun `scoped fallback substitutes, logs once, and carries FALLBACK provenance`() {
        val bookPage = """
            <html><body>
            <div class="chapters">
              <a href="/series/my-book/chapter-1">Chapter 1</a>
              <a href="/series/my-book/chapter-2">Chapter 2</a>
            </div>
            </body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            when (request.url) {
                bookUrl -> FakeTransport.ok(bookPage)
                endpointUrl -> FakeTransport.ok("{\"chapters\": []}")
                else -> AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val source = DeclarativeSource(
            scopedConfig(),
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("api.example"))
            )
        )
        val rows = source.chapters(
            SourceBook(id = bookUrl, title = "My Book")
        )
        assertEquals(
            setOf(
                "$base/series/my-book/chapter-1",
                "$base/series/my-book/chapter-2"
            ),
            rows.map { it.id }.toSet()
        )
        assertEquals(
            ChapterSuppliedBy.FALLBACK, source.lastWalkSuppliedBy
        )
        val fallbackLines = DiagnosticLog.entries()
            .filter { it.tag == "chapter-fallback" }
        assertEquals(
            "exactly one fallback log line, got $fallbackLines",
            1, fallbackLines.size
        )
        val line = fallbackLines.single().message
        assertTrue(
            "the line names the declared road: $line",
            line.contains("jsonArrayPath=chapters")
        )
        assertTrue(
            "the line names the substituted count: $line",
            line.contains("2 rows")
        )
        assertTrue(
            "the line names the source page: $line",
            line.contains(bookUrl)
        )
    }
}
