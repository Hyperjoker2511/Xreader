package app.xreader.ui.shell

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gooey Nav geometry contract (Liquid Glass, piece 4): the melt
 * between dock slots is pure math, so its promises are pinned here —
 * endpoints exact in both directions, neck zero at both ends and
 * peaking mid-travel under its cap, travel strictly monotonic over
 * a 1000-step sweep, same-tab stillness, and retarget continuity
 * (a mid-flight re-base starts exactly where the blob is drawn).
 */
class GooeyGeometryTest {

    private val radius = 26f
    private val near = 100f
    private val far = 700f

    private fun assertClose(expected: Float, actual: Float, eps: Float = 0.001f) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    @Test
    fun `progress zero sits exactly on the start with no neck`() {
        val bridge = gooeyBridge(near, far, 0f, radius)
        assertEquals(near, bridge.mainBlobX)
        assertEquals(radius, bridge.mainBlobRadius)
        assertEquals(1f, bridge.mainBlobSquashY)
        assertEquals(0f, bridge.neckWidth)
        assertFalse(bridge.neckVisible)
    }

    @Test
    fun `progress one sits exactly on the target with no neck`() {
        val bridge = gooeyBridge(near, far, 1f, radius)
        assertEquals(far, bridge.mainBlobX)
        assertEquals(1f, bridge.mainBlobSquashY)
        assertEquals(0f, bridge.neckWidth)
        assertFalse(bridge.neckVisible)
        assertEquals(0f, bridge.trailRadius)
    }

    @Test
    fun `endpoints are exact travelling backwards too`() {
        assertEquals(far, gooeyBridge(far, near, 0f, radius).mainBlobX)
        assertEquals(near, gooeyBridge(far, near, 1f, radius).mainBlobX)
    }

    @Test
    fun `neck peaks at mid travel at 1_45 radius under the cap`() {
        // Visible-layer fix round (§C): the neck peak/cap were
        // raised (1.05/1.1 → 1.45/1.6) so the melt's stretch is
        // unmistakable at normal speed.
        val peak = gooeyBridge(near, far, 0.5f, radius)
        assertTrue(peak.neckVisible)
        assertClose(radius * 1.45f, peak.neckWidth, 0.0001f)
        assertTrue(peak.neckWidth <= radius * 1.6f)
    }

    @Test
    fun `neck peak sits at mid travel and never exceeds the cap`() {
        var peakAt = -1f
        var peakWidth = -1f
        for (i in 0..1000) {
            val p = i / 1000f
            val width = gooeyBridge(near, far, p, radius).neckWidth
            assertTrue("neck above cap at p=$p", width <= radius * 1.6f)
            if (width > peakWidth) {
                peakWidth = width
                peakAt = p
            }
        }
        assertClose(0.5f, peakAt, 0.001f)
    }

    @Test
    fun `travel is strictly monotonic forward over 1000 steps`() {
        var previous = gooeyBridge(near, far, 0f, radius).mainBlobX
        for (i in 1..1000) {
            val p = i / 1000f
            val x = gooeyBridge(near, far, p, radius).mainBlobX
            assertTrue("not increasing at p=$p: $previous -> $x", x > previous)
            previous = x
        }
        assertEquals(far, previous)
    }

    @Test
    fun `travel is strictly monotonic backward over 1000 steps`() {
        var previous = gooeyBridge(far, near, 0f, radius).mainBlobX
        for (i in 1..1000) {
            val p = i / 1000f
            val x = gooeyBridge(far, near, p, radius).mainBlobX
            assertTrue("not decreasing at p=$p: $previous -> $x", x < previous)
            previous = x
        }
        assertEquals(near, previous)
    }

    @Test
    fun `same tab yields no neck and no travel at any progress`() {
        for (i in 0..100) {
            val p = i / 100f
            val bridge = gooeyBridge(340f, 340f, p, radius)
            assertEquals(340f, bridge.mainBlobX)
            assertEquals(0f, bridge.neckWidth)
            assertFalse(bridge.neckVisible)
        }
    }

    @Test
    fun `trailing circle lags behind the blob and shrinks away`() {
        val mid = gooeyBridge(near, far, 0.5f, radius)
        // The trail rides the p² curve (0.25 of the way at p = 0.5)
        // while the blob has already eased to the midpoint.
        assertClose(near + (far - near) * 0.25f, mid.trailX)
        assertClose(radius * 0.90f * 0.5f, mid.trailRadius)
        assertTrue(mid.trailX < mid.mainBlobX)
        assertTrue(mid.trailRadius < mid.mainBlobRadius)
    }

    @Test
    fun `main blob squashes to 0_70 at mid travel and is round at the ends`() {
        // Fix round (§C): squash depth 0.14 → 0.30, so mid-travel
        // squash is 1 − 0.30 = 0.70.
        assertEquals(1f, gooeyBridge(near, far, 0f, radius).mainBlobSquashY)
        assertClose(0.70f, gooeyBridge(near, far, 0.5f, radius).mainBlobSquashY, 0.0001f)
        assertEquals(1f, gooeyBridge(near, far, 1f, radius).mainBlobSquashY)
    }

    @Test
    fun `retarget from the current visual position is continuous`() {
        val target = 340f
        for (step in 0..20) {
            val p = step / 20f
            val visual = gooeyBridge(near, far, p, radius).mainBlobX
            // Re-basing a run on the visual position starts exactly
            // there — the first frame after a retarget cannot pop.
            val rebased = gooeyBridge(visual, target, 0f, radius)
            assertEquals(visual, rebased.mainBlobX)
            // And one frame into the new run the blob has barely
            // moved — continuity, not just a matching start point.
            val nextFrame = gooeyBridge(visual, target, 0.001f, radius)
            assertTrue(
                "retarget jump at p=$p",
                abs(nextFrame.mainBlobX - visual) < 1f
            )
        }
    }
}
