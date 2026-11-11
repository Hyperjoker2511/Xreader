package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.download.FetchOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (B2, the plate fence — pins B3's input contract):
 * a novel book whose chapters ride the AJAX road, with ONE
 * image-only colour-plate chapter among prose chapters.
 * The plate chapter's fetch stores its pages record as
 * EVIDENCE (B2's pass — what B3's tier-2 reads), the prose
 * chapters store none, and the book's kind does NOT move:
 * a single plate verdict is absorbed by design (B3's bar
 * for a TEXT-prior book is three consecutive image
 * verdicts with no prose verdict ever — never one plate).
 */
class V158PlateChapterEvidenceFenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://fenrir.example"
    private val bookUrl = "$base/api/series/plated-novel"
    private val proseUrl = "$base/api/series/plated-novel/chapters/1"
    private val plateUrl = "$base/api/series/plated-novel/chapters/2"
    private val prose2Url = "$base/api/series/plated-novel/chapters/3"

    private fun fenrirShapedConfig() = DeclarativeConfig(
        id = "v158-plate-fence",
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

    private fun ajaxAnswer(html: String) =
        "{\"content_format\":\"html\",\"content\":\"" +
            html.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"

    @Test
    fun `plate chapter stores evidence pages, prose chapters do not, kind unmoved`() {
        val proseHtml = (1..5).joinToString("") { n ->
            "<p>Paragraph $n, and the sword sang as the hero " +
                "turned toward the gate, because the road home " +
                "was long and the night was only beginning.</p>"
        }
        val plateHtml = (1..4).joinToString("") { n ->
            "<img src=\"https://cdn.example/plates/pl$n.jpg\"/>"
        }
        val transport = FakeTransport { request ->
            when (request.url) {
                proseUrl, prose2Url -> FakeTransport.ok(ajaxAnswer(proseHtml))
                plateUrl -> FakeTransport.ok(ajaxAnswer(plateHtml))
                else -> AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val stack = v158FetcherStack(
            tmp, fenrirShapedConfig(), transport,
            bookKind = BookKind152.TEXT,
            bookUrl = bookUrl,
            chapterUrls = listOf(proseUrl, plateUrl, prose2Url)
        )

        val proseOutcome = stack.fetch(proseUrl)
        assertTrue(proseOutcome is FetchOutcome.Success)
        assertEquals(
            null,
            (proseOutcome as FetchOutcome.Success).content.pages
        )

        val plateOutcome = stack.fetch(plateUrl)
        assertTrue(
            "the plate fetch must succeed with its evidence, got $plateOutcome",
            plateOutcome is FetchOutcome.Success
        )
        val plateContent = (plateOutcome as FetchOutcome.Success).content
        assertNotNull(
            "the plate chapter's pages record is stored as EVIDENCE",
            plateContent.pages
        )
        assertEquals(
            (1..4).map { "https://cdn.example/plates/pl$it.jpg" },
            plateContent.pages!!.urls
        )

        val prose2Outcome = stack.fetch(prose2Url)
        assertTrue(prose2Outcome is FetchOutcome.Success)
        assertEquals(
            null,
            (prose2Outcome as FetchOutcome.Success).content.pages
        )

        // The book's kind does NOT move on a plate verdict:
        // the row still files TEXT (the re-file bar is B3's,
        // and one plate among prose never clears it).
        val row = runBlocking { stack.bookDao.getById(stack.bookId) }
        assertEquals(BookKind152.TEXT, row?.kind)
    }
}
