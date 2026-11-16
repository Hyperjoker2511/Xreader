package app.xreader.ui.shell

import app.xreader.core.motion.SpringMotionEngine
import app.xreader.data.library.NavIndicatorColourMode
import app.xreader.data.library.NavIndicatorStyle
import app.xreader.data.prefs.PerfTier
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nav-bar styles (v1.6.2, SPEC-NAVBAR-STYLES-2026-11-16): the
 * pure contract behind the dock indicator — style dispatch and
 * degradation, the luminance colour decisions, travel timing
 * per tier, and every style's frame geometry at sample
 * progresses for a 1-slot and a 3-slot jump.
 */
class NavIndicatorLogicTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 0.001f) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    // -- Dispatch / degradation --------------------------------------

    @Test
    fun `reduced motion flattens every style to plain on every tier`() {
        NavIndicatorStyle.entries.forEach { style ->
            PerfTier.entries.forEach { tier ->
                assertEquals(
                    "$style on $tier",
                    NavIndicatorStyle.PLAIN,
                    NavIndicatorLogic.effectiveStyle(style, tier, reduceMotion = true)
                )
            }
        }
    }

    @Test
    fun `minimal tier degrades notch and lens to slide and keeps the rest`() {
        // SPEC CHANGE, ordered by SPEC-NAV-ENGINE-2026-11-16
        // §3/§5 (the matrix grows): NOTCH degrades to SLIDE as
        // before, and LENS joins it — a drawn lens still costs
        // a per-frame canvas on the weakest phones, so MINIMAL
        // takes the honest disc instead. Every other style
        // keeps its identity (the stretch styles keep their
        // edges on the MINIMAL spring set — pinned below).
        assertEquals(
            NavIndicatorStyle.SLIDE,
            NavIndicatorLogic.effectiveStyle(
                NavIndicatorStyle.NOTCH, PerfTier.MINIMAL, reduceMotion = false
            )
        )
        assertEquals(
            NavIndicatorStyle.SLIDE,
            NavIndicatorLogic.effectiveStyle(
                NavIndicatorStyle.LENS, PerfTier.MINIMAL, reduceMotion = false
            )
        )
        NavIndicatorStyle.entries
            .filter {
                it != NavIndicatorStyle.NOTCH && it != NavIndicatorStyle.LENS
            }
            .forEach { style ->
                assertEquals(
                    "$style survives MINIMAL",
                    style,
                    NavIndicatorLogic.effectiveStyle(
                        style, PerfTier.MINIMAL, reduceMotion = false
                    )
                )
            }
    }

    @Test
    fun `full and standard tiers render every style as requested`() {
        NavIndicatorStyle.entries.forEach { style ->
            listOf(PerfTier.FULL, PerfTier.STANDARD).forEach { tier ->
                assertEquals(
                    "$style on $tier",
                    style,
                    NavIndicatorLogic.effectiveStyle(
                        style, tier, reduceMotion = false
                    )
                )
            }
        }
    }

    @Test
    fun `bubble scrim shows on full tiers and never on minimal`() {
        assertTrue(
            NavIndicatorLogic.bubbleShowsScrim(
                NavIndicatorStyle.BUBBLE, PerfTier.FULL
            )
        )
        assertTrue(
            NavIndicatorLogic.bubbleShowsScrim(
                NavIndicatorStyle.BUBBLE, PerfTier.STANDARD
            )
        )
        assertFalse(
            NavIndicatorLogic.bubbleShowsScrim(
                NavIndicatorStyle.BUBBLE, PerfTier.MINIMAL
            )
        )
        assertFalse(
            NavIndicatorLogic.bubbleShowsScrim(
                NavIndicatorStyle.SLIDE, PerfTier.FULL
            )
        )
    }

    // -- Colour --------------------------------------------------------

    @Test
    fun `indicator colour resolves theme or custom`() {
        val theme = 0xFF8B5CF6L
        val custom = 0xFF22CC88L
        assertEquals(
            theme,
            NavIndicatorLogic.resolveIndicatorArgb(
                NavIndicatorColourMode.THEME, custom, theme
            )
        )
        assertEquals(
            custom,
            NavIndicatorLogic.resolveIndicatorArgb(
                NavIndicatorColourMode.CUSTOM, custom, theme
            )
        )
    }

    @Test
    fun `luminance endpoints are exact`() {
        assertEquals(0.0, NavIndicatorLogic.relativeLuminance(0xFF000000L), 0.0001)
        assertEquals(1.0, NavIndicatorLogic.relativeLuminance(0xFFFFFFFFL), 0.0001)
    }

    @Test
    fun `on-indicator colour is white on dark and black on light`() {
        // The theme's own violet takes white — the shipped
        // onPrimary behaviour under the default scheme.
        assertEquals(
            NavIndicatorLogic.WHITE_ARGB,
            NavIndicatorLogic.onIndicatorArgb(0xFF8B5CF6L)
        )
        assertEquals(
            NavIndicatorLogic.WHITE_ARGB,
            NavIndicatorLogic.onIndicatorArgb(0xFF000000L)
        )
        assertEquals(
            NavIndicatorLogic.BLACK_ARGB,
            NavIndicatorLogic.onIndicatorArgb(0xFFFFFFFFL)
        )
        assertEquals(
            NavIndicatorLogic.BLACK_ARGB,
            NavIndicatorLogic.onIndicatorArgb(0xFFFFFF00L)
        )
        assertEquals(
            NavIndicatorLogic.BLACK_ARGB,
            NavIndicatorLogic.onIndicatorArgb(0xFF00FF00L)
        )
    }

    @Test
    fun `icon and label accents follow the style fill`() {
        val indicator = 0xFF8B5CF6L
        val on = NavIndicatorLogic.onIndicatorArgb(indicator)
        // Filled styles: the icon sits ON the indicator.
        listOf(
            NavIndicatorStyle.GOOEY, NavIndicatorStyle.SLIDE,
            NavIndicatorStyle.BUBBLE, NavIndicatorStyle.PLAIN
        ).forEach { style ->
            assertEquals(
                "$style icon",
                on,
                NavIndicatorLogic.navIconArgb(style, indicator, on)
            )
        }
        // Unfilled styles: the icon takes the indicator accent.
        listOf(NavIndicatorStyle.NOTCH, NavIndicatorStyle.GLOW).forEach { style ->
            assertEquals(
                "$style icon",
                indicator,
                NavIndicatorLogic.navIconArgb(style, indicator, on)
            )
        }
        // The label always sits on the bar: BUBBLE joins the
        // accent group, filled-in-bar styles keep the
        // on-indicator colour.
        listOf(
            NavIndicatorStyle.NOTCH, NavIndicatorStyle.GLOW,
            NavIndicatorStyle.BUBBLE
        ).forEach { style ->
            assertEquals(
                "$style label",
                indicator,
                NavIndicatorLogic.navLabelArgb(style, indicator, on)
            )
        }
        listOf(
            NavIndicatorStyle.GOOEY, NavIndicatorStyle.SLIDE,
            NavIndicatorStyle.PLAIN
        ).forEach { style ->
            assertEquals(
                "$style label",
                on,
                NavIndicatorLogic.navLabelArgb(style, indicator, on)
            )
        }
    }

    // -- Travel timing -------------------------------------------------

    @Test
    fun `travel profile follows the tier`() {
        assertEquals(
            SpringMotionEngine.NavTravel,
            NavIndicatorLogic.navTravelProfile(PerfTier.FULL)
        )
        assertEquals(
            SpringMotionEngine.NavTravel,
            NavIndicatorLogic.navTravelProfile(PerfTier.STANDARD)
        )
        assertEquals(
            SpringMotionEngine.NavTravelMinimal,
            NavIndicatorLogic.navTravelProfile(PerfTier.MINIMAL)
        )
    }

    @Test
    fun `travel settles inside the visible band per tier`() {
        // The spec's standard: ~300–380 ms of visible travel
        // for a neighbouring tab on full tiers (the spring's
        // 2% settle is the measurable proxy), ~180 ms on
        // MINIMAL — shortened, never a teleport.
        val full = SpringMotionEngine.settleMillis(SpringMotionEngine.NavTravel)
        assertTrue("NavTravel settles in ${full}ms", full in 260.0..330.0)
        val minimal = SpringMotionEngine.settleMillis(
            SpringMotionEngine.NavTravelMinimal
        )
        assertTrue(
            "NavTravelMinimal settles in ${minimal}ms",
            minimal in 150.0..210.0
        )
    }

    // -- Geometry --------------------------------------------------------

    @Test
    fun `indicator centre is the exact lerp at sample progresses`() {
        // A 1-slot jump (100 → 200) and a 3-slot jump
        // (100 → 400): the shared position law every
        // travelling style's frame is built from.
        listOf(100f to 200f, 100f to 400f).forEach { (from, to) ->
            assertClose(from, NavIndicatorLogic.indicatorCentreAt(from, to, 0f))
            assertClose(
                from + (to - from) * 0.25f,
                NavIndicatorLogic.indicatorCentreAt(from, to, 0.25f)
            )
            assertClose(
                from + (to - from) * 0.5f,
                NavIndicatorLogic.indicatorCentreAt(from, to, 0.5f)
            )
            assertClose(
                from + (to - from) * 0.75f,
                NavIndicatorLogic.indicatorCentreAt(from, to, 0.75f)
            )
            assertClose(to, NavIndicatorLogic.indicatorCentreAt(from, to, 1f))
        }
    }

    @Test
    fun `slide radius is half the smaller of pill height and disc cap`() {
        assertClose(26f, NavIndicatorLogic.slideRadiusPx(52f, 88f))
        assertClose(44f, NavIndicatorLogic.slideRadiusPx(120f, 88f))
        assertClose(0f, NavIndicatorLogic.slideRadiusPx(-4f, 88f))
    }

    @Test
    fun `glow dot sits under the icon near the pill bottom`() {
        val y = NavIndicatorLogic.glowDotCentreYPx(
            topPx = 6f, heightPx = 52f, dotRadiusPx = 3f, gapPx = 5f
        )
        assertClose(50f, y)
        assertTrue("dot inside the pill", y in 6f..58f)
        assertTrue("dot below the pill centre", y > 6f + 52f / 2f)
    }

    @Test
    fun `bubble rides proud of the top edge and dips into the bar`() {
        val frame = NavIndicatorLogic.bubbleFrame(
            centreX = 300f, diameterPx = 104f, risePx = 8f
        )
        assertClose(300f, frame.centreX)
        assertClose(-8f, frame.centreY)
        assertClose(52f, frame.radius)
        assertClose(44f, frame.bottomPx)
        assertTrue("centre above the bar", frame.centreY < 0f)
        assertTrue("lower arc inside the bar", frame.bottomPx > 0f)
        assertTrue("not swallowed by the bar", frame.bottomPx < 104f)
    }

    @Test
    fun `notch scoop numbers follow the slot and the bar`() {
        val scoop = NavIndicatorLogic.notchScoop(
            barWidthPx = 1000f, barHeightPx = 128f,
            centreXPx = 500f, slotWidthPx = 180f, docked = false
        )
        assertClose(500f, scoop.centreX)
        assertClose(180f * 0.42f, scoop.halfWidth)
        assertClose(128f * 0.34f, scoop.depth)
        assertTrue(scoop.depth < 128f * 0.5f)
    }

    @Test
    fun `notch scoop centre is clamped off the corner curve`() {
        // First slot of a pill bar: the raw centre (90) would
        // bite the scoop into the stadium's corner; the clamp
        // pulls it in to halfWidth + 0.35 × corner.
        val first = NavIndicatorLogic.notchScoop(
            barWidthPx = 1000f, barHeightPx = 128f,
            centreXPx = 90f, slotWidthPx = 180f, docked = false
        )
        assertClose(180f * 0.42f + 64f * 0.35f, first.centreX)
        // Last slot mirrors it.
        val last = NavIndicatorLogic.notchScoop(
            barWidthPx = 1000f, barHeightPx = 128f,
            centreXPx = 910f, slotWidthPx = 180f, docked = false
        )
        assertClose(1000f - (180f * 0.42f + 64f * 0.35f), last.centreX)
        // A docked bar has no corner: only the half-width
        // itself guards the edge.
        val docked = NavIndicatorLogic.notchScoop(
            barWidthPx = 1000f, barHeightPx = 128f,
            centreXPx = 40f, slotWidthPx = 180f, docked = true
        )
        assertClose(180f * 0.42f, docked.centreX)
    }

    // -- Nav engine round (SPEC-NAV-ENGINE-2026-11-16) ----------------

    @Test
    fun `new styles follow the accent rules`() {
        val indicator = 0xFF8B5CF6L
        val on = NavIndicatorLogic.onIndicatorArgb(indicator)
        // Filled newcomers: the icon sits ON the indicator.
        listOf(
            NavIndicatorStyle.WORM, NavIndicatorStyle.LENS,
            NavIndicatorStyle.EXPANDING, NavIndicatorStyle.SQUIRCLE
        ).forEach { style ->
            assertEquals(
                "$style icon",
                on,
                NavIndicatorLogic.navIconArgb(style, indicator, on)
            )
        }
        // SEGMENTED's thumb is a pale wash, not a fill: the
        // icon keeps the on-indicator colour like PLAIN's.
        assertEquals(
            on,
            NavIndicatorLogic.navIconArgb(
                NavIndicatorStyle.SEGMENTED, indicator, on
            )
        )
        // Unfilled newcomers: the icon takes the accent.
        listOf(
            NavIndicatorStyle.RING, NavIndicatorStyle.DOT,
            NavIndicatorStyle.TOPLINE, NavIndicatorStyle.MINIMAL,
            NavIndicatorStyle.FLASHY
        ).forEach { style ->
            assertEquals(
                "$style icon",
                indicator,
                NavIndicatorLogic.navIconArgb(style, indicator, on)
            )
        }
        // Labels: the accent group gains the unfilled styles;
        // the filled newcomers keep the on-indicator colour
        // (LENS's label sits under the lens's fill; EXPANDING's
        // inside its pill; SEGMENTED matches PLAIN).
        listOf(
            NavIndicatorStyle.RING, NavIndicatorStyle.DOT,
            NavIndicatorStyle.TOPLINE, NavIndicatorStyle.MINIMAL,
            NavIndicatorStyle.FLASHY
        ).forEach { style ->
            assertEquals(
                "$style label",
                indicator,
                NavIndicatorLogic.navLabelArgb(style, indicator, on)
            )
        }
        listOf(
            NavIndicatorStyle.WORM, NavIndicatorStyle.LENS,
            NavIndicatorStyle.EXPANDING, NavIndicatorStyle.SQUIRCLE,
            NavIndicatorStyle.SEGMENTED
        ).forEach { style ->
            assertEquals(
                "$style label",
                on,
                NavIndicatorLogic.navLabelArgb(style, indicator, on)
            )
        }
    }

    @Test
    fun `edge profiles follow style and tier`() {
        // The lead edge: GOOEY rides the melt spring itself
        // (its 280–380 ms pin passes unmodified); MINIMAL
        // shortens both to the MINIMAL travel set.
        assertEquals(
            SpringMotionEngine.GooeyMelt,
            NavIndicatorLogic.leadEdgeProfile(
                NavIndicatorStyle.GOOEY, PerfTier.FULL
            )
        )
        // SPEC CHANGE (SPEC-NAV-TRAVEL-WAVE-2026-11-16 §B.2):
        // WORM's lead edge now rides GooeyMelt on the full
        // tiers too — the old pin (NavTravel, ζ 0.91) could
        // not produce the spec's visible overshoot landing;
        // both stretch styles share the one retuned lead
        // spring. The MINIMAL pins below are untouched (§B.3).
        assertEquals(
            SpringMotionEngine.GooeyMelt,
            NavIndicatorLogic.leadEdgeProfile(
                NavIndicatorStyle.WORM, PerfTier.FULL
            )
        )
        assertEquals(
            SpringMotionEngine.NavTravelMinimal,
            NavIndicatorLogic.leadEdgeProfile(
                NavIndicatorStyle.GOOEY, PerfTier.MINIMAL
            )
        )
        assertEquals(
            SpringMotionEngine.NavTravelMinimal,
            NavIndicatorLogic.leadEdgeProfile(
                NavIndicatorStyle.WORM, PerfTier.MINIMAL
            )
        )
        // The trail edge always lags on TrailLag, shortened
        // proportionally on MINIMAL.
        assertEquals(
            SpringMotionEngine.TrailLag,
            NavIndicatorLogic.trailEdgeProfile(
                NavIndicatorStyle.GOOEY, PerfTier.FULL
            )
        )
        assertEquals(
            SpringMotionEngine.TrailLag,
            NavIndicatorLogic.trailEdgeProfile(
                NavIndicatorStyle.WORM, PerfTier.STANDARD
            )
        )
        assertEquals(
            SpringMotionEngine.TrailLagMinimal,
            NavIndicatorLogic.trailEdgeProfile(
                NavIndicatorStyle.GOOEY, PerfTier.MINIMAL
            )
        )
    }

    @Test
    fun `expanding weight profile follows the tier`() {
        assertEquals(2.3f, NavIndicatorLogic.EXPANDING_SELECTED_WEIGHT, 0f)
        assertEquals(
            SpringMotionEngine.NavTravel,
            NavIndicatorLogic.expandingWeightProfile(PerfTier.FULL)
        )
        assertEquals(
            SpringMotionEngine.NavTravelMinimal,
            NavIndicatorLogic.expandingWeightProfile(PerfTier.MINIMAL)
        )
    }

    @Test
    fun `stretch frame orders the edges and measures the stretch`() {
        val settled = NavIndicatorLogic.stretchFrame(97f, 3f, 94f)
        assertClose(3f, settled.leftPx)
        assertClose(97f, settled.rightPx)
        assertClose(94f, settled.widthPx)
        assertClose(0f, settled.stretchPx)
        val mid = NavIndicatorLogic.stretchFrame(150f, 30f, 94f)
        assertClose(30f, mid.leftPx)
        assertClose(150f, mid.rightPx)
        assertClose(120f, mid.widthPx)
        assertClose(26f, mid.stretchPx)
    }

    @Test
    fun `worm thins with the stretch and saturates`() {
        assertClose(1f, NavIndicatorLogic.wormHeightScale(0f, 100f))
        // Halfway to full separation: sin(π/4) of the depth.
        assertClose(
            1f - 0.28f * 0.70711f,
            NavIndicatorLogic.wormHeightScale(17.5f, 100f),
            0.001f
        )
        // A full pitch of stretch saturates at 0.72×, and a
        // longer jump stays there.
        assertClose(0.72f, NavIndicatorLogic.wormHeightScale(100f, 100f))
        assertClose(0.72f, NavIndicatorLogic.wormHeightScale(300f, 100f))
    }

    @Test
    fun `lens stretch width and squash follow the spec numbers`() {
        // Speed factor: still = 0, one slot ~8×/s = full.
        assertClose(0f, NavIndicatorLogic.lensStretchFactor(0f, 94f))
        assertClose(0.5f, NavIndicatorLogic.lensStretchFactor(94f * 4f, 94f))
        assertClose(1f, NavIndicatorLogic.lensStretchFactor(94f * 8f, 94f))
        assertClose(1f, NavIndicatorLogic.lensStretchFactor(-94f * 8f, 94f))
        // Width: +24% at full stretch, the slot's own at rest.
        assertClose(94f, NavIndicatorLogic.lensWidthPx(94f, 0f))
        assertClose(94f * 1.24f, NavIndicatorLogic.lensWidthPx(94f, 1f))
        // Squash: 0.92 at the first third's midpoint, 1.0 at
        // both ends of the third and beyond it.
        assertClose(1f, NavIndicatorLogic.lensHeightScale(0f))
        assertClose(0.92f, NavIndicatorLogic.lensHeightScale(1f / 6f))
        assertClose(1f, NavIndicatorLogic.lensHeightScale(1f / 3f))
        assertClose(1f, NavIndicatorLogic.lensHeightScale(0.5f))
        assertClose(1f, NavIndicatorLogic.lensHeightScale(1f))
    }

    @Test
    fun `segmented squircle ring dot and topline geometry`() {
        val thumb = NavIndicatorLogic.segmentedThumb(
            centreX = 150f, slotWidthPx = 94f,
            topPx = 6f, heightPx = 52f, insetPx = 3f
        )
        assertClose(106f, thumb.leftPx)
        assertClose(9f, thumb.topPx)
        assertClose(88f, thumb.widthPx)
        assertClose(46f, thumb.heightPx)
        // Dividers sit halfway between slot centres.
        assertClose(100f, NavIndicatorLogic.slotBoundaryXPx(50f, 100f, 1))
        assertClose(200f, NavIndicatorLogic.slotBoundaryXPx(50f, 100f, 2))
        val cell = NavIndicatorLogic.squircleRect(
            centreX = 150f, pitchPx = 100f,
            barTopPx = 0f, barHeightPx = 64f, insetPx = 4f
        )
        assertClose(104f, cell.leftPx)
        assertClose(4f, cell.topPx)
        assertClose(92f, cell.widthPx)
        assertClose(56f, cell.heightPx)
        // The ring centres on the icon: pill top + half the
        // slack + half the icon.
        assertClose(
            25f,
            NavIndicatorLogic.ringCentreYPx(
                topPx = 6f, heightPx = 52f, iconPx = 24f, labelPx = 14f
            )
        )
        // The dot hugs the pill's bottom inner edge.
        assertClose(
            53f,
            NavIndicatorLogic.dotCentreYPx(
                topPx = 6f, heightPx = 52f, dotRadiusPx = 3f, gapPx = 2f
            )
        )
        // The top line is 55% of the slot, centred.
        assertClose(51.7f, NavIndicatorLogic.topLineWidthPx(94f), 0.01f)
        assertClose(124.15f, NavIndicatorLogic.topLineLeftPx(150f, 94f), 0.01f)
    }

    @Test
    fun `switch mapping covers all sixteen styles`() {
        // SPEC-NAV-ENGINE §5: the stretch family keeps its
        // stretch in the two-stop switch, the lens keeps its
        // lens, and everything else — including the layout
        // styles the switch cannot express (EXPANDING, FLASHY,
        // BUBBLE, NOTCH, MINIMAL) — rides the plain pill.
        val expected = mapOf(
            NavIndicatorStyle.GOOEY to NavIndicatorLogic.SwitchIndicator.STRETCH,
            NavIndicatorStyle.WORM to NavIndicatorLogic.SwitchIndicator.STRETCH,
            NavIndicatorStyle.LENS to NavIndicatorLogic.SwitchIndicator.LENS
        )
        NavIndicatorStyle.entries.forEach { style ->
            assertEquals(
                "$style in the switch",
                expected[style] ?: NavIndicatorLogic.SwitchIndicator.PILL,
                NavIndicatorLogic.switchStyleFor(style)
            )
        }
    }
}
