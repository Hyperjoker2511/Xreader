package app.xreader.ui.shell

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The gooey melt's PURE geometry (Liquid Glass, piece 4;
 * retained by the nav engine round, 2026-11-16,
 * SPEC-NAV-ENGINE-2026-11-16 §2).
 *
 * History: this file's renderer drew the melt as a leading
 * blob + trailing circle + liquid neck through [gooeyBridge].
 * The fresh-context audit (2026-11-16) convicted that drawing:
 * the blob was a full-slot-width stadium at every frame and
 * the neck and trail never escaped its outline on a 1-slot
 * jump (neck curve peak ~16.9 dp vs blob edge ~18.2 dp
 * mid-trip), so on the owner's phone the melt read as a plain
 * slide — "Gooye not working". The nav engine round rebuilt
 * GOOEY as a two-edge STRETCHED PILL (see StretchIndicator in
 * NavIndicator.kt, driven by the lead/trail springs in
 * NavIndicatorLogic) and REMOVED the neck/trail drawing.
 *
 * What stays here, pinned by GooeyGeometryTest: [gooeyBridge]
 * and [gooeyTravelProgress] as pure functions — and, load
 * bearing, the melt's SQUASH curve: the rebuilt GOOEY reads
 * its height from [gooeySquashAt], which is [gooeyBridge]'s
 * own squash output, so the shipped 0.70 mid-trip squeeze
 * survives the rebuild exactly.
 */

// -- Pure geometry -----------------------------------------------------

/**
 * One frame of the melt between two slot centres, in px (the
 * function is unit-agnostic; the caller passes px throughout).
 *
 * [mainBlobX] is the leading blob's centre; [mainBlobRadius] is its
 * vertical radius ([radius] verbatim — the horizontal extent is the
 * slot's, applied by the renderer); [mainBlobSquashY] is the blob's
 * vertical squash (1 at rest, 0.70 mid-travel — the liquid squeeze,
 * deepened by the visible-layer fix round).
 * [trailX]/[trailRadius] are the shrinking trailing circle.
 * [neckWidth] is the bridge thickness at the midpoint; it is 0 and
 * [neckVisible] false at both ends and whenever from == to.
 */
data class GooeyBridge(
    val mainBlobX: Float,
    val mainBlobRadius: Float,
    val mainBlobSquashY: Float,
    val trailX: Float,
    val trailRadius: Float,
    val neckWidth: Float,
    val neckVisible: Boolean
)

// Visible-layer fix round (§C): the melt's geometry constants
// are raised to the owner's visibility bar — the shipped values
// (neck 1.05 under a 1.1 cap, squash 0.14) deformed so little
// that the melt read as a plain slide. Neck peak 1.45 under a
// 1.6 cap, squash depth 0.30, trail 0.90: the stretch is
// unmistakable, the endpoints are untouched (every constant
// here is multiplied by an envelope that is 0 at both ends).

/** The neck may never exceed radius × this (contract cap). */
private const val NECK_CAP = 1.6f

/** The neck's peak, as a multiple of radius: under the cap, with headroom. */
private const val NECK_PEAK = 1.45f

/** How much smaller the trailing circle starts than the blob. */
private const val TRAIL_SCALE = 0.90f

/** The blob's vertical squash at mid-travel (1 − this at the peak). */
private const val SQUASH_DEPTH = 0.30f

private fun easeInOutCubic(p: Float): Float =
    if (p < 0.5f) {
        4f * p * p * p
    } else {
        val u = -2f * p + 2f
        1f - u * u * u / 2f
    }

/**
 * The lag-blend travel ease (build decision): a small linear share
 * over the cubic keeps the derivative above zero everywhere, so the
 * blob's travel is strictly monotonic while still lagging early and
 * catching up late — the liquid feel.
 */
private fun gooeyEase(p: Float): Float = 0.04f * p + 0.96f * easeInOutCubic(p)

