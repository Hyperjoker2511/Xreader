package app.xreader.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xreader.data.prefs.DesignState
import app.xreader.data.prefs.M3ColourSource
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
 * COLOUR DOCTRINE (code 123, corrected by the owner's
 * rulings of 2026-11-24 ~17:54–17:56 and the code-124 cut):
 * this design IS the app's Material 3 design. Its LANGUAGE
 * is fixed — paper character, Fraunces serif, its shapes and
 * layouts — but its COLOUR is the user's: the face adopts
 * the shared colour state (Dynamic = the phone's palette,
 * or a picked colour / one of the six presets) and generates
 * its scheme, light and dark, from that seed through the
 * app's vendored MCU machinery ([McuScheme]) in this
 * design's own tonal recipe. Sage #3b6a34 is only the
 * starting colour for a fresh install that has chosen
 * nothing; for the sage seed the light scheme keeps the
 * approved design's `.theme-b` values verbatim (paper
 * #fbf9f3, sage primary #3b6a34, primary-container #bcf0ad,
 * secondary-container #d6e8cb, peach tertiary-container
 * #ffdbd0, ink #1b1c18). Nocturne's colour world is separate
 * and untouched. The dark/light choice rides the shared
 * family theme-mode state.
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

/**
 * Code 124: the six one-tap preset colours of the Design
 * page's colour bar (owner, 2026-11-24: "Just add 5 or 6
 * custom colors also — to select easily and use them").
 * Each is a seed the face generates from, light and dark,
 * in its own recipe. Sage is the design's own; Indigo's seed
 * is drawn from the banked Midnight Indigo design in the
 * same Gemini zip (its primary-container #3e4285 family);
 * Ocean blue, Violet (the settings-design violet #7c3aed),
 * Ember and Rose are chosen in the same mid-tone register.
 */
val EditorialColourPresets: List<Pair<String, Int>> = listOf(
    "Sage" to EDITORIAL_SEED_ARGB,
    "Indigo" to 0xFF4F52A8.toInt(),
    "Ocean blue" to 0xFF1D6FB8.toInt(),
    "Violet" to 0xFF7C3AED.toInt(),
    "Ember" to 0xFFBC4B1F.toInt(),
    "Rose" to 0xFFBE3E77.toInt()
)

/**
 * The seed this face wears right now, from the shared colour
 * state (code 124, the owner's colour ruling): DYNAMIC takes
 * the phone palette's primary as the seed (API 31+); PICKED
 * takes the picked colour. Each falls back to the other, and
 * then to sage — the fresh-install starting colour, reached
 * only when nothing has been chosen and the phone offers no
 * dynamic palette.
 */
fun editorialSeedArgb(context: Context, design: DesignState): Int {
    val dynamicSeed: Int? =
        if (Build.VERSION.SDK_INT >= 31) {
            dynamicLightColorScheme(context).primary.toArgb()
        } else {
            null
        }
    return when (design.colourSource) {
        M3ColourSource.PICKED ->
            design.seedArgb?.toInt() ?: dynamicSeed ?: EDITORIAL_SEED_ARGB
        M3ColourSource.DYNAMIC ->
            dynamicSeed ?: design.seedArgb?.toInt() ?: EDITORIAL_SEED_ARGB
    }
}

/**
 * The scheme for one seed in this design's recipe: the sage
 * seed keeps the approved design's verbatim light values
 * (and its FIDELITY dark); every other seed generates whole
 * through the MCU machinery in the user's chosen style.
 */
fun editorialSchemeFor(
    seedArgb: Int,
    dark: Boolean,
    style: M3ColourStyle
): ColorScheme =
    if (seedArgb == EDITORIAL_SEED_ARGB) {
        if (dark) editorialDarkColorScheme() else editorialLightColorScheme()
    } else {
        McuScheme.scheme(seedArgb, dark, style)
    }

fun resolveEditorialColorScheme(
    context: Context,
    design: DesignState,
    dark: Boolean
): ColorScheme = editorialSchemeFor(
    editorialSeedArgb(context, design), dark, design.colourStyle
)

/**
 * The AMOLED floor (owner approval A-121-4, carried into
 * this face with the colour state in code 124): the dark
 * scheme's floor roles go true black; High and Highest keep
 * a minimal step so stacked containers stay legible. Every
 * other role stays the generated scheme's own.
 */
internal fun ColorScheme.withAmoledFloor(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF0A0A0A),
    surfaceContainerHighest = Color(0xFF121212)
)

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
    val context = LocalContext.current
    val dark = m3DarkTheme(design)
    // Code 124: the scheme resolves from the shared colour
    // state (the owner's colour ruling) — the phone's palette
    // or the picked colour generates this face, light and
    // dark; sage only when nothing has been chosen.
    val seed = remember(context, design.colourSource, design.seedArgb) {
        editorialSeedArgb(context, design)
    }
    val resolvedDark = remember(seed, design.colourStyle) {
        editorialSchemeFor(seed, dark = true, design.colourStyle)
    }
    val darkColors = remember(resolvedDark, design.m3AmoledDark) {
        if (design.m3AmoledDark) {
            resolvedDark.withAmoledFloor()
        } else {
            resolvedDark
        }
    }
    val lightColors = remember(seed, design.colourStyle) {
        editorialSchemeFor(seed, dark = false, design.colourStyle)
    }
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
