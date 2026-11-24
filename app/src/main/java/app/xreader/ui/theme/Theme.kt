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
import app.xreader.data.prefs.GlassTier
import app.xreader.data.prefs.PerformancePolicy
import app.xreader.data.prefs.resolveGlassTier
import dev.chrisbanes.haze.HazeState

/**
 * The global glass controls (PLAN §2.2): opacity, corner radius and
 * elevation as *scales over the design tokens*, driven live by the
 * Appearance screen. The no-blur rule is structural: glass here is a
 * surface colour at an alpha + a hairline border + a soft shadow —
 * there is no backdrop-blur API anywhere in this design system.
 */
/**
 * v1.0.6.2 (F7): the extra lift every floating bottom bar sits
 * ABOVE the system gesture/navigation inset — one shared amount
 * so the reader's bottom bar and the main bottom-navigation pill
 * float at the same height. (The user's phone has a dead strip
 * at the very bottom of its screen; controls must float clear
 * of the edge.)
 */
val FloatingChromeLift: Dp = 14.dp

data class GlassSettings(
    val opacity: Float = 1.0f,
    val radiusScale: Float = 1.0f,
    val elevationScale: Float = 1.0f,
    /** The user's glass choice (Appearance). */
    val style: GlassStyle = GlassStyle.TINTED,
    /** The style actually rendered on this device (FROSTED may have
     * fallen back to TINTED — see AppearanceValidation). */
    val effectiveStyle: GlassStyle = GlassStyle.TINTED,
    /** The resolved glass TIER for this device (visible-layer fix
     * round, spec §B): the single resolver's verdict
     * ([resolveGlassTier] over the choice, SDK, RAM and the
     * performance tier), computed once here in the theme so every
     * surface — dock, cards, sheets — reads the SAME tier. The
     * dock has resolved its own since Patch 1; this generalises
     * that resolution to the whole app. */
    val tier: GlassTier = GlassTier.TINTED,
    /** The user's film-grain setting (AppearanceState.grain),
     * carried here so glass surfaces can read it from the same
     * local as every other glass value: static-variant glass
     * plans draw the blueprint's dither layer only while this
     * is on. Default off — the setting's own default. */
    val grainOn: Boolean = false
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
    fun glassSurface(surface: Color): Color = when (style) {
        GlassStyle.SOLID -> surface
        GlassStyle.TINTED -> surface.copy(alpha = opacity)
        // Glass blueprint (2026-11-15): the alphas mirror the
        // style+tier plan fills in glassEffectPlanFor, so the
        // surfaces that still consume glassSurface (rail, reader
        // chrome, the Me card) agree with the plan-rendered ones.
        // FROSTED: 0.72 live (the contrast-pinned value — do not
        // drift); its static variant (tier resolved below
        // FROSTED) takes the raised 0.78.
        GlassStyle.FROSTED -> surface.copy(
            alpha = if (tier == GlassTier.TINTED || tier == GlassTier.SOLID) {
                0.78f
            } else {
                0.72f
            }
        )
        // LIQUID: the identity's fill follows the identity, not
        // the tier it landed on — 0.62 wherever the identity
        // renders with backdrop blur (LIQUID or FROSTED tier);
        // the static variant (TINTED tier) takes the raised 0.68.
        // (Pre-blueprint, a down-resolved LIQUID choice borrowed
        // FROSTED's 0.72 — the identity-erasure this build ends.)
        GlassStyle.LIQUID -> surface.copy(
            alpha = when (tier) {
                GlassTier.LIQUID, GlassTier.FROSTED -> 0.62f
                else -> 0.68f
            }
        )
    }

    /** Whether the FROSTED *style* is the effective one. Style
     * branches (GlassCard's frosted fill/border treatment) key on
     * the resolved TIER now; blur gates use [blurLive]. */
    val frosted: Boolean get() = effectiveStyle == GlassStyle.FROSTED

    /** A backdrop-blur tier is live: LIQUID or FROSTED. Every
     * blur gate in the tree reads this (the fix round migrated
     * them off the style check, under which a LIQUID choice —
     * whose effectiveStyle never equalled FROSTED — composed no
     * Haze source and no effect at all). */
    val blurLive: Boolean
        get() = tier == GlassTier.LIQUID || tier == GlassTier.FROSTED
}

