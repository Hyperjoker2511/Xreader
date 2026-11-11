package app.xreader.ui.localreader

import androidx.compose.ui.graphics.Color
import app.xreader.data.prefs.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.9 (F-8a/U-1..U-3): the reader chrome's glass spec is a
 * PURE mapping pinned to DESIGN.md's numbers — FROSTED takes
 * Floating Glass (blur on when a haze state exists, surface
 * alpha 0.72, blur 24 dp, violet ambient shadow); SOLID and
 * TINTED (which is also what plain mode / API < 31 resolves
 * to, by construction in AppearanceValidation) stay flat; the
 * shared stadium chip carries the DESIGN chip rule.
 *
 * Delta (Gemini sift A-8): the mapping also takes the
 * BACKDROP KIND. FROSTED over a live WebView (the EPUB
 * navigator) NEVER blurs — tint surface + violet border
 * instead — while FROSTED over app-rasterised backdrops (PDF
 * canvas, Compose surfaces) keeps true blur. Every row of
 * style × backdrop is pinned below.
 */
class ReaderGlassSpecTest {

    @Test
    fun `frosted with a haze state blurs at floating-glass numbers`() {
        val spec = readerGlassSpec(GlassStyle.FROSTED, hazeAvailable = true)
        assertTrue(spec.blur)
        assertEquals(0.72f, spec.surfaceAlpha, 0.0001f)
        assertEquals(24f, spec.blurRadiusDp, 0.0001f)
        assertEquals(0.10f, spec.borderAlpha, 0.0001f)
        assertTrue(spec.violetShadow)
    }

    @Test
    fun `frosted without a haze state cannot blur but keeps its tint`() {
        val spec = readerGlassSpec(GlassStyle.FROSTED, hazeAvailable = false)
        assertFalse("no haze source to sample means no blur node", spec.blur)
        assertEquals(0.72f, spec.surfaceAlpha, 0.0001f)
    }

    @Test
    fun `solid and tinted - the plain-mode landing styles - are flat`() {
        val solid = readerGlassSpec(GlassStyle.SOLID, hazeAvailable = true)
        assertFalse(solid.blur)
        assertEquals(1f, solid.surfaceAlpha, 0.0001f)
        assertFalse(solid.violetShadow)

        val tinted = readerGlassSpec(
            GlassStyle.TINTED, hazeAvailable = true, opacity = 0.85f
        )
        assertFalse(tinted.blur)
        assertEquals(0.85f, tinted.surfaceAlpha, 0.0001f)
        assertFalse(tinted.violetShadow)
    }

    @Test
    fun `frosted over a rasterised backdrop is the blur row`() {
        val spec = readerGlassSpec(
            GlassStyle.FROSTED,
            hazeAvailable = true,
            backdropKind = ReaderBackdropKind.RASTERISED
        )
        assertTrue("PDF canvas / Compose surfaces keep true blur", spec.blur)
        assertFalse(spec.violetBorder)
    }

    @Test
    fun `frosted over the webview never blurs - tint plus violet border`() {
        val withHaze = readerGlassSpec(
            GlassStyle.FROSTED,
            hazeAvailable = true,
            backdropKind = ReaderBackdropKind.WEBVIEW
        )
        assertFalse(
            "A-8: no hazeEffect over a live WebView, haze state or not",
            withHaze.blur
        )
        assertEquals(0f, withHaze.blurRadiusDp, 0.0001f)
        assertEquals(
            "the tint surface keeps the Floating Glass alpha",
            0.72f, withHaze.surfaceAlpha, 0.0001f
        )
        assertTrue("the U-3 violet border treatment", withHaze.violetBorder)
        assertEquals(0.8f, withHaze.borderAlpha, 0.0001f)
        assertTrue(withHaze.violetShadow)

        val noHaze = readerGlassSpec(
            GlassStyle.FROSTED,
            hazeAvailable = false,
            backdropKind = ReaderBackdropKind.WEBVIEW
        )
        assertFalse(noHaze.blur)
        assertTrue(noHaze.violetBorder)
    }

    @Test
    fun `solid and tinted are flat over BOTH backdrop kinds`() {
        for (kind in ReaderBackdropKind.entries) {
            val solid = readerGlassSpec(
                GlassStyle.SOLID, hazeAvailable = true, backdropKind = kind
            )
            assertFalse("SOLID × $kind", solid.blur)
            assertFalse("SOLID × $kind", solid.violetBorder)
            val tinted = readerGlassSpec(
                GlassStyle.TINTED,
                hazeAvailable = true,
                opacity = 0.85f,
                backdropKind = kind
            )
            assertFalse("TINTED × $kind", tinted.blur)
            assertFalse("TINTED × $kind", tinted.violetBorder)
            assertEquals(0.85f, tinted.surfaceAlpha, 0.0001f)
        }
    }

    @Test
    fun `the resolver default backdrop is rasterised - pdf stays blur capable`() {
        val spec = readerGlassSpec(GlassStyle.FROSTED, hazeAvailable = true)
        assertTrue(spec.blur)
        assertFalse(spec.violetBorder)
    }

    @Test
    fun `unselected chip is tier-2 slate with muted text`() {
        val chip = readerChipSpec(selected = false)
        assertEquals(Color(0xFF161822), chip.containerColor)
        assertEquals(Color.White.copy(alpha = 0.06f), chip.borderColor)
        assertEquals(Color(0xFF94A3B8), chip.labelColor)
        assertFalse(chip.glow)
    }

    @Test
    fun `selected chip carries the violet treatment`() {
        val chip = readerChipSpec(selected = true)
        assertEquals(
            Color(0xFF8B5CF6).copy(alpha = 0.8f), chip.borderColor
        )
        assertEquals(Color.White, chip.labelColor)
    }

    @Test
    fun `selected primary chip takes the violet surface and glows`() {
        val chip = readerChipSpec(selected = true, primary = true)
        assertEquals(Color(0xFF8B5CF6), chip.containerColor)
        assertEquals(Color.White, chip.labelColor)
        assertTrue("the primary state is the glow state", chip.glow)
    }
}
