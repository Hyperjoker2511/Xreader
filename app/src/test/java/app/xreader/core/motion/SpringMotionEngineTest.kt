package app.xreader.core.motion

import app.xreader.core.motion.SpringMotionEngine.MassClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the SUBTLE contract from build-logs/MOTION-RESEARCH.md §4:
 * Xreader's springs are ColorOS-physics-derived, not ReTranslate's.
 * If someone "makes it bouncier" by lowering a damping ratio, these
 * tests fail — on purpose.
 */
class SpringMotionEngineTest {

    @Test
    fun `every profile is subtle - overshoot at or below 0_7 percent`() {
        SpringMotionEngine.profiles.forEach { profile ->
            val overshoot = SpringMotionEngine.overshootFraction(profile.dampingRatio)
            assertTrue(
                "${profile.name} overshoots ${(overshoot * 100)}% — over the subtle ceiling",
                overshoot <= 0.007
            )
        }
    }

    @Test
    fun `no profile is bouncier than ReTranslate spatial was`() {
        // ReTranslate's spatial spring: ζ = 0.5 → 16.3% overshoot.
        val retranslateSpatialOvershoot = SpringMotionEngine.overshootFraction(0.5f)
        SpringMotionEngine.profiles.forEach { profile ->
            assertTrue(
                SpringMotionEngine.overshootFraction(profile.dampingRatio) <
                    retranslateSpatialOvershoot / 10.0
            )
        }
    }

    @Test
    fun `every profile settles inside the calm band`() {
        SpringMotionEngine.profiles.forEach { profile ->
            val settle = SpringMotionEngine.settleMillis(profile)
            assertTrue(
                "${profile.name} settles in ${settle}ms — outside 140–340ms",
                settle in 140.0..340.0
            )
        }
    }

    @Test
    fun `overshoot math matches the research table`() {
        // Reference values computed in MOTION-RESEARCH §4.
        assertEquals(0.16303, SpringMotionEngine.overshootFraction(0.5f), 0.001)
        assertEquals(0.02838, SpringMotionEngine.overshootFraction(0.75f), 0.001)
        assertEquals(0.00297, SpringMotionEngine.overshootFraction(0.88f), 0.0005)
        assertEquals(0.0, SpringMotionEngine.overshootFraction(1.0f), 0.0)
        assertEquals(0.0, SpringMotionEngine.overshootFraction(1.4f), 0.0)
    }

    @Test
    fun `press scale is the spec squish`() {
        assertEquals(0.95f, SpringMotionEngine.PRESS_SCALE, 0.0f)
    }

    @Test
    fun `apple press keeps the user supplied numbers`() {
        // v1.0.4 (D5): the user's paste, verbatim — squash to 0.92,
        // spring stiffness 300 / dampingRatio 0.6.
        assertEquals(0.92f, SpringMotionEngine.APPLE_PRESS_SCALE, 0.0f)
        assertEquals(300f, SpringMotionEngine.ApplePress.stiffness, 0f)
        assertEquals(0.6f, SpringMotionEngine.ApplePress.dampingRatio, 0f)
        // ζ 0.6 overshoots ≈9.5% — the visible bounce IS the feature.
        assertEquals(
            0.0948, SpringMotionEngine.overshootFraction(0.6f), 0.001
        )
    }

    @Test
    fun `apple press stays out of the subtle family`() {
        // The subtle contract above governs `profiles`; the Apple
        // press is a separate user-directed family (like the
        // celebration profiles) and must never leak into it.
        assertFalse(SpringMotionEngine.profiles.contains(SpringMotionEngine.ApplePress))
        assertTrue(SpringMotionEngine.pressProfiles.contains(SpringMotionEngine.ApplePress))
        assertFalse(
            SpringMotionEngine.celebrationProfiles.contains(SpringMotionEngine.ApplePress)
        )
    }

    @Test
    fun `mass classes order stiffness and damping the Quantum way`() {
        val base = SpringMotionEngine.Spatial
        val light = SpringMotionEngine.profileFor(base, MassClass.Light)
        val heavy = SpringMotionEngine.profileFor(base, MassClass.Heavy)
        assertTrue(light.stiffness > base.stiffness)
        assertTrue(heavy.stiffness < base.stiffness)
        assertTrue(light.dampingRatio < base.dampingRatio)
        assertTrue(heavy.dampingRatio > base.dampingRatio)
    }

    @Test
    fun `mass adjustment never pushes damping past critical`() {
        // Sheet is the most-damped base (0.92); Heavy adds +0.03 → 0.95,
        // still under 1.0; and the clamp guards any future profile.
        val heavySheet = SpringMotionEngine.profileFor(
            SpringMotionEngine.Sheet, MassClass.Heavy
        )
        assertTrue(heavySheet.dampingRatio <= 1.0f)
        val clamped = SpringMotionEngine.profileFor(
            SpringMotionEngine.SpringProfile("X", 100f, 0.99f), MassClass.Heavy
        )
        assertEquals(1.0f, clamped.dampingRatio, 0.0f)
    }

    @Test
    fun `rubber band is bounded, diminishing, and zero at rest`() {
        val d = 1000f
        assertEquals(0f, SpringMotionEngine.rubberBand(0f, d), 0f)
        assertEquals(0f, SpringMotionEngine.rubberBand(-50f, d), 0f)
        // Research-table spot values (MOTION-RESEARCH §4).
        assertEquals(52.1f, SpringMotionEngine.rubberBand(100f, d), 0.5f)
        assertEquals(141.6f, SpringMotionEngine.rubberBand(300f, d), 0.5f)
        assertEquals(397.6f, SpringMotionEngine.rubberBand(1200f, d), 0.5f)
        assertTrue(SpringMotionEngine.rubberBand(1200f, d) < d) // bounded
        // Equal drag steps must buy ever less travel (diminishing).
        val steps = listOf(100f, 300f, 500f, 700f)
            .map { SpringMotionEngine.rubberBand(it, d) }
        val gains = steps.zipWithNext { a, b -> b - a }
        assertTrue(gains[0] > gains[1] && gains[1] > gains[2])
        assertTrue(gains.all { it > 0f }) // still monotonic
    }

    @Test
    fun `reduce motion decision`() {
        assertTrue(SpringMotionEngine.animationsEnabled(1f, true))
        assertFalse(SpringMotionEngine.animationsEnabled(0f, true))
        assertFalse(SpringMotionEngine.animationsEnabled(1f, false))
        assertFalse(SpringMotionEngine.animationsEnabled(0f, false))
    }

    @Test
    fun `motion tiers follow the device rules`() {
        // The user's 2 GB tablet: Minimal. His 6 GB M31: Full.
        assertEquals(
            SpringMotionEngine.MotionTier.Minimal,
            SpringMotionEngine.tierFor(2048L, animationsOn = true)
        )
        assertEquals(
            SpringMotionEngine.MotionTier.Standard,
            SpringMotionEngine.tierFor(3072L, animationsOn = true)
        )
        assertEquals(
            SpringMotionEngine.MotionTier.Full,
            SpringMotionEngine.tierFor(6144L, animationsOn = true)
        )
        // Reduce-motion wins over hardware.
        assertEquals(
            SpringMotionEngine.MotionTier.Minimal,
            SpringMotionEngine.tierFor(6144L, animationsOn = false)
        )
        // Unknown RAM (0) never strands a device on Minimal by accident.
        assertEquals(
            SpringMotionEngine.MotionTier.Full,
            SpringMotionEngine.tierFor(0L, animationsOn = true)
        )
    }
}
