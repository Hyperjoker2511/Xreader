package app.xreader.domain.reader

import app.xreader.core.motion.SpringMotionEngine

/**
 * Page-turn rendering tiers (PLAN §6, Amendment 1):
 *  - [FULL] — interactive 3D-mesh page curl (capable devices, M31 6 GB)
 *  - [MID] — liquid edge-bend slide (mid devices)
 *  - [MINIMAL] — crossfade only (the 2 GB tablet's tier)
 *  - [EINK] — instant, animation-free, high contrast
 */
enum class PageTurnTier { FULL, MID, MINIMAL, EINK }

/** Pure tier selection — the 2 GB case is pinned by unit tests. */
object PageTurnTiers {

    const val FULL_MIN_RAM_MB = 4096L
    const val MID_MIN_RAM_MB = 2048L // strictly greater than this is MID

    /**
     * Effective total RAM: the ActivityManager total when known; when
     * it isn't (0), the app's memory class is the fallback signal
     * (large-heap classes only exist on bigger devices).
     */
    fun effectiveRamMb(totalRamMb: Long, memoryClassMb: Int): Long = when {
        totalRamMb > 0 -> totalRamMb
        memoryClassMb >= 384 -> 8192L
        memoryClassMb >= 256 -> 4096L
        memoryClassMb >= 160 -> 3072L
        memoryClassMb > 0 -> 2048L
        else -> 0L
    }

    fun select(
        totalRamMb: Long,
        memoryClassMb: Int,
        override: PageTurnOverride,
        eInkMode: Boolean,
        motionTier: SpringMotionEngine.MotionTier
    ): PageTurnTier {
        if (eInkMode) return PageTurnTier.EINK
        when (override) {
            PageTurnOverride.FULL -> return PageTurnTier.FULL
            PageTurnOverride.MID -> return PageTurnTier.MID
            PageTurnOverride.MINIMAL -> return PageTurnTier.MINIMAL
            PageTurnOverride.AUTO -> Unit
        }
        // Reduce-motion / Minimal motion tier always wins over RAM:
        // the app never disagrees with itself about motion (§6).
        if (motionTier == SpringMotionEngine.MotionTier.Minimal) return PageTurnTier.MINIMAL
        val ram = effectiveRamMb(totalRamMb, memoryClassMb)
        return when {
            ram >= FULL_MIN_RAM_MB -> PageTurnTier.FULL
            ram > MID_MIN_RAM_MB -> PageTurnTier.MID
            ram > 0L -> PageTurnTier.MINIMAL
            else -> PageTurnTier.FULL // unknown device: never strand it on Minimal
        }
    }
}
