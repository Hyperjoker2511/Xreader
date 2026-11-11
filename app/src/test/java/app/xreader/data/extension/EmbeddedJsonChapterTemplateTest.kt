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
 * v1.5.2 (FIX-16, engine fix B): the embedded-JSON chapter
 * road honours the spec's jsonLinkTemplate — the listing
 * road and the direct-endpoint road always did, and this
 * road silently absolutised the raw link field against the
 * book URL instead, minting phantom rows (…/novel/1,
 * …/novel/2, …) beside the real chapters whenever the link
 * field was a bare number (the WuxiaClick shape; WoopRead's
 * slug-valued field survived only by URL geometry).
 *
 * The fixture is a Next.js-shaped book page: the DOM window
 * carries chapters 1–2, the embedded payload's ToC carries
 * 1–3. With the template applied, the payload rows land on
 * the SAME URLs as the DOM rows (dedupe keeps one entry per
 * URL) and the payload-only third chapter extends the list.
 */
class EmbeddedJsonChapterTemplateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookPage = """
        <html><head></head><body>
        <ol>
          <li><a href="/chapter/book-slug-1">Chapter 1</a></li>
          <li><a href="/chapter/book-slug-2">Chapter 2</a></li>
        </ol>
        <script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"toc":{"items":[
          {"number":1,"title":"Chapter 1 - Alpha"},
          {"number":2,"title":"Chapter 2 - Beta"},
          {"number":3,"title":"Chapter 3 - Gamma"}
        ]}}}}
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `embedded json chapter rows honour the link template`() {
        val listing = ListSpec(
            url = "https://nextjs.example/",
            results = "a", title = "@text", link = "@href"
        )
        val config = DeclarativeConfig(
            id = "test-nextjs",
            name = "NextJs",
            baseUrl = "https://nextjs.example",
            search = listing,
            popular = listing,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "li a", name = "@text", link = "@href",
                    reverse = false,
                    jsonPath = "props.pageProps.toc.items",
                    jsonNameField = "title",
                    jsonLinkField = "number",
                    jsonLinkTemplate =
                        "https://nextjs.example/chapter/{bookSlug}-{value}"
                )
            ),
            chapter = ChapterSpec(content = "div")
        )
        val transport = FakeTransport { request ->
            if (request.url == "https://nextjs.example/novel/book-slug") {
                AppResult.Ok(FakeTransport.response(200, bookPage))
            } else {
                AppResult.Ok(FakeTransport.response(404, "no recorded shape"))
            }
        }
        val context = testContext(
            transport = transport,
            filesDir = tmp.newFolder(),
            permissions = ExtensionPermissions(
                domains = listOf("nextjs.example")
            )
        )
        val source = DeclarativeSource(config, context)
        val chapters = source.chapters(
            SourceBook(
                id = "https://nextjs.example/novel/book-slug",
                title = "Book"
            )
        )
        assertEquals(
            "one entry per chapter URL, payload-extended: " +
                chapters.map { it.id },
            3, chapters.size
        )
        assertEquals(
            "https://nextjs.example/chapter/book-slug-1",
            chapters[0].id
        )
        assertEquals(
            "https://nextjs.example/chapter/book-slug-3",
            chapters[2].id
        )
        assertEquals("Chapter 3 - Gamma", chapters[2].name)
        assertTrue(
            "no phantom rows from relative resolution: " +
                chapters.map { it.id },
            chapters.none { it.id.contains("/novel/") }
        )
    }
}
