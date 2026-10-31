package app.xreader.ui.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R08 shell invariants: the locked five-tab line-up, the constant
 * 64 dp bar, and pill geometry that fits five labelled tabs on
 * phone widths (the geometry is the ReTranslate v1.1.1 lesson —
 * pinned so a future tab change can't silently break the bar).
 */
class ShellNavTest {

    @Test
    fun `the five tabs are the locked line-up in order`() {
        // v1.1.0 (2026-10-31): pin updated DELIBERATELY with the
        // user's tab rename (design Q4) — Browse is labelled
        // "Explore" and Me is labelled "Profile"; routes below
        // are unchanged.
        assertEquals(
            listOf("Library", "Explore", "History", "Downloads", "Profile"),
            Tab.entries.map { it.label }
        )
        assertEquals(
            listOf("library", "browse", "history", "downloads", "me"),
            Tab.entries.map { it.route }
        )
    }

    @Test
    fun `bar height stays the constant 64dp`() {
        assertEquals(64.dp, BarHeight)
    }

    @Test
    fun `five-tab pill fits and stays inside phone widths`() {
        listOf(960, 1080, 1233, 1440).forEach { widthPx ->
            val pill = NavGeometry.pillWidthPx(widthPx, Tab.entries.size, 72)
            assertTrue("width $widthPx pill $pill", pill > 0)
            Tab.entries.indices.forEach { index ->
                val x = NavGeometry.pillTargetXPx(
                    widthPx, Tab.entries.size, index, 72
                )
                assertTrue(
                    "width $widthPx tab $index x=$x pill=$pill",
                    x >= 0 && x + pill <= widthPx
                )
            }
        }
    }

    @Test
    fun `rail pill fits five slots on tablet heights`() {
        val slotsHeight = 228 * Tab.entries.size
        val pill = NavGeometry.pillHeightRailPx(slotsHeight, Tab.entries.size, 48)
        assertTrue(pill > 0)
        Tab.entries.indices.forEach { index ->
            val y = NavGeometry.pillTargetYRailPx(
                slotsHeight, Tab.entries.size, index, 48
            )
            assertTrue(
                "rail tab $index y=$y pill=$pill",
                y >= 0 && y + pill <= slotsHeight
            )
        }
    }
}
