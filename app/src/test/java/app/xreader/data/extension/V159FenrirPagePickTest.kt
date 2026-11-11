package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.9 (Finding 5 §6): the page-image pickers choose the
 * FULL-SIZE door. Fenrir's markup contract (decoded from
 * its production reader bundle — the conviction's evidence;
 * a signed-in comic payload could not be captured
 * anonymously, the anonymous API answering `locked`):
 * every comic <img> carries src=preview_url (a deliberate
 * momentary blurry placeholder), data-full=original_url,
 * and a srcset width ladder (480/768/1280) built on the
 * original. The old picker read data-src/src only, so the
 * preview was stored, cached URL-keyed and upscaled ~2.25×
 * forever.
 *
 * Picker order pinned here, in BOTH pickers
 * ([DeclarativeSource.sniffImageChapterEvidence]'s sniff
 * road and the declared-spec road through pageUrlOf):
 * data-full → largest-width srcset candidate → data-src →
 * src (the old tail, unchanged).
 */
class V159FenrirPagePickTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://fenrir.example"
    private val chapterUrl = "$base/series/dragon/chapters/1"

    private fun config(pages: String? = null) = DeclarativeConfig(
        id = "v159-fenrir-pick",
        name = "Fenrir Pick Fixture",
        baseUrl = base,
        mediaKind = MediaKind.MANGA,
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "a", name = "@text", link = "@href"
            )
        ),
        chapter = ChapterSpec(
            content = "div.pages",
            pages = pages
        )
    )

    private fun source(
        config: DeclarativeConfig,
        transport: FakeTransport = FakeTransport {
            AppResult.Ok(FakeTransport.response(404, "no shape"))
        }
    ): DeclarativeSource = DeclarativeSource(
        config,
        testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("fenrir.example"))
        )
    )

    /** One Fenrir-shaped page <img>: preview in src, the
     * original in data-full, the ladder in srcset. */
    private fun fenrirImg(n: Int): String =
        "<img src=\"$base/pages/p$n-preview.jpg\" " +
            "data-full=\"$base/pages/p$n.jpg\" " +
            "srcset=\"$base/pages/p$n.jpg?width=480 480w, " +
            "$base/pages/p$n.jpg?width=768 768w, " +
            "$base/pages/p$n.jpg?width=1280 1280w\"/>"

    /** One srcset-only page <img>: preview in src, the
     * ladder in srcset, NO data-full. */
    private fun srcsetOnlyImg(n: Int): String =
        "<img src=\"$base/pages/p$n-preview.jpg\" " +
            "srcset=\"$base/pages/p$n.jpg?width=480 480w, " +
            "$base/pages/p$n.jpg?width=768 768w, " +
            "$base/pages/p$n.jpg?width=1280 1280w\"/>"

    private fun fragment(imgs: String): String =
        "<div class=\"pages\">$imgs</div>"

    private fun sniffUrls(html: String): List<String> {
        val pages = source(config()).sniffImageChapterEvidence(
            SourceChapter(id = chapterUrl, name = "Chapter 1"),
            html
        )
        assertNotNull("the fragment must read as an image chapter", pages)
        return pages!!.urls
    }

    // ── (a) data-full wins (sniff road) ───────────────────

    @Test
    fun `fenrir markup stores the original, not the preview`() {
        val urls = sniffUrls(fragment((1..3).joinToString("") { fenrirImg(it) }))
        assertEquals(
            "data-full (the original) must be the stored page",
            (1..3).map { "$base/pages/p$it.jpg" },
            urls
        )
    }

    // ── (a2) data-full wins (declared-spec road) ──────────

    @Test
    fun `declared pages spec also stores the original`() {
        val pageHtml = "<html><head><title>Chapter 1</title></head>" +
            "<body>" +
            fragment((1..3).joinToString("") { fenrirImg(it) }) +
            "</body></html>"
        val transport = FakeTransport { request ->
            if (request.url == chapterUrl) {
                FakeTransport.ok(pageHtml)
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val src = source(config(pages = "div.pages img"), transport)
        src.chapterContent(
            SourceChapter(id = chapterUrl, name = "Chapter 1")
        )
        val pages = src.pagesOf(chapterUrl)
        assertNotNull("the declared road must stash a page record", pages)
        assertEquals(
            (1..3).map { "$base/pages/p$it.jpg" },
            pages!!.urls
        )
    }

    // ── (b) srcset-only: the LARGEST width wins ───────────

    @Test
    fun `srcset only picks the largest width, never the first`() {
        val urls = sniffUrls(
            fragment((1..3).joinToString("") { srcsetOnlyImg(it) })
        )
        assertEquals(
            (1..3).map { "$base/pages/p$it.jpg?width=1280" },
            urls
        )
        assertNotEquals(
            "the first ladder entry (480w) must not win",
            "$base/pages/p1.jpg?width=480", urls[0]
        )
        assertNotEquals(
            "the preview src must not win",
            "$base/pages/p1-preview.jpg", urls[0]
        )
    }

    // ── (c) plain src-only control ────────────────────────

    @Test
    fun `plain src only images resolve exactly as before`() {
        val imgs = (1..3).joinToString("") { n ->
            "<img src=\"$base/pages/q$n.jpg\"/>"
        }
        val urls = sniffUrls(fragment(imgs))
        assertEquals(
            (1..3).map { "$base/pages/q$it.jpg" },
            urls
        )
    }

    @Test
    fun `data-src still beats src when no full-size door exists`() {
        val imgs = (1..3).joinToString("") { n ->
            "<img src=\"$base/pages/r$n-preview.jpg\" " +
                "data-src=\"$base/pages/r$n.jpg\"/>"
        }
        val urls = sniffUrls(fragment(imgs))
        assertEquals(
            "the old data-src → src tail is unchanged",
            (1..3).map { "$base/pages/r$it.jpg" },
            urls
        )
    }
}
