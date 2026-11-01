package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.ai.GeminiClient
import app.xreader.data.db.Chapter
import app.xreader.domain.reader.ChapterDateText
import app.xreader.domain.reader.WpmEstimator
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.3 pins: the fix round from the v1.0.6.2 draw-check.
 *
 * P3 — "≈21 min left in this book" on a 759-chapter book at 0%:
 * un-downloaded chapters carried ZERO words. The estimator now
 * counts them at the book's own average chapter length.
 *
 * P4 — the novelight lane declared NO details cover (null), so
 * covers never resolved; the lane now names the book's own
 * poster (".poster img@src", verified on the live page).
 *
 * P5 — chapter dates end to end: the lane declares the row date
 * (".date@text"), the engine extracts it through the pagination
 * walk over the REAL capture, the reconciler carries it without
 * erasing known dates, and the display formatter renders it
 * relatively (never negative, never raw).
 *
 * P6 — the home-page catalogue hop picks its link purely.
 *
 * P7 — a quota 429 on one Gemini image model cascades to the
 * next instead of aborting Illustrate, and the final error
 * carries Google's own quota words.
 */
class V1063FixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── P3: the honest estimator ─────────────────────────────

    private fun cw(read: Boolean, words: Int) =
        WpmEstimator.ChapterWords(read, words)

    @Test
    fun `estimator counts measured chapters exactly`() {
        val (minutes, estimated) = WpmEstimator.estimateRemainingMinutes(
            listOf(cw(true, 2000), cw(false, 2000), cw(false, 3000)),
            wpm = 100.0
        )
        assertEquals(50, minutes)
        assertEquals(false, estimated)
    }

    @Test
    fun `estimator falls back to a typical chapter when nothing is measured`() {
        val (minutes, estimated) = WpmEstimator.estimateRemainingMinutes(
            listOf(cw(false, 0), cw(false, 0), cw(false, 0)),
            wpm = 200.0
        )
        // 3 chapters × the 2000-word fallback ÷ 200 wpm.
        assertEquals(30, minutes)
        assertEquals(true, estimated)
    }

    @Test
    fun `estimator never repeats the 21-minute lie on a huge book`() {
        // The draw-check shape: 758 unread chapters, ONE of them
        // downloaded (2500 words) — the old math said ≈21 min.
        val chapters = listOf(cw(false, 2500)) +
            List(757) { cw(false, 0) }
        val (minutes, estimated) = WpmEstimator.estimateRemainingMinutes(
            chapters, wpm = 180.0
        )
        assertEquals(758 * 2500 / 180 + 1, minutes)
        assertTrue(minutes > 10_000)
        assertEquals(true, estimated)
    }

    @Test
    fun `estimator is zero when everything is read`() {
        val (minutes, estimated) = WpmEstimator.estimateRemainingMinutes(
            listOf(cw(true, 2000), cw(true, 3000)), wpm = 180.0
        )
        assertEquals(0, minutes)
        assertEquals(false, estimated)
    }

    // ── P5: the relative date formatter ──────────────────────

    private val today: LocalDate = LocalDate.of(2026, 10, 30)

    @Test
    fun `dates render relatively and never negative`() {
        assertEquals("Today", ChapterDateText.relative("30.10.2026", today))
        assertEquals("Yesterday", ChapterDateText.relative("29.10.2026", today))
        assertEquals("3 days ago", ChapterDateText.relative("27.10.2026", today))
        assertEquals("3 weeks ago", ChapterDateText.relative("09.10.2026", today))
        // v1.3.1 (item 53): 50 days now reads in the user's own
        // wording — "a month ago" — not the old absolute date.
        assertEquals("a month ago", ChapterDateText.relative("10.09.2026", today))
        // A future date is an absolute date, never "-1 days ago".
        assertEquals("31 Oct 2026", ChapterDateText.relative("31.10.2026", today))
        assertNull(ChapterDateText.relative("not a date", today))
        assertNull(ChapterDateText.relative(null, today))
        assertNull(ChapterDateText.relative("", today))
    }

    @Test
    fun `latest relative picks the newest date`() {
        assertEquals(
            "Yesterday",
            ChapterDateText.latestRelative(
                listOf("01.10.2026", null, "29.10.2026", "junk"), today
            )
        )
        assertNull(ChapterDateText.latestRelative(listOf(null, "junk"), today))
    }

    // ── P5: the reconciler carries dates ─────────────────────

    private fun row(remoteId: String, dateText: String?) = Chapter(
        id = 1L, bookId = 7L, index = 0, title = "Chapter 1",
        remoteId = remoteId, updatedAt = 1L, dateText = dateText
    )

    @Test
    fun `reconciler takes the fresh date and never erases a known one`() {
        val fresh = ChapterReconciler.reconcile(
            listOf(row("a/1", "01.10.2026")),
            listOf(SourceChapter(id = "a/1", name = "Chapter 1", dateText = "02.10.2026"))
        )
        assertEquals("02.10.2026", fresh.updates.single().newDateText)

        val kept = ChapterReconciler.reconcile(
            listOf(row("a/1", "01.10.2026")),
            listOf(SourceChapter(id = "a/1", name = "Chapter 1"))
        )
        assertEquals("01.10.2026", kept.updates.single().newDateText)

        val inserted = ChapterReconciler.reconcile(
            emptyList(),
            listOf(SourceChapter(id = "a/9", name = "Chapter 9", dateText = "05.10.2026"))
        )
        assertEquals("05.10.2026", inserted.inserts.single().fetched.dateText)
    }

    // ── P4 + P5: the novelight lane, config and real capture ─

    @Test
    fun `novelight lane names the poster cover and the row date`() {
        val entry = KnownSites.entryFor(NovelightCaptureSite.HOST)!!
        val config = KnownSites.fullConfig(
            entry, "link-test", "Novelight", NovelightCaptureSite.BASE
        )!!
        assertEquals(".poster img@src", config.details.cover)
        assertEquals(".date@text", config.details.chapters.date)
    }

    private fun synthPageJson(numbers: List<Int>): String {
        val rows = numbers.joinToString("\n") { n ->
            "<a href=\"/book/chapter/${700_000 + n}\" class=\"chapter \">" +
                "<div class=\"title\">$n chapter </div>" +
                "<div class=\"chapter-info\">" +
                "<span class=\"author\">KimSU</span>" +
                "</div></a>"
        }
        val escaped = rows
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"html": "$escaped"}"""
    }

    @Test
    fun `the pagination walk carries the real capture's dates`() {
        val prefix = "${NovelightCaptureSite.BASE}/book/ajax/" +
            "chapter-pagination?book_id=402&page="
        val transport = FakeTransport { request ->
            val url = request.url
            when {
                url == NovelightCaptureSite.BOOK_URL ->
                    FakeTransport.ok(NovelightCaptureSite.BOOK_PAGE)
                url.startsWith(prefix) -> {
                    val page = url.removePrefix(prefix).toInt()
                    when (page) {
                        2 -> FakeTransport.ok(NovelightCaptureSite.PAGE_2_RESPONSE)
                        in 3..15 -> {
                            val top = 660 - (page - 3) * 50
                            FakeTransport.ok(
                                synthPageJson((0..49).map { top - it })
                            )
                        }
                        16 -> FakeTransport.ok(NovelightCaptureSite.PAGE_16_RESPONSE)
                        else -> AppResult.Ok(
                            FakeTransport.response(404, "no such page")
                        )
                    }
                }
                else -> AppResult.Ok(FakeTransport.response(404, "not found"))
            }
        }
        val entry = KnownSites.entryFor(NovelightCaptureSite.HOST)!!
        val config = KnownSites.fullConfig(
            entry, "link-dates", "Novelight", NovelightCaptureSite.BASE
        )!!
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf(NovelightCaptureSite.HOST))
        )
        val chapters = DeclarativeSource(config, context).chapters(
            SourceBook(id = NovelightCaptureSite.BOOK_URL, title = "")
        )
        assertEquals(759, chapters.size)
        // Chapter 1 (real last page of the walk): dated 07.11.2024.
        assertEquals("07.11.2024", chapters.first().dateText)
        // Chapter 710 (real page 2): dated 09.09.2026.
        val chapter710 = chapters.single { it.id.endsWith("/335760") }
        assertEquals("09.09.2026", chapter710.dateText)
        // At least the two real pages' rows (50 + 9) carry dates;
        // the synthesised middle pages declare none.
        assertTrue(chapters.count { it.dateText != null } >= 59)
    }

    // ── P6: the catalogue hop's picker ───────────────────────

    @Test
    fun `catalogue picker takes path family first and skips other hosts`() {
        val doc = Jsoup.parse(
            """
            <html><body>
              <a href="https://other.example/novels">Other host</a>
              <a href="/about">About us</a>
              <a href="/novels">All novels</a>
              <a href="/book/some-book">A book</a>
            </body></html>
            """.trimIndent(),
            "https://site.example/"
        )
        assertEquals(
            "https://site.example/novels",
            LinkProber.pickCatalogueLink(doc, "site.example")
        )
    }

    @Test
    fun `catalogue picker accepts link text and gives up cleanly`() {
        val byText = Jsoup.parse(
            """<html><body><a href="/browse-all">Browse the library</a></body></html>""",
            "https://site.example/"
        )
        assertEquals(
            "https://site.example/browse-all",
            LinkProber.pickCatalogueLink(byText, "site.example")
        )
        val none = Jsoup.parse(
            """<html><body><a href="/about">About</a></body></html>""",
            "https://site.example/"
        )
        assertNull(LinkProber.pickCatalogueLink(none, "site.example"))
    }

    // ── P7: the image cascade on quota ───────────────────────

    private val quotaBody =
        """{"error":{"code":429,"message":"Quota exceeded for this """ +
            """model. Please check your plan.","status":"RESOURCE_EXHAUSTED"}}"""

    private val imageBody =
        """{"candidates":[{"content":{"parts":[{"inlineData":""" +
            """{"data":"AQID","mimeType":"image/png"}}]}}]}"""

    private fun gemini(transport: FakeTransport) = GeminiClient(
        transport = transport,
        keyProvider = { "test-key" },
        pickerProvider = { app.xreader.data.ai.GeminiModels.AUTO }
    )

    @Test
    fun `image generation cascades past a quota 429`() = runBlocking {
        val transport = FakeTransport { request ->
            when {
                request.url.contains("gemini-3.1-flash-image") ->
                    AppResult.Ok(FakeTransport.response(429, quotaBody))
                else -> FakeTransport.ok(imageBody)
            }
        }
        val result = gemini(transport).generateImage("paint a sword")
        assertTrue("expected Ok, got $result", result is AppResult.Ok)
        assertEquals(2, transport.requests.size)
        assertTrue(
            transport.requests[1].url.contains("gemini-2.5-flash-image")
        )
    }

    @Test
    fun `image generation surfaces the quota truth when every model is blocked`() =
        runBlocking {
            val transport = FakeTransport { _ ->
                AppResult.Ok(FakeTransport.response(429, quotaBody))
            }
            val result = gemini(transport).generateImage("paint a sword")
            assertTrue(result is AppResult.Err)
            val error = (result as AppResult.Err).error
            assertTrue(error is AppError.RateLimited)
            assertTrue(
                "Google's words must ride the error, got: ${error.detail}",
                error.detail?.contains("Quota exceeded") == true
            )
            assertEquals(2, transport.requests.size)
        }
}
