package app.xreader.ui.localreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.9 (Finding 3 §8.3 / F-5): render geometry is computed
 * from the FULL window size, so chrome showing/hiding can never
 * change a render size (and with it the cache key) — Finding 3
 * §3.4's latent re-render class. The page is HEIGHT-bound
 * (396×1200 pt in a 1080×2400 window), the case where the old
 * chrome-dependent box actually changed the answer.
 */
class V159PdfGeometryStabilityTest {

    private val pageW = 396
    private val pageH = 1200
    private val windowW = 1080
    private val windowH = 2400

    @Test
    fun `render size is identical for chrome-up and chrome-down viewports`() {
        val chromeUp = pdfRenderViewportPx(
            windowW, windowH, chromeTopPx = 180, chromeBottomPx = 420
        )
        val chromeDown = pdfRenderViewportPx(
            windowW, windowH, chromeTopPx = 0, chromeBottomPx = 0
        )
        assertEquals("the render viewport ignores chrome", chromeUp, chromeDown)
        assertEquals(windowW to windowH, chromeUp)

        val sizeUp = PdfFitGeometry.renderSizePx(
            pageW, pageH, chromeUp.first, chromeUp.second,
            PdfFitMode.FIT_PAGE, 2f
        )
        val sizeDown = PdfFitGeometry.renderSizePx(
            pageW, pageH, chromeDown.first, chromeDown.second,
            PdfFitMode.FIT_PAGE, 2f
        )
        assertEquals("identical render size → identical cache key", sizeUp, sizeDown)
        // Height-bound sanity: the height is the limiting side.
        assertEquals(windowH * 2, sizeUp.second)
    }
}
