package app.xreader.ui.theme

import androidx.compose.ui.graphics.Color
import app.xreader.data.prefs.CustomSchemeSpec
import kotlin.math.abs

/**
 * Make-your-own-theme generator (v1.0.2, R04 foundation): derives a
 * complete [AppScheme] from a stored [CustomSchemeSpec] — seed colour
 * (+ optional gradient end colour) — with pure HSL maths, no Android
 * APIs, so it is fully unit-testable.
 *
 * Readability is structural, not hopeful: fills go through
 * [buildScheme] (whose on* colours are chosen by the real WCAG
 * function with an ensureContrast fallback), and the accent-as-text
 * tokens are pushed through [Contrast.ensureContrast] against every
 * surface they can sit on. CustomSchemeGeneratorTest pins the same
 * pairs the contrast gate pins for presets, over a battery of seeds
 * including pure white, pure black and pure yellow.
 *
 * The full creation UI is R07's Customise work; this is the data path
 * + generator the plan's foundation phase assigns.
 */
object CustomSchemeGenerator {

    /**
     * v1.0.7 (AnymeX port): the flavour recipes — AnymeX offers
     * Material's scheme VARIANTS per seed; these are ours, as
     * scalings of the one HSL recipe. TONAL's numbers reproduce
     * the original recipe EXACTLY (scale 1, grounds 0.07/0.96,
     * no extra hue rotation, tertiary at +300°), so every scheme
     * saved before v1.0.7 renders bit-identically.
     */
    private data class FlavourProfile(
        val satScale: Float,
        val darkBgL: Float,
        val lightBgL: Float,
        val endHueExtra: Float,
        val tertiaryShift: Float
    )

    private fun profileFor(
        flavour: app.xreader.data.prefs.SchemeFlavour
    ): FlavourProfile = when (flavour) {
        app.xreader.data.prefs.SchemeFlavour.TONAL ->
            FlavourProfile(1.0f, 0.07f, 0.96f, 0f, 300f)
        app.xreader.data.prefs.SchemeFlavour.VIBRANT ->
            FlavourProfile(1.3f, 0.07f, 0.95f, 0f, 300f)
        app.xreader.data.prefs.SchemeFlavour.SOFT ->
            FlavourProfile(0.62f, 0.09f, 0.97f, 0f, 300f)
        app.xreader.data.prefs.SchemeFlavour.EXPRESSIVE ->
            FlavourProfile(1.15f, 0.07f, 0.96f, 60f, 200f)
        app.xreader.data.prefs.SchemeFlavour.MONO ->
            FlavourProfile(0.12f, 0.07f, 0.96f, 0f, 300f)
    }

