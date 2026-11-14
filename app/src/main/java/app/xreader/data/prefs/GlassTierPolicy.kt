package app.xreader.data.prefs

/**
 * The glass tier a surface actually renders (Liquid Glass, piece 1).
 *
 * [GlassStyle] is the user's CHOICE; [GlassTier] is what the device
 * can honour. The two enums separate here because LIQUID is a tier
 * no stored choice resolved to before: a LIQUID choice on a device
 * that cannot run it must land on a real tier (FROSTED or TINTED),
 * never on a style the renderers don't know. Later pieces consume
 * the tier; the choice stays the user's.
 *
 * Pure and unit-tested over the full input matrix — the theme, the
 * dock and every glass surface ask this one function, so no screen
 * invents its own idea of "strong enough for liquid".
 */
enum class GlassTier { LIQUID, FROSTED, TINTED, SOLID }

/**
 * Resolve the effective [GlassTier] for a glass [choice] on a device
 * with [sdkInt] and [totalRamMb], whose performance tier is
 * [perfTier] (see [PerformancePolicy.tierForRam]).
 *
 * - SOLID and TINTED pass through untouched: a user who picked a
 *   plain surface is never upgraded or downgraded by the device.
 * - FROSTED follows the shipped rule exactly (see [frostedTier]) —
 *   the equivalence test pins this resolver to
 *   [AppearanceValidation.effectiveGlassStyle] so the two can never
 *   drift apart for the existing choices.
 * - LIQUID is earned only by `sdkInt >= 33 && perfTier == FULL`:
 *   the refraction shader is a RuntimeShader (API 33+), and FULL
 *   already implies RAM > 4096 (the [PerformancePolicy] boundary),
 *   so the liquid gate needs no separate RAM check. Below the gate
 *   a LIQUID choice falls back through the SAME frosted rule — a
 *   liquid-choosing user on a weaker device gets exactly the
 *   frosted-or-tinted behaviour existing users get, never a broken
 *   or missing surface.
 */
fun resolveGlassTier(
    choice: GlassStyle,
    sdkInt: Int,
    totalRamMb: Long,
    perfTier: PerfTier
): GlassTier = when (choice) {
    GlassStyle.SOLID -> GlassTier.SOLID
    GlassStyle.TINTED -> GlassTier.TINTED
    GlassStyle.FROSTED -> frostedTier(sdkInt, totalRamMb)
    GlassStyle.LIQUID ->
        if (sdkInt >= 33 && perfTier == PerfTier.FULL) GlassTier.LIQUID
        else frostedTier(sdkInt, totalRamMb)
}

/**
 * The shipped frosted rule, shared by the FROSTED choice and the
 * LIQUID fallback: real blur needs RenderEffect (API 31+) and more
 * than the low-RAM tier (> 2 GB); below either bar the honest
 * answer is TINTED — the smoked plain tier. Identical to
 * [AppearanceValidation.effectiveGlassStyle]'s FROSTED mapping.
 */
private fun frostedTier(sdkInt: Int, totalRamMb: Long): GlassTier =
    if (sdkInt >= 31 && totalRamMb > 2048) GlassTier.FROSTED else GlassTier.TINTED
