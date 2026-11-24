package app.xreader.ui.reader

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.foundation.layout.widthIn
import kotlin.math.roundToInt
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
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
import app.xreader.data.parse.PageCharGeometry
import app.xreader.data.parse.PdfCharRect
import app.xreader.data.parse.PdfLineIndex
import app.xreader.data.parse.pdfCharIndexAt
import app.xreader.data.parse.pdfMergedLineRects
import app.xreader.data.parse.resolvePdfWordRange
import app.xreader.ui.localreader.AutoScrollController
import app.xreader.ui.localreader.AutoScrollEngine
import app.xreader.ui.localreader.NormalizedRect
import app.xreader.ui.localreader.PDF_BASE_SCALE
import app.xreader.ui.localreader.PDF_DOUBLE_TAP_ANIM_MS
import app.xreader.ui.localreader.PDF_PLACEHOLDER_SPINNER_DELAY_MS
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
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.toSpec
import app.xreader.ui.localreader.SelectionNoteEditor
import app.xreader.ui.localreader.StoredSelectionHighlight
import app.xreader.ui.localreader.TapLayoutPreset
import app.xreader.ui.localreader.TapZoneAction
import app.xreader.ui.localreader.pdfBaseSlotWidthPx
import app.xreader.ui.localreader.pdfDisplayedSizePx
import app.xreader.ui.localreader.pdfPagerUserScrollEnabled
import app.xreader.ui.localreader.pdfPlaceholderSpec
import app.xreader.ui.localreader.pdfRenderViewportPx
import app.xreader.ui.localreader.pdfTapCentredPan
import app.xreader.ui.localreader.pdfTapObserver
import app.xreader.ui.localreader.pdfVisibleClip
import app.xreader.ui.localreader.resolvedForDisplay
import app.xreader.ui.localreader.smartScrollLockConsumesHorizontal
import app.xreader.ui.localreader.tapActionFor
import app.xreader.ui.localreader.withFilter
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** MR-2b (Revision 1, Part G): the scanned-book line —
 * shown once per book per session when the whole book has
 * no text layer. Plain words, said once, never a toast
 * channel (the app has none). */
private const val PDF_SCANNED_LINE =
    "This book is scanned pictures — there's no text to select."

/** MR-2b (Revision 1, C3): the trim stand-down line — the
 * first time per session the trim hides selection on a book
 * that has something to hide (or a long-press meets it). */
private const val PDF_TRIM_LINE =
    "Page trim is on — selection and highlights are hidden " +
        "until it's off."

/** Pages mode: the printed pages, in the book's chosen
 * scroll model — the shipped bodies, unaltered.
 *
 * MR-2b: the surface also owns the one transient banner
 * both of this build's plain lines ride (the scanned-book
 * line and the trim stand-down line) — top-centred over
 * the page, state-driven, dismissed on page turn. There is
 * no capsule toast anywhere in the app to reuse; this is
 * the honest local shape, not a new channel. */
@Composable
fun PdfPagesSurface(
    session: PdfSession,
    settings: PdfDocSettings,
    initialPage: Int,
    currentPage: Int,
    handlers: PdfPagesHandlers,
    autoScroll: AutoScrollController,
    onToggleControls: () -> Unit,
    onResolvedFilter: (Int, PdfPageFilter) -> Unit,
    pageHighlights: List<StoredSelectionHighlight> = emptyList(),
    searchMark: PdfSearchMark? = null,
    textAvailable: Boolean? = null,
    onAddPageHighlight: (StoredSelectionHighlight) -> Unit = {},
    onRemovePageHighlight: (StoredSelectionHighlight) -> Unit = {},
    onUpdatePageNote: (StoredSelectionHighlight, String) -> Unit =
        { _, _ -> },
    // SelectionBar: the tint write (§7.3), the More doors
    // (§6.4) and the back chain's reports + clear nonce
    // (§9.2), threaded from PdfReaderHost.
    onUpdatePageTint: (StoredSelectionHighlight, Int) -> Unit =
        { _, _ -> },
    onSelectionTranslate: (String) -> Unit = {},
    onSelectionAskAi: (String) -> Unit = {},
    // Code 124 (A7): Illustrate paints exactly the selection.
    onSelectionIllustrate: (String) -> Unit = {},
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    onDialogOpenChanged: (Boolean) -> Unit = {},
    clearSelectionSignal: Int = 0,
    motionEnabled: Boolean = true
) {
    var bannerText by remember(session) { mutableStateOf<String?>(null) }
    var bannerPage by remember(session) { mutableStateOf<Int?>(null) }
    var scannedLineShown by remember(session) { mutableStateOf(false) }
    var trimLineShown by remember(session) { mutableStateOf(false) }
    // A page turn dismisses whatever line is up (the shown
    // flags persist — each line is once per book per
    // session, not once per page).
    fun noteVisiblePage(page: Int) {
        if (bannerPage != page) {
            bannerPage = page
            bannerText = null
        }
    }
    LaunchedEffect(currentPage) { noteVisiblePage(currentPage) }
    LaunchedEffect(textAvailable) {
        if (textAvailable == false && !scannedLineShown) {
            scannedLineShown = true
            bannerText = PDF_SCANNED_LINE
        }
    }
    val onTrimStandDown = {
        if (!trimLineShown) {
            trimLineShown = true
            bannerText = PDF_TRIM_LINE
        }
    }
    Box(Modifier.fillMaxSize()) {
        when (settings.scrollModel) {
            PdfScrollModel.PAGED -> PdfPagedBody(
                handlers = handlers,
                session = session,
                initialPage = initialPage,
                currentPage = currentPage,
                settings = settings,
                autoScroll = autoScroll,
                onToggleControls = onToggleControls,
                onResolvedFilter = onResolvedFilter,
                pageHighlights = pageHighlights,
                searchMark = searchMark,
                onAddPageHighlight = onAddPageHighlight,
                onRemovePageHighlight = onRemovePageHighlight,
                onUpdatePageNote = onUpdatePageNote,
                onUpdatePageTint = onUpdatePageTint,
                onSelectionTranslate = onSelectionTranslate,
                onSelectionAskAi = onSelectionAskAi,
                            onSelectionIllustrate =
                                onSelectionIllustrate,
                onSelectionActiveChanged = onSelectionActiveChanged,
                onDialogOpenChanged = onDialogOpenChanged,
                clearSelectionSignal = clearSelectionSignal,
                motionEnabled = motionEnabled,
                onTrimStandDown = onTrimStandDown
            )
            PdfScrollModel.CONTINUOUS -> PdfContinuousBody(
                handlers = handlers,
                session = session,
                initialPage = initialPage,
                settings = settings,
                autoScroll = autoScroll,
                onToggleControls = onToggleControls,
                onResolvedFilter = onResolvedFilter,
                pageHighlights = pageHighlights,
                searchMark = searchMark,
                onAddPageHighlight = onAddPageHighlight,
                onRemovePageHighlight = onRemovePageHighlight,
                onUpdatePageNote = onUpdatePageNote,
                onUpdatePageTint = onUpdatePageTint,
                onSelectionTranslate = onSelectionTranslate,
                onSelectionAskAi = onSelectionAskAi,
                            onSelectionIllustrate =
                                onSelectionIllustrate,
                onSelectionActiveChanged = onSelectionActiveChanged,
                onDialogOpenChanged = onDialogOpenChanged,
                clearSelectionSignal = clearSelectionSignal,
                motionEnabled = motionEnabled,
                onPageVisible = { page -> noteVisiblePage(page) }
            )
        }
        val line = bannerText
        if (line != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp
            ) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        horizontal = 14.dp, vertical = 8.dp
                    )
                )
            }
        }
    }
}

// ── MR-2b: on-page selection (shared by both bodies) ────────────

/**
 * A live Pages-form selection on one page: [start, end)
 * char offsets into the page's strip text — the stored
 * form's own shape (StoredSelectionHighlight.charStart /
 * charEnd) — over the selection's OWN copy of the page
 * geometry (Revision 1, B3: the session cache may evict; a
 * live selection never loses the ground under its handles).
 */
private class PdfPageSelection(
    val geometry: PageCharGeometry,
    start: Int,
    end: Int
) {
    var start by mutableStateOf(start)
    var end by mutableStateOf(end)

    val text: String
        get() {
            val length = geometry.pageText.length
            val s = start.coerceIn(0, length)
            val e = end.coerceIn(s, length)
            return geometry.pageText.substring(s, e)
        }
}

/** The follow-on surfaces a page's selection can open —
 * one holder per page host (paged cell / continuous item),
 * rendered by [PdfSelectionDialogs]. */
private class PdfSelectionDialogState {
    var noteDraft by mutableStateOf<PdfPageSelection?>(null)
    var editing by mutableStateOf<StoredSelectionHighlight?>(null)
    var dictionary by mutableStateOf<Pair<String, String?>?>(null)

    fun clearAll() {
        noteDraft = null
        editing = null
        dictionary = null
    }
}

/**
 * MR-4 (R1): a continuous-body selection's registration
 * with the surface-level action bar. The bar is hosted in
 * the surface's outermost box (both hosts seat it the same
 * way), so the item that owns the live selection registers
 * itself — its page, its selection, and its action closures
 * (which keep pointing at the item's own dialogs/clipboard
 * state) — and unregisters when the selection clears or the
 * item leaves composition.
 *
 * SelectionBar (Revision 1, Critic F9): the bar's state
 * travels as [MutableState] objects the OWNING ITEM creates
 * and the surface host reads/writes directly — a state
 * object cannot go stale by construction, where a value
 * snapshot in the registration could. [barState] is the
 * bar's hoisted state; [dismissing] is §9.3's post-action
 * fade flag; [dialogOpen] is the item's follow-on-dialog
 * report for the back chain's armed aggregate (Critic F1);
 * [requestClear] is the back chain's clear route into the
 * item-owned selection state (Readiness F4) — built by the
 * owning item, invoked by the body when the screen's clear
 * nonce bumps.
 */
private class PdfContinuousBarRegistration(
    val page: Int,
    val selection: PdfPageSelection,
    val barState: MutableState<SelectionBarState>,
    val dismissing: MutableState<Boolean>,
    val dialogOpen: MutableState<Boolean>,
    val requestClear: () -> Unit,
    val onCopy: () -> Unit,
    val onHighlight: () -> Unit,
    val onNote: () -> Unit,
    val onDictionary: () -> Unit,
    val onApplyHighlight: (tintArgb: Int, style: HighlightStyle) -> Unit,
    val onRemoveHighlight: () -> Unit,
    val onDismiss: () -> Unit
)

/** The selection's start from a press at page point
 * ([xPt], [yPt]): hit char → its word (A4's outward snap
 * with the whitespace scan), or null when the press lands
 * on a wordless line. Shared by both bodies' entries. */
private fun pdfSelectionAt(
    geometry: PageCharGeometry,
    xPt: Float,
    yPt: Float
): PdfPageSelection? {
    val index = pdfCharIndexAt(geometry, xPt, yPt) ?: return null
    val word = resolvePdfWordRange(geometry.pageText, index)
        ?: return null
    return PdfPageSelection(geometry, word.first, word.last + 1)
}

/** F1 (post-re-check): the drain a post-long-press exit
 * owes its gesture. Once an entry recogniser's long-press
 * has fired, Rev 1 §11.1's consumption duty runs to the
 * gesture's end — every remaining change of the tracked
 * pointer is consumed until its up/cancel — so the
 * release can never reach the split tap detectors
 * unconsumed (foundation's waitForUpOrCancellation has
 * no long-press timeout, so an unconsumed up could pair
 * with the next tap). The success paths and the geometry
 * waits already consume to the up inline; the early
 * exits call this instead of returning bare. */
private suspend fun AwaitPointerEventScope
    .drainPdfEntryGesture(pointerId: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes
            .firstOrNull { it.id == pointerId } ?: continue
        change.consume()
        if (!change.pressed) break
    }
}

/** Code 117 §13: one handle end's geometry, in the
 * overlay's local pixel space — the tip point (the anchor
 * char's bottom corner), the teardrop body's centre, and
 * the grab radius. The Canvas draw and the grab hit test
 * both consume [pdfHandleGeometry]'s output, so what is
 * drawn is exactly what can be grabbed. */
private class PdfHandleGeom(
    val tip: Offset,
    val centre: Offset,
    val grabRadiusPx: Float,
    val bodyRadiusPx: Float,
    val tipLengthPx: Float
)

/**
 * Code 117 §13: the handle pair's geometry for a
 * selection's [first]/[last] char rects, scaled from page
 * points by [sx]/[sy].
 *
 * Rule 1 — screen-constant size: this overlay sits inside
 * the cell's zoom-transformed layer, so the local
 * dimensions are dp ÷ z (the density conversion, then
 * divided by the cell's current zoom [z]) — on screen the
 * teardrop's body diameter is 22 dp and its tip 9 dp at
 * every zoom, and the 24 dp grab radius is divided the
 * same way.
 *
 * Rule 2 — one helper: both consumers (draw and hit test)
 * take their numbers from here. Per Revision 2 D2, both
 * consumers read z at USE time (the draw inside its draw
 * lambda, the grab loop at each gesture start) — never a
 * composition-captured Float.
 */
