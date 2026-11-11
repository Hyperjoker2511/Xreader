package app.xreader.ui.localreader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.xreader.data.engine.PdfPageFilter

/**
 * v1.5.8: the PDF render/display seam's pure geometry — fit
 * (H1) and spread pairing (H9): display transforms over the
 * existing render cache; neither re-renders a page.
 * (Margin crop (H4) was removed in v1.5.25 by owner
 * decision — pinch zoom is the way to fill the screen.)
 */

// ── H3: the one-tap night toggle ─────────────────────────────────

/**
 * The bottom row's night toggle: Invert B&W ⇄ As printed — the
 * SAME [app.xreader.data.engine.PdfPageFilter] pref the display
 * sheet writes (one state, two controls). From any other preset
 * the toggle lands on Invert B&W (the night side).
 */
fun app.xreader.data.engine.PdfPageFilter.toggledNight():
    app.xreader.data.engine.PdfPageFilter =
    if (this == app.xreader.data.engine.PdfPageFilter.INVERT_BLACK_AND_WHITE) {
        app.xreader.data.engine.PdfPageFilter.NONE
    } else {
        app.xreader.data.engine.PdfPageFilter.INVERT_BLACK_AND_WHITE
    }

// ── H1: fit-page / fit-width ─────────────────────────────────────

object PdfFitGeometry {

    /**
     * The render request size (device px) for a page of
     * [pageWidthPt]×[pageHeightPt] points inside a
     * [viewportWidthPx]×[viewportHeightPx] viewport.
     *
     * FIT_WIDTH reproduces the shipped v1.5.7 numbers exactly
     * (the fence): width = viewport width × oversample, height
     * follows the page aspect.
     *
     * FIT_PAGE scales so the WHOLE page bounds inside the
     * viewport aspect: the limiting dimension governs, so a
     * page taller than the screen renders at the size that
     * shows it whole.
     */
    fun renderSizePx(
        pageWidthPt: Int,
        pageHeightPt: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        fitMode: PdfFitMode,
        oversample: Float
    ): Pair<Int, Int> {
        val pw = pageWidthPt.coerceAtLeast(1)
        val ph = pageHeightPt.coerceAtLeast(1)
        val os = oversample.coerceIn(1f, 2.5f)
        return when (fitMode) {
            PdfFitMode.FIT_WIDTH -> {
                val width = (viewportWidthPx * os).toInt().coerceAtLeast(1)
                val height = (width.toFloat() * ph / pw).toInt().coerceAtLeast(1)
                width to height
            }
            PdfFitMode.FIT_PAGE -> {
                val scale = minOf(
                    viewportWidthPx.toFloat() / pw,
                    viewportHeightPx.toFloat() / ph
                ) * os
                val width = (pw * scale).toInt().coerceAtLeast(1)
                val height = (ph * scale).toInt().coerceAtLeast(1)
                width to height
            }
        }
    }
}

// ── v1.5.9 (B-3): the fit matrix ─────────────────────────────────

/** A page rect in viewport pixels — where FIT_PAGE actually
 * draws the page inside the window. */
data class FittedPageRect(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float
) {
    val centreXPx: Float get() = leftPx + widthPx / 2f
    val centreYPx: Float get() = topPx + heightPx / 2f
}

/**
 * The rect a page of [pageWidthPt]×[pageHeightPt] occupies in
 * a [viewportWidthPx]×[viewportHeightPx] viewport under
 * FIT_PAGE: min-scale, centred on both axes, never exceeding
 * the viewport. (FIT_WIDTH shares the scale, anchored to the
 * width — its rect can exceed the height by design, so the
 * centred-and-contained contract is FIT_PAGE's.)
 */
fun PdfFitGeometry.fittedRectPx(
    pageWidthPt: Int,
    pageHeightPt: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int
): FittedPageRect {
    val pw = pageWidthPt.coerceAtLeast(1).toFloat()
    val ph = pageHeightPt.coerceAtLeast(1).toFloat()
    val scale = minOf(viewportWidthPx / pw, viewportHeightPx / ph)
    val width = pw * scale
    val height = ph * scale
    return FittedPageRect(
        leftPx = (viewportWidthPx - width) / 2f,
        topPx = (viewportHeightPx - height) / 2f,
        widthPx = width,
        heightPx = height
    )
}

// ── v1.5.9 (A-2): the zoom-refine clip ───────────────────────────

/** The size the page picture is DISPLAYED at inside a cell of
 * [cellWidthPx]×[cellHeightPx] (before the zoom layer): the
 * fit branches' own output size. */