/** How hard the travel-progress warp lingers mid-run (below). */
private const val TRAVEL_WARP = 0.30f

/**
 * The travel-progress warp (nav-bar styles pass, 2026-11-16,
 * SPEC-NAVBAR-STYLES-2026-11-16 Change 1): the spring's raw
 * progress is reshaped before it reaches [gooeyBridge] so the
 * run LINGERS in the bridge band — the neck at visible
 * thickness spans a watchable share of the trip (the Morphe
 * stretch) instead of flashing past inside a fast middle.
 *
 * warped(p) = p + A·sin(2πp)/(2π), A = [TRAVEL_WARP]. The
 * endpoints are exact (0 → 0, 1 → 1, 0.5 → 0.5); the
 * derivative 1 + A·cos(2πp) stays in [0.70, 1.30] — strictly
 * positive, so travel stays monotonic — dipping to 0.70 at
 * mid-run (the linger) and rising to 1.30 at the ends (the
 * launch and the catch-up). Spring overshoot outside [0, 1]
 * passes through harmlessly: [gooeyBridge] pins its own
 * endpoints. Pure — pinned by GooeyGeometryTest.
 */
fun gooeyTravelProgress(raw: Float): Float =
    raw + TRAVEL_WARP * sin(2f * PI.toFloat() * raw) / (2f * PI.toFloat())

/**
 * The melt frame for [progress] between slot centres [fromX] and
 * [toX], for a blob of vertical radius [radius].
 *
 * Endpoints are pinned BY CONSTRUCTION (progress ≤ 0 returns the
 * start state verbatim, ≥ 1 the end state), so a spring overshoot
 * past 1 simply dwells on the settled pill instead of extrapolating
 * the neck envelope into NaN territory.
 */
fun gooeyBridge(fromX: Float, toX: Float, progress: Float, radius: Float): GooeyBridge {
    if (progress <= 0f) {
        return GooeyBridge(
            mainBlobX = fromX,
            mainBlobRadius = radius,
            mainBlobSquashY = 1f,
            trailX = fromX,
            trailRadius = radius * TRAIL_SCALE,
            neckWidth = 0f,
            neckVisible = false
        )
    }
    if (progress >= 1f) {
        return GooeyBridge(
            mainBlobX = toX,
            mainBlobRadius = radius,
            mainBlobSquashY = 1f,
            trailX = toX,
            trailRadius = 0f,
            neckWidth = 0f,
            neckVisible = false
        )
    }
    val p = progress
    // Neck envelope: 0 at both ends, exactly 1 at p = 0.5. The base
    // is coerced non-negative so an out-of-range progress can never
    // feed a fractional power a negative number.
    val envelope = (4f * p * (1f - p)).coerceAtLeast(0f).pow(1.5f)
    val travelling = fromX != toX
    return GooeyBridge(
        mainBlobX = fromX + (toX - fromX) * gooeyEase(p),
        mainBlobRadius = radius,
        mainBlobSquashY = 1f - SQUASH_DEPTH * envelope,
        trailX = fromX + (toX - fromX) * p * p,
        trailRadius = radius * TRAIL_SCALE * (1f - p),
        neckWidth = if (travelling) {
            (radius * NECK_PEAK * envelope).coerceAtMost(radius * NECK_CAP)
        } else {
            0f
        },
        neckVisible = travelling
    )
}

/**
 * The rebuilt GOOEY's height curve (nav engine round,
 * SPEC-NAV-ENGINE-2026-11-16 §2): the stretched pill's
 * vertical squash at trip [progress] — [gooeyBridge]'s own
 * squash output, read off a unit run so the constant lives in
 * exactly one place. 1.0 at both ends, 0.70 at mid-trip: the
 * pill fattens back to full height as it lands, squeezed
 * while it stretches.
 */
fun gooeySquashAt(progress: Float): Float =
    gooeyBridge(0f, 1f, progress, 1f).mainBlobSquashY
