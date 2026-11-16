package app.xreader.ui.shell

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The travel-choreography round's pure laws
 * (SPEC-NAV-TRAVEL-WAVE-2026-11-16):
 *
 * - §B.1 label coverage: a slot's label opacity is a pure
 *   function of how much the travelling marker currently
 *   covers that slot — [NavIndicatorLogic.slotCoverage] and
 *   [NavIndicatorLogic.labelAlphaForCoverage]. The dock reads
 *   them in the layout / draw phases; what is pinned here is
 *   the law itself: rest is exact (1 on the selected slot, 0
 *   elsewhere), departure falls monotonically, arrival reaches
 *   full only at a full rest-width overlap.
 * - §C WAVE geometry: [NavIndicatorLogic.waveDip] and
 *   [NavIndicatorLogic.waveCircle] — the dip rests exactly on
 *   the slot centres, and the circle is cradled in the dip
 *   (crown in the carved void, bottom inside the bar), its
 *   centre-x the dip's centre: one parameter, so dip, circle
 *   and icon can never decouple.
 */
class NavLabelCoverageWaveTest {

    private val pitch = 100f
    private val restWidth = 94f

    // -- slotCoverage -------------------------------------------

    @Test
    fun `coverage at rest is exact on the selected slot and zero elsewhere`() {
        // The settled stretch marker in slot 0: [3, 97].
        assertEquals(
            1f,
            NavIndicatorLogic.slotCoverage(3f, 97f, 0f, pitch, restWidth),
            0.001f
        )
        assertEquals(
            0f,
            NavIndicatorLogic.slotCoverage(3f, 97f, pitch, 2 * pitch, restWidth),
            0.001f
        )
        // The settled centre-style marker (span = centre ±
        // pitch / 2, rest width = pitch) in slot 2.
        assertEquals(
            1f,
            NavIndicatorLogic.slotCoverage(
                200f, 300f, 200f, 300f, pitch
            ),
            0.001f
        )
        assertEquals(
            0f,
            NavIndicatorLogic.slotCoverage(
                200f, 300f, 100f, 200f, pitch
            ),
            0.001f
        )
    }

    @Test
    fun `departing coverage falls monotonically as the marker leaves`() {
        var previous = Float.MAX_VALUE
        var shift = 0f
        while (shift <= 100f) {
            val coverage = NavIndicatorLogic.slotCoverage(
                3f + shift, 97f + shift, 0f, pitch, restWidth
            )
            assertTrue(
                "coverage $coverage rose after $previous at shift $shift",
                coverage <= previous + 0.0001f
            )
            previous = coverage
            shift += 10f
        }
        // Fully gone once the marker's left edge passes the
        // slot's right edge.
        assertEquals(0f, previous, 0.001f)
    }

    @Test
    fun `arriving coverage reaches full only at a full rest width overlap`() {
        // Centre-style marker travelling into slot 1
        // ([100, 200], rest width = pitch): one pixel short of
        // settled, the overlap is 99 of 100 — not yet full.
        val almost = NavIndicatorLogic.slotCoverage(
            99f, 199f, 100f, 200f, pitch
        )
        assertTrue("almost $almost", almost < 1f)
        assertEquals(0.99f, almost, 0.001f)
        assertEquals(
            1f,
            NavIndicatorLogic.slotCoverage(
                100f, 200f, 100f, 200f, pitch
            ),
            0.001f
        )
    }

    @Test
    fun `a stretched span can fully cover a middle slot`() {
        // Mid-flight the stretch marker straddles two slots;
        // a span wider than the slot covers it completely
        // (clamped at 1, never above).
        assertEquals(
            1f,
            NavIndicatorLogic.slotCoverage(
                53f, 247f, 100f, 200f, restWidth
            ),
            0.001f
        )
    }

    @Test
    fun `coverage tolerates reversed edges and degenerate spans`() {
        assertEquals(
            1f,
            NavIndicatorLogic.slotCoverage(97f, 3f, pitch, 0f, restWidth),
            0.001f
        )
        // An unmeasured bar (zero rest width) covers nothing.
        assertEquals(
            0f,
            NavIndicatorLogic.slotCoverage(3f, 97f, 0f, pitch, 0f),
            0.001f
        )
        // A zero-width slot is covered by nothing.
        assertEquals(
            0f,
            NavIndicatorLogic.slotCoverage(3f, 97f, 50f, 50f, restWidth),
            0.001f
        )
    }

