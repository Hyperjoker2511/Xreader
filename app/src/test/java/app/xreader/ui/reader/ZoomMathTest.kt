package app.xreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 (WS2) regression pins for manga zoom Option A.
 *
 * The property everything hangs on is finger invariance: the
 * content point under the user's finger (double-tap) or under
 * the gesture centroid (pinch) must still be there after the
 * transform — an offset equation that scales about the wrong
 * origin makes the tapped detail jump away, which is exactly
 * the failure these tests exist to catch. The remaining pins
 * hold the Mihon/Legado envelope: pinch 0.5×–3× (floor 1× with
 * "Disable zoom out" on), double-tap 1× ↔ 2×, offsets bounded
 * by the image edges, and fit always meaning offset zero.
 */
class ZoomMathTest {

    private val width = 1000
    private val height = 2000

    private fun assertPointEquals(
        expectedX: Float,
        expectedY: Float,
        actual: Pair<Float, Float>
    ) {
        assertEquals(expectedX.toDouble(), actual.first.toDouble(), 0.01)
        assertEquals(expectedY.toDouble(), actual.second.toDouble(), 0.01)
    }

    // ── The spec numbers ──────────────────────────────────────

    @Test
    fun zoomEnvelopeMatchesTheResearchedReaders() {
        assertEquals(3f, ZoomMath.MAX_SCALE)
        assertEquals(0.5f, ZoomMath.MIN_SCALE_WITH_ZOOM_OUT)
        assertEquals(2f, ZoomMath.DOUBLE_TAP_SCALE)
        assertEquals(1f, ZoomMath.minScale(disableZoomOut = true))
        assertEquals(0.5f, ZoomMath.minScale(disableZoomOut = false))
    }

    // ── Double-tap invariance ─────────────────────────────────

