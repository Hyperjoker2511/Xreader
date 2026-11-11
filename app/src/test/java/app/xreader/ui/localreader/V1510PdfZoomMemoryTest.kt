package app.xreader.ui.localreader

import androidx.compose.ui.geometry.Offset
import app.xreader.data.engine.PdfPageFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.10 (G1) gate: PDF page display — Q5 → B zoom memory
 * (remembered across page turns, pan clamped to the page)
 * and the VERIFY-ONLY pin for the six colour themes that
 * already ship (PdfPageFilter in PdfSession.kt — read, never
 * rebuilt). Robolectric only because the engine's matrices
 * are android.graphics.ColorMatrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V1510PdfZoomMemoryTest {

    // ── Zoom memory (Q5 → B) ────────────────────────────────

    @Test
    fun `remembered zoom opens the page zoomed`() {
        val state = PdfPageZoomState.withRememberedZoom(2.0f)
        assertEquals(2.0f, state.zoom)
        assertTrue(state.isZoomed)
    }

    @Test
    fun `fit preference and plain constructor still open at fit`() {
        assertEquals(1f, PdfPageZoomState.withRememberedZoom(1f).zoom)
        assertEquals(1f, PdfPageZoomState().zoom)
        // A corrupt/clamped preference cannot open below fit.
        assertEquals(1f, PdfPageZoomState.withRememberedZoom(0.5f).zoom)
        assertEquals(
            PdfPageZoomState.MAX_ZOOM,
            PdfPageZoomState.withRememberedZoom(99f).zoom
        )
    }

    // ── Pan clamp ───────────────────────────────────────────

    @Test
    fun `pan clamps to the page edges at zoom`() {
        // Page displays 400×600 in a 400×600 viewport at 2×:
        // half-extents = (800−400)/2 = 200, (1200−600)/2 = 300.
        val clamped = clampPdfPan(
            pan = Offset(10_000f, -10_000f),
            displayedWidthPx = 400f, displayedHeightPx = 600f,
            zoom = 2f, viewportWidthPx = 400, viewportHeightPx = 600
        )
        assertEquals(Offset(200f, -300f), clamped)
        val inside = clampPdfPan(
            pan = Offset(50f, -50f),
            displayedWidthPx = 400f, displayedHeightPx = 600f,
            zoom = 2f, viewportWidthPx = 400, viewportHeightPx = 600
        )
        assertEquals(Offset(50f, -50f), inside)
    }

    @Test
    fun `pan pins to centre when the scaled page fits`() {
        val clamped = clampPdfPan(
            pan = Offset(300f, 300f),
            displayedWidthPx = 400f, displayedHeightPx = 600f,
            zoom = 1f, viewportWidthPx = 400, viewportHeightPx = 600
        )
        assertEquals(Offset.Zero, clamped)
    }

    @Test
    fun `bound geometry clamps live transforms`() {
        val state = PdfPageZoomState.withRememberedZoom(2f)
        state.bindGeometry(400f, 600f, 400, 600)
        state.applyTransform(Offset(10_000f, 10_000f), 1f)
        assertEquals(Offset(200f, 300f), state.pan)
        // Reset still returns to fit exactly.
        state.reset()
        assertEquals(1f, state.zoom)
        assertEquals(Offset.Zero, state.pan)
    }

    @Test
    fun `unbound state keeps v1_5_9 transform behaviour`() {
        val state = PdfPageZoomState()
        state.applyTransform(Offset(10f, 10f), 2f)
        assertEquals(2f, state.zoom)
        assertEquals(Offset(10f, 10f), state.pan)
    }

    // ── Six themes: verify-only ─────────────────────────────

    @Test
    fun `six colour themes ship and are untouched`() {
        assertEquals(6, PdfPageFilter.entries.size)
        assertEquals(
            listOf(
                "NONE", "INVERT", "BLACK_AND_WHITE",
                "INVERT_BLACK_AND_WHITE", "SEPIA_PAGE", "SOFT_GREY"
            ),
            PdfPageFilter.entries.map { it.name }
        )
        // The colour themes warm/tint the raw page (positive
        // diagonal), unlike the inverting filters.
        for (theme in listOf(PdfPageFilter.SEPIA_PAGE, PdfPageFilter.SOFT_GREY)) {
            val matrix = theme.colorMatrix()
            assertTrue("theme $theme must produce a matrix", matrix != null)
            val values = matrix!!.getArray()
            assertTrue(values[0] >= 0f)
            assertTrue(values[6] >= 0f)
            assertTrue(values[12] >= 0f)
        }
        assertTrue(PdfPageFilter.NONE.colorMatrix() == null)
    }
}
