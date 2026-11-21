package app.xreader.ui.reader

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.engine.LocatorJson
import app.xreader.data.engine.OutlineEntry
import app.xreader.data.engine.PdfPageClip
import app.xreader.data.engine.PdfPageFilter
import app.xreader.data.engine.PdfProbeState
import app.xreader.data.engine.PdfRenderSlot
import app.xreader.data.engine.PdfSession
import app.xreader.data.engine.PdfStripState
import app.xreader.ui.localreader.AutoScrollController
import app.xreader.ui.localreader.PDF_BASE_SCALE
import app.xreader.ui.localreader.PDF_DOUBLE_TAP_ANIM_MS
import app.xreader.ui.localreader.PDF_REFINE_CLIP_MARGIN
import app.xreader.ui.localreader.PDF_ZOOM_SUPERSAMPLE
import app.xreader.ui.localreader.PdfDocSettings
import app.xreader.ui.localreader.PdfFitGeometry
import app.xreader.ui.localreader.PdfFitMode
import app.xreader.ui.localreader.PdfPageZoomState
import app.xreader.ui.localreader.PdfPlaceholderSpec
import app.xreader.ui.localreader.PdfScrollModel
import app.xreader.ui.localreader.PdfSpreadMath
import app.xreader.ui.localreader.PdfSurfaceZoomState
import app.xreader.ui.localreader.ReaderViolet
import app.xreader.ui.localreader.TapLayoutPreset
import app.xreader.ui.localreader.pdfBaseSlotWidthPx
import app.xreader.ui.localreader.pdfPagerUserScrollEnabled
import app.xreader.ui.localreader.pdfPlaceholderSpec
import app.xreader.ui.localreader.pdfRenderViewportPx
import app.xreader.ui.localreader.pdfTapObserver
import app.xreader.ui.localreader.resolvedForDisplay
import app.xreader.ui.localreader.tapActionFor
import app.xreader.ui.localreader.withFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * v1.7 OE-1 (R4, R5): the Pages-mode surface of a PDF
 * inside the core reader — the shipped LocalDoc surface's
 * rendering internals (paged/continuous bodies,
 * [PdfPageCell], zoom machinery, [PdfFilmstripRow]),
 * moved host VERBATIM under R4's no-behaviour-change
 * rule; only the ViewModel seam became the narrow
 * [PdfPagesHandlers] bundle (the four calls the internals
 * ever made: jumps, position reports, page seeks,
 * settings writes). The LocalDoc destination's own bars
 * and sheets did NOT move — the core reader's chrome
 * replaces them.
 *
 * The tap preset and the continuous model's smart
 * scroll lock ride their shipped defaults here
 * (TapLayoutPreset.MOON_DEFAULT, lock on): they were
 * seats of the retired surface's bar-customisation
 * store, which does not move with the internals.
 */

/** The Pages internals' whole seam to the reader's
 * brain — the four handlers the shipped bodies called
 * on LocalDocReaderViewModel, nothing else. */
class PdfPagesHandlers(
    val jumps: SharedFlow<Int>,
    val onPosition: (page: Int, inPageFraction: Float) -> Unit,
    val onGoToPage: (Int) -> Unit,
    val onUpdateSettings: ((PdfDocSettings) -> PdfDocSettings) -> Unit
)

/** Pages mode: the printed pages, in the book's chosen
 * scroll model — the shipped bodies, unaltered. */
@Composable
fun PdfPagesSurface(
    session: PdfSession,
    settings: PdfDocSettings,
    initialPage: Int,
    currentPage: Int,
    handlers: PdfPagesHandlers,
    autoScroll: AutoScrollController,
    onToggleControls: () -> Unit,
    onResolvedFilter: (Int, PdfPageFilter) -> Unit
) {
    when (settings.scrollModel) {
        PdfScrollModel.PAGED -> PdfPagedBody(
            handlers = handlers,
            session = session,
            initialPage = initialPage,
            currentPage = currentPage,
            settings = settings,
            autoScroll = autoScroll,
            onToggleControls = onToggleControls,
            onResolvedFilter = onResolvedFilter
        )
        PdfScrollModel.CONTINUOUS -> PdfContinuousBody(
            handlers = handlers,
            session = session,
            initialPage = initialPage,
            settings = settings,
            autoScroll = autoScroll,
            onToggleControls = onToggleControls,
            onResolvedFilter = onResolvedFilter
        )
    }
}

private const val FILMSTRIP_PREVIEW_BASE_WIDTH_PX = 384

/**
 * v1.7 MR-2 (P4): the filmstrip seek row — one thumbnail
 * per page, horizontally scrolling, the current page
 * outlined in the accent. Tapping a thumb seeks through
 * the same door the slider uses ([onSeek] →
 * `goToPdfPage`); when the page changes by any other
 * door, the strip follows and re-centres on it.
 *
 * Render-slot discipline (the P4 checkpoint): thumbs are
 * the session's PREVIEW pass requested at
 * [PdfRenderSlot.P2] — the speculative class. A strip
 * full of thumbs can therefore never delay the visible
 * page's P0 render or a landing's P1; the scheduler
 * serves them only with otherwise-idle capacity.
 */
@Composable
internal fun PdfFilmstripRow(
    session: PdfSession,
    currentPage: Int,
    pageCount: Int,
    rtlPaging: Boolean,
    onSeek: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val thumbWidthPx = with(density) { 46.dp.toPx() }
    // Follow: when the page changes elsewhere (turns,
    // contents, go-to), bring its thumb to the middle of
    // the strip. The offset is computed from the strip's
    // own laid-out viewport, so it holds at any width.
    LaunchedEffect(currentPage) {
        val viewport = listState.layoutInfo.viewportEndOffset -
            listState.layoutInfo.viewportStartOffset
        val offset = if (viewport > 0) {
            -(viewport / 2 - thumbWidthPx.toInt() / 2)
        } else {
            0
        }
        listState.animateScrollToItem(currentPage, offset)
    }
    LazyRow(
        state = listState,
        reverseLayout = rtlPaging,
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(pageCount, key = { page -> page }) { page ->
            var thumb by remember(page) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(page) {
                thumb = session.renderPreview(
                    page,
                    FILMSTRIP_PREVIEW_BASE_WIDTH_PX,
                    slot = PdfRenderSlot.P2
                )
            }
            val aspect = pdfPlaceholderSpec(
                page, session.pagePointsSizePeek(page)
            ).aspect
            Box(
                Modifier
                    .width(46.dp)
                    .aspectRatio(aspect)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF161822))
                    .then(
                        if (page == currentPage) {
                            Modifier.border(
                                2.dp, ReaderViolet,
                                RoundedCornerShape(4.dp)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSeek(page) },
                contentAlignment = Alignment.Center
            ) {
                val bitmap = thumb
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = session.displayLabelFor(page),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        "${page + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


private fun pdfCellRenderWidthPx(
    session: PdfSession,
    page: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    fitMode: PdfFitMode
): Int {
    // v1.5.12 (P4 / contract §3.6): PEEK only — a miss must
    // never fetch through the provider here (this runs inside
    // `remember` during cell composition; the fetching variant
    // could run a lock-contending openPage on the main
    // thread). On a miss the cell renders at the viewport
    // fallback and the size is requested asynchronously
    // (PdfSession.requestPageSizeAsync); the size-table
    // version key recomposes the cell once it lands.
    val points = session.pagePointsSizePeek(page) ?: return viewportWidthPx
    return PdfFitGeometry.renderSizePx(
        pageWidthPt = points.first,
        pageHeightPt = points.second,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        fitMode = fitMode,
        oversample = PDF_BASE_SCALE
    ).first / PDF_BASE_SCALE.toInt().coerceAtLeast(1)
}

/** v1.5.28: the width the paged cell's BASE SLOT renders at —
 * the fit width supersampled by [PDF_ZOOM_SUPERSAMPLE] for
 * the zoom layer (one texture, transformed during gestures —
 * the Komikku mirror, build-logs/V1528-ZOOM-MAP.md §3.3),
 * pixel-capped at the session's refine budget so a slot never
 * costs more than a refine render (2 GB perf pass: ~2.5 MP on
 * the MINIMAL tier — see [pdfBaseSlotWidthPx]). Shared by the cell and the
 * paged body's prefetch, exactly like [pdfCellRenderWidthPx],
 * so a prefetched page lands under the key the cell asks for.
 * On a size-table miss the fit-width fallback is returned
 * (the cell re-keys when the size lands). */
private fun pdfCellSlotWidthPx(
    session: PdfSession,
    page: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    fitMode: PdfFitMode
): Int {
    val displayWidth = pdfCellRenderWidthPx(
        session, page, viewportWidthPx, viewportHeightPx, fitMode
    )
    val points = session.pagePointsSizePeek(page) ?: return displayWidth
    // 2 GB perf pass (F2): the pixel cap is tier-aware — on
    // MINIMAL the slot is capped at ~2.5 MP (see
    // pdfBaseSlotWidthPx) so the budgeted prefetch window
    // keeps ≥2 resident pages; STANDARD/FULL compute exactly
    // the pre-pass numbers.
    return pdfBaseSlotWidthPx(
        displayWidthPx = displayWidth,
        pageWidthPt = points.first,
        pageHeightPt = points.second,
        tier = session.tier,
        refineMaxPixels = PdfSession.REFINE_MAX_PIXELS,
        supersample = PDF_ZOOM_SUPERSAMPLE
    )
}

/**
 * v1.5.10 (G2): a watch-only tap observer reporting BOTH
 * fractions — the nine-zone preset needs Y, which the shipped
 * x-only [pdfTapObserver] does not report. Same contract as
 * the shipped observer (initial pass, consumes nothing, clean
 * single taps only); used only when a non-default tap preset
 * is active, so the default road is byte-identical.
 */
private fun Modifier.pdfXYTapObserver(
    onTap: (xFraction: Float, yFraction: Float) -> Unit
): Modifier = pointerInput(onTap) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        val downPosition = down.position
        val downTime = down.uptimeMillis
        var sawSecondPointer = false
        var moved = false
        var upTime = 0L
        var upPosition = downPosition
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) sawSecondPointer = true
            for (change in event.changes) {
                if (kotlin.math.abs(change.position.x - downPosition.x) >
                    viewConfiguration.touchSlop ||
                    kotlin.math.abs(change.position.y - downPosition.y) >
                    viewConfiguration.touchSlop
                ) {
                    moved = true
                }
                if (!change.pressed) {
                    upTime = change.uptimeMillis
                    upPosition = change.position
                }
            }
            if (event.changes.all { !it.pressed }) break
        }
        val isCleanTap = !sawSecondPointer && !moved &&
            upTime > 0L && (upTime - downTime) <= 400L
        if (isCleanTap && size.width > 0 && size.height > 0) {
            onTap(
                (upPosition.x / size.width).coerceIn(0f, 1f),
                (upPosition.y / size.height).coerceIn(0f, 1f)
            )
        }
    }
}

