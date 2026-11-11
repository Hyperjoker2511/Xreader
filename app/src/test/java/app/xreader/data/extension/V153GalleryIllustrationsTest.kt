package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.3 (finding #4, item 15): the gallery illustrations
 * CAPABILITY. Some sites keep a book's illustrations on a
 * separate gallery page instead of inside its chapters.
 * When a config declares [IllustrationsSpec], resolving the
 * book's chapter list harvests the gallery once, and each
 * volume's images lead that volume's opening chapter (the
 * <img> tags ride the ordinary chapter HTML, so the existing
 * per-book image pipeline downloads them — no reader change).
 *
 * The LNT wiring itself stays UNPROVEN in v1.5.3: the Step-0
 * probe (2026-11-04) captured the real gallery tab three
 * ways (plain, fragment parameter, XHR header) and the
 * gallery content section is EMPTY in every anonymous
 * capture — the art loads behind behaviour this egress can't
 * reproduce. So the capability is proven here against a
 * SYNTHETIC grouped gallery, and the real captured markup
 * (resources/lnt/gallery-tab-excerpt.html, byte-exact) is
 * proven to attach NOTHING — feature dark on real data,
 * never guessed selectors.
 */
class V153GalleryIllustrationsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://gal.example"
    private val bookUrl = "$base/book/x"
    private val perms = ExtensionPermissions(domains = listOf("gal.example"))

    private val bookPage = """
        <html><head><title>X</title></head><body>
        <h1>X</h1>
        <ul class="chapters">
          <li><a href="/book/x/v1c1">Volume 1 Chapter 1</a></li>
          <li><a href="/book/x/v1c2">Volume 1 Chapter 2</a></li>
          <li><a href="/book/x/v2c1">Volume 2 Chapter 1</a></li>
        </ul>
        </body></html>
    """.trimIndent()

    private fun chapterPage(marker: String) = """
        <html><body><div class="content">
          <p>$marker — the prose of this chapter runs long enough
          to be a real chapter and not a stub page at all.</p>
        </div></body></html>
    """.trimIndent()

    private val groupedGallery = """
        <html><body>
        <div class="vol"><h3>Volume 1</h3>
          <img class="gal" src="/illus/v1-a.jpg"/>
          <img class="gal" src="data:image/gif;base64,R0lGODlhAQABAAAAACw="
               data-src="/illus/v1-b.jpg"/>
        </div>
        <div class="vol"><h3>Volume 2</h3>
          <img class="gal" src="/illus/v2-a.jpg"/>
        </div>
        </body></html>
    """.trimIndent()

    private val flatGallery = """
        <html><body>
          <img class="gal" src="/illus/flat-a.jpg"/>
          <img class="gal" src="/illus/flat-b.jpg"/>
        </body></html>
    """.trimIndent()

    private fun config(
        illustrations: IllustrationsSpec?
    ) = DeclarativeConfig(
        id = "v153-gallery",
        name = "Gallery Fixture",
        baseUrl = base,
        popular = ListSpec(
            url = "$base/popular",
            results = "div.result",
            title = "a.t",
            link = "a.t@href"
        ),
        details = DetailsSpec(
            title = "h1",
            chapters = ChapterListSpec(
                list = "ul.chapters li a",
                name = "@text",
                link = "@href",
                reverse = false
            )
        ),
        chapter = ChapterSpec(content = "div.content"),
        illustrations = illustrations
    )

    private fun source(
        illustrations: IllustrationsSpec?,
        galleryPage: String
    ): DeclarativeSource {
        val galleryUrl = "$bookUrl?tab=illustrations"
        val transport = FakeTransport { request ->
            when (request.url) {
                bookUrl -> FakeTransport.ok(bookPage)
                galleryUrl -> FakeTransport.ok(galleryPage)
                "$base/book/x/v1c1" ->
                    FakeTransport.ok(chapterPage("First volume opens"))
                "$base/book/x/v1c2" ->
                    FakeTransport.ok(chapterPage("First volume continues"))
                "$base/book/x/v2c1" ->
                    FakeTransport.ok(chapterPage("Second volume opens"))
                else -> AppResult.Ok(
                    FakeTransport.response(404, "no recorded shape")
                )
            }
        }
        return DeclarativeSource(
            config(illustrations),
            testContext(transport, tmp.newFolder(), perms)
        )
    }

    private fun openBook(source: DeclarativeSource) =
        source.chapters(SourceBook(id = bookUrl, title = "X"))

    private fun content(
        source: DeclarativeSource,
        chapterId: String,
        name: String
    ): String = source.chapterContent(
        SourceChapter(id = chapterId, name = name)
    ).html

    @Test
    fun `grouped gallery images lead their volumes opening chapters`() {
        val src = source(
            IllustrationsSpec(
                urlTemplate = "{bookUrl}?tab=illustrations",
                imageSelector = "img.gal@src",
                groupSelector = "div.vol"
            ),
            groupedGallery
        )
        val chapters = openBook(src)
        assertEquals(3, chapters.size)

        val v1Open = content(src, "$base/book/x/v1c1", "Volume 1 Chapter 1")
        val firstText = v1Open.indexOf("First volume opens")
        val imgA = v1Open.indexOf("$base/illus/v1-a.jpg")
        val imgB = v1Open.indexOf("$base/illus/v1-b.jpg")
        assertTrue("vol-1 art A must lead the chapter: $v1Open", imgA in 0 until firstText)
        assertTrue(
            "the data-src-only art must resolve through the lazy ladder: $v1Open",
            imgB in 0 until firstText
        )
        assertTrue("group order kept", imgA < imgB)
        assertFalse(
            "volume 2 art must not leak into volume 1",
            v1Open.contains("v2-a.jpg")
        )

        val v1Second = content(src, "$base/book/x/v1c2", "Volume 1 Chapter 2")
        assertFalse(
            "a non-opening chapter gains no gallery images",
            v1Second.contains("/illus/")
        )

        val v2Open = content(src, "$base/book/x/v2c1", "Volume 2 Chapter 1")
        val v2Text = v2Open.indexOf("Second volume opens")
        val imgV2 = v2Open.indexOf("$base/illus/v2-a.jpg")
        assertTrue("vol-2 art must lead its opening chapter", imgV2 in 0 until v2Text)
        assertFalse(v2Open.contains("v1-a.jpg"))
    }

    @Test
    fun `ungrouped gallery sends every image to the first chapter`() {
        val src = source(
            IllustrationsSpec(
                urlTemplate = "{bookUrl}?tab=illustrations",
                imageSelector = "img.gal@src"
            ),
            flatGallery
        )
        openBook(src)
        val first = content(src, "$base/book/x/v1c1", "Volume 1 Chapter 1")
        assertTrue(first.contains("$base/illus/flat-a.jpg"))
        assertTrue(first.contains("$base/illus/flat-b.jpg"))
        val second = content(src, "$base/book/x/v1c2", "Volume 1 Chapter 2")
        assertFalse(second.contains("/illus/"))
    }

    @Test
    fun `the real captured lnt gallery attaches nothing`() {
        // The byte-exact captured gallery block: its content
        // section is EMPTY (the art never reaches anonymous
        // fetches), so the harvest finds no images and the
        // chapters read exactly as they would without the
        // feature — capability present, wiring dark, book
        // unharmed.
        val excerpt = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "lnt/gallery-tab-excerpt.html"
            )
        ) { "missing lnt gallery fixture" }.readBytes().toString(Charsets.UTF_8)
        val capturedPage = "<html><body>$excerpt</body></html>"
        val src = source(
            IllustrationsSpec(
                urlTemplate = "{bookUrl}?tab=illustrations",
                imageSelector = ".lnt-gallery-content img@src"
            ),
            capturedPage
        )
        val chapters = openBook(src)
        assertEquals(3, chapters.size)
        val first = content(src, "$base/book/x/v1c1", "Volume 1 Chapter 1")
        assertFalse(
            "no illustration may be invented from the empty capture",
            first.contains("<img")
        )
        assertTrue(first.contains("First volume opens"))
    }
}
