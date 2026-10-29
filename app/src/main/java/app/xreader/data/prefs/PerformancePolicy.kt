package app.xreader.data.prefs

/**
 * The effective performance policy (v1.0.2, R06): the user's visible
 * [PerformanceMode] choice resolved against the device's RAM tier
 * into the concrete budgets every effect honours — palette cache
 * size, blurred backdrops, particles, ambient animation.
 *
 * Pure and unit-tested: the same numbers drive the theme, the book
 * page, Library glow, and Me, so one policy applies everywhere and
 * no screen invents its own idea of "cheap".
 *
 * Tier boundaries match the motion engine's (≤2 GB is the tablet
 * tier). An unknown RAM reading resolves as FULL — never strand a
 * device on MINIMAL by accident (the AppContainer.totalRamMb
 * convention).
 */
enum class PerfTier { MINIMAL, STANDARD, FULL }

data class EffectivePerformance(
    val tier: PerfTier,
    /** Book-page pre-blurred cover backdrop (a static bitmap, never
     * a live blur pass). MINIMAL uses a static tint instead (R05). */
    val blurredBackdropAllowed: Boolean,
    /** Cover-palette LRU capacity (R05 budgets: 200 / 48). */
    val paletteCacheSize: Int,
    /** Max simultaneous celebration particles; 0 = none. */
    val particleCap: Int,
    /** Ambient/looping animation allowed at all. */
    val ambientAnimation: Boolean,
    /** Library cover glow may render when the user opted in (R05:
     * MINIMAL allows it only under Best looking). */
    val coverGlowAllowed: Boolean
)

object PerformancePolicy {

    fun tierForRam(totalRamMb: Long): PerfTier = when {
        totalRamMb in 1L..2048L -> PerfTier.MINIMAL
        totalRamMb in 2049L..4096L -> PerfTier.STANDARD
        else -> PerfTier.FULL
    }

    fun resolve(
        mode: PerformanceMode,
        totalRamMb: Long,
        coverGlowOptIn: Boolean
    ): EffectivePerformance {
        val deviceTier = tierForRam(totalRamMb)
        // SMOOTH forces the cheapest paths on any device. BEST
        // LOOKING never exceeds what the device can safely do — it
        // unlocks opt-in extras (glow on MINIMAL), not unsafe ones.
        val tier = if (mode == PerformanceMode.SMOOTH) PerfTier.MINIMAL else deviceTier
        return EffectivePerformance(
            tier = tier,
            blurredBackdropAllowed =
                tier != PerfTier.MINIMAL && mode != PerformanceMode.SMOOTH,
            paletteCacheSize = if (tier == PerfTier.MINIMAL) 48 else 200,
            particleCap = when {
                mode == PerformanceMode.SMOOTH -> 0
                tier == PerfTier.MINIMAL -> 0
                tier == PerfTier.STANDARD -> 16
                else -> 40
            },
            ambientAnimation =
                mode != PerformanceMode.SMOOTH && tier != PerfTier.MINIMAL,
            coverGlowAllowed = coverGlowOptIn &&
                (tier != PerfTier.MINIMAL || mode == PerformanceMode.BEST_LOOKING) &&
                mode != PerformanceMode.SMOOTH
        )
    }
}
