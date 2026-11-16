package app.xreader.core.motion

import app.xreader.core.motion.SpringMotionEngine.MassClass
import kotlin.math.abs
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

    @Test
    fun `gooey melt settles inside the readability window`() {
        // Superseded by SPEC-MOTION-COLOROS-2026-11-15 (Change 1):
        // the melt must be WATCHABLE but brisk-premium — settle
        // between 280 and 380 ms. The visible-layer fix round's
        // 150/0.62 landed at ~527 ms and read slow and loose on
        // the owner's phone ("not good premium smooth", benchmark
        // ColorOS 17); the retuned 300/0.72 lands at ~321 ms by
        // the engine's own formula, with a ~3.8% single overshoot.
        val settle = SpringMotionEngine.settleMillis(SpringMotionEngine.GooeyMelt)
        assertTrue(
            "GooeyMelt settles in ${settle}ms — outside the 280–380ms window",
            settle in 280.0..380.0
        )
    }

    @Test
    fun `trail lag settles inside the nav engine band`() {
        // Nav engine round (SPEC-NAV-ENGINE-2026-11-16 §1): the
        // stretched pill's TRAILING edge lags the lead on
        // purpose — its settle must land in the spec's
        // 420–540 ms window, clearly behind GooeyMelt's
        // 280–380 ms lead (the pin above, unmodified), or the
        // stretch reads as a plain slide — the exact failure
        // the owner's "Gooye not working" verdict convicted.
        val settle = SpringMotionEngine.settleMillis(SpringMotionEngine.TrailLag)
        assertTrue(
            "TrailLag settles in ${settle}ms — outside the 420–540ms window",
            settle in 420.0..540.0
        )
    }

    @Test
    fun `trail lag minimal shortens in proportion to nav travel`() {
        // §3: on MINIMAL the trail keeps but shortens
        // proportionally — the same ratio NavTravelMinimal
        // keeps to NavTravel.
        val settle = SpringMotionEngine.settleMillis(
            SpringMotionEngine.TrailLagMinimal
        )
        assertTrue(
            "TrailLagMinimal settles in ${settle}ms",
            settle in 270.0..330.0
        )
        val trailRatio = settle /
            SpringMotionEngine.settleMillis(SpringMotionEngine.TrailLag)
        val travelRatio = SpringMotionEngine.settleMillis(
            SpringMotionEngine.NavTravelMinimal
        ) / SpringMotionEngine.settleMillis(SpringMotionEngine.NavTravel)
        assertTrue(
            "trail ratio $trailRatio vs travel ratio $travelRatio",
            abs(trailRatio - travelRatio) < 0.05
        )
    }

    @Test
    fun `trail lag stays out of the subtle family`() {
        // Like the gooey family, the trail profiles are a
        // separate sanctioned family (ζ 0.75 overshoots ~2.8% —
        // the visible lag IS the feature) and must never leak
        // into `profiles`, whose subtle contract is pinned
        // above.
        assertFalse(SpringMotionEngine.profiles.contains(SpringMotionEngine.TrailLag))
        assertFalse(
            SpringMotionEngine.profiles.contains(SpringMotionEngine.TrailLagMinimal)
        )
        assertTrue(
            SpringMotionEngine.trailLagProfiles.contains(SpringMotionEngine.TrailLag)
        )
        assertTrue(
            SpringMotionEngine.trailLagProfiles.contains(
                SpringMotionEngine.TrailLagMinimal
            )
        )
    }

    @Test
    fun `spring position model starts at from and lands on to`() {
        // springPositionAt is the pure model of the host's
        // Animatables — the silhouette test's ground truth.
        val profile = SpringMotionEngine.NavTravel
        assertEquals(
            10f,
            SpringMotionEngine.springPositionAt(profile, 10f, 110f, 0.0),
            0f
        )
        assertEquals(
            110f,
            SpringMotionEngine.springPositionAt(profile, 10f, 110f, 5000.0),
            0.5f
        )
        // Strictly increasing through the first 300 ms (the
        // overshoot peak is far later for ζ 0.91).
        var prev = Float.NEGATIVE_INFINITY
        var t = 0.0
        while (t <= 300.0) {
            val x = SpringMotionEngine.springPositionAt(profile, 0f, 100f, t)
            assertTrue("position at ${t}ms ($x) must exceed $prev", x > prev)
            prev = x
            t += 25.0
        }
    }
}
