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

    /** One chapter's contribution to a remaining-time estimate. */
    data class ChapterWords(val read: Boolean, val wordCount: Int)

    companion object {
        const val DEFAULT_WPM = 180.0
        const val MIN_WPM = 60.0
        const val MAX_WPM = 900.0

        /** Typical chapter length when a book has NO measured
         * chapter to average (a completely un-downloaded book). */
        const val FALLBACK_CHAPTER_WORDS = 2000

        /**
         * v1.0.6.3 (P3): the honest remaining-minutes estimate.
         * Chapters carry a word count only once downloaded; an
         * unread chapter without one used to count as ZERO words,
         * so a 759-chapter book claimed "≈21 min left" (the two
         * downloaded chapters only). Now an unmeasured unread
         * chapter counts at THIS book's own average measured
         * chapter length — or [FALLBACK_CHAPTER_WORDS] when the
         * book has no measured chapter at all. Returns the
         * minutes and whether any chapter had to be estimated
         * (the ≈ in the rendered line covers the honesty).
         */
        fun estimateRemainingMinutes(
            chapters: List<ChapterWords>,
            wpm: Double
        ): Pair<Int, Boolean> {
            if (wpm <= 0.0) return 0 to false
            val measured = chapters.filter { it.wordCount > 0 }
            val average = if (measured.isNotEmpty()) {
                measured.sumOf { it.wordCount } / measured.size
            } else {
                FALLBACK_CHAPTER_WORDS
            }
            var estimated = false
            var unreadWords = 0
            for (chapter in chapters) {
                if (chapter.read) continue
                if (chapter.wordCount > 0) {
                    unreadWords += chapter.wordCount
                } else {
                    unreadWords += average
                    estimated = true
                }
            }
            if (unreadWords <= 0) return 0 to false
            val minutes = ceil(unreadWords / wpm).toInt().coerceAtLeast(1)
            return minutes to estimated
        }

        /** "≈12 min", "≈1 h", "≈3 h 5 min", "Done". */
        fun formatMinutes(minutes: Int): String = when {
            minutes <= 0 -> "Done"
            minutes < 60 -> "≈$minutes min"
            minutes % 60 == 0 -> "≈${minutes / 60} h"
            else -> "≈${minutes / 60} h ${minutes % 60} min"
        }
    }
}
