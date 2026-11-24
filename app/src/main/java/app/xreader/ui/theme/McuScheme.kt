package app.xreader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.xreader.data.prefs.M3ColourStyle
import dynamiccolor.DynamicScheme
import dynamiccolor.MaterialDynamicColors
import hct.Hct
import quantize.QuantizerCelebi
import scheme.SchemeContent
import scheme.SchemeExpressive
import scheme.SchemeFidelity
import scheme.SchemeTonalSpot
import scheme.SchemeVibrant
import score.Score

/**
 * Code 120 (Material 3 design, scratch §2.1): the app's bridge to
 * Google's Material Color Utilities — the same library that
 * generates Android's dynamic colour — vendored as source under
 * the official packages (`hct`, `scheme`, `dynamiccolor`, …;
 * Apache-2.0, attributed in THIRD_PARTY_NOTICES.md) because MCU
 * is not published as a Maven artifact (verified 2026-11-24:
 * no `com.google.material:material-color-utilities` on Maven
 * Central or Google Maven).
 *
 * Everything the M3 design generates — the free picker's scheme,
 * the book page's cover-derived scheme — goes through here, so
 * the app speaks the platform's own colour language and no
 * house variant generation exists beside it (the Nocturne
 * design's own generator is untouched and serves only Nocturne).
 */
object McuScheme {

    private val dynamicColors = MaterialDynamicColors()

    /** A full Compose [ColorScheme] from a seed colour through
     * the named MCU scheme style, at the standard contrast level
     * (0.0 — the platform default; the design pins no other). */
    fun scheme(seedArgb: Int, dark: Boolean, style: M3ColourStyle): ColorScheme {
        val source = Hct.fromInt(seedArgb)
        val scheme: DynamicScheme = when (style) {
            M3ColourStyle.FIDELITY -> SchemeFidelity(source, dark, 0.0)
            M3ColourStyle.TONAL_SPOT -> SchemeTonalSpot(source, dark, 0.0)
            M3ColourStyle.VIBRANT -> SchemeVibrant(source, dark, 0.0)
            M3ColourStyle.EXPRESSIVE -> SchemeExpressive(source, dark, 0.0)
            M3ColourStyle.CONTENT -> SchemeContent(source, dark, 0.0)
        }
        return scheme.toComposeColorScheme(dark)
    }

    /** The book page's cover scheme (§2.5 R8): Content style from
     * the cover's own seed. */
    fun contentScheme(seedArgb: Int, dark: Boolean): ColorScheme =
        scheme(seedArgb, dark, M3ColourStyle.CONTENT)

    /**
     * The cover's seed colour by MCU's own extraction
     * (Celebi quantisation + Score ranking) — the same pipeline
     * Android uses to seed dynamic colour from a wallpaper.
     * Null when the pixels yield no usable colour.
     */
    fun coverSeedArgb(argbPixels: IntArray): Int? {
        if (argbPixels.isEmpty()) return null
        return runCatching {
            val counts = QuantizerCelebi.quantize(argbPixels, 128)
            Score.score(counts).firstOrNull()
        }.getOrNull()
    }

