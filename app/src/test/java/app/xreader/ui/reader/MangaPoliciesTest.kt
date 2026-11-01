package app.xreader.ui.reader

import app.xreader.domain.content.ChapterPage
import app.xreader.domain.reader.ImageReaderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manga programme (slice M2) pins for the image reader's pure
 * rules: the option-scope mode resolution, the page-fraction
 * progress mapping (both modes + restore), the preload
 * window, the local-file-vs-URL page choice, the low-RAM
 * decode width, and the webtoon reading-position maths.
 */
class MangaPoliciesTest {

    // ── Mode resolution (option-scope rule) ─────────────────

    @Test
    fun seriesOverrideWinsOverTheGlobalDefault() {
        assertEquals(
            ImageReaderMode.PAGED,
            MangaModeResolution.resolve(ImageReaderMode.PAGED, ImageReaderMode.WEBTOON)
        )
        assertEquals(
            ImageReaderMode.WEBTOON,
            MangaModeResolution.resolve(ImageReaderMode.WEBTOON, ImageReaderMode.PAGED)
        )
    }

    @Test
    fun noOverrideFollowsTheGlobalDefault() {
        assertEquals(
            ImageReaderMode.WEBTOON,
            MangaModeResolution.resolve(null, ImageReaderMode.WEBTOON)
        )
        assertEquals(
            ImageReaderMode.PAGED,
            MangaModeResolution.resolve(null, ImageReaderMode.PAGED)
        )
    }

    @Test
    fun thePlanDefaultIsWebtoon() {
        assertEquals(ImageReaderMode.WEBTOON, MangaModeResolution.DEFAULT)
    }

    // ── Progress fractions: paged mode ──────────────────────

    @Test
    fun pagedFractionRunsFirstPageZeroToLastPageOne() {
        assertEquals(0f, MangaProgress.fractionForPage(0, 40), 1e-6f)
        assertEquals(1f, MangaProgress.fractionForPage(39, 40), 1e-6f)
        assertEquals(0.5f, MangaProgress.fractionForPage(5, 11), 1e-6f)
    }

    @Test
    fun pagedFractionClampsOutOfRangePages() {
        assertEquals(1f, MangaProgress.fractionForPage(99, 40), 1e-6f)
        assertEquals(0f, MangaProgress.fractionForPage(-3, 40), 1e-6f)
    }

    @Test
    fun singlePageChapterSitsAtZero() {
        assertEquals(0f, MangaProgress.fractionForPage(0, 1), 1e-6f)
        assertEquals(0, MangaProgress.pageForFraction(0.9f, 1))
        assertEquals(0f, MangaProgress.fractionForPagePosition(0.7f, 1), 1e-6f)
    }

    @Test
    fun fractionAndPageRoundTripExactly() {
        for (count in listOf(2, 3, 40, 101)) {
            for (page in 0 until count) {
                assertEquals(
                    "count=$count page=$page",
                    page,
                    MangaProgress.pageForFraction(
                        MangaProgress.fractionForPage(page, count), count
                    )
                )
            }
        }
    }

    // ── Progress fractions: webtoon mode ────────────────────

    @Test
    fun webtoonFractionRidesTheFractionalPagePosition() {
        assertEquals(0f, MangaProgress.fractionForPagePosition(0f, 11), 1e-6f)
        assertEquals(0.25f, MangaProgress.fractionForPagePosition(2.5f, 11), 1e-6f)
        assertEquals(1f, MangaProgress.fractionForPagePosition(10f, 11), 1e-6f)
        // Scrolled past the end clamps to the last page's mark.
        assertEquals(1f, MangaProgress.fractionForPagePosition(25f, 11), 1e-6f)
    }

    // ── Restore ─────────────────────────────────────────────

    @Test
    fun restoreHonoursTheHintFirst() {
        assertEquals(
            39,
            MangaProgress.restorePage(PageHint.LAST, 0, 0f, 40)
        )
        assertEquals(
            0,
            MangaProgress.restorePage(PageHint.FIRST, 12, 0.5f, 40)
        )
    }

    @Test
    fun restoreUsesTheStoredPageWhenOneWasStored() {
        // An in-range stored page wins over the fraction (the
        // fraction is the carrier today — the offset slot
        // clamps against the empty chapter text — but the
        // preference costs nothing and stays exact if a
        // writer ever stores the page there).
        assertEquals(
            7,
            MangaProgress.restorePage(PageHint.OFFSET, 7, 0.9f, 40)
        )
    }

    @Test
    fun restoreFallsBackToTheFractionWithoutAStoredPage() {
        assertEquals(
            20,
            MangaProgress.restorePage(PageHint.OFFSET, 0, 0.5f, 41)
        )
        // An out-of-range stored page is not trusted either.
        assertEquals(
            0,
            MangaProgress.restorePage(PageHint.OFFSET, 999, 0f, 40)
        )
    }

    @Test
    fun pageLineIsOneBasedAndPlain() {
        assertEquals("Page 12 of 40", MangaProgress.pageLine(11, 40))
        assertEquals("Page 1 of 1", MangaProgress.pageLine(0, 1))
        assertEquals("", MangaProgress.pageLine(0, 0))
    }

    // ── Preload window ──────────────────────────────────────

