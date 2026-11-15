package app.xreader.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elastic Slider pure-math proofs (PATCH-SPEC-ELASTIC-SLIDER §8):
 * the rubber band never exceeds its cap at either end and is
 * monotonic with diminishing returns; the velocity stretch is
 * clamped to 1f..1.30f; position↔value round-trips and clamps.
 */
class ElasticSliderMathTest {

    private val cap = 40f // px, arbitrary stand-in for 10.dp at a density

    @Test
    fun rubberBandIsZeroAtZero() {
        assertEquals(0f, rubberBandOverflow(0f, cap), 0.0001f)
    }

    @Test
    fun rubberBandNeverExceedsCapAtEitherEnd() {
        for (raw in listOf(1f, 10f, 40f, 400f, 10_000f, 1_000_000f, Float.MAX_VALUE)) {
            val pos = rubberBandOverflow(raw, cap)
            val neg = rubberBandOverflow(-raw, cap)
            assertTrue("pos $pos must be within cap for raw $raw", pos in 0f..cap)
            assertTrue("neg $neg must be within cap for raw $raw", neg in -cap..0f)
        }
        assertEquals(cap, rubberBandOverflow(Float.POSITIVE_INFINITY, cap), 0.0001f)
        assertEquals(-cap, rubberBandOverflow(Float.NEGATIVE_INFINITY, cap), 0.0001f)
    }

    @Test
    fun rubberBandIsMonotonic() {
        var previous = 0f
        var raw = 0f
        while (raw <= 500f) {
            val current = rubberBandOverflow(raw, cap)
            assertTrue("overflow must not decrease at raw=$raw", current >= previous)
            previous = current
            raw += 7.5f
        }
    }

    @Test
    fun rubberBandHasDiminishingReturns() {
        // Doubling the raw overshoot less than doubles the visual
        // displacement once past the cap's neighbourhood.
        val single = rubberBandOverflow(cap * 2f, cap)
        val doubled = rubberBandOverflow(cap * 4f, cap)
        assertTrue(doubled < single * 2f)
        assertTrue(doubled > single)
    }

    @Test
    fun rubberBandMirrorsAcrossEnds() {
        for (raw in listOf(3f, 25f, 90f, 1_000f)) {
            assertEquals(
                rubberBandOverflow(raw, cap),
                -rubberBandOverflow(-raw, cap),
                0.0001f
            )
        }
    }

    @Test
    fun rubberBandWithNoCapIsZero() {
        assertEquals(0f, rubberBandOverflow(100f, 0f), 0.0001f)
        assertEquals(0f, rubberBandOverflow(Float.NaN, cap), 0.0001f)
    }

    @Test
    fun stretchIsOneAtRest() {
        assertEquals(1f, stretchForVelocity(0f), 0.0001f)
        assertEquals(1f, stretchForVelocity(Float.NaN), 0.0001f)
    }

    @Test
    fun stretchClampsAtExtremeVelocity() {
        for (v in listOf(10_000f, 1_000_000f, Float.MAX_VALUE)) {
            val s = stretchForVelocity(v)
            assertTrue("stretch $s must stay within 1..1.30 for v=$v", s in 1f..1.30f)
        }
        assertEquals(1.30f, stretchForVelocity(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun stretchIsMonotonicAndIgnoresDirection() {
        var previous = 1f
        var v = 0f
        while (v <= 20_000f) {
            val s = stretchForVelocity(v)
            assertTrue("stretch must not decrease at v=$v", s >= previous)
            assertEquals(s, stretchForVelocity(-v), 0.0001f)
            previous = s
            v += 250f
        }
    }

    @Test
    fun stretchAtReferenceVelocityIsBetweenRestAndCap() {
        val s = stretchForVelocity(2400f)
        assertTrue(s > 1f && s < 1.30f)
    }

    @Test
    fun fractionPositionRoundTrips() {
        val range = 10f..210f
        val width = 500f
        for (x in listOf(0f, 1f, 125f, 250f, 499f, 500f)) {
            val value = fractionForPosition(x, width, range)
            assertEquals(x, positionForValue(value, width, range), 0.01f)
        }
        for (value in listOf(10f, 60f, 110f, 210f)) {
            val x = positionForValue(value, width, range)
            assertEquals(value, fractionForPosition(x, width, range), 0.01f)
        }
    }

    @Test
    fun positionBeyondEitherEndMapsToClampedEndValue() {
        val range = 0f..100f
        val width = 400f
        assertEquals(0f, fractionForPosition(-50f, width, range), 0.0001f)
        assertEquals(100f, fractionForPosition(width + 50f, width, range), 0.0001f)
        assertEquals(0f, fractionForPosition(-1_000f, width, range), 0.0001f)
        assertEquals(100f, fractionForPosition(1_000f, width, range), 0.0001f)
    }

    @Test
    fun valueBeyondRangeMapsToClampedEndPosition() {
        val range = 0f..100f
        val width = 400f
        assertEquals(0f, positionForValue(-5f, width, range), 0.0001f)
        assertEquals(width, positionForValue(105f, width, range), 0.0001f)
    }

    @Test
    fun fractionMidpointIsRangeMidpoint() {
        val range = 20f..60f
        assertEquals(40f, fractionForPosition(200f, 400f, range), 0.0001f)
        assertEquals(200f, positionForValue(40f, 400f, range), 0.0001f)
    }

    @Test
    fun degenerateInputsStaySafe() {
        val range = 0f..1f
        assertEquals(0f, fractionForPosition(10f, 0f, range), 0.0001f)
        assertEquals(0f, positionForValue(0.5f, 0f, range), 0.0001f)
        assertEquals(0f, fractionForPosition(Float.NaN, 100f, range), 0.0001f)
    }
}