fun pdfDisplayedSizePx(
    bitmapWidthPx: Int,
    bitmapHeightPx: Int,
    cellWidthPx: Int,
    cellHeightPx: Int,
    fitMode: PdfFitMode
): Pair<Float, Float> {
    val bw = bitmapWidthPx.coerceAtLeast(1).toFloat()
    val bh = bitmapHeightPx.coerceAtLeast(1).toFloat()
    return when (fitMode) {
        PdfFitMode.FIT_WIDTH -> {
            val width = cellWidthPx.toFloat()
            width to width * bh / bw
        }
        PdfFitMode.FIT_PAGE -> {
            val scale = minOf(cellWidthPx / bw, cellHeightPx / bh)
            (bw * scale) to (bh * scale)
        }
    }
}

/**
 * The visible clip of the page, normalised 0..1, under a
 * centre-anchored zoom layer: the layer scales the displayed
 * picture by [zoom] about the cell centre and translates it
 * by [panPx]. Inverting that mapping over the cell's own
 * bounds gives exactly the region the refine pass must render
 * (A-2) — and nothing outside the page ever enters it.
 */
fun pdfVisibleClip(
    zoom: Float,
    panPx: Offset,
    cellWidthPx: Int,
    cellHeightPx: Int,
    displayedWidthPx: Float,
    displayedHeightPx: Float
): NormalizedRect {
    val z = zoom.coerceAtLeast(1f)
    val dw = (displayedWidthPx * z).coerceAtLeast(1f)
    val dh = (displayedHeightPx * z).coerceAtLeast(1f)
    fun axis(cell: Int, pan: Float, displayed: Float): Pair<Float, Float> {
        val centre = 0.5f - pan / displayed
        val half = (cell / 2f) / displayed
        return (centre - half).coerceIn(0f, 1f) to (centre + half).coerceIn(0f, 1f)
    }
    val (left, right) = axis(cellWidthPx, panPx.x, dw)
    val (top, bottom) = axis(cellHeightPx, panPx.y, dh)
    return NormalizedRect(left, top, right, bottom)
}

// ── v1.5.9 (F-5): stable render geometry ─────────────────────────

/**
 * The viewport the RENDER size is computed from: the FULL
 * window size. Chrome heights are accepted (callers have them
 * at hand) and deliberately ignored — Finding 3 §3.4: feeding
 * the chrome-dependent content box made a chrome toggle change
 * the render size (new cache key, full re-render) for any
 * height-bound page. The display `ContentScale.Fit` centres the
 * page inside whatever box the chrome leaves; the render itself
 * never changes.
 */
fun pdfRenderViewportPx(
    windowWidthPx: Int,
    windowHeightPx: Int,
    chromeTopPx: Int = 0,
    chromeBottomPx: Int = 0
): Pair<Int, Int> =
    windowWidthPx.coerceAtLeast(1) to windowHeightPx.coerceAtLeast(1)

// ── v1.5.10 (G1, Q5 → B): clamped pan ────────────────────────────

/**
 * Clamps a pan offset so the zoomed page can never be dragged
 * (or landed) onto empty black: per axis, the page's scaled
 * size is [displayedPx] × [zoom]; the offset may move the
 * page's centre at most half the overflow beyond the
 * [viewportPx] cell. When the scaled page fits the cell on an
 * axis, that axis is pinned to 0 (centred). Pure — the gate
 * test tables the corners.
 */
fun clampPdfPan(
    pan: Offset,
    displayedWidthPx: Float,
    displayedHeightPx: Float,
    zoom: Float,
    viewportWidthPx: Int,
    viewportHeightPx: Int
): Offset {
    val z = zoom.coerceAtLeast(1f)
    fun clampAxis(value: Float, displayed: Float, viewport: Int): Float {
        val overflow = displayed * z - viewport
        if (overflow <= 0f) return 0f
        val max = overflow / 2f
        return value.coerceIn(-max, max)
    }
    return Offset(
        x = clampAxis(pan.x, displayedWidthPx, viewportWidthPx),
        y = clampAxis(pan.y, displayedHeightPx, viewportHeightPx)
    )
}

// ── v1.5.9 (F-1): transient per-page zoom/pan ────────────────────
// ── v1.5.10 (G1, Q5 → B): a page may OPEN at the remembered zoom.

