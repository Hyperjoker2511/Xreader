package app.xreader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * App colour schemes (v1.0.1): complete token bundles the user picks
 * in Appearance → Colour theme. Ember is the original design and stays
 * the default, pixel-identical (it reuses [DarkColors]/[LightColors]
 * verbatim). Every other scheme is built by [buildScheme] from a small
 * family of tints, and EVERY scheme's on* pairs are pinned ≥4.5:1 by
 * the contrast gate tests — a scheme that can't be read can't ship.
 *
 * [primaryTextDark]/[primaryTextLight] etc. are the accent-as-TEXT
 * tokens for the scheme (links, eyebrows, AI headings, errors): fills
 * and text variants are separate because no single accent satisfies
 * both directions at 4.5:1.
 */
data class AppScheme(
    val name: String,
    val dark: ColorScheme,
    val light: ColorScheme,
    val primaryTextDark: Color,
    val primaryTextLight: Color,
    val aiTextDark: Color,
    val aiTextLight: Color,
    val errorTextDark: Color,
    val errorTextLight: Color,
    val sageTextDark: Color,
    val sageTextLight: Color
) {
    fun colors(darkTheme: Boolean): ColorScheme = if (darkTheme) dark else light
}

/** Builds one mode's scheme from family tints. The on* colour for each
 * fill is whichever of warm-white / ink reads better on it — chosen by
 * the real WCAG function, not by eye. */
internal fun buildScheme(
    dark: Boolean,
    primary: Color, secondary: Color, tertiary: Color, error: Color,
    background: Color, surface: Color, surfaceHigh: Color,
    text: Color, muted: Color, hairline: Color
): ColorScheme {
    fun on(fill: Color): Color =
        if (Contrast.ratio(Color.White, fill) >= Contrast.ratio(text, fill) &&
            Contrast.ratio(Color.White, fill) >= 4.5
        ) Color.White
        else if (Contrast.ratio(text, fill) >= 4.5) text
        else Contrast.ensureContrast(
            if (Contrast.ratio(Color.White, fill) > Contrast.ratio(text, fill)) Color.White else text,
            fill, toward = if (background.luminanceIsDark()) Color.White else Color.Black
        )
    return if (dark) darkColorScheme(
        primary = primary, onPrimary = on(primary),
        primaryContainer = surfaceHigh, onPrimaryContainer = text,
        secondary = secondary, onSecondary = on(secondary),
        secondaryContainer = surfaceHigh, onSecondaryContainer = text,
        tertiary = tertiary, onTertiary = on(tertiary),
        tertiaryContainer = surfaceHigh, onTertiaryContainer = text,
        error = error, onError = on(error),
        background = background, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = surfaceHigh, onSurfaceVariant = muted,
        outline = hairline, outlineVariant = surfaceHigh,
        surfaceContainerLowest = background, surfaceContainerLow = surface,
        surfaceContainer = surface, surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh
    ) else lightColorScheme(
        primary = primary, onPrimary = on(primary),
        primaryContainer = surfaceHigh, onPrimaryContainer = text,
        secondary = secondary, onSecondary = on(secondary),
        secondaryContainer = surfaceHigh, onSecondaryContainer = text,
        tertiary = tertiary, onTertiary = on(tertiary),
        tertiaryContainer = surfaceHigh, onTertiaryContainer = text,
        error = error, onError = on(error),
        background = background, onBackground = text,
        surface = surface, onSurface = text,
        surfaceVariant = surfaceHigh, onSurfaceVariant = muted,
        outline = hairline, outlineVariant = surfaceHigh,
        surfaceContainerLowest = surface, surfaceContainerLow = surface,
        surfaceContainer = surfaceHigh, surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh
    )
}

