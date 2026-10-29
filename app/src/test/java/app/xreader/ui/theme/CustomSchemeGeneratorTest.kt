package app.xreader.ui.theme

import androidx.compose.ui.graphics.Color
import app.xreader.data.prefs.CustomSchemeSpec
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R04 foundation: the make-your-own generator must satisfy the same
 * contrast discipline as the preset gate — for ANY seed, including
 * the pathological ones (pure white, pure black, pure yellow).
 */
class CustomSchemeGeneratorTest {

    private val seeds = listOf(
        0xFF7C5CD6L, // violet
        0xFFFFFFFFL, // pure white
        0xFF000000L, // pure black
        0xFFFFFF00L, // pure yellow
        0xFFFF0000L, // pure red
        0xFF00FF00L, // pure green
        0xFF808080L, // mid grey
        0xFF123456L, // dark navy
        0xFFF5DEB3L  // pale wheat
    )

    @Test
    fun `generated schemes pass the on-star pairs in both modes`() {
        seeds.forEach { seed ->
            val scheme = CustomSchemeGenerator.generate(
                CustomSchemeSpec(id = "t", name = "T", seedArgb = seed)
            )
            listOf(true, false).forEach { dark ->
                val colors = scheme.colors(dark)
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
                    assertTrue(
                        "seed=${seed.toString(16)} dark=$dark $label = $ratio",
                        ratio >= 4.5
                    )
                }
            }
        }
    }

    @Test
    fun `generated accent text tokens pass on surfaces`() {
        seeds.forEach { seed ->
            val scheme = CustomSchemeGenerator.generate(
                CustomSchemeSpec(id = "t", name = "T", seedArgb = seed)
            )
            assertTrue(
                "seed=${seed.toString(16)} primaryTextDark",
                Contrast.ratio(scheme.primaryTextDark, scheme.dark.surface) >= 4.5
            )
            assertTrue(
                "seed=${seed.toString(16)} primaryTextLight",
                Contrast.ratio(scheme.primaryTextLight, scheme.light.surface) >= 4.5
            )
            assertTrue(
                "seed=${seed.toString(16)} aiTextDark",
                Contrast.ratio(scheme.aiTextDark, scheme.dark.surface) >= 4.5
            )
            assertTrue(
                "seed=${seed.toString(16)} aiTextLight",
                Contrast.ratio(scheme.aiTextLight, scheme.light.surface) >= 4.5
            )
        }
    }

    @Test
    fun `true black variants of every preset keep their pairs`() {
        AppSchemes.forEach { scheme ->
            val black = scheme.dark.withTrueBlack()
            assertTrue(black.background == Color.Black && black.surface == Color.Black)
            val pairs = listOf(
                "onBackground" to (black.onBackground to black.background),
                "onSurface" to (black.onSurface to black.surface),
                "onSurfaceVariant" to (black.onSurfaceVariant to black.surfaceVariant)
            )
            pairs.forEach { (label, pair) ->
                val ratio = Contrast.ratio(pair.first, pair.second)
                assertTrue("${scheme.name} true-black $label = $ratio", ratio >= 4.5)
            }
        }
    }
}