/**
 * One page cell's zoom/pan VIEW state (Finding 3 F-1). A fresh
 * instance is identity (zoom 1, pan 0) — a page ALWAYS opens at
 * fit. The persisted per-book `PdfDocSettings.zoom` is only the
 * MAGNIFICATION PREFERENCE: [doubleTap] reads it as the target
 * magnification, and pinch results are reported back out by the
 * cell to be persisted as the new preference. The preference is
 * never applied to a fresh page, and one page's pan can never
 * reach another page — each cell owns its own instance via
 * `remember(page)`.
 *
 * v1.5.29: the READER's double-tap road no longer reads the
 * preference at all — it is a fixed fit ↔ 2× toggle (Komikku
 * semantics; the preference pinch keeps rewriting made the
 * double-tap size change from tap to tap). The preference now
 * has exactly two jobs left: the remembered OPEN zoom
 * ([withRememberedZoom]) and the value pinch persists. The
 * state-level [doubleTap] helper keeps its shipped shape for
 * its existing tests; no production caller remains.
 *
 * v1.5.10 (G1, Q5 → B) reverses F-1's open-at-fit trade under
 * the owner's word: [withRememberedZoom] constructs the state
 * AT the book's remembered zoom (the same persisted
 * preference), so turning a page lands at the zoom the reader
 * left — with the pan CLAMPED by [clampPdfPan] (through
 * [bindGeometry]) so a turn can never land on empty black.
 * The plain constructor keeps F-1's fit-open exactly: a NEW
 * book (preference 1f) opens at fit either way.
 */
class PdfPageZoomState(initialZoom: Float = 1f) {
    // Snapshot state: the cell's graphicsLayer reads these in
    // composition, so a gesture's write must recompose it.
    var zoom: Float by mutableStateOf(
        if (initialZoom > ZOOMED_EPSILON) {
            initialZoom.coerceIn(1f, MAX_ZOOM)
        } else {
            1f
        }
    )
        private set
    var pan: Offset by mutableStateOf(Offset.Zero)
        private set

    // G1: the geometry the pan clamp needs, bound by the cell
    // once it knows its size + the displayed page size. Unbound
    // (no geometry yet) = unclamped, exactly the pre-G1 shape.
    private var boundDisplayed: Pair<Float, Float>? = null
    private var boundViewport: Pair<Int, Int>? = null

    /** Binds the clamp geometry; re-clamps the current pan
     * against it (a remembered-zoom landing is centred, so
     * this only bites a pan that outlived a resize). */
    fun bindGeometry(
        displayedWidthPx: Float,
        displayedHeightPx: Float,
        viewportWidthPx: Int,
        viewportHeightPx: Int
    ) {
        boundDisplayed = displayedWidthPx to displayedHeightPx
        boundViewport = viewportWidthPx to viewportHeightPx
        pan = clamped(pan)
    }

    private fun clamped(candidate: Offset): Offset {
        val displayed = boundDisplayed ?: return candidate
        val viewport = boundViewport ?: return candidate
        return clampPdfPan(
            pan = candidate,
            displayedWidthPx = displayed.first,
            displayedHeightPx = displayed.second,
            zoom = zoom,
            viewportWidthPx = viewport.first,
            viewportHeightPx = viewport.second
        )
    }

    val isZoomed: Boolean get() = zoom > ZOOMED_EPSILON

