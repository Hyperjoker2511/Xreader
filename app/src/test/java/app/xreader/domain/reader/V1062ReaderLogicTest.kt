package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.6.2 (F7): the reader's position naming and centre-tap
 * rules, pinned as pure logic.
 */
class V1062ReaderLogicTest {

    @Test fun streamPositionNamesTheChapterWhoseTextIsOnScreen() {
        // The screenshot case: index 0 is an imported front-matter
        // page (zero blocks), index 1 is the book's Chapter 1.
        val counts = mapOf(0 to 0, 1 to 42, 2 to 40)
        assertEquals(1, StreamReadingPosition.resolve(0, counts))
        assertEquals(1, StreamReadingPosition.resolve(1, counts))
        assertEquals(2, StreamReadingPosition.resolve(2, counts))
    }

    @Test fun streamPositionNeverSkipsALiveChapter() {
        // A negative count means "not loaded yet" (a chapter the
        // stream is fetching live) — it names the position itself.
        val counts = mapOf(0 to 10, 1 to -1, 2 to 10)
        assertEquals(1, StreamReadingPosition.resolve(1, counts))
    }

    @Test fun streamPositionFallsBackToThePreviousRealChapter() {
        // Trailing empties (a book ending with a blank page): the
        // position belongs to the last chapter with text.
        val counts = mapOf(0 to 10, 1 to 12, 2 to 0)
        assertEquals(1, StreamReadingPosition.resolve(2, counts))
    }

    @Test fun streamPositionKeepsTheInputWhenEverythingIsEmpty() {
        val counts = mapOf(0 to 0, 1 to 0)
        assertEquals(0, StreamReadingPosition.resolve(0, counts))
    }

    @Test fun centreBandIsTheMiddleFortyPercent() {
        assertTrue(CentreTap.isCentre(0.5f))
        assertTrue(CentreTap.isCentre(0.30f))
        assertTrue(CentreTap.isCentre(0.70f))
        assertFalse(CentreTap.isCentre(0.29f))
        assertFalse(CentreTap.isCentre(0.71f))
    }

    @Test fun aTapEndsBeforeTheLongPressTimeout() {
        assertTrue(CentreTap.isTap(120L, 500L))
        assertTrue(CentreTap.isTap(0L, 500L))
        assertFalse(CentreTap.isTap(500L, 500L))
        assertFalse(CentreTap.isTap(900L, 500L))
    }
}
