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
        assertEquals(400, SpringMotionEngine.NAV_TRANSITION_MS)
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
}
