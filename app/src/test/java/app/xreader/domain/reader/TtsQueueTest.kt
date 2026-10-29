package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsQueueTest {

    private fun queueOf(count: Int): TtsQueue =
        TtsQueue((0 until count).map { Sentence(it * 10, it * 10 + 5) })

    @Test
    fun utteranceDoneAdvancesThenFinishes() {
        val queue = queueOf(3)
        queue.play()
        assertEquals(0, queue.index)
        assertEquals(Sentence(10, 15), queue.onUtteranceDone())
        assertEquals(Sentence(20, 25), queue.onUtteranceDone())
        assertNull(queue.onUtteranceDone())
        assertTrue(queue.finished)
        assertFalse(queue.playing)
    }

    @Test
    fun skipControlsClampAtEnds() {
        val queue = queueOf(2)
        assertEquals(Sentence(0, 5), queue.skipPrevious()) // already first
        assertEquals(Sentence(10, 15), queue.skipNext())
        assertEquals(Sentence(10, 15), queue.skipNext()) // already last
        assertEquals(Sentence(0, 5), queue.skipPrevious())
    }

    @Test
    fun jumpToClampsAndClearsFinished() {
        val queue = queueOf(3)
        queue.jumpTo(99)
        assertEquals(2, queue.index)
        queue.onUtteranceDone()
        assertTrue(queue.finished)
        queue.jumpTo(0)
        assertFalse(queue.finished)
        assertEquals(0, queue.index)
    }

    @Test
    fun emptyQueueIsFinishedAndSilent() {
        val queue = TtsQueue(emptyList())
        assertTrue(queue.finished)
        queue.play()
        assertFalse(queue.playing)
        assertNull(queue.current)
        assertNull(queue.onUtteranceDone())
    }

    @Test
    fun sleepTimerMinutesDeadline() {
        var now = 1_000_000L
        val timer = SleepTimer { now }
        timer.arm(SleepMode.AfterMinutes(20))
        assertEquals(20 * 60_000L, timer.remainingMs())
        now += 19 * 60_000L
        assertFalse(timer.isExpired())
        assertEquals(60_000L, timer.remainingMs())
        now += 60_000L
        assertTrue(timer.isExpired())
        assertEquals(0L, timer.remainingMs())
    }

    @Test
    fun sleepTimerEndOfChapterHasNoDeadline() {
        var now = 0L
        val timer = SleepTimer { now }
        timer.arm(SleepMode.EndOfChapter)
        now += 10_000_000L
        assertFalse(timer.isExpired())
        assertNull(timer.remainingMs())
        assertTrue(timer.chapterEnded())
        timer.disarm()
        assertFalse(timer.chapterEnded())
    }

    @Test
    fun sentenceTimingEstimatorScalesWithRateAndScript() {
        val latin = "word ".repeat(170) // 850 chars
        val at1x = SentenceTimingEstimator.estimateMillis(latin, 1f)
        assertTrue("expected ~60s at 1x, was $at1x", at1x in 55_000..65_000)
        val at2x = SentenceTimingEstimator.estimateMillis(latin, 2f)
        assertTrue(at2x < at1x)
        val cjk = "天地玄黄".repeat(100) // 400 chars
        val cjkMs = SentenceTimingEstimator.estimateMillis(cjk, 1f)
        assertTrue("expected ~60s of CJK, was $cjkMs", cjkMs in 55_000..65_000)
        assertEquals(0L, SentenceTimingEstimator.estimateMillis("", 1f))
    }
}
