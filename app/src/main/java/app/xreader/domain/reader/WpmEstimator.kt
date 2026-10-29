package app.xreader.domain.reader

import kotlin.math.ceil

/**
 * Rolling reading-speed estimation (PLAN §6): words-per-minute from
 * recent reading samples (one sample = words read over an active-time
 * window, fed by the session tracker). The estimate is a plain
 * totals-over-window average — stable, explainable, and testable.
 * Cold start uses a conservative [DEFAULT_WPM] until real samples land.
 */
class WpmEstimator(private val maxSamples: Int = 24) {

    private data class Sample(val words: Int, val activeSeconds: Double)

    private val samples = ArrayDeque<Sample>()

    fun addSample(words: Int, activeSeconds: Double) {
        if (words <= 0 || activeSeconds <= 0.0) return
        samples.addLast(Sample(words, activeSeconds))
        while (samples.size > maxSamples) samples.removeFirst()
    }

    val hasData: Boolean get() = samples.isNotEmpty()

    fun wpm(): Double {
        val words = samples.sumOf { it.words }
        val seconds = samples.sumOf { it.activeSeconds }
        if (words == 0 || seconds <= 0.0) return DEFAULT_WPM
        val value = words / (seconds / 60.0)
        // Clamp to sane human reading speeds; a corrupt sample must
        // never produce "≈0 min left" nonsense.
        return value.coerceIn(MIN_WPM, MAX_WPM)
    }

    /** Whole minutes needed for [words] more words (ceil; 0 words → 0). */
    fun minutesLeft(words: Int): Int {
        if (words <= 0) return 0
        return ceil(words / wpm()).toInt().coerceAtLeast(1)
    }

    companion object {
        const val DEFAULT_WPM = 180.0
        const val MIN_WPM = 60.0
        const val MAX_WPM = 900.0

        /** "≈12 min", "≈1 h", "≈3 h 5 min", "Done". */
        fun formatMinutes(minutes: Int): String = when {
            minutes <= 0 -> "Done"
            minutes < 60 -> "≈$minutes min"
            minutes % 60 == 0 -> "≈${minutes / 60} h"
            else -> "≈${minutes / 60} h ${minutes % 60} min"
        }
    }
}