private fun pdfHandleGeometry(
    first: PdfCharRect,
    last: PdfCharRect,
    sx: Float,
    sy: Float,
    z: Float,
    density: Density
): Pair<PdfHandleGeom, PdfHandleGeom> {
    val zoom = if (z > 0f) z else 1f
    val tipPx = with(density) { 9.dp.toPx() } / zoom
    val radiusPx = with(density) { 11.dp.toPx() } / zoom
    val grabPx = with(density) { 24.dp.toPx() } / zoom
    fun end(tip: Offset) = PdfHandleGeom(
        tip = tip,
        centre = tip + Offset(0f, tipPx + radiusPx),
        grabRadiusPx = grabPx,
        bodyRadiusPx = radiusPx,
        tipLengthPx = tipPx
    )
    return end(Offset(first.left * sx, first.bottom * sy)) to
        end(Offset(last.right * sx, last.bottom * sy))
}

/**
 * Code 117 §11.2: the press-drag edge rule for an ENTRY
 * drag (paged + continuous). The entry word-snap happens
 * once, in pdfSelectionAt; extension is CHARACTER-level.
 * The anchor is the entry word's far boundary relative to
 * first travel — moving forward past the word's end pins
 * the anchor at the word's start, moving backward before
 * its start pins it at the word's end; the anchor never
 * changes again. The range is word ∪ span(anchor, finger
 * edge), so it never empties and never inverts.
 *
 * [wordStart]/[wordEnd] are half-open; [index] is the
 * character under the finger. Returns the inclusive range
 * to write and the (possibly newly pinned) anchor.
 */
internal fun pdfEntryDragRange(
    wordStart: Int,
    wordEnd: Int,
    anchor: Int?,
    index: Int
): Pair<IntRange, Int?> {
    var pinned = anchor
    if (pinned == null) {
        when {
            index + 1 > wordEnd -> pinned = wordStart
            index < wordStart -> pinned = wordEnd
            else -> return (wordStart..wordEnd - 1) to null
        }
    }
    val a = pinned!!
    val edge = if (index >= a) index + 1 else index
    val lo = minOf(wordStart, a, edge)
    val hi = maxOf(wordEnd, a, edge)
    return (lo..hi - 1) to a
}

/**
 * The geometry overlay for one page — drawn exactly over
 * the page's displayed rect (the caller sizes and positions
 * this composable into that frame; inside it, coordinates
 * are displayed-page pixels, which Compose delivers already
 * mapped through whatever transform carries the frame).
 *
 * Draws, back to front: the page's stored highlights (each
 * in its own record tint — today always the one violet,
 * Q5), the search mark (amber, the Reflow precedent), the
 * live selection (the Reflow live-selection tint), and the
 * selection's two handle dots at its first char's
 * bottom-left and its last char's bottom-right.
 *
 * Gestures (its own pointerInput, Main pass): a press
 * inside a handle's grab radius drags that edge — changes
 * are consumed from the down, so the paged body's transform
 * loop sees them already-consumed (and its C4 guard applies
 * nothing) and the pager/list is locked by the hosts'
 * selection reports. A clean tap (short, unmoved — a long
 * hold belongs to the hosts' long-press entry) with a live
 * selection dismisses it ([onTapAway]); with no selection,
 * a tap inside a drawn highlight's merged rects opens its
 * bar (overlaps: the most recently created wins), and with
 * that bar open a tap on no highlight is a tap-away too.
 * Anything else is watch-only: nothing is consumed, so the
 * shipped tap/pan/scroll behaviour proceeds untouched.
 */
@Composable
private fun PdfPageGeometryOverlay(
    geometry: PageCharGeometry,
    pageWidthPt: Float,
    pageHeightPt: Float,
    highlights: List<StoredSelectionHighlight>,
    searchMark: PdfSearchMark?,
    selection: PdfPageSelection?,
    onHighlightTap: (StoredSelectionHighlight) -> Unit,
    onTapAway: () -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    /** Code 117 (§11.1): the live drag range, hoisted to
     * the host so the entry recogniser writes through the
     * same channel this overlay renders from. */
    dragRangeState: MutableState<IntRange?>,
    /** Code 117 §13 / Revision 2 D2: the hosting layer's
     * current zoom, read at use time (draw lambda / gesture
     * start) — 1f where the layer is untransformed. */
    zoomProvider: () -> Float = { 1f },
    modifier: Modifier = Modifier,
    /** SelectionBar (§7.4/§9.1): the existing-highlight bar
     * is open over this page — a clean tap that hits no
     * highlight then counts as a tap-away. */
    barOpen: Boolean = false
) {
    // MR-4 (R2.1): the held line index — built once per
    // geometry, the drag loop's hit-test no longer scans
    // the whole page per move.
    val lineIndex = remember(geometry) { PdfLineIndex(geometry) }
    // MR-4 (R2.2): the highlights' and the search mark's
    // merged rects, computed once per (geometry, highlights,
    // mark) change and HELD — a drag redraw recomputes only
    // the live selection's rects.
    val heldHighlightRects = remember(geometry, highlights) {
        highlights.mapNotNull { highlight ->
            val start = highlight.charStart ?: return@mapNotNull null
            val end = highlight.charEnd ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            val clampedStart = start.coerceIn(0, geometry.pageText.length)
            val clampedEnd = end.coerceIn(
                clampedStart, geometry.pageText.length
            )
            if (clampedEnd <= clampedStart) return@mapNotNull null
            highlight to pdfMergedLineRects(
                geometry, clampedStart..clampedEnd - 1
            )
        }
    }
    val heldMarkRects = remember(geometry, searchMark) {
        val mark = searchMark
        if (mark == null || mark.end <= mark.start) {
            emptyList()
        } else {
            val clampedStart = mark.start
                .coerceIn(0, geometry.pageText.length)
            val clampedEnd = mark.end
                .coerceIn(clampedStart, geometry.pageText.length)
            if (clampedEnd > clampedStart) {
                pdfMergedLineRects(
                    geometry, clampedStart..clampedEnd - 1
                )
            } else {
                emptyList()
            }
        }
    }
    // MR-4 (R2.3): the drag's live range — the settled
    // [selection] (and with it the action bar's text) is
    // written only at settle points: entry (the hosts create
    // the selection whole) and drag release (the commit in
    // the drag loop below). Code 117: the range state is
    // hoisted (the entry recogniser writes it too) and
    // resets when the selection OBJECT changes — a stale
    // range from a dismissed episode can never render over
    // a new selection (at entry the fallback below reads
    // the selection itself, which holds the same word).
    var dragRange by dragRangeState
    LaunchedEffect(selection) {
        dragRangeState.value = null
    }
    val effStart = dragRange?.first ?: selection?.start ?: 0
    val effEnd = dragRange?.let { it.last + 1 } ?: selection?.end ?: 0
    // Code 120 (recheck erratum E1): the live-selection wash and
    // handles bind to the active design's selection colour —
    // LocalSelectionTint defaults to the ReaderViolet constant's
    // value (Nocturne byte-identical); Material 3 provides its
    // scheme's primary.
    val selectionTint = app.xreader.ui.theme.LocalSelectionTint.current
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (pageWidthPt <= 0f || pageHeightPt <= 0f) {
                return@Canvas
            }
            val sx = size.width / pageWidthPt
            val sy = size.height / pageHeightPt
            fun drawRects(rects: List<PdfCharRect>, color: Color) {
                for (rect in rects) {
                    drawRect(
                        color = color,
                        topLeft = Offset(rect.left * sx, rect.top * sy),
                        size = Size(
                            (rect.right - rect.left) * sx,
                            (rect.bottom - rect.top) * sy
                        )
                    )
                }
            }
            for ((highlight, rects) in heldHighlightRects) {
                drawRects(rects, Color(highlight.tintArgb))
            }
            if (heldMarkRects.isNotEmpty()) {
                drawRects(heldMarkRects, Color(0x99FFB020))
            }
            val live = selection
            if (live != null && effEnd > effStart) {
                drawRects(
                    pdfMergedLineRects(
                        geometry, effStart..effEnd - 1
                    ),
                    selectionTint.copy(alpha = 0.33f)
                )
                val first = geometry.rects.getOrNull(effStart)
                val last = geometry.rects.getOrNull(effEnd - 1)
                if (first != null && last != null) {
                    // Code 117 §13: the handles are the
                    // §6.2 teardrops — tip on the anchor
                    // char's bottom corner, body hanging
                    // below — drawn in ReaderViolet, sized
                    // screen-constant through
                    // pdfHandleGeometry (z read here, at
                    // draw time — Revision 2 D2). Start
                    // first, end last (on top).
                    val (startGeom, endGeom) = pdfHandleGeometry(
                        first, last, sx, sy, zoomProvider(), this
                    )
                    drawPath(
                        path = selectionTeardropPath(
                            startGeom.tip,
                            startGeom.tipLengthPx,
                            startGeom.bodyRadiusPx
                        ),
                        color = selectionTint
                    )
                    drawPath(
                        path = selectionTeardropPath(
                            endGeom.tip,
                            endGeom.tipLengthPx,
                            endGeom.bodyRadiusPx
                        ),
                        color = selectionTint
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(
                geometry, selection, highlights
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width
                    val height = size.height
                    if (width <= 0 || height <= 0 ||
                        pageWidthPt <= 0f || pageHeightPt <= 0f
                    ) {
                        return@awaitEachGesture
                    }
                    val sx = width / pageWidthPt
                    val sy = height / pageHeightPt
                    fun pointOf(position: Offset) = Offset(
                        position.x / sx, position.y / sy
                    )
                    val live = selection
                    if (live != null && effEnd > effStart) {
                        val first = geometry.rects.getOrNull(effStart)
                        val last = geometry.rects.getOrNull(effEnd - 1)
                        if (first != null && last != null) {
                            // Code 117 §13: the grab geometry
                            // comes from the one helper the
                            // draw uses, z read at gesture
                            // start (Revision 2 D2). Grab
                            // routing keeps start-dot
                            // priority (§11.3, unchanged).
                            val (startGeom, endGeom) =
                                pdfHandleGeometry(
                                    first, last, sx, sy,
                                    zoomProvider(), this
                                )
                            val handle = when {
                                (down.position - startGeom.centre)
                                    .getDistance() <=
                                    startGeom.grabRadiusPx -> 0
                                (down.position - endGeom.centre)
                                    .getDistance() <=
                                    endGeom.grabRadiusPx -> 1
                                else -> null
                            }
                            if (handle != null) {
                                down.consume()
                                onDragStateChange(true)
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes
                                            .firstOrNull {
                                                it.id == down.id
                                            } ?: continue
                                        if (change.positionChanged()) {
                                            change.consume()
                                            val point = pointOf(
                                                change.position
                                            )
                                            // MR-4 (R2.1): the
                                            // held line index
                                            // answers; the full
                                            // scan stays as the
                                            // fallback.
                                            val index = lineIndex
                                                .charIndexAt(
                                                    point.x, point.y
                                                )
                                                ?: pdfCharIndexAt(
                                                    geometry,
                                                    point.x, point.y
                                                )
                                            if (index != null) {
                                                // Code 117 §11.2:
                                                // extension is
                                                // CHARACTER-level —
                                                // the moving edge is
                                                // the character
                                                // under the finger,
                                                // clamped against
                                                // the fixed edge
                                                // (minimum one
                                                // character, never
                                                // inverted, never
                                                // empty). The word
                                                // snap lives only at
                                                // entry, inside
                                                // pdfSelectionAt.
                                                if (handle == 0) {
                                                    val newStart =
                                                        index.coerceIn(
                                                            0,
                                                            (effEnd - 1)
                                                                .coerceAtLeast(0)
                                                        )
                                                    dragRange =
                                                        newStart..effEnd - 1
                                                } else {
                                                    val newEnd =
                                                        (index + 1)
                                                            .coerceIn(
                                                                effStart + 1,
                                                                geometry
                                                                    .pageText
                                                                    .length
                                                            )
                                                    dragRange =
                                                        effStart..newEnd - 1
                                                }
                                            }
                                        }
                                        if (!change.pressed) break
                                    }
                                    // MR-4 (R2.3): the settle —
                                    // the one write of the
                                    // settled selection (and
                                    // its text) per drag.
                                    val settled = dragRange
                                    if (settled != null) {
                                        live.start = settled.first
                                        live.end = settled.last + 1
                                        dragRange = null
                                    }
                                } finally {
                                    onDragStateChange(false)
                                }
                                return@awaitEachGesture
                            }
                        }
                    }
                    // Tap routing: wait for the up without
                    // consuming anything; a drag or a long
                    // hold is not a tap (the hold is the
                    // hosts' long-press entry). The up is
                    // consumed only once the tap is confirmed
                    // as one this overlay handles.
                    var upChange:
                        androidx.compose.ui.input.pointer
                            .PointerInputChange? = null
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull {
                            it.id == down.id
                        } ?: continue
                        if ((change.position - down.position)
                                .getDistance() >
                            viewConfiguration.touchSlop
                        ) {
                            moved = true
                        }
                        if (!change.pressed) {
                            upChange = change
                            break
                        }
                    }
                    val tap = upChange ?: return@awaitEachGesture
                    if (moved ||
                        tap.uptimeMillis - down.uptimeMillis >
                        viewConfiguration.longPressTimeoutMillis
                    ) {
                        return@awaitEachGesture
                    }
                    if (live != null) {
                        tap.consume()
                        onTapAway()
                        return@awaitEachGesture
                    }
                    val point = pointOf(tap.position)
                    // MR-4 (R2.2): the tap hit-test reads the
                    // held merged rects, not a fresh merge.
                    val hit = heldHighlightRects.filter { (highlight, rects) ->
                        rects.any { rect -> rect.contains(point.x, point.y) }
                    }.maxByOrNull { (highlight, _) ->
                        highlight.createdAtEpochMillis
                    }?.first
                    if (hit != null) {
                        tap.consume()
                        onHighlightTap(hit)
                    } else if (barOpen) {
                        // SelectionBar (§9.1): with the
                        // existing-highlight bar open and no
                        // live selection, a clean tap on no
                        // highlight is a tap-away — consumed,
                        // the bar dismisses.
                        tap.consume()
                        onTapAway()
                    }
                }
            }
        )
    }
}