    /** The graphicsLayer transform this state applies: identity
     * for a fresh/reset page. */
    val isIdentity: Boolean get() = zoom == 1f && pan == Offset.Zero

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }

    /**
     * Double-tap: zoomed → back to fit; at fit → magnify to the
     * persisted preference (or [DEFAULT_DOUBLE_TAP_ZOOM] when
     * the preference is fit itself). Returns the new zoom.
     * Deliberately does NOT write the preference — resetting to
     * fit must not erase the reader's chosen magnification.
     */
    fun doubleTap(magnificationPreference: Float): Float {
        if (isZoomed) {
            reset()
        } else {
            zoom = if (magnificationPreference > ZOOMED_EPSILON) {
                magnificationPreference.coerceIn(1f, MAX_ZOOM)
            } else {
                DEFAULT_DOUBLE_TAP_ZOOM
            }
            pan = Offset.Zero
        }
        return zoom
    }

    /**
     * One transform-gesture step. A pinch-out that reaches fit
     * clears the pan with it; pans only accumulate while zoomed.
     * Returns the new zoom (the cell persists it as the
     * magnification preference when it changed).
     *
     * v1.5.29 (the pinch rest floor): the floor of THIS road
     * is [PINCH_OVERSHOOT_FLOOR], not 1f — a pinch-out that
     * starts zoomed may overshoot BELOW fit while the fingers
     * are down (the pan stays zeroed down there, so the page
     * shrinks centred, SSIV-style), and the reader settles
     * the state back to exactly fit when the gesture ends
     * (the donor's minScale settle). At exact fit a pinch-out
     * is still ignored whole, so the pager keeps the gesture.
     * Every other road into this state (constructor,
     * [setZoomPan], [reset]) keeps the [1f, MAX_ZOOM] clamp:
     * below-fit exists only mid-pinch, never at rest.
     */
    /**
     * v1.5.38 (Fix 1, PDF-ZOOM-ANCHOR-FORENSICS M1): the
     * FOCAL step. The layer is centre-anchored
     * (screen = C + (s − C)·z + pan), so for the content
     * under the fingers to stay under the fingers, one step
     * with clamped zoom ratio k and gesture centroid g
     * requires pan' = k·pan + panChange + (g − C)·(1 − k) —
     * the formula the continuous model in this same file
     * ([PdfSurfaceZoomState.applyPinch]) already implements
     * and this paged state omitted both correction terms
     * from: the pinch zoomed about the screen centre
     * wherever the fingers were, and the owner had to chase
     * the drift with drags. [centroidPx]/[centrePx] are the
     * gesture centroid and the cell centre, both in cell px.
     * [focal] is the caller's pointer-set guard: on an event
     * whose pressed-pointer set changed (a finger landed or
     * lifted mid-gesture) the centroid teleports, so that
     * event applies with the focal term suppressed — the
     * k-rescale and the pan change only — rather than
     * injecting a one-frame jump of exactly the class being
     * fixed. A null centroid (every pre-v1.5.38 caller and
     * test) degrades to the same suppressed form.
     */
    fun applyTransform(
        panChange: Offset,
        zoomChange: Float,
        centroidPx: Offset? = null,
        centrePx: Offset? = null,
        focal: Boolean = true
    ): Float {
        if (zoom == 1f && zoomChange <= 1f) return zoom
        val old = zoom
        val next = (zoom * zoomChange).coerceIn(PINCH_OVERSHOOT_FLOOR, MAX_ZOOM)
        zoom = next
        pan = if (next <= ZOOMED_EPSILON) {
            Offset.Zero
        } else {
            val k = next / old
            val focalTerm = if (focal && centroidPx != null &&
                centrePx != null
            ) {
                (centroidPx - centrePx) * (1f - k)
            } else {
                Offset.Zero
            }
            clamped(pan * k + panChange + focalTerm)
        }
        return zoom
    }

    /**
     * v1.5.28: a direct set for the ANIMATED roads (the
     * double-tap glide) — the caller drives (zoom, pan) frame
     * by frame toward its target; the pan is clamped against
     * the bound geometry exactly as a gesture's would be, and
     * reaching fit clears the pan with the zoom.
     */
    fun setZoomPan(newZoom: Float, newPan: Offset) {
        zoom = newZoom.coerceIn(1f, MAX_ZOOM)
        pan = if (zoom <= ZOOMED_EPSILON) {
            Offset.Zero
        } else {
            clamped(newPan)
        }
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_ZOOM = 2f
        const val MAX_ZOOM = 4f
        const val ZOOMED_EPSILON = 1.01f

        /** v1.5.29: how far a live pinch-out may overshoot
         * below fit before the gesture-end settle restores
         * exactly 1f (the donor webtoon model's MIN_RATE). */
        const val PINCH_OVERSHOOT_FLOOR = 0.5f

        /** G1 (Q5 → B): a page cell's state opening at the
         * book's remembered zoom — [remembered] is the
         * persisted `PdfDocSettings.zoom`; 1f (a new book)
         * opens at fit, unchanged. */
        fun withRememberedZoom(remembered: Float): PdfPageZoomState =
            PdfPageZoomState(initialZoom = remembered)
    }
}

/**
 * v1.5.28: the pan that carries the TAPPED point to the cell
 * centre under a centre-anchored zoom layer — Komikku's paged
 * double-tap (SSIV's ZOOM_FOCUS_CENTER), mirrored. The layer
 * maps a content point s (cell px) to C + (s − C)·z + pan,
 * where C is the cell centre; solving "the tapped point lands
 * on C" for pan gives pan = (C − tap)·z. The tap is taken at
 * fit (the only state the focal double-tap starts from), so
 * the content point under the finger is the tap position
 * itself. The result is UNclamped — [PdfPageZoomState] clamps
 * on apply, which is the donor's PAN_LIMIT_INSIDE behaviour:
 * a tap near a page edge centres as far as the page allows.
 */
