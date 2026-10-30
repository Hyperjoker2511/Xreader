package app.xreader

import app.xreader.data.prefs.AppearanceValidation
import app.xreader.data.prefs.CustomSchemeSpec
import app.xreader.data.prefs.SchemeFlavour
import app.xreader.ui.theme.Contrast
import app.xreader.ui.theme.CustomSchemeGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.7 pins: the AnymeX-port customise round.
 *
 * - Scheme flavours ride the stored spec as a sixth field; the
 *   five-field rows written before v1.0.7 read back as TONAL
 *   (bit-identical themes), and an unknown flavour name falls
 *   back to TONAL instead of killing the row.
 * - EVERY flavour × a battery of seeds (incl. pure white, pure
 *   black, pure yellow) passes the same on-colour and fill
 *   contrast pairs the preset gate pins — flavours change the
 *   cooking, never the readability discipline.
 * - Flavours actually differ: VIBRANT is not TONAL, and MONO's
 *   primary is nearly grey.
 * - Grain strength clamps to its range.
 */
class V107CustomiseTest {

    @Test
    fun `flavour round-trips and old rows read as tonal`() {
        val specs = listOf(
            CustomSchemeSpec(
                "c1", "Vib", 0xFF7C5CD6L, null, 1, SchemeFlavour.VIBRANT
            ),
            CustomSchemeSpec(
                "c2", "Mono", 0xFF2E7CF6L, null, 1, SchemeFlavour.MONO
            )
        )
        val decoded = AppearanceValidation.decodeCustomSchemes(
            AppearanceValidation.encodeCustomSchemes(specs)
        )
        assertEquals(SchemeFlavour.VIBRANT, decoded[0].flavour)
        assertEquals(SchemeFlavour.MONO, decoded[1].flavour)

        // A pre-v1.0.7 five-field row: TONAL, everything else intact.
        val legacy = AppearanceValidation.decodeCustomSchemes(
            "c9|Old theme|123456||1"
        )
        assertEquals(1, legacy.size)
        assertEquals(SchemeFlavour.TONAL, legacy[0].flavour)
        assertEquals(123456L, legacy[0].seedArgb)

        // An unknown flavour name degrades to TONAL, not to a lost row.
        val unknown = AppearanceValidation.decodeCustomSchemes(
            "c8|Future|123456||1|NEON"
        )
        assertEquals(1, unknown.size)
        assertEquals(SchemeFlavour.TONAL, unknown[0].flavour)
    }

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
    fun `every flavour passes the contrast pairs for every seed`() {
        SchemeFlavour.entries.forEach { flavour ->
            seeds.forEach { seed ->
                val scheme = CustomSchemeGenerator.generate(
                    CustomSchemeSpec(
                        id = "t", name = "T", seedArgb = seed,
                        flavour = flavour
                    )
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
                        "onSurfaceVariant" to
                            (colors.onSurfaceVariant to colors.surfaceVariant)
                    )
                    pairs.forEach { (label, pair) ->
                        val ratio = Contrast.ratio(pair.first, pair.second)
                        assertTrue(
                            "flavour=$flavour seed=${seed.toString(16)} " +
                                "dark=$dark $label = $ratio",
                            ratio >= 4.5
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `flavours actually differ and mono is nearly grey`() {
        fun darkPrimary(flavour: SchemeFlavour) = CustomSchemeGenerator
            .generate(
                CustomSchemeSpec(
                    id = "t", name = "T", seedArgb = 0xFF7C5CD6L,
                    flavour = flavour
                )
            )
            .colors(true).primary

        val tonal = darkPrimary(SchemeFlavour.TONAL)
        val vibrant = darkPrimary(SchemeFlavour.VIBRANT)
        assertTrue("VIBRANT must not equal TONAL", tonal != vibrant)

        val mono = darkPrimary(SchemeFlavour.MONO)
        with(CustomSchemeGenerator) {
            val (_, monoSat, _) = mono.toHsl()
            val (_, tonalSat, _) = tonal.toHsl()
            assertTrue("MONO primary sat $monoSat should be < 0.2", monoSat < 0.2f)
            assertTrue("TONAL primary sat $tonalSat should be > 0.5", tonalSat > 0.5f)
        }
    }

    @Test
    fun `grain intensity clamps to its range`() {
        assertEquals(
            0.02f,
            AppearanceValidation.clampGrainIntensity(0.001f),
            0.0001f
        )
        assertEquals(
            0.20f,
            AppearanceValidation.clampGrainIntensity(0.9f),
            0.0001f
        )
        assertEquals(
            AppearanceValidation.DEFAULT_GRAIN_INTENSITY,
            AppearanceValidation.clampGrainIntensity(Float.NaN),
            0.0001f
        )
    }
}
