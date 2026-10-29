package app.xreader.domain.reader

import app.xreader.core.motion.SpringMotionEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTurnTierTest {

    private fun select(
        ramMb: Long,
        memoryClassMb: Int = 256,
        override: PageTurnOverride = PageTurnOverride.AUTO,
        eInk: Boolean = false,
        motionTier: SpringMotionEngine.MotionTier = SpringMotionEngine.MotionTier.Full
    ): PageTurnTier = PageTurnTiers.select(ramMb, memoryClassMb, override, eInk, motionTier)

    @Test
    fun twoGigTabletGetsMinimal() {
        // The user's tablet: 2 GB RAM (Amendment 1 / DESIGN.md rules).
        assertEquals(PageTurnTier.MINIMAL, select(ramMb = 2048L))
        assertEquals(PageTurnTier.MINIMAL, select(ramMb = 1876L)) // usable-RAM reports
    }

    @Test
    fun sixGigM31GetsFullCurl() {
        assertEquals(PageTurnTier.FULL, select(ramMb = 6144L))
        assertEquals(PageTurnTier.FULL, select(ramMb = 4096L))
    }

    @Test
    fun threeGigGetsMid() {
        assertEquals(PageTurnTier.MID, select(ramMb = 3072L))
        assertEquals(PageTurnTier.MID, select(ramMb = 2049L))
    }

    @Test
    fun manualOverrideWinsOverRam() {
        assertEquals(
            PageTurnTier.FULL,
            select(ramMb = 2048L, override = PageTurnOverride.FULL)
        )
        assertEquals(
            PageTurnTier.MINIMAL,
            select(ramMb = 6144L, override = PageTurnOverride.MINIMAL)
        )
        assertEquals(
            PageTurnTier.MID,
            select(ramMb = 6144L, override = PageTurnOverride.MID)
        )
    }

    @Test
    fun eInkBeatsEverything() {
        assertEquals(
            PageTurnTier.EINK,
            select(ramMb = 6144L, override = PageTurnOverride.FULL, eInk = true)
        )
    }

    @Test
    fun reduceMotionForcesMinimal() {
        assertEquals(
            PageTurnTier.MINIMAL,
            select(
                ramMb = 6144L,
                motionTier = SpringMotionEngine.MotionTier.Minimal
            )
        )
    }

    @Test
    fun unknownRamFallsBackToMemoryClassThenFull() {
        // Unknown total but a big heap class → capable device.
        assertEquals(PageTurnTier.FULL, select(ramMb = 0L, memoryClassMb = 512))
        // Nothing known at all: never strand a device on Minimal.
        assertEquals(PageTurnTier.FULL, select(ramMb = 0L, memoryClassMb = 0))
        // Small heap class only → treat as low-RAM.
        assertEquals(PageTurnTier.MINIMAL, select(ramMb = 0L, memoryClassMb = 128))
    }

    @Test
    fun motionEngineTierAgreesOnTablet() {
        // SpringMotionEngine.tierFor is the app's other RAM decision —
        // both must put the 2 GB tablet on the light tier.
        assertEquals(
            SpringMotionEngine.MotionTier.Minimal,
            SpringMotionEngine.tierFor(2048L, animationsOn = true)
        )
        assertEquals(
            PageTurnTier.MINIMAL,
            select(
                ramMb = 2048L,
                motionTier = SpringMotionEngine.tierFor(2048L, animationsOn = true)
            )
        )
    }
}
