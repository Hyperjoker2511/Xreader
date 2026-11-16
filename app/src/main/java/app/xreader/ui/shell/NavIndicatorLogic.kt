package app.xreader.ui.shell

import androidx.compose.animation.core.SpringSpec
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.toSpec
import app.xreader.data.library.NavIndicatorColourMode
import app.xreader.data.library.NavIndicatorStyle
import app.xreader.data.prefs.PerfTier
import kotlin.math.pow

/**
 * Nav-bar styles (v1.6.2, SPEC-NAVBAR-STYLES-2026-11-16): the
 * PURE decisions behind the dock's indicator — which style
 * actually renders on this device, which colour the indicator
 * wears, what sits on it legibly, how fast it travels, and the
 * frame geometry of every style. Plain numbers only (ARGB as
 * Long, px as Float) so the whole contract is JVM-testable;
 * the composables in NavIndicator.kt and MainScaffold.kt only
 * draw what this object decides.
 */
object NavIndicatorLogic {

    // -- Style dispatch / degradation --------------------------------

    /**
     * The style that actually renders. Reduced motion flattens
     * EVERY style to PLAIN (instant tint — the shipped gate,
     * now stated in the Customise caption). On the MINIMAL tier
     * NOTCH degrades to SLIDE (the bar-shape path is the one
     * cost the 2 GB phone doesn't pay) and LENS degrades to
     * SLIDE (nav engine round, SPEC-NAV-ENGINE-2026-11-16 §3:
     * no fake-glass drawing on the small phone); every other
     * style keeps its identity there — BUBBLE merely loses its
     * scrim (see [bubbleShowsScrim]), the stretch styles keep
     * their edges on shortened springs (see [leadEdgeProfile])
     * and travel shortens (see [navTravelProfile]).
     */
    fun effectiveStyle(
        requested: NavIndicatorStyle,
        perfTier: PerfTier,
        reduceMotion: Boolean
    ): NavIndicatorStyle = when {
        reduceMotion -> NavIndicatorStyle.PLAIN
        perfTier == PerfTier.MINIMAL &&
            requested == NavIndicatorStyle.NOTCH -> NavIndicatorStyle.SLIDE
        perfTier == PerfTier.MINIMAL &&
            requested == NavIndicatorStyle.LENS -> NavIndicatorStyle.SLIDE
        else -> requested
    }

    /** BUBBLE's soft shadow scrim under the bubble: drawn on
     * every tier except MINIMAL (the spec's cheap-tier cut). */
    fun bubbleShowsScrim(effective: NavIndicatorStyle, perfTier: PerfTier): Boolean =
        effective == NavIndicatorStyle.BUBBLE && perfTier != PerfTier.MINIMAL

    // -- Colour -------------------------------------------------------

    /** The indicator's ARGB: the stored custom colour under
     * CUSTOM, the theme primary's ARGB under THEME. */
    fun resolveIndicatorArgb(
        mode: NavIndicatorColourMode,
        customArgb: Long,
        themePrimaryArgb: Long
    ): Long = when (mode) {
        NavIndicatorColourMode.THEME -> themePrimaryArgb
        NavIndicatorColourMode.CUSTOM -> customArgb
    }

