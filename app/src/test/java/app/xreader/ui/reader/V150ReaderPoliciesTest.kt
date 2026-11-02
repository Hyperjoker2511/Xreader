package app.xreader.ui.reader

import app.xreader.data.prefs.BookReaderOverride
import app.xreader.domain.reader.ImageReaderMode
import app.xreader.domain.reader.MangaOrientation
import app.xreader.domain.reader.MangaTapLayout
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.TapInvert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 (Part C): the Komikku tap-zone geometry, per-series
 * resolution, and the border-trim scan — the pure halves of
 * the reader transplant, pinned without a device.
 */
class V150ReaderPoliciesTest {

    private fun action(
        layout: MangaTapLayout,
        x: Float,
        y: Float,
        mode: ImageReaderMode = ImageReaderMode.PAGED,
        invert: TapInvert = TapInvert.NONE,
        smaller: Boolean = false,
        rtl: Boolean = false
    ): MangaTapAction = MangaTapZones.action(
        layout, invert, smaller, mode, x, y, rtl
    )

    @Test
    fun `right and left zones split the screen in thirds`() {
        val layout = MangaTapLayout.RIGHT_AND_LEFT
        assertEquals(MangaTapAction.LEFT, action(layout, 0.1f, 0.5f))
        assertEquals(MangaTapAction.MENU, action(layout, 0.5f, 0.5f))
        assertEquals(MangaTapAction.RIGHT, action(layout, 0.9f, 0.5f))
    }

    @Test
    fun `a tap outside every region opens the menu`() {
        // Kindlish's regions start a third down; the band
        // above them is menu territory.
        assertEquals(
            MangaTapAction.MENU,
            action(MangaTapLayout.KINDLISH, 0.5f, 0.1f)
        )
        assertEquals(
            MangaTapAction.MENU,
            action(MangaTapLayout.EDGE, 0.5f, 0.5f)
        )
    }

    @Test
    fun `L shape turns on the left column and the bottom band`() {
        val layout = MangaTapLayout.L_SHAPE
        assertEquals(MangaTapAction.PREV, action(layout, 0.1f, 0.5f))
        assertEquals(MangaTapAction.PREV, action(layout, 0.5f, 0.1f))
        assertEquals(MangaTapAction.NEXT, action(layout, 0.9f, 0.5f))
        assertEquals(MangaTapAction.NEXT, action(layout, 0.5f, 0.9f))
        assertEquals(MangaTapAction.MENU, action(layout, 0.5f, 0.5f))
    }

    @Test
    fun `kindlish turns forward on the big lower right`() {
        val layout = MangaTapLayout.KINDLISH
        assertEquals(MangaTapAction.NEXT, action(layout, 0.8f, 0.8f))
        assertEquals(MangaTapAction.PREV, action(layout, 0.1f, 0.8f))
        assertEquals(MangaTapAction.MENU, action(layout, 0.8f, 0.2f))
    }

    @Test
    fun `edge zones live on the top corners`() {
        val layout = MangaTapLayout.EDGE
        assertEquals(MangaTapAction.NEXT, action(layout, 0.05f, 0.2f))
        assertEquals(MangaTapAction.NEXT, action(layout, 0.95f, 0.2f))
        assertEquals(MangaTapAction.PREV, action(layout, 0.5f, 0.9f))
        assertEquals(MangaTapAction.MENU, action(layout, 0.5f, 0.5f))
    }

    @Test
    fun `disabled zones always open the menu`() {
        assertEquals(
            MangaTapAction.MENU,
            action(MangaTapLayout.DISABLED, 0.05f, 0.95f)
        )
    }

    @Test
    fun `default layout resolves per mode family`() {
        // Paged defaults to right-and-left; webtoon to L.
        assertEquals(
            MangaTapAction.RIGHT,
            action(MangaTapLayout.DEFAULT, 0.9f, 0.5f, ImageReaderMode.PAGED)
        )
        assertEquals(
            MangaTapAction.NEXT,
            action(MangaTapLayout.DEFAULT, 0.9f, 0.5f, ImageReaderMode.WEBTOON)
        )
    }

    @Test
    fun `horizontal inversion mirrors the tap point`() {
        assertEquals(
            MangaTapAction.RIGHT,
            action(
                MangaTapLayout.RIGHT_AND_LEFT, 0.1f, 0.5f,
                invert = TapInvert.HORIZONTAL
            )
        )
    }

