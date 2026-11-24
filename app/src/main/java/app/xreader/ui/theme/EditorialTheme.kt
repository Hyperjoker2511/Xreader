package app.xreader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.DesignState
import app.xreader.data.prefs.M3ColourStyle
import app.xreader.data.prefs.M3MotionChoice

/**
 * Code 123 (the Editorial design): the Paper & Sage face the
 * owner picked from the Gemini zip and approved page by page
 * on 2026-11-24 (Library / Explore / Book details as 1:1
 * copies; Settings / Profile / History / Downloads as approved
 * extensions in its language; the remaining surfaces extended
 * in the same language under his "all in one build" word).
 *
 * COLOUR DOCTRINE (the owner's correction, 2026-11-24): the
 * design's beauty lives in its OWN palette. The light scheme
 * below is the design's `.theme-b` value set EXACTLY — paper
 * #fbf9f3, sage primary #3b6a34, primary-container #bcf0ad,
 * secondary-container #d6e8cb, peach tertiary-container
 * #ffdbd0, ink #1b1c18 — carried verbatim into the matching
 * scheme roles. Roles the design never names (secondary,
 * inverse, surface-dim/bright, the lowest container) are
 * filled by the MCU generation from the same sage seed, so
 * the scheme is complete and coherent without inventing a
 * single design value. The DARK scheme is generated whole
 * from the sage seed through the app's vendored MCU machinery
 * ([McuScheme], FIDELITY — the same style the M3 free picker
 * defaults to): the design's own night form, derived, never
 * borrowed from another design.
 *
 * Editorial does NOT follow dynamic colour and does NOT read
 * the colour bar: its palette is fixed by the design (the
 * owner's ruling: "Default is editorial — another follows my
 * phone or color wheel"). The Material 3 design keeps its
 * dynamic → baseline → picked order untouched; Nocturne is
 * untouched. Editorial's dark/light choice rides the shared
 * M3-family theme-mode state, as its settings live in the
 * same Design page.
 *
 * TYPE: Fraunces (bundled, OFL) carries the display and
 * headline styles — the design's editorial voice — and the
 * platform sans carries body and labels, the design's own
 * division (its Roboto Flex body maps to the platform sans;
 * Flex is not a bundleable Android family, so the platform
 * face stands in — recorded here, per the build brief).
 *
 * SHAPES: the design's forms — 24dp cards, 28dp sheets and
 * hero cards, pill and 12dp chip steps below them.
 */

/** The design's sage — the seed its dark scheme generates
 * from, and its light scheme's primary, verbatim. */
const val EDITORIAL_SEED_ARGB: Int = 0xFF3B6A34.toInt()

/** The Editorial light scheme: the design's own values in
 * their roles, over an MCU FIDELITY generation from the sage
 * seed that fills every role the design leaves unnamed. */
fun editorialLightColorScheme(): ColorScheme =
    McuScheme.scheme(EDITORIAL_SEED_ARGB, dark = false, M3ColourStyle.FIDELITY)
        .copy(
            primary = Color(0xFF3B6A34),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFBCF0AD),
            onPrimaryContainer = Color(0xFF002200),
            secondaryContainer = Color(0xFFD6E8CB),
            onSecondaryContainer = Color(0xFF112009),
            tertiary = Color(0xFF8F4C38),
            tertiaryContainer = Color(0xFFFFDBD0),
            onTertiaryContainer = Color(0xFF380D00),
            error = Color(0xFFBA1A1A),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            background = Color(0xFFFBF9F3),
            onBackground = Color(0xFF1B1C18),
            surface = Color(0xFFFBF9F3),
            onSurface = Color(0xFF1B1C18),
            surfaceVariant = Color(0xFFEFEDE6),
            onSurfaceVariant = Color(0xFF44483D),
            surfaceContainerLow = Color(0xFFF5F3EC),
            surfaceContainer = Color(0xFFEFEDE6),
            surfaceContainerHigh = Color(0xFFE9E7E0),
            surfaceContainerHighest = Color(0xFFE3E2DA),
            outline = Color(0xFF74796B),
            outlineVariant = Color(0xFFC4C8B9)
        )

