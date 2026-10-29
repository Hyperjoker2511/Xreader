package app.xreader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R05 cover palette extraction/cache + R04 Wallpaper sanitizer. */
class CoverPaletteTest {

    private fun px(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `a saturated violet cover yields a vibrant palette`() {
        // Mostly deep violet with a band of pale paper.
        val pixels = IntArray(10_000) { i ->
            if (i % 5 == 0) px(240, 235, 220) else px(110, 60, 200)
        }
        val palette = CoverPaletteExtractor.extract(pixels)
        assertNotNull(palette.dominant)
        assertNotNull(palette.vibrant)
        // Vibrant is the violet, not the paper border.
        val v = palette.vibrant!!
        assertTrue(v.blue > v.red)
    }

    @Test
    fun `empty and transparent pixels give the empty palette`() {
        assertEquals(CoverPalette.EMPTY, CoverPaletteExtractor.extract(IntArray(0)))
        assertEquals(
            CoverPalette.EMPTY,
            CoverPaletteExtractor.extract(IntArray(100) { 0x00112233 })
        )
    }

    @Test
    fun `placeholder palette is deterministic per title`() {
        val a = CoverPaletteExtractor.placeholderPalette("Return of the Sword God")
        val b = CoverPaletteExtractor.placeholderPalette("Return of the Sword God")
        val c = CoverPaletteExtractor.placeholderPalette("Another Book Entirely")
        assertEquals(a, b)
        assertTrue(a != c)
        assertNotNull(a.ambient)
    }

    @Test
    fun `cache evicts least recently used beyond capacity`() {
        val cache = CoverPaletteCache(2)
        cache.put("a", CoverPalette.EMPTY)
        cache.put("b", CoverPalette.EMPTY)
        cache.get("a") // touch a; b is now eldest
        cache.put("c", CoverPalette.EMPTY)
        assertEquals(2, cache.size())
        assertNotNull(cache.get("a"))
        assertEquals(null, cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `dynamic scheme sanitizer repairs unreadable pairs and keeps fills`() {
        val bad = darkColorScheme(
            primary = Color(0xFF888888),
            onPrimary = Color(0xFF999999), // ~1.2:1 — unreadable
            background = Color(0xFF101010),
            onBackground = Color(0xFF181818) // unreadable on background
        )
        val fixed = DynamicSchemeSanitizer.sanitize(bad)
        assertEquals(bad.primary, fixed.primary) // fill untouched
        assertTrue(Contrast.ratio(fixed.onPrimary, fixed.primary) >= 4.5)
        assertTrue(Contrast.ratio(fixed.onBackground, fixed.background) >= 4.5)
    }

    @Test
    fun `wallpaper app scheme text tokens pass on their grounds`() {
        val dark = darkColorScheme()
        val light = androidx.compose.material3.lightColorScheme()
        val scheme = DynamicSchemeSanitizer.appScheme(dark, light)
        for ((token, grounds) in listOf(
            scheme.primaryTextDark to listOf(scheme.dark.background, scheme.dark.surface),
            scheme.primaryTextLight to listOf(scheme.light.background, scheme.light.surface)
        )) {
            for (ground in grounds) {
                assertTrue(Contrast.ratio(token, ground) >= 4.5)
            }
        }
        assertEquals("Wallpaper", scheme.name)
    }
}
