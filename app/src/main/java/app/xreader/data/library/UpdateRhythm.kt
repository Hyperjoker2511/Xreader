package app.xreader.data.library

import kotlin.math.roundToLong

/**
 * "Next chapter expected…" (v1.0.2, sweep addendum — Mihon's
 * Upcoming screen idea, per-book): estimate a serial's rhythm from
 * the gaps between its recent chapter timestamps and project the
 * next one. An estimate, labelled as one — the book page shows the
 * plain-words line and never promises.
 *
 * Needs at least 4 timestamps (3 intervals); the median of the last
 * 8 intervals is the rhythm (a median shrugs off the one hiatus).
 */
object UpdateRhythm {

    data class Estimate(
        val medianIntervalMillis: Long,
        val nextExpectedMillis: Long,
        val overdue: Boolean
    )

    fun estimate(chapterTimestamps: List<Long>, nowMillis: Long): Estimate? {
        val stamps = chapterTimestamps.filter { it > 0 }.sorted()
        if (stamps.size < 4) return null
        val intervals = stamps.zipWithNext { a, b -> b - a }
            .filter { it > 0 }
            .takeLast(8)
        if (intervals.size < 3) return null
        val median = intervals.sorted().let { sorted ->
            val mid = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[mid]
            else (sorted[mid - 1] + sorted[mid]) / 2
        }
        if (median <= 0) return null
        val next = stamps.last() + median
        return Estimate(
            medianIntervalMillis = median,
            nextExpectedMillis = next,
            overdue = next < nowMillis
        )
    }

    /** The book-page line: "Next chapter expected today / tomorrow /
     * in 3 days / — it is overdue" style, in plain words. */
    fun label(estimate: Estimate, nowMillis: Long): String {
        val dayMs = 24.0 * 60 * 60 * 1000
        val days = ((estimate.nextExpectedMillis - nowMillis) / dayMs).roundToLong()
        return when {
            estimate.overdue -> "Next chapter expected by now — the source may be late"
            days <= 0L -> "Next chapter expected today"
            days == 1L -> "Next chapter expected tomorrow"
            else -> "Next chapter expected in $days days"
        }
    }
}
