package app.xreader.ui.localreader

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.9 (Finding 3 §8.2 / F-1): zoom/pan are TRANSIENT per-page
 * view state. The seam under test is [PdfPageZoomState] — the
 * exact state each `PdfPageCell` creates with `remember(page)`.
 * On the shipped code the persisted per-book zoom (2.0 after the
 * owner's double-tap on page 3) was applied as a graphicsLayer
 * over EVERY page and one shared pan displaced them all — the
 * cover opening halfway down the screen (video f013) and the
 * pure-black pages (f015/f016).
 */
class V159PdfFitDisplayTest {

    /** The owner's persisted magnification preference in the
     * proof video: 2.0, set by double-tapping the page-3 plate. */
    private val persistedZoom = 2.0f

    @Test
    fun `a fresh page presents identity transform despite persisted zoom 2`() {
        // A fresh cell builds a fresh state; the persisted
        // preference is NOT an input to it.
        val freshPage = PdfPageZoomState()
        assertTrue("fresh page is identity", freshPage.isIdentity)
        assertEquals(1f, freshPage.zoom, 0.0001f)
        assertEquals(Offset.Zero, freshPage.pan)
        assertFalse(freshPage.isZoomed)
    }

    @Test
    fun `zoom applies only after a gesture on that cell, to the preference level`() {
        val cell = PdfPageZoomState()
        // Double-tap reads the preference as its target.
        assertEquals(persistedZoom, cell.doubleTap(persistedZoom), 0.0001f)
        assertTrue(cell.isZoomed)
        // Panning accumulates on THIS page while zoomed.
        cell.applyTransform(Offset(50f, 80f), 1f)
        assertEquals(Offset(50f, 80f), cell.pan)
        // A drag on an UNZOOMED page can no longer drag it
        // off-screen (the f015/f016 mechanism): no transform
        // gesture, no displacement.
        val unzoomed = PdfPageZoomState()
        unzoomed.applyTransform(Offset(500f, 900f), 1f)
        assertTrue(unzoomed.isIdentity)
    }

    @Test
    fun `page change resets - the next page starts at fit`() {
        val page3 = PdfPageZoomState()
        page3.doubleTap(persistedZoom)
        page3.applyTransform(Offset(-300f, 700f), 1f)
        assertTrue(page3.isZoomed)
        // Turning the page composes a NEW cell with a NEW state.
        val page1Cover = PdfPageZoomState()
        assertTrue(
            "the cover opens whole, at fit",
            page1Cover.isIdentity
        )
        // And the zoomed page's own reset (double-tap back,
        // edge tap, slider jump) restores fit without erasing
        // the preference: double-tapping again magnifies to it.
        page3.doubleTap(persistedZoom)
        assertTrue(page3.isIdentity)
        assertEquals(persistedZoom, page3.doubleTap(persistedZoom), 0.0001f)
    }

    @Test
    fun `pinch from fit magnifies and reports the preference value`() {
        val cell = PdfPageZoomState()
        val reported = cell.applyTransform(Offset.Zero, 1.5f)
        assertEquals(1.5f, reported, 0.0001f)
        assertEquals(1.5f, cell.zoom, 0.0001f)
        // Pinching back out clears the pan on the way
        // through fit. v1.5.29: the pinch road may overshoot
        // BELOW fit mid-gesture (1.5 × 0.5 = 0.75, pan
        // zeroed) — the reader settles the state back to
        // exactly fit when the gesture ends, so below-fit
        // never rests.
        cell.applyTransform(Offset(120f, 40f), 1f)
        assertEquals(Offset(120f, 40f), cell.pan)
        cell.applyTransform(Offset(10f, 10f), 0.5f)
        assertEquals(0.75f, cell.zoom, 0.0001f)
        assertEquals(Offset.Zero, cell.pan)
        // A fit preference makes double-tap use the default 2×.
        val fresh = PdfPageZoomState()
        assertEquals(
            PdfPageZoomState.DEFAULT_DOUBLE_TAP_ZOOM,
            fresh.doubleTap(1f), 0.0001f
        )
    }
}
