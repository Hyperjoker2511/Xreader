package app.xreader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import app.xreader.data.prefs.AppearanceState
import app.xreader.data.prefs.AppearanceValidation
import app.xreader.data.prefs.CUSTOM_SCHEME_PREFIX
import app.xreader.data.prefs.GlassStyle
import dev.chrisbanes.haze.HazeState

/**
 * The global glass controls (PLAN §2.2): opacity, corner radius and
 * elevation as *scales over the design tokens*, driven live by the
 * Appearance screen. The no-blur rule is structural: glass here is a
 * surface colour at an alpha + a hairline border + a soft shadow —
 * there is no backdrop-blur API anywhere in this design system.
 */
data class GlassSettings(
    val opacity: Float = 1.0f,
    val radiusScale: Float = 1.0f,
    val elevationScale: Float = 1.0f,
    /** The user's glass choice (Appearance). */
    val style: GlassStyle = GlassStyle.TINTED,
    /** The style actually rendered on this device (FROSTED may have
     * fallen back to TINTED — see AppearanceValidation). */
    val effectiveStyle: GlassStyle = GlassStyle.TINTED
) {
    /** Design card radius ~24 dp (DESIGN.md), scaled by the user's slider. */
    val cardRadius: Dp get() = (24f * radiusScale).dp
    /** Design cover radius ~16 dp. */
    val coverRadius: Dp get() = (16f * radiusScale).dp
    /** Sheet top radius 28 dp (PLAN §2.2). */
    val sheetRadius: Dp get() = (28f * radiusScale).dp
    val cardElevation: Dp get() = (3f * elevationScale).dp
    val raisedElevation: Dp get() = (6f * elevationScale).dp

    fun shapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape((8f * radiusScale).dp),
        small = RoundedCornerShape((12f * radiusScale).dp),
        medium = RoundedCornerShape(coverRadius),
        large = RoundedCornerShape(cardRadius),
        extraLarge = RoundedCornerShape(sheetRadius)
    )

    /** A glass surface colour at the user's opacity over [behind].
     * SOLID ignores the opacity slider (fully opaque, as promised);
     * FROSTED renders mostly translucent because the blur behind it
     * carries the surface — text still sits on the tint, whose pairs
     * are pinned by the contrast tests at this exact alpha. */
    fun glassSurface(surface: Color): Color = when (effectiveStyle) {
        GlassStyle.SOLID -> surface
        GlassStyle.FROSTED -> surface.copy(alpha = 0.72f)
        GlassStyle.TINTED -> surface.copy(alpha = opacity)
    }

    /** Whether this surface should get the real backdrop blur. */
    val frosted: Boolean get() = effectiveStyle == GlassStyle.FROSTED
}

/** The shared Haze state: the app content is the haze SOURCE and the
 * chrome surfaces (nav bar, rail, floating cards, reader bars) are
 * the effects — one state, shared, per the Haze docs' pattern, so
 * blur stays chrome-sized and never full-screen. */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** The active app colour scheme (v1.0.1), for accent-as-text helpers. */
val LocalAppScheme = compositionLocalOf { EmberScheme }

val LocalGlassSettings = compositionLocalOf { GlassSettings() }

// internal (not private) so the v1.0.1 contrast gate tests can pin
// every on* pair in this module's unit tests.
internal val DarkColors: ColorScheme = darkColorScheme(
    primary = EmberAmber,
    onPrimary = EmberTextLight,
    primaryContainer = EmberSurfaceHighDark,
    onPrimaryContainer = EmberTextDark,
    // v1.0.1: the fill sage/violet/red are DARKER than the design
    // accents so the cream on* text reaches 4.5:1 (was 4.36 / 3.87 /
    // 3.33). The bright accents still exist — as text tokens and
    // icon tints, where the helpers below pick the readable variant.
    secondary = Color(0xFF4E6153),
    onSecondary = EmberTextDark,
    secondaryContainer = EmberSurfaceHighDark,
    onSecondaryContainer = EmberTextDark,
    tertiary = Color(0xFF63539B),
    onTertiary = EmberTextDark,
    tertiaryContainer = EmberSurfaceHighDark,
    onTertiaryContainer = EmberTextDark,
    error = EmberErrorLight,
    onError = EmberTextDark,
    background = EmberBackgroundDark,
    onBackground = EmberTextDark,
    surface = EmberSurfaceDark,
    onSurface = EmberTextDark,
    surfaceVariant = EmberSurfaceHighDark,
    onSurfaceVariant = EmberTextMutedDark,
    outline = EmberHairlineDark,
    outlineVariant = EmberSurfaceHighDark,
    surfaceContainerLowest = EmberBackgroundDark,
    surfaceContainerLow = EmberSurfaceDark,
    surfaceContainer = EmberSurfaceDark,
    surfaceContainerHigh = EmberSurfaceHighDark,
    surfaceContainerHighest = EmberSurfaceHighDark
)

