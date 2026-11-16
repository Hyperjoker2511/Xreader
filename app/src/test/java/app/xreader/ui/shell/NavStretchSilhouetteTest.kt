package app.xreader.ui.shell

import app.xreader.data.library.NavIndicatorStyle
import app.xreader.data.prefs.PerfTier
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silhouette-escape test (nav engine round,
 * SPEC-NAV-ENGINE-2026-11-16 §5): the proof the rebuilt
 * stretch ESCAPES the settled pill's outline on a 1-slot
 * jump — the property the shipped melt lacked (its neck and
 * trail never left the blob's stadium outline, so the melt
 * read as a plain slide and the owner ruled "Gooye not
 * working").
 *
 * The model is dp-scale: slot width 94 on a pitch of 100 —
 * slot 0 centred at 50 (settled rect [3, 97]), slot 1 centred
 * at 150 (settled rect [103, 197]), the slots' boundary at
 * x = 100. Edge positions come from
 * [NavIndicatorLogic.stretchEdgesAt] — the pure model of the
 * host's two Animatables, built on the engine's own
 * springPositionAt — so what is pinned here is the exact
 * silhouette the painters draw, not a paraphrase of it.
 */
class NavStretchSilhouetteTest {

    private val slotWidth = 94f
    private val centreA = 50f
    private val centreB = 150f
    private val boundary = 100f

    @Test
    fun `gooey stretch escapes the settled pill on a one slot jump`() {
        assertEscape(NavIndicatorStyle.GOOEY, centreA, centreB)
        assertEscape(NavIndicatorStyle.GOOEY, centreB, centreA)
    }

    @Test
    fun `worm stretch escapes the settled pill on a one slot jump`() {
        assertEscape(NavIndicatorStyle.WORM, centreA, centreB)
        assertEscape(NavIndicatorStyle.WORM, centreB, centreA)
    }

    private fun assertEscape(
        style: NavIndicatorStyle,
        from: Float,
        to: Float
    ) {
        val forward = to > from
        val half = slotWidth / 2f
        // The run starts exactly on the origin slot's rect…
        val (startLeft, startRight) = NavIndicatorLogic.stretchEdgesAt(
            style, PerfTier.FULL, from, to, slotWidth, 0.0
        )
        assertTrue(
            "$style start left $startLeft",
            abs(startLeft - (from - half)) < 0.01f
        )
        assertTrue(
            "$style start right $startRight",
            abs(startRight - (from + half)) < 0.01f
        )
        // …and lands exactly on the target slot's rect.
        val (endLeft, endRight) = NavIndicatorLogic.stretchEdgesAt(
            style, PerfTier.FULL, from, to, slotWidth, 2000.0
        )
        assertTrue(
            "$style end left $endLeft",
            abs(endLeft - (to - half)) < 1f
        )
        assertTrue(
            "$style end right $endRight",
            abs(endRight - (to + half)) < 1f
        )
        // Mid-trip — the spec's band is lead progress
        // p ∈ [0.35, 0.65]; take the first 5 ms sample at
        // p ≥ 0.5 and judge the silhouette there.
        var t = 0.0
        var judged = false
        while (t <= 2000.0) {
            val (left, right) = NavIndicatorLogic.stretchEdgesAt(
                style, PerfTier.FULL, from, to, slotWidth, t
            )
            val leadProgress = if (forward) {
                (right - (from + half)) / (to - from)
            } else {
                ((from - half) - left) / (from - to)
            }
            if (leadProgress >= 0.5f) {
                if (forward) {
                    // The leading edge has crossed into the
                    // target slot…
                    assertTrue(
                        "$style lead $right crossed the boundary",
                        right > boundary
                    )
                    // …while the trailing edge is still inside
                    // the origin slot — the pill STRADDLES.
                    assertTrue(
                        "$style trail $left still in the origin slot",
                        left < boundary
                    )
                    // The outline escapes the settled target
                    // rect by at least 4 on the trailing side.
                    assertTrue(
                        "$style escape ${(to - half) - left} below 4",
                        (to - half) - left >= 4f
                    )
                } else {
                    assertTrue(
                        "$style lead $left crossed the boundary",
                        left < boundary
                    )
                    assertTrue(
                        "$style trail $right still in the origin slot",
                        right > boundary
                    )
                    assertTrue(
                        "$style escape ${right - (to + half)} below 4",
                        right - (to + half) >= 4f
                    )
                }
                // And the frame is visibly wider than one slot.
                assertTrue(
                    "$style width ${right - left} not stretched",
                    right - left > slotWidth + 4f
                )
                judged = true
                break
            }
            t += 5.0
        }
        assertTrue("$style never reached mid-trip", judged)
    }
}
