package app.xreader.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.xreader.data.prefs.AppDesign
import app.xreader.data.prefs.DesignState
import app.xreader.data.prefs.M3ColourSource
import app.xreader.data.prefs.M3MotionChoice
import app.xreader.data.prefs.M3ThemeMode

/**
 * Code 120 (Material 3 design): the design's theme root and the
 * locals deep surfaces read instead of threading state through
 * every signature.
 *
 * [LocalDesignState] carries the active design's state (defaults
 * to the Nocturne state, so any surface composed outside the
 * themed root — previews, the Komikku tree — reads Nocturne).
 *
 * [LocalSelectionTint] is recheck erratum E1's binding: the
 * selection tint and handles follow the ACTIVE design's scheme.
 * Its default is the shipped ReaderViolet constant's value, so
 * under Nocturne — where nothing provides it — every selection
 * surface renders byte-identically to before this build. The
 * Material 3 theme provides the scheme's `primary` role.
 */
val LocalDesignState = compositionLocalOf { DesignState() }

val LocalSelectionTint = compositionLocalOf { Color(0xFF8B5CF6) }

/** The M3 design's dark/light decision (§2.11: its own mode,
 * default Dark — Nocturne's theme mode is never consulted). */
@Composable
fun m3DarkTheme(design: DesignState): Boolean = when (design.themeMode) {
    M3ThemeMode.DARK -> true
    M3ThemeMode.LIGHT -> false
    M3ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
}

/**
 * The M3 colour resolution (§1.1.1's three doors):
 *  1. Dynamic colour (the default): the platform scheme as
 *     generated on API 31+ — never re-varianted, never
 *     sanitised (that treatment is Nocturne's own path).
 *  2. A picked colour: MCU generation in the chosen style
 *     (Fidelity default). Until a colour exists, the picker
 *     falls back to door 1 (or door 3 below API 31).
 *  3. Where dynamic colour does not exist (API < 31): Google's
 *     baseline scheme — the library's own default
 *     [lightColorScheme]/[darkColorScheme] tokens, which ARE
 *     the baseline scheme as Google ships it (Q7 ruling).
 */
fun resolveM3ColorScheme(context: Context, design: DesignState, dark: Boolean): ColorScheme {
    val dynamic: ColorScheme? =
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    val picked: ColorScheme? = design.seedArgb?.let { seed ->
        McuScheme.scheme(seed.toInt(), dark, design.colourStyle)
    }
    val baseline: ColorScheme = if (dark) darkColorScheme() else lightColorScheme()
    return when (design.colourSource) {
        M3ColourSource.DYNAMIC -> dynamic ?: baseline
        M3ColourSource.PICKED -> picked ?: dynamic ?: baseline
    }
}

/**
 * The official 15-style type scale (§1.2) with the brand-font
 * mapping (recheck note N9 — families are applied per group, the
 * tokens themselves are the library's spec defaults, weights and
 * metrics untouched): Jakarta for Display / Headline / Title,
 * Inter for Body / Label, emphasized variants (material3 1.5)
 * following their base style's family. The closed emphasized
 * list (addendum A5) is a usage rule for surfaces, not a token.
 */
private val SpecTypography = Typography()

