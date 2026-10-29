package app.xreader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Wallpaper colours / Material You (v1.0.2, R04): the platform's
 * dynamic schemes are beautiful but the contrast gate is ours, not
 * the wallpaper's. [sanitize] walks every fill/on pair the app
 * actually renders text on and nudges any failing on-colour with
 * [Contrast.ensureContrast] — fills (the wallpaper's identity) are
 * never touched. Pure (operates on ColorScheme values), so the gate
 * tests pin it exactly like the preset schemes.
 *
 * The theme calls this only on API 31+, where dynamic schemes exist.
 */
object DynamicSchemeSanitizer {

    private fun readable(on: Color, fill: Color): Color {
        if (Contrast.ratio(on, fill) >= 4.5) return on
        val toward = if (isDark(fill)) Color.White else Color.Black
        return Contrast.ensureContrast(on, fill, toward)
    }

    private fun isDark(c: Color): Boolean =
        (0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue) < 0.4f

    fun sanitize(scheme: ColorScheme): ColorScheme = scheme.copy(
        onPrimary = readable(scheme.onPrimary, scheme.primary),
        onPrimaryContainer = readable(scheme.onPrimaryContainer, scheme.primaryContainer),
        onSecondary = readable(scheme.onSecondary, scheme.secondary),
        onSecondaryContainer = readable(scheme.onSecondaryContainer, scheme.secondaryContainer),
        onTertiary = readable(scheme.onTertiary, scheme.tertiary),
        onTertiaryContainer = readable(scheme.onTertiaryContainer, scheme.tertiaryContainer),
        onError = readable(scheme.onError, scheme.error),
        onErrorContainer = readable(scheme.onErrorContainer, scheme.errorContainer),
        onBackground = readable(scheme.onBackground, scheme.background),
        onSurface = readable(scheme.onSurface, scheme.surface),
        onSurfaceVariant = readable(scheme.onSurfaceVariant, scheme.surfaceVariant)
    )

    /** Build the [AppScheme] the theme exposes for accent-as-text
     * helpers: both dynamic modes sanitized, text tokens derived
     * from each mode's primary with the same ensureContrast rule. */
    fun appScheme(darkDynamic: ColorScheme, lightDynamic: ColorScheme): AppScheme {
        val dark = sanitize(darkDynamic)
        val light = sanitize(lightDynamic)
        fun textToken(primary: Color, grounds: List<Color>, toward: Color): Color =
            grounds.fold(primary) { c, bg -> Contrast.ensureContrast(c, bg, toward) }
        return AppScheme(
            name = "Wallpaper",
            dark = dark,
            light = light,
            primaryTextDark = textToken(
                dark.primary, listOf(dark.background, dark.surface, dark.surfaceVariant),
                Color.White
            ),
            primaryTextLight = textToken(
                light.primary, listOf(light.background, light.surface, light.surfaceVariant),
                Color.Black
            ),
            aiTextDark = textToken(
                dark.tertiary, listOf(dark.background, dark.surface), Color.White
            ),
            aiTextLight = textToken(
                light.tertiary, listOf(light.background, light.surface), Color.Black
            ),
            errorTextDark = textToken(
                dark.error, listOf(dark.background, dark.surface), Color.White
            ),
            errorTextLight = textToken(
                light.error, listOf(light.background, light.surface), Color.Black
            ),
            sageTextDark = EmberSageTextDark,
            sageTextLight = Color(0xFF44543F)
        )
    }
}