private fun Color.luminanceIsDark(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.4f

private fun scheme(
    name: String,
    darkPrimary: Color, darkSecondary: Color, darkTertiary: Color,
    lightPrimary: Color, lightSecondary: Color, lightTertiary: Color,
    darkBg: Color, darkSurface: Color, darkHigh: Color, darkText: Color, darkMuted: Color, darkHair: Color,
    lightBg: Color, lightSurface: Color, lightHigh: Color, lightText: Color, lightMuted: Color, lightHair: Color,
    primaryTextDark: Color, primaryTextLight: Color,
    aiTextDark: Color, aiTextLight: Color
): AppScheme = AppScheme(
    name = name,
    dark = buildScheme(true, darkPrimary, darkSecondary, darkTertiary, EmberErrorLight,
        darkBg, darkSurface, darkHigh, darkText, darkMuted, darkHair),
    light = buildScheme(false, lightPrimary, lightSecondary, lightTertiary, EmberErrorLight,
        lightBg, lightSurface, lightHigh, lightText, lightMuted, lightHair),
    primaryTextDark = primaryTextDark, primaryTextLight = primaryTextLight,
    aiTextDark = aiTextDark, aiTextLight = aiTextLight,
    errorTextDark = EmberErrorTextDark, errorTextLight = EmberErrorLight,
    sageTextDark = EmberSageTextDark, sageTextLight = Color(0xFF44543F)
)

val EmberScheme = AppScheme(
    name = "Ember",
    dark = DarkColors, light = LightColors,
    primaryTextDark = EmberAmber, primaryTextLight = EmberAmberTextLight,
    aiTextDark = EmberVioletTextDark, aiTextLight = EmberVioletTextLight,
    errorTextDark = EmberErrorTextDark, errorTextLight = EmberErrorLight,
    sageTextDark = EmberSageTextDark, sageTextLight = Color(0xFF44543F)
)

val VioletNightScheme = scheme(
    name = "Violet night",
    darkPrimary = Color(0xFF5E4E96), darkSecondary = Color(0xFF4E6153), darkTertiary = Color(0xFF7A4E72),
    lightPrimary = Color(0xFF5E4E96), lightSecondary = Color(0xFF5F7161), lightTertiary = Color(0xFF7C6AAE),
    darkBg = Color(0xFF131118), darkSurface = Color(0xFF1E1A29), darkHigh = Color(0xFF282136),
    darkText = Color(0xFFEFEBF7), darkMuted = Color(0xFFADA3C4), darkHair = Color(0xFF3B3352),
    lightBg = Color(0xFFF6F3FC), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFEAE3F7),
    lightText = Color(0xFF201A30), lightMuted = Color(0xFF655B7D), lightHair = Color(0xFFCFC4E6),
    primaryTextDark = Color(0xFFB7A8E3), primaryTextLight = Color(0xFF5E4E96),
    aiTextDark = Color(0xFFB7A8E3), aiTextLight = Color(0xFF5E4E96)
)

val OceanScheme = scheme(
    name = "Ocean",
    darkPrimary = Color(0xFF1F5C73), darkSecondary = Color(0xFF2E6355), darkTertiary = Color(0xFF63539B),
    lightPrimary = Color(0xFF166B84), lightSecondary = Color(0xFF2E6355), lightTertiary = Color(0xFF5E4E96),
    darkBg = Color(0xFF0D1417), darkSurface = Color(0xFF162126), darkHigh = Color(0xFF1E2D33),
    darkText = Color(0xFFE6F0F2), darkMuted = Color(0xFF9DB4BA), darkHair = Color(0xFF2C4149),
    lightBg = Color(0xFFEFF6F7), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFDCEDEF),
    lightText = Color(0xFF14242A), lightMuted = Color(0xFF47626D), lightHair = Color(0xFFBCD3D8),
    primaryTextDark = Color(0xFF8FC7D9), primaryTextLight = Color(0xFF0F5A73),
    aiTextDark = Color(0xFFB7A8E3), aiTextLight = Color(0xFF5E4E96)
)

val ForestScheme = scheme(
    name = "Forest",
    darkPrimary = Color(0xFF48693F), darkSecondary = Color(0xFF5C6644), darkTertiary = Color(0xFF63539B),
    lightPrimary = Color(0xFF48693F), lightSecondary = Color(0xFF5F7161), lightTertiary = Color(0xFF5E4E96),
    darkBg = Color(0xFF101410), darkSurface = Color(0xFF1A211A), darkHigh = Color(0xFF232C23),
    darkText = Color(0xFFEAF0E6), darkMuted = Color(0xFFA9BBA3), darkHair = Color(0xFF33402F),
    lightBg = Color(0xFFF2F6EF), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFE2EDDC),
    lightText = Color(0xFF1C261A), lightMuted = Color(0xFF49593F), lightHair = Color(0xFFC2D2B8),
    primaryTextDark = Color(0xFFA9CFA0), primaryTextLight = Color(0xFF3E6034),
    aiTextDark = Color(0xFFB7A8E3), aiTextLight = Color(0xFF5E4E96)
)

