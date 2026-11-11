package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import app.xreader.extensionapi.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.3 (finding #2, items 3/5/6/7): the BrightNovel shape,
 * proven against DOCUMENTED fixtures — the live site is
 * Cloudflare-walled from this egress, so the shapes here are
 * the ones finding #2 recorded from the owner's signed-in
 * browser session and QuickNovel's wire:
 *
 *  - the catalogue is an Inertia.js shell: the whole page
 *    model rides in `div#app[data-page]`, series records at
 *    `props.seriesList.data[]` as {id, title, slug,
 *    cover{url}} — the DOM carries no catalogue anchors;
 *  - search answers bare JSON: {data:{series:[…]}};
 *  - the book page's payload carries `props.chapters[]` as
 *    {name, slug} — chapter links are BUILT from the slug by
 *    template, they are not URLs in the payload;
 *  - the chapter page's payload carries the text at
 *    `props.chapter.content`.
 *
 * Plus item 6's two cases: a page whose payload no read
 * consumed is a SHAPE-MISS (distinct failure), and a
 * genuinely empty page stays the ordinary empty result.
 */
class V153BrightNovelFixtureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://brightnovels.example"

    private fun inertiaPage(pageJson: String): String {
        val encoded = pageJson
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return """
            <html><head><title>BrightNovels</title></head><body>
            <div id="app" data-page="$encoded"></div>
            </body></html>
        """.trimIndent()
    }

    private val seriesPayload = """
        {"component":"Series/Index","props":{"seriesList":{"data":[
          {"id":1,"title":"The Dullard","slug":"the-dullard",
           "cover":{"url":"/covers/dullard.jpg"}},
          {"id":2,"title":"Second Son","slug":"second-son",
           "cover":{"url":"https://brightnovels.example/covers/second.jpg"}}
        ]}},"url":"/series","version":"v1"}
    """.trimIndent()

    private val bookPayload = """
        {"component":"Series/Show","props":{
          "book":{"title":"The Dullard","slug":"the-dullard"},
          "chapters":[
            {"name":"Chapter 1: Awakening","slug":"chapter-1-awakening"},
            {"name":"Chapter 2: The Road","slug":"chapter-2-the-road"}
          ]},"url":"/series/the-dullard","version":"v1"}
    """.trimIndent()

    private val chapterPayload = """
        {"component":"Chapter/Show","props":{"chapter":{
          "title":"Chapter 1: Awakening",
          "content":"<p>The dullard rose at dawn and took up the wooden sword his father left him.</p><p>He swung it a hundred times before breakfast, as he had every morning for ten years.</p>"
        }},"url":"/series/the-dullard/chapter-1-awakening","version":"v1"}
    """.trimIndent()

    private fun config(
        popularPath: String = "props.seriesList.data"
    ) = DeclarativeConfig(
        id = "v153-brightnovel",
        name = "BrightNovel Fixture",
        baseUrl = base,
        popular = ListSpec(
            url = "$base/series",
            results = "",
            title = "title",
            link = "slug",
            cover = "cover.url",
            jsonPath = popularPath,
            jsonLinkTemplate = "/series/{value}"
        ),
        search = ListSpec(
            url = "$base/api/search?query={query}",
            results = "",
            title = "title",
            link = "slug",
            cover = "cover.url",
            jsonPath = "data.series",
            jsonLinkTemplate = "/series/{value}"
        ),
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "li a",
                name = "@text",
                link = "@href",
                jsonPath = "props.chapters",
                jsonNameField = "name",
                jsonLinkField = "slug",
                jsonLinkTemplate = "/series/{bookSlug}/{value}"
            )
        ),
        chapter = ChapterSpec(content = "props.chapter.content")
    )

    private fun transport(
        seriesPage: String = inertiaPage(seriesPayload)
    ): FakeTransport = FakeTransport { request ->
        when {
            request.url == "$base/series" ->
                FakeTransport.ok(seriesPage)
            request.url.startsWith("$base/api/search") ->
                FakeTransport.ok(
                    """{"data":{"series":[
                      {"title":"The Dullard","slug":"the-dullard",
                       "cover":{"url":"/covers/dullard.jpg"}}
                    ]}}"""
                )
            request.url == "$base/series/the-dullard" ->
                FakeTransport.ok(inertiaPage(bookPayload))
            request.url ==
                "$base/series/the-dullard/chapter-1-awakening" ->
                FakeTransport.ok(inertiaPage(chapterPayload))
            else -> AppResult.Ok(
                FakeTransport.response(404, "no recorded shape")
            )
        }
    }

    private fun source(
        config: DeclarativeConfig = config(),
        transport: FakeTransport = transport()
    ): DeclarativeSource = DeclarativeSource(
        config,
        testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("brightnovels.example"))
        )
    )

    // ── (a) the Inertia catalogue ──────────────────────────

    @Test
    fun `popular rows come from the inertia payload`() {
        val page = source().popular(1)
        assertEquals(2, page.books.size)
        assertEquals("The Dullard", page.books[0].title)
        assertEquals(
            "$base/series/the-dullard", page.books[0].id
        )
        assertEquals(
            "$base/covers/dullard.jpg", page.books[0].coverUrl
        )
        assertEquals("Second Son", page.books[1].title)
        assertEquals(
            "$base/series/second-son", page.books[1].id
        )
        assertEquals(
            "$base/covers/second.jpg", page.books[1].coverUrl
        )
    }

    // ── (b) the bare-JSON search answer ────────────────────

    @Test
    fun `search rows come from the api json body`() {
        val page = source().search("dullard", 1)
        assertEquals(1, page.books.size)
        assertEquals("The Dullard", page.books[0].title)
        assertEquals("$base/series/the-dullard", page.books[0].id)
        assertEquals(
            "$base/covers/dullard.jpg", page.books[0].coverUrl
        )
    }

    // ── (c) chapters built by template from payload slugs ──

    @Test
    fun `chapters are built from payload slugs by template`() {
        val chapters = source().chapters(
            SourceBook(id = "$base/series/the-dullard", title = "The Dullard")
        )
        assertEquals(2, chapters.size)
        assertEquals(
            "$base/series/the-dullard/chapter-1-awakening",
            chapters[0].id
        )
        assertEquals("Chapter 1: Awakening", chapters[0].name)
        assertEquals(
            "$base/series/the-dullard/chapter-2-the-road",
            chapters[1].id
        )
    }

    // ── (d) chapter text from the embedded content path ────

    @Test
    fun `chapter text resolves from the embedded content path`() {
        val content = source().chapterContent(
            SourceChapter(
                id = "$base/series/the-dullard/chapter-1-awakening",
                name = "Chapter 1: Awakening"
            )
        )
        assertTrue(
            "chapter text missing from the read: ${content.html}",
            content.html.contains(
                "The dullard rose at dawn and took up the wooden sword"
            )
        )
        assertTrue(
            content.html.contains(
                "He swung it a hundred times before breakfast"
            )
        )
    }

    // ── item 6: shape-miss vs genuinely empty ──────────────

    @Test
    fun `payload no read consumed is a shape miss`() {
        // The same Inertia page, but the payload's records
        // ride under a key the declared path doesn't name and
        // no envelope key carries — the data is on the page,
        // the app's shape can't reach it.
        val drifted = inertiaPage(
            """
            {"component":"Series/Index","props":{"seriesList":{
              "records":[
                {"id":1,"title":"The Dullard","slug":"the-dullard"}
              ]}},"url":"/series","version":"v1"}
            """.trimIndent()
        )
        val failure = runCatching {
            source(transport = transport(seriesPage = drifted)).popular(1)
        }.exceptionOrNull()
        assertTrue(
            "expected a SourceException, got: $failure",
            failure is SourceException
        )
        assertTrue(
            "expected the shape-miss marker, got: ${failure?.message}",
            failure!!.message.orEmpty()
                .contains(DeclarativeSource.SHAPE_MISS_MARKER)
        )
    }

    @Test
    fun `genuinely empty catalogue stays an empty result`() {
        // No payload anywhere and no rows: an honest empty.
        val plain = FakeTransport { request ->
            if (request.url == "$base/series") {
                FakeTransport.ok(
                    "<html><body><p>Nothing here yet.</p></body></html>"
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no"))
            }
        }
        val domConfig = config().copy(
            popular = ListSpec(
                url = "$base/series",
                results = "div.result",
                title = "a.t",
                link = "a.t@href"
            )
        )
        val page = source(config = domConfig, transport = plain).popular(1)
        assertTrue(page.books.isEmpty())
    }

    @Test
    fun `declared path resolving to an empty array is consumed not missed`() {
        val emptyPayload = inertiaPage(
            """
            {"component":"Series/Index","props":{"seriesList":{
              "data":[]}},"url":"/series","version":"v1"}
            """.trimIndent()
        )
        val page = source(
            transport = transport(seriesPage = emptyPayload)
        ).popular(1)
        assertTrue(page.books.isEmpty())
    }

    @Test
    fun `shape miss never fires on search`() {
        // A no-hit search is honest even when the page shape
        // is unreadable — search keeps its own empty copy.
        val driftedSearch = FakeTransport { request ->
            when {
                request.url.startsWith("$base/api/search") ->
                    FakeTransport.ok(
                        """{"payload":{"records":[]}}"""
                    )
                else -> AppResult.Ok(
                    FakeTransport.response(404, "no")
                )
            }
        }
        val page = source(transport = driftedSearch).search("zzz", 1)
        assertTrue(page.books.isEmpty())
    }
}
