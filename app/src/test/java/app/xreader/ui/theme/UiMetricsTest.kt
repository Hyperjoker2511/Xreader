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

    // ── App size scaling (v1.6.2) ────────────────────────────
    // scaledMetrics is the pure function the provider applies
    // to the tier row. The raw rows above stay pinned as they
    // are — scaling is a separate, separately-pinned layer.

    @Test
    fun `app size 1_00 is an exact identity on every tier`() {
        UiTier.entries.forEach { tier ->
            assertEquals(uiMetricsFor(tier), scaledMetrics(uiMetricsFor(tier), 1.0f))
        }
    }

    @Test
    fun `app size 0_70 shrinks every scaled field of the regular row`() {
        val m = scaledMetrics(uiMetricsFor(480), 0.70f)
        assertEquals(UiTier.REGULAR, m.tier)
        assertEquals(11.dp, m.screenPadding)   // 16 × 0.70 = 11.2 → 11
        assertEquals(13.dp, m.cardPadding)     // 18 × 0.70 = 12.6 → 13
        assertEquals(73.dp, m.cardMinHeight)   // 104 × 0.70 = 72.8 → 73
        assertEquals(45.dp, m.dockHeight)      // 64 × 0.70 = 44.8 → 45
        assertEquals(16.8f, m.titleSize.value, 0.01f)
        assertEquals(10.5f, m.bodySize.value, 0.01f)
        assertEquals(28.dp, m.iconBadge)       // 40 × 0.70 = 28
        assertEquals(14.dp, m.sectionGap)      // 20 × 0.70 = 14
        assertEquals(4.dp, m.topGap)           // 6 × 0.70 = 4.2 → 4
        // NOT scaled: the count and the one radius.
        assertEquals(4, m.gridColumns)
        assertEquals(24.dp, m.coverCorner)
    }

    @Test
    fun `app size 1_30 grows every scaled field of the regular row`() {
        val m = scaledMetrics(uiMetricsFor(480), 1.30f)
        assertEquals(UiTier.REGULAR, m.tier)
        assertEquals(21.dp, m.screenPadding)   // 16 × 1.30 = 20.8 → 21
        assertEquals(23.dp, m.cardPadding)     // 18 × 1.30 = 23.4 → 23
        assertEquals(135.dp, m.cardMinHeight)  // 104 × 1.30 = 135.2 → 135
        assertEquals(83.dp, m.dockHeight)      // 64 × 1.30 = 83.2 → 83
        assertEquals(31.2f, m.titleSize.value, 0.01f)
        assertEquals(19.5f, m.bodySize.value, 0.01f)
        assertEquals(52.dp, m.iconBadge)       // 40 × 1.30 = 52
        assertEquals(26.dp, m.sectionGap)      // 20 × 1.30 = 26
        assertEquals(8.dp, m.topGap)           // 6 × 1.30 = 7.8 → 8
        assertEquals(4, m.gridColumns)
        assertEquals(24.dp, m.coverCorner)
    }

    @Test
    fun `app size small preset scales the compact row`() {
        // The 2 GB phone shape: COMPACT base at Small (0.85).
        val m = scaledMetrics(uiMetricsFor(360), 0.85f)
        assertEquals(UiTier.COMPACT, m.tier)
        assertEquals(10.dp, m.screenPadding)   // 12 × 0.85 = 10.2 → 10
        assertEquals(12.dp, m.cardPadding)     // 14 × 0.85 = 11.9 → 12
        assertEquals(82.dp, m.cardMinHeight)   // 96 × 0.85 = 81.6 → 82
        assertEquals(48.dp, m.dockHeight)      // 56 × 0.85 = 47.6 → 48
        assertEquals(17.85f, m.titleSize.value, 0.01f)
        assertEquals(11.9f, m.bodySize.value, 0.01f)
        assertEquals(31.dp, m.iconBadge)       // 36 × 0.85 = 30.6 → 31
        assertEquals(14.dp, m.sectionGap)      // 16 × 0.85 = 13.6 → 14
        assertEquals(3.dp, m.topGap)           // 4 × 0.85 = 3.4 → 3
        assertEquals(3, m.gridColumns)
        assertEquals(24.dp, m.coverCorner)
    }

    @Test
    fun `grid columns and corner survive every factor on every tier`() {
        listOf(0.70f, 0.85f, 1.0f, 1.15f, 1.30f).forEach { factor ->
            UiTier.entries.forEach { tier ->
                val base = uiMetricsFor(tier)
                val scaled = scaledMetrics(base, factor)
                assertEquals(base.gridColumns, scaled.gridColumns)
                assertEquals(24.dp, scaled.coverCorner)
                assertEquals(base.tier, scaled.tier)
            }
        }
    }
}
