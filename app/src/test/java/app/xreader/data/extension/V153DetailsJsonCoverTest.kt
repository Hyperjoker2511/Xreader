package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.3 (finding #3, item 9 mechanism): the DETAILS cover of
 * a JSON-endpoint source. The heal-never class (seven bundled
 * sources) takes its details from a bare API answer — no DOM,
 * no meta tags — so the DOM/meta cover chain could never heal
 * a cover for them. When the details document IS a JSON
 * payload, the declared [DetailsSpec.jsonCover] dot path
 * resolves over the body root, the optional template expands
 * the raw value (the FenrirRealm storage-path shape), and the
 * result absolutises exactly as the JSON listing road does.
 */
class V153DetailsJsonCoverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun source(
        config: DeclarativeConfig,
        transport: FakeTransport,
        host: String
    ): DeclarativeSource = DeclarativeSource(
        config,
        testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf(host))
        )
    )

    @Test
    fun `fenrir shape storage path expands through the template`() {
        val base = "https://fenrir.example"
        val config = DeclarativeConfig(
            id = "v153-fenrir",
            name = "Fenrir Fixture",
            baseUrl = base,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                ),
                jsonCover = "cover_url",
                jsonCoverTemplate = "$base/{value}"
            ),
            chapter = ChapterSpec(content = "div")
        )
        val transport = FakeTransport { request ->
            if (request.url == "$base/api/series/dullard") {
                FakeTransport.ok(
                    """{"slug":"dullard","title":"The Dullard",""" +
                        """"cover_url":"storage/covers/dullard.jpg"}"""
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val details = source(config, transport, "fenrir.example").details(
            SourceBook(id = "$base/api/series/dullard", title = "The Dullard")
        )
        assertEquals(
            "$base/storage/covers/dullard.jpg", details.coverUrl
        )
    }

    @Test
    fun `base relative json cover absolutises without a template`() {
        val base = "https://na.example"
        val config = DeclarativeConfig(
            id = "v153-na",
            name = "Archive Fixture",
            baseUrl = base,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                ),
                jsonCover = "cover_url"
            ),
            chapter = ChapterSpec(content = "div")
        )
        val transport = FakeTransport { request ->
            if (request.url == "$base/api/novels/abc") {
                FakeTransport.ok(
                    """{"title":"Archived Tale",""" +
                        """"cover_url":"/covers/archive.jpg"}"""
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val details = source(config, transport, "na.example").details(
            SourceBook(id = "$base/api/novels/abc", title = "Archived Tale")
        )
        assertEquals("$base/covers/archive.jpg", details.coverUrl)
    }

    @Test
    fun `absolute json cover passes through unchanged`() {
        val base = "https://na.example"
        val config = DeclarativeConfig(
            id = "v153-na-abs",
            name = "Archive Fixture Abs",
            baseUrl = base,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                ),
                jsonCover = "cover_url"
            ),
            chapter = ChapterSpec(content = "div")
        )
        val transport = FakeTransport { request ->
            if (request.url == "$base/api/novels/xyz") {
                FakeTransport.ok(
                    """{"title":"Cdn Tale",""" +
                        """"cover_url":"https://cdn.na.example/x.jpg"}"""
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val details = source(config, transport, "na.example").details(
            SourceBook(id = "$base/api/novels/xyz", title = "Cdn Tale")
        )
        assertEquals("https://cdn.na.example/x.jpg", details.coverUrl)
    }

    @Test
    fun `html details page never consults the json cover`() {
        // Precedence guard: an HTML book page keeps the DOM →
        // meta → incoming chain exactly as before; a stray
        // JSON-looking text on the page must not bind.
        val base = "https://plain.example"
        val config = DeclarativeConfig(
            id = "v153-plain",
            name = "Plain Fixture",
            baseUrl = base,
            details = DetailsSpec(
                chapters = ChapterListSpec(
                    list = "a", name = "@text", link = "@href"
                ),
                jsonCover = "cover_url"
            ),
            chapter = ChapterSpec(content = "div")
        )
        val page = """
            <html><head>
              <meta property="og:image" content="$base/covers/meta.jpg"/>
            </head><body><h1>Plain Tale</h1></body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            if (request.url == "$base/book/plain") {
                FakeTransport.ok(page)
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val details = source(config, transport, "plain.example").details(
            SourceBook(id = "$base/book/plain", title = "Plain Tale")
        )
        assertEquals("$base/covers/meta.jpg", details.coverUrl)
    }
}
