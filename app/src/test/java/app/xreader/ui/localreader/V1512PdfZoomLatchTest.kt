package app.xreader.ui.localreader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.12 gate — FRONT 3, PDF half, P3 (contract §2.4) + the
 * production wiring census for P1/P4/P5.
 *
 * The shipped zoom latch: ONE sticky boolean written by every
 * composed cell, cleared only when the reporting cell's reset
 * handle identity-matched the stored one. The beyond-viewport
 * neighbour — which opens at the same remembered zoom —
 * overwrote the handle, so the settled page's own un-zoom
 * could never clear it: a book opened with a persisted zoom
 * > 1 had `userScrollEnabled = false` forever. The fix
 * derives scroll-enable from the SETTLED unit's pages' ACTUAL
 * zoom ([pdfPagerUserScrollEnabled]) and gates the transform
 * detector on actual zoom ([pdfTransformGesturesActive]), so
 * at fit-to-page the pager's drags belong to the pager.
 *
 * These tests pin the pure rules over the exact report
 * sequences that stranded the old latch, plus the remembered-
 * zoom state's own behaviour, plus the source census that
 * the production pager/cells actually use them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class V1512PdfZoomLatchTest {

    // ── The latch rule over the stranding sequence ───────────

    @Test
    fun `neighbour zoom report can never disable the pager`() {
        // Book opens at fit on page 5; the beyond-viewport
        // neighbour (page 6) happens to report zoomed (e.g. it
        // inherited a stale transform). The settled page is at
        // fit, so turning must stay enabled — the shipped
        // latch disabled it on ANY cell's zoomed report.
        assertTrue(
            pdfPagerUserScrollEnabled(
                settledPages = listOf(5),
                zoomedPages = setOf(6)
            )
        )
        assertTrue(
            pdfPagerUserScrollEnabled(
                settledPages = listOf(5),
                zoomedPages = emptySet()
            )
        )
    }

    @Test
    fun `settled page zoom disables and its own reset re-enables`() {
        // The F-1 rule, preserved: while the SETTLED page is
        // zoomed, the transform owns its drags (pager locked).
        assertFalse(
            pdfPagerUserScrollEnabled(
                settledPages = listOf(5),
                zoomedPages = setOf(5)
            )
        )
        // The settled page's own reset (double-tap / edge tap)
        // drops it from the zoomed set — turning is back,
        // immediately, with no identity matching involved.
        assertTrue(
            pdfPagerUserScrollEnabled(
                settledPages = listOf(5),
                zoomedPages = emptySet()
            )
        )
    }

    @Test
    fun `page change clears the latch`() {
        // Page 5 was left zoomed (remembered zoom). The reader
        // turns to page 6, which is at fit: page 5's zoom no
        // longer counts, because it left the settled set.
        assertTrue(
            pdfPagerUserScrollEnabled(
                settledPages = listOf(6),
                zoomedPages = setOf(5)
            )
        )
    }

    @Test
    fun `book opened with persisted zoom stays turnable through reset`() {
        // Owner Q4: per-book remembered zoom STAYS. A book
        // whose preference is 2.0 opens its settled page
        // zoomed — the pager is locked WHILE zoomed (correct),
        // and the page's reset returns it to fit, at which
        // point the same derivation enables turning. The old
        // latch stranded at the middle step whenever the
        // neighbour cell had reported in between.
        val zoomState = PdfPageZoomState.withRememberedZoom(2f)
        assertTrue(zoomState.isZoomed)
        val page = 3
        var zoomedPages = setOf(page)
        // Neighbour reports arrive (pages 2 and 4, also at the
        // remembered zoom): the settled derivation is unmoved
        // by them either way.
        zoomedPages = zoomedPages + setOf(2, 4)
        assertFalse(
            pdfPagerUserScrollEnabled(listOf(page), zoomedPages)
        )
        // The reader double-taps: the settled page resets.
        zoomState.reset()
        assertFalse(zoomState.isZoomed)
        zoomedPages = zoomedPages - page
        assertTrue(
            "after the settled page's own reset, turning works " +
                "even though neighbour reports still say zoomed",
            pdfPagerUserScrollEnabled(
                settledPages = listOf(page),
                zoomedPages = zoomedPages - page
            )
        )
        // And a spread: either settled page zoomed locks.
        assertFalse(
            pdfPagerUserScrollEnabled(
                settledPages = listOf(4, 5),
                zoomedPages = setOf(5)
            )
        )
    }

    // ── The transform-detector gate ──────────────────────────

    @Test
    fun `transform detector is active only while zoomed`() {
        // At fit, NO detector: a fit-scale drag is the pager's.
        assertFalse(pdfTransformGesturesActive(isZoomed = false))
        // Zoomed (entered via the explicit double-tap road),
        // the detector owns pinch/pan.
        assertTrue(pdfTransformGesturesActive(isZoomed = true))
        // The state itself agrees with the gate's input.
        val fit = PdfPageZoomState()
        assertFalse(pdfTransformGesturesActive(fit.isZoomed))
        val zoomed = PdfPageZoomState.withRememberedZoom(1.5f)
        assertTrue(pdfTransformGesturesActive(zoomed.isZoomed))
    }

    // ── Production wiring census (P1 / P3 / P4 / P5) ─────────

    @Test
    fun `production pager and cells use the derived rules`() {
        val reader = sourceFile(
            "app/src/main/java/app/xreader/ui/localreader/PdfDocumentReader.kt"
        ).readText()
        // P3: scroll-enable is the derivation, not the latch.
        assertTrue(reader.contains("userScrollEnabled = pagerScrollEnabled"))
        assertTrue(reader.contains("pdfPagerUserScrollEnabled("))
        assertFalse(reader.contains("userScrollEnabled = !activeZoomed"))
        assertFalse(reader.contains("resetActiveZoom === reset"))
        // P3: the transform loop is always installed, but
        // self-gates on the cell's actual zoom state. It
        // enters transform mode when already zoomed or when
        // a second pointer makes the gesture a pinch, and
        // reports each claim to the pager synchronously.
        assertFalse(
            reader.contains("pdfTransformGesturesActive(zoomState.isZoomed)")
        )
        assertTrue(
            reader.contains("var transformMode = zoomStateRef.value.isZoomed")
        )
        assertTrue(
            reader.contains("if (!transformMode && pressed >= 2)")
        )
        assertEquals(
            2,
            reader.split("onZoomActivity(page, true, resetHandle)").size - 1
        )
        assertTrue(reader.contains("if (transformMode) {"))
        // P1: the paged cell keys its render effect on the
        // session's published pager state.
        assertTrue(
            reader.contains(
                "LaunchedEffect(page, renderWidthPx, slot, landingBoost, pagerPublication)"
            )
        )
        // P4: composition never calls the fetching size read.
        assertFalse(reader.contains("pagePointsSizeCached"))
        assertTrue(reader.contains("pagePointsSizePeek"))
        assertTrue(reader.contains("requestPageSizeAsync"))
        // P5: prefetch windows are single-flight per settle in
        // BOTH bodies (paged + continuous).
        assertEquals(
            2,
            reader.split("prefetchJob?.cancel()").size - 1
        )
    }

    @Test
    fun `session carries the P1 retry and P2 hardening`() {
        val session = sourceFile(
            "app/src/main/java/app/xreader/data/engine/PdfSession.kt"
        ).readText()
        assertTrue(session.contains("coherentSlotFor"))
        assertTrue(session.contains("STALE_RETRY_BUDGET"))
        assertTrue(
            "release inside the render finally is NonCancellable-guarded",
            session.contains("withContext(NonCancellable) { scheduler.release(waiter) }")
        )
        assertTrue(session.contains("val pagerPublication"))
        assertTrue(session.contains("fun pagePointsSizePeek"))
    }

    private fun sourceFile(path: String): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val base = dir ?: error("cannot locate $path")
            val candidate = File(base, path)
            if (candidate.isFile) return candidate
            val stripped = File(base, path.removePrefix("app/"))
            if (stripped.isFile) return stripped
            dir = base.parentFile
        }
        error("cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