/** The shared Haze state: ONE state for the whole shell. Since
 * v1.0.4 (D1) the shell's mesh backdrop layer is the haze SOURCE
 * and the glass surfaces — cards, nav bar, rail, reader bars — are
 * the effects sampling it; before that the NavHost content itself
 * was the source, which structurally forbade card effects (R01). */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** The window-bottom zone the overlay dock occupies on the
 * current destination (visible-layer fix round, spec §A.2):
 * 0.dp when the dock is hidden, immersive, or the shell is the
 * rail shell (which never provides it). Scroll ends repay it
 * through effectiveBottomPadding so content never rests behind
 * the bar. Provided by the phone shell, which derives it from
 * the SAME visibility expression that composes the bar, so the
 * bar and the clearance can never disagree. */
val LocalDockClearance = compositionLocalOf { 0.dp }

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
    /** Code 120: the whole-app design switch. The default is the
     * Nocturne state, so every call site that does not pass it
     * (including the whole Komikku tree) renders exactly as
     * before. When the design is Material 3, the content delegates
     * to [M3ThemeRoot] inside the shared providers below — the
     * app-size density and UiMetrics apply to both designs (App
     * size is a settled behaviour rule, not a Nocturne feature),
     * while Nocturne's colour resolution is simply not read by
     * the M3 path and Nocturne's state is never written by it. */
    design: app.xreader.data.prefs.DesignState =
        app.xreader.data.prefs.DesignState(),
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
    // Visible-layer fix round (§B): the glass TIER, resolved
    // exactly as the dock resolves it — the performance tier
    // from the same inputs, then the single tier resolver — so
    // the theme and the dock can never disagree about which
    // tier this device is on.
    val perfTier = PerformancePolicy.resolve(
        appearance.performanceMode, totalRamMb, appearance.coverGlow
    ).tier
    val glassTier = resolveGlassTier(
        choice = appearance.glassStyle,
        sdkInt = Build.VERSION.SDK_INT,
        totalRamMb = totalRamMb,
        perfTier = perfTier
    )
    val glass = GlassSettings(
        opacity = appearance.glassOpacity,
        radiusScale = appearance.glassRadiusScale,
        elevationScale = appearance.glassElevationScale,
        style = appearance.glassStyle,
        effectiveStyle = effective,
        tier = glassTier,
        grainOn = appearance.grain
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
    // v1.6.2 (App size): the ONE size number IS the density's
    // fontScale. The layout metrics scale by the same number
    // in ProvideUiMetrics below, so words and layout stay in
    // proportion — the v1.0.2 text-size % scaled the words
    // alone and left every card, door and button behind (the
    // defect the owner's 2 GB phone proof convicted). The
    // system font scale does NOT multiply on top (owner's
    // no-stacking rule, 2026-11-16): the number picked in
    // Customise is exactly the size the app renders, on any
    // phone. Reader text keeps its own size setting and its
    // own rendering path, untouched as before.
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = remember(baseDensity, appearance.appSize) {
        androidx.compose.ui.unit.Density(
            density = baseDensity.density,
            fontScale = appearance.appSize
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
        // Adaptive metrics (Patch 5) + App size (v1.6.2):
        // LocalUiMetrics installed at the root from the live
        // window width, scaled by the user's App size, beside
        // the other theme-level locals, so any screen can read
        // its scaled tier row.
        ProvideUiMetrics(appearance.appSize) {
            if (design.design == app.xreader.data.prefs.AppDesign.MATERIAL3) {
                M3ThemeRoot(
                    design = design,
                    reduceMotion = appearance.reduceMotion,
                    content = content
                )
            } else {
                CompositionLocalProvider(
                    LocalDesignState provides design
                ) {
                    MaterialTheme(
                        colorScheme = colors,
                        typography = XreaderTypography,
                        shapes = glass.shapes(),
                        content = content
                    )
                }
            }
        }
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
