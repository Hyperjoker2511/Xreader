package app.xreader.domain.game

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Streaks + the GitHub-style heatmap (PLAN §8), as pure functions
 * over local calendar dates. A "reading day" is a day with any
 * recorded reading minutes; the daily GOAL is tracked separately
 * (it drives the goal ring + goal EXP, not the flame).
 *
 * Day boundaries are the caller's local timezone by construction:
 * `DailyActivity` rows are keyed by local ISO date (SessionRecorder
 * writes them in the device zone), and [today] is injected so the
 * midnight edge is testable instead of hoped about.
 */
object Streaks {

    /** Consecutive reading days ending today — or ending yesterday
     * when today hasn't started yet (a streak isn't lost at 00:01;
     * it's lost when a full day passes with no reading). */
    fun currentStreak(activeDates: Set<LocalDate>, today: LocalDate): Int {
        var cursor = when {
            activeDates.contains(today) -> today
            activeDates.contains(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        while (activeDates.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** The longest run of consecutive reading days, ever. */
    fun bestStreak(activeDates: Set<LocalDate>): Int {
        if (activeDates.isEmpty()) return 0
        var best = 0
        for (date in activeDates) {
            if (activeDates.contains(date.minusDays(1))) continue // not a run start
            var length = 0
            var cursor = date
            while (activeDates.contains(cursor)) {
                length++
                cursor = cursor.plusDays(1)
            }
            best = maxOf(best, length)
        }
        return best
    }

    /** One heatmap cell. [level] 0 = no reading; 1–3 = read, rising
     * with minutes against the daily goal; 3 also marks goal-met. */
    data class HeatDay(
        val date: LocalDate,
        val minutesRead: Int,
        val goalMet: Boolean,
        val level: Int,
        val inFuture: Boolean
    )

    /**
     * [weeks] columns of 7 days (Monday-first), the last column
     * containing [today] — the grid the Profile screen draws.
     * [minutesByDate] holds recorded minutes per local date and
     * [goalMinutes] scales the intensity levels.
     */
    fun heatmap(
        minutesByDate: Map<LocalDate, Int>,
        goalMetDates: Set<LocalDate>,
        today: LocalDate,
        goalMinutes: Int,
        weeks: Int = 26
    ): List<List<HeatDay>> {
        val thisMonday = today.with(DayOfWeek.MONDAY)
        val start = thisMonday.minusWeeks((weeks - 1).toLong())
        return (0 until weeks).map { column ->
            (0 until 7).map { row ->
                val date = start.plusDays(column * 7L + row)
                val minutes = minutesByDate[date] ?: 0
                val goalMet = goalMetDates.contains(date)
                val level = when {
                    minutes <= 0 -> 0
                    goalMet -> 3
                    goalMinutes > 0 && minutes * 2 >= goalMinutes -> 2
                    else -> 1
                }
                HeatDay(
                    date = date,
                    minutesRead = minutes,
                    goalMet = goalMet,
                    level = level,
                    inFuture = date.isAfter(today)
                )
            }
        }
    }
}
