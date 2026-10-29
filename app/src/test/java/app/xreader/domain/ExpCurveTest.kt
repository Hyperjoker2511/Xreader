package app.xreader.domain

import app.xreader.domain.game.ExpAwards
import app.xreader.domain.game.ExpCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 gate (PLAN §8): the EXP curve. Spec formula EXP =
 * 500·L^1.8 read as the per-level span for FROM level L.
 * span(1) = 500·1 = 500; span(2) = 500·2^1.8 ≈ 1741.1;
 * span(10) = 500·10^1.8 ≈ 31,547.9.
 */
class ExpCurveTest {

    @Test
    fun `span values match the spec formula`() {
        assertEquals(500L, ExpCurve.spanFor(1))
        assertEquals(1741L, ExpCurve.spanFor(2))
        assertEquals(31548L, ExpCurve.spanFor(10))
    }

    @Test
    fun `cumulative thresholds derive from the spans`() {
        assertEquals(0L, ExpCurve.cumulativeFor(1))
        assertEquals(500L, ExpCurve.cumulativeFor(2))
        assertEquals(2241L, ExpCurve.cumulativeFor(3))
    }

    @Test
    fun `level for exp respects the thresholds`() {
        assertEquals(1, ExpCurve.levelFor(0))
        assertEquals(1, ExpCurve.levelFor(499))
        assertEquals(2, ExpCurve.levelFor(500))
        assertEquals(2, ExpCurve.levelFor(2240))
        assertEquals(3, ExpCurve.levelFor(2241))
    }

    @Test
    fun `curve is monotonic and capped at 100`() {
        var prev = -1L
        for (level in 1..ExpCurve.MAX_LEVEL) {
            val c = ExpCurve.cumulativeFor(level)
            assertTrue("level $level cumulative must grow", c > prev)
            prev = c
        }
        assertEquals(ExpCurve.MAX_LEVEL, ExpCurve.levelFor(Long.MAX_VALUE / 2))
    }

    @Test
    fun `progress reports the fraction and the remaining exp`() {
        // 1000 EXP: level 2 (500..2241), 500 into the level,
        // span(2) = 1741 → 1241 still to go.
        val progress = ExpCurve.progress(1000)
        assertEquals(2, progress.level)
        assertEquals(500L, progress.intoLevel)
        assertEquals(1741L, progress.spanToNext)
        assertEquals(1241L, ExpCurve.expToNextLevel(1000))
        assertTrue(progress.fraction > 0.28f && progress.fraction < 0.29f)
    }

    @Test
    fun `award math honours the per-hour cap and the focus multiplier`() {
        // 90 active minutes inside one wall hour → only 40 minutes
        // are creditable (the anti-farm cap is per started hour).
        assertEquals(
            40L * ExpAwards.EXP_PER_MINUTE,
            ExpAwards.sessionExp(
                activeMinutes = 90, wallMinutes = 59,
                chaptersFinished = 0, focusBonus = false
            )
        )
        // 30 minutes + 2 chapters, no focus: 120 + 50.
        assertEquals(
            170L,
            ExpAwards.sessionExp(30, 30, 2, focusBonus = false)
        )
        // The uninterrupted-focus flag multiplies the whole award.
        assertEquals(
            255L,
            ExpAwards.sessionExp(30, 30, 2, focusBonus = true)
        )
        // Nothing read, nothing earned — never negative.
        assertEquals(0L, ExpAwards.sessionExp(0, 30, 0, focusBonus = false))
        assertEquals(0L, ExpAwards.creditableMinutes(0, 30).toLong())
    }

    @Test
    fun `titles cover every level 1 to 100`() {
        for (level in 1..100) {
            assertTrue(ExpCurve.titleFor(level).isNotBlank())
        }
        assertEquals("Novice Initiate", ExpCurve.titleFor(1))
        assertEquals("The Apex Literati", ExpCurve.titleFor(100))
        assertEquals("The Apex Literati", ExpCurve.titleFor(95))
    }
}
