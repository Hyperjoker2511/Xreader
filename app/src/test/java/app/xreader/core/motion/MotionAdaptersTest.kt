package app.xreader.core.motion

import org.junit.Assert.assertEquals
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
    fun `list placement comes from the engine spatial profile`() {
        val spec = listPlacementSpec()
        // The spec IS the engine's Spatial profile (MOTION-RESEARCH §4:
        // k 380, ζ 0.88) — not a screen-local invention.
        assertEquals(SpringMotionEngine.Spatial.stiffness, spec.stiffness, 0f)
        assertEquals(SpringMotionEngine.Spatial.dampingRatio, spec.dampingRatio, 0f)
    }
}