fun pdfTapCentredPan(
    tapPx: Offset,
    cellWidthPx: Int,
    cellHeightPx: Int,
    zoom: Float
): Offset {
    val centre = Offset(cellWidthPx / 2f, cellHeightPx / 2f)
    return (centre - tapPx) * zoom
}

// ── v1.5.28: the continuous model's surface zoom ────────────────

/**
 * v1.5.28: the CONTINUOUS (Scroll-down) model's zoom state —
 * Komikku's webtoon model mirrored (build-logs/
 * V1528-ZOOM-MAP.md §3.2): the zoom is a transform of the
 * WHOLE scrolling surface, never of an item. Pinch applies
 * the focal formula (the point under the centroid stays
 * put); drags while zoomed pan within the donor's limits
 * (max offset per axis = half the surface × (scale − 1), so
 * the surface can never be dragged into empty space);
 * double-tap toggles fit ↔ 2× (animated by the caller through
 * [setScalePan]); returning to fit zeroes the pan. The scale
 * range is [1, MAX_SCALE]: the donor also allows 0.5×
 * zoom-OUT (with a layout-height compensation our list does
 * not mirror) — PDF pages at fit-width are already fully
 * visible, so zoom-out has no reading purpose here.
 */
class PdfSurfaceZoomState {
    var scale: Float by mutableStateOf(1f)
        private set
    var pan: Offset by mutableStateOf(Offset.Zero)
        private set

    val isZoomed: Boolean get() = scale > ZOOMED_EPSILON

    private fun clampedPan(
        candidate: Offset,
        forScale: Float,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int
    ): Offset {
        if (forScale <= 1f || surfaceWidthPx <= 0 || surfaceHeightPx <= 0) {
            return Offset.Zero
        }
        val maxX = surfaceWidthPx / 2f * (forScale - 1f)
        val maxY = surfaceHeightPx / 2f * (forScale - 1f)
        return Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY)
        )
    }

    /** One pinch step: scale about [centroidPx] (surface px),
     * the centroid's content point staying under the fingers. */
    fun applyPinch(
        centroidPx: Offset,
        zoomChange: Float,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int
    ) {
        val old = scale
        val next = (old * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        if (next == old) return
        val ratio = next / old
        val centre = Offset(
            surfaceWidthPx / 2f, surfaceHeightPx / 2f
        )
        pan = clampedPan(
            pan + (centroidPx - centre - pan) * (1f - ratio),
            next, surfaceWidthPx, surfaceHeightPx
        )
        scale = next
        if (!isZoomed) pan = Offset.Zero
    }

    /** A drag pan while zoomed (the caller decides which axes
     * a given drag may move — the donor pans X always, Y only
     * at the list's ends). */
    fun panBy(deltaPx: Offset, surfaceWidthPx: Int, surfaceHeightPx: Int) {
        if (!isZoomed) return
        pan = clampedPan(pan + deltaPx, scale, surfaceWidthPx, surfaceHeightPx)
    }

    /** A direct set for the animated double-tap road. */
    fun setScalePan(
        newScale: Float,
        newPan: Offset,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int
    ) {
        scale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
        pan = if (!isZoomed) {
            Offset.Zero
        } else {
            clampedPan(newPan, scale, surfaceWidthPx, surfaceHeightPx)
        }
    }

    /**
     * The double-tap toggle's target (donor: 200 ms animated):
     * zoomed → back to fit; at fit → [DOUBLE_TAP_SCALE] with
     * the tapped point held in place — pan = (centre − tap) ×
     * (scale − 1), the WebtoonRecyclerView formula.
     */
    fun doubleTapTarget(
        tapPx: Offset,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int
    ): Pair<Float, Offset> {
        if (isZoomed) return 1f to Offset.Zero
        val centre = Offset(
            surfaceWidthPx / 2f, surfaceHeightPx / 2f
        )
        val targetPan = (centre - tapPx) * (DOUBLE_TAP_SCALE - 1f)
        return DOUBLE_TAP_SCALE to clampedPan(
            targetPan, DOUBLE_TAP_SCALE, surfaceWidthPx, surfaceHeightPx
        )
    }

    fun reset() {
        scale = 1f
        pan = Offset.Zero
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 3f
        const val DOUBLE_TAP_SCALE = 2f
        const val ZOOMED_EPSILON = 1.01f
    }
}