@Composable
private fun PdfPagedBody(
    handlers: PdfPagesHandlers,
    session: PdfSession,
    initialPage: Int,
    currentPage: Int,
    settings: PdfDocSettings,
    autoScroll: AutoScrollController,
    onToggleControls: () -> Unit,
    tapPreset: TapLayoutPreset = TapLayoutPreset.MOON_DEFAULT,
    // MR-2 (A12): the cells report the filter they
    // resolved smart contrast to, per page (see PdfDocumentReader).
    onResolvedFilter: (Int, PdfPageFilter) -> Unit = { _, _ -> }
) {
    val pageCount = session.sectionCount.coerceAtLeast(1)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // F-5: render geometry comes from the FULL window size —
    // never from the chrome-dependent content box, so a chrome
    // toggle can never change a render size (or cache key).
    val (windowWidthPx, windowHeightPx) = pdfRenderViewportPx(
        windowWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt(),
        windowHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()
    )
    // H9: the spread lives only in landscape.
    val spreadActive = settings.spread &&
        configuration.screenWidthDp > configuration.screenHeightDp
    val unitCount = PdfSpreadMath.unitCount(pageCount, spreadActive)

    // A-2: the page a NON-sequential jump landed on (scrubber /
    // TOC / go-to) — its cell leads with the preview pass.
    var boostPage by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize()) {
        key(spreadActive) {
            val pagerState = rememberPagerState(
                initialPage = PdfSpreadMath.unitForPage(
                    initialPage.coerceIn(0, pageCount - 1), spreadActive
                ).coerceIn(0, (unitCount - 1).coerceAtLeast(0))
            ) { unitCount }

            // v1.5.12 (P3 / contract §2.4): zoom state is
            // reported PER PAGE by the cells (page → (isZoomed,
            // reset handle)), and the pager's scroll-enable is
            // DERIVED at composition from the SETTLED unit's
            // pages only — pdfPagerUserScrollEnabled. The
            // shipped single sticky boolean + identity-matched
            // reset handle stranded: every cell (including the
            // beyond-viewport neighbour) wrote it, the
            // neighbour's report overwrote the stored handle,
            // and the settled page's own un-zoom could then
            // never clear the latch, so a book opened at a
            // persisted zoom > 1 could never be swiped again.
            // Derived-from-settled has no latch to strand: a
            // neighbour's zoom never counts, a page change
            // moves the old page out of the settled set, and
            // the settled page's own reset re-enables turning
            // the moment its zoom reaches fit. (F-1's rule
            // stands: while the SETTLED page is zoomed the
            // pager locks its swipe — the transform owns the
            // drag — and edge taps / jumps reset it first.)
            var zoomReports by remember {
                mutableStateOf<Map<Int, Pair<Boolean, () -> Unit>>>(emptyMap())
            }
            var settledUnit by remember {
                mutableStateOf(pagerState.currentPage)
            }
            val settledPagesNow = PdfSpreadMath.pagesForUnit(
                settledUnit, pageCount, spreadActive
            )
            val pagerScrollEnabled = pdfPagerUserScrollEnabled(
                settledPages = settledPagesNow,
                zoomedPages = zoomReports.filterValues { it.first }.keys
            )
            val activeZoomed = !pagerScrollEnabled

            LaunchedEffect(pagerState, session) {
                var lastSettledUnit = -1
                snapshotFlow {
                    Triple(
                        pagerState.currentPage,
                        pagerState.targetPage,
                        pagerState.isScrollInProgress
                    )
                }.collect { (unit, targetUnit, scrolling) ->
                    val settledPages = PdfSpreadMath
                        .pagesForUnit(unit, pageCount, spreadActive)
                    val firstPage = settledPages.firstOrNull() ?: 0
                    // A-1: THE pager publication — settled
                    // page(s), the in-flight targetPage and the
                    // scrolling flag, at the same point the
                    // position is reported. The session bumps
                    // its generation on settled/target change.
                    session.publishPagerState(
                        settledPage = firstPage,
                        settledSecondPage = settledPages.getOrNull(1),
                        targetPage = PdfSpreadMath
                            .pagesForUnit(targetUnit, pageCount, spreadActive)
                            .firstOrNull() ?: firstPage,
                        scrolling = scrolling
                    )
                    if (unit != lastSettledUnit) {
                        lastSettledUnit = unit
                        // v1.5.12 (P3): the settled unit the
                        // zoom latch derives from.
                        settledUnit = unit
                        handlers.onPosition(firstPage, 0f)
                    }
                }
            }
            LaunchedEffect(session, handlers, spreadActive) {
                handlers.jumps.collect { jump ->
                    // F-1: a jump first resets a live transform —
                    // it never turns a displaced page. v1.5.12
                    // (P3): the reset targets the SETTLED unit's
                    // pages' own handles (per-page reports), not
                    // one identity-matched handle another cell
                    // may have overwritten.
                    if (activeZoomed) {
                        PdfSpreadMath.pagesForUnit(
                            settledUnit, pageCount, spreadActive
                        ).forEach { settledPage ->
                            zoomReports[settledPage]
                                ?.takeIf { it.first }?.second?.invoke()
                        }
                    }
                    // A-2: a NON-sequential landing (distance
                    // > 1) earns the preview pass on its cell.
                    // v1.5.11 (step 3, D6): the landing protocol
                    // is now strict — a jump FLUSHES every queued
                    // prefetch first, and the landing cell pays
                    // ONE deterministic base render (no preview:
                    // after the flush the slot is as free as it
                    // can be, so the preview could only delay
                    // the sharp page or waste the slot behind
                    // it, in random order — the shipped bug).
                    // boostPage now marks a JUMP LANDING (base-
                    // only at P0), not a preview request.
                    val settledFirst = PdfSpreadMath
                        .pagesForUnit(pagerState.currentPage, pageCount, spreadActive)
                        .firstOrNull() ?: 0
                    val isJump = kotlin.math.abs(jump.index - settledFirst) > 1
                    if (isJump) {
                        session.flushQueuedPrefetch()
                    }
                    boostPage = if (isJump) {
                        jump.index
                    } else {
                        null
                    }
                    val target = PdfSpreadMath.unitForPage(
                        jump.index.coerceIn(0, pageCount - 1), spreadActive
                    )
                    if (target != pagerState.currentPage) {
                        pagerState.scrollToPage(target)
                    }
                }
            }
            // F-3/A-1/A-4: prefetch at P2 — the tier's full-res
            // window (+2/−1, MINIMAL +1/−1) plus the preview
            // ring (+4/−2 at 0.35×). Enqueue order is the
            // REVERSE of service order: the scheduler serves
            // the pending P2 set LIFO (A-1 rider), so tickets
            // are launched preview-ring first and +1 last —
            // service lands +1, −1, +2, then the ring.
            // v1.5.11 (step 1, D1): tickets are NO LONGER
            // stamped with this settle's generation — P2
            // validity is distance-based, judged at grant time
            // against the live settled page, so this set
            // survives consecutive turns instead of being
            // conflated stale by the next gesture. (step 2,
            // D2): the loop bounds read the session's
            // BUDGETED window (prefetchForward/Backward),
            // derived from the real render width's page bytes.
            val fitModeState = rememberUpdatedState(settings.fitMode)
            LaunchedEffect(pagerState, session) {
                // v1.5.12 (P5 / contract §3.5): prefetch windows
                // are SINGLE-FLIGHT per settle and STRUCTURED —
                // the window job lives in THIS effect's scope
                // (cancelled with the effect), and a new settle
                // cancels the previous settle's window before
                // launching its own. The shipped code launched
                // every window from a rememberCoroutineScope that
                // outlived the settle, so consecutive settles
                // stacked duplicate full windows on the queue.
                var prefetchJob: kotlinx.coroutines.Job? = null
                snapshotFlow { pagerState.currentPage }.collect { unit ->
                    prefetchJob?.cancel()
                    prefetchJob = launch {
                    // v1.5.37 (F2 — V1535-PDF-REGRESSION-
                    // FORENSICS §6): the jump protocol,
                    // extended to turns. Drop whatever
                    // speculative P2 the PREVIOUS settle
                    // queued before this settle's burst
                    // files its own set, so the burst's
                    // deliberate service order (+1 last
                    // under LIFO) is the only speculation
                    // ahead of the reader and the turned-to
                    // page's wait stays bounded by
                    // (in-flight render + its own render)
                    // through fast turn chains.
                    session.flushQueuedPrefetch()
                    val pages = PdfSpreadMath.pagesForUnit(unit, pageCount, spreadActive)
                    val anchors = pages.ifEmpty { listOf(0) }
                    val cellWidthPx = if (spreadActive) {
                        windowWidthPx / 2
                    } else {
                        windowWidthPx
                    }
                    for (anchor in anchors) {
                        fun enqueue(target: Int, preview: Boolean) {
                            if (target !in 0 until pageCount) return
                            // v1.5.28: prefetches land at the
                            // cell's BASE SLOT width (the
                            // supersampled zoom texture), so a
                            // prefetched page fills the slot
                            // under the very key the cell asks
                            // for.
                            val widthPx = pdfCellSlotWidthPx(
                                session, target, cellWidthPx, windowHeightPx,
                                fitModeState.value
                            )
                            launch {
                                if (preview) {
                                    session.renderPreview(
                                        target, widthPx,
                                        slot = PdfRenderSlot.P2
                                    )
                                } else {
                                    session.renderPage(
                                        target, widthPx, PdfPageFilter.NONE,
                                        PDF_BASE_SCALE,
                                        slot = PdfRenderSlot.P2
                                    )
                                }
                            }
                        }
                        // Preview ring (served last under LIFO).
                        for (delta in PdfSession.PREVIEW_RING_FORWARD downTo
                            (session.prefetchForward + 1)) {
                            enqueue(anchor + delta, preview = true)
                        }
                        enqueue(anchor - PdfSession.PREVIEW_RING_BACKWARD, preview = true)
                        // Full-res window, reverse service order.
                        for (delta in session.prefetchForward downTo 1) {
                            if (delta != 1) enqueue(anchor + delta, preview = false)
                        }
                        for (delta in session.prefetchBackward downTo 1) {
                            if (delta != 1) enqueue(anchor - delta, preview = false)
                        }
                        enqueue(anchor - 1, preview = false)
                        enqueue(anchor + 1, preview = false)
                    }
                    }
                }
            }
            // H7: the paged auto-scroll surface — page-advance.
            val currentPageState = rememberUpdatedState(currentPage)
            DisposableEffect(session, settings.autoScrollSpeed) {
                val surface = AutoScrollEngine.Surface {
                    val next = currentPageState.value + 1
                    if (next < pageCount) {
                        handlers.onGoToPage(next)
                        true
                    } else {
                        false
                    }
                }
                autoScroll.register(
                    scope = scope,
                    surface = surface,
                    intervalMs = AutoScrollEngine.intervalFor(
                        AutoScrollEngine.PAGE_INTERVAL_MS,
                        settings.autoScrollSpeed
                    )
                )
                onDispose { autoScroll.unregister(surface) }
            }

            HorizontalPager(
                state = pagerState,
                // v1.7 MR-2 (P5): RTL paging — the pager
                // lays its units out right-to-left, so the
                // next page sits to the LEFT and a rightward
                // drag brings it in (the RTL book's own
                // direction). pagerState stays logical:
                // publications, jumps and prefetch read the
                // same page indices either way.
                reverseLayout = settings.rtlPaging,
                // v1.5.12 (P3): derived from the settled unit's
                // pages' ACTUAL current zoom (see zoomReports
                // above) — never a sticky latch.
                userScrollEnabled = pagerScrollEnabled,
                // v1.5.37 (turn rendering, REVERT of the
                // v1.5.35 widening — V1535-PDF-REGRESSION-
                // FORENSICS F1): ONE page of look-ahead
                // composition, the v1.5.34 demand. On this
                // scheduler a composed cell is a render
                // ISSUER, not a passive placeholder: the +2
                // cell composed mid-turn and filed
                // speculative requests at composition time,
                // every pager publication restarted every
                // uncached composed cell's render effect
                // (five cells churning instead of three,
                // each restart re-filing its ticket at the
                // back of its class), and an in-flight
                // render is never cancelled, so speculative
                // grants taxed the landing page at the
                // supersampled slot width. Worse, once
                // page sizes land, the session's budgeted
                // P2 window collapses to +0/-1 at
                // slot-width page bytes, so the +2 page's
                // base request is stale at enqueue — the
                // wider look-ahead paid the churn and
                // could never collect its benefit. The
                // settle-prefetch burst above already
                // speculates in deliberate service order;
                // composition stays at ±1.
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { unit ->
                val pages = PdfSpreadMath.pagesForUnit(unit, pageCount, spreadActive)
                Row(Modifier.fillMaxSize()) {
                    // MR-2 (P5): in RTL paging a spread's
                    // pair also reads right-to-left — the
                    // earlier page stands on the right.
                    val orderedPages = if (settings.rtlPaging) {
                        pages.reversed()
                    } else {
                        pages
                    }
                    orderedPages.forEach { page ->
                        PdfPageCell(
                            session = session,
                            page = page,
                            viewportWidthPx = if (spreadActive) {
                                windowWidthPx / 2
                            } else {
                                windowWidthPx
                            },
                            viewportHeightPx = windowHeightPx,
                            settings = settings,
                            slot = when (unit) {
                                pagerState.currentPage -> PdfRenderSlot.P0
                                pagerState.targetPage -> PdfRenderSlot.P1
                                else -> PdfRenderSlot.P2
                            },
                            landingBoost = page == boostPage,
                            onMagnificationPreference = { zoom ->
                                handlers.onUpdateSettings { it.copy(zoom = zoom) }
                            },
                            onZoomActivity = { reportedPage, zoomed, reset ->
                                // v1.5.12 (P3): per-page report —
                                // the body derives the pager's
                                // scroll-enable from the SETTLED
                                // unit's pages only, so this
                                // neighbour-inclusive bookkeeping
                                // can no longer strand the latch.
                                zoomReports = zoomReports +
                                    (reportedPage to (zoomed to reset))
                            },
                            onToggleControls = onToggleControls,
                            tapPreset = tapPreset,
                            onResolvedFilter = onResolvedFilter,
                            onTurnPage = { direction ->
                                val target = (unit + direction)
                                    .coerceIn(0, (unitCount - 1).coerceAtLeast(0))
                                if (target != unit) {
                                    scope.launch { pagerState.animateScrollToPage(target) }
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/** One page (or one half of a spread): render + transient
 * zoom/pan + taps, in the paged model. Extracted so a spread
 * cell is the same page as a single — same cache, same gestures.
 *
 * v1.5.9 (F-1): zoom/pan are THIS cell's own [PdfPageZoomState]
 * (`remember(page)`) — the preference is read only by this
 * page's own double-tap/pinch and written only by this page's
 * own pinch. (F-6): the bitmap is the RAW render — the display
 * filter is a draw-time [ColorFilter].
 *
 * v1.5.10 (G1, Q5 → B): the cell's state now OPENS at the
 * book's remembered zoom ([PdfPageZoomState.withRememberedZoom]
 * over the persisted preference, read at this page's first
 * composition) and binds its clamp geometry once the bitmap
 * and cell size are known, so a turn lands zoomed but never
 * on empty black. A new book's preference is 1f = fit,
 * unchanged.
 *
 * v1.5.9 (A-2 AMENDED): the base render is 1.0× fit. Two
 * passes ride on it: the 0.35× PREVIEW leads on a cold
 * visible-page miss and the base crossfades over it
 * (~120 ms); the REFINE pass renders the visible clip at
 * reading resolution once a zoom >1.25 settles (~150 ms;
 * immediately on double-tap), and is evicted when the zoom
 * leaves or the cell dies.
 *
 * v1.5.28 (the Komikku rebuild, build-logs/V1528-ZOOM-MAP.md):
 * the base is now the supersampled SLOT (pdfCellSlotWidthPx);
 * the preview leads only until the base lands and is then
 * replaced atomically (the crossfade is deleted); the refine
 * SWAPS the slot's image under capture-and-compare instead
 * of overlaying it; double-tap is an animated focal toggle;
 * and pinch enters from fit through the self-gating
 * transform loop.
 *
 * v1.5.11 (step 3, D6): the preview's lead is now the strict
 * landing protocol in the cell's render effect — a jump
 * landing pays ONE base render only (the body flushed the
 * queued prefetches first); a preview runs only for a cold
 * miss while the render slot is busy, awaited strictly
 * before the base is enqueued. */
@Composable
private fun PdfPageCell(
    session: PdfSession,
    page: Int,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    settings: PdfDocSettings,
    slot: PdfRenderSlot,
    landingBoost: Boolean,
    onMagnificationPreference: (Float) -> Unit,
    /** v1.5.12 (P3): per-page zoom report — (page, isZoomed,
     * reset handle). The body derives the pager's scroll state
     * from the settled unit's reports only. */
    onZoomActivity: (Int, Boolean, () -> Unit) -> Unit,
    onToggleControls: () -> Unit,
    onTurnPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tapPreset: TapLayoutPreset = TapLayoutPreset.MOON_DEFAULT,
    /** MR-2 (A12): reports the filter this cell resolved
     * smart contrast to for its page (the stored filter itself when the mode is off, or for every
     * filter) — the reader's night toggle reads the
     * current page's value. */
    onResolvedFilter: (Int, PdfPageFilter) -> Unit = { _, _ -> }
) {
    val cellScope = rememberCoroutineScope()
    // F-1 + G1: per-page view state. remember(page) — a cell
    // for a different page is a different state; it opens at
    // the remembered zoom (Q5 → B), which for a new book is
    // fit. The preference value is read at first composition
    // only, so this page's own pinch writes never recreate it.
    val rememberedZoom = rememberUpdatedState(settings.zoom)
    val zoomState = remember(page) {
        PdfPageZoomState.withRememberedZoom(rememberedZoom.value)
    }
    val zoomStateRef = rememberUpdatedState(zoomState)
    // v1.5.28: the double-tap glide's job — a live glide is
    // cancelled by a new gesture, a new glide, or a reset.
    var zoomAnimation by remember(page) {
        mutableStateOf<kotlinx.coroutines.Job?>(null)
    }
    // v1.5.29: GLIDE PURITY — true while a double-tap (or
    // settle-snap) glide is running. The settle effect and
    // refineNow refuse to request or install a refine while
    // it is set, and the draw never shows a refine under it:
    // the only image drawn during a glide is the slot bitmap
    // under the animated transform. v1.5.28 had no explicit
    // suspension — only the settle effect's 150 ms restart
    // delay, which a >150 ms frame gap on a loaded device
    // defeats, letting a refine be captured mid-glide. The
    // refine resumes when the flag clears at the glide's
    // rest (the settle effect restarts on the flag).
    var zoomGlideActive by remember(page) { mutableStateOf(false) }
    fun cancelZoomAnimation() {
        zoomAnimation?.cancel()
        zoomAnimation = null
    }
    /** v1.5.28: the Komikku double-tap glide — animate ONLY
     * the layer transform (zoom + pan) to the target over
     * [PDF_DOUBLE_TAP_ANIM_MS]; no bitmap work rides the
     * motion path, and the settle refine fires once the
     * transform comes to rest (the settle effect below). */
    fun animateZoomTo(targetZoom: Float, targetPan: Offset) {
        cancelZoomAnimation()
        val startZoom = zoomState.zoom
        val startPan = zoomState.pan
        if (startZoom == targetZoom && startPan == targetPan) return
        zoomGlideActive = true
        zoomAnimation = cellScope.launch {
            try {
                val progress = Animatable(0f)
                progress.animateTo(1f, tween(PDF_DOUBLE_TAP_ANIM_MS)) {
                    zoomState.setZoomPan(
                        startZoom + (targetZoom - startZoom) * value,
                        startPan + (targetPan - startPan) * value
                    )
                }
            } finally {
                zoomGlideActive = false
            }
        }
    }
    // v1.5.29: TAP ARBITRATION — the watch-only tap
    // observers report EVERY clean tap, so a double-tap used
    // to fire its two single-tap actions first (chrome
    // toggles, page turns — the owner's frame-by-frame:
    // "reader setting opens, duplicate pages, lag, and then
    // zoom"). Single-tap actions are now CONFIRMED singles
    // (the Komikku/SSIV onSingleTapConfirmed pattern): a
    // reported tap's action defers by the platform
    // double-tap window (ViewConfiguration
    // .doubleTapTimeoutMillis); the double-tap handler
    // cancels the pending action outright, so a double-tap
    // produces ONLY the zoom, fired on the second tap's
    // release with no added wait. A claimed transform
    // gesture or a reset cancels a pending single too.
    val viewConfiguration = LocalViewConfiguration.current
    var pendingSingleTap by remember(page) {
        mutableStateOf<kotlinx.coroutines.Job?>(null)
    }
    fun cancelPendingSingleTap() {
        pendingSingleTap?.cancel()
        pendingSingleTap = null
    }
    fun fireSingleTapConfirmed(action: () -> Unit) {
        pendingSingleTap?.cancel()
        pendingSingleTap = cellScope.launch {
            delay(viewConfiguration.doubleTapTimeoutMillis)
            action()
        }
    }
    val resetHandle = remember(page) {
        {
            cancelZoomAnimation()
            cancelPendingSingleTap()
            zoomStateRef.value.reset()
        }
    }
    // v1.5.28: the transform loop reports its own activity, so
    // the pager's lock engages synchronously with the gesture
    // that causes it (detector-driven — the Komikku weld)
    // instead of only a frame later through the zoom value.
    var gestureActive by remember(page) { mutableStateOf(false) }
    LaunchedEffect(zoomState, zoomState.zoom, gestureActive) {
        onZoomActivity(
            page, zoomState.isZoomed || gestureActive, resetHandle
        )
    }
    DisposableEffect(page) {
        onDispose {
            cancelPendingSingleTap()
            onZoomActivity(page, false, resetHandle)
            // A-2: refine entries die with the cell.
            session.evictRefinements(page)
        }
    }
    // v1.5.29: the double-tap no longer reads the
    // magnification preference (fixed 2× target — see the
    // onDoubleTap handler below), so the cell's
    // rememberUpdatedState mirror of settings.zoom for it
    // is gone; settings.zoom still reaches the cell through
    // [rememberedZoom] at state construction (open zoom).

    // v1.5.12 (P1 + P4): the two off-composition signals this
    // cell keys on. The session's PUBLISHED pager state: when
    // the publication catches up with the turn that composed
    // this cell, the render effect below re-runs and re-issues
    // a request a born-stale drop may have killed (and re-reads
    // the memory cache, so a prefetch that landed meanwhile
    // fills the cell). The size-table version: when this page's
    // size lands from its off-composition fetch, the render
    // width + placeholder recompute from the peeked cache.
    val pagerPublication by session.pagerPublication
        .collectAsStateWithLifecycle()
    val sizeTableVersion by session.sizeTableVersion
        .collectAsStateWithLifecycle()
    // P4: on a size-cache miss, fill this page's size OFF the
    // composition path (the session's IO strip scope); the
    // version bump above brings it in when it lands.
    LaunchedEffect(page, sizeTableVersion) {
        if (session.pagePointsSizePeek(page) == null) {
            session.requestPageSizeAsync(page)
        }
    }

    // F-4: page geometry from the cached size table only.
    // v1.5.12 (P4): keyed on the size-table version and reading
    // through the PEEK (see pdfCellRenderWidthPx) — composition
    // never opens a page.
    // v1.5.28: the cell renders its base at the SLOT width —
    // the supersampled zoom texture (see pdfCellSlotWidthPx).
    val renderWidthPx = remember(
        page, viewportWidthPx, viewportHeightPx, settings.fitMode, sizeTableVersion
    ) {
        pdfCellSlotWidthPx(session, page, viewportWidthPx, viewportHeightPx, settings.fitMode)
    }
    var previewBitmap by remember(page, renderWidthPx) { mutableStateOf<Bitmap?>(null) }
    var bitmap by remember(page, renderWidthPx) { mutableStateOf<Bitmap?>(null) }
    // v1.5.12 (P1): keyed ALSO on the session's published
    // pager state — the publication catching up re-runs this
    // effect and re-issues the page's request at top priority
    // if its first ticket was dropped as born-stale (the
    // session's own retry loop is the first line of defence;
    // this key is the cell-level guarantee the spec asks for,
    // and it is also what lets a later prefetch cache-hit fill
    // the cell: the re-run's renderPage reads the memory cache
    // before any ticket is issued).
    LaunchedEffect(page, renderWidthPx, slot, landingBoost, pagerPublication) {
        // v1.5.11 (step 3, research D6): the STRICT landing
        // protocol. The shipped cell launched the 0.35× preview
        // CONCURRENTLY with the base request — two serial
        // renders of the same page in non-deterministic order:
        // either the sharp page waited behind its own preview
        // (plus any unpreemptable prefetch), or the blur
        // arrived after the sharp page and wasted the slot.
        // Now the order is deterministic and the landing pays
        // the fewest renders that can serve it:
        //  - JUMP LANDING ([landingBoost], P2 already flushed
        //    by the body): base ONLY — one render, at P0.
        //  - Cold visible miss with an IDLE slot: base only —
        //    it can start immediately, a preview could only
        //    delay it.
        //  - Cold visible miss with a BUSY slot: the preview
        //    is awaited FIRST (strictly before the base is
        //    even enqueued), so something readable lands as
        //    early as the queue allows and the base follows
        //    in a known order — never the reverse.
        if (!landingBoost && !session.isBaseCached(page, renderWidthPx) &&
            !session.isRenderSlotIdle()
        ) {
            previewBitmap = session.renderPreview(
                page, renderWidthPx, slot = slot
            )
        }
        val base = session.renderPage(
            page, renderWidthPx, PdfPageFilter.NONE, PDF_BASE_SCALE,
            slot = slot
        )
        // v1.5.28: the base replaces the preview in the slot
        // ATOMICALLY — the ~120 ms crossfade that used to run
        // here (a semi-transparent zoomed base drawn over an
        // unscaled preview — the G3 ghost window of the V1526
        // forensics) is deleted. The slot never holds two
        // scales at once.
        bitmap = base
    }
    // v1.5.12 (P4): the placeholder spec peeks the size cache
    // (composition opens nothing); it recomputes when the size
    // lands because this composition already reads
    // sizeTableVersion (the renderWidthPx remember key above).
    val placeholder = pdfPlaceholderSpec(page, session.pagePointsSizePeek(page))
    // MR-2 (A12): the filter this page is DRAWN with. Smart
    // contrast resolves here, at draw time, per page — from
    // the reader's dark state (the applied theme's background)
    // and the displayed bitmap's sampled luminance; the
    // renders and the render cache never learn the answer
    // (filters are draw-time colour filters, never keyed).
    // Every other filter resolves to itself, so the shipped
    // behaviour is bit-identical with the mode off.
    val themeBackground = MaterialTheme.colorScheme.background
    val readerDark = 0.299f * themeBackground.red +
        0.587f * themeBackground.green +
        0.114f * themeBackground.blue < 0.5f
    val resolvedFilter = settings.filter.resolvedForDisplay(
        settings.autoContrast, readerDark, bitmap ?: previewBitmap
    )
    LaunchedEffect(resolvedFilter) {
        onResolvedFilter(page, resolvedFilter)
    }
    val surround = resolvedFilter.surroundArgb()
    // F-6: the display filter as a draw-time colour filter over
    // the RAW cached render.
    val drawFilter = remember(resolvedFilter) {
        resolvedFilter.colorMatrix()?.let { matrix ->
            ColorFilter.colorMatrix(
                androidx.compose.ui.graphics.ColorMatrix(matrix.array.copyOf())
            )
        }
    }

    // ── A-2: zoom refine ─────────────────────────────────────
    // v1.5.28: the refine is now a SLOT SWAP under
    // capture-and-compare — it captures the (zoom, pan) it is
    // rendered for and may be installed/displayed ONLY while
    // the live (zoom, pan) still equals the capture exactly.
    // A stale sharp patch can never land (the G2 doubling of
    // the V1526 forensics), and any movement reverts the slot
    // to the supersampled base until the next settle.
    var cellSizePx by remember { mutableStateOf(IntSize.Zero) }
    var refinedBitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    var refinedClip by remember(page) { mutableStateOf<NormalizedRect?>(null) }
    var refinedForZoom by remember(page) { mutableStateOf<Float?>(null) }
    var refinedForPan by remember(page) { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    suspend fun refineNow() {
        val base = bitmap ?: return
        // v1.5.29: glide purity — a refine is never even
        // REQUESTED while a glide runs (the settle effect
        // below also refuses; this guard covers any other
        // road in).
        if (zoomGlideActive) return
        // MR-2 (A13): refine rects are computed in
        // UNTRIMMED page fractions — under an edge trim
        // they would land displaced, so the refine gate
        // stays closed while trim ≠ 0 (the supersampled
        // slot alone carries the zoom, exactly as it does
        // below the refine threshold).
        if (settings.edgeTrim > 0f) return
        val state = zoomStateRef.value
        if (state.zoom <= PdfSession.REFINE_ZOOM_THRESHOLD) return
        if (cellSizePx == IntSize.Zero) return
        val (dispW, dispH) = pdfDisplayedSizePx(
            base.width, base.height,
            cellSizePx.width, cellSizePx.height, settings.fitMode
        )
        // v1.5.28: the supersampled slot may already carry
        // this zoom's resolution — refine only BEYOND the
        // slot's own scale (bitmap px per displayed px).
        val slotScale = base.width / dispW.coerceAtLeast(1f)
        if (state.zoom <= slotScale) {
            refinedBitmap = null
            refinedClip = null
            refinedForZoom = null
            refinedForPan = null
            return
        }
        val capturedZoom = state.zoom
        val capturedPan = state.pan
        val visible = pdfVisibleClip(
            zoom = capturedZoom,
            panPx = capturedPan,
            cellWidthPx = cellSizePx.width,
            cellHeightPx = cellSizePx.height,
            displayedWidthPx = dispW,
            displayedHeightPx = dispH
        )
        // The clip is requested slightly GROWN (clamped to
        // the page) so the swapped-in image over-covers the
        // visible region — no hairline seam at its edges.
        val clip = NormalizedRect(
            (visible.left - PDF_REFINE_CLIP_MARGIN).coerceAtLeast(0f),
            (visible.top - PDF_REFINE_CLIP_MARGIN).coerceAtLeast(0f),
            (visible.right + PDF_REFINE_CLIP_MARGIN).coerceAtMost(1f),
            (visible.bottom + PDF_REFINE_CLIP_MARGIN).coerceAtMost(1f)
        )
        val clipWidthPx = (dispW * clip.width * capturedZoom)
            .toInt().coerceAtLeast(1)
        val refined = session.renderRefinedClip(
            page,
            PdfPageClip(clip.left, clip.top, clip.right, clip.bottom),
            clipWidthPx
        ) ?: return
        // Capture-and-compare: install ONLY if the view is
        // still exactly where this refine was captured for.
        val now = zoomStateRef.value
        if (now.zoom == capturedZoom && now.pan == capturedPan) {
            refinedBitmap = refined
            refinedClip = clip
            refinedForZoom = capturedZoom
            refinedForPan = capturedPan
        }
    }
    // The settle path: this effect restarts on every zoom/pan
    // change, so its 150 ms delay IS the settle timer — a
    // moving zoom keeps restarting it and no refine fires
    // until the gesture rests.
    // v1.5.29: the glide flag is a key too — while a
    // double-tap glide runs the effect refuses outright (the
    // 150 ms restart delay alone was not a suspension: a
    // frame gap longer than the delay let a refine be
    // captured mid-glide); when the flag clears at the
    // glide's rest, the effect restarts and the refine
    // resumes under capture-and-compare, exactly as a
    // pinch settle does.
    LaunchedEffect(zoomState.zoom, zoomState.pan, zoomGlideActive) {
        if (zoomGlideActive) return@LaunchedEffect
        // MR-2 (A13): the trim gate (see refineNow) is a
        // key of this effect too — raising the trim with a
        // refine installed evicts it below instead of
        // letting it ride displaced.
        if (zoomState.zoom > PdfSession.REFINE_ZOOM_THRESHOLD &&
            settings.edgeTrim == 0f
        ) {
            delay(150)
            refineNow()
        } else if (!zoomState.isZoomed || settings.edgeTrim > 0f) {
            // Zoom left: the refine is evicted (A-2).
            refinedBitmap = null
            refinedClip = null
            refinedForZoom = null
            refinedForPan = null
            session.evictRefinements(page)
        }
    }

    // G1: bind the pan-clamp geometry once the base bitmap
    // and the cell size are both known; re-rendering or
    // resizing re-clamps the live pan (empty-space guard).
    LaunchedEffect(bitmap, cellSizePx) {
        val base = bitmap
        if (base != null && cellSizePx != IntSize.Zero) {
            val (dispW, dispH) = pdfDisplayedSizePx(
                base.width, base.height,
                cellSizePx.width, cellSizePx.height, settings.fitMode
            )
            zoomState.bindGeometry(
                dispW, dispH, cellSizePx.width, cellSizePx.height
            )
        }
    }

    // MR-2 (P5): in RTL paging the tap thirds trade
    // meaning — the left third turns FORWARD (the next
    // page stands to the left), the right third back.
    // Multiplied into the shipped deltas, so LTR is
    // bit-identical.
    val turnSign = if (settings.rtlPaging) -1 else 1
    Box(
        modifier
            .onSizeChanged { cellSizePx = it }
            .then(
                if (surround != null) {
                    Modifier.background(Color(surround))
                } else {
                    Modifier
                }
            )
            .then(
                when (tapPreset) {
                    // The default road: the shipped observer,
                    // unchanged (F-1 semantics).
                    TapLayoutPreset.MOON_DEFAULT ->
                        Modifier.pdfTapObserver { xFraction ->
                            // v1.5.29: a CONFIRMED single —
                            // the action runs only if no
                            // second tap lands inside the
                            // double-tap window (see the
                            // arbitration block above).
                            fireSingleTapConfirmed {
                            when {
                                // F-1: while zoomed, an edge tap
                                // first resets the transform —
                                // it never turns a displaced page.
                                zoomState.isZoomed &&
                                    (xFraction < 0.33f || xFraction > 0.67f) ->
                                    zoomState.reset()
                                // Edge thirds turn pages while
                                // unzoomed; the centre toggles
                                // the chrome. (MR-2: turnSign
                                // flips the thirds under RTL
                                // paging.)
                                xFraction < 0.33f -> onTurnPage(-turnSign)
                                xFraction > 0.67f -> onTurnPage(turnSign)
                                else -> onToggleControls()
                            }
                            }
                        }
                    // G2: a non-default preset routes through
                    // the XY observer + the shared preset map;
                    // a tap while zoomed resets first (the F-1
                    // rule, generalised: no displaced turns).
                    else -> Modifier.pdfXYTapObserver { x, y ->
                        // v1.5.29: a confirmed single, as
                        // the default road above.
                        fireSingleTapConfirmed {
                        when (tapActionFor(tapPreset, x, y)) {
                            TapZoneAction.TURN_PREV ->
                                if (zoomState.isZoomed) {
                                    zoomState.reset()
                                } else {
                                    onTurnPage(-turnSign)
                                }
                            TapZoneAction.TURN_NEXT ->
                                if (zoomState.isZoomed) {
                                    zoomState.reset()
                                } else {
                                    onTurnPage(turnSign)
                                }
                            TapZoneAction.TOGGLE_CHROME -> onToggleControls()
                            TapZoneAction.NONE -> Unit
                        }
                        }
                    }
                }
            )
            .pointerInput(page) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        // v1.5.29: the double-tap is a FIXED
                        // two-state toggle (Komikku semantics),
                        // and it is the ONLY thing a double-tap
                        // produces: the pending confirmed
                        // single is cancelled first, and the
                        // zoom fires on this second tap's
                        // release with no added wait.
                        //
                        // The toggle: zoomed → glide back to
                        // fit; at fit → glide to EXACTLY
                        // [PdfPageZoomState.DEFAULT_DOUBLE_TAP_ZOOM]
                        // (2×, the donor's double-tap scale)
                        // with the TAPPED point carried to the
                        // cell centre (pdfTapCentredPan).
                        //
                        // v1.5.28 read the target from the
                        // magnification preference — the very
                        // value pinch rewrites on every
                        // change — so the double-tap's in-size
                        // was whatever pinch last left ("double
                        // tap zoom changing sizes", the
                        // owner's verdict). The target now
                        // NEVER comes from the preference, and
                        // the preference is still not written
                        // (the F-1 rule: resetting must not
                        // erase the reader's chosen
                        // magnification). Books still OPEN at
                        // the remembered zoom; pinch still
                        // persists it. Double-tap owns neither.
                        cancelPendingSingleTap()
                        if (zoomState.isZoomed) {
                            animateZoomTo(1f, Offset.Zero)
                        } else if (cellSizePx != IntSize.Zero) {
                            val target =
                                PdfPageZoomState.DEFAULT_DOUBLE_TAP_ZOOM
                            animateZoomTo(
                                target,
                                pdfTapCentredPan(
                                    tap,
                                    cellSizePx.width,
                                    cellSizePx.height,
                                    target
                                )
                            )
                        }
                    }
                )
            }
            .pointerInput(page) {
                // v1.5.28: the transform loop — always
                // installed, SELF-GATING (the v1.5.12 P3
                // ownership rule enforced inside the loop
                // instead of by installing/uninstalling the
                // detector). It consumes NOTHING until the
                // gesture is a pinch (a second pointer lands)
                // or the page is already zoomed: pager drags
                // at fit are never starved (P3's bug cannot
                // return), pinch can ENTER from fit (new), and
                // once a gesture is claimed, the consumed
                // changes never reach the pager — the lock is
                // physical from the gesture's first frame
                // (the detector-driven weld; the effect-driven
                // report alone lagged a frame — G5). The
                // transform math is the shipped pairing
                // (calculateZoom/calculatePan are exactly what
                // detectTransformGestures computes), and a
                // live double-tap glide is cancelled the
                // moment a real gesture claims the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var transformMode = zoomStateRef.value.isZoomed
                    // v1.5.35 (turn rendering): the claim is
                    // reported to the pager DIRECTLY from the
                    // detector, not only through the
                    // gestureActive effect — the V1526 G5
                    // conviction was the lock flipping a frame
                    // or more after the gesture's first
                    // transform event, its opening frames both
                    // pager-translated and layer-scaled (the
                    // torn turn boundary). The effect remains
                    // as the value-driven path; this call is
                    // the same map write, made synchronously
                    // at the claim/release edges.
                    var claimed = false
                    if (transformMode) {
                        cancelZoomAnimation()
                        // v1.5.29: a claimed gesture also
                        // kills any pending confirmed single.
                        cancelPendingSingleTap()
                        gestureActive = true
                        claimed = true
                        onZoomActivity(page, true, resetHandle)
                    }
                    // v1.5.38 (Fix 1): the previous event's
                    // pressed-pointer set — the focal guard's
                    // memory (see the transform branch below).
                    var lastPressedIds:
                        Set<androidx.compose.ui.input.pointer.PointerId> =
                        emptySet()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (!transformMode && pressed >= 2) {
                                transformMode = true
                                cancelZoomAnimation()
                                cancelPendingSingleTap()
                                gestureActive = true
                                claimed = true
                                onZoomActivity(page, true, resetHandle)
                            }
                            if (transformMode) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                // v1.5.38 (Fix 1, anchor
                                // forensics M1): the focal
                                // inputs the paged loop never
                                // read — the pressed pointers'
                                // centroid, computed exactly as
                                // the surface loop computes its
                                // own, and the pointer-set guard:
                                // when the pressed set changes
                                // (a finger lands or lifts
                                // mid-gesture) the centroid
                                // teleports, so that event
                                // applies with the focal term
                                // suppressed rather than
                                // injecting a one-frame jump.
                                val pressedChanges =
                                    event.changes.filter { it.pressed }
                                val pressedIds = pressedChanges
                                    .map { it.id }.toSet()
                                val pointerSetChanged =
                                    pressedIds != lastPressedIds
                                lastPressedIds = pressedIds
                                if (zoomChange != 1f ||
                                    panChange != Offset.Zero
                                ) {
                                    val state = zoomStateRef.value
                                    if (state.isZoomed || zoomChange != 1f) {
                                        val before = state.zoom
                                        val centroid =
                                            if (pressedChanges.isEmpty()) {
                                                null
                                            } else {
                                                var cx = 0f
                                                var cy = 0f
                                                pressedChanges.forEach {
                                                    change ->
                                                    cx += change.position.x
                                                    cy += change.position.y
                                                }
                                                Offset(
                                                    cx / pressedChanges.size,
                                                    cy / pressedChanges.size
                                                )
                                            }
                                        val next = state.applyTransform(
                                            panChange, zoomChange,
                                            centroidPx = centroid,
                                            centrePx = Offset(
                                                cellSizePx.width / 2f,
                                                cellSizePx.height / 2f
                                            ),
                                            focal = !pointerSetChanged
                                        )
                                        // v1.5.29: a sub-fit
                                        // OVERSHOOT value is
                                        // transient — it is
                                        // never persisted as
                                        // the preference; the
                                        // gesture-end settle
                                        // (below) persists the
                                        // restored 1f instead.
                                        if (next != before && next >= 1f) {
                                            onMagnificationPreference(next)
                                        }
                                    }
                                }
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                        change.consume()
                                    }
                                }
                            }
                            if (pressed == 0) break
                        }
                    } finally {
                        // v1.5.29: THE PINCH REST FLOOR — if
                        // the gesture ended inside the
                        // sub-fit overshoot, settle the page
                        // back to exactly fit (the donor's
                        // minScale snap, through the same
                        // glide road as the double-tap) and
                        // persist the restored fit as the
                        // preference, exactly as the shipped
                        // hard clamp's landing on 1f did.
                        // No resting state smaller than fit
                        // can survive a gesture's end.
                        if (zoomStateRef.value.zoom < 1f) {
                            onMagnificationPreference(1f)
                            animateZoomTo(1f, Offset.Zero)
                        }
                        gestureActive = false
                        // v1.5.35: the release edge of the
                        // direct claim report (see the claim
                        // sites above) — the pager's lock
                        // re-derives from the resting zoom in
                        // the same dispatch, not a frame later.
                        if (claimed) {
                            onZoomActivity(
                                page,
                                zoomStateRef.value.isZoomed,
                                resetHandle
                            )
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        val preview = previewBitmap
        if (current == null && preview == null) {
            // F-8: the page-shaped placeholder.
            PdfPagePlaceholder(spec = placeholder)
        } else {
            // v1.5.28: THE SLOT — the zoom layer holds exactly
            // ONE image at a time: the refine (only while the
            // live zoom/pan still equals its capture — the
            // capture-and-compare display gate), else the
            // supersampled base, else (before the base lands)
            // the preview. Nothing is stacked, nothing
            // crossfades: the G2/G3 composites of the V1526
            // forensics have no arrangement left to live in.
            val refined = refinedBitmap
            val clipRect = refinedClip
            // v1.5.29: …and never under a running glide
            // (glide purity — the slot bitmap alone rides
            // the animated transform).
            val showRefined = current != null && refined != null &&
                clipRect != null && cellSizePx != IntSize.Zero &&
                !zoomGlideActive &&
                // MR-2 (A13): never under an edge trim —
                // refine rects are untrimmed fractions.
                settings.edgeTrim == 0f &&
                refinedForZoom == zoomState.zoom &&
                refinedForPan == zoomState.pan
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomState.zoom
                        scaleY = zoomState.zoom
                        translationX = zoomState.pan.x
                        translationY = zoomState.pan.y
                        clip = true
                    },
                contentAlignment = Alignment.Center
            ) {
                if (showRefined) {
                    val base = current!!
                    val refinedImage = refined!!
                    val refinedClipRect = clipRect!!
                    val (dispW, dispH) = pdfDisplayedSizePx(
                        base.width, base.height,
                        cellSizePx.width, cellSizePx.height, settings.fitMode
                    )
                    val originX = (cellSizePx.width - dispW) / 2f +
                        refinedClipRect.left * dispW
                    val originY = (cellSizePx.height - dispH) / 2f +
                        refinedClipRect.top * dispH
                    Image(
                        bitmap = refinedImage.asImageBitmap(),
                        contentDescription = session.displayLabelFor(page),
                        modifier = Modifier
                            .offset(
                                x = with(density) { originX.toDp() },
                                y = with(density) { originY.toDp() }
                            )
                            .width(
                                with(density) {
                                    (refinedClipRect.width * dispW).toDp()
                                }
                            )
                            .height(
                                with(density) {
                                    (refinedClipRect.height * dispH).toDp()
                                }
                            )
                            .align(Alignment.TopStart),
                        contentScale = ContentScale.FillBounds,
                        colorFilter = drawFilter
                    )
                } else {
                    val slot = current ?: preview
                    if (slot != null) {
                        if (settings.edgeTrim > 0f &&
                            cellSizePx != IntSize.Zero
                        ) {
                            // MR-2 (P5/A13): cut-the-edges as
                            // a page-geometry inset. The page
                            // is drawn at its displayed size
                            // grown by 1/(1 − 2·trim) and
                            // centred in the cell; the slot
                            // layer's own clip (above) cuts
                            // the overflow, so the visible
                            // page is the centre (1 − 2·trim)
                            // of the printed page, its
                            // margins pushed out of view. No
                            // re-render, no cache change: the
                            // same RAW bitmap, a different
                            // draw size. The zoom state never
                            // learns about the trim (its pan
                            // clamp stays on the untrimmed
                            // geometry — conservative by the
                            // trim's own width), and the
                            // refine stays gated off (above).
                            val (dispW, dispH) = pdfDisplayedSizePx(
                                slot.width, slot.height,
                                cellSizePx.width, cellSizePx.height,
                                settings.fitMode
                            )
                            val grow = 1f / (1f - 2f * settings.edgeTrim)
                            Image(
                                bitmap = slot.asImageBitmap(),
                                contentDescription = session.displayLabelFor(page),
                                modifier = Modifier
                                    .width(
                                        with(density) {
                                            (dispW * grow).toDp()
                                        }
                                    )
                                    .height(
                                        with(density) {
                                            (dispH * grow).toDp()
                                        }
                                    ),
                                contentScale = ContentScale.FillBounds,
                                colorFilter = drawFilter
                            )
                        } else if (settings.fitMode == PdfFitMode.FIT_PAGE) {
                            Image(
                                bitmap = slot.asImageBitmap(),
                                contentDescription = session.displayLabelFor(page),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                                colorFilter = drawFilter
                            )
                        } else {
                            Image(
                                bitmap = slot.asImageBitmap(),
                                contentDescription = session.displayLabelFor(page),
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                                colorFilter = drawFilter
                            )
                        }
                    }
                }
            }
        }
    }
}

/** v1.5.9 (F-7/F-8): the pending page's placeholder — a tier-1
 * surface at the page's true aspect, spinner + "Page N" label.
 * Shared by the paged cell and the continuous list. */
@Composable
internal fun PdfPagePlaceholder(spec: PdfPlaceholderSpec, modifier: Modifier = Modifier) {
    // A-6: the spinner appears only if the bitmap is STILL
    // absent after ~120 ms — the card + label are immediate,
    // fast loads never flash a spinner.
    var showSpinner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PDF_PLACEHOLDER_SPINNER_DELAY_MS)
        showSpinner = true
    }
    Box(
        modifier.fillMaxWidth()
            .aspectRatio(spec.aspect)
            .background(Color(spec.surfaceArgb)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator()
            }
            Text(
                spec.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ── Continuous model: fit-width vertical scroll ──────────────────

@Composable
private fun PdfContinuousBody(
    handlers: PdfPagesHandlers,
    session: PdfSession,
    initialPage: Int,
    settings: PdfDocSettings,
    autoScroll: AutoScrollController,
    onToggleControls: () -> Unit,
    tapPreset: TapLayoutPreset = TapLayoutPreset.MOON_DEFAULT,
    smartScrollLock: Boolean = true,
    // MR-2 (A12): items report the filter they resolved
    // AUTO to, per page (see PdfDocumentReader).
    onResolvedFilter: (Int, PdfPageFilter) -> Unit = { _, _ -> }
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0)
    )
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // The continuous item width, published by the layout box
    // below so the prefetch effect (above it in the body) can
    // request the very width the items render at.
    var itemWidthPx by remember { mutableStateOf(0) }
    fun widthPxForPrefetch(session: PdfSession, page: Int): Int = itemWidthPx

    // v1.5.28: the SURFACE zoom — Komikku's webtoon model
    // (build-logs/V1528-ZOOM-MAP.md §3.2): one transform over
    // the whole list, owned by this body; items never see a
    // gesture. The surface's pixel size is published by the
    // layout box below, like the item width.
    val surfaceZoom = remember { PdfSurfaceZoomState() }
    var surfaceSizePx by remember { mutableStateOf(IntSize.Zero) }
    var surfaceZoomAnimation by remember {
        mutableStateOf<kotlinx.coroutines.Job?>(null)
    }
    fun cancelSurfaceZoomAnimation() {
        surfaceZoomAnimation?.cancel()
        surfaceZoomAnimation = null
    }
    /** The donor's double-tap: an animated (here
     * [PDF_DOUBLE_TAP_ANIM_MS]) run of scale + pan to the
     * target, transform-only — no item re-renders. */
    fun animateSurfaceZoomTo(targetScale: Float, targetPan: Offset) {
        cancelSurfaceZoomAnimation()
        val startScale = surfaceZoom.scale
        val startPan = surfaceZoom.pan
        if (startScale == targetScale && startPan == targetPan) return
        val w = surfaceSizePx.width
        val h = surfaceSizePx.height
        surfaceZoomAnimation = scope.launch {
            val progress = Animatable(0f)
            progress.animateTo(1f, tween(PDF_DOUBLE_TAP_ANIM_MS)) {
                surfaceZoom.setScalePan(
                    startScale + (targetScale - startScale) * value,
                    startPan + (targetPan - startPan) * value,
                    w, h
                )
            }
        }
    }

    // v1.5.29: TAP ARBITRATION, the same confirmed-single
    // pattern as the paged cell — the watch-only observers
    // here report every clean tap, so a surface double-tap
    // used to toggle the chrome twice before its zoom
    // arrived. The surface double-tap cancels the pending
    // single and produces ONLY the zoom. (The surface
    // double-tap's target was already the fixed
    // PdfSurfaceZoomState.DOUBLE_TAP_SCALE — it never read
    // the magnification preference, so items 1–2 of the
    // v1.5.29 patch needed no change here.)
    val surfaceViewConfiguration = LocalViewConfiguration.current
    var pendingSurfaceSingleTap by remember {
        mutableStateOf<kotlinx.coroutines.Job?>(null)
    }
    fun cancelSurfacePendingSingleTap() {
        pendingSurfaceSingleTap?.cancel()
        pendingSurfaceSingleTap = null
    }
    fun fireSurfaceSingleTapConfirmed(action: () -> Unit) {
        pendingSurfaceSingleTap?.cancel()
        pendingSurfaceSingleTap = scope.launch {
            delay(surfaceViewConfiguration.doubleTapTimeoutMillis)
            action()
        }
    }

    LaunchedEffect(session, handlers) {
        handlers.jumps.collect { jump ->
            listState.scrollToItem(
                jump.index.coerceIn(0, (session.sectionCount - 1).coerceAtLeast(0))
            )
        }
    }
    LaunchedEffect(listState, session) {
        // v1.5.12 (P5 / contract §3.5): continuous prefetch is
        // single-flight per settle too — one window job in THIS
        // effect's scope, cancelled by the next settle before
        // its window launches (the shipped code launched every
        // window from the body's rememberCoroutineScope, so
        // windows stacked while scrolling).
        var prefetchJob: kotlinx.coroutines.Job? = null
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { (page, offsetPx, scrolling) ->
            val itemSize = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == page }?.size ?: 1
            val fraction = if (itemSize > 0) {
                (offsetPx.toFloat() / itemSize).coerceIn(0f, 1f)
            } else {
                0f
            }
            // A-1: publish at the same point the position is
            // reported — settled page + the scrolling flag
            // (the continuous list has no separate targetPage;
            // its target is its settled page).
            session.publishPagerState(
                settledPage = page,
                targetPage = page,
                scrolling = scrolling
            )
            handlers.onPosition(page, fraction)
            // A-4: continuous prefetch at P2 — the tier's
            // full-res window ahead of the last visible item
            // and one behind the first.
            // v1.5.11 (step 1, D1): no generation stamp — P2
            // validity is distance-based, so these prefetches
            // survive continued scrolling instead of being
            // conflated stale by the next settle. The window
            // read here is the budgeted one (step 2, D2).
            val lastVisible = listState.layoutInfo.visibleItemsInfo
                .lastOrNull()?.index ?: page
            // v1.5.12 (P5): the whole window rides ONE job in
            // this effect's scope; the next settle cancels it
            // before launching its own window.
            val windowWidth = itemWidthPx
            prefetchJob?.cancel()
            prefetchJob = launch {
                for (target in (lastVisible + 1)..
                    (lastVisible + session.prefetchForward)) {
                    if (target !in 0 until session.sectionCount) continue
                    launch {
                        session.renderPage(
                            target, windowWidth,
                            PdfPageFilter.NONE, PDF_BASE_SCALE,
                            slot = PdfRenderSlot.P2
                        )
                    }
                }
                val behind = page - session.prefetchBackward
                if (behind in 0 until session.sectionCount) {
                    launch {
                        session.renderPage(
                            behind, windowWidth,
                            PdfPageFilter.NONE, PDF_BASE_SCALE,
                            slot = PdfRenderSlot.P2
                        )
                    }
                }
            }
        }
    }
    // H7: the continuous auto-scroll surface — small smooth
    // scroll steps on G9's engine.
    DisposableEffect(session, settings.autoScrollSpeed) {
        val surface = AutoScrollEngine.Surface {
            val viewport = listState.layoutInfo.viewportSize.height
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            val atEnd = lastVisible != null &&
                lastVisible.index >= session.sectionCount - 1 &&
                lastVisible.offset + lastVisible.size <=
                listState.layoutInfo.viewportEndOffset
            if (atEnd) {
                false
            } else {
                listState.scroll { scrollBy((viewport * 0.02f).coerceAtLeast(2f)) }
                true
            }
        }
        autoScroll.register(
            scope = scope,
            surface = surface,
            intervalMs = AutoScrollEngine.intervalFor(
                AutoScrollEngine.STEP_INTERVAL_MS,
                settings.autoScrollSpeed
            )
        )
        onDispose { autoScroll.unregister(surface) }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .then(
                when (tapPreset) {
                    // The default road: the shipped observer,
                    // unchanged.
                    TapLayoutPreset.MOON_DEFAULT ->
                        Modifier.pdfTapObserver { xFraction ->
                            // Finding 1 §(d) step 4: the centre
                            // zone toggles the chrome; scrolling
                            // itself is untouched.
                            // v1.5.29: as a CONFIRMED single —
                            // a double-tap cancels it and
                            // produces only its zoom.
                            fireSurfaceSingleTapConfirmed {
                                if (xFraction in 0.33f..0.67f) {
                                    onToggleControls()
                                }
                            }
                        }
                    // G2: presets turn by scrolling one page
                    // in the list.
                    else -> Modifier.pdfXYTapObserver { x, y ->
                        val first =
                            listState.firstVisibleItemIndex
                        // v1.5.29: a confirmed single, as
                        // the paged cell.
                        fireSurfaceSingleTapConfirmed {
                        when (tapActionFor(tapPreset, x, y)) {
                            TapZoneAction.TURN_PREV -> scope.launch {
                                listState.animateScrollToItem(
                                    (first - 1).coerceAtLeast(0)
                                )
                            }
                            TapZoneAction.TURN_NEXT -> scope.launch {
                                val last =
                                    (session.sectionCount - 1).coerceAtLeast(0)
                                listState.animateScrollToItem(
                                    (first + 1).coerceAtMost(last)
                                )
                            }
                            TapZoneAction.TOGGLE_CHROME -> onToggleControls()
                            TapZoneAction.NONE -> Unit
                        }
                        }
                    }
                }
            )
            .pointerInput(surfaceZoom) {
                // v1.5.28: the frame-level gesture observer —
                // Komikku's WebtoonFrame keeps its detectors
                // in the PARENT because the scaled child's own
                // coordinates are translated; here the
                // container watches the stream and drives the
                // surface transform, consuming NOTHING (the
                // donor's detectors don't take events from the
                // list either): the list keeps scrolling
                // vertically, pinch zooms the surface, and a
                // single-finger drag while zoomed pans it —
                // X always, Y only at the list's ends, the
                // donor's exact split (mid-list vertical drags
                // belong to the scroll).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val w = surfaceSizePx.width
                        val h = surfaceSizePx.height
                        if (w > 0 && h > 0) {
                            if (pressed.size >= 2) {
                                cancelSurfaceZoomAnimation()
                                // v1.5.29: a claimed pinch
                                // also kills any pending
                                // confirmed single.
                                cancelSurfacePendingSingleTap()
                                val zoomChange = event.calculateZoom()
                                if (zoomChange != 1f) {
                                    // The pressed pointers'
                                    // centroid — the pinch's
                                    // focal point.
                                    var cx = 0f
                                    var cy = 0f
                                    pressed.forEach { change ->
                                        cx += change.position.x
                                        cy += change.position.y
                                    }
                                    surfaceZoom.applyPinch(
                                        Offset(
                                            cx / pressed.size,
                                            cy / pressed.size
                                        ),
                                        zoomChange, w, h
                                    )
                                }
                            } else if (pressed.size == 1 &&
                                surfaceZoom.isZoomed
                            ) {
                                val change = pressed.first()
                                val delta = change.position -
                                    change.previousPosition
                                if (delta != Offset.Zero) {
                                    val atStart =
                                        listState.firstVisibleItemIndex == 0 &&
                                            listState
                                                .firstVisibleItemScrollOffset == 0
                                    val lastVisible =
                                        listState.layoutInfo
                                            .visibleItemsInfo.lastOrNull()
                                    val atEnd = lastVisible != null &&
                                        lastVisible.index >=
                                            session.sectionCount - 1 &&
                                        lastVisible.offset +
                                            lastVisible.size <=
                                            listState.layoutInfo
                                                .viewportEndOffset + 1
                                    surfaceZoom.panBy(
                                        Offset(
                                            delta.x,
                                            if (atStart || atEnd) {
                                                delta.y
                                            } else {
                                                0f
                                            }
                                        ),
                                        w, h
                                    )
                                }
                            }
                        }
                        if (pressed.isEmpty()) break
                    }
                }
            }
            .pointerInput(surfaceZoom) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        // v1.5.28: the donor's double-tap —
                        // animated toggle fit ↔ 2× with the
                        // tapped point held in place; back to
                        // fit from any zoom.
                        // v1.5.29: the pending confirmed
                        // single is cancelled first — the
                        // double-tap produces ONLY the zoom.
                        cancelSurfacePendingSingleTap()
                        if (surfaceSizePx != IntSize.Zero) {
                            val (targetScale, targetPan) =
                                surfaceZoom.doubleTapTarget(
                                    tap,
                                    surfaceSizePx.width,
                                    surfaceSizePx.height
                                )
                            animateSurfaceZoomTo(targetScale, targetPan)
                        }
                    }
                )
            }
    ) {
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val heightPx = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(1)
        LaunchedEffect(widthPx, heightPx) {
            itemWidthPx = widthPx
            surfaceSizePx = IntSize(widthPx, heightPx)
        }
        // F-6: the display filter as a draw-time colour filter
        // over the RAW cached renders.
        // MR-2 (A12): smart contrast resolves PER ITEM from
        // the item's own bitmap (see the item body below), so
        // the body-level filter survives only as the non-AUTO
        // shortcut — identical for every manual filter.
        val drawFilter = remember(settings.filter) {
            settings.filter.colorMatrix()?.let { matrix ->
                ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix(matrix.array.copyOf())
                )
            }
        }
        val themeBackground = MaterialTheme.colorScheme.background
        val readerDark = 0.299f * themeBackground.red +
            0.587f * themeBackground.green +
            0.114f * themeBackground.blue < 0.5f
        // v1.5.10 (G2): the Smart scroll lock — on a
        // predominantly vertical drag, the stray horizontal
        // component is consumed before it can drift the
        // column sideways (the pure decision is
        // [smartScrollLockConsumesHorizontal]).
        val smartLockConnection = remember(smartScrollLock) {
            object :
                androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(
                    available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll
                        .NestedScrollSource
                ): androidx.compose.ui.geometry.Offset =
                    if (smartScrollLockConsumesHorizontal(
                            available.x, available.y, smartScrollLock
                        )
                    ) {
                        androidx.compose.ui.geometry.Offset(available.x, 0f)
                    } else {
                        androidx.compose.ui.geometry.Offset.Zero
                    }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .nestedScroll(smartLockConnection)
                // v1.5.28: the surface transform — the donor
                // scales its RecyclerView itself; the layout
                // never changes, only the draw (and the input
                // mapping, which the layer carries with it).
                .graphicsLayer {
                    scaleX = surfaceZoom.scale
                    scaleY = surfaceZoom.scale
                    translationX = surfaceZoom.pan.x
                    translationY = surfaceZoom.pan.y
                }
        ) {
            itemsIndexed(
                (0 until session.sectionCount).toList(),
                key = { _, page -> page }
            ) { _, page ->
                var bitmap by remember(page, widthPx) { mutableStateOf<Bitmap?>(null) }
                // v1.5.12 (P1 + P4): the item keys its render on
                // the session's published pager state (a
                // born-stale drop of the initial page's P0 is
                // re-issued when the publication catches up, and
                // a later prefetch cache-hit can fill the item),
                // and its placeholder peeks the size cache —
                // composition never opens a page; a miss fills
                // asynchronously and the size version recomposes.
                val itemPublication by session.pagerPublication
                    .collectAsStateWithLifecycle()
                val itemSizeVersion by session.sizeTableVersion
                    .collectAsStateWithLifecycle()
                LaunchedEffect(page, itemSizeVersion) {
                    if (session.pagePointsSizePeek(page) == null) {
                        session.requestPageSizeAsync(page)
                    }
                }
                LaunchedEffect(page, widthPx, itemPublication) {
                    bitmap = session.renderPage(
                        page, widthPx, PdfPageFilter.NONE, PDF_BASE_SCALE,
                        slot = if (page == initialPage) {
                            PdfRenderSlot.P0
                        } else {
                            PdfRenderSlot.P2
                        }
                    )
                }
                // F-4: aspect from the cached size table.
                // v1.5.12 (P4): peek only (see above).
                val placeholder = pdfPlaceholderSpec(
                    page, session.pagePointsSizePeek(page)
                )
                val current = bitmap
                // MR-2 (A12): this item's resolved filter —
                // Smart contrast reads the item's own bitmap; a manual
                // filter resolves to itself and the item
                // draws with the body's filter, unchanged.
                val itemResolved = settings.filter.resolvedForDisplay(
                    settings.autoContrast, readerDark, current
                )
                LaunchedEffect(itemResolved) {
                    onResolvedFilter(page, itemResolved)
                }
                val itemAutoFilter = remember(itemResolved) {
                    itemResolved.colorMatrix()?.let { matrix ->
                        ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix(
                                matrix.array.copyOf()
                            )
                        )
                    }
                }
                val itemDrawFilter = if (settings.autoContrast) {
                    itemAutoFilter
                } else {
                    drawFilter
                }
                if (current == null) {
                    // F-7: pending items get the page-shaped
                    // placeholder, not a blank reserved box.
                    PdfPagePlaceholder(spec = placeholder)
                } else {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = session.displayLabelFor(page),
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                        colorFilter = itemDrawFilter
                    )
                }
            }
        }
    }
}

// ── Go to page: number entry + thumbnail-free page grid ─────────