val M3Typography: Typography = Typography(
    displayLarge = SpecTypography.displayLarge.copy(fontFamily = JakartaSans),
    displayMedium = SpecTypography.displayMedium.copy(fontFamily = JakartaSans),
    displaySmall = SpecTypography.displaySmall.copy(fontFamily = JakartaSans),
    headlineLarge = SpecTypography.headlineLarge.copy(fontFamily = JakartaSans),
    headlineMedium = SpecTypography.headlineMedium.copy(fontFamily = JakartaSans),
    headlineSmall = SpecTypography.headlineSmall.copy(fontFamily = JakartaSans),
    titleLarge = SpecTypography.titleLarge.copy(fontFamily = JakartaSans),
    titleMedium = SpecTypography.titleMedium.copy(fontFamily = JakartaSans),
    titleSmall = SpecTypography.titleSmall.copy(fontFamily = JakartaSans),
    bodyLarge = SpecTypography.bodyLarge.copy(fontFamily = InterSans),
    bodyMedium = SpecTypography.bodyMedium.copy(fontFamily = InterSans),
    bodySmall = SpecTypography.bodySmall.copy(fontFamily = InterSans),
    labelLarge = SpecTypography.labelLarge.copy(fontFamily = InterSans),
    labelMedium = SpecTypography.labelMedium.copy(fontFamily = InterSans),
    labelSmall = SpecTypography.labelSmall.copy(fontFamily = InterSans),
    displayLargeEmphasized = SpecTypography.displayLargeEmphasized.copy(fontFamily = JakartaSans),
    displayMediumEmphasized = SpecTypography.displayMediumEmphasized.copy(fontFamily = JakartaSans),
    displaySmallEmphasized = SpecTypography.displaySmallEmphasized.copy(fontFamily = JakartaSans),
    headlineLargeEmphasized = SpecTypography.headlineLargeEmphasized.copy(fontFamily = JakartaSans),
    headlineMediumEmphasized = SpecTypography.headlineMediumEmphasized.copy(fontFamily = JakartaSans),
    headlineSmallEmphasized = SpecTypography.headlineSmallEmphasized.copy(fontFamily = JakartaSans),
    titleLargeEmphasized = SpecTypography.titleLargeEmphasized.copy(fontFamily = JakartaSans),
    titleMediumEmphasized = SpecTypography.titleMediumEmphasized.copy(fontFamily = JakartaSans),
    titleSmallEmphasized = SpecTypography.titleSmallEmphasized.copy(fontFamily = JakartaSans),
    bodyLargeEmphasized = SpecTypography.bodyLargeEmphasized.copy(fontFamily = InterSans),
    bodyMediumEmphasized = SpecTypography.bodyMediumEmphasized.copy(fontFamily = InterSans),
    bodySmallEmphasized = SpecTypography.bodySmallEmphasized.copy(fontFamily = InterSans),
    labelLargeEmphasized = SpecTypography.labelLargeEmphasized.copy(fontFamily = InterSans),
    labelMediumEmphasized = SpecTypography.labelMediumEmphasized.copy(fontFamily = InterSans),
    labelSmallEmphasized = SpecTypography.labelSmallEmphasized.copy(fontFamily = InterSans)
)

/** The AppScheme the theme helpers (primaryTextColor() and
 * family) read under the M3 design: the design's own generated
 * pair, with the text variants bound to the scheme roles
 * (primary reads as text in both modes by construction of the
 * MCU tones; AI = tertiary, error = error, success = secondary). */
private fun m3AppScheme(darkColors: ColorScheme, lightColors: ColorScheme): AppScheme =
    AppScheme(
        name = "Material 3",
        dark = darkColors,
        light = lightColors,
        primaryTextDark = darkColors.primary,
        primaryTextLight = lightColors.primary,
        aiTextDark = darkColors.tertiary,
        aiTextLight = lightColors.tertiary,
        errorTextDark = darkColors.error,
        errorTextLight = lightColors.error,
        sageTextDark = darkColors.secondary,
        sageTextLight = lightColors.secondary
    )

/**
 * The Material 3 theme itself: [MaterialExpressiveTheme] with
 * the resolved scheme, the expressive motion scheme at theme
 * level (§1.4 — Standard when the user picks it or Reduce
 * motion is on), the spec shape tokens (the library's [Shapes]
 * defaults ARE the §1.3 library: 4/8/12/16/28 + the increased
 * set) and the official type scale above.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun M3ThemeRoot(
    design: DesignState,
    reduceMotion: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = m3DarkTheme(design)
    val darkColors = remember(context, design) { resolveM3ColorScheme(context, design, true) }
    val lightColors = remember(context, design) { resolveM3ColorScheme(context, design, false) }
    val colors = if (dark) darkColors else lightColors
    val motionScheme = if (
        design.motionChoice == M3MotionChoice.STANDARD || reduceMotion
    ) {
        MotionScheme.standard()
    } else {
        MotionScheme.expressive()
    }
    val appScheme = remember(darkColors, lightColors) { m3AppScheme(darkColors, lightColors) }
    CompositionLocalProvider(
        LocalAppScheme provides appScheme,
        LocalSelectionTint provides colors.primary,
        LocalDesignState provides design
    ) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = motionScheme,
            shapes = Shapes(),
            typography = M3Typography,
            content = content
        )
    }
}

/** True when the active design (as read from [LocalDesignState])
 * is Material 3 — the branch condition surfaces use. */
@Composable
fun isMaterial3Design(): Boolean = LocalDesignState.current.design == AppDesign.MATERIAL3
