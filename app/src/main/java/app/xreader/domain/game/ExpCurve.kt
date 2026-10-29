package app.xreader.domain.game

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The level curve (SPEC E / PLAN §8): advancing from level L to L+1
 * costs **500 · L^1.8** EXP. Levels run 1–100; the cumulative
 * thresholds are derived from the formula, never hand-tabled, so the
 * curve the Profile screen shows is the curve the tests pin.
 */
object ExpCurve {

    const val MAX_LEVEL = 100
    const val BASE = 500.0
    const val EXPONENT = 1.8

    /** The 10 rank titles (PLAN §8, assumption A2 — user-renamable
     * copy, locked by the spec's endpoints: Novice Initiate → The
     * Apex Literati). Tier i covers levels (10i+1)..(10i+10). */
    val TITLES: List<String> = listOf(
        "Novice Initiate",
        "Page Turner",
        "Book Wanderer",
        "Story Seeker",
        "Chapter Sage",
        "Tale Weaver",
        "Lore Keeper",
        "Grand Bibliophile",
        "Mythic Reader",
        "The Apex Literati"
    )

    /** EXP needed to advance from [level] to level + 1. 0 at the cap. */
    fun spanFor(level: Int): Long {
        if (level >= MAX_LEVEL) return 0L
        if (level < 1) return spanFor(1)
        return (BASE * level.toDouble().pow(EXPONENT)).roundToLong()
    }

    private val cumulative: LongArray by lazy {
        val table = LongArray(MAX_LEVEL + 1)
        var sum = 0L
        for (level in 1..MAX_LEVEL) {
            table[level] = sum
            sum += spanFor(level)
        }
        table
    }

    /** Total EXP required to REACH [level] (level 1 = 0). */
    fun cumulativeFor(level: Int): Long {
        val clamped = level.coerceIn(1, MAX_LEVEL)
        return cumulative[clamped]
    }

    /** The level a given lifetime EXP total has reached. */
    fun levelFor(totalExp: Long): Int {
        if (totalExp <= 0L) return 1
        var level = 1
        while (level < MAX_LEVEL && cumulative[level + 1] <= totalExp) {
            level++
        }
        return level
    }

    /** Where [totalExp] sits inside its level, for rings and bars. */
    data class Progress(
        val level: Int,
        /** EXP earned inside the current level. */
        val intoLevel: Long,
        /** EXP the current level spans (0 at the cap). */
        val spanToNext: Long,
        /** intoLevel / spanToNext, 0..1 (1 at the cap). */
        val fraction: Float,
        val atMax: Boolean
    )

    fun progress(totalExp: Long): Progress {
        val level = levelFor(totalExp)
        val base = cumulativeFor(level)
        val span = spanFor(level)
        val into = (totalExp - base).coerceAtLeast(0L)
        return Progress(
            level = level,
            intoLevel = into,
            spanToNext = span,
            fraction = when {
                span <= 0L -> 1f
                else -> (into.toFloat() / span.toFloat()).coerceIn(0f, 1f)
            },
            atMax = level >= MAX_LEVEL
        )
    }

    /** EXP still needed to reach the next level (0 at the cap). */
    fun expToNextLevel(totalExp: Long): Long {
        val progress = progress(totalExp)
        if (progress.atMax) return 0L
        return (progress.spanToNext - progress.intoLevel).coerceAtLeast(0L)
    }

    /** The rank title for [level] (1–100). */
    fun titleFor(level: Int): String {
        val tier = ((level.coerceIn(1, MAX_LEVEL) - 1) / 10).coerceIn(0, TITLES.lastIndex)
        return TITLES[tier]
    }
}
