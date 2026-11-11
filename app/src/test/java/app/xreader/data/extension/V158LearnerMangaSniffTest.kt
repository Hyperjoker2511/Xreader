package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.download.FetchOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (A7 / F1 §6 fixture (c)): a LEARNER-shaped config
 * — the link learner writes content/title/nextPage only,
 * `pages = null` — stamped MANGA (the Keiyoushi-claim
 * stamp), driven through [ExtensionChapterFetcher.fetch]
 * against a recorded Asura-shaped chapter page (a reader
 * container holding a strip of page images). Pre-fix this
 * threw "this manga source doesn't say where its chapter
 * pages are" deterministically; post-fix the manga road
 * extracts via the sniff and a pages record is stored.
 */
class V158LearnerMangaSniffTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://asura.example"
    private val bookUrl = "$base/series/blade-of-ash"
    private val chapterUrl = "$base/series/blade-of-ash/chapter-1"

    /** The learner's exact ChapterSpec shape: content +
     * title, NO pages selector (LinkProber's construction). */
    private fun learnerConfig() = DeclarativeConfig(
        id = "v158-learner-manga",
        name = "Learner Manga",
        baseUrl = base,
        mediaKind = MediaKind.MANGA,
        details = DetailsSpec(
            chapters = ChapterListSpec(
                list = "a", name = "@text", link = "@href"
            )
        ),
        chapter = ChapterSpec(
            content = "div.reader",
            title = "h1",
            pages = null
        )
    )

    @Test
    fun `stamped learner config with no pages spec serves a sniffed page list`() {
        val pageImages = (1..6).joinToString("") { n ->
            "<img src=\"https://cdn.example/blade/p$n.jpg\"/>"
        }
        val transport = FakeTransport { request ->
            if (request.url == chapterUrl) {
                FakeTransport.ok(
                    "<html><head><title>Chapter 1</title></head>" +
                        "<body><h1>Chapter 1</h1>" +
                        "<div class=\"reader\">$pageImages</div>" +
                        "</body></html>"
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val stack = v158FetcherStack(
            tmp, learnerConfig(), transport,
            bookKind = BookKind152.MANGA,
            bookUrl = bookUrl,
            chapterUrls = listOf(chapterUrl)
        )
        val outcome = stack.fetch(chapterUrl)
        assertTrue(
            "a fresh chapter of a stamped learner source must " +
                "load its pages, not throw; got $outcome",
            outcome is FetchOutcome.Success
        )
        val content = (outcome as FetchOutcome.Success).content
        assertNotNull(content.pages)
        assertEquals(
            (1..6).map { "https://cdn.example/blade/p$it.jpg" },
            content.pages!!.urls
        )
    }

    @Test
    fun `sniff road under the threshold keeps the plain-words failure`() {
        // Two images only: under MIN_SNIFFED_PAGES the sniff
        // must NOT mint a page list — the chapter keeps the
        // honest "pages weren't found" failure (via the B2
        // guard's retryable ProviderDown at the seam).
        val transport = FakeTransport { request ->
            if (request.url == chapterUrl) {
                FakeTransport.ok(
                    "<html><body><div class=\"reader\">" +
                        "<img src=\"https://cdn.example/blade/p1.jpg\"/>" +
                        "<img src=\"https://cdn.example/blade/p2.jpg\"/>" +
                        "</div></body></html>"
                )
            } else {
                AppResult.Ok(FakeTransport.response(404, "no shape"))
            }
        }
        val stack = v158FetcherStack(
            tmp, learnerConfig(), transport,
            bookKind = BookKind152.MANGA,
            bookUrl = bookUrl,
            chapterUrls = listOf(chapterUrl)
        )
        val outcome = stack.fetch(chapterUrl)
        assertTrue(
            "an under-threshold sniff must fail honestly, got $outcome",
            outcome is FetchOutcome.Failure
        )
    }
}