val RoseScheme = scheme(
    name = "Rose",
    darkPrimary = Color(0xFF8C3548), darkSecondary = Color(0xFF4E6153), darkTertiary = Color(0xFF63539B),
    lightPrimary = Color(0xFF9C3A50), lightSecondary = Color(0xFF5F7161), lightTertiary = Color(0xFF5E4E96),
    darkBg = Color(0xFF171013), darkSurface = Color(0xFF241A1E), darkHigh = Color(0xFF302227),
    darkText = Color(0xFFF7ECEE), darkMuted = Color(0xFFC2A3AB), darkHair = Color(0xFF473138),
    lightBg = Color(0xFFFBF2F3), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFF5E0E3),
    lightText = Color(0xFF2B1A1E), lightMuted = Color(0xFF7D5A62), lightHair = Color(0xFFE3C4C9),
    primaryTextDark = Color(0xFFF0A8B8), primaryTextLight = Color(0xFF8C2F44),
    aiTextDark = Color(0xFFB7A8E3), aiTextLight = Color(0xFF5E4E96)
)

val MonoScheme = scheme(
    name = "Mono",
    darkPrimary = Color(0xFF4B5563), darkSecondary = Color(0xFF4E6153), darkTertiary = Color(0xFF63539B),
    lightPrimary = Color(0xFF4B5563), lightSecondary = Color(0xFF5F7161), lightTertiary = Color(0xFF5E4E96),
    darkBg = Color(0xFF111214), darkSurface = Color(0xFF1C1E20), darkHigh = Color(0xFF26282B),
    darkText = Color(0xFFF0F0F2), darkMuted = Color(0xFFA8ABB0), darkHair = Color(0xFF383B3F),
    lightBg = Color(0xFFF4F4F5), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFE7E7E9),
    lightText = Color(0xFF1E2023), lightMuted = Color(0xFF5F6368), lightHair = Color(0xFFC9CBCE),
    primaryTextDark = Color(0xFFC3C8CF), primaryTextLight = Color(0xFF3D454F),
    aiTextDark = Color(0xFFB7A8E3), aiTextLight = Color(0xFF5E4E96)
)

// ── v1.0.2 vibrant presets (R04) ─────────────────────────────────
// The v1.0.1 schemes read as "so lame" (user) — muted, grey-leaning.
// These three are the vibrant groundwork: saturated accents on deep
// tinted grounds, same contrast discipline (the gate pins them).

val NebulaVioletScheme = scheme(
    name = "Nebula violet",
    darkPrimary = Color(0xFF7C5CD6), darkSecondary = Color(0xFF39709F), darkTertiary = Color(0xFFA83FB8),
    lightPrimary = Color(0xFF6A3FD4), lightSecondary = Color(0xFF2E6DA4), lightTertiary = Color(0xFFB03FC4),
    darkBg = Color(0xFF120E1F), darkSurface = Color(0xFF1D1730), darkHigh = Color(0xFF292040),
    darkText = Color(0xFFF1ECFA), darkMuted = Color(0xFFB3A6D6), darkHair = Color(0xFF40345F),
    lightBg = Color(0xFFF4F0FD), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFE7DFF9),
    lightText = Color(0xFF1E1533), lightMuted = Color(0xFF5E517E), lightHair = Color(0xFFCDBFEA),
    primaryTextDark = Color(0xFFC4B0F5), primaryTextLight = Color(0xFF5B3FA8),
    aiTextDark = Color(0xFFE3A6EE), aiTextLight = Color(0xFF9C3FA8)
)

val EmeraldScheme = scheme(
    name = "Emerald",
    darkPrimary = Color(0xFF157A4E), darkSecondary = Color(0xFF2E7D5B), darkTertiary = Color(0xFF63539B),
    lightPrimary = Color(0xFF0E8A52), lightSecondary = Color(0xFF2E7D5B), lightTertiary = Color(0xFF5E4E96),
    darkBg = Color(0xFF0C1712), darkSurface = Color(0xFF14231C), darkHigh = Color(0xFF1D3026),
    darkText = Color(0xFFEAF7EF), darkMuted = Color(0xFFA3C4B0), darkHair = Color(0xFF2E4638),
    lightBg = Color(0xFFEFF8F2), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFDDF0E4),
    lightText = Color(0xFF12241A), lightMuted = Color(0xFF44604E), lightHair = Color(0xFFBAD6C2),
    primaryTextDark = Color(0xFF8FE3B0), primaryTextLight = Color(0xFF14663C),
    aiTextDark = Color(0xFFB7A8E3), aiTextLight = Color(0xFF5E4E96)
)