    @Test
    fun `rtl swaps the screen-direction actions`() {
        assertEquals(
            MangaTapAction.RIGHT,
            action(MangaTapLayout.RIGHT_AND_LEFT, 0.1f, 0.5f, rtl = true)
        )
        assertEquals(
            MangaTapAction.LEFT,
            action(MangaTapLayout.RIGHT_AND_LEFT, 0.9f, 0.5f, rtl = true)
        )
    }

    @Test
    fun `smaller zones shrink the turn areas to quarters`() {
        val layout = MangaTapLayout.RIGHT_AND_LEFT
        assertEquals(
            MangaTapAction.MENU,
            action(layout, 0.3f, 0.5f, smaller = true)
        )
        assertEquals(
            MangaTapAction.LEFT,
            action(layout, 0.2f, 0.5f, smaller = true)
        )
    }

    // ── MangaSettings.resolve ──────────────────────────────

    @Test
    fun `series overrides win over the global defaults`() {
        val global = ReaderConfig(
            mangaTapLayout = MangaTapLayout.EDGE,
            mangaOrientation = MangaOrientation.FREE,
            cropBordersPaged = false
        )
        val override = BookReaderOverride(
            mangaTapLayout = MangaTapLayout.KINDLISH,
            mangaOrientation = MangaOrientation.LANDSCAPE,
            mangaCropBorders = true
        )
        val resolved = MangaSettings.resolve(
            global, override, ImageReaderMode.PAGED
        )
        assertEquals(MangaTapLayout.KINDLISH, resolved.tapLayout)
        assertEquals(MangaOrientation.LANDSCAPE, resolved.orientation)
        assertTrue(resolved.cropBorders)
    }

    @Test
    fun `crop resolves per mode family without an override`() {
        val global = ReaderConfig(
            cropBordersPaged = true,
            cropBordersWebtoon = false
        )
        val paged = MangaSettings.resolve(
            global, BookReaderOverride(), ImageReaderMode.PAGED
        )
        val webtoon = MangaSettings.resolve(
            global, BookReaderOverride(), ImageReaderMode.WEBTOON
        )
        assertTrue(paged.cropBorders)
        assertFalse(webtoon.cropBorders)
    }

    // ── BorderTrim ─────────────────────────────────────────

    @Test
    fun `a uniform border trims on all four sides`() {
        // 200x200 page: 20px white border, dark content inside.
        val white = 0xFFFFFFFF.toInt()
        val dark = 0xFF202020.toInt()
        val insets = BorderTrim.scan(200, 200) { x, y ->
            if (x < 20 || x >= 180 || y < 20 || y >= 180) white else dark
        }
        assertEquals(20, insets.left)
        assertEquals(20, insets.top)
        assertEquals(20, insets.right)
        assertEquals(20, insets.bottom)
    }

    @Test
    fun `a borderless page trims nothing`() {
        // Content running right up to every edge: a
        // checkerboard of two shades far apart (a uniform
        // page is indistinguishable from an all-border page
        // — that's the capped case below, not this one).
        val dark = 0xFF202020.toInt()
        val light = 0xFFD8D8D8.toInt()
        val insets = BorderTrim.scan(200, 200) { x, y ->
            if ((x / 16 + y / 16) % 2 == 0) dark else light
        }
        assertTrue(insets.isEmpty)
    }

    @Test
    fun `an all-border page trims only to the safety cap`() {
        // The cap (45% per side) is what keeps a blank page
        // from collapsing to a zero-size bitmap.
        val white = 0xFFFFFFFF.toInt()
        val insets = BorderTrim.scan(200, 200) { _, _ -> white }
        assertEquals(90, insets.left)
        assertEquals(90, insets.right)
        assertEquals(90, insets.top)
        assertEquals(90, insets.bottom)
    }

    @Test
    fun `grayscale and invert matrices compose as documented`() {
        assertEquals(null, mangaColorFilter(grayscale = false, invert = false))
        assertTrue(mangaColorFilter(grayscale = true, invert = false) != null)
        assertTrue(mangaColorFilter(grayscale = false, invert = true) != null)
        assertTrue(mangaColorFilter(grayscale = true, invert = true) != null)
    }
}
