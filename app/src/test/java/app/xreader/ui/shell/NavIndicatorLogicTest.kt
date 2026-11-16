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
    fun `minimal tier degrades notch to slide and keeps the rest`() {
        assertEquals(
            NavIndicatorStyle.SLIDE,
            NavIndicatorLogic.effectiveStyle(
                NavIndicatorStyle.NOTCH, PerfTier.MINIMAL, reduceMotion = false
            )
        )
        NavIndicatorStyle.entries
            .filter { it != NavIndicatorStyle.NOTCH }
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
}
