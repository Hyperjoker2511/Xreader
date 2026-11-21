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

    // v1.7 OE-4 patch (Fix A — the EXP pace gate, owner ruling
    // 2026-11-21): EXP pays for reading, not movement. Words are
    // credited at at most a generous human ceiling of 500 wpm of
    // ACTIVE time; a chapter finish pays only when at least 80%
    // of its words were credited; a session that credits zero
    // words earns nothing at all, minutes included.
    const val WORDS_CREDITED_PER_MINUTE = 500
    const val CHAPTER_FINISHED_MIN_CREDITED_FRACTION = 0.8

    /** The words of a session that honestly count as read:
     * what was scrolled past, capped at what [activeMinutes]
     * at [WORDS_CREDITED_PER_MINUTE] could have covered. */
    fun creditedWords(wordsScrolled: Long, activeMinutes: Int): Long {
        if (wordsScrolled <= 0L || activeMinutes <= 0) return 0L
        val cap = activeMinutes.toLong() * WORDS_CREDITED_PER_MINUTE
        return minOf(wordsScrolled, cap)
    }

    /** Whether a crossed chapter counts as finished (+25):
     * at least [CHAPTER_FINISHED_MIN_CREDITED_FRACTION] of its
     * words must have been credited under the pace cap. A
     * chapter with no counted words (an image chapter) has
     * nothing to pace against and counts as before. */
    fun chapterCountsAsFinished(
        chapterWords: Long,
        creditedWordsForChapter: Long
    ): Boolean {
        if (chapterWords <= 0L) return true
        return creditedWordsForChapter.toDouble() >=
            chapterWords.toDouble() *
                CHAPTER_FINISHED_MIN_CREDITED_FRACTION
    }

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
        focusBonus: Boolean,
        // v1.7 OE-4 patch (Fix A, Q1): the session's credited
        // words under the pace cap. A session that credited zero
        // words read nothing — it earns zero, minutes included.
        // Production (ExpService.settle) always passes the real
        // figure; the default keeps pre-gate callers/tests on
        // their recorded expectations.
        creditedWords: Long = Long.MAX_VALUE
    ): Long {
        if (creditedWords <= 0L) return 0L
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
