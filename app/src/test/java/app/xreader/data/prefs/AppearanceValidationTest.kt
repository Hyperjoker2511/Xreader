package app.xreader.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stored appearance values are validated on read (PLAN §1.5): corrupt
 * or out-of-range preferences degrade to safe design defaults. */
class AppearanceValidationTest {

    @Test
    fun `liquid style resolves to liquid wherever blur is possible`() {
        // Visible-layer fix round (§B): LIQUID no longer
        // collapses to FROSTED in the style resolver — it is
        // renderable in its own right (the tier decides LIQUID
        // vs FROSTED rendering; the style only gates blur
        // possibility, exactly FROSTED's bar).
        assertEquals(
            GlassStyle.LIQUID,
            AppearanceValidation.effectiveGlassStyle(GlassStyle.LIQUID, 31, 4096)
        )
        assertEquals(
            GlassStyle.LIQUID,
            AppearanceValidation.effectiveGlassStyle(GlassStyle.LIQUID, 36, 6144)
        )
        // The boundary: RAM just above the 2048 bar still earns it.
        assertEquals(
            GlassStyle.LIQUID,
            AppearanceValidation.effectiveGlassStyle(GlassStyle.LIQUID, 31, 2049)
        )
    }

    @Test
    fun `liquid style falls to tinted below the blur bar`() {
        // Below API 31 there is no RenderEffect; at exactly
        // 2048 MB the device is on the low-RAM tier.
        assertEquals(
            GlassStyle.TINTED,
            AppearanceValidation.effectiveGlassStyle(GlassStyle.LIQUID, 30, 6144)
        )
        assertEquals(
            GlassStyle.TINTED,
            AppearanceValidation.effectiveGlassStyle(GlassStyle.LIQUID, 36, 2048)
        )
    }

    @Test
    fun `liquid fallback notes name the tier actually used`() {
        assertEquals(
            "Liquid glass needs a stronger device — using tinted glass here.",
            AppearanceValidation.glassFallbackNote(GlassStyle.LIQUID, 30, 6144)
        )
        // API 31/32: the style resolves to LIQUID but the shader
        // leg needs API 33+, so the tier is FROSTED at best.
        assertEquals(
            "Liquid glass needs a stronger device — using frosted glass here.",
            AppearanceValidation.glassFallbackNote(GlassStyle.LIQUID, 32, 6144)
        )
        // A fully capable device gets no note.
        assertNull(
            AppearanceValidation.glassFallbackNote(GlassStyle.LIQUID, 36, 6144)
        )
    }

    @Test
    fun `glass opacity clamps into its range`() {
        assertEquals(1.0f, AppearanceValidation.clampGlassOpacity(2.0f), 0f)
        assertEquals(0.55f, AppearanceValidation.clampGlassOpacity(0.1f), 0f)
        assertEquals(0.8f, AppearanceValidation.clampGlassOpacity(0.8f), 0f)
        assertEquals(
            AppearanceValidation.DEFAULT_GLASS_OPACITY,
            AppearanceValidation.clampGlassOpacity(Float.NaN), 0f
        )
    }

    @Test
    fun `radius and elevation scales clamp`() {
        assertEquals(1.5f, AppearanceValidation.clampRadiusScale(99f), 0f)
        assertEquals(0.5f, AppearanceValidation.clampRadiusScale(0f), 0f)
        assertEquals(0.0f, AppearanceValidation.clampElevationScale(-3f), 0f)
        assertEquals(2.0f, AppearanceValidation.clampElevationScale(7f), 0f)
        assertEquals(
            AppearanceValidation.DEFAULT_ELEVATION_SCALE,
            AppearanceValidation.clampElevationScale(Float.NaN), 0f
        )
    }

    @Test
    fun `theme mode parses defensively`() {
        assertEquals(ThemeMode.DARK, AppearanceValidation.parseThemeMode("DARK"))
        assertEquals(ThemeMode.LIGHT, AppearanceValidation.parseThemeMode("LIGHT"))
        assertEquals(ThemeMode.FOLLOW_SYSTEM, AppearanceValidation.parseThemeMode(null))
        assertEquals(ThemeMode.FOLLOW_SYSTEM, AppearanceValidation.parseThemeMode("garbage"))
        assertEquals(ThemeMode.FOLLOW_SYSTEM, AppearanceValidation.parseThemeMode(""))
    }

    @Test
    fun `validate sanitises a whole state`() {
        val dirty = AppearanceState(
            glassOpacity = 42f,
            glassRadiusScale = Float.NaN,
            glassElevationScale = -1f
        )
        val clean = AppearanceValidation.validate(dirty)
        assertTrue(clean.glassOpacity in AppearanceValidation.GLASS_OPACITY_RANGE)
        assertEquals(AppearanceValidation.DEFAULT_RADIUS_SCALE, clean.glassRadiusScale, 0f)
        assertEquals(0.0f, clean.glassElevationScale, 0f)
    }
}
