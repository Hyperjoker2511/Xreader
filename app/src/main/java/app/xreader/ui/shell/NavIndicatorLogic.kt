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
     * cost the 2 GB phone doesn't pay); every other style keeps
     * its identity there — BUBBLE merely loses its scrim (see
     * [bubbleShowsScrim]) and travel shortens (see
     * [navTravelProfile]).
     */
    fun effectiveStyle(
        requested: NavIndicatorStyle,
        perfTier: PerfTier,
        reduceMotion: Boolean
    ): NavIndicatorStyle = when {
        reduceMotion -> NavIndicatorStyle.PLAIN
        perfTier == PerfTier.MINIMAL &&
            requested == NavIndicatorStyle.NOTCH -> NavIndicatorStyle.SLIDE
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
     * SLIDE, BUBBLE, PLAIN) put the icon ON the indicator, so
     * it takes the on-indicator colour; NOTCH and GLOW have no
     * fill under the icon — the icon sits on the bar itself and
     * takes the indicator colour as its accent.
     */
    fun navIconArgb(
        effective: NavIndicatorStyle,
        indicatorArgb: Long,
        onIndicatorArgb: Long
    ): Long = when (effective) {
        NavIndicatorStyle.NOTCH, NavIndicatorStyle.GLOW -> indicatorArgb
        else -> onIndicatorArgb
    }

    /**
     * The selected slot's LABEL colour. The label always sits
     * on the bar (never inside the indicator), so BUBBLE joins
     * NOTCH and GLOW here: the label takes the indicator colour
     * as its accent, where the on-indicator colour (chosen for
     * contrast against the INDICATOR) could vanish against the
     * bar — black on dark glass for a yellow bubble.
     */
    fun navLabelArgb(
        effective: NavIndicatorStyle,
        indicatorArgb: Long,
        onIndicatorArgb: Long
    ): Long = when (effective) {
        NavIndicatorStyle.NOTCH, NavIndicatorStyle.GLOW,
        NavIndicatorStyle.BUBBLE -> indicatorArgb
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
}
