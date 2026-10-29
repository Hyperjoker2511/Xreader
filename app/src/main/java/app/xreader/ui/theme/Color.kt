package app.xreader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * "Ember Ink" palette — Xreader's own Stitch design system
 * (`~/workspace/xreader/design/DESIGN.md`, asset "Xreader — Ember Ink").
 * A candlelit midnight library: ember amber on warm near-black, deep sage
 * for success/downloaded states, muted violet for every Gemini AI accent.
 * Deliberately NOT ReTranslate's azure/mint Fluid Glass.
 *
 * The named tokens below are the exact values from the design HTML
 * (frequency-verified across all 12 generated screens). Material colour
 * roles are mapped from them; component code that reproduces a specific
 * design element uses the named Ember tokens directly.
 */

// ---- Named design tokens (exact, from design/*.html) --------------------

val EmberAmber = Color(0xFFC77D3A)
val EmberSage = Color(0xFF5F7161)
val EmberViolet = Color(0xFF7C6AAE)

val EmberBackgroundDark = Color(0xFF12100E)
val EmberSurfaceDark = Color(0xFF1E1A16)
val EmberSurfaceHighDark = Color(0xFF262019)
val EmberTextDark = Color(0xFFF2EAD9)
val EmberTextMutedDark = Color(0xFFA79B85)

val EmberBackgroundLight = Color(0xFFFAF6EF)
val EmberSurfaceLight = Color(0xFFFFFFFF)
val EmberSurfaceHighLight = Color(0xFFF3ECDF)
val EmberTextLight = Color(0xFF241C12)

/** Hairline for glass borders (derived: one step above the dark surface
 * ladder / one step below light paper — the DESIGN.md glass recipe is
 * "layered translucency + hairline border + soft shadow, no blur"). */
val EmberHairlineDark = Color(0xFF3A332A)
val EmberHairlineLight = Color(0xFFD9CFBC)
val EmberTextMutedLight = Color(0xFF6E6151)

val EmberError = Color(0xFFCF5B52)
val EmberErrorLight = Color(0xFFB3382E)

// ---- v1.0.1 contrast-fix tokens -----------------------------------------
// The v1.0.0 scheme used ONE token per accent for both fills (with an
// on* colour on top) and text (the accent itself on a surface). On the
// dark scheme no single violet/sage/red can satisfy both directions
// at 4.5:1, so fills got darker variants in Theme.kt and TEXT usages
// get these purpose-built tokens via the helpers in Theme.kt
// (aiAccentColor / errorTextColor / sageTextColor / primaryTextColor).

/** Violet as TEXT on dark surfaces (≥7.4:1 on every dark surface). */
val EmberVioletTextDark = Color(0xFFB7A8E3)

/** Violet as TEXT on light surfaces (≥5.9:1). Also the light scheme's
 * darker amber-text companion below plays the same role for primary. */
val EmberVioletTextLight = Color(0xFF5E4E96)

/** Red as TEXT on dark surfaces (≥5.1:1). */
val EmberErrorTextDark = Color(0xFFD96A62)

/** Sage as TEXT on dark surfaces (≥8.8:1). */
val EmberSageTextDark = Color(0xFFA9BFA9)

/** Amber as TEXT on light surfaces (≥5.7:1). The fill amber
 * ([EmberAmber], 3.0:1 on paper) must never carry text itself. */
val EmberAmberTextLight = Color(0xFF8A5608)

// ---- v1.0.4 "Liquid" tokens (user-supplied palette, D6/D7/D9) -----------
// The user's own vibrant trio + the Library mock's mesh pair, kept as
// named tokens so the scheme, the shell mesh backdrop and the hero
// stage all draw from ONE set of hexes. ElectricPurple is also the
// "Electric violet" scheme's primary in both modes (AppSchemes.kt);
// the deep fill variants a scheme needs for 4.5:1 on* pairs live
// there, not here — these are the pure accent/stage colours.

/** The user's ElectricPurple (#8A2BE2) — scheme primary + mesh family. */
val ElectricPurple = Color(0xFF8A2BE2)

/** The user's VibrantCyan (#00F0FF) — accent/stage colour, never a fill. */
val VibrantCyan = Color(0xFF00F0FF)

/** The user's ApplePink (#FF2D55) — accent/stage colour, never a fill. */
val ApplePink = Color(0xFFFF2D55)

/** D9 mesh: the glow colour over near-black in the dark shell backdrop. */
val NeonPurple = Color(0xFF6A0DAD)

/** D9 mesh: the near-black base of the dark shell backdrop. */
val DeepSpace = Color(0xFF0D0B14)

/** D6 glass values: the FROSTED card fill (white at 10%) and the
 * specular border's bright end (white at 30%) in dark mode. */
val LiquidGlassFillDark = Color(0x1AFFFFFF)
val LiquidGlassBorderBright = Color(0x4DFFFFFF)

// ---- Reader palettes (PLAN §2.3 — their own token family) ---------------

/** A reading surface: page, ink, muted ink, accent. */
data class ReaderPalette(
    val name: String,
    val background: Color,
    val text: Color,
    val muted: Color,
    val accent: Color
)