    /** Code 121 re-verified against the staged material3 1.5.0
     * sources: [ColorScheme] carries exactly 48 colour roles
     * and NO `shadow`; both branches below assign all 48. */
    private fun DynamicScheme.toComposeColorScheme(dark: Boolean): ColorScheme {
        fun c(color: dynamiccolor.DynamicColor): Color =
            Color(color.getArgb(this))
        val primary = c(dynamicColors.primary())
        return if (dark) {
            darkColorScheme(
                primary = primary,
                onPrimary = c(dynamicColors.onPrimary()),
                primaryContainer = c(dynamicColors.primaryContainer()),
                onPrimaryContainer = c(dynamicColors.onPrimaryContainer()),
                inversePrimary = c(dynamicColors.inversePrimary()),
                secondary = c(dynamicColors.secondary()),
                onSecondary = c(dynamicColors.onSecondary()),
                secondaryContainer = c(dynamicColors.secondaryContainer()),
                onSecondaryContainer = c(dynamicColors.onSecondaryContainer()),
                tertiary = c(dynamicColors.tertiary()),
                onTertiary = c(dynamicColors.onTertiary()),
                tertiaryContainer = c(dynamicColors.tertiaryContainer()),
                onTertiaryContainer = c(dynamicColors.onTertiaryContainer()),
                error = c(dynamicColors.error()),
                onError = c(dynamicColors.onError()),
                errorContainer = c(dynamicColors.errorContainer()),
                onErrorContainer = c(dynamicColors.onErrorContainer()),
                background = c(dynamicColors.background()),
                onBackground = c(dynamicColors.onBackground()),
                surface = c(dynamicColors.surface()),
                onSurface = c(dynamicColors.onSurface()),
                surfaceVariant = c(dynamicColors.surfaceVariant()),
                onSurfaceVariant = c(dynamicColors.onSurfaceVariant()),
                inverseSurface = c(dynamicColors.inverseSurface()),
                inverseOnSurface = c(dynamicColors.inverseOnSurface()),
                outline = c(dynamicColors.outline()),
                outlineVariant = c(dynamicColors.outlineVariant()),
                scrim = c(dynamicColors.scrim()),
                surfaceDim = c(dynamicColors.surfaceDim()),
                surfaceBright = c(dynamicColors.surfaceBright()),
                surfaceContainerLowest = c(dynamicColors.surfaceContainerLowest()),
                surfaceContainerLow = c(dynamicColors.surfaceContainerLow()),
                surfaceContainer = c(dynamicColors.surfaceContainer()),
                surfaceContainerHigh = c(dynamicColors.surfaceContainerHigh()),
                surfaceContainerHighest = c(dynamicColors.surfaceContainerHighest()),
                surfaceTint = primary,
                primaryFixed = c(dynamicColors.primaryFixed()),
                primaryFixedDim = c(dynamicColors.primaryFixedDim()),
                onPrimaryFixed = c(dynamicColors.onPrimaryFixed()),
                onPrimaryFixedVariant = c(dynamicColors.onPrimaryFixedVariant()),
                secondaryFixed = c(dynamicColors.secondaryFixed()),
                secondaryFixedDim = c(dynamicColors.secondaryFixedDim()),
                onSecondaryFixed = c(dynamicColors.onSecondaryFixed()),
                onSecondaryFixedVariant = c(dynamicColors.onSecondaryFixedVariant()),
                tertiaryFixed = c(dynamicColors.tertiaryFixed()),
                tertiaryFixedDim = c(dynamicColors.tertiaryFixedDim()),
                onTertiaryFixed = c(dynamicColors.onTertiaryFixed()),
                onTertiaryFixedVariant = c(dynamicColors.onTertiaryFixedVariant())
            )
        } else {
            lightColorScheme(
                primary = primary,
                onPrimary = c(dynamicColors.onPrimary()),
                primaryContainer = c(dynamicColors.primaryContainer()),
                onPrimaryContainer = c(dynamicColors.onPrimaryContainer()),
                inversePrimary = c(dynamicColors.inversePrimary()),
                secondary = c(dynamicColors.secondary()),
                onSecondary = c(dynamicColors.onSecondary()),
                secondaryContainer = c(dynamicColors.secondaryContainer()),
                onSecondaryContainer = c(dynamicColors.onSecondaryContainer()),
                tertiary = c(dynamicColors.tertiary()),
                onTertiary = c(dynamicColors.onTertiary()),
                tertiaryContainer = c(dynamicColors.tertiaryContainer()),
                onTertiaryContainer = c(dynamicColors.onTertiaryContainer()),
                error = c(dynamicColors.error()),
                onError = c(dynamicColors.onError()),
                errorContainer = c(dynamicColors.errorContainer()),
                onErrorContainer = c(dynamicColors.onErrorContainer()),
                background = c(dynamicColors.background()),
                onBackground = c(dynamicColors.onBackground()),
                surface = c(dynamicColors.surface()),
                onSurface = c(dynamicColors.onSurface()),
                surfaceVariant = c(dynamicColors.surfaceVariant()),
                onSurfaceVariant = c(dynamicColors.onSurfaceVariant()),
                inverseSurface = c(dynamicColors.inverseSurface()),
                inverseOnSurface = c(dynamicColors.inverseOnSurface()),
                outline = c(dynamicColors.outline()),
                outlineVariant = c(dynamicColors.outlineVariant()),
                scrim = c(dynamicColors.scrim()),
                surfaceDim = c(dynamicColors.surfaceDim()),
                surfaceBright = c(dynamicColors.surfaceBright()),
                surfaceContainerLowest = c(dynamicColors.surfaceContainerLowest()),
                surfaceContainerLow = c(dynamicColors.surfaceContainerLow()),
                surfaceContainer = c(dynamicColors.surfaceContainer()),
                surfaceContainerHigh = c(dynamicColors.surfaceContainerHigh()),
                surfaceContainerHighest = c(dynamicColors.surfaceContainerHighest()),
                surfaceTint = primary,
                primaryFixed = c(dynamicColors.primaryFixed()),
                primaryFixedDim = c(dynamicColors.primaryFixedDim()),
                onPrimaryFixed = c(dynamicColors.onPrimaryFixed()),
                onPrimaryFixedVariant = c(dynamicColors.onPrimaryFixedVariant()),
                secondaryFixed = c(dynamicColors.secondaryFixed()),
                secondaryFixedDim = c(dynamicColors.secondaryFixedDim()),
                onSecondaryFixed = c(dynamicColors.onSecondaryFixed()),
                onSecondaryFixedVariant = c(dynamicColors.onSecondaryFixedVariant()),
                tertiaryFixed = c(dynamicColors.tertiaryFixed()),
                tertiaryFixedDim = c(dynamicColors.tertiaryFixedDim()),
                onTertiaryFixed = c(dynamicColors.onTertiaryFixed()),
                onTertiaryFixedVariant = c(dynamicColors.onTertiaryFixedVariant())
            )
        }
    }
}
