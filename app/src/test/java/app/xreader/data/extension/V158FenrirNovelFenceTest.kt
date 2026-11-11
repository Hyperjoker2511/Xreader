package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.download.FetchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (B2, the novel fence): an AJAX PROSE chapter on
 * the Fenrir-shaped road must gain NO pages record and read
 * as TEXT evidence only — B2's evidence pass fires on
 * image-shaped HTML, never on a novel chapter's prose (its
 * rule demands near-zero prose AND a real page-image set).
 * Fenrir novels keep reading as novels, exactly as before.
 */
class V158FenrirNovelFenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://fenrir.example"
    private val bookUrl = "$base/api/series/my-novel"
    private val chapterUrl = "$base/api/series/my-novel/chapters/3"

    private fun fenrirShapedConfig() = DeclarativeConfig(
        id = "v158-novel-fence",
        name = "Fenrir Shaped",
        baseUrl = base,
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "a", name = "@text", link = "@href",
                jsonNameField = "title",
                jsonLinkField = "slug",
                jsonLinkTemplate = "$base/api/series/{bookSlug}/{value}"
            )
        ),
        chapter = ChapterSpec(
            content = "body",
            ajax = ChapterTextAjax(
                urlTemplate = "$base/api/series/{seg-3}/chapters/{seg-1}",
                jsonContentField = "content"
            )
        ),
        popular = ListSpec(
            url = "$base/api/series",
            results = "",
            title = "title",
            link = "slug",
            jsonPath = "data",
            kindField = "type",
            kindValueMap = mapOf("webtoon" to "MANGA", "novel" to "TEXT")
        )
    )

    @Test
    fun `ajax prose chapter gains no pages record and reads as text`() {
        val proseHtml = (1..6).joinToString("") { n ->
            "<p>Paragraph $n, and the sword sang as the hero " +
                "turned toward the gate, because the road home " +
                "was long and the night was only beginning.</p>"
        }
        val transport = FakeTransport { request ->
            if (request.url == chapterUrl) {
                FakeTransport.ok(
                    "{\"content_format\":\"html\",\"content\":\"" +
                        proseHtml.replace("\"", "\\\"") + "\"}"
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val stack = v158FetcherStack(
            tmp, fenrirShapedConfig(), transport,
            bookKind = BookKind152.TEXT,
            bookUrl = bookUrl,
            chapterUrls = listOf(chapterUrl)
        )
        val outcome = stack.fetch(chapterUrl)
        assertTrue(
            "expected Success, got $outcome",
            outcome is FetchOutcome.Success
        )
        val content = (outcome as FetchOutcome.Success).content
        assertEquals(
            "a novel chapter must NOT gain a pages record",
            null, content.pages
        )
        assertTrue(
            "the chapter reads as prose blocks",
            content.blocks.isNotEmpty()
        )
        assertTrue(
            "tier-2's witness must testify TEXT (no image chapter)",
            !content.isImageChapter
        )
    }
}
