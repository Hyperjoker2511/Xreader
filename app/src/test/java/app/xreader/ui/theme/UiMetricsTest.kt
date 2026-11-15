package app.xreader.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adaptive metrics (Patch 5): the tier boundaries and every
 * value in the table are pinned exactly — a tier value may only
 * change by deliberately changing this test too. Widths 360 /
 * 480 / 800 stand for one phone inside each tier.
 */
class UiMetricsTest {

    @Test
    fun `dock overlay clearance is the bar zone without the inset`() {
        // Visible-layer fix round (§A): floating — the bar
        // (64.dp) + its 10.dp top pad + 24.dp bottom pad; docked
        // — the flush bar alone. The window's navigation inset
        // is the shell's to add, never baked in here.
        assertEquals(98.dp, dockOverlayClearance(docked = false))
        assertEquals(64.dp, dockOverlayClearance(docked = true))
    }

    @Test
    fun `effective bottom padding keeps the larger side`() {
        // The overlay clearance wins when it is larger…
        assertEquals(110.dp, effectiveBottomPadding(88.dp, 110.dp))
        // …the standing padding wins when IT is larger (no
        // surface ever loses padding it has today)…
        assertEquals(120.dp, effectiveBottomPadding(120.dp, 110.dp))
        // …and a zero clearance (dock hidden / rail shell) is a
        // byte-identical no-op.
        assertEquals(88.dp, effectiveBottomPadding(88.dp, 0.dp))
    }

    @Test
    fun `tier boundaries are exact`() {
        assertEquals(UiTier.COMPACT, uiTierFor(399))
        assertEquals(UiTier.REGULAR, uiTierFor(400))
        assertEquals(UiTier.REGULAR, uiTierFor(600))
        assertEquals(UiTier.EXPANDED, uiTierFor(601))
        // The width-based factory agrees at the same boundaries.
        assertEquals(UiTier.COMPACT, uiMetricsFor(399).tier)
        assertEquals(UiTier.REGULAR, uiMetricsFor(400).tier)
        assertEquals(UiTier.REGULAR, uiMetricsFor(600).tier)
        assertEquals(UiTier.EXPANDED, uiMetricsFor(601).tier)
    }

    @Test
    fun `compact row is pinned`() {
        val m = uiMetricsFor(360)
        assertEquals(UiTier.COMPACT, m.tier)
        assertEquals(12.dp, m.screenPadding)
        assertEquals(14.dp, m.cardPadding)
        assertEquals(3, m.gridColumns)
        assertEquals(96.dp, m.cardMinHeight)
        assertEquals(56.dp, m.dockHeight)
        assertEquals(21.sp, m.titleSize)
        assertEquals(14.sp, m.bodySize)
        assertEquals(36.dp, m.iconBadge)
        assertEquals(16.dp, m.sectionGap)
        assertEquals(4.dp, m.topGap)
        assertEquals(24.dp, m.coverCorner)
    }

    @Test
    fun `regular row is pinned`() {
        val m = uiMetricsFor(480)
        assertEquals(UiTier.REGULAR, m.tier)
        assertEquals(16.dp, m.screenPadding)
        assertEquals(18.dp, m.cardPadding)
        assertEquals(4, m.gridColumns)
        assertEquals(104.dp, m.cardMinHeight)
        assertEquals(64.dp, m.dockHeight)
        assertEquals(24.sp, m.titleSize)
        assertEquals(15.sp, m.bodySize)
        assertEquals(40.dp, m.iconBadge)
        assertEquals(20.dp, m.sectionGap)
        assertEquals(6.dp, m.topGap)
        assertEquals(24.dp, m.coverCorner)
    }

    @Test
    fun `expanded row is pinned`() {
        val m = uiMetricsFor(800)
        assertEquals(UiTier.EXPANDED, m.tier)
        assertEquals(24.dp, m.screenPadding)
        assertEquals(22.dp, m.cardPadding)
        assertEquals(6, m.gridColumns)
        assertEquals(112.dp, m.cardMinHeight)
        assertEquals(68.dp, m.dockHeight)
        assertEquals(28.sp, m.titleSize)
        assertEquals(16.sp, m.bodySize)
        assertEquals(44.dp, m.iconBadge)
        assertEquals(24.dp, m.sectionGap)
        assertEquals(8.dp, m.topGap)
        assertEquals(24.dp, m.coverCorner)
    }

    @Test
    fun `no value shrinks as the tier grows and the corner is constant`() {
        val compact = uiMetricsFor(UiTier.COMPACT)
        val regular = uiMetricsFor(UiTier.REGULAR)
        val expanded = uiMetricsFor(UiTier.EXPANDED)
        val rows = listOf(compact, regular, expanded)
        // Every structural value is non-decreasing across tiers.
        rows.map { it.screenPadding }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.cardPadding }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.gridColumns }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.cardMinHeight }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.dockHeight }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.titleSize }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.bodySize }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.iconBadge }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.sectionGap }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        rows.map { it.topGap }.zipWithNext()
            .forEach { (a, b) -> assertTrue(a <= b) }
        // The one constant: the card corner never varies by tier.
        assertTrue(rows.all { it.coverCorner == 24.dp })
    }

    @Test
    fun `bottom content padding is exact with and without the dock`() {
        // (dockHeight + 24) + topGap with the dock; 16 + topGap without.
        assertEquals(84.dp, bottomContentPadding(uiMetricsFor(UiTier.COMPACT), true))
        assertEquals(20.dp, bottomContentPadding(uiMetricsFor(UiTier.COMPACT), false))
        assertEquals(94.dp, bottomContentPadding(uiMetricsFor(UiTier.REGULAR), true))
        assertEquals(22.dp, bottomContentPadding(uiMetricsFor(UiTier.REGULAR), false))
        assertEquals(100.dp, bottomContentPadding(uiMetricsFor(UiTier.EXPANDED), true))
        assertEquals(24.dp, bottomContentPadding(uiMetricsFor(UiTier.EXPANDED), false))
    }

    @Test
    fun `hidden dock padding is always smaller than visible dock padding`() {
        UiTier.entries.forEach { tier ->
            val metrics = uiMetricsFor(tier)
            assertTrue(
                bottomContentPadding(metrics, false) <
                    bottomContentPadding(metrics, true)
            )
        }
    }
}
