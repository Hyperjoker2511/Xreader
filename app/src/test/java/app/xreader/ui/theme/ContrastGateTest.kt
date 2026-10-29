package app.xreader.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1.0.1 contrast gate armour: every pair a reader's eye meets
 * is pinned here. Body text ≥ 4.5:1, muted ≥ 3:1 — in every reader
 * palette, every app colour scheme, both modes.
 */
class ContrastGateTest {

    @Test
    fun `every reader palette body text passes 4_5`() {
        ReaderPalettes.forEach { palette ->
            val ratio = Contrast.ratio(palette.text, palette.background)
            assertTrue("${palette.name} text $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun `every reader palette muted passes 3`() {
        ReaderPalettes.forEach { palette ->
            val ratio = Contrast.ratio(palette.muted, palette.background)
            assertTrue("${palette.name} muted $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun `high contrast substitution passes on every palette`() {
        ReaderPalettes.forEach { palette ->
            val hc = palette.withHighContrastText()
            assertTrue(
                "${palette.name} hc text ${Contrast.ratio(hc.text, hc.background)}",
                Contrast.ratio(hc.text, hc.background) >= 4.5
            )
            assertTrue(
                "${palette.name} hc muted ${Contrast.ratio(hc.muted, hc.background)}",
                Contrast.ratio(hc.muted, hc.background) >= 3.0
            )
        }
    }

    @Test
    fun `the four new palettes exist and pass`() {
        val names = ReaderPalettes.map { it.name }
        listOf("Max contrast", "Night blue", "Forest", "Café").forEach {
            assertTrue("missing $it", it in names)
        }
        val max = ReaderPalettes.first { it.name == "Max contrast" }
        assertTrue(Contrast.ratio(max.text, max.background) >= 20.0)
    }

    @Test
    fun `app schemes on-star pairs pass in both modes`() {
        AppSchemes.forEach { scheme ->
            listOf(true, false).forEach { dark ->
                val colors = scheme.colors(dark)
                val mode = if (dark) "dark" else "light"
                val pairs = listOf(
                    "onPrimary" to (colors.onPrimary to colors.primary),
                    "onSecondary" to (colors.onSecondary to colors.secondary),
                    "onTertiary" to (colors.onTertiary to colors.tertiary),
                    "onError" to (colors.onError to colors.error),
                    "onBackground" to (colors.onBackground to colors.background),
                    "onSurface" to (colors.onSurface to colors.surface),
                    "onSurfaceVariant" to (colors.onSurfaceVariant to colors.surfaceVariant)
                )
                pairs.forEach { (label, pair) ->
                    val ratio = Contrast.ratio(pair.first, pair.second)
                    assertTrue("${scheme.name} $mode $label = $ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test
    fun `accent text tokens pass on their scheme surfaces`() {
        AppSchemes.forEach { scheme ->
            assertTrue(
                "${scheme.name} primaryTextDark",
                Contrast.ratio(scheme.primaryTextDark, scheme.dark.surface) >= 4.5
            )
            assertTrue(
                "${scheme.name} primaryTextLight",
                Contrast.ratio(scheme.primaryTextLight, scheme.light.surface) >= 4.5
            )
            assertTrue(
                "${scheme.name} aiTextDark",
                Contrast.ratio(scheme.aiTextDark, scheme.dark.surface) >= 4.5
            )
            assertTrue(
                "${scheme.name} aiTextLight",
                Contrast.ratio(scheme.aiTextLight, scheme.light.surface) >= 4.5
            )
            assertTrue(
                "${scheme.name} errorTextDark",
                Contrast.ratio(scheme.errorTextDark, scheme.dark.surface) >= 4.5
            )
            assertTrue(
                "${scheme.name} sageTextDark",
                Contrast.ratio(scheme.sageTextDark, scheme.dark.surface) >= 4.5
            )
        }
    }

    @Test
    fun `status pill labels pass after ensureContrast`() {
        val accents = listOf(EmberAmber, EmberSage, EmberViolet, EmberError)
        AppSchemes.forEach { scheme ->
            listOf(scheme.dark, scheme.light).forEach { colors ->
                accents.forEach { accent ->
                    val pillBg = Contrast.blendOver(accent, colors.surface, 0.16f)
                    val lum = 0.2126f * colors.surface.red +
                        0.7152f * colors.surface.green +
                        0.0722f * colors.surface.blue
                    val toward = if (lum < 0.4f) Color.White else Color.Black
                    val text = Contrast.ensureContrast(accent, pillBg, toward)
                    assertTrue(
                        "${scheme.name} pill ${Contrast.ratio(text, pillBg)}",
                        Contrast.ratio(text, pillBg) >= 4.5
                    )
                }
            }
        }
    }

    @Test
    fun `custom pair warning logic - close pairs fail the gate ratio`() {
        assertTrue(Contrast.ratio(Color(0xFF777777), Color(0xFF888888)) < 4.5)
        assertTrue(Contrast.ratio(Color.White, Color.Black) >= 4.5)
    }
}