    /** WCAG relative luminance of an ARGB colour (alpha ignored). */
    fun relativeLuminance(argb: Long): Double {
        fun linear(channel: Long): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    const val WHITE_ARGB: Long = 0xFFFFFFFFL
    const val BLACK_ARGB: Long = 0xFF000000L

    /**
     * The colour that sits ON the indicator (the icon inside a
     * filled style): white on a dark indicator, black on a
     * light one, by relative luminance — the banked
     * contrast-clamp idea, applied here first. The theme's own
     * violet (L ≈ 0.20) takes white, exactly as the shipped
     * onPrimary does.
     */
    fun onIndicatorArgb(indicatorArgb: Long): Long =
        if (relativeLuminance(indicatorArgb) > 0.5) BLACK_ARGB else WHITE_ARGB

    /**
     * The selected slot's ICON colour. Filled styles (GOOEY,
     * WORM, SLIDE, BUBBLE, PLAIN, SQUIRCLE, EXPANDING, LENS)
     * put the icon ON the indicator, so it takes the
     * on-indicator colour; the unfilled styles (NOTCH, GLOW,
     * RING, DOT, TOPLINE, MINIMAL, FLASHY) have no fill under
     * the icon — the icon sits on the bar itself and takes the
     * indicator colour as its accent. SEGMENTED wears PLAIN's
     * colours (the spec's rule), so it sits in the else branch
     * with the filled set.
     */
    fun navIconArgb(
        effective: NavIndicatorStyle,
        indicatorArgb: Long,
        onIndicatorArgb: Long
    ): Long = when (effective) {
        NavIndicatorStyle.NOTCH, NavIndicatorStyle.GLOW,
        NavIndicatorStyle.RING, NavIndicatorStyle.DOT,
        NavIndicatorStyle.TOPLINE, NavIndicatorStyle.MINIMAL,
        NavIndicatorStyle.FLASHY -> indicatorArgb
        else -> onIndicatorArgb
    }

    /**
     * The selected slot's LABEL colour. The label always sits
     * on the bar (never inside the indicator), so BUBBLE joins
     * NOTCH and GLOW here: the label takes the indicator colour
     * as its accent, where the on-indicator colour (chosen for
     * contrast against the INDICATOR) could vanish against the
     * bar — black on dark glass for a yellow bubble. The nav
     * engine round adds the same bar-side styles (RING, DOT,
     * TOPLINE), MINIMAL (its whole point is colour alone) and
     * FLASHY (the label IS the selected slot's face). LENS is
     * the exception among the new styles: its label sits under
     * the lens's fill, so it keeps the on-indicator colour.
     * SEGMENTED wears PLAIN's colours, as its icon does.
     */
    fun navLabelArgb(
        effective: NavIndicatorStyle,
        indicatorArgb: Long,
        onIndicatorArgb: Long
    ): Long = when (effective) {
        NavIndicatorStyle.NOTCH, NavIndicatorStyle.GLOW,
        NavIndicatorStyle.BUBBLE, NavIndicatorStyle.RING,
        NavIndicatorStyle.DOT, NavIndicatorStyle.TOPLINE,
        NavIndicatorStyle.MINIMAL, NavIndicatorStyle.FLASHY -> indicatorArgb
        else -> onIndicatorArgb
    }

    // -- Travel -------------------------------------------------------

    /**
     * The travel spring profile for a tier: the engine's
     * NavTravel on FULL / STANDARD (~286 ms settle — the
     * visible-travel band), NavTravelMinimal on MINIMAL
     * (~176 ms — shortened, still a slide, never a teleport).
     */
    fun navTravelProfile(perfTier: PerfTier): SpringMotionEngine.SpringProfile =
        if (perfTier == PerfTier.MINIMAL) {
            SpringMotionEngine.NavTravelMinimal
        } else {
            SpringMotionEngine.NavTravel
        }

    /** The travel spring spec for a tier (the Compose adapter
     * over [navTravelProfile]). */
    fun navTravelSpec(perfTier: PerfTier): SpringSpec<Float> =
        navTravelProfile(perfTier).toSpec()

    /** The indicator centre between two slot centres at an
     * eased [progress] — the shared position law every
     * travelling style's frame is built from. */
    fun indicatorCentreAt(fromX: Float, toX: Float, progress: Float): Float =
        fromX + (toX - fromX) * progress

    // -- Per-style frame geometry (px) --------------------------------

    /** SLIDE: the circle's radius — the 44 dp disc, capped by
     * the pill's height so it can never outgrow the bar. */
    fun slideRadiusPx(heightPx: Float, maxDiameterPx: Float): Float =
        (minOf(heightPx, maxDiameterPx) / 2f).coerceAtLeast(0f)

    /** GLOW: the dot's centre Y — one dot radius plus [gapPx]
     * above the pill's bottom edge ("a dot under the icon"). */
    fun glowDotCentreYPx(
        topPx: Float,
        heightPx: Float,
        dotRadiusPx: Float,
        gapPx: Float
    ): Float = topPx + heightPx - dotRadiusPx - gapPx

    /**
     * One frame of the BUBBLE, in bar coordinates (the bar's
     * top edge is y = 0): the circle's centre sits [risePx]
     * ABOVE the top edge — riding proud — while its lower arc
     * still dips into the bar, so the bubble reads as carried
     * by the bar, not floating free of it.
     */
    data class BubbleFrame(
        val centreX: Float,
        val centreY: Float,
        val radius: Float,
        /** The circle's lowest point, in bar coordinates. */
        val bottomPx: Float
    )

    fun bubbleFrame(centreX: Float, diameterPx: Float, risePx: Float): BubbleFrame {
        val radius = (diameterPx / 2f).coerceAtLeast(0f)
        val centreY = -risePx
        return BubbleFrame(
            centreX = centreX,
            centreY = centreY,
            radius = radius,
            bottomPx = centreY + radius
        )
    }

    /**
     * NOTCH: the scoop the bar's top edge dips into around the
     * active icon. [halfWidth] is a fixed share of the slot;
     * [depth] a fixed share of the bar's height. The centre is
     * clamped so the scoop never bites into the pill's corner
     * curve (a docked bar has no corner to protect).
     */
    data class NotchScoop(
        val centreX: Float,
        val halfWidth: Float,
        val depth: Float
    )

    /** The scoop's half-width as a share of the slot width. */
    const val NOTCH_HALF_WIDTH_FRACTION = 0.42f

    /** The scoop's depth as a share of the bar height. */
    const val NOTCH_DEPTH_FRACTION = 0.34f

    fun notchScoop(
        barWidthPx: Float,
        barHeightPx: Float,
        centreXPx: Float,
        slotWidthPx: Float,
        docked: Boolean
    ): NotchScoop {
        val halfWidth = (slotWidthPx * NOTCH_HALF_WIDTH_FRACTION).coerceAtLeast(0f)
        val depth = (barHeightPx * NOTCH_DEPTH_FRACTION).coerceAtLeast(0f)
        val corner = if (docked) 0f else barHeightPx / 2f
        val inset = halfWidth + corner * 0.35f
        val centre = if (barWidthPx <= 2f * inset) {
            barWidthPx / 2f
        } else {
            centreXPx.coerceIn(inset, barWidthPx - inset)
        }
        return NotchScoop(centreX = centre, halfWidth = halfWidth, depth = depth)
    }

    // -- The stretch engine (nav engine round, 2026-11-16) --------

    /**
     * The LEAD edge's spring for a stretch style (GOOEY /
     * WORM): the edge toward the target, the one that arrives
     * first. GOOEY leads on the melt spring itself (its pinned
     * 280–380 ms window is untouched); WORM leads on the calmer
     * NavTravel. On MINIMAL both lead on NavTravelMinimal —
     * shortened, still travelling (SPEC-NAV-ENGINE §3).
     */
    fun leadEdgeProfile(
        style: NavIndicatorStyle,
        perfTier: PerfTier
    ): SpringMotionEngine.SpringProfile = when {
        perfTier == PerfTier.MINIMAL -> SpringMotionEngine.NavTravelMinimal
        style == NavIndicatorStyle.WORM -> SpringMotionEngine.NavTravel
        else -> SpringMotionEngine.GooeyMelt
    }

    /**
     * The TRAIL edge's spring: always the TrailLag family —
     * the lag IS the stretch. MINIMAL shortens it by the
     * tier's own proportion (TrailLagMinimal).
     */
    fun trailEdgeProfile(
        style: NavIndicatorStyle,
        perfTier: PerfTier
    ): SpringMotionEngine.SpringProfile =
        if (perfTier == PerfTier.MINIMAL) {
            SpringMotionEngine.TrailLagMinimal
        } else {
            SpringMotionEngine.TrailLag
        }

    /** The lead edge's Compose spec (see [leadEdgeProfile]). */
    fun leadEdgeSpec(
        style: NavIndicatorStyle,
        perfTier: PerfTier
    ): SpringSpec<Float> = leadEdgeProfile(style, perfTier).toSpec()

    /** The trail edge's Compose spec (see [trailEdgeProfile]). */
    fun trailEdgeSpec(
        style: NavIndicatorStyle,
        perfTier: PerfTier
    ): SpringSpec<Float> = trailEdgeProfile(style, perfTier).toSpec()

    /** EXPANDING's slot-weight spring: NavTravel on full tiers
     * (~286 ms — the spec's ~300 ms), NavTravelMinimal on
     * MINIMAL (the style keeps, the spec shortens — §3). */
    fun expandingWeightProfile(
        perfTier: PerfTier
    ): SpringMotionEngine.SpringProfile =
        if (perfTier == PerfTier.MINIMAL) {
            SpringMotionEngine.NavTravelMinimal
        } else {
            SpringMotionEngine.NavTravel
        }

    /** EXPANDING's weight spec (see [expandingWeightProfile]). */
    fun expandingWeightSpec(perfTier: PerfTier): SpringSpec<Float> =
        expandingWeightProfile(perfTier).toSpec()

    /** The selected slot's weight under EXPANDING (the others
     * stay 1.0 — the bar's total width never changes). */
    const val EXPANDING_SELECTED_WEIGHT = 2.3f

    /**
     * One frame of the stretched pill, in px: the stadium spans
     * [leftPx]..[rightPx] (the two edge Animatables, ordered);
     * [stretchPx] is how much wider than the settled slot the
     * frame is — the number every height curve reads.
     */
    data class StretchFrame(
        val leftPx: Float,
        val rightPx: Float,
        val widthPx: Float,
        val stretchPx: Float
    )

    fun stretchFrame(
        leadEdgePx: Float,
        trailEdgePx: Float,
        slotWidthPx: Float
    ): StretchFrame {
        val left = minOf(leadEdgePx, trailEdgePx)
        val right = maxOf(leadEdgePx, trailEdgePx)
        val width = (right - left).coerceAtLeast(0f)
        return StretchFrame(
            leftPx = left,
            rightPx = right,
            widthPx = width,
            stretchPx = (width - slotWidthPx).coerceAtLeast(0f)
        )
    }

    /**
     * The two edges' positions [elapsedMs] into a jump from
     * [fromCentre] to [toCentre], by the engine's analytic
     * spring response over the style's own lead/trail profiles
     * — the pure model of the host's Animatables, so the
     * silhouette contract is JVM-testable. Returns
     * (leftPx, rightPx) of the stadium that would be drawn.
     */
    fun stretchEdgesAt(
        style: NavIndicatorStyle,
        perfTier: PerfTier,
        fromCentre: Float,
        toCentre: Float,
        slotWidthPx: Float,
        elapsedMs: Double
    ): Pair<Float, Float> {
        val half = slotWidthPx / 2f
        val fromLeft = fromCentre - half
        val fromRight = fromCentre + half
        val toLeft = toCentre - half
        val toRight = toCentre + half
        val lead = leadEdgeProfile(style, perfTier)
        val trail = trailEdgeProfile(style, perfTier)
        // The lead edge is the one on the target's side; the
        // trail edge is the one it drags behind it.
        val movingRight = toCentre >= fromCentre
        val leadPos = if (movingRight) {
            SpringMotionEngine.springPositionAt(lead, fromRight, toRight, elapsedMs)
        } else {
            SpringMotionEngine.springPositionAt(lead, fromLeft, toLeft, elapsedMs)
        }
        val trailPos = if (movingRight) {
            SpringMotionEngine.springPositionAt(trail, fromLeft, toLeft, elapsedMs)
        } else {
            SpringMotionEngine.springPositionAt(trail, fromRight, toRight, elapsedMs)
        }
        return minOf(leadPos, trailPos) to maxOf(leadPos, trailPos)
    }

    /** WORM's thinning depth: at full stretch the stadium is
     * 1 − this tall (0.72× — the caterpillar pinch). */
    const val WORM_THIN_DEPTH = 0.28f

    /** The lead–trail separation, as a share of the slot pitch,
     * at which WORM's thinning saturates. Two springs in the
     * researched lag band separate by roughly a third of the
     * pitch mid-jump; past that the worm is fully thin. */
    const val WORM_FULL_THIN_SEPARATION = 0.35f

    /**
     * WORM's height scale for a frame stretched [stretchPx]
     * beyond the settled slot on a [pitchPx] pitch: 1.0 at
     * rest, easing to 1 − [WORM_THIN_DEPTH] (0.72×) as the
     * separation reaches [WORM_FULL_THIN_SEPARATION] of the
     * pitch, and saturated beyond — a long jump stays thin for
     * its whole middle instead of fattening again.
     */
    fun wormHeightScale(stretchPx: Float, pitchPx: Float): Float {
        if (stretchPx <= 0f || pitchPx <= 0f) return 1f
        val u = (stretchPx / pitchPx / WORM_FULL_THIN_SEPARATION)
            .coerceIn(0f, 1f)
        val env = kotlin.math.sin(u * (kotlin.math.PI.toFloat() / 2f))
        return 1f - WORM_THIN_DEPTH * env
    }

    // -- LENS (fake liquid lens) -------------------------------------

    /** How much wider than the slot the lens grows at full
     * travel speed (the research's dragStretch class, adapted:
     * SPEC-NAV-ENGINE §2 pins the 0.24 factor). */
    const val LENS_WIDEN = 0.24f

    /** The lens's squash floor in the first third of a trip. */
    const val LENS_SQUASH = 0.92f

    /** Velocity normaliser for [lensStretchFactor]: a travel
     * spring crossing one slot width ~8 times per second is
     * at full stretch. */
    const val LENS_VELOCITY_NORM = 8f

    /**
     * The lens's speed factor, 0..1: how hard the current
     * travel velocity (px/s, the centre Animatable's own
     * velocity) stretches the lens, normalised by the slot
     * width so the factor is size-free.
     */
    fun lensStretchFactor(velocityPxPerSec: Float, slotWidthPx: Float): Float {
        if (slotWidthPx <= 0f) return 0f
        val speed = kotlin.math.abs(velocityPxPerSec)
        return (speed / (slotWidthPx * LENS_VELOCITY_NORM)).coerceIn(0f, 1f)
    }

    /** The lens's drawn width for a speed [stretch] factor. */
    fun lensWidthPx(slotWidthPx: Float, stretch: Float): Float =
        slotWidthPx * (1f + LENS_WIDEN * stretch.coerceIn(0f, 1f))

    /**
     * The lens's height scale over a trip: it dips to
     * [LENS_SQUASH] at the first third's midpoint and is back
     * to 1.0 by the third's end (the research's "squashes in
     * the first third" behaviour); 1.0 for the rest of the
     * trip and at rest.
     */
    fun lensHeightScale(tripProgress: Float): Float {
        if (tripProgress <= 0f || tripProgress >= 1f / 3f) return 1f
        val u = tripProgress / (1f / 3f)
        val env = kotlin.math.sin(u * kotlin.math.PI.toFloat())
        return 1f - (1f - LENS_SQUASH) * env
    }

    // -- SEGMENTED / SQUIRCLE / RING / DOT / TOPLINE -----------------

    /** A plain rect in bar coordinates (px). */
    data class BarRect(
        val leftPx: Float,
        val topPx: Float,
        val widthPx: Float,
        val heightPx: Float
    )

    /** SEGMENTED's raised thumb: the slot rect inset by
     * [insetPx] on every side. */
    fun segmentedThumb(
        centreX: Float,
        slotWidthPx: Float,
        topPx: Float,
        heightPx: Float,
        insetPx: Float
    ): BarRect = BarRect(
        leftPx = centreX - slotWidthPx / 2f + insetPx,
        topPx = topPx + insetPx,
        widthPx = (slotWidthPx - 2f * insetPx).coerceAtLeast(0f),
        heightPx = (heightPx - 2f * insetPx).coerceAtLeast(0f)
    )

    /** The x of the divider between slot [boundaryIndex] − 1
     * and slot [boundaryIndex] (1-based boundaries between
     * slots): halfway between the two slot centres. */
    fun slotBoundaryXPx(
        firstCentreX: Float,
        pitchPx: Float,
        boundaryIndex: Int
    ): Float = firstCentreX + (boundaryIndex - 0.5f) * pitchPx

    /** SQUIRCLE's cell highlight: the whole slot cell — one
     * pitch wide, the bar's height inset by [insetPx] top and
     * bottom — centred on the travelling centre. */
    fun squircleRect(
        centreX: Float,
        pitchPx: Float,
        barTopPx: Float,
        barHeightPx: Float,
        insetPx: Float
    ): BarRect = BarRect(
        leftPx = centreX - pitchPx / 2f + insetPx,
        topPx = barTopPx + insetPx,
        widthPx = (pitchPx - 2f * insetPx).coerceAtLeast(0f),
        heightPx = (barHeightPx - 2f * insetPx).coerceAtLeast(0f)
    )

    /**
     * RING's centre Y: the icon's centre inside the slot. The
     * slot stacks icon over label, centred as a group in the
     * pill — so the icon centre is the pill's top plus half
     * the slack, plus half the icon.
     */
    fun ringCentreYPx(
        topPx: Float,
        heightPx: Float,
        iconPx: Float,
        labelPx: Float
    ): Float {
        val slack = (heightPx - iconPx - labelPx).coerceAtLeast(0f)
        return topPx + slack / 2f + iconPx / 2f
    }

    /** DOT's centre Y: one dot radius plus [gapPx] above the
     * pill's bottom edge — under the label, at the slot's
     * bottom inner edge (matte: no halo, unlike GLOW). */
    fun dotCentreYPx(
        topPx: Float,
        heightPx: Float,
        dotRadiusPx: Float,
        gapPx: Float
    ): Float = topPx + heightPx - dotRadiusPx - gapPx

    /** TOPLINE's width as a share of the slot width. */
    const val TOPLINE_WIDTH_FRACTION = 0.55f

    /** TOPLINE's left edge for a slot centred at [centreX]. */
    fun topLineLeftPx(centreX: Float, slotWidthPx: Float): Float =
        centreX - slotWidthPx * TOPLINE_WIDTH_FRACTION / 2f

    /** TOPLINE's width for a slot of [slotWidthPx]. */
    fun topLineWidthPx(slotWidthPx: Float): Float =
        slotWidthPx * TOPLINE_WIDTH_FRACTION

    // -- The Library switch's mapping --------------------------------

    /** How the Novels↔Manga switch renders a dock style: the
     * stretch family keeps its two-stop stretch, the lens
     * keeps its lens, and everything else rides the switch's
     * existing single pill (SPEC-NAV-ENGINE §5). */
    enum class SwitchIndicator { STRETCH, LENS, PILL }

    fun switchStyleFor(effective: NavIndicatorStyle): SwitchIndicator =
        when (effective) {
            NavIndicatorStyle.GOOEY, NavIndicatorStyle.WORM ->
                SwitchIndicator.STRETCH
            NavIndicatorStyle.LENS -> SwitchIndicator.LENS
            else -> SwitchIndicator.PILL
        }
}
