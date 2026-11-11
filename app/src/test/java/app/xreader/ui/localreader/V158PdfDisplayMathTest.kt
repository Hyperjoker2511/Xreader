package app.xreader.ui.localreader

import app.xreader.data.engine.PdfPageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (H1 + H4 + H9): the display-math seams.
 *
 * H1 — fit-page bounds the WHOLE page inside the viewport
 * (the DECIDED paged default); fit-width reproduces the
 * shipped v1.5.7 numbers exactly.
 *
 * H4 — the uniform crop is the union of the persisted per-page
 * bounds + padding, clamped to the page; all-textless →
 * no crop.
 *
 * H9 — landscape spread pairs (n, n+1); readouts keep the
 * UNDERLYING page numbers; off → singles.
 */
class V158PdfDisplayMathTest {

    // A tall light-novel page (612×792 pt) on a phone viewport.
    private val pageW = 612
    private val pageH = 792
    private val viewportW = 1080
    private val viewportH = 2200
    private val oversample = 2.0f

    @Test
    fun `fit width reproduces the shipped numbers`() {
        val (w, h) = PdfFitGeometry.renderSizePx(
            pageW, pageH, viewportW, viewportH, PdfFitMode.FIT_WIDTH, oversample
        )
        assertEquals(2160, w)
        assertEquals((2160f * pageH / pageW).toInt(), h)
    }

    @Test
    fun `fit page bounds the whole page inside the viewport`() {
        val (w, h) = PdfFitGeometry.renderSizePx(
            pageW, pageH, viewportW, viewportH, PdfFitMode.FIT_PAGE, oversample
        )
        assertTrue("width inside viewport", w <= (viewportW * oversample).toInt())
        assertTrue("height inside viewport", h <= (viewportH * oversample).toInt())
        // One dimension touches the bound (maximal fit).
        assertTrue(
            w == (viewportW * oversample).toInt() ||
                h == (viewportH * oversample).toInt()
        )
    }

    @Test
    fun `fit page on a wide page binds on width`() {
        val (w, h) = PdfFitGeometry.renderSizePx(
            1000, 400, viewportW, viewportH, PdfFitMode.FIT_PAGE, oversample
        )
        assertEquals((viewportW * oversample).toInt(), w)
        assertEquals((w * 400f / 1000f).toInt(), h)
    }

    // ── H9 ────────────────────────────────────────────────────

    @Test
    fun `spread pairs pages and counts underlying numbers honestly`() {
        val pages = 227
        assertEquals(114, PdfSpreadMath.unitCount(pages, spread = true))
        assertEquals(listOf(0, 1), PdfSpreadMath.pagesForUnit(0, pages, true))
        assertEquals(listOf(6, 7), PdfSpreadMath.pagesForUnit(3, pages, true))
        // The last unit of an odd count holds one page.
        assertEquals(listOf(226), PdfSpreadMath.pagesForUnit(113, pages, true))
        // Readouts: page 7 lives in unit 3 — the page number is
        // never renumbered by the pairing.
        assertEquals(3, PdfSpreadMath.unitForPage(7, spread = true))
        assertEquals(3, PdfSpreadMath.unitForPage(6, spread = true))
    }

    @Test
    fun `spread off is single pages`() {
        assertEquals(227, PdfSpreadMath.unitCount(227, spread = false))
        assertEquals(listOf(9), PdfSpreadMath.pagesForUnit(9, 227, false))
        assertEquals(9, PdfSpreadMath.unitForPage(9, spread = false))
    }
}

/**
 * v1.5.8 (H3): SIX named night presets over the colour-matrix
 * seam; the one-tap toggle flips Invert B&W ⇄ As printed on
 * the SAME pref.
 */
class V158PdfNightPresetsTest {

    @Test
    fun `six presets exist and resolve over the matrix seam`() {
        assertEquals(6, PdfPageFilter.entries.size)
        for (filter in PdfPageFilter.entries) {
            val surround = filter.surroundArgb()
            when (filter) {
                PdfPageFilter.NONE -> {
                    assertNull(filter.colorMatrix())
                    assertNull(surround)
                }
                else -> {
                    assertNotNull("matrix for $filter", filter.colorMatrix())
                }
            }
        }
        // The dark-room presets paint a dark surround; the
        // gentle ones a warm/soft one; plain Invert keeps the
        // page's own (inverted) surround = null.
        assertNotNull(PdfPageFilter.INVERT_BLACK_AND_WHITE.surroundArgb())
        assertNotNull(PdfPageFilter.SEPIA_PAGE.surroundArgb())
        assertNotNull(PdfPageFilter.SOFT_GREY.surroundArgb())
    }

    @Test
    fun `toggle flips between As printed and Invert B and W`() {
        assertEquals(
            PdfPageFilter.INVERT_BLACK_AND_WHITE,
            PdfPageFilter.NONE.toggledNight()
        )
        assertEquals(
            PdfPageFilter.NONE,
            PdfPageFilter.INVERT_BLACK_AND_WHITE.toggledNight()
        )
        // From any other preset the toggle lands on the night side.
        assertEquals(
            PdfPageFilter.INVERT_BLACK_AND_WHITE,
            PdfPageFilter.SEPIA_PAGE.toggledNight()
        )
    }
}