/** The Editorial dark scheme: generated whole from the sage
 * seed through the vendored MCU machinery (FIDELITY). */
fun editorialDarkColorScheme(): ColorScheme =
    McuScheme.scheme(EDITORIAL_SEED_ARGB, dark = true, M3ColourStyle.FIDELITY)

fun resolveEditorialColorScheme(dark: Boolean): ColorScheme =
    if (dark) editorialDarkColorScheme() else editorialLightColorScheme()

/** The Editorial type scale: the platform scale's sizes and
 * line heights, with the display / headline / large-title
 * styles set in Fraunces semibold — the design sets its page
 * titles (34sp), section heads (19sp) and card titles in the
 * serif, and everything else in the body sans. */
val EditorialTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(
            fontFamily = FrauncesSerif, fontWeight = FontWeight.SemiBold
        ),
        displayMedium = base.displayMedium.copy(
            fontFamily = FrauncesSerif, fontWeight = FontWeight.SemiBold
        ),
        displaySmall = base.displaySmall.copy(
            fontFamily = FrauncesSerif, fontWeight = FontWeight.SemiBold
        ),
        headlineLarge = base.headlineLarge.copy(
            fontFamily = FrauncesSerif, fontWeight = FontWeight.SemiBold
        ),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = FrauncesSerif, fontWeight = FontWeight.SemiBold
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = FrauncesSerif, fontWeight = FontWeight.SemiBold
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = FrauncesSerif, fontWeight = FontWeight.SemiBold
        ),
        titleMedium = base.titleMedium,
        titleSmall = base.titleSmall,
        bodyLarge = base.bodyLarge,
        bodyMedium = base.bodyMedium,
        bodySmall = base.bodySmall,
        labelLarge = base.labelLarge,
        labelMedium = base.labelMedium,
        labelSmall = base.labelSmall
    )
}

/** The Editorial shape scale: 24dp cards (large), 28dp hero
 * cards and sheets (extraLarge), 16dp covers (medium), 12dp
 * chips (small), 8dp mini-chips (extraSmall). */
val EditorialShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** The AppScheme the theme helpers read under Editorial —
 * the same binding discipline as the M3 design's: text
 * variants bound to the scheme's own roles. */
private fun editorialAppScheme(
    darkColors: ColorScheme,
    lightColors: ColorScheme
): AppScheme = AppScheme(
    name = "Editorial",
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
 * The Editorial theme root — the M3ThemeRoot's sibling. It
 * provides the same locals the shared surfaces read
 * ([LocalDesignState], [LocalSelectionTint] bound to the
 * scheme's primary, [LocalAppScheme]) so every surface that
 * serves the M3 family composes under Editorial unchanged in
 * structure, wearing this palette, this serif and these
 * shapes. Motion follows the same family rule as M3
 * (Expressive default; Standard under the Standard/Reduced
 * picks or the Reduce-motion gate).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EditorialThemeRoot(
    design: DesignState,
    reduceMotion: Boolean,
    content: @Composable () -> Unit
) {
    val dark = m3DarkTheme(design)
    val darkColors = remember { editorialDarkColorScheme() }
    val lightColors = remember { editorialLightColorScheme() }
    val colors = if (dark) darkColors else lightColors
    val motionScheme = if (
        design.motionChoice == M3MotionChoice.STANDARD ||
        design.motionChoice == M3MotionChoice.REDUCED ||
        reduceMotion
    ) {
        MotionScheme.standard()
    } else {
        MotionScheme.expressive()
    }
    val appScheme = remember(darkColors, lightColors) {
        editorialAppScheme(darkColors, lightColors)
    }
    CompositionLocalProvider(
        LocalAppScheme provides appScheme,
        LocalSelectionTint provides colors.primary,
        LocalDesignState provides design
    ) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = motionScheme,
            shapes = EditorialShapes,
            typography = EditorialTypography,
            content = content
        )
    }
}