internal val LightColors: ColorScheme = lightColorScheme(
    primary = EmberAmber,
    // v1.0.1: white on the amber fill was 3.27:1 — the dark ink the
    // dark scheme already used reaches 5.14:1 and reads better.
    onPrimary = EmberTextLight,
    primaryContainer = EmberSurfaceHighLight,
    onPrimaryContainer = EmberTextLight,
    secondary = EmberSage,
    onSecondary = Color.White,
    secondaryContainer = EmberSurfaceHighLight,
    onSecondaryContainer = EmberTextLight,
    tertiary = EmberViolet,
    onTertiary = Color.White,
    tertiaryContainer = EmberSurfaceHighLight,
    onTertiaryContainer = EmberTextLight,
    error = EmberErrorLight,
    onError = Color.White,
    background = EmberBackgroundLight,
    onBackground = EmberTextLight,
    surface = EmberSurfaceLight,
    onSurface = EmberTextLight,
    surfaceVariant = EmberSurfaceHighLight,
    onSurfaceVariant = EmberTextMutedLight,
    outline = EmberHairlineLight,
    outlineVariant = EmberSurfaceHighLight,
    surfaceContainerLowest = EmberSurfaceLight,
    surfaceContainerLow = EmberSurfaceLight,
    surfaceContainer = EmberSurfaceHighLight,
    surfaceContainerHigh = EmberSurfaceHighLight,
    surfaceContainerHighest = EmberSurfaceHighLight
)

/**
 * Xreader's theme: Ember Ink colours + Jakarta/Inter type + the glass
 * settings from the user's Appearance state, all in one place (PLAN §3:
 * tokens live in ONE theme file so the app cannot drift from Stitch).
 */
@Composable
fun XreaderTheme(
    darkTheme: Boolean,
    appearance: AppearanceState = AppearanceState(),
    hazeReady: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val totalRamMb = remember(context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024L * 1024L)
        }.getOrDefault(4096L)
    }
    val effective = AppearanceValidation.effectiveGlassStyle(
        appearance.glassStyle, Build.VERSION.SDK_INT, totalRamMb
    )
    val glass = GlassSettings(
        opacity = appearance.glassOpacity,
        radiusScale = appearance.glassRadiusScale,
        elevationScale = appearance.glassElevationScale,
        style = appearance.glassStyle,
        effectiveStyle = effective
    )
    // v1.0.2 (R04): "Wallpaper" resolves to the platform dynamic
    // schemes on API 31+, contrast-sanitized by our own gate; on
    // older devices the name falls back to Ember (the picker hides
    // it there). A "custom:<id>" selection resolves through the
    // make-your-own generator; anything unresolvable falls back to
    // the named presets, then Ember.
    val scheme = if (appearance.colourScheme == "Wallpaper" &&
        Build.VERSION.SDK_INT >= 31
    ) {
        remember(context) {
            DynamicSchemeSanitizer.appScheme(
                androidx.compose.material3.dynamicDarkColorScheme(context),
                androidx.compose.material3.dynamicLightColorScheme(context)
            )
        }
    } else {
        remember(appearance.colourScheme, appearance.customSchemes) {
            val customId = appearance.colourScheme
                .takeIf { it.startsWith(CUSTOM_SCHEME_PREFIX) }
                ?.removePrefix(CUSTOM_SCHEME_PREFIX)
            val spec = appearance.customSchemes.firstOrNull { it.id == customId }
            if (spec != null) CustomSchemeGenerator.generate(spec)
            else appSchemeNamed(appearance.colourScheme)
        }
    }
    // v1.0.2 (R04 foundation): true black — in dark mode the scheme's
    // surfaces collapse to pure black (AMOLED); the pairs stay inside
    // the contrast gate (pinned by TrueBlackContrastTest).
    val colors = scheme.colors(darkTheme).let {
        if (darkTheme && appearance.trueBlack) it.withTrueBlack() else it
    }
    val hazeState = remember { HazeState() }
    // v1.0.2 (R07): app text size scales the density's fontScale —
    // app chrome only in effect, because reader text has its own
    // size setting and its own rendering path.
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = remember(baseDensity, appearance.appTextSizePercent) {
        androidx.compose.ui.unit.Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * appearance.appTextSizePercent / 100f
        )
    }
    // R01 boot sequence: while [hazeReady] is false the tree composes
    // WITHOUT a haze state, so the first frame can never draw a Haze
    // effect; the caller flips it true once the shell is up and every
    // frosted surface falls back to its tint in the meantime.
    CompositionLocalProvider(
        LocalGlassSettings provides glass,
        LocalAppScheme provides scheme,
        LocalHazeState provides if (hazeReady) hazeState else null,
        androidx.compose.ui.platform.LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = XreaderTypography,
            shapes = glass.shapes(),
            content = content
        )
    }
}

// ── Readable text variants of the accent colours (v1.0.1) ──────────
// The scheme's primary/tertiary/error tokens are FILL colours first:
// text drawn IN them on a surface failed 4.5:1 in one scheme or the
// other (amber text on paper 3.03, violet text on near-black 3.73,
// red text on near-black 4.4 for small copy). Any composable that
// wants accent-coloured TEXT uses these helpers instead — each one
// is pinned ≥4.5:1 on background/surface/surfaceVariant by the
// contrast gate tests.

/** True when the active scheme is a dark one (by its background). */
@Composable
fun isDarkScheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Amber for TEXT (links, eyebrows, the current-chapter row…). */
@Composable
fun primaryTextColor(): Color =
    if (isDarkScheme()) LocalAppScheme.current.primaryTextDark
    else LocalAppScheme.current.primaryTextLight

/** Violet for TEXT (AI headings and accents). */
@Composable
fun aiAccentColor(): Color =
    if (isDarkScheme()) LocalAppScheme.current.aiTextDark
    else LocalAppScheme.current.aiTextLight

/** Red for TEXT (inline error messages). */
@Composable
fun errorTextColor(): Color =
    if (isDarkScheme()) LocalAppScheme.current.errorTextDark
    else LocalAppScheme.current.errorTextLight

/** Sage for TEXT (downloaded / success readouts). */
@Composable
fun sageTextColor(): Color =
    if (isDarkScheme()) LocalAppScheme.current.sageTextDark
    else LocalAppScheme.current.sageTextLight
