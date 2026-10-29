package app.xreader.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R06 performance policy + R05 cache budgets. */
class PerformancePolicyTest {

    @Test
    fun `ram tiers match the tablet boundary`() {
        assertEquals(PerfTier.MINIMAL, PerformancePolicy.tierForRam(2048))
        assertEquals(PerfTier.MINIMAL, PerformancePolicy.tierForRam(1024))
        assertEquals(PerfTier.STANDARD, PerformancePolicy.tierForRam(3072))
        assertEquals(PerfTier.FULL, PerformancePolicy.tierForRam(6144))
        // Unknown reads as FULL — never strand a device on MINIMAL.
        assertEquals(PerfTier.FULL, PerformancePolicy.tierForRam(0))
    }

    @Test
    fun `automatic on the 2 gb tablet is minimal with the 48 palette budget`() {
        val p = PerformancePolicy.resolve(PerformanceMode.AUTOMATIC, 2048, coverGlowOptIn = true)
        assertEquals(PerfTier.MINIMAL, p.tier)
        assertEquals(48, p.paletteCacheSize)
        assertFalse(p.blurredBackdropAllowed)
        assertEquals(0, p.particleCap)
        // Glow stays off on MINIMAL under Automatic (R05).
        assertFalse(p.coverGlowAllowed)
    }

    @Test
    fun `best looking unlocks glow on minimal but never unsafe extras`() {
        val p = PerformancePolicy.resolve(PerformanceMode.BEST_LOOKING, 2048, coverGlowOptIn = true)
        assertTrue(p.coverGlowAllowed)
        assertEquals(0, p.particleCap)
    }

    @Test
    fun `smooth forces the cheap paths on a big phone`() {
        val p = PerformancePolicy.resolve(PerformanceMode.SMOOTH, 6144, coverGlowOptIn = true)
        assertEquals(PerfTier.MINIMAL, p.tier)
        assertFalse(p.ambientAnimation)
        assertFalse(p.coverGlowAllowed)
        assertFalse(p.blurredBackdropAllowed)
    }

    @Test
    fun `full phone gets the 200 palette budget and backdrop`() {
        val p = PerformancePolicy.resolve(PerformanceMode.AUTOMATIC, 6144, coverGlowOptIn = false)
        assertEquals(200, p.paletteCacheSize)
        assertTrue(p.blurredBackdropAllowed)
        assertTrue(p.particleCap > 0)
        assertFalse(p.coverGlowAllowed) // user did not opt in
    }
}
