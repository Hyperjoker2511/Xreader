package app.xreader.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.0.4 (D13 extras): the Today's-goal bar fraction — pure math
 * behind the animated progress bar on the Profile screen.
 */
class DailyGoalFractionTest {

    @Test
    fun `fraction is today's share of the goal`() {
        assertEquals(0f, dailyGoalFraction(0, 20), 0f)
        assertEquals(0.5f, dailyGoalFraction(10, 20), 0f)
        assertEquals(0.65f, dailyGoalFraction(13, 20), 0.001f)
        assertEquals(1f, dailyGoalFraction(20, 20), 0f)
    }

    @Test
    fun `over-goal days clamp at full and bad goals read as zero`() {
        assertEquals(1f, dailyGoalFraction(45, 20), 0f)
        assertEquals(0f, dailyGoalFraction(10, 0), 0f)
        assertEquals(0f, dailyGoalFraction(10, -5), 0f)
        assertEquals(0f, dailyGoalFraction(-3, 20), 0f)
    }
}
