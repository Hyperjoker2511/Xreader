package app.xreader.domain.game

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * What reading is worth (PLAN §8). Pure arithmetic over a finished
 * session's numbers — [app.xreader.data.game.ExpService] applies it
 * exactly once per session (the session row's `expProcessed` flag is
 * the idempotency key), so these functions never need to defend
 * against being run twice.
 *
 * Anti-farming, per the plan: active minutes are the tracker's own
 * idle-capped numbers (§6), and EXP only ever credits
 * [MINUTES_CREDITED_PER_HOUR] of them per wall-clock hour — leaving
 * the reader open, or auto-scrolling through the night, plateaus.
 */
object ExpAwards {

    const val EXP_PER_MINUTE = 4L
    const val MINUTES_CREDITED_PER_HOUR = 40
    const val EXP_PER_CHAPTER = 25L
    const val EXP_BOOK_FINISHED = 200L
    const val EXP_DAILY_GOAL = 50L
    const val FOCUS_MULTIPLIER = 1.5

    /** Minutes of a session that earn EXP: the active minutes,
     * capped per wall-clock hour of the session. */
    fun creditableMinutes(activeMinutes: Int, wallMinutes: Int): Int {
        if (activeMinutes <= 0) return 0
        val wall = maxOf(wallMinutes, 1)
        val hours = ceil(wall / 60.0).toInt().coerceAtLeast(1)
        val cap = hours * MINUTES_CREDITED_PER_HOUR
        return minOf(activeMinutes, cap)
    }

    /**
     * EXP for one reading session. The focus multiplier applies to
     * the whole session award (minutes + chapters) — the plan's
     * "focus bonus ×1.5 on EXP from uninterrupted Pomodoro sessions",
     * where [focusBonus] is the Phase 4 timer's pause-aware
     * uninterrupted-completion flag.
     */
    fun sessionExp(
        activeMinutes: Int,
        wallMinutes: Int,
        chaptersFinished: Int,
        focusBonus: Boolean
    ): Long {
        val minutes = creditableMinutes(activeMinutes, wallMinutes)
        val base = minutes * EXP_PER_MINUTE +
            chaptersFinished.coerceAtLeast(0) * EXP_PER_CHAPTER
        if (base <= 0L) return 0L
        return if (focusBonus) {
            (base * FOCUS_MULTIPLIER).roundToLong()
        } else {
            base
        }
    }
}
