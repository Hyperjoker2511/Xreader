package app.xreader.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stored appearance values are validated on read (PLAN §1.5): corrupt
 * or out-of-range preferences degrade to safe design defaults. */
class AppearanceValidationTest {

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
