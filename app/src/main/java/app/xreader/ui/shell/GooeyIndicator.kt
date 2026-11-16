package app.xreader.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import app.xreader.core.motion.gooeyMeltShortSpec
import app.xreader.core.motion.gooeyMeltSpec
import app.xreader.data.prefs.PerfTier
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Liquid Glass, piece 4 (Gooey Nav): the dock's selection indicator
 * MELTS between slots instead of sliding as a rigid pill. A leading
 * blob travels on a lagging ease, a trailing circle shrinks behind
 * it, and a liquid neck bridges the two — then everything settles
 * into exactly the pill the dock has always drawn (a full-slot
 * stadium in the theme's primary, full strength).
 *
 * The geometry is a pure function ([gooeyBridge]) so the contract is
 * JVM-testable: endpoints exact, neck zero at both ends and peaking
 * mid-travel under its cap, travel strictly monotonic. The
 * composable drives ONE [Animatable] progress on the piece-4 spring
 * and reads it ONLY in the Canvas draw phase, so a melt never
 * recomposes the bar per frame.
 *
 * Retargeting (rapid A→B→C): a mid-flight target change re-bases the
 * run from the CURRENT visual blob position — recomputed through
 * [gooeyBridge] itself — never back to the run's origin, so the blob
 * cannot pop. The radius is computed ONCE, here, from the slot
 * height and shared by the animation driver and the Canvas (a split
 * computation could make a retarget land on a different radius than
 * the frame being drawn).
 *
 * Device honesty: [perfTier] and [reduceMotion] are PASSED IN by the
 * bar (which resolves them from the app's existing paths) — this
 * composable reads no device state of its own. Reduced motion means
 * no melt at all: the pill sits at the target instantly, which is
 * the behaviour the dock had before this piece. The MINIMAL tier
 * (nav-bar styles pass, 2026-11-16) still melts — never a
 * teleport — but on the short spring (~180 ms).
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

// -- The indicator composable ------------------------------------------

/**
 * Draws the gooey selection indicator behind the dock's slots.
 *
 * [targetCentreX] is the selected slot's centre in px, in the same
 * coordinate space the Canvas fills; [slotWidthPx] is the slot
 * (pill) width, [topPx] the pill's top edge and [heightPx] its
 * height — all from the bar's NavGeometry, so the settled frame is
 * the old pill rect exactly. [colour] is the pill's own tint, read
 * by the bar from the theme.
 */
@Composable
fun GooeyIndicator(
    targetCentreX: Float,
    slotWidthPx: Float,
    topPx: Float,
    heightPx: Float,
    colour: Color,
    perfTier: PerfTier,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    // ONE radius computation, shared by the animation driver (the
    // retarget maths in the effect below) and the Canvas.
    val radius = heightPx / 2f
    // Nav-bar styles pass (2026-11-16): reduced motion alone
    // makes the melt instant now. The MINIMAL tier melts on the
    // SHORT spring — travel shortens (~180 ms) but still
    // travels; it never teleports. (The spec is picked INSIDE
    // the effect: a SpringSpec is a fresh object per call, so
    // keying the effect on it would relaunch — and cancel —
    // the run on every recomposition.)
    val instant = reduceMotion

    var fromX by remember { mutableFloatStateOf(targetCentreX) }
    var toX by remember { mutableFloatStateOf(targetCentreX) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(targetCentreX, instant, perfTier) {
        val meltSpec = if (perfTier == PerfTier.MINIMAL) {
            gooeyMeltShortSpec()
        } else {
            gooeyMeltSpec()
        }
        when {
            instant -> {
                fromX = targetCentreX
                toX = targetCentreX
                progress.snapTo(1f)
            }
            targetCentreX != toX -> {
                // Retarget from the CURRENT visual position: the
                // blob the user is looking at right now becomes
                // the new run's origin — never the old origin.
                // The visual position is read through the SAME
                // warped progress the Canvas draws, so the
                // re-base lands exactly on the drawn blob.
                val visualX = gooeyBridge(
                    fromX, toX, gooeyTravelProgress(progress.value), radius
                ).mainBlobX
                fromX = visualX
                toX = targetCentreX
                progress.snapTo(0f)
                progress.animateTo(1f, meltSpec)
            }
        }
    }

    Canvas(modifier) {
        if (radius <= 0f || slotWidthPx <= 0f || size.width <= 0f) return@Canvas
        val bridge = gooeyBridge(
            fromX, toX, gooeyTravelProgress(progress.value), radius
        )
        val cy = topPx + radius
        // NavGeometry's invariant, kept: even with the spring's
        // overshoot in the progress, the drawn blob never leaves
        // the bar (centres arrive clamped; this bites ~1 px).
        val centreX = bridge.mainBlobX.coerceIn(
            slotWidthPx / 2f,
            (size.width - slotWidthPx / 2f).coerceAtLeast(slotWidthPx / 2f)
        )
        // Trailing circle: emerges from behind the settled blob and
        // shrinks away as the blob arrives.
        if (bridge.trailRadius > 0f) {
            drawCircle(
                color = colour,
                radius = bridge.trailRadius,
                center = Offset(bridge.trailX, cy)
            )
        }
        // The neck: a quadratic bridge between the trailing circle
        // and the leading blob, pinched to neckWidth at its middle.
        if (bridge.neckVisible && bridge.neckWidth > 0f) {
            val mainRy = radius * bridge.mainBlobSquashY
            val midX = (bridge.trailX + centreX) / 2f
            val halfNeck = bridge.neckWidth / 2f
            val neck = Path().apply {
                moveTo(bridge.trailX, cy - bridge.trailRadius)
                quadraticTo(midX, cy - halfNeck, centreX, cy - mainRy)
                lineTo(centreX, cy + mainRy)
                quadraticTo(midX, cy + halfNeck, bridge.trailX, cy + bridge.trailRadius)
                close()
            }
            drawPath(neck, colour)
        }
        // The leading blob: the pill itself — a stadium of the
        // slot's width, squashed vertically mid-travel. At rest
        // (squash 1, centred on the slot) it IS the old pill.
        val ry = radius * bridge.mainBlobSquashY
        drawRoundRect(
            color = colour,
            topLeft = Offset(centreX - slotWidthPx / 2f, cy - ry),
            size = Size(slotWidthPx, ry * 2f),
            cornerRadius = CornerRadius(ry, ry)
        )
    }
}
