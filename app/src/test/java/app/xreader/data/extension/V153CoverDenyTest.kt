package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.3 (finding #3/#4, item 17 mechanism): declared
 * NON-covers. A site's own no-cover filler (the LNT case: a
 * logo banner every coverless series shares) must never bind
 * as a book's cover — a resolved cover URL containing any of
 * the config's [DeclarativeConfig.coverDenyPatterns] is
 * treated as NO cover at ingestion, on the DOM listing road,
 * the JSON listing road and the details/meta read alike.
 * Plain substring match; non-matching records on the same
 * spec keep their covers.
 */
class V153CoverDenyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://deny.example"
    private val perms =
        ExtensionPermissions(domains = listOf("deny.example"))

    private fun source(
        config: DeclarativeConfig,
        transport: FakeTransport
    ): DeclarativeSource = DeclarativeSource(
        config, testContext(transport, tmp.newFolder(), perms)
    )

    @Test
    fun `dom listing drops the denied cover and keeps the real one`() {
        val config = DeclarativeConfig(
            id = "v153-deny-dom",
            name = "Deny DOM",
            baseUrl = base,
            popular = ListSpec(
                url = "$base/popular",
                results = "div.result",
                title = "a.t",
                link = "a.t@href",
                cover = "img.c@src"
            ),
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "div"),
            coverDenyPatterns = listOf("no-cover-banner")
        )
        val page = """
            <html><body><div class="results">
              <div class="result">
                <a class="t" href="/book/coverless">Coverless Tale</a>
                <img class="c" src="/img/no-cover-banner.jpg"/>
              </div>
              <div class="result">
                <a class="t" href="/book/covered">Covered Tale</a>
                <img class="c" src="/covers/real.jpg"/>
              </div>
            </div></body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            if (request.url == "$base/popular") {
                FakeTransport.ok(page)
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val books = source(config, transport).popular(1).books
        assertEquals(2, books.size)
        assertNull(
            "the site's filler banner must not bind as a cover",
            books[0].coverUrl
        )
        assertEquals("$base/covers/real.jpg", books[1].coverUrl)
    }

    @Test
    fun `json listing drops the denied cover and keeps the real one`() {
        val config = DeclarativeConfig(
            id = "v153-deny-json",
            name = "Deny JSON",
            baseUrl = base,
            popular = ListSpec(
                url = "$base/api/books",
                results = "",
                title = "title",
                link = "slug",
                cover = "cover",
                jsonPath = "data",
                jsonLinkTemplate = "/book/{value}"
            ),
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "div"),
            coverDenyPatterns = listOf("no-cover-banner")
        )
        val transport = FakeTransport { request ->
            if (request.url == "$base/api/books") {
                FakeTransport.ok(
                    """{"data":[
                      {"title":"Coverless","slug":"coverless",
                       "cover":"/img/no-cover-banner.jpg"},
                      {"title":"Covered","slug":"covered",
                       "cover":"/covers/real.jpg"}
                    ]}"""
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val books = source(config, transport).popular(1).books
        assertEquals(2, books.size)
        assertNull(books[0].coverUrl)
        assertEquals("$base/covers/real.jpg", books[1].coverUrl)
    }

    @Test
    fun `details meta cover is denied too`() {
        val config = DeclarativeConfig(
            id = "v153-deny-meta",
            name = "Deny Meta",
            baseUrl = base,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                )
            ),
            chapter = ChapterSpec(content = "div"),
            coverDenyPatterns = listOf("no-cover-banner")
        )
        val page = """
            <html><head>
              <meta property="og:image"
                    content="$base/img/no-cover-banner.jpg"/>
            </head><body><h1>Coverless Tale</h1></body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            if (request.url == "$base/book/coverless") {
                FakeTransport.ok(page)
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val details = source(config, transport).details(
            SourceBook(id = "$base/book/coverless", title = "Coverless Tale")
        )
        assertNull(
            "a denied og:image is no cover, however it arrived",
            details.coverUrl
        )
    }
}