val SunsetScheme = scheme(
    name = "Sunset",
    darkPrimary = Color(0xFFB5561F), darkSecondary = Color(0xFF8C4A5E), darkTertiary = Color(0xFF63539B),
    lightPrimary = Color(0xFFC25E1E), lightSecondary = Color(0xFF8C4A5E), lightTertiary = Color(0xFF5E4E96),
    darkBg = Color(0xFF1B110B), darkSurface = Color(0xFF291A12), darkHigh = Color(0xFF37241A),
    darkText = Color(0xFFFBF0E6), darkMuted = Color(0xFFD3B096), darkHair = Color(0xFF4D3524),
    lightBg = Color(0xFFFBF3EC), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFF7E4D2),
    lightText = Color(0xFF2E1B0E), lightMuted = Color(0xFF7A5A40), lightHair = Color(0xFFE8CCAF),
    primaryTextDark = Color(0xFFFFB37A), primaryTextLight = Color(0xFFA34A12),
    aiTextDark = Color(0xFFB7A8E3), aiTextLight = Color(0xFF5E4E96)
)

// ── v1.0.4 (D6): the user's own vibrant palette as a scheme ──────
// Built on his exact trio — ElectricPurple #8A2BE2 is the primary
// in BOTH modes (white reads 5.9:1 on it, so the pure colour can
// carry the fill). Cyan and pink are too bright to carry white text
// as raw fills (1.4:1 / 3.6:1), so their scheme fills are the deep
// counterparts (#007A87 / #C21847, both ≥5:1 with white); the pure
// #00F0FF / #FF2D55 live as the text/stage tokens below and in the
// shell mesh + hero gradient, where no small text sits on them.

// ── v1.0.6.2 (F8b): the two violet-family relatives that
// complete the three-theme picker — a deep blue-violet and a
// magenta-violet, built through the same scheme() builder and
// held to the same contrast gate as every scheme above.

val DeepIndigoScheme = scheme(
    name = "Deep indigo",
    darkPrimary = Color(0xFF4338CA), darkSecondary = Color(0xFF3E6FB0), darkTertiary = Color(0xFF7C5CD6),
    lightPrimary = Color(0xFF3D34B8), lightSecondary = Color(0xFF2E5FA3), lightTertiary = Color(0xFF6A3FD4),
    darkBg = Color(0xFF0B0E1D), darkSurface = Color(0xFF151A2E), darkHigh = Color(0xFF1F2742),
    darkText = Color(0xFFECEEFB), darkMuted = Color(0xFFA6ABD0), darkHair = Color(0xFF2E3657),
    lightBg = Color(0xFFF1F2FA), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFE0E3F2),
    lightText = Color(0xFF141830), lightMuted = Color(0xFF4E5474), lightHair = Color(0xFFC3C8E2),
    primaryTextDark = Color(0xFFA5B4FC), primaryTextLight = Color(0xFF3730A3),
    aiTextDark = Color(0xFFC4B0F5), aiTextLight = Color(0xFF5B3FA8)
)

val MagentaVioletScheme = scheme(
    name = "Magenta violet",
    darkPrimary = Color(0xFFA21CAF), darkSecondary = Color(0xFF7C5CD6), darkTertiary = Color(0xFFC21847),
    lightPrimary = Color(0xFF86198F), lightSecondary = Color(0xFF6A3FD4), lightTertiary = Color(0xFFC21847),
    darkBg = Color(0xFF170A1C), darkSurface = Color(0xFF221129), darkHigh = Color(0xFF2E1738),
    darkText = Color(0xFFF9EDFA), darkMuted = Color(0xFFCBA3D4), darkHair = Color(0xFF472A52),
    lightBg = Color(0xFFFAF1FB), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFF3DFF5),
    lightText = Color(0xFF2A1030), lightMuted = Color(0xFF6E4A77), lightHair = Color(0xFFE4C6E8),
    primaryTextDark = Color(0xFFF0A6F5), primaryTextLight = Color(0xFF86198F),
    aiTextDark = Color(0xFFFFB3C8), aiTextLight = Color(0xFFB0123F)
)