    @Test
    fun webtoonWindowWarmsThreeAhead() {
        assertEquals(
            listOf(1, 2, 3),
            MangaPreload.window(0, 40, ImageReaderMode.WEBTOON, lowRam = false)
        )
        assertEquals(
            listOf(38, 39),
            MangaPreload.window(37, 40, ImageReaderMode.WEBTOON, lowRam = false)
        )
        assertEquals(
            emptyList<Int>(),
            MangaPreload.window(39, 40, ImageReaderMode.WEBTOON, lowRam = false)
        )
    }

    @Test
    fun webtoonWindowShrinksOnTheLowRamTier() {
        assertEquals(
            listOf(1, 2),
            MangaPreload.window(0, 40, ImageReaderMode.WEBTOON, lowRam = true)
        )
    }

    @Test
    fun pagedWindowWarmsNextAndPrevious() {
        assertEquals(
            listOf(4, 6),
            MangaPreload.window(5, 40, ImageReaderMode.PAGED, lowRam = false)
        )
        assertEquals(
            listOf(1),
            MangaPreload.window(0, 40, ImageReaderMode.PAGED, lowRam = false)
        )
        assertEquals(
            listOf(38),
            MangaPreload.window(39, 40, ImageReaderMode.PAGED, lowRam = false)
        )
    }

    @Test
    fun pagedWindowOnLowRamWarmsNextOnly() {
        assertEquals(
            listOf(6),
            MangaPreload.window(5, 40, ImageReaderMode.PAGED, lowRam = true)
        )
        assertEquals(
            emptyList<Int>(),
            MangaPreload.window(0, 1, ImageReaderMode.PAGED, lowRam = true)
        )
    }

    // ── Page source: local file vs URL ──────────────────────

    @Test
    fun downloadedPageReadsFromItsLocalFile() {
        val page = ChapterPage(url = "https://cdn.example/p1.jpg", localFile = "p1.jpg")
        assertEquals(
            MangaPageSource.Local("p1.jpg"),
            MangaPageSources.sourceFor(page)
        )
        assertFalse(MangaPageSources.needsNetwork(page))
    }

    @Test
    fun onlinePageRidesItsUrl() {
        val page = ChapterPage(url = "https://cdn.example/p2.jpg")
        assertEquals(
            MangaPageSource.Remote("https://cdn.example/p2.jpg"),
            MangaPageSources.sourceFor(page)
        )
        assertTrue(MangaPageSources.needsNetwork(page))
    }

    @Test
    fun blankLocalFileNameFallsBackToTheUrl() {
        val page = ChapterPage(url = "https://cdn.example/p3.jpg", localFile = "  ")
        assertEquals(
            MangaPageSource.Remote("https://cdn.example/p3.jpg"),
            MangaPageSources.sourceFor(page)
        )
    }

    // ── Decode width ────────────────────────────────────────

    @Test
    fun lowRamDecodesAtScreenWidth() {
        assertEquals(1080, MangaDecode.widthPx(lowRam = true, screenWidthPx = 1080))
        assertEquals(720, MangaDecode.widthPx(lowRam = true, screenWidthPx = 720))
        // An unreadable width never decodes at zero.
        assertEquals(1, MangaDecode.widthPx(lowRam = true, screenWidthPx = 0))
    }

    @Test
    fun standardTierKeepsTheReaderWideCap() {
        assertEquals(
            ReaderImageDecode.MAX_WIDTH_PX,
            MangaDecode.widthPx(lowRam = false, screenWidthPx = 1080)
        )
    }

    // ── Webtoon reading position ────────────────────────────

    @Test
    fun webtoonPositionPicksTheMostVisiblePage() {
        // Page 0 is 90% scrolled past; page 1 fills the view.
        val (page, position) = webtoonReadingPosition(
            items = listOf(
                VisiblePageItem(index = 0, offset = -900, size = 1000),
                VisiblePageItem(index = 1, offset = 100, size = 1000)
            ),
            viewportStart = 0,
            viewportEnd = 1000,
            pageCount = 5
        )
        assertEquals(1, page)
        assertEquals(1f, position, 1e-6f)
    }

    @Test
    fun webtoonPositionCountsThePartAlreadyScrolledPast() {
        val (page, position) = webtoonReadingPosition(
            items = listOf(
                VisiblePageItem(index = 0, offset = -500, size = 1000),
                VisiblePageItem(index = 1, offset = 500, size = 1000)
            ),
            viewportStart = 0,
            viewportEnd = 1000,
            pageCount = 5
        )
        // Equal visible shares: the earlier page wins the tie,
        // and its position is half-scrolled.
        assertEquals(0, page)
        assertEquals(0.5f, position, 1e-6f)
    }

    @Test
    fun webtoonPositionNeverCountsTheEndCard() {
        val (page, _) = webtoonReadingPosition(
            items = listOf(
                VisiblePageItem(index = 4, offset = 0, size = 800),
                VisiblePageItem(index = 5, offset = 800, size = 300)
            ),
            viewportStart = 0,
            viewportEnd = 1100,
            pageCount = 5
        )
        assertEquals(4, page)
    }

    @Test
    fun webtoonPositionOfAnEmptySnapshotIsTheFirstPage() {
        assertEquals(
            0 to 0f,
            webtoonReadingPosition(emptyList(), 0, 1000, pageCount = 5)
        )
    }
}
