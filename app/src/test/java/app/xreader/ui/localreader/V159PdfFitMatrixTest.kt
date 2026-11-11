package app.xreader.ui.localreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.9 (sift B-3, F-5 test rider): the fit matrix — for
 * [portrait page, landscape page] × [portrait viewport,
 * landscape viewport] the mapped page rect is centred and
 * contained in the viewport, at the PdfDisplayMath display
 * seam (`PdfFitGeometry.fittedRectPx` — the F-5 fit source).
 * Plus the A-2 clip seam pins (`pdfVisibleClip`,
 * `pdfDisplayedSizePx`).
 */
class V159PdfFitMatrixTest {

    private fun assertCentredAndContained(
        pageW: Int, pageH: Int, viewW: Int, viewH: Int
    ) {
        val rect = with(PdfFitGeometry) {
            fittedRectPx(pageW, pageH, viewW, viewH)
        }
        assertTrue("left on screen", rect.leftPx >= -0.51f)
        assertTrue("top on screen", rect.topPx >= -0.51f)
        assertTrue(
            "right on screen",
            rect.leftPx + rect.widthPx <= viewW + 0.51f
        )
        assertTrue(
            "bottom on screen",
            rect.topPx + rect.heightPx <= viewH + 0.51f
        )
        assertEquals(
            "horizontally centred",
            viewW / 2f, rect.centreXPx, 0.51f
        )
        assertEquals(
            "vertically centred",
            viewH / 2f, rect.centreYPx, 0.51f
        )
        assertTrue(
            "one dimension touches the viewport (it is a fit)",
            kotlin.math.abs(rect.widthPx - viewW) < 1.01f ||
                kotlin.math.abs(rect.heightPx - viewH) < 1.01f
        )
    }

    @Test
    fun `portrait page in portrait viewport`() =
        assertCentredAndContained(396, 612, 1080, 2400)

    @Test
    fun `portrait page in landscape viewport`() =
        assertCentredAndContained(396, 612, 2400, 1080)

    @Test
    fun `landscape page in portrait viewport`() =
        assertCentredAndContained(612, 396, 1080, 2400)

    @Test
    fun `landscape page in landscape viewport`() =
        assertCentredAndContained(612, 396, 2400, 1080)

    @Test
    fun `visible clip inverts the centre-anchored zoom layer`() {
        // At zoom 1 the whole page is visible.
        val full = pdfVisibleClip(
            zoom = 1f, panPx = androidx.compose.ui.geometry.Offset.Zero,
            cellWidthPx = 1080, cellHeightPx = 1670,
            displayedWidthPx = 1080f, displayedHeightPx = 1670f
        )
        assertEquals(0f, full.left, 0.001f)
        assertEquals(1f, full.right, 0.001f)

        // At zoom 2, centred: the middle quarter of the page.
        val quarter = pdfVisibleClip(
            zoom = 2f, panPx = androidx.compose.ui.geometry.Offset.Zero,
            cellWidthPx = 1080, cellHeightPx = 1670,
            displayedWidthPx = 1080f, displayedHeightPx = 1670f
        )
        assertEquals(0.25f, quarter.left, 0.001f)
        assertEquals(0.75f, quarter.right, 0.001f)
        assertEquals(0.25f, quarter.top, 0.001f)
        assertEquals(0.75f, quarter.bottom, 0.001f)

        // Panning right by half a cell moves the visible
        // window LEFT across the page, clamped at the edge.
        val panned = pdfVisibleClip(
            zoom = 2f,
            panPx = androidx.compose.ui.geometry.Offset(540f, 0f),
            cellWidthPx = 1080, cellHeightPx = 1670,
            displayedWidthPx = 1080f, displayedHeightPx = 1670f
        )
        assertEquals(0f, panned.left, 0.001f)
        assertEquals(0.5f, panned.right, 0.001f)
    }

    @Test
    fun `displayed size follows the fit mode`() {
        // FIT_PAGE: 396×612 into 1080×2400 → width-bound.
        val (w1, h1) = pdfDisplayedSizePx(1080, 1670, 1080, 2400, PdfFitMode.FIT_PAGE)
        assertEquals(1080f, w1, 0.01f)
        assertEquals(1670f, h1, 0.01f)
        // FIT_WIDTH: 396×612 into a 1080-wide, 600-tall cell →
        // full width, taller than the cell.
        val (w2, h2) = pdfDisplayedSizePx(1080, 1670, 1080, 600, PdfFitMode.FIT_WIDTH)
        assertEquals(1080f, w2, 0.01f)
        assertEquals(1670f, h2, 0.01f)
    }

    @Test
    fun `A-2 base scale and A-6 spinner delay are the sifted constants`() {
        assertEquals(1f, PDF_BASE_SCALE, 0.0001f)
        assertEquals(120L, PDF_PLACEHOLDER_SPINNER_DELAY_MS)
    }
}