val ElectricVioletScheme = scheme(
    name = "Electric violet",
    darkPrimary = ElectricPurple, darkSecondary = Color(0xFF007A87), darkTertiary = Color(0xFFC21847),
    lightPrimary = ElectricPurple, lightSecondary = Color(0xFF00707C), lightTertiary = Color(0xFFC21847),
    darkBg = DeepSpace, darkSurface = Color(0xFF1A1526), darkHigh = Color(0xFF251D38),
    darkText = Color(0xFFF2EFFF), darkMuted = Color(0xFFB9AFD6), darkHair = Color(0xFF3A3153),
    lightBg = Color(0xFFF6F2FC), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFEAE2F7),
    lightText = Color(0xFF1D1430), lightMuted = Color(0xFF5B4E77), lightHair = Color(0xFFCFC2E6),
    primaryTextDark = VibrantCyan, primaryTextLight = Color(0xFF6D28B8),
    aiTextDark = Color(0xFFFF8FAB), aiTextLight = Color(0xFFB0123F)
)

/**
 * Nocturne Violet (user's Stitch design system, 2026-10-30): the
 * prose spec over the token table — pure-black #000000 canvas,
 * tiers #0c0d12 / #161822, electric violet #8B5CF6 family, slate
 * text ladder (#f8fafc / #cbd5e1 / #94a3b8 / #64748b). Built
 * through the same [scheme] builder as every other scheme, so
 * its on* pairs are chosen by the WCAG function and pinned by
 * the same contrast gate. The dark primary fill is the deep
 * violet (white 5.9:1); the pure #8B5CF6 lives in the text
 * tokens and stage accents, where no small white text sits.
 */
val NocturneVioletScheme = scheme(
    name = "Nocturne violet",
    darkPrimary = NocturneVioletDeep, darkSecondary = Color(0xFF475569), darkTertiary = Color(0xFF7C3AED),
    lightPrimary = Color(0xFF7C3AED), lightSecondary = Color(0xFF64748B), lightTertiary = Color(0xFF8B5CF6),
    darkBg = NocturneCanvas, darkSurface = NocturneTier1, darkHigh = NocturneTier2,
    darkText = NocturneTextPrimary, darkMuted = NocturneTextMuted, darkHair = Color(0xFF26272F),
    lightBg = Color(0xFFF8FAFC), lightSurface = Color(0xFFFFFFFF), lightHigh = Color(0xFFEEF0F6),
    lightText = Color(0xFF0F172A), lightMuted = Color(0xFF5B6472), lightHair = Color(0xFFD8DBE6),
    primaryTextDark = Color(0xFFC4B5FD), primaryTextLight = Color(0xFF6D28D9),
    aiTextDark = Color(0xFFD8B4FE), aiTextLight = Color(0xFF7E22CE)
)

/** Every resolvable scheme (stored names keep working). */
val AppSchemes: List<AppScheme> = listOf(
    EmberScheme, VioletNightScheme, OceanScheme, ForestScheme, RoseScheme, MonoScheme,
    NebulaVioletScheme, EmeraldScheme, SunsetScheme, ElectricVioletScheme,
    DeepIndigoScheme, MagentaVioletScheme, NocturneVioletScheme
)

/**
 * v1.0.6.2 (F8b + the user's 1–3 options rule): the Colour
 * theme PICKER offers exactly three complete themes — Electric
 * Violet (the default) and its two violet-family relatives.
 * The other schemes above stay resolvable for installs that
 * already chose them; they are simply no longer a wall of
 * options.
 */
val AppColourThemes: List<AppScheme> = listOf(
    ElectricVioletScheme, DeepIndigoScheme, MagentaVioletScheme
)

/**
 * True black (R04): a dark scheme with every surface collapsed to
 * pure black — AMOLED-friendly. Only surface/background roles change;
 * the text roles were chosen against near-black grounds, so the
 * pairs stay valid (pinned by TrueBlackContrastTest). Variants keep
 * a whisper of separation so stacked cards still read as layers.
 */
fun ColorScheme.withTrueBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF101010),
    surfaceContainerHighest = Color(0xFF161616)
)

fun appSchemeNamed(name: String?): AppScheme =
    AppSchemes.firstOrNull { it.name == name } ?: EmberScheme