    // -- labelAlphaForCoverage -----------------------------------

    @Test
    fun `label alpha is the smoothstep of coverage`() {
        assertEquals(0f, NavIndicatorLogic.labelAlphaForCoverage(0f), 0.001f)
        assertEquals(1f, NavIndicatorLogic.labelAlphaForCoverage(1f), 0.001f)
        assertEquals(
            0.5f, NavIndicatorLogic.labelAlphaForCoverage(0.5f), 0.001f
        )
        // Monotonic over the whole range…
        var previous = -1f
        var c = 0f
        while (c <= 1.0001f) {
            val alpha = NavIndicatorLogic.labelAlphaForCoverage(c)
            assertTrue("alpha fell at $c", alpha >= previous)
            previous = alpha
            c += 0.05f
        }
        // …symmetric, so departing and arriving labels cross
        // at equal strength…
        c = 0f
        while (c <= 1.0001f) {
            val sum = NavIndicatorLogic.labelAlphaForCoverage(c) +
                NavIndicatorLogic.labelAlphaForCoverage(1f - c)
            assertTrue("asymmetric at $c: $sum", abs(sum - 1f) < 0.001f)
            c += 0.1f
        }
        // …and clamped outside the range.
        assertEquals(
            0f, NavIndicatorLogic.labelAlphaForCoverage(-0.5f), 0.001f
        )
        assertEquals(
            1f, NavIndicatorLogic.labelAlphaForCoverage(1.5f), 0.001f
        )
    }

    // -- WAVE geometry --------------------------------------------

    @Test
    fun `the wave dip rests exactly on every slot centre`() {
        // A five-slot bar, 1000 px wide on a 200 px pitch,
        // 128 px tall — floating and docked alike.
        for (docked in listOf(false, true)) {
            for (slot in 0..4) {
                val centre = 100f + slot * 200f
                val dip = NavIndicatorLogic.waveDip(
                    barWidthPx = 1000f,
                    barHeightPx = 128f,
                    centreXPx = centre,
                    pitchPx = 200f,
                    docked = docked
                )
                assertEquals(
                    "docked=$docked slot=$slot centre",
                    centre, dip.centreX, 0.001f
                )
                assertEquals(
                    0.34f * 200f, dip.halfWidth, 0.001f
                )
                assertEquals(
                    0.22f * 128f, dip.depth, 0.001f
                )
            }
        }
        // Mid-flight the dip's centre IS the travel centre.
        val mid = NavIndicatorLogic.waveDip(
            barWidthPx = 1000f, barHeightPx = 128f,
            centreXPx = 437f, pitchPx = 200f, docked = false
        )
        assertEquals(437f, mid.centreX, 0.001f)
    }

    @Test
    fun `the wave circle is cradled in the dip and stays inside the bar`() {
        // 64 dp bar at density 2: height 128 px, dip depth
        // 28.16 px; the shipped 40 dp circle is 80 px across.
        val dip = NavIndicatorLogic.waveDip(
            barWidthPx = 1000f, barHeightPx = 128f,
            centreXPx = 300f, pitchPx = 200f, docked = false
        )
        val circle = NavIndicatorLogic.waveCircle(
            centreXPx = dip.centreX,
            diameterPx = 80f,
            dipDepthPx = dip.depth
        )
        // One parameter: the circle's centre-x is the dip's.
        assertEquals(dip.centreX, circle.centreX, 0.001f)
        assertEquals(40f, circle.radius, 0.001f)
        // Cradled: the crown rises above the dip's floor line
        // (into the carved void)…
        assertTrue(
            "crown ${circle.centreY - circle.radius} not above " +
                "the dip floor ${dip.depth}",
            circle.centreY - circle.radius < dip.depth
        )
        // …the crown never leaves the bar's rect…
        assertTrue(
            "crown ${circle.centreY - circle.radius} above the bar",
            circle.centreY - circle.radius > 0f
        )
        // …and the bottom stays inside the bar.
        assertTrue(
            "bottom ${circle.centreY + circle.radius} past the bar",
            circle.centreY + circle.radius < 128f
        )
    }
}
