package app.xreader.ui.reader

import app.xreader.data.db.Chapter
import app.xreader.domain.reader.WpmEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 (Wave 3) pins for the reader's pure policies:
 * [PrefetchPolicy] (the user-specified comic near-end warm),
 * [ProgressSaveCoalescer] (audit #2's progress-write coalescing)
 * and [ChapterPositionIndex] (the O(1) chapter index + suffix
 * estimates that replaced the per-settle book scan).
 */
class ReaderPoliciesTest {

    // ── PrefetchPolicy: every branch ───────────────────────

    private fun warm(
        isImageChapter: Boolean = true,
        fraction: Float = 0.85f,
        hasNext: Boolean = true,
        nextStored: Boolean = false,
        inFlight: Boolean = false
    ) = PrefetchPolicy.shouldWarmNext(
        isImageChapter, fraction, hasNext, nextStored, inFlight
    )

    @Test
    fun imageChapterNearEndWithUnstoredNextWarms() {
        assertTrue(warm())
    }

    @Test
    fun thresholdIsInclusiveAtPointSeven() {
        assertTrue(warm(fraction = PrefetchPolicy.NEAR_END_FRACTION))
        assertTrue(warm(fraction = 0.7f))
    }

    @Test
    fun belowThresholdDoesNotWarm() {
        assertFalse(warm(fraction = 0.69f))
        assertFalse(warm(fraction = 0f))
    }

    @Test
    fun textChapterNeverWarmsThroughThisPath() {
        assertFalse(warm(isImageChapter = false, fraction = 0.99f))
    }

    @Test
    fun lastChapterDoesNotWarm() {
        assertFalse(warm(hasNext = false))
    }

    @Test
    fun alreadyStoredNextDoesNotWarm() {
        assertFalse(warm(nextStored = true))
    }

    @Test
    fun warmInFlightDoesNotDoubleWarm() {
        assertFalse(warm(inFlight = true))
    }

    // ── ProgressSaveCoalescer ──────────────────────────────

    @Test
    fun coalescerIsCleanUntilFirstPosition() {
        val coalescer = ProgressSaveCoalescer()
        assertFalse(coalescer.isDirty)
        assertNull(coalescer.dueMs())
    }

    @Test
    fun firstPositionIsDueOnSettle() {
        val coalescer = ProgressSaveCoalescer()
        val due = coalescer.onPosition(1_000L)
        assertEquals(1_000L + ProgressSaveCoalescer.SETTLE_MS, due)
        assertTrue(coalescer.isDirty)
    }

    @Test
    fun settleShortlyAfterAPersistWaitsForTheCoarseInterval() {
        val coalescer = ProgressSaveCoalescer()
        coalescer.onPosition(0L)
        coalescer.markPersisted(2_500L)
        // A screenful pause 500 ms after the last write must NOT
        // write again at its settle (2.5 s + a screenful was the
        // old every-~1.5 s behaviour); the write is due one coarse
        // interval after the previous one.
        val due = coalescer.onPosition(3_000L)
        assertEquals(2_500L + ProgressSaveCoalescer.COARSE_MS, due)
    }

    @Test
    fun continuousScrollingIsDueByTheHardCap() {
        val coalescer = ProgressSaveCoalescer()
        coalescer.onPosition(0L)
        coalescer.markPersisted(2_500L)
        coalescer.onPosition(3_000L) // dirty since 3_000
        // Positions keep arriving; the due time never runs past
        // dirtySince + coarse, so a write lands even mid-scroll.
        val due = coalescer.onPosition(22_000L)
        assertEquals(3_000L + ProgressSaveCoalescer.COARSE_MS, due)
    }

    @Test
    fun settleLongAfterLastPersistIsPromptAgain() {
        val coalescer = ProgressSaveCoalescer()
        coalescer.onPosition(0L)
        coalescer.markPersisted(2_500L)
        val due = coalescer.onPosition(50_000L)
        assertEquals(50_000L + ProgressSaveCoalescer.SETTLE_MS, due)
    }

    @Test
    fun markPersistedClearsTheDirtyState() {
        val coalescer = ProgressSaveCoalescer()
        coalescer.onPosition(1_000L)
        coalescer.markPersisted(2_500L)
        assertFalse(coalescer.isDirty)
        assertNull(coalescer.dueMs())
        assertEquals(2_500L, coalescer.lastPersistMs)
    }

    // ── ChapterPositionIndex ───────────────────────────────

    private fun chapter(
        id: Long,
        index: Int,
        wordCount: Int = 0,
        read: Boolean = false
    ) = Chapter(
        id = id,
        bookId = 1,
        index = index,
        title = "Chapter ${index + 1}",
        wordCount = wordCount,
        read = read,
        updatedAt = 0
    )

    @Test
    fun indexOfChapterIsById() {
        val index = ChapterPositionIndex.build(
            listOf(chapter(10, 0), chapter(11, 1), chapter(12, 2))
        )
        assertEquals(0, index.indexOfChapter(10))
        assertEquals(2, index.indexOfChapter(12))
        assertNull(index.indexOfChapter(99))
        assertNull(index.indexOfChapter(null))
        assertEquals(3, index.size)
    }

    @Test
    fun remainingMinutesMatchesTheEstimatorForEveryPosition() {
        val chapters = listOf(
            chapter(1, 0, wordCount = 1800, read = true),
            chapter(2, 1, wordCount = 2400),
            chapter(3, 2, wordCount = 0),
            chapter(4, 3, wordCount = 1200, read = true),
            chapter(5, 4, wordCount = 0),
            chapter(6, 5, wordCount = 3000)
        )
        val index = ChapterPositionIndex.build(chapters)
        for (wpm in listOf(120.0, 180.0, 257.5)) {
            for (position in -1..chapters.lastIndex) {
                val expected = WpmEstimator.estimateRemainingMinutes(
                    chapters.drop(position + 1).map {
                        WpmEstimator.ChapterWords(it.read, it.wordCount)
                    },
                    wpm
                ).first
                assertEquals(
                    "position $position @ $wpm wpm",
                    expected,
                    index.remainingMinutesAfter(position, wpm)
                )
            }
            // Past the end there is nothing left, however the
            // estimator is asked.
            assertEquals(0, index.remainingMinutesAfter(chapters.lastIndex, wpm))
        }
    }

    @Test
    fun remainingMinutesUsesTheBookAverageForUnmeasuredChapters() {
        val chapters = listOf(
            chapter(1, 0, wordCount = 2000, read = true),
            chapter(2, 1, wordCount = 2000),
            chapter(3, 2, wordCount = 0)
        )
        val index = ChapterPositionIndex.build(chapters)
        // After ch.1: ch.2 contributes its measured 2000; ch.3 is
        // unmeasured and counts at the suffix average (2000).
        assertEquals(20, index.remainingMinutesAfter(0, 200.0))
    }

    @Test
    fun remainingMinutesFallsBackWhenNothingIsMeasured() {
        val index = ChapterPositionIndex.build(
            listOf(chapter(1, 0), chapter(2, 1))
        )
        // Two unread, unmeasured chapters at the fallback size.
        assertEquals(
            2 * WpmEstimator.FALLBACK_CHAPTER_WORDS / 200,
            index.remainingMinutesAfter(-1, 200.0)
        )
    }

    @Test
    fun emptyIndexIsHarmless() {
        assertEquals(0, ChapterPositionIndex.EMPTY.size)
        assertEquals(0, ChapterPositionIndex.EMPTY.remainingMinutesAfter(0, 180.0))
        assertNull(ChapterPositionIndex.EMPTY.indexOfChapter(1))
    }

    @Test
    fun nonPositiveWpmGivesNoEstimate() {
        val index = ChapterPositionIndex.build(
            listOf(chapter(1, 0, wordCount = 2000))
        )
        assertEquals(0, index.remainingMinutesAfter(-1, 0.0))
    }
}
