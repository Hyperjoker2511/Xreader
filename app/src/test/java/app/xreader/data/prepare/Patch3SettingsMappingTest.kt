package app.xreader.data.prepare

import app.xreader.ui.localreader.EpubBookSettings
import app.xreader.ui.localreader.EpubThemePreset
import app.xreader.ui.localreader.applyPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.7 Patch 3 (Item 6): the Item 4g settings mapping, case
 * by case — presets to core palette names, the AMOLED
 * default clearing instead of pinning, free-swatch clearing,
 * the ×16 font-size inverse, and the untouched-book gate.
 */
class Patch3SettingsMappingTest {

    private fun mapped(settings: EpubBookSettings, stored: Boolean = true) =
        EpubSettingsMapping.map(settings, stored)

    @Test
    fun paperSepiaNightMapToCorePalettes() {
        assertEquals(
            "Paper",
            mapped(EpubBookSettings().applyPreset(EpubThemePreset.PAPER))
                ?.paletteName
        )
        assertEquals(
            "Sepia",
            mapped(EpubBookSettings().applyPreset(EpubThemePreset.SEPIA))
                ?.paletteName
        )
        assertEquals(
            "AMOLED",
            mapped(EpubBookSettings().applyPreset(EpubThemePreset.NIGHT))
                ?.paletteName
        )
    }

    @Test
    fun amoledClearsTheOverride() {
        // AMOLED IS the core default: clearing keeps the book
        // on the global default rather than pinning it.
        assertNull(
            mapped(EpubBookSettings().applyPreset(EpubThemePreset.AMOLED))
                ?.paletteName
        )
    }

    @Test
    fun freeSwatchClearsTheOverride() {
        val free = EpubBookSettings(
            textColorArgb = 0xFF112233.toInt(),
            backgroundColorArgb = 0xFF445566.toInt()
        )
        assertNull(mapped(free)?.paletteName)
    }

    @Test
    fun fontSizeInvertsTheLocalSeed() {
        val sized = EpubBookSettings().applyPreset(EpubThemePreset.SEPIA)
            .copy(fontSize = 1.25)
        assertEquals(20f, mapped(sized)?.fontSizeSp)
    }

    @Test
    fun defaultFontSizeClears() {
        val plain = EpubBookSettings().applyPreset(EpubThemePreset.SEPIA)
        assertNull(mapped(plain)?.fontSizeSp)
    }

    @Test
    fun untouchedBookMapsNothing() {
        assertNull(mapped(EpubBookSettings(), stored = false))
        assertNull(
            mapped(
                EpubBookSettings().applyPreset(EpubThemePreset.SEPIA),
                stored = false
            )
        )
    }
}