/**
 * v1.5.12 (P3 / contract §2.4): the pager's scroll-enable rule,
 * derived from the SETTLED unit's pages ONLY. The shipped
 * latch was a single sticky boolean written by EVERY composed
 * cell (including the beyond-viewport neighbour) and cleared
 * only through an identity-matched reset handle — once the
 * neighbour's report overwrote the handle, the settled page's
 * own un-zoom could never clear it and `userScrollEnabled`
 * stranded false. The rule now: the pager scrolls unless a
 * page of the unit the pager is SETTLED on is actually zoomed
 * right now. Consequences, by construction: a zoom report from
 * a non-settled (neighbour) cell can never disable turning; a
 * page change clears the latch (the old page's zoom simply
 * leaves the settled set); a settled page's own reset re-
 * enables turning immediately.
 */
fun pdfPagerUserScrollEnabled(
    settledPages: List<Int>,
    zoomedPages: Set<Int>
): Boolean = settledPages.none { it in zoomedPages }

/**
 * v1.5.12 (P3 / contract §2.1+§2.4): whether a page cell's
 * transform-gesture detector may be ACTIVE (installed and
 * consuming). At fit-to-page the pager's drags belong to the
 * pager — one owner per gesture — so the detector exists only
 * while the page is actually zoomed. Zoom is ENTERED through
 * the explicit double-tap road (its own detector, which never
 * consumes drags); once zoomed, pinch/pan transforms own the
 * cell's gestures until the zoom returns to fit.
 *
 * v1.5.28: the cell's transform loop is now always installed
 * and SELF-GATES instead (it consumes only once a gesture is
 * a pinch or the page is zoomed — the same ownership rule,
 * enforced inside the loop, which is what lets pinch ENTER
 * from fit without ever starving the pager). This helper
 * remains as the statement of the P3 rule the loop enforces.
 */
fun pdfTransformGesturesActive(isZoomed: Boolean): Boolean = isZoomed

// ── v1.5.9 (F-7/F-8): the page-shaped loading placeholder ────────

/** What a pending page shows (both scroll models): a surface at
 * the page's TRUE aspect (from the cached size table) in the
 * design's tier-1 colour, with a spinner and the page label —
 * never a bare spinner on black (SLOP-CHECK tell #6). */
data class PdfPlaceholderSpec(
    val aspect: Float,
    val label: String,
    val surfaceArgb: Long
)

/** v1.5.9 (A-6): the placeholder's spinner appears only when
 * the bitmap is still absent after this long — fast loads
 * never flash a spinner. Tunable. */
const val PDF_PLACEHOLDER_SPINNER_DELAY_MS = 120L

fun pdfPlaceholderSpec(page: Int, points: Pair<Int, Int>?): PdfPlaceholderSpec {
    val aspect = if (points != null && points.first > 0 && points.second > 0) {
        points.first.toFloat() / points.second
    } else {
        0.707f
    }
    return PdfPlaceholderSpec(
        aspect = aspect,
        label = "Page ${page + 1}",
        surfaceArgb = 0xFF0C0D12
    )
}

// ── v1.5.9 (§8.6): one transform per mirrored control ────────────

/** The filter write BOTH the bottom Night chip and the display
 * sheet's filter row perform — one transform, two controls. */
fun PdfDocSettings.withFilter(filter: PdfPageFilter): PdfDocSettings =
    copy(filter = filter)

/** A sub-rectangle of a page, normalised to 0..1 per axis. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        val IDENTITY = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

// ── H9: dual-page spread pairing ─────────────────────────────────

object PdfSpreadMath {

    /** Pager units: single pages, or page pairs when [spread]. */
    fun unitCount(pageCount: Int, spread: Boolean): Int =
        if (spread) (pageCount + 1) / 2 else pageCount

    /** The pages a pager unit shows: [n] alone, or [n, n+1]. */
    fun pagesForUnit(unit: Int, pageCount: Int, spread: Boolean): List<Int> {
        if (!spread) return listOf(unit.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
        val first = unit * 2
        if (first >= pageCount) return emptyList()
        return if (first + 1 < pageCount) listOf(first, first + 1) else listOf(first)
    }

    /** The unit a page belongs to (readouts count the
     * underlying page numbers honestly — a spread's position IS
     * its first page). */
    fun unitForPage(page: Int, spread: Boolean): Int =
        if (spread) page / 2 else page
}
