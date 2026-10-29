package app.xreader.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the bounded-geometry invariant (ReTranslate v1.1.1 lesson): for
 * ANY bar/rail size, item count, index or animated position, the pill
 * rect computed by [NavGeometry] stays fully inside its bar.
 */
class NavGeometryTest {

    @Test
    fun `phone pill fits a realistic bar`() {
        // 360dp-wide phone at 3x density, 64dp-tall bar.
        val barWidth = 1080
        val barHeight = 192
        val pillWidth = NavGeometry.pillWidthPx(barWidth, 4, 18)
        val pillHeight = NavGeometry.pillHeightPx(barHeight, 18)
        assertEquals(234, pillWidth)
        assertEquals(156, pillHeight)
        assertTrue(pillHeight < barHeight)
        for (index in 0..3) {
            val x = NavGeometry.pillTargetXPx(barWidth, 4, index, 18)
            assertTrue("index $index starts inside", x >= 0)
            assertTrue("index $index ends inside", x + pillWidth <= barWidth)
        }
    }

    @Test
    fun `pill never escapes for any inputs`() {
        val widths = listOf(0, 1, 100, 333, 1080, 2560)
        val heights = listOf(0, 1, 64, 192, 2210)
        for (w in widths) for (h in heights) for (count in 1..6) {
            val pillW = NavGeometry.pillWidthPx(w, count, 18)
            val pillH = NavGeometry.pillHeightPx(h, 18)
            assertTrue(pillW in 0..maxOf(w, 0))
            assertTrue(pillH in 0..maxOf(h, 0))
            for (index in -1..8) {
                val x = NavGeometry.pillTargetXPx(w, count, index, 18)
                assertTrue("x=$x w=$w pillW=$pillW", x >= 0 && x + pillW <= maxOf(w, pillW))
                val clamped = NavGeometry.clampPillXPx(x.toFloat() + 500f, w, pillW)
                assertTrue(clamped >= 0f && clamped <= maxOf(w - pillW, 0).toFloat())
            }
        }
    }

    @Test
    fun `spring overshoot is clamped every frame`() {
        // A spring travelling from slot 0 to slot 3 can overshoot past
        // the last slot; the clamp keeps the pill inside regardless.
        val barWidth = 1080
        val pillWidth = NavGeometry.pillWidthPx(barWidth, 4, 18)
        assertEquals(846f, NavGeometry.clampPillXPx(5000f, barWidth, pillWidth), 0f)
        assertEquals(0f, NavGeometry.clampPillXPx(-500f, barWidth, pillWidth), 0f)
    }

    @Test
    fun `rail pill fits a realistic rail`() {
        // 104dp-wide rail at 3x, four 76dp slots.
        val railWidth = 312
        val slotsHeight = 912
        val pillWidth = NavGeometry.pillWidthRailPx(railWidth, 30)
        val pillHeight = NavGeometry.pillHeightRailPx(slotsHeight, 4, 24)
        assertEquals(252, pillWidth)
        assertEquals(180, pillHeight)
        for (index in 0..3) {
            val y = NavGeometry.pillTargetYRailPx(slotsHeight, 4, index, 24)
            assertTrue(y >= 0 && y + pillHeight <= slotsHeight)
        }
        assertEquals(732f, NavGeometry.clampPillYPx(5000f, slotsHeight, pillHeight), 0f)
        assertEquals(0f, NavGeometry.clampPillYPx(-10f, slotsHeight, pillHeight), 0f)
    }

    @Test
    fun `degenerate sizes are zero, never negative`() {
        assertEquals(0, NavGeometry.pillWidthPx(0, 4, 18))
        assertEquals(0, NavGeometry.pillHeightPx(0, 18))
        assertEquals(0, NavGeometry.pillTargetXPx(0, 4, 2, 18))
        assertEquals(0, NavGeometry.pillWidthRailPx(0, 30))
        assertEquals(0, NavGeometry.pillHeightRailPx(0, 4, 24))
        assertEquals(0, NavGeometry.slotWidthPx(100, 0))
        assertEquals(0, NavGeometry.slotHeightPx(100, 0))
    }
}