    @Test
    fun doubleTapKeepsTheTappedPointUnderTheFinger() {
        val before = ZoomState()
        val tapX = 750f
        val tapY = 500f
        val contentBefore = ZoomMath.contentPoint(before, tapX, tapY, width, height)

        val after = ZoomMath.onDoubleTap(
            before, tapX, tapY, width, height,
            doubleTapEnabled = true, disableZoomOut = false
        )

        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, after.scale)
        assertTrue(after.isZoomedIn)
        // The content that was under the finger is still under it.
        assertPointEquals(
            contentBefore.first, contentBefore.second,
            ZoomMath.contentPoint(after, tapX, tapY, width, height)
        )
        // …and concretely: at fit the content point IS the tap.
        assertPointEquals(tapX, tapY, contentBefore)
    }

    @Test
    fun doubleTapAtTheCentreZoomsStraight() {
        val after = ZoomMath.onDoubleTap(
            ZoomState(), 500f, 1000f, width, height,
            doubleTapEnabled = true, disableZoomOut = false
        )
        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, after.scale)
        assertEquals(0f, after.offsetX)
        assertEquals(0f, after.offsetY)
    }

    @Test
    fun secondDoubleTapReturnsToFitWithZeroOffset() {
        val zoomed = ZoomMath.onDoubleTap(
            ZoomState(), 750f, 500f, width, height,
            doubleTapEnabled = true, disableZoomOut = false
        )
        assertTrue(zoomed.isZoomedIn)
        val back = ZoomMath.onDoubleTap(
            zoomed, 750f, 500f, width, height,
            doubleTapEnabled = true, disableZoomOut = false
        )
        assertEquals(1f, back.scale)
        assertEquals(0f, back.offsetX)
        assertEquals(0f, back.offsetY)
        assertFalse(back.isZoomedIn)
    }

    @Test
    fun doubleTapDoesNothingWhenTheSettingIsOff() {
        val state = ZoomState()
        val result = ZoomMath.onDoubleTap(
            state, 750f, 500f, width, height,
            doubleTapEnabled = false, disableZoomOut = false
        )
        assertEquals(state, result)
    }

    // ── Pinch invariance ──────────────────────────────────────

    @Test
    fun pinchKeepsTheContentPointUnderTheCentroid() {
        val before = ZoomState()
        val centroidX = 600f
        val centroidY = 800f
        val panX = 10f
        val panY = -20f
        val contentBefore =
            ZoomMath.contentPoint(before, centroidX, centroidY, width, height)

        val after = ZoomMath.onTransform(
            before,
            centroidX = centroidX, centroidY = centroidY,
            panX = panX, panY = panY, zoomChange = 1.5f,
            containerWidthPx = width, containerHeightPx = height,
            disableZoomOut = false
        )

        assertEquals(1.5f, after.scale)
        // The tracked content point moved exactly with the pan.
        assertPointEquals(
            contentBefore.first, contentBefore.second,
            ZoomMath.contentPoint(
                after, centroidX + panX, centroidY + panY, width, height
            )
        )
    }

    @Test
    fun panOnlyStepMovesTheOffsetByExactlyThePan() {
        val zoomed = ZoomState(scale = 2f)
        val after = ZoomMath.onTransform(
            zoomed,
            centroidX = 500f, centroidY = 1000f,
            panX = 30f, panY = -40f, zoomChange = 1f,
            containerWidthPx = width, containerHeightPx = height,
            disableZoomOut = false
        )
        assertEquals(2f, after.scale)
        assertEquals(30f, after.offsetX)
        assertEquals(-40f, after.offsetY)
    }

    // ── Scale envelope ────────────────────────────────────────

    @Test
    fun pinchClampsAtTripleScale() {
        val after = ZoomMath.onTransform(
            ZoomState(scale = 2.9f),
            centroidX = 500f, centroidY = 1000f,
            panX = 0f, panY = 0f, zoomChange = 1.5f,
            containerWidthPx = width, containerHeightPx = height,
            disableZoomOut = false
        )
        assertEquals(ZoomMath.MAX_SCALE, after.scale)
    }

    @Test
    fun pinchMayShrinkToHalfWhenZoomOutIsAllowed() {
        val after = ZoomMath.onTransform(
            ZoomState(),
            centroidX = 500f, centroidY = 1000f,
            panX = 0f, panY = 0f, zoomChange = 0.1f,
            containerWidthPx = width, containerHeightPx = height,
            disableZoomOut = false
        )
        assertEquals(0.5f, after.scale)
        assertEquals(0f, after.offsetX)
        assertEquals(0f, after.offsetY)
    }

    @Test
    fun disableZoomOutFloorsTheScaleAtFit() {
        val zoomed = ZoomState(scale = 2f, offsetX = 100f, offsetY = 100f)
        val after = ZoomMath.onTransform(
            zoomed,
            centroidX = 500f, centroidY = 1000f,
            panX = 0f, panY = 0f, zoomChange = 0.25f,
            containerWidthPx = width, containerHeightPx = height,
            disableZoomOut = true
        )
        assertEquals(1f, after.scale)
        // At fit the offset is always zeroed — no stranded pan.
        assertEquals(0f, after.offsetX)
        assertEquals(0f, after.offsetY)
    }

    // ── Offset envelope ───────────────────────────────────────

    @Test
    fun panIsClampedAtTheImageEdges() {
        val after = ZoomMath.onTransform(
            ZoomState(scale = 2f),
            centroidX = 500f, centroidY = 1000f,
            panX = 100_000f, panY = -100_000f, zoomChange = 1f,
            containerWidthPx = width, containerHeightPx = height,
            disableZoomOut = false
        )
        // (scale − 1) · size / 2 per axis.
        assertEquals(500f, after.offsetX)
        assertEquals(-1000f, after.offsetY)
    }

    @Test
    fun panAtFitIsANoOp() {
        val after = ZoomMath.onTransform(
            ZoomState(),
            centroidX = 300f, centroidY = 700f,
            panX = 50f, panY = 50f, zoomChange = 1f,
            containerWidthPx = width, containerHeightPx = height,
            disableZoomOut = false
        )
        assertEquals(ZoomState(), after)
    }

    @Test
    fun zoomingBackOutThroughFitZeroesTheOffset() {
        val zoomed = ZoomState(scale = 2f, offsetX = -250f, offsetY = 500f)
        val after = ZoomMath.onTransform(
            zoomed,
            centroidX = 500f, centroidY = 1000f,
            panX = 0f, panY = 0f, zoomChange = 0.4f,
            containerWidthPx = width, containerHeightPx = height,
            disableZoomOut = false
        )
        assertEquals(0.8f, after.scale)
        assertEquals(0f, after.offsetX)
        assertEquals(0f, after.offsetY)
        assertFalse(after.isZoomedIn)
    }

    // ── Degenerate inputs ─────────────────────────────────────

    @Test
    fun unknownContainerSizeLeavesTheStateAlone() {
        val state = ZoomState(scale = 2f, offsetX = 10f, offsetY = 10f)
        assertEquals(
            state,
            ZoomMath.onTransform(
                state, 100f, 100f, 5f, 5f, 1.2f,
                containerWidthPx = 0, containerHeightPx = 0,
                disableZoomOut = false
            )
        )
        assertEquals(
            ZoomState(),
            ZoomMath.onDoubleTap(
                ZoomState(), 100f, 100f,
                containerWidthPx = 0, containerHeightPx = 0,
                doubleTapEnabled = true, disableZoomOut = false
            )
        )
    }

    @Test
    fun junkGestureFactorsAreRefused() {
        val state = ZoomState(scale = 2f)
        assertEquals(
            state,
            ZoomMath.onTransform(
                state, 500f, 1000f, 0f, 0f, Float.NaN,
                containerWidthPx = width, containerHeightPx = height,
                disableZoomOut = false
            )
        )
        assertEquals(
            state,
            ZoomMath.onTransform(
                state, 500f, 1000f, 0f, 0f, 0f,
                containerWidthPx = width, containerHeightPx = height,
                disableZoomOut = false
            )
        )
        assertEquals(1f, ZoomMath.clampScale(Float.NaN, disableZoomOut = false))
    }

    // ── Decode sizing ─────────────────────────────────────────

    @Test
    fun decodeCapCoversFullZoomOnAPhoneColumn() {
        // The cap is the WS2 contract: 2048 px — wide enough that
        // a ~680 px phone column keeps headroom at the 3× pinch
        // ceiling, bounded enough for the 2 GB tablet.
        assertEquals(2048, ReaderImageDecode.MAX_WIDTH_PX)
        assertTrue(
            ReaderImageDecode.MAX_WIDTH_PX >= (ZoomMath.MAX_SCALE * 680f).toInt()
        )
    }
}