/** SelectionBar: the hosts seat the shared [SelectionBar]
 * AT the selection (MR-4, R1: the seat copies Moon+'s
 * layoutHBar as adapted there) in the surface's outermost
 * box — outside any zoom layer, so it never scales. Visible
 * from the selection's entry and during drags. The bar
 * never receives the selection's text (§5: its size cannot
 * depend on the selection); the hosts report its measured
 * size back through `onSizeChanged` for the seat. */

/** MR-4 (R1): the inputs of the bar's seat, all in the
 * hosting surface's own pixel space (post-transform). */
private class PdfBarSeatInputs(
    /** The selection's start dot centre. */
    val startDot: Offset,
    /** The selection's horizontal midpoint (surface px) —
     * code 117 §14's fallback anchor when the start-dot
     * side cannot be honoured inside the 16 dp slack. */
    val midX: Float,
    /** The lower (end) dot's bottom edge. */
    val endDotBottom: Float,
    /** The selection's first line top edge. */
    val firstLineTop: Float,
    /** The selection's first line height. */
    val firstLineHeight: Float
)

/**
 * MR-4 (R1): a selection's seat inputs from its geometry,
 * given the caller's page-point → surface-point mapping.
 * [heightScale] converts a page-point height into surface
 * pixels (the caller's y scale × zoom); [dotRadiusPx] is
 * the handle dot's radius in surface pixels. Null when the
 * range draws nothing.
 */
private fun pdfSeatInputs(
    geometry: PageCharGeometry,
    start: Int,
    end: Int,
    mapPoint: (xPt: Float, yPt: Float) -> Offset,
    heightScale: Float,
    dotRadiusPx: Float
): PdfBarSeatInputs? {
    if (end <= start) return null
    val clampedStart = start.coerceIn(0, geometry.pageText.length)
    val clampedEnd = end.coerceIn(clampedStart, geometry.pageText.length)
    if (clampedEnd <= clampedStart) return null
    val rects = pdfMergedLineRects(geometry, clampedStart..clampedEnd - 1)
    val first = rects.firstOrNull() ?: return null
    val last = rects.last()
    val startDot = mapPoint(first.left, first.bottom)
    val endDot = mapPoint(last.right, last.bottom)
    val minLeft = rects.minOf { it.left }
    val maxRight = rects.maxOf { it.right }
    return PdfBarSeatInputs(
        startDot = startDot,
        midX = mapPoint((minLeft + maxRight) / 2f, first.top).x,
        endDotBottom = endDot.y + dotRadiusPx,
        firstLineTop = mapPoint(first.left, first.top).y,
        firstLineHeight = (first.bottom - first.top) * heightScale
    )
}

/**
 * MR-4 (R1): the bar's offset within its surface, copying
 * Moon+'s `layoutHBar` (ActivityTxt.java:20893-20972) as
 * adapted: vertically the bar's bottom edge seats at the
 * first line's top − (the first line's display height +
 * 15dp) when that fits, else the bar's top seats at the
 * lower dot's bottom + 15dp, else the bar pins to the
 * surface's bottom edge (8dp).
 *
 * Horizontal rule (code 117 §14): the bar's left edge
 * lives in [marginPx, surface − bar − marginPx] — 16 dp
 * of slack at both edges. The preferred anchor is the
 * start dot's x; when that anchor cannot be honoured
 * inside the range, the bar CENTRES on the selection's
 * horizontal midpoint instead of pinning to an edge.
 */
private fun pdfBarSeatOffset(
    surfaceW: Float,
    surfaceH: Float,
    barW: Float,
    barH: Float,
    inputs: PdfBarSeatInputs,
    gapPx: Float,
    edgePx: Float,
    marginPx: Float
): Offset {
    val maxLeft = (surfaceW - barW - marginPx)
        .coerceAtLeast(marginPx)
    val preferred = inputs.startDot.x
    val left = if (preferred < marginPx || preferred > maxLeft) {
        (inputs.midX - barW / 2f).coerceIn(marginPx, maxLeft)
    } else {
        preferred
    }
    val aboveY = inputs.firstLineTop -
        (inputs.firstLineHeight + gapPx) - barH
    val y = when {
        aboveY >= 0f -> aboveY
        else -> {
            val belowY = inputs.endDotBottom + gapPx
            if (belowY + barH <= surfaceH - edgePx) {
                belowY
            } else {
                (surfaceH - edgePx - barH).coerceAtLeast(0f)
            }
        }
    }
    return Offset(left, y)
}

/**
 * Code 117 §12.1 (Revision 1's replacement — the base
 * spec's Main-pass mechanism could never fire): a
 * watch-only INITIAL-pass watcher seated on the bar's own
 * modifier chain. A clean tap that lands anywhere on the
 * bar cancels the surface's pending single tap (the job a
 * tap-zone observer created at the tap's up, ~300 ms from
 * firing), so a bar tap can never turn the page or toggle
 * the chrome. It watches only and consumes nothing; it
 * covers the bar's bounds only, never the cell's.
 *
 * Clean tap = single pointer throughout, never past
 * touch slop, up within 400 ms of the down (the
 * pdfTapObserver's own constant).
 *
 * (Revision 2 D4: a press starting within touch slop
 * OUTSIDE the bar's bounds and drifting to an up INSIDE
 * them is a recorded seam, not covered here.)
 */
private suspend fun PointerInputScope.pdfBarTapWatcher(
    onCleanTap: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial
        )
        var sawSecondPointer = false
        var moved = false
        var upTime = 0L
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) sawSecondPointer = true
            for (change in event.changes) {
                if ((change.position - down.position)
                        .getDistance() > viewConfiguration.touchSlop
                ) {
                    moved = true
                }
                if (!change.pressed) {
                    upTime = change.uptimeMillis
                }
            }
            if (event.changes.all { !it.pressed }) break
        }
        if (!sawSecondPointer && !moved && upTime > 0L &&
            upTime - down.uptimeMillis <= 400L
        ) {
            onCleanTap()
        }
    }
}

/** The selection's follow-on dialogs (the kit's own
 * surfaces, shared with Reflow): the note editor in its
 * new-note and edit-note seats, and the dictionary result.
 * (A tapped highlight's menu is gone — SelectionBar §7.4:
 * the tap now opens the bar in its existing-highlight
 * state, trash slot and colour row included.) */