/** True AMOLED: the page is #000000 exactly (DESIGN.md, reader-dark). */
val ReaderAmoled = ReaderPalette(
    name = "AMOLED",
    background = Color(0xFF000000),
    text = Color(0xFFE8DFD0),
    muted = Color(0xFF8A7F6D),
    accent = EmberAmber
)

val ReaderEmberDark = ReaderPalette(
    name = "Ember dark",
    background = EmberBackgroundDark,
    text = EmberTextDark,
    muted = EmberTextMutedDark,
    accent = EmberAmber
)

val ReaderSepia = ReaderPalette(
    name = "Sepia",
    background = Color(0xFFF4ECD8),
    text = Color(0xFF433422),
    muted = Color(0xFF8A7658),
    accent = EmberAmber
)

val ReaderPaper = ReaderPalette(
    name = "Paper",
    background = EmberBackgroundLight,
    text = EmberTextLight,
    muted = EmberTextMutedLight,
    accent = EmberAmber
)

val ReaderSlate = ReaderPalette(
    name = "Slate",
    background = Color(0xFF2B3038),
    text = Color(0xFFD8DCE2),
    muted = Color(0xFF98A0AA),
    accent = EmberAmber
)

/** v1.0.1: pure black / pure white — the strongest pair there is. */
val ReaderMaxContrast = ReaderPalette(
    name = "Max contrast",
    background = Color(0xFF000000),
    text = Color(0xFFFFFFFF),
    muted = Color(0xFFC9C9C9),
    accent = EmberAmber
)

/** v1.0.1: deep navy page, soft blue-grey ink. */
val ReaderNightBlue = ReaderPalette(
    name = "Night blue",
    background = Color(0xFF0E1524),
    text = Color(0xFFD7E3F4),
    muted = Color(0xFF93A9C9),
    accent = Color(0xFF8FB4EC)
)

/** v1.0.1: deep green page, pale leaf ink. */
val ReaderForest = ReaderPalette(
    name = "Forest",
    background = Color(0xFF14201A),
    text = Color(0xFFDCE8D8),
    muted = Color(0xFFA3BFA5),
    accent = Color(0xFF8FC49A)
)

/** v1.0.1: warm brown page, cream ink — the candlelit end of the set. */
val ReaderCafe = ReaderPalette(
    name = "Café",
    background = Color(0xFF211712),
    text = Color(0xFFEFDFC8),
    muted = Color(0xFFC9A97E),
    accent = Color(0xFFD9A05B)
)

val ReaderPalettes = listOf(
    ReaderAmoled, ReaderEmberDark, ReaderSepia, ReaderPaper, ReaderSlate,
    ReaderMaxContrast, ReaderNightBlue, ReaderForest, ReaderCafe
)

/**
 * The "High contrast text" substitution (v1.0.1): keep the palette's
 * page and accent, but replace its ink with the maximum-contrast pair
 * for that page — pure white ink on dark pages, near-black ink on
 * light ones — regardless of the palette's tinted defaults. Pure, so
 * the substitution itself is unit-tested.
 */
fun ReaderPalette.withHighContrastText(): ReaderPalette {
    val darkPage = background.luminance() < 0.18f
    return if (darkPage) {
        copy(text = Color.White, muted = Color(0xFFDADADA))
    } else {
        copy(text = Color.Black, muted = Color(0xFF2A2A2A))
    }
}

/** Applies [withHighContrastText] when the reader setting is on. */
fun ReaderPalette.maxContrast(enabled: Boolean): ReaderPalette =
    if (enabled) withHighContrastText() else this

/**
 * WCAG contrast arithmetic (v1.0.1 regression armour): the unit tests
 * pin every palette and scheme pair with these exact functions, so a
 * future token edit that breaks readability fails the gate instead of
 * reaching a user's phone.
 */
object Contrast {
    /** WCAG 2.x contrast ratio of [foreground] on [background]
     * (both treated as opaque), 1.0–21.0. */
    fun ratio(foreground: Color, background: Color): Double {
        val lf = foreground.luminance().toDouble()
        val lb = background.luminance().toDouble()
        val hi = maxOf(lf, lb)
        val lo = minOf(lf, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** [foreground] at [alpha] composited over opaque [background]
     * (sRGB-space blending, as the compositor does it). */
    fun blendOver(foreground: Color, background: Color, alpha: Float): Color {
        val a = alpha.coerceIn(0f, 1f)
        return Color(
            red = foreground.red * a + background.red * (1f - a),
            green = foreground.green * a + background.green * (1f - a),
            blue = foreground.blue * a + background.blue * (1f - a),
            alpha = 1f
        )
    }

    /**
     * Nudge [foreground] toward [toward] (white or black) in small
     * steps until it reaches [target] against [background] — the
     * StatusPill fix: a coloured label on its own tint can always be
     * made readable without changing the pill's identity colour.
     */
    fun ensureContrast(
        foreground: Color,
        background: Color,
        toward: Color,
        target: Double = 4.5
    ): Color {
        if (ratio(foreground, background) >= target) return foreground
        var step = 0.05f
        while (step <= 1.0f) {
            val candidate = blendOver(toward, foreground, step)
            if (ratio(candidate, background) >= target) return candidate
            step += 0.05f
        }
        return toward
    }
}