    fun generate(spec: CustomSchemeSpec): AppScheme {
        val profile = profileFor(spec.flavour)
        val scale = profile.satScale
        val seed = Color(spec.seedArgb.toInt())
        val (h, s, _) = seed.toHsl()
        val endHue = (
            (spec.endArgb?.let { Color(it.toInt()).toHsl().first }
                ?: (h + 40f) % 360f) + profile.endHueExtra
            ) % 360f
        val sat = (s.coerceIn(0.25f, 0.9f) * scale).coerceIn(0.04f, 0.95f)
        val tertiaryHue = (h + profile.tertiaryShift) % 360f

        // Dark family: deep tinted grounds, vivid fills.
        val darkBg = hsl(h, sat * 0.55f, profile.darkBgL)
        val darkSurface = hsl(h, sat * 0.5f, 0.11f)
        val darkHigh = hsl(h, sat * 0.5f, 0.16f)
        val darkText = hsl(h, 0.18f * scale, 0.93f)
        // Muted sits on surfaceVariant (= darkHigh): guarantee the
        // pair by construction, not by lightness guesswork — luminous
        // hues (yellow) make tinted grounds brighter than expected.
        val darkMuted = Contrast.ensureContrast(
            hsl(h, 0.25f * scale, 0.68f), darkHigh, Color.White
        )
        val darkHair = hsl(h, sat * 0.45f, 0.26f)
        val darkPrimary = darkFill(h, (0.78f * scale).coerceAtMost(0.95f), 0.62f)
        val darkSecondary = darkFill(endHue, 0.55f * scale, 0.5f)
        val darkTertiary = darkFill(tertiaryHue, 0.6f * scale, 0.66f)

        // Light family: near-white tinted grounds, deep fills.
        val lightBg = hsl(h, sat * 0.6f, profile.lightBgL)
        val lightSurface = Color.White
        val lightHigh = hsl(h, sat * 0.55f, 0.89f)
        val lightText = hsl(h, 0.28f * scale, 0.12f)
        val lightMuted = Contrast.ensureContrast(
            hsl(h, 0.3f * scale, 0.34f), lightHigh, Color.Black
        )
        val lightHair = hsl(h, sat * 0.45f, 0.78f)
        val lightPrimary = hsl(h, 0.72f * scale, 0.4f)
        val lightSecondary = hsl(endHue, 0.5f * scale, 0.36f)
        val lightTertiary = hsl(tertiaryHue, 0.55f * scale, 0.44f)

        fun textToken(base: Color, grounds: List<Color>, toward: Color): Color =
            grounds.fold(base) { c, bg -> Contrast.ensureContrast(c, bg, toward) }

        return AppScheme(
            name = spec.name,
            dark = buildScheme(
                true, darkPrimary, darkSecondary, darkTertiary, EmberErrorLight,
                darkBg, darkSurface, darkHigh, darkText, darkMuted, darkHair
            ),
            light = buildScheme(
                false, lightPrimary, lightSecondary, lightTertiary, EmberErrorLight,
                lightBg, lightSurface, lightHigh, lightText, lightMuted, lightHair
            ),
            primaryTextDark = textToken(
                hsl(h, 0.75f * scale, 0.78f), listOf(darkSurface, darkBg, darkHigh),
                toward = Color.White
            ),
            primaryTextLight = textToken(
                hsl(h, 0.7f * scale, 0.32f), listOf(lightSurface, lightBg, lightHigh),
                toward = Color.Black
            ),
            aiTextDark = textToken(
                hsl(tertiaryHue, 0.65f * scale, 0.8f),
                listOf(darkSurface, darkBg, darkHigh),
                toward = Color.White
            ),
            aiTextLight = textToken(
                hsl(tertiaryHue, 0.6f * scale, 0.36f),
                listOf(lightSurface, lightBg, lightHigh),
                toward = Color.Black
            ),
            errorTextDark = EmberErrorTextDark,
            errorTextLight = EmberErrorLight,
            sageTextDark = EmberSageTextDark,
            sageTextLight = Color(0xFF44543F)
        )
    }

    /**
     * The plain-words note the editor shows before saving (R04): if
     * the generator had to deepen the seed to keep text readable,
     * say so honestly rather than silently shipping a different
     * colour than the user picked. Null = the seed survived as-is.
     */
    fun adjustmentNote(spec: CustomSchemeSpec): String? {
        val seed = Color(spec.seedArgb.toInt())
        val (h, _, l) = seed.toHsl()
        // Dark primary starts at hsl(h, .78, .62); luminous seeds
        // (yellow/lime) fail white-text there and get deepened by
        // darkFill. Light primary is fixed deep (0.40), so a light,
        // bright seed is deepened in light mode too.
        val naiveDark = hsl(h, 0.78f, 0.62f)
        val darkAdjusted = Contrast.ratio(Color.White, naiveDark) < 4.5
        val lightAdjusted = l > 0.5f
        return if (darkAdjusted || lightAdjusted) {
            "We deepened your colour a little so words on it stay easy to read."
        } else null
    }

    /**
     * A dark-mode fill colour: starts vivid, then darkens in small
     * steps until WHITE text on it passes 4.5:1. Luminous hues
     * (yellow, lime) are intrinsically bright — without this cap
     * their on* colour could not pass in dark mode, where the only
     * candidate text colours are light. Hue and saturation (the
     * colour's identity) are untouched; a yellow seed becomes a deep
     * gold rather than a pale lemon.
     */
    private fun darkFill(h: Float, s: Float, l: Float): Color {
        var lightness = l
        var color = hsl(h, s, lightness)
        while (Contrast.ratio(Color.White, color) < 4.5 && lightness > 0.16f) {
            lightness -= 0.04f
            color = hsl(h, s, lightness)
        }
        return color
    }

    /** RGB → HSL (h in degrees 0–360, s/l in 0–1). Pure maths. */
    internal fun Color.toHsl(): Triple<Float, Float, Float> {
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val l = (max + min) / 2f
        if (abs(max - min) < 0.0001f) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            red -> 60f * (((green - blue) / d) % 6f)
            green -> 60f * (((blue - red) / d) + 2f)
            else -> 60f * (((red - green) / d) + 4f)
        }
        return Triple((h + 360f) % 360f, s, l)
    }

    /** HSL → opaque colour. Pure maths. */
    internal fun hsl(h: Float, s: Float, l: Float): Color {
        val hue = ((h % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s.coerceIn(0f, 1f)
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(
            red = (r + m).coerceIn(0f, 1f),
            green = (g + m).coerceIn(0f, 1f),
            blue = (b + m).coerceIn(0f, 1f)
        )
    }
}