@Composable
private fun PdfSelectionDialogs(
    state: PdfSelectionDialogState,
    onNoteDraftSave: (String) -> Unit,
    onEditNoteSave: (String) -> Unit
) {
    val draft = state.noteDraft
    if (draft != null) {
        SelectionNoteEditor(
            selectedText = draft.text,
            initialNote = "",
            onSave = onNoteDraftSave,
            onCancel = { state.noteDraft = null }
        )
    }
    val editing = state.editing
    if (editing != null) {
        SelectionNoteEditor(
            selectedText = editing.selectedText,
            initialNote = editing.note,
            onSave = onEditNoteSave,
            onCancel = { state.editing = null }
        )
    }
    val lookup = state.dictionary
    if (lookup != null) {
        AlertDialog(
            onDismissRequest = { state.dictionary = null },
            title = { Text(lookup.first) },
            text = {
                Text(
                    lookup.second
                        ?: "No definition found on this device."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { state.dictionary = null }
                ) { Text("Close") }
            }
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
                                2.dp,
                                app.xreader.ui.theme.LocalSelectionTint.current,
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
    onResolvedFilter: (Int, PdfPageFilter) -> Unit = { _, _ -> },
    // MR-2b: the Pages-form selection seam (highlights in,
    // writes out through the ViewModel's store doors).
    pageHighlights: List<StoredSelectionHighlight> = emptyList(),
    searchMark: PdfSearchMark? = null,
    onAddPageHighlight: (StoredSelectionHighlight) -> Unit = {},
    onRemovePageHighlight: (StoredSelectionHighlight) -> Unit = {},
    onUpdatePageNote: (StoredSelectionHighlight, String) -> Unit =
        { _, _ -> },
    // SelectionBar: threaded from the surface (see
    // PdfPagesSurface's own params).
    onUpdatePageTint: (StoredSelectionHighlight, Int) -> Unit =
        { _, _ -> },
    onSelectionTranslate: (String) -> Unit = {},
    onSelectionAskAi: (String) -> Unit = {},
    // Code 124 (A7): Illustrate paints exactly the selection.
    onSelectionIllustrate: (String) -> Unit = {},
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    onDialogOpenChanged: (Boolean) -> Unit = {},
    clearSelectionSignal: Int = 0,
    motionEnabled: Boolean = true,
    onTrimStandDown: () -> Unit = {}
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
            // MR-2b (Revision 1, C4): selection activity is
            // reported PER PAGE on its OWN channel — never
            // through the zoom reports — and the pager's
            // scroll-enable derives from the settled unit's
            // pages exactly as the zoom derivation does: a
            // live selection on a settled page locks the
            // swipe (the handles own the drags) until a clear
            // site (page change, mode exit, dismiss) reports
            // it away. Edge taps and jumps still turn.
            var selectionReports by remember {
                mutableStateOf<Map<Int, Boolean>>(emptyMap())
            }
            // Code 119 (CONVICTION-CODE118-PDF-SELECT §6):
            // the cells' entry-shield reports (entry pending
            // OR a selection drag live), aggregated beside
            // the selection reports — the settle effect
            // below stands down while any is up, because a
            // stationary long-press hold reads as
            // not-scrolling and the settle could otherwise
            // animate mid-press.
            var entryReports by remember {
                mutableStateOf<Map<Int, Boolean>>(emptyMap())
            }
            val anyEntryGestureActive =
                entryReports.values.any { it }
            // SelectionBar (Critic F1): the cells' follow-on
            // dialog reports, aggregated beside the selection
            // reports — the body's dialog-open boolean and
            // the ARMED selection aggregate (selection active
            // AND no selection dialog open) the back chain
            // consumes (§9.2 as revised).
            var dialogReports by remember {
                mutableStateOf<Map<Int, Boolean>>(emptyMap())
            }
            val anyDialogOpen = dialogReports.values.any { it }
            val anySelectionActive = selectionReports.values.any { it }
            LaunchedEffect(anySelectionActive, anyDialogOpen) {
                onSelectionActiveChanged(
                    anySelectionActive && !anyDialogOpen
                )
                onDialogOpenChanged(anyDialogOpen)
            }
            var settledUnit by remember {
                mutableStateOf(pagerState.currentPage)
            }
            val settledPagesNow = PdfSpreadMath.pagesForUnit(
                settledUnit, pageCount, spreadActive
            )
            val zoomScrollEnabled = pdfPagerUserScrollEnabled(
                settledPages = settledPagesNow,
                zoomedPages = zoomReports.filterValues { it.first }.keys
            )
            // The swipe is enabled only when NEITHER a settled
            // page's zoom NOR a settled page's live selection
            // owns the drags. (activeZoomed keeps its shipped
            // meaning — the jump road resets zoom handles —
            // and reads the zoom derivation alone.)
            val pagerScrollEnabled = zoomScrollEnabled &&
                pdfPagerUserScrollEnabled(
                    settledPages = settledPagesNow,
                    zoomedPages = selectionReports
                        .filterValues { it }.keys
                )
            val activeZoomed = !zoomScrollEnabled

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
            // Code 118 (S6 ghosting, CONVICTION-S6 claim B):
            // while a settled page is zoomed the pager must
            // never sit PARKED mid-turn. A turn-drag combined
            // with a pinch can interrupt the pager's own snap
            // (the gesture is claimed by the cell's zoom), and
            // the zoom lock (userScrollEnabled above) then
            // holds the fractional offset for the whole zoom
            // session — the settled cell and its neighbour,
            // each in its own zoom transform, both on screen.
            // This effect settles the pager onto its nearest
            // unit in exactly that state: zoom engaged, pager
            // idle, offset non-zero. The pager's own snap owns
            // every normal gesture end (it runs while
            // isScrollInProgress is still true, so the offset
            // is already 0 when this could fire), and the
            // effect stands down the moment the zoom does —
            // unzoomed paging is never touched.
            // Code 119 (CONVICTION-CODE118-PDF-SELECT §6):
            // the effect ALSO stands down while a selection
            // entry is pending or a selection drag is live
            // on any page (anyEntryGestureActive, keyed so
            // the effect restarts around the press): a
            // stationary hold is "not scrolling", so without
            // this the settle could animate mid-press and a
            // cell leaving the settled set would clear the
            // entry's state mid-gesture. When the press
            // resolves the effect re-keys and settles as
            // before.
            LaunchedEffect(pagerState, activeZoomed, anyEntryGestureActive) {
                if (!activeZoomed) return@LaunchedEffect
                if (anyEntryGestureActive) return@LaunchedEffect
                snapshotFlow {
                    pagerState.isScrollInProgress to
                        pagerState.currentPageOffsetFraction
                }.collect { (scrolling, fraction) ->
                    if (!scrolling && fraction != 0f) {
                        pagerState.animateScrollToPage(
                            pagerState.currentPage
                        )
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
                    val isJump = kotlin.math.abs(jump - settledFirst) > 1
                    if (isJump) {
                        session.flushQueuedPrefetch()
                    }
                    boostPage = if (isJump) {
                        jump
                    } else {
                        null
                    }
                    val target = PdfSpreadMath.unitForPage(
                        jump.coerceIn(0, pageCount - 1), spreadActive
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
                            pageHighlights = pageHighlights,
                            searchMark = searchMark,
                            isSettledPage = when (unit) {
                                pagerState.currentPage -> true
                                else -> false
                            },
                            onSelectionActivity = { reportedPage, active ->
                                selectionReports = selectionReports +
                                    (reportedPage to active)
                            },
                            onEntryActivity = { reportedPage, active ->
                                entryReports = entryReports +
                                    (reportedPage to active)
                            },
                            onDialogActivity = { reportedPage, open ->
                                dialogReports = dialogReports +
                                    (reportedPage to open)
                            },
                            onAddPageHighlight = onAddPageHighlight,
                            onRemovePageHighlight = onRemovePageHighlight,
                            onUpdatePageNote = onUpdatePageNote,
                            onUpdatePageTint = onUpdatePageTint,
                            onSelectionTranslate = onSelectionTranslate,
                            onSelectionAskAi = onSelectionAskAi,
                            onSelectionIllustrate =
                                onSelectionIllustrate,
                            clearSelectionSignal = clearSelectionSignal,
                            motionEnabled = motionEnabled,
                            onTrimStandDown = onTrimStandDown,
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
    onResolvedFilter: (Int, PdfPageFilter) -> Unit = { _, _ -> },
    // MR-2b: the on-page selection seam — this book's
    // Pages-form highlights (all pages; the cell filters to
    // its own), the search mark, the settled-page flag the
    // selection's page-change clear site keys on, and the
    // dedicated selection-activity report (C4 — never the
    // zoom channel).
    pageHighlights: List<StoredSelectionHighlight> = emptyList(),
    searchMark: PdfSearchMark? = null,
    isSettledPage: Boolean = true,
    onSelectionActivity: (Int, Boolean) -> Unit = { _, _ -> },
    // Code 119: the entry-shield report — entry pending
    // OR a selection drag live on this page; the body
    // aggregates it for the settle effect's stand-down.
    onEntryActivity: (Int, Boolean) -> Unit = { _, _ -> },
    onAddPageHighlight: (StoredSelectionHighlight) -> Unit = {},
    onRemovePageHighlight: (StoredSelectionHighlight) -> Unit = {},
    onUpdatePageNote: (StoredSelectionHighlight, String) -> Unit =
        { _, _ -> },
    // SelectionBar: the per-cell dialog report (Critic F1),
    // the tint write (§7.3), the More doors (§6.4) and the
    // back chain's clear nonce (§9.2).
    onDialogActivity: (Int, Boolean) -> Unit = { _, _ -> },
    onUpdatePageTint: (StoredSelectionHighlight, Int) -> Unit =
        { _, _ -> },
    onSelectionTranslate: (String) -> Unit = {},
    onSelectionAskAi: (String) -> Unit = {},
    // Code 124 (A7): Illustrate paints exactly the selection.
    onSelectionIllustrate: (String) -> Unit = {},
    clearSelectionSignal: Int = 0,
    motionEnabled: Boolean = true,
    onTrimStandDown: () -> Unit = {}
) {
    val cellScope = rememberCoroutineScope()
    val cellContext = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // MR-2b: this cell's selection state. The selection
    // holds its own geometry copy (B3); [pageGeometry] is
    // the cell's drawing copy for stored highlights + the
    // search mark, fetched when either needs it (B3 i/ii).
    var selection by remember(page) {
        mutableStateOf<PdfPageSelection?>(null)
    }
    var pageGeometry by remember(page) {
        mutableStateOf<PageCharGeometry?>(null)
    }
    val selectionDragActive = remember(page) { mutableStateOf(false) }
    // Code 119 (CONVICTION-CODE118-PDF-SELECT §2): the
    // entry shield. True from the entry recogniser's
    // awaitFirstDown until its gesture resolves (its
    // try/finally clears it on every exit). While it is
    // up, this cell's transform loop must not consume
    // single-pointer changes — a consumed change kills
    // awaitLongPressOrCancellation (foundation 1.7.6
    // reads isConsumed in its wait loop), which is
    // exactly how a zoomed long-press died on any
    // finger drift. Reported to the body beside the
    // selection reports (onEntryActivity) so the
    // pager's settle effect stands down mid-press too.
    val entryPending = remember(page) { mutableStateOf(false) }
    // Code 117 (§11.1): the live drag range, hoisted out of
    // the geometry overlay so the entry recogniser writes
    // through the same channel the overlay renders from
    // (half-open [first, last + 1) carried as an inclusive
    // range). The overlay resets it when the selection
    // object changes.
    val dragRangeState = remember(page) {
        mutableStateOf<IntRange?>(null)
    }
    // Code 117 §14 / Revision 2 D1: the last computed seat
    // target. While an episode's bar is unmeasured the seat
    // is NOT placed — the animated target holds this value
    // and the bar stays invisible (alpha gate at the bar).
    var seatHold by remember(page) {
        mutableStateOf(IntOffset.Zero)
    }
    val dialogState = remember(page) { PdfSelectionDialogState() }
    // SelectionBar (§6.1): the bar's hoisted state, its
    // §9.3 dismissing flag, and the existing-highlight
    // record whose tap opened the bar in its §7.4 state
    // (mutually exclusive with a live selection — the
    // overlay only offers the tap when none is live).
    var barState by remember(page) {
        mutableStateOf(SelectionBarState())
    }
    var barDismissing by remember(page) { mutableStateOf(false) }
    var existingBar by remember(page) {
        mutableStateOf<StoredSelectionHighlight?>(null)
    }
    // §9.3 (as revised): an action tap fires its effect at
    // once and sets [barDismissing]; the selection state
    // clears 150ms later, as the bar's fade completes. A
    // new selection entry cancels the fade (its write sets
    // barDismissing = false first, which cancels this
    // effect); tap-away / back / scroll clear immediately
    // at their own sites.
    LaunchedEffect(barDismissing) {
        if (barDismissing) {
            delay(150)
            selection = null
            existingBar = null
            barDismissing = false
        }
    }
    // §6.1's reset rule: the bar state resets whenever the
    // selection clears, and a fresh selection supersedes
    // any existing-highlight bar with a clean state.
    LaunchedEffect(selection, existingBar) {
        if (selection != null) {
            // Code 124 (A9): a fresh selection that sits ON a
            // stored highlight of this page opens the bar in
            // that highlight's existing state — Remove and
            // the re-tint row act on the stored record (the
            // callbacks below already fulfil both against
            // existingBar) instead of stacking a second
            // highlight. Any other fresh selection supersedes
            // an existing-highlight bar with a clean state,
            // as before.
            val sel = checkNotNull(selection)
            val hit = pageHighlights.firstOrNull { stored ->
                val cs = stored.charStart
                val ce = stored.charEnd
                stored.page == page && cs != null && ce != null &&
                    sel.start >= cs && sel.end <= ce
            }
            if (hit != null) {
                if (existingBar != hit) existingBar = hit
                if (!barState.existingHighlight) {
                    barState = SelectionBarState(
                        existingHighlight = true,
                        activeTintArgb = hit.tintArgb
                    )
                }
            } else {
                if (existingBar != null) existingBar = null
                barState = SelectionBarState()
            }
        } else if (existingBar == null) {
            barState = SelectionBarState()
        }
    }
    // Critic F1: this cell's follow-on-dialog report — the
    // body aggregates it into the back chain's dialog layer.
    val anyDialogOpen = dialogState.noteDraft != null ||
        dialogState.editing != null || dialogState.dictionary != null
    LaunchedEffect(anyDialogOpen) {
        onDialogActivity(page, anyDialogOpen)
    }
    // §9.2: the back chain's clear nonce — one press kills
    // this cell's selection (and its existing-highlight
    // bar), handles and all.
    LaunchedEffect(clearSelectionSignal) {
        if (clearSelectionSignal > 0) {
            selection = null
            existingBar = null
            barDismissing = false
        }
    }
    val myHighlights = pageHighlights.filter { it.page == page }
    val myMark = searchMark?.takeIf { it.page == page }
    val trimStandsDown = settings.edgeTrim > 0f
    LaunchedEffect(page, myHighlights.isNotEmpty(), myMark != null) {
        if ((myHighlights.isNotEmpty() || myMark != null) &&
            pageGeometry == null
        ) {
            pageGeometry = session.pageGeometry(page)
        }
    }
    // C3: the trim stand-down bites in this cell — the book
    // has Pages highlights this page would draw, and the
    // trim hides them: the surface's line is owed (once).
    LaunchedEffect(page, trimStandsDown, myHighlights.isNotEmpty()) {
        if (trimStandsDown && myHighlights.isNotEmpty()) {
            onTrimStandDown()
        }
    }
    // C4: the selection report — set at activation, cleared
    // at every clear site (the dismiss paths set [selection]
    // null; leaving the settled set clears below; dispose
    // reports in the cell's DisposableEffect). The report
    // covers the existing-highlight bar too (re-check
    // Finding 1): an open bar is a selection layer the back
    // chain must see, exactly as the continuous body's
    // registration arms it — the clear nonce above already
    // clears [existingBar], so the chain's clear route
    // works once the layer is reported.
    val selectionLayerOpen = selection != null || existingBar != null
    LaunchedEffect(selectionLayerOpen) {
        onSelectionActivity(page, selectionLayerOpen)
    }
    // Code 119: the entry-shield report — the pager's
    // settle effect stands down while an entry press is
    // pending or a selection drag is live on this page
    // (a stationary hold reads as not-scrolling, so the
    // settle's own gate cannot see it).
    val entryGestureActive =
        entryPending.value || selectionDragActive.value
    LaunchedEffect(entryGestureActive) {
        onEntryActivity(page, entryGestureActive)
    }
    // C4 clear site — page change: a cell that leaves the
    // settled set drops its selection and its dialogs.
    LaunchedEffect(isSettledPage) {
        if (!isSettledPage) {
            selection = null
            existingBar = null
            barDismissing = false
            dialogState.clearAll()
        }
    }
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
            // MR-2b (C4 clear site — surface dispose): the
            // selection report dies with the cell, so the
            // pager's derivation can never strand on it.
            onSelectionActivity(page, false)
            // Code 119: the entry-shield report dies with
            // the cell too, on the same rule.
            onEntryActivity(page, false)
            // SelectionBar (Critic F1): the dialog report
            // dies with the cell too, on the same rule.
            onDialogActivity(page, false)
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
    // Code 118 (S6 flicker, CONVICTION-S6 claim A): the
    // DISPLAYED bitmaps are keyed on the page ONLY — never on
    // renderWidthPx. They used to be remember(page,
    // renderWidthPx): when the async size fill landed, the
    // width re-keyed (provisional → true slot width), both
    // states reset to null, and a page that had ALREADY
    // landed dropped back to the placeholder for the whole
    // re-render (~150 ms). Now a width change only re-runs
    // the render effect below (it stays keyed on
    // renderWidthPx); the landed bitmap stays on screen —
    // same page, same aspect, drawn at its own size — until
    // the render at the new width swaps in atomically.
    var previewBitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
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
                // Code 117 §11.1: the press-drag entry
                // recogniser (replaces detectTapGestures'
                // onLongPress; detectTapGestures remains
                // below for onDoubleTap only). The recogniser
                // KEEPS the pointer after the long-press
                // fires: the entry word-snaps through
                // pdfSelectionAt, then the same motion
                // extends the selection character-level
                // (§11.2) until the up. From the firing
                // onward it consumes every change of its
                // pointer — consumption, not duration, is
                // what protects the split double-tap detector
                // (foundation's waitForUpOrCancellation
                // returns null for a consumed up). A scroll
                // can still claim the gesture first:
                // awaitLongPressOrCancellation returns null
                // when it does, and no entry happens.
                // There is no onSelectionDragActive callback
                // (§11.1): the recogniser writes the cell's
                // selectionDragActive state directly, as the
                // overlay's handle drags do.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Code 119 (CONVICTION-CODE118-PDF-SELECT
                    // §2): the entry shield — set before the
                    // long-press wait, cleared in the finally
                    // below on EVERY exit (fire, fail, drain,
                    // cancel), so the flag can never stick.
                    // While it is up the transform loop skips
                    // single-pointer consumption (its consume
                    // block) and the pager's settle effect
                    // stands down (the onEntryActivity report).
                    entryPending.value = true
                    try {
                        val longPress = awaitLongPressOrCancellation(down.id)
                            ?: return@awaitEachGesture
                        // MR-2b (Revision 1, C3): under an edge
                        // trim the entry stands down — the press
                        // reports the stand-down and no drag
                        // begins (the branch is unchanged).
                        if (trimStandsDown) {
                            onTrimStandDown()
                            longPress.consume()
                            drainPdfEntryGesture(down.id)
                            return@awaitEachGesture
                        }
                        longPress.consume()
                        if (cellSizePx == IntSize.Zero) {
                            drainPdfEntryGesture(down.id)
                            return@awaitEachGesture
                        }
                        val slotBitmap = bitmap ?: previewBitmap
                        val points = session.pagePointsSizePeek(page)
                        if (slotBitmap == null || points == null) {
                            drainPdfEntryGesture(down.id)
                            return@awaitEachGesture
                        }
                        // The inversion (MR-2b's maths,
                        // unchanged): offsets arrive
                        // POST-transform (this box's screen
                        // space); invert the centre-anchored
                        // layer transform FIRST (screen =
                        // C + (s − C)·z + pan), then the fitted
                        // rect takes layer points to page points.
                        // Null when the point falls outside the
                        // displayed page rect.
                        fun pagePointOf(
                            screen: Offset
                        ): Pair<Float, Float>? {
                            val centre = Offset(
                                cellSizePx.width / 2f,
                                cellSizePx.height / 2f
                            )
                            val z = zoomState.zoom
                            val local = if (z != 0f) {
                                centre + (screen - centre -
                                    zoomState.pan) / z
                            } else {
                                screen
                            }
                            val (dispW, dispH) = pdfDisplayedSizePx(
                                slotBitmap.width, slotBitmap.height,
                                cellSizePx.width, cellSizePx.height,
                                settings.fitMode
                            )
                            if (dispW <= 0f || dispH <= 0f) return null
                            val originX = (cellSizePx.width - dispW) / 2f
                            val originY = (cellSizePx.height - dispH) / 2f
                            if (local.x !in originX..(originX + dispW) ||
                                local.y !in originY..(originY + dispH)
                            ) {
                                return null
                            }
                            return ((local.x - originX) *
                                points.first / dispW) to
                                ((local.y - originY) *
                                    points.second / dispH)
                        }
                        // The geometry: the cell's copy if held,
                        // else suspend for it — still holding the
                        // pointer and tracking its latest
                        // position; if the pointer lifts or
                        // cancels before the geometry arrives,
                        // the entry abandons silently (§11.5's
                        // rule, shared by both entries).
                        var latestPosition = longPress.position
                        var geometry = pageGeometry
                        if (geometry == null) {
                            val holder = object {
                                var value: PageCharGeometry? = null
                                var done = false
                            }
                            val fetch = cellScope.launch {
                                holder.value = session.pageGeometry(page)
                                if (holder.value != null) {
                                    pageGeometry = holder.value
                                }
                                holder.done = true
                            }
                            var lifted = false
                            while (!holder.done && !lifted) {
                                val event = awaitPointerEvent()
                                val change = event.changes
                                    .firstOrNull { it.id == down.id }
                                    ?: continue
                                change.consume()
                                if (change.pressed) {
                                    latestPosition = change.position
                                } else {
                                    lifted = true
                                }
                            }
                            if (lifted) {
                                fetch.cancel()
                                return@awaitEachGesture
                            }
                            geometry = holder.value
                            if (geometry == null) {
                                drainPdfEntryGesture(down.id)
                                return@awaitEachGesture
                            }
                        }
                        // Code 119 (CONVICTION-CODE118-PDF-SELECT
                        // §5): the press point is read AFTER the
                        // geometry wait, from the finger's latest
                        // position — the wait already tracks it.
                        // Reading it from the long-press position
                        // before the wait pinned the entry word
                        // wherever the finger STARTED, so a moved
                        // finger folded an unexpected word into a
                        // cold page's first selection. (On a warm
                        // page no wait runs and latestPosition IS
                        // the long-press position — unchanged.)
                        val pressPoint = pagePointOf(latestPosition)
                        if (pressPoint == null) {
                            drainPdfEntryGesture(down.id)
                            return@awaitEachGesture
                        }
                        val geo = geometry!!
                        // §9.3: a new selection entry cancels a
                        // fade still running.
                        barDismissing = false
                        val entered = pdfSelectionAt(
                            geo, pressPoint.first, pressPoint.second
                        )
                        if (entered == null) {
                            drainPdfEntryGesture(down.id)
                            return@awaitEachGesture
                        }
                        selection = entered
                        selectionDragActive.value = true
                        dragRangeState.value =
                            entered.start..entered.end - 1
                        val entryLineIndex = PdfLineIndex(geo)
                        var anchor: Int? = null
                        fun extend(screen: Offset) {
                            val point = pagePointOf(screen) ?: return
                            val index = entryLineIndex.charIndexAt(
                                point.first, point.second
                            ) ?: pdfCharIndexAt(
                                geo, point.first, point.second
                            ) ?: return
                            val (range, newAnchor) = pdfEntryDragRange(
                                entered.start, entered.end, anchor, index
                            )
                            anchor = newAnchor
                            dragRangeState.value = range
                        }
                        try {
                            if (latestPosition != longPress.position) {
                                extend(latestPosition)
                            }
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes
                                    .firstOrNull { it.id == down.id }
                                    ?: continue
                                change.consume()
                                if (!change.pressed) break
                                extend(change.position)
                            }
                        } finally {
                            // The settle (R2.3's rule): the one
                            // write of the settled selection (and
                            // its text) per drag; the range
                            // stands — no rollback.
                            val settled = dragRangeState.value
                            if (settled != null) {
                                entered.start = settled.first
                                entered.end = settled.last + 1
                                dragRangeState.value = null
                            }
                            selectionDragActive.value = false
                        }
                    } finally {
                        entryPending.value = false
                    }
                }
            }
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
                                // MR-2b (Revision 1, C4): the ONE
                                // narrow guard this loop gains —
                                // it applies NOTHING to an
                                // already-consumed change (a
                                // selection handle drag inside
                                // the layer consumes from its
                                // down), and nothing while a
                                // selection drag is active on
                                // this page. No other behaviour
                                // of the loop changes: the claim
                                // edges, the settle and the
                                // end-of-gesture consume below
                                // are exactly as shipped.
                                val selectionBlocksTransform =
                                    selectionDragActive.value ||
                                        event.changes.any {
                                            it.isConsumed &&
                                                it.positionChanged()
                                        }
                                if (!selectionBlocksTransform &&
                                    (zoomChange != 1f ||
                                        panChange != Offset.Zero)
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
                                // Code 119 (CONVICTION-CODE118-
                                // PDF-SELECT §2): while a
                                // selection entry is pending on
                                // this cell, single-pointer
                                // changes are NOT consumed —
                                // consumption is what kills the
                                // entry's long-press wait
                                // (foundation reads isConsumed
                                // in the wait loop), so a
                                // zoomed hold died on ≥1 px of
                                // drift. A second pointer (a
                                // real pinch, pressed >= 2)
                                // consumes exactly as shipped:
                                // the pinch flips transform
                                // mode above and proceeds.
                                event.changes.forEach { change ->
                                    if (change.positionChanged() &&
                                        !(entryPending.value &&
                                            pressed < 2)
                                    ) {
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
                // MR-2b: the geometry overlay — inside the
                // layer, in the refine precedent's frame
                // (exactly over the centred displayed rect),
                // so it rides the zoom transform glued to the
                // text at every zoom. It stands down under an
                // edge trim (C3: the drawn page there is the
                // GROWN page, whose rect this frame does not
                // describe). The selection's own geometry
                // wins over the cell's drawing copy (B3).
                val overlayGeometry = selection?.geometry
                    ?: pageGeometry
                val overlayBitmap = current ?: preview
                if (!trimStandsDown && overlayGeometry != null &&
                    overlayBitmap != null &&
                    cellSizePx != IntSize.Zero &&
                    (selection != null || myHighlights.isNotEmpty() ||
                        myMark != null)
                ) {
                    val points = session.pagePointsSizePeek(page)
                    if (points != null) {
                        val (dispW, dispH) = pdfDisplayedSizePx(
                            overlayBitmap.width, overlayBitmap.height,
                            cellSizePx.width, cellSizePx.height,
                            settings.fitMode
                        )
                        PdfPageGeometryOverlay(
                            geometry = overlayGeometry,
                            pageWidthPt = points.first.toFloat(),
                            pageHeightPt = points.second.toFloat(),
                            highlights = myHighlights,
                            searchMark = myMark,
                            selection = selection,
                            onHighlightTap = { highlight ->
                                // The file's arbitration
                                // pattern: a handled tap
                                // cancels the pending confirmed
                                // single, so the shipped tap
                                // action never also fires.
                                cancelPendingSingleTap()
                                // §7.4: the tap opens the bar
                                // in its existing-highlight
                                // state (the dialog menu is
                                // gone) — a fresh entry, so
                                // any running fade cancels.
                                barDismissing = false
                                barState = SelectionBarState(
                                    existingHighlight = true,
                                    activeTintArgb = highlight.tintArgb
                                )
                                existingBar = highlight
                            },
                            onTapAway = {
                                cancelPendingSingleTap()
                                selection = null
                                existingBar = null
                                barDismissing = false
                            },
                            barOpen = existingBar != null,
                            onDragStateChange = { active ->
                                selectionDragActive.value = active
                            },
                            dragRangeState = dragRangeState,
                            // §13 / D2: read at use time
                            // from the zoom state holder.
                            zoomProvider = { zoomState.zoom },
                            modifier = Modifier
                                .offset(
                                    x = with(density) {
                                        ((cellSizePx.width - dispW) / 2f)
                                            .toDp()
                                    },
                                    y = with(density) {
                                        ((cellSizePx.height - dispH) / 2f)
                                            .toDp()
                                    }
                                )
                                .width(with(density) { dispW.toDp() })
                                .height(with(density) { dispH.toDp() })
                                .align(Alignment.TopStart)
                        )
                    }
                }
            }
            // SelectionBar (MR-4 R1's seat, kept): the bar —
            // a child of the cell's OUTER box, outside the
            // zoom layer (it never scales), seated AT the
            // selection by pdfBarSeatOffset: the selection's
            // display rects run through the overlay frame
            // offset and the centre-anchored zoom transform
            // (the entry inversion's own forward map). The
            // seat is computed in composition from the settled
            // range + zoom/pan state, so it re-seats at entry,
            // at each drag release, and on every zoom/pan
            // change while the selection lives (zoom never
            // dismisses it — MR-2b's rule, kept). Without seat
            // inputs (no bitmap/points yet) the bar pins to
            // the cell's bottom edge, the seat's own fallback.
            // §7.4: in the existing-highlight state the seat
            // derives from the highlight's own char range
            // through the same pdfSeatInputs.
            val liveSelection = selection
            val existingSelection = existingBar?.let { highlight ->
                val geometry = pageGeometry
                val start = highlight.charStart
                val end = highlight.charEnd
                if (geometry != null && start != null && end != null) {
                    PdfPageSelection(geometry, start, end)
                } else {
                    null
                }
            }
            val barSelection = liveSelection ?: existingSelection
            if (barSelection != null) {
                val seatBitmap = current ?: preview
                val seatPoints = session.pagePointsSizePeek(page)
                val seatInputs = if (seatBitmap != null &&
                    seatPoints != null &&
                    cellSizePx != IntSize.Zero
                ) {
                    val (dispW, dispH) = pdfDisplayedSizePx(
                        seatBitmap.width, seatBitmap.height,
                        cellSizePx.width, cellSizePx.height,
                        settings.fitMode
                    )
                    if (dispW > 0f && dispH > 0f) {
                        val originX = (cellSizePx.width - dispW) / 2f
                        val originY = (cellSizePx.height - dispH) / 2f
                        val centre = Offset(
                            cellSizePx.width / 2f,
                            cellSizePx.height / 2f
                        )
                        val z = zoomState.zoom
                        val sx = dispW / seatPoints.first
                        val sy = dispH / seatPoints.second
                        pdfSeatInputs(
                            geometry = barSelection.geometry,
                            start = barSelection.start,
                            end = barSelection.end,
                            mapPoint = { xPt, yPt ->
                                val layer = Offset(
                                    originX + xPt * sx,
                                    originY + yPt * sy
                                )
                                centre + (layer - centre) * z +
                                    zoomState.pan
                            },
                            heightScale = sy * z,
                            // §13: the end handle is now the
                            // teardrop — its full drop (9 dp
                            // tip + 22 dp body) below the end
                            // char's corner, so a below-seat
                            // clears the drawn handle.
                            dotRadiusPx = with(density) {
                                31.dp.toPx()
                            } * z
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
                val gapPx = with(density) { 15.dp.toPx() }
                val edgePx = with(density) { 8.dp.toPx() }
                // Code 117 §14: the horizontal slack is
                // 16 dp at both edges.
                val marginPx = with(density) { 16.dp.toPx() }
                // Code 117 §14: the bar's measured size is
                // keyed by this episode (page + the
                // selection's start/end pair) and resets to
                // Zero when the key changes — a previous
                // page's or selection's size can never seat
                // this episode's bar.
                var pillSize by remember(
                    page, barSelection.start, barSelection.end
                ) {
                    mutableStateOf(IntSize.Zero)
                }
                // Revision 2 D1: while the keyed size is
                // Zero NO seat target is computed — the
                // animated target holds the last placed seat
                // and the bar is held invisible (the alpha
                // gate on its graphicsLayer below). The bar
                // IS composed from the episode's first
                // frame; the seat motion's fade covers its
                // appearance once the first measure lands.
                val seatTarget = if (pillSize == IntSize.Zero) {
                    seatHold
                } else if (seatInputs != null) {
                    val computed = pdfBarSeatOffset(
                        surfaceW = cellSizePx.width.toFloat(),
                        surfaceH = cellSizePx.height.toFloat(),
                        barW = pillSize.width.toFloat(),
                        barH = pillSize.height.toFloat(),
                        inputs = seatInputs,
                        gapPx = gapPx,
                        edgePx = edgePx,
                        marginPx = marginPx
                    )
                    IntOffset(
                        computed.x.roundToInt(),
                        computed.y.roundToInt()
                    )
                } else {
                    // Measured but unmappable: bottom pin,
                    // centred — the seat's edge fallback.
                    IntOffset(
                        ((cellSizePx.width - pillSize.width) / 2f)
                            .coerceIn(
                                marginPx,
                                maxOf(
                                    marginPx,
                                    cellSizePx.width -
                                        pillSize.width - marginPx
                                )
                            )
                            .roundToInt(),
                        (cellSizePx.height - edgePx - pillSize.height)
                            .coerceAtLeast(0f)
                            .roundToInt()
                    )
                }
                LaunchedEffect(seatTarget) {
                    if (pillSize != IntSize.Zero) {
                        seatHold = seatTarget
                    }
                }
                // §9.4 (as revised): the seat MOVES on the
                // Spatial spring — and the animated value is
                // clamped per frame inside the offset lambda
                // to the same bounds pdfBarSeatOffset clamps
                // its target to (Critic F7), so the spring's
                // overshoot can never carry the bar off the
                // cell's edge.
                val animatedSeat by animateIntOffsetAsState(
                    targetValue = seatTarget,
                    animationSpec = if (motionEnabled) {
                        SpringMotionEngine.Spatial.toSpec()
                    } else {
                        snap()
                    },
                    label = "pdfPagedBarSeat"
                )
                val seatMotion = rememberSelectionBarSeatMotion(
                    visible = true,
                    dismissing = barDismissing,
                    motionEnabled = motionEnabled
                )
                SelectionBar(
                    state = barState,
                    onStateChange = { barState = it },
                    moreItems = PdfSelectionMoreItems,
                    callbacks = SelectionBarCallbacks(
                        onAction = { action ->
                            if (!barDismissing) {
                                when (action) {
                                    SelectionBarAction.COPY -> {
                                        clipboard.setText(
                                            AnnotatedString(
                                                barSelection.text
                                            )
                                        )
                                        barDismissing = true
                                    }
                                    SelectionBarAction.HIGHLIGHT -> {
                                        // Colour-row hosts never
                                        // route Highlight here
                                        // (the bar opens the row
                                        // instead); this arm is
                                        // the capability-off
                                        // fallback — apply with
                                        // the active tint.
                                        onAddPageHighlight(
                                            StoredSelectionHighlight(
                                                id = UUID.randomUUID()
                                                    .toString(),
                                                page = page,
                                                charStart =
                                                    barSelection.start,
                                                charEnd =
                                                    barSelection.end,
                                                selectedText =
                                                    barSelection.text,
                                                tintArgb =
                                                    barState.activeTintArgb,
                                                createdAtEpochMillis =
                                                    System
                                                        .currentTimeMillis()
                                            )
                                        )
                                        barDismissing = true
                                    }
                                    SelectionBarAction.NOTE -> {
                                        val existing = existingBar
                                        if (existing != null) {
                                            // §7.4: the note
                                            // editor over the
                                            // stored highlight;
                                            // the bar closes.
                                            dialogState.editing =
                                                existing
                                            barDismissing = true
                                        } else {
                                            // The shipped draft
                                            // flow: the editor
                                            // opens over the
                                            // live selection,
                                            // which stays until
                                            // save/cancel.
                                            dialogState.noteDraft =
                                                barSelection
                                        }
                                    }
                                    SelectionBarAction.DICTIONARY -> {
                                        val word = barSelection.text
                                            .trim()
                                            .split(Regex("\\s+"))
                                            .firstOrNull().orEmpty()
                                        if (word.isNotBlank()) {
                                            cellScope.launch {
                                                val definition =
                                                    runCatching {
                                                        app.xreader.ui
                                                            .library
                                                            .DictionaryLookup
                                                            .lookup(
                                                                cellContext
                                                                    .filesDir,
                                                                word
                                                            )
                                                    }.getOrNull()
                                                dialogState.dictionary =
                                                    word to definition
                                            }
                                        }
                                        barDismissing = true
                                    }
                                    SelectionBarAction.TRANSLATE -> {
                                        onSelectionTranslate(
                                            barSelection.text
                                        )
                                        barDismissing = true
                                    }
                                    SelectionBarAction.ASK_AI -> {
                                        onSelectionAskAi(
                                            barSelection.text
                                        )
                                        barDismissing = true
                                    }
                                    SelectionBarAction.ILLUSTRATE -> {
                                        onSelectionIllustrate(
                                            barSelection.text
                                        )
                                        barDismissing = true
                                    }
                                    SelectionBarAction.SHARE -> {
                                        val intent = Intent(
                                            Intent.ACTION_SEND
                                        ).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                barSelection.text
                                            )
                                        }
                                        cellContext.startActivity(
                                            Intent.createChooser(
                                                intent, null
                                            )
                                        )
                                        barDismissing = true
                                    }
                                    else -> Unit
                                }
                            }
                        },
                        onApplyHighlight = { tintArgb, _ ->
                            if (!barDismissing) {
                                val existing = existingBar
                                if (existing != null) {
                                    // §7.4: re-tint the stored
                                    // highlight (§7.3's store
                                    // door).
                                    onUpdatePageTint(
                                        existing, tintArgb
                                    )
                                } else {
                                    onAddPageHighlight(
                                        StoredSelectionHighlight(
                                            id = UUID.randomUUID()
                                                .toString(),
                                            page = page,
                                            charStart =
                                                barSelection.start,
                                            charEnd = barSelection.end,
                                            selectedText =
                                                barSelection.text,
                                            tintArgb = tintArgb,
                                            createdAtEpochMillis =
                                                System
                                                    .currentTimeMillis()
                                        )
                                    )
                                }
                                barDismissing = true
                            }
                        },
                        onRemoveHighlight = {
                            if (!barDismissing) {
                                existingBar?.let {
                                    onRemovePageHighlight(it)
                                }
                                barDismissing = true
                            }
                        },
                        onDismiss = {
                            selection = null
                            existingBar = null
                            barDismissing = false
                        }
                    ),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            val edge = edgePx.roundToInt()
                            // §14: the per-frame clamp honours
                            // the same 16 dp horizontal slack
                            // as the seat target.
                            val margin = marginPx.roundToInt()
                            IntOffset(
                                animatedSeat.x.coerceIn(
                                    margin,
                                    maxOf(
                                        margin,
                                        cellSizePx.width -
                                            pillSize.width - margin
                                    )
                                ),
                                animatedSeat.y.coerceIn(
                                    0,
                                    maxOf(
                                        0,
                                        cellSizePx.height -
                                            pillSize.height - edge
                                    )
                                )
                            )
                        }
                        .widthIn(
                            max = with(density) {
                                (cellSizePx.width - 16.dp.toPx())
                                    .coerceAtLeast(0f).toDp()
                            }
                        )
                        .onSizeChanged { pillSize = it }
                        // §12.1: a clean tap landing on the
                        // bar cancels the pending single tap,
                        // so a bar tap never turns the page.
                        .pointerInput(page) {
                            pdfBarTapWatcher {
                                cancelPendingSingleTap()
                            }
                        }
                        .graphicsLayer {
                            // Revision 2 D1: held invisible
                            // until this episode's first
                            // measure lands.
                            alpha = if (pillSize == IntSize.Zero) {
                                0f
                            } else {
                                seatMotion.alpha
                            }
                            scaleX = seatMotion.scale
                            scaleY = seatMotion.scale
                        }
                )
            }
            // Code 120 fix pass (recheck L5): the note-draft
            // save's highlight tint — the design-aware default
            // (under Nocturne the shipped constant, so the
            // stored value there is unchanged).
            val noteDefaultTintArgb = defaultHighlightTintArgb()
            PdfSelectionDialogs(
                state = dialogState,
                onNoteDraftSave = { note ->
                    val draft = dialogState.noteDraft
                    if (draft != null) {
                        onAddPageHighlight(
                            StoredSelectionHighlight(
                                id = UUID.randomUUID().toString(),
                                page = page,
                                charStart = draft.start,
                                charEnd = draft.end,
                                selectedText = draft.text,
                                note = note,
                                tintArgb = noteDefaultTintArgb,
                                createdAtEpochMillis =
                                    System.currentTimeMillis()
                            )
                        )
                    }
                    dialogState.noteDraft = null
                    selection = null
                },
                onEditNoteSave = { note ->
                    val editing = dialogState.editing
                    if (editing != null) {
                        onUpdatePageNote(editing, note)
                    }
                    dialogState.editing = null
                }
            )
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
    onResolvedFilter: (Int, PdfPageFilter) -> Unit = { _, _ -> },
    // MR-2b: the Pages-form selection seam, as in the paged
    // body — plus the visible-page report the surface's
    // banner dismissal keys on.
    pageHighlights: List<StoredSelectionHighlight> = emptyList(),
    searchMark: PdfSearchMark? = null,
    onAddPageHighlight: (StoredSelectionHighlight) -> Unit = {},
    onRemovePageHighlight: (StoredSelectionHighlight) -> Unit = {},
    onUpdatePageNote: (StoredSelectionHighlight, String) -> Unit =
        { _, _ -> },
    // SelectionBar: threaded from the surface (see
    // PdfPagesSurface's own params).
    onUpdatePageTint: (StoredSelectionHighlight, Int) -> Unit =
        { _, _ -> },
    onSelectionTranslate: (String) -> Unit = {},
    onSelectionAskAi: (String) -> Unit = {},
    // Code 124 (A7): Illustrate paints exactly the selection.
    onSelectionIllustrate: (String) -> Unit = {},
    onSelectionActiveChanged: (Boolean) -> Unit = {},
    onDialogOpenChanged: (Boolean) -> Unit = {},
    clearSelectionSignal: Int = 0,
    motionEnabled: Boolean = true,
    onPageVisible: (Int) -> Unit = {}
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0)
    )
    // MR-2b (Revision 1, C5): the pages holding a live
    // selection in this body — while any does, the list's
    // own scroll stands down (userScrollEnabled below) and
    // the frame observer's single-finger pan branch with it.
    var selectionPages by remember {
        mutableStateOf<Set<Int>>(emptySet())
    }
    // MR-4 (R1): the pages whose selection HANDLE is being
    // dragged right now — the items' overlay drag callbacks
    // report here (the shipped body wired that callback to
    // a no-op). It rides the same C5 channel: a drag is a
    // selection activity, so the lock below reads both sets.
    var selectionDragPages by remember {
        mutableStateOf<Set<Int>>(emptySet())
    }
    val bodySelectionActive = selectionPages.isNotEmpty() ||
        selectionDragPages.isNotEmpty()
    // MR-4 (R1): the surface-level action bar's registration
    // (the item owning the live selection) + its measured
    // size, for the seat.
    var barRegistration by remember {
        mutableStateOf<PdfContinuousBarRegistration?>(null)
    }
    // Code 117 §14: the measured size is keyed by the
    // registration's episode (its page + the selection's
    // start/end pair) and resets to Zero when the key
    // changes — the paged seat's rule, this body's seat.
    var surfacePillSize by remember(
        barRegistration?.let {
            it.page to (it.selection.start to it.selection.end)
        }
    ) {
        mutableStateOf(IntSize.Zero)
    }
    // Revision 2 D1: the last placed seat target — held
    // (bar invisible) while an episode is unmeasured.
    var surfaceSeatHold by remember {
        mutableStateOf(IntOffset.Zero)
    }
    // SelectionBar (§9.2 as revised): the back chain's
    // reports for this body — the ARMED aggregate is a live
    // registration whose item has no follow-on dialog open
    // (the registration's dialogOpen state is read here, in
    // composition, so this body recomposes with it); the
    // clear nonce routes through the registration's
    // item-built requestClear (Readiness F4).
    val registrationDialogOpen =
        barRegistration?.dialogOpen?.value ?: false
    LaunchedEffect(barRegistration != null, registrationDialogOpen) {
        onSelectionActiveChanged(
            barRegistration != null && !registrationDialogOpen
        )
        onDialogOpenChanged(registrationDialogOpen)
    }
    LaunchedEffect(clearSelectionSignal) {
        if (clearSelectionSignal > 0) {
            barRegistration?.requestClear?.invoke()
        }
    }
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
                jump.coerceIn(0, (session.sectionCount - 1).coerceAtLeast(0))
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
            // MR-2b: the surface's banner dismissal keys on
            // the visible page here too (the paged body keys
            // on its currentPage; this body has none).
            onPageVisible(page)
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
                                surfaceZoom.isZoomed &&
                                // MR-2b (C5): the single-finger
                                // pan branch stands down while a
                                // selection is active in this
                                // body — it consumes nothing, so
                                // without the gate it would pan
                                // the surface under a handle drag.
                                selectionPages.isEmpty()
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
            // MR-2b (C5): the list's scroll stands down while
            // a selection drag — a selection's whole life, the
            // C4-analogue reading for this body — is active in
            // it; restored at the same clear sites.
            userScrollEnabled = !bodySelectionActive,
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
                    // MR-2b (Revision 1, C5): the item's own
                    // selection host — a per-item overlay in
                    // the item's own composable (this body has
                    // no per-page layer; the LazyColumn's
                    // graphicsLayer wraps the whole list, so
                    // the item's local coordinates arrive
                    // already un-transformed by Compose) and a
                    // per-item long-press entry (watch-only,
                    // Initial pass — the surface's detectors
                    // keep their events). No trim stand-down
                    // exists in this body: it has no edgeTrim.
                    val itemContext = LocalContext.current
                    val itemClipboard = LocalClipboardManager.current
                    var itemSelection by remember(page) {
                        mutableStateOf<PdfPageSelection?>(null)
                    }
                    var itemGeometry by remember(page) {
                        mutableStateOf<PageCharGeometry?>(null)
                    }
                    var itemSizePx by remember {
                        mutableStateOf(IntSize.Zero)
                    }
                    // Code 117 (§11.5): the live drag range,
                    // hoisted so the entry recogniser writes
                    // through the channel the item's overlay
                    // renders from (as §11.1's paged cell).
                    val itemDragRangeState = remember(page) {
                        mutableStateOf<IntRange?>(null)
                    }
                    val itemDialogs = remember(page) {
                        PdfSelectionDialogState()
                    }
                    // SelectionBar (Critic F9): the bar's
                    // state objects — created HERE, in the
                    // owning item, and carried in the
                    // registration as MutableState so the
                    // surface host reads/writes them directly
                    // (no value snapshot can go stale).
                    val itemBarState = remember(page) {
                        mutableStateOf(SelectionBarState())
                    }
                    val itemBarDismissing = remember(page) {
                        mutableStateOf(false)
                    }
                    val itemDialogOpen = remember(page) {
                        mutableStateOf(false)
                    }
                    // §7.4: the stored highlight whose tap
                    // opened the bar in its existing state.
                    var existingBarHighlight by remember(page) {
                        mutableStateOf<StoredSelectionHighlight?>(null)
                    }
                    // §9.3: the delayed clear — an action's
                    // effect fires at tap; the item's
                    // selection state clears 150ms later,
                    // which unregisters the bar (the effect
                    // below). A new entry cancels the fade by
                    // setting the flag false first.
                    LaunchedEffect(itemBarDismissing.value) {
                        if (itemBarDismissing.value) {
                            delay(150)
                            itemSelection = null
                            existingBarHighlight = null
                            itemBarDismissing.value = false
                        }
                    }
                    // Critic F1: this item's follow-on-dialog
                    // report, written into the registration's
                    // dialogOpen state.
                    val itemAnyDialogOpen =
                        itemDialogs.noteDraft != null ||
                            itemDialogs.editing != null ||
                            itemDialogs.dictionary != null
                    LaunchedEffect(itemAnyDialogOpen) {
                        itemDialogOpen.value = itemAnyDialogOpen
                    }
                    val itemHighlights = pageHighlights
                        .filter { it.page == page }
                    val itemMark = searchMark
                        ?.takeIf { it.page == page }
                    LaunchedEffect(
                        page, itemHighlights.isNotEmpty(),
                        itemMark != null
                    ) {
                        if ((itemHighlights.isNotEmpty() ||
                                itemMark != null) &&
                            itemGeometry == null
                        ) {
                            itemGeometry = session.pageGeometry(page)
                        }
                    }
                    // Code 124 (A9, re-check fix): a fresh
                    // selection that sits ON a stored highlight
                    // of this page opens the bar in that
                    // highlight's existing state — the paged
                    // body's rule, mirrored here (this body
                    // never derived it, so over a live
                    // selection its Remove was a no-op and its
                    // colour row stacked a second highlight).
                    // The registration effect below keys on
                    // existingBarHighlight, so the seeded
                    // record flows into its Remove / re-tint
                    // closures.
                    LaunchedEffect(itemSelection) {
                        val sel = itemSelection
                        if (sel != null) {
                            val hit = itemHighlights
                                .firstOrNull { stored ->
                                    val cs = stored.charStart
                                    val ce = stored.charEnd
                                    cs != null && ce != null &&
                                        sel.start >= cs &&
                                        sel.end <= ce
                                }
                            if (hit != null &&
                                existingBarHighlight != hit
                            ) {
                                existingBarHighlight = hit
                                itemBarState.value =
                                    SelectionBarState(
                                        existingHighlight = true,
                                        activeTintArgb = hit.tintArgb
                                    )
                            }
                        }
                    }
                    // The body's scroll lock (C5): report the
                    // selection's life; dispose clears it.
                    LaunchedEffect(itemSelection != null) {
                        selectionPages = if (itemSelection != null) {
                            selectionPages + page
                        } else {
                            selectionPages - page
                        }
                    }
                    // MR-4 (R1) + SelectionBar: register the
                    // live selection (or the §7.4
                    // existing-highlight bar) with the
                    // surface-level bar (the bar moved out of
                    // the item — it seats in the surface's
                    // outermost box now). The closures resolve
                    // against the item's CURRENT state at call
                    // time and drive this item's own
                    // dialogs/clipboard, exactly the shipped
                    // item-local pill's actions; the dismissal
                    // itself is the host's §9.3 mechanism (the
                    // host sets itemBarDismissing — the delayed
                    // clear above then unregisters).
                    LaunchedEffect(
                        itemSelection, existingBarHighlight, itemGeometry
                    ) {
                        val existing = existingBarHighlight
                        val sel = itemSelection ?: existing?.let { hl ->
                            val g = itemGeometry
                            val s = hl.charStart
                            val e = hl.charEnd
                            if (g != null && s != null && e != null) {
                                PdfPageSelection(g, s, e)
                            } else {
                                null
                            }
                        }
                        if (sel != null) {
                            barRegistration =
                                PdfContinuousBarRegistration(
                                    page = page,
                                    selection = sel,
                                    barState = itemBarState,
                                    dismissing = itemBarDismissing,
                                    dialogOpen = itemDialogOpen,
                                    requestClear = {
                                        itemBarDismissing.value = false
                                        itemSelection = null
                                        existingBarHighlight = null
                                    },
                                    onCopy = {
                                        val text = existing
                                            ?.selectedText
                                            ?: itemSelection?.text
                                        if (text != null) {
                                            itemClipboard.setText(
                                                AnnotatedString(text)
                                            )
                                        }
                                    },
                                    onHighlight = {
                                        // Capability-off fallback
                                        // (the bar opens the
                                        // colour row instead on
                                        // this host).
                                        itemSelection?.let { live ->
                                            onAddPageHighlight(
                                                StoredSelectionHighlight(
                                                    id = UUID.randomUUID()
                                                        .toString(),
                                                    page = page,
                                                    charStart = live.start,
                                                    charEnd = live.end,
                                                    selectedText =
                                                        live.text,
                                                    tintArgb =
                                                        itemBarState.value
                                                            .activeTintArgb,
                                                    createdAtEpochMillis =
                                                        System
                                                            .currentTimeMillis()
                                                )
                                            )
                                        }
                                    },
                                    onNote = {
                                        if (existing != null) {
                                            itemDialogs.editing =
                                                existing
                                        } else {
                                            itemSelection?.let { live ->
                                                itemDialogs.noteDraft =
                                                    live
                                            }
                                        }
                                    },
                                    onDictionary = {
                                        val word = (
                                            existing?.selectedText
                                                ?: itemSelection?.text
                                            )?.trim()
                                            ?.split(Regex("\\s+"))
                                            ?.firstOrNull().orEmpty()
                                        if (word.isNotBlank()) {
                                            scope.launch {
                                                val definition =
                                                    runCatching {
                                                        app.xreader.ui
                                                            .library
                                                            .DictionaryLookup
                                                            .lookup(
                                                                itemContext
                                                                    .filesDir,
                                                                word
                                                            )
                                                    }.getOrNull()
                                                itemDialogs.dictionary =
                                                    word to definition
                                            }
                                        }
                                    },
                                    onApplyHighlight = { tintArgb, _ ->
                                        if (existing != null) {
                                            onUpdatePageTint(
                                                existing, tintArgb
                                            )
                                        } else {
                                            itemSelection?.let { live ->
                                                onAddPageHighlight(
                                                    StoredSelectionHighlight(
                                                        id = UUID
                                                            .randomUUID()
                                                            .toString(),
                                                        page = page,
                                                        charStart =
                                                            live.start,
                                                        charEnd = live.end,
                                                        selectedText =
                                                            live.text,
                                                        tintArgb =
                                                            tintArgb,
                                                        createdAtEpochMillis =
                                                            System
                                                                .currentTimeMillis()
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    onRemoveHighlight = {
                                        if (existing != null) {
                                            onRemovePageHighlight(
                                                existing
                                            )
                                        }
                                    },
                                    onDismiss = {
                                        itemBarDismissing.value = false
                                        itemSelection = null
                                        existingBarHighlight = null
                                    }
                                )
                        } else {
                            if (barRegistration?.page == page) {
                                barRegistration = null
                            }
                            // §6.1's reset rule, this body's
                            // seat: a cleared bar starts clean
                            // next time.
                            itemBarState.value = SelectionBarState()
                        }
                    }
                    DisposableEffect(page) {
                        onDispose {
                            selectionPages = selectionPages - page
                            if (barRegistration?.page == page) {
                                barRegistration = null
                            }
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .onSizeChanged { itemSizePx = it }
                            .pointerInput(page) {
                                // Code 117 §11.5: the press-drag
                                // entry recogniser (replaces the
                                // watch-only Initial-pass
                                // pdfLongPressObserver). The
                                // same custom shape as the paged
                                // entry (§11.1): Main pass,
                                // awaitLongPressOrCancellation —
                                // null when the list's scroll
                                // claims the pointer first — then
                                // the pointer is KEPT: the entry
                                // word-snaps through
                                // pdfSelectionAt, the same
                                // motion extends the selection
                                // character-level (§11.2), and
                                // every change from the firing
                                // onward is consumed (the
                                // consumption duty of §11.1).
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false
                                    )
                                    val longPress =
                                        awaitLongPressOrCancellation(
                                            down.id
                                        ) ?: return@awaitEachGesture
                                    longPress.consume()
                                    val points =
                                        session.pagePointsSizePeek(page)
                                    if (points == null ||
                                        itemSizePx.width <= 0 ||
                                        itemSizePx.height <= 0
                                    ) {
                                        drainPdfEntryGesture(down.id)
                                        return@awaitEachGesture
                                    }
                                    // Item-local offsets map
                                    // straight to page points:
                                    // Compose delivers them
                                    // already un-scaled through
                                    // the surface's zoom layer
                                    // (the C5 note).
                                    fun pagePointOf(
                                        local: Offset
                                    ): Pair<Float, Float> =
                                        (local.x * points.first /
                                            itemSizePx.width) to
                                            (local.y * points.second /
                                                itemSizePx.height)
                                    // §11.5's async geometry
                                    // wait: suspend for the
                                    // geometry still holding
                                    // the pointer, tracking its
                                    // moves; abandon silently
                                    // if the pointer lifts or
                                    // cancels before it arrives.
                                    var latestPosition =
                                        longPress.position
                                    var geometry = itemGeometry
                                    if (geometry == null) {
                                        val holder = object {
                                            var value:
                                                PageCharGeometry? = null
                                            var done = false
                                        }
                                        val fetch = scope.launch {
                                            holder.value =
                                                session.pageGeometry(page)
                                            if (holder.value != null) {
                                                itemGeometry =
                                                    holder.value
                                            }
                                            holder.done = true
                                        }
                                        var lifted = false
                                        while (!holder.done && !lifted) {
                                            val event =
                                                awaitPointerEvent()
                                            val change = event.changes
                                                .firstOrNull {
                                                    it.id == down.id
                                                } ?: continue
                                            change.consume()
                                            if (change.pressed) {
                                                latestPosition =
                                                    change.position
                                            } else {
                                                lifted = true
                                            }
                                        }
                                        if (lifted) {
                                            fetch.cancel()
                                            return@awaitEachGesture
                                        }
                                        geometry = holder.value
                                        if (geometry == null) {
                                            drainPdfEntryGesture(down.id)
                                            return@awaitEachGesture
                                        }
                                    }
                                    // Code 119 (CONVICTION-CODE118-
                                    // PDF-SELECT §5): the press
                                    // point is read AFTER the
                                    // geometry wait, from the
                                    // finger's latest position —
                                    // the same re-read as the
                                    // paged entry. (No wait on a
                                    // warm page: latestPosition
                                    // IS the long-press position.)
                                    val pressPoint =
                                        pagePointOf(latestPosition)
                                    val geo = geometry!!
                                    // §9.3 + §6.1: a new entry
                                    // cancels a running fade,
                                    // supersedes any
                                    // existing-highlight bar,
                                    // and starts from a clean
                                    // bar state (today's entry
                                    // writes, kept).
                                    itemBarDismissing.value = false
                                    existingBarHighlight = null
                                    itemBarState.value =
                                        SelectionBarState()
                                    val entered = pdfSelectionAt(
                                        geo,
                                        pressPoint.first,
                                        pressPoint.second
                                    )
                                    if (entered == null) {
                                        drainPdfEntryGesture(down.id)
                                        return@awaitEachGesture
                                    }
                                    itemSelection = entered
                                    selectionDragPages =
                                        selectionDragPages + page
                                    itemDragRangeState.value =
                                        entered.start..entered.end - 1
                                    val entryLineIndex =
                                        PdfLineIndex(geo)
                                    var anchor: Int? = null
                                    fun extend(local: Offset) {
                                        val point = pagePointOf(local)
                                        val index = entryLineIndex
                                            .charIndexAt(
                                                point.first,
                                                point.second
                                            ) ?: pdfCharIndexAt(
                                                geo,
                                                point.first,
                                                point.second
                                            ) ?: return
                                        val (range, newAnchor) =
                                            pdfEntryDragRange(
                                                entered.start,
                                                entered.end,
                                                anchor,
                                                index
                                            )
                                        anchor = newAnchor
                                        itemDragRangeState.value =
                                            range
                                    }
                                    try {
                                        if (latestPosition !=
                                            longPress.position
                                        ) {
                                            extend(latestPosition)
                                        }
                                        while (true) {
                                            val event =
                                                awaitPointerEvent()
                                            val change = event.changes
                                                .firstOrNull {
                                                    it.id == down.id
                                                } ?: continue
                                            change.consume()
                                            if (!change.pressed) break
                                            extend(change.position)
                                        }
                                    } finally {
                                        // The settle (R2.3's
                                        // rule): the one write
                                        // of the settled
                                        // selection per drag;
                                        // the drag report
                                        // leaves through the
                                        // existing
                                        // selectionDragPages
                                        // channel.
                                        val settled =
                                            itemDragRangeState.value
                                        if (settled != null) {
                                            entered.start = settled.first
                                            entered.end =
                                                settled.last + 1
                                            itemDragRangeState.value =
                                                null
                                        }
                                        selectionDragPages =
                                            selectionDragPages - page
                                    }
                                }
                            }
                    ) {
                        Image(
                            bitmap = current.asImageBitmap(),
                            contentDescription =
                                session.displayLabelFor(page),
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                            colorFilter = itemDrawFilter
                        )
                        val overlayGeometry = itemSelection?.geometry
                            ?: itemGeometry
                        val points = session.pagePointsSizePeek(page)
                        if (overlayGeometry != null && points != null &&
                            (itemSelection != null ||
                                itemHighlights.isNotEmpty() ||
                                itemMark != null)
                        ) {
                            PdfPageGeometryOverlay(
                                geometry = overlayGeometry,
                                pageWidthPt = points.first.toFloat(),
                                pageHeightPt = points.second.toFloat(),
                                highlights = itemHighlights,
                                searchMark = itemMark,
                                selection = itemSelection,
                                onHighlightTap = { highlight ->
                                    // §7.4: the tap opens the
                                    // bar in its
                                    // existing-highlight state
                                    // (the dialog menu is gone).
                                    itemBarDismissing.value = false
                                    itemBarState.value =
                                        SelectionBarState(
                                            existingHighlight = true,
                                            activeTintArgb =
                                                highlight.tintArgb
                                        )
                                    existingBarHighlight = highlight
                                },
                                onTapAway = {
                                    itemSelection = null
                                    existingBarHighlight = null
                                    itemBarDismissing.value = false
                                },
                                barOpen = existingBarHighlight != null,
                                // MR-4 (R1): the drag report
                                // is wired now (it was a no-op
                                // in MR-2b) — into the body's
                                // C5 channel beside the
                                // selection's life report.
                                onDragStateChange = { active ->
                                    selectionDragPages = if (active) {
                                        selectionDragPages + page
                                    } else {
                                        selectionDragPages - page
                                    }
                                },
                                dragRangeState = itemDragRangeState,
                                // §13 / D2: the item sits inside
                                // the surface's zoom layer — z is
                                // the surface zoom, read at use
                                // time.
                                zoomProvider = { surfaceZoom.scale },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        // MR-4 (R1): no item-local pill — the
                        // item registered its selection with
                        // the surface-level bar above.
                        // Code 120 fix pass (recheck L5): the
                        // note-draft save's highlight tint —
                        // the design-aware default (under
                        // Nocturne the shipped constant, so the
                        // stored value there is unchanged).
                        val noteDefaultTintArgb =
                            defaultHighlightTintArgb()
                        PdfSelectionDialogs(
                            state = itemDialogs,
                            onNoteDraftSave = { note ->
                                val draft = itemDialogs.noteDraft
                                if (draft != null) {
                                    onAddPageHighlight(
                                        StoredSelectionHighlight(
                                            id = UUID.randomUUID()
                                                .toString(),
                                            page = page,
                                            charStart = draft.start,
                                            charEnd = draft.end,
                                            selectedText = draft.text,
                                            note = note,
                                            tintArgb =
                                                noteDefaultTintArgb,
                                            createdAtEpochMillis =
                                                System
                                                    .currentTimeMillis()
                                        )
                                    )
                                }
                                itemDialogs.noteDraft = null
                                itemSelection = null
                            },
                            onEditNoteSave = { note ->
                                val editing = itemDialogs.editing
                                if (editing != null) {
                                    onUpdatePageNote(editing, note)
                                }
                                itemDialogs.editing = null
                            }
                        )
                    }
                }
            }
        }
        // MR-4 (R1): the surface-level action bar — hosted
        // in this outermost box (the paged host's seat, same
        // rule), fed by the owning item's registration. The
        // seat maps the selection's item-local rects through
        // the item's layout offset in the list and the
        // surfaceZoom centre-anchored transform; it
        // re-seats at entry, at drag release (the settled
        // range changes), and on zoom/pan/scroll change
        // (layoutInfo + surfaceZoom are composition reads).
        // No registration, an off-screen item, or an
        // unmeasured bar falls back to the bottom pin.
        val registration = barRegistration
        if (registration != null) {
            val regPoints = session.pagePointsSizePeek(registration.page)
            val itemInfo = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == registration.page }
            val seatInputs = if (regPoints != null && itemInfo != null &&
                surfaceSizePx != IntSize.Zero
            ) {
                val itemW = surfaceSizePx.width.toFloat()
                val itemH = itemW * regPoints.second / regPoints.first
                val isx = itemW / regPoints.first
                val isy = itemH / regPoints.second
                val centre = Offset(
                    surfaceSizePx.width / 2f,
                    surfaceSizePx.height / 2f
                )
                val scale = surfaceZoom.scale
                pdfSeatInputs(
                    geometry = registration.selection.geometry,
                    start = registration.selection.start,
                    end = registration.selection.end,
                    mapPoint = { xPt, yPt ->
                        val layout = Offset(
                            xPt * isx,
                            itemInfo.offset + yPt * isy
                        )
                        centre + (layout - centre) * scale +
                            surfaceZoom.pan
                    },
                    heightScale = isy * scale,
                    dotRadiusPx = with(density) { 31.dp.toPx() } * scale
                )
            } else {
                null
            }
            val gapPx = with(density) { 15.dp.toPx() }
            val edgePx = with(density) { 8.dp.toPx() }
            // Code 117 §14: the 16 dp horizontal slack, and
            // the teardrop's full drop for the below-seat
            // (§13), as at the paged seat.
            val marginPx = with(density) { 16.dp.toPx() }
            // Revision 2 D1: while the keyed size is Zero no
            // seat target is computed — the animated target
            // holds the last placed seat and the bar stays
            // invisible (alpha gate on its graphicsLayer).
            val seatTarget = if (surfacePillSize == IntSize.Zero) {
                surfaceSeatHold
            } else if (seatInputs != null) {
                val computed = pdfBarSeatOffset(
                    surfaceW = surfaceSizePx.width.toFloat(),
                    surfaceH = surfaceSizePx.height.toFloat(),
                    barW = surfacePillSize.width.toFloat(),
                    barH = surfacePillSize.height.toFloat(),
                    inputs = seatInputs,
                    gapPx = gapPx,
                    edgePx = edgePx,
                    marginPx = marginPx
                )
                IntOffset(
                    computed.x.roundToInt(),
                    computed.y.roundToInt()
                )
            } else {
                IntOffset(
                    ((surfaceSizePx.width - surfacePillSize.width) / 2f)
                        .coerceIn(
                            marginPx,
                            maxOf(
                                marginPx,
                                surfaceSizePx.width -
                                    surfacePillSize.width - marginPx
                            )
                        )
                        .roundToInt(),
                    (surfaceSizePx.height - edgePx -
                        surfacePillSize.height)
                        .coerceAtLeast(0f)
                        .roundToInt()
                )
            }
            LaunchedEffect(seatTarget) {
                if (surfacePillSize != IntSize.Zero) {
                    surfaceSeatHold = seatTarget
                }
            }
            // §9.4 (as revised): the seat animation + the
            // per-frame clamp, exactly as the paged seat
            // (Critic F7) — the spring may overshoot its
            // target, never the screen.
            val animatedSeat by animateIntOffsetAsState(
                targetValue = seatTarget,
                animationSpec = if (motionEnabled) {
                    SpringMotionEngine.Spatial.toSpec()
                } else {
                    snap()
                },
                label = "pdfContinuousBarSeat"
            )
            val seatMotion = rememberSelectionBarSeatMotion(
                visible = true,
                dismissing = registration.dismissing.value,
                motionEnabled = motionEnabled
            )
            val surfaceContext = LocalContext.current
            SelectionBar(
                state = registration.barState.value,
                onStateChange = { registration.barState.value = it },
                moreItems = PdfSelectionMoreItems,
                callbacks = SelectionBarCallbacks(
                    onAction = { action ->
                        if (!registration.dismissing.value) {
                            when (action) {
                                SelectionBarAction.COPY -> {
                                    registration.onCopy()
                                    registration.dismissing.value = true
                                }
                                SelectionBarAction.HIGHLIGHT -> {
                                    registration.onHighlight()
                                    registration.dismissing.value = true
                                }
                                SelectionBarAction.NOTE -> {
                                    registration.onNote()
                                    // Live state: the note
                                    // editor opens over the
                                    // selection, which stays
                                    // (§6.1's draft flow).
                                    // Existing state: the bar
                                    // closes (§7.4).
                                    if (registration.barState.value
                                            .existingHighlight
                                    ) {
                                        registration.dismissing.value =
                                            true
                                    }
                                }
                                SelectionBarAction.DICTIONARY -> {
                                    registration.onDictionary()
                                    registration.dismissing.value = true
                                }
                                SelectionBarAction.TRANSLATE -> {
                                    onSelectionTranslate(
                                        registration.selection.text
                                    )
                                    registration.dismissing.value = true
                                }
                                SelectionBarAction.ASK_AI -> {
                                    onSelectionAskAi(
                                        registration.selection.text
                                    )
                                    registration.dismissing.value = true
                                }
                                SelectionBarAction.ILLUSTRATE -> {
                                    onSelectionIllustrate(
                                        registration.selection.text
                                    )
                                    registration.dismissing.value = true
                                }
                                SelectionBarAction.SHARE -> {
                                    val intent = Intent(
                                        Intent.ACTION_SEND
                                    ).apply {
                                        type = "text/plain"
                                        putExtra(
                                            Intent.EXTRA_TEXT,
                                            registration.selection.text
                                        )
                                    }
                                    surfaceContext.startActivity(
                                        Intent.createChooser(intent, null)
                                    )
                                    registration.dismissing.value = true
                                }
                                else -> Unit
                            }
                        }
                    },
                    onApplyHighlight = { tintArgb, style ->
                        if (!registration.dismissing.value) {
                            registration.onApplyHighlight(tintArgb, style)
                            registration.dismissing.value = true
                        }
                    },
                    onRemoveHighlight = {
                        if (!registration.dismissing.value) {
                            registration.onRemoveHighlight()
                            registration.dismissing.value = true
                        }
                    },
                    onDismiss = { registration.requestClear() }
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val edge = edgePx.roundToInt()
                        // §14: the per-frame clamp honours the
                        // same 16 dp horizontal slack.
                        val margin = marginPx.roundToInt()
                        IntOffset(
                            animatedSeat.x.coerceIn(
                                margin,
                                maxOf(
                                    margin,
                                    surfaceSizePx.width -
                                        surfacePillSize.width - margin
                                )
                            ),
                            animatedSeat.y.coerceIn(
                                0,
                                maxOf(
                                    0,
                                    surfaceSizePx.height -
                                        surfacePillSize.height - edge
                                )
                            )
                        )
                    }
                    .widthIn(
                        max = with(density) {
                            (surfaceSizePx.width - 16.dp.toPx())
                                .coerceAtLeast(0f).toDp()
                        }
                    )
                    .onSizeChanged { surfacePillSize = it }
                    // §12.1: a clean tap landing on the bar
                    // cancels the surface's pending single
                    // tap, so a bar tap never scrolls the
                    // list or toggles the chrome.
                    .pointerInput(Unit) {
                        pdfBarTapWatcher {
                            cancelSurfacePendingSingleTap()
                        }
                    }
                    .graphicsLayer {
                        // Revision 2 D1: held invisible until
                        // this episode's first measure lands.
                        alpha = if (surfacePillSize == IntSize.Zero) {
                            0f
                        } else {
                            seatMotion.alpha
                        }
                        scaleX = seatMotion.scale
                        scaleY = seatMotion.scale
                    }
            )
        }
    }
}

// ── Go to page: number entry + thumbnail-free page grid ─────────
