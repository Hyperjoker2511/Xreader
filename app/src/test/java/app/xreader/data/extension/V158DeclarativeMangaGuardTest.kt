package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.download.FetchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (B2 / F1 M3): the §1.1 missing-record guard
 * extended to the declarative leg. A book RESOLVED MANGA
 * (its stored kind, filed by tier-1) whose declarative
 * fetch yields prose with NO page record must end in the
 * same retryable ProviderDown the adapter guard returns —
 * never the HTML-conversion fall-through that stored prose
 * poison. Nothing is stored: the outcome is a Failure.
 */
class V158DeclarativeMangaGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://both.example"
    private val bookUrl = "$base/book/storm-rider"
    private val chapterUrl = "$base/book/storm-rider/chapter-1"

    private fun bothKindsConfig() = DeclarativeConfig(
        id = "v158-manga-guard",
        name = "Both Kinds",
        baseUrl = base,
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "a", name = "@text", link = "@href"
            )
        ),
        chapter = ChapterSpec(content = "div.content"),
        popular = ListSpec(
            url = "$base/popular",
            results = "div.result",
            title = "a",
            link = "a@href",
            kindField = "type",
            kindValueMap = mapOf("webtoon" to "MANGA", "novel" to "TEXT")
        )
    )

    @Test
    fun `manga-resolved book with prose-only fetch fails retryably and stores nothing`() {
        val prose = (1..6).joinToString("") { n ->
            "<p>Paragraph $n of a chapter that reads as plain " +
                "prose, with the hero crossing the river at dawn " +
                "and the banner flying over the gate.</p>"
        }
        val transport = FakeTransport { request ->
            if (request.url == chapterUrl) {
                FakeTransport.ok(
                    "<html><body><div class=\"content\">" +
                        "$prose</div></body></html>"
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val stack = v158FetcherStack(
            tmp, bothKindsConfig(), transport,
            bookKind = BookKind152.MANGA,
            bookUrl = bookUrl,
            chapterUrls = listOf(chapterUrl)
        )
        val outcome = stack.fetch(chapterUrl)
        assertTrue(
            "a manga-routed declarative chapter with no page " +
                "record must FAIL, not store prose poison; got $outcome",
            outcome is FetchOutcome.Failure
        )
        val error = (outcome as FetchOutcome.Failure).error
        assertTrue(
            "the guard's error is the retryable ProviderDown, got $error",
            error is AppError.ProviderDown
        )
        assertEquals(
            "the chapter's page list couldn't be loaded",
            (error as AppError.ProviderDown).detail
        )
        assertTrue(
            "the guard failure must be retryable",
            error.retryable
        )
    }

    @Test
    fun `text-resolved book with the same prose fetch succeeds as prose`() {
        // The fence: the guard is keyed on the RESOLVED kind —
        // the identical prose answer for a TEXT book is a
        // perfectly good novel chapter.
        val prose = (1..6).joinToString("") { n ->
            "<p>Paragraph $n of a chapter that reads as plain " +
                "prose, with the hero crossing the river at dawn " +
                "and the banner flying over the gate.</p>"
        }
        val transport = FakeTransport { request ->
            if (request.url == chapterUrl) {
                FakeTransport.ok(
                    "<html><body><div class=\"content\">" +
                        "$prose</div></body></html>"
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val stack = v158FetcherStack(
            tmp, bothKindsConfig(), transport,
            bookKind = BookKind152.TEXT,
            bookUrl = bookUrl,
            chapterUrls = listOf(chapterUrl)
        )
        val outcome = stack.fetch(chapterUrl)
        assertTrue(
            "expected Success for the TEXT book, got $outcome",
            outcome is FetchOutcome.Success
        )
        val content = (outcome as FetchOutcome.Success).content
        assertEquals(null, content.pages)
        assertTrue(content.blocks.isNotEmpty())
    }
}
