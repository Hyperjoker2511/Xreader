package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceChapter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.12 gate — FRONT 2, M6 (G2.5, Fenrir blur): Fenrir's
 * REAL road for a signed-in comic chapter, driven end to end.
 *
 * The v1.5.9 fix (V159FenrirPagePickTest) pinned the page
 * pickers on the SNIFF road and the DECLARED-SPEC road. But
 * FenrirRealm's shipped config (BundledSingles.fenrirRealm)
 * serves chapters through neither: its chapter spec is the
 * AJAX text road (ChapterTextAjax, content field "content",
 * read-token contract), and a comic chapter arrives as a
 * blank-text payload whose <img> strip is rescued by
 * processAjaxResponse's image-chapter branch. That exact
 * road — real bundled config, real endpoint shape, a
 * Fenrir-shaped payload (src=preview_url, data-full=
 * original_url, srcset ladder on the original) — is what
 * this test drives: the stored page record must carry the
 * ORIGINALS, never the previews.
 *
 * The token lifecycle is not the claim here: the transport
 * answers the chapter endpoint directly with a readable
 * (format-less, unlocked) payload, which the road accepts
 * without a mint — the URL SELECTION is what is pinned.
 */
class V1512FenrirAjaxRoadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val host = "https://fenrirealm.com"
    private val chapterId =
        "$host/api/new/v2/series/dragon-slayer/chapters/12"

    /** One Fenrir-shaped page <img>: preview in src, the
     * original in data-full, the ladder in srcset. */
    private fun fenrirImg(n: Int): String =
        "<img src=\"$host/pages/p$n-preview.jpg\" " +
            "data-full=\"$host/pages/p$n.jpg\" " +
            "srcset=\"$host/pages/p$n.jpg?width=480 480w, " +
            "$host/pages/p$n.jpg?width=768 768w, " +
            "$host/pages/p$n.jpg?width=1280 1280w\"/>"

    @Test
    fun `fenrir ajax road stores the originals for a comic chapter`() {
        val payloadHtml = (1..3).joinToString("") { fenrirImg(it) }
        val body = buildJsonObject {
            put("content", JsonPrimitive(payloadHtml))
        }.toString()
        val transport = FakeTransport { request ->
            if (request.url == chapterId) {
                FakeTransport.ok(body)
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val source = DeclarativeSource(
            BundledSingles.fenrirRealm("fenrirealm", "FenrirRealm"),
            testContext(
                transport, tmp.newFolder(),
                ExtensionPermissions(domains = listOf("fenrirealm.com"))
            )
        )
        source.chapterContent(
            SourceChapter(id = chapterId, name = "Chapter 12")
        )
        val pages = source.pagesOf(chapterId)
        assertNotNull(
            "the AJAX road must stash a page record for the" +
                " image chapter",
            pages
        )
        assertEquals(
            "data-full (the original) must be the stored page," +
                " never the preview",
            (1..3).map { "$host/pages/p$it.jpg" },
            pages!!.urls
        )
    }
}
