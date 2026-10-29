package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WPM estimator, bionic transform, focus timer, session tracker,
 * reader-config validation — the smaller pure engines of §6. */
class ReaderPureLogicTest {

    // -- WPM ------------------------------------------------------------------

    @Test
    fun wpmColdStartUsesDefault() {
        val estimator = WpmEstimator()
        assertFalse(estimator.hasData)
        assertEquals(WpmEstimator.DEFAULT_WPM, estimator.wpm(), 0.001)
        assertEquals(1, estimator.minutesLeft(180))
        assertEquals(0, estimator.minutesLeft(0))
    }

    @Test
    fun wpmAveragesSamplesAndClamps() {
        val estimator = WpmEstimator()
        estimator.addSample(words = 400, activeSeconds = 120.0) // 200 wpm
        estimator.addSample(words = 100, activeSeconds = 60.0) // window total 500/3min
        assertEquals(500.0 / 3.0, estimator.wpm(), 0.01)
        assertEquals(3, estimator.minutesLeft(450)) // ceil(450 / 166.7) = 3
        val absurd = WpmEstimator()
        absurd.addSample(words = 100_000, activeSeconds = 10.0)
        assertEquals(WpmEstimator.MAX_WPM, absurd.wpm(), 0.001)
        // Junk samples are refused.
        estimator.addSample(words = 0, activeSeconds = 10.0)
        estimator.addSample(words = 10, activeSeconds = 0.0)
        assertEquals(500.0 / 3.0, estimator.wpm(), 0.01)
    }

    @Test
    fun minutesFormatting() {
        assertEquals("Done", WpmEstimator.formatMinutes(0))
        assertEquals("≈12 min", WpmEstimator.formatMinutes(12))
        assertEquals("≈1 h", WpmEstimator.formatMinutes(60))
        assertEquals("≈3 h 5 min", WpmEstimator.formatMinutes(185))
    }

    // -- Bionic ---------------------------------------------------------------

    @Test
    fun bionicBoldsFirstHalfOfLetters() {
        assertEquals("swo" to "rd", BionicReading.splitToken("sword"))
        assertEquals("a" to "", BionicReading.splitToken("a"))
        assertEquals("ab" to "cd", BionicReading.splitToken("abcd"))
        assertEquals("" to "123", BionicReading.splitToken("123"))
        assertEquals("\"aft" to "er\"", BionicReading.splitToken("\"after\""))
    }

    @Test
    fun bionicSegmentsReassembleExactly() {
        val text = "The ancient mist clung to the peaks."
        val segments = BionicReading.segments(text)
        assertEquals(text, segments.joinToString("") { it.first })
        assertTrue(segments.any { it.second }) // something is bold
        assertTrue(BionicReading.segments("").isEmpty())
    }

    // -- Focus timer ------------------------------------------------------------

    @Test
    fun focusTimerCompletesUninterrupted() {
        var now = 0L
        val timer = FocusTimer { now }
        timer.start(25, Soundscape.RAIN)
        assertEquals(FocusState.RUNNING, timer.state)
        now = 10 * 60_000L
        assertFalse(timer.tick())
        assertEquals(15 * 60_000L, timer.remainingMs())
        now = 25 * 60_000L
        assertTrue(timer.tick())
        assertEquals(FocusState.DONE, timer.state)
        assertTrue(timer.completedUninterrupted)
        assertFalse(timer.tick()) // fires exactly once
    }

    @Test
    fun focusTimerPauseBreaksUninterruptedAndFreezesTime() {
        var now = 0L
        val timer = FocusTimer { now }
        timer.start(25)
        now = 5 * 60_000L
        timer.pause()
        now = 60 * 60_000L // an hour passes paused: no progress
        assertFalse(timer.tick())
        assertEquals(20 * 60_000L, timer.remainingMs())
        timer.resume()
        now += 20 * 60_000L
        assertTrue(timer.tick())
        assertFalse(timer.completedUninterrupted)
    }

    @Test
    fun focusTimerStopReturnsFocusedMinutes() {
        var now = 0L
        val timer = FocusTimer { now }
        timer.start(50, Soundscape.FOREST)
        now = 12 * 60_000L + 30_000L
        assertEquals(12, timer.stop())
        assertEquals(FocusState.IDLE, timer.state)
    }

    // -- Session tracker ---------------------------------------------------------

    @Test
    fun sessionCountsOnlyActiveTime() {
        var now = 0L
        val tracker = SessionTracker(clock = { now }, idleTimeoutMs = 120_000L)
        tracker.begin()
        now = 60_000L
        tracker.onActivity()
        assertEquals(1, tracker.activeMinutes())
        // Two minutes of silence: accrual caps at lastActivity + timeout.
        now = 60_000L + 120_000L + 600_000L
        assertEquals(3, tracker.activeMinutes()) // 60s + 120s grace
        tracker.onActivity() // reading again
        now += 60_000L
        assertEquals(4, tracker.activeMinutes())
    }

    @Test
    fun sessionEndResetsAndReports() {
        var now = 1_000L
        val tracker = SessionTracker(clock = { now })
        tracker.begin()
        // Five minutes of reading, turning a page every minute.
        repeat(5) {
            now += 60_000L
            tracker.onActivity()
        }
        tracker.addWords(900)
        tracker.addChaptersFinished(2)
        val snapshot = tracker.end()
        assertEquals(5, snapshot.activeMinutes)
        assertEquals(900L, snapshot.wordsRead)
        assertEquals(2, snapshot.chaptersFinished)
        assertFalse(tracker.isRunning)
        assertEquals(0L, tracker.wordsRead)
    }

    // -- Reader validation ---------------------------------------------------------

    @Test
    fun validationClampsEverything() {
        val wild = ReaderConfig(
            fontSizeSp = 99f, lineHeightMultiplier = 0.1f,
            marginHorizontalDp = -5f, warmLight = 3f,
            speechRate = Float.NaN, brightness = 7f, paletteName = ""
        )
        val sane = ReaderValidation.validate(wild)
        assertEquals(ReaderValidation.FONT_SIZE_RANGE.endInclusive, sane.fontSizeSp)
        assertEquals(ReaderValidation.LINE_HEIGHT_RANGE.start, sane.lineHeightMultiplier)
        assertEquals(0f, sane.marginHorizontalDp)
        assertEquals(ReaderValidation.WARM_LIGHT_RANGE.endInclusive, sane.warmLight)
        assertEquals(1f, sane.speechRate)
        assertEquals(1f, sane.brightness)
        assertEquals("AMOLED", sane.paletteName)
    }

    @Test
    fun validationKeepsGoodValues() {
        val good = ReaderConfig(fontSizeSp = 21f, brightness = null)
        assertEquals(good, ReaderValidation.validate(good))
    }
}
