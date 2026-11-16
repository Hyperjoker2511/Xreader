package app.xreader.core.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Amendment-5 adapters' pure math: parallax is identical on every
 * surface because it comes from this one function. */
class MotionAdaptersTest {

    @Test
    fun `parallax background moves slower than the foreground`() {
        // Half-speed background: 200px of scroll moves it 100px.
        assertEquals(-100f, parallaxTranslationPx(200f, 0.5f), 0f)
        // Rate 1 = locked to the foreground (no visible parallax).
        assertEquals(0f, parallaxTranslationPx(200f, 1f), 0f)
        // Rate 0 = fixed background.
        assertEquals(-200f, parallaxTranslationPx(200f, 0f), 0f)
        // Out-of-range rates clamp.
        assertEquals(0f, parallaxTranslationPx(200f, 2f), 0f)
        assertEquals(-200f, parallaxTranslationPx(200f, -1f), 0f)
    }

    @Test
    fun `nav parallax is a quarter of the travel`() {
        // Patch 8 geometry (owner decision 2026-11-14, re-pinning the
        // D8 pin below): pushed screens travel width / 6 and covered /
        // returning screens drift a quarter OF THAT TRAVEL = width / 24.
        // D8's original geometry (full-width travel, drift width / 4)
        // pinned 25 / 270 / 1 with divisor 4 until this patch.
        assertEquals(4, SpringMotionEngine.navParallaxOffset(100))
        assertEquals(45, SpringMotionEngine.navParallaxOffset(1080))
        assertEquals(0, SpringMotionEngine.navParallaxOffset(0))
        assertEquals(0, SpringMotionEngine.navParallaxOffset(7))
        // Superseded by SPEC-MOTION-COLOROS-2026-11-15 (Change 5):
        // the NAV_TRANSITION_MS pin (D8's timed 400 ms curve) is
        // retired with the token — stack travel rides the Spatial
        // spring now; the one timed leg left is the stack fade,
        // pinned here at STACK_FADE_MS = 200.
        assertEquals(200, STACK_FADE_MS)
        assertEquals(24, SpringMotionEngine.NAV_PARALLAX_DIVISOR)
    }

    @Test
    fun `tab switches are only tab to tab`() {
        val tabs = setOf("library", "browse", "history", "downloads", "me")
        // Tab ↔ tab crossfades.
        assertTrue(SpringMotionEngine.isTabSwitch("library", "browse", tabs))
        assertTrue(SpringMotionEngine.isTabSwitch("me", "me", tabs))
        // Anything touching a pushed route uses the stack geometry.
        assertTrue(!SpringMotionEngine.isTabSwitch("library", "book/{bookId}", tabs))
        assertTrue(!SpringMotionEngine.isTabSwitch("book/{bookId}", "library", tabs))
        assertTrue(!SpringMotionEngine.isTabSwitch("book/{bookId}", "reader/{bookId}/{chapterIndex}", tabs))
        // Unknown endpoints never count as tab switches.
        assertTrue(!SpringMotionEngine.isTabSwitch(null, "library", tabs))
        assertTrue(!SpringMotionEngine.isTabSwitch("library", null, tabs))
    }

    @Test
    fun `list placement comes from the engine spatial profile`() {
        val spec = listPlacementSpec()
        // The spec IS the engine's Spatial profile (MOTION-RESEARCH §4:
        // k 380, ζ 0.88) — not a screen-local invention.
        assertEquals(SpringMotionEngine.Spatial.stiffness, spec.stiffness, 0f)
        assertEquals(SpringMotionEngine.Spatial.dampingRatio, spec.dampingRatio, 0f)
    }

    @Test
    fun `tab fades are the sequenced fade-through`() {
        // SPEC CHANGE, ordered by SPEC-NAV-ENGINE-2026-11-16 §4
        // (superseding the nav-bar styles pass's Change 4 pins —
        // 120 in / 80 out — which this test previously held):
        // peer tab switches are a SEQUENCED fade-through on ONE
        // clock. The outgoing tab fades out over 100 ms; the
        // incoming tab fades in over 200 ms DELAYED by the same
        // 100 ms, so the two screens never overlap at partial
        // strength (the ghosting the owner kept seeing) and no
        // third clock (the old horizontal slide) runs beside
        // them. Total 300 ms.
        assertEquals(200, TAB_FADE_MS)
        assertEquals(100, TAB_EXIT_FADE_MS)
        assertEquals(100, TAB_ENTER_DELAY_MS)
    }
}
