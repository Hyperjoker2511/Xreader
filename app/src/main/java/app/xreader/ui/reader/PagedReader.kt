package app.xreader.ui.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.fadeTween
import app.xreader.core.motion.toSpec
import app.xreader.data.db.Highlight
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.ChapterLayoutCache
import app.xreader.domain.reader.ChapterLayoutService
import app.xreader.domain.reader.ChapterMeasurer
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.MeasuredBlock
import app.xreader.domain.reader.Page
import app.xreader.domain.reader.PageLocator
import app.xreader.domain.reader.AutoScrollController
import app.xreader.domain.reader.PageTurnMachine
import app.xreader.domain.reader.PageTurnTier
import app.xreader.domain.reader.TapZones
import app.xreader.domain.reader.ReaderAlignment
import app.xreader.domain.reader.ReaderConfig
import app.xreader.ui.theme.EmberAmber
import app.xreader.ui.theme.ReaderPalette
import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** v1.2.0: re-pagination debounce — font-size slider ticks
 * coalesce for this long before the chapter is re-measured. */
private const val PAGINATION_DEBOUNCE_MS = 150L

/**
 * The paged reader (PLAN §6): the chapter is measured once per
 * (chapter, typography, page size) into a page list, and pages turn
 * with the device's tier — FULL curl, MID gooey slide, MINIMAL
 * crossfade, EINK instant. Every animation runs on the
 * SpringMotionEngine's PageAssist profile (Amendment 5); position is
 * always reported as a char offset, so the other mode and any future
 * typography resume at the same words.
 */
@Composable
fun PagedReader(
    chapterText: ChapterText,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    tier: PageTurnTier,
    highlights: List<Highlight>,
    spokenRange: IntRange?,
    overlayBlockId: String?,
    restoreOffset: Int,
    restoreHint: PageHint,
    restoreGeneration: Int,
    externalTurns: SharedFlow<Int>,
    assetFile: (String) -> File,
    onPosition: (Int, Float) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onCenterTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** v1.0.6.2 (F7): measured chrome heights — pages are laid
     * out in the space BETWEEN the bars, never beneath them. */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** v1.7 Patch 2 (item 2): the current chapter's id (its
     * layout-cache key). */
    chapterId: Long = -1L,
    /** v1.7 document engine (Item 2): the SHARED layout store
     * and its inputs, hoisted to ReaderScreen — one store for
     * the whole reader. [layoutFingerprint] /
     * [spreadFingerprint] are the two width families (single
     * column == the scroll surface's width, so a chapter laid
     * out ahead by the worker serves a paged open of the same
     * chapter, and the spread's own family lives beside it);
     * [layoutContentHeightPx] is the height both were
     * assembled with (== this surface's own content height).
     * [preemptAheadLayout] is the screen worker's cancel
     * handle: a visible-chapter request pre-empts the ahead
     * job (R4). */
    layoutService: ChapterLayoutService,
    layoutFingerprint: List<Any?>,
    spreadFingerprint: List<Any?>,
    singleColumnWidthPx: Float,
    spreadColumnWidthPx: Float,
    layoutContentHeightPx: Float,
    preemptAheadLayout: () -> Unit
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val spread = wide && config.twoPageSpread
        val marginH = config.marginHorizontalDp.dp
        val marginV = config.marginVerticalDp.dp
        val gap = 36.dp
        val columnWidth = if (spread) {
            (maxWidth - marginH * 2 - gap) / 2
        } else {
            maxWidth - marginH * 2
        }
        val contentHeight = maxHeight - marginV * 2 - topInset - bottomInset
        val columnWidthPx = with(density) { columnWidth.toPx() }
        val contentHeightPx = with(density) { contentHeight.toPx() }

        // v1.2.0 (Wave 3, audit #5): pagination OFF the
        // composition path. The whole chapter used to be measured
        // + packed synchronously inside a remember{} during
        // composition — a freeze on chapter open and a full redo
        // on EVERY font-size slider tick (the sheet persists each
        // tick immediately). Now the request is keyed by
        // (chapterText, config, fontFamily, page size) in a
        // produceState, the work runs on Dispatchers.Default, and
        // a re-pagination of the SAME chapter first waits out a
        // debounce window so slider ticks coalesce into one run.
        // While a re-pagination is in flight the PREVIOUS pages
        // stay on screen (never a blank flash); a new chapter
        // starts from a single empty placeholder page.
        // Audit-claim check: the audit's "Paginator.awaitReady()
        // awaits one DB query per paragraph (N+1)" does NOT exist
        // in this tree — domain/reader/Paginator.kt is a pure
        // calculator over MeasuredBlocks (no awaitReady, no DB),
        // and the measuring loop below is the only per-block work:
        // one text measurement per prose block per pagination.
        // v1.7 Patch 2 (item 2a/2b): measure + paginate run
        // through the ChapterLayoutService. The Compose
        // measurer below is the UI-layer ChapterMeasurer — this
        // reader's own measuring machinery (TextMeasurer +
        // Density + blockTextStyle + annotated strings) behind
        // the seam, verbatim in substance.
        // v1.7 document engine (Item 2): the store, the
        // fingerprints and the geometry are HOISTED — passed in
        // from ReaderScreen, which assembles the fingerprint
        // ONCE for both surfaces. The ACTIVE fingerprint and
        // width follow the spread: the single-column family is
        // the same key family the scroll surface and the
        // screen's ahead worker use (same width by
        // construction), the spread is the second family in
        // the same store. A record's absence costs a measure,
        // never a place (the scoped-eviction invariant).
        val activeFingerprint = if (spread) {
            spreadFingerprint
        } else {
            layoutFingerprint
        }
        val activeColumnWidthPx = if (spread) {
            spreadColumnWidthPx
        } else {
            singleColumnWidthPx
        }
        val chapterMeasurer = remember(
            measurer, density, fontFamily, activeColumnWidthPx,
            layoutContentHeightPx
        ) {
            ComposeChapterMeasurer(
                textMeasurer = measurer,
                density = density,
                fontFamily = fontFamily,
                columnWidthPx = activeColumnWidthPx,
                contentHeightPx = layoutContentHeightPx
            )
        }
        var lastPages by remember { mutableStateOf<List<Page>>(emptyList()) }
        var lastPagesText by remember { mutableStateOf<ChapterText?>(null) }
        val computedPages by produceState<List<Page>?>(
            null, chapterText, config, fontFamily,
            activeColumnWidthPx, layoutContentHeightPx
        ) {
            value = null
            // v1.7 Patch 2 (item 2b): a request to lay out the
            // chapter ON SCREEN pre-empts the ahead job — the
            // visible chapter always wins the measuring budget.
            // v1.7 document engine (Item 2): the ahead job is
            // ReaderScreen's worker over the resident run now;
            // the pre-emption is its cancel handle.
            preemptAheadLayout()
            val cacheKey = ChapterLayoutCache.Key(
                chapterId = chapterId,
                contentHash = ChapterLayoutCache.contentHash(chapterText),
                fingerprint = activeFingerprint
            )
            val cached = layoutService.cache.get(cacheKey)
            if (cached != null) {
                // An ahead-laid-out chapter (or an identical
                // re-layout) serves from the cache — the key's
                // content hash + fingerprint make a stale serve
                // impossible.
                value = cached.pages
            } else {
                if (lastPagesText == chapterText) {
                    delay(PAGINATION_DEBOUNCE_MS)
                }
                value = withContext(Dispatchers.Default) {
                    layoutService.layout(
                        chapterText = chapterText,
                        config = config,
                        pageHeightPx = layoutContentHeightPx,
                        fingerprint = activeFingerprint,
                        measurer = chapterMeasurer
                    ).also { layout ->
                        layoutService.cache.put(cacheKey, layout)
                    }.pages
                }
            }
        }
        // The Patch-2 layout-ahead effect that lived here is
        // retired: layout-ahead is ReaderScreen's worker over
        // the published resident-run candidates (Item 2), into
        // this same shared store.
        LaunchedEffect(computedPages) {
            val ready = computedPages
            if (ready != null) {
                lastPages = ready
                lastPagesText = chapterText
            }
        }
        val pages = computedPages
            ?: if (lastPagesText == chapterText) {
                lastPages
            } else {
                listOf(Page(emptyList(), 0, 0))
            }

        val step = if (spread) 2 else 1
        // v1.0.1: page position lives in the PageTurnMachine, not in
        // loose flags. The v1.0.0 wedge — a drag's snapTo() cancelling
        // the commit animation and stranding `targetPage` forever —
        // is impossible here: every animation path settles the
        // machine (commit on finish, ROLL BACK on cancel), and the
        // machine's own tests pin that invariant.
        val machine = remember(chapterText, pages.size, step) {
            PageTurnMachine(pages.size, step)
        }
        var uiTick by remember(chapterText) { mutableIntStateOf(0) }
        fun bump() { uiTick++ }
        // Auto page-turn pause state (v1.0.1): any touch pauses;
        // a centre tap (the menu control) resumes.
        var autoPaused by remember(chapterText) { mutableStateOf(false) }
        // v1.2.0: the last char offset actually reported upward.
        // v1.7 one-engine (R3): REPORTING ONLY from here on —
        // re-pagination anchoring consumes capturedAnchorOffset
        // below, never this value: a report can lag a settle,
        // and the anchoring trap (A4) is reading any anchor out
        // of state that the page-list swap has already moved.
        var lastReportedOffset by remember(chapterText) {
            mutableIntStateOf(restoreOffset)
        }
        // v1.7 one-engine (R3 + R3-capture / A4): the anchor
        // offset for same-generation re-pagination, captured
        // CONTINUOUSLY from the settled current page by the
        // capture effect below — which deliberately has NO
        // computedPages suspension, so a page settled on
        // mid-re-pagination still updates the capture from the
        // OUTGOING page list before lastPages is overwritten
        // with the new one. The anchoring effect consumes this
        // capture and never reads a page list itself.
        var capturedAnchorOffset by remember(chapterText) {
            mutableIntStateOf(restoreOffset)
        }
        // C7: a turn in flight when a re-pagination lands keeps
        // its target — the target page's char offset in the
        // outgoing pagination, recorded at turn start in turn()
        // and cleared when the machine settles back to Idle.
        var turnTargetOffset by remember(chapterText) {
            mutableStateOf<Int?>(null)
        }
        // v1.2.0 (WS2 contract): a page image reporting zoom
        // active suppresses page-turn drags until it reports back.
        var imageZoomActive by remember(chapterText) { mutableStateOf(false) }
        // v1.7 Patch 3 (Item 4f): the plate-viewer claim clock.
        // A page image's single tap is reported by ZoomableImage
        // only after its double-tap window closes (~300 ms
        // after the tap), while this surface's tap zones decide
        // at the tap itself — so on a page carrying an image,
        // the tap-zone action below defers briefly and stands
        // down when a claim (this timestamp, stamped by the
        // subtree handler wrapped around the pages) lands after
        // the tap. Without the stand-down, a side tap on an
        // illustration would turn the page AND open the viewer;
        // the turn is the place-corrupting half.
        var imageClaimAtMs by remember { mutableStateOf(0L) }
        val outerImageTapHandler = LocalImageTapHandler.current
        val claimImageTapHandler: (Any?) -> Unit = remember(outerImageTapHandler) {
            { model: Any? ->
                imageClaimAtMs = System.currentTimeMillis()
                outerImageTapHandler?.invoke(model)
            }
        }

        fun alignPage(index: Int): Int {
            val clamped = index.coerceIn(0, pages.lastIndex)
            return if (spread && clamped % 2 == 1) clamped - 1 else clamped
        }

        // Reading uiTick makes every machine transition recompose.
        @Suppress("UNUSED_EXPRESSION") uiTick
        val machineState = machine.state
        val currentPage = alignPage(
            when (machineState) {
                is PageTurnMachine.State.Idle -> machineState.page
                is PageTurnMachine.State.Turning -> machineState.from
                is PageTurnMachine.State.Dragging -> machineState.from
            }
        )
        val targetPage: Int? = when (machineState) {
            is PageTurnMachine.State.Idle -> null
            is PageTurnMachine.State.Turning -> machineState.to
            is PageTurnMachine.State.Dragging -> machineState.to
        }
        var turnDirection by remember(chapterText) { mutableIntStateOf(1) }
        val progress = remember(chapterText) { Animatable(0f) }
        var lastDragEndMs by remember { mutableStateOf(0L) }

        // Every settled page change reports its char offset upward.
        // v1.1.2: the page fraction rides along — for image
        // chapters every page's startChar is ~the same (image
        // blocks are zero-width in the chapter text), so the
        // char offset alone can never say how far into the
        // chapter the reader is; the page's own position can.
        LaunchedEffect(currentPage, pages, targetPage, computedPages) {
            // v1.2.0: never report from the placeholder / previous
            // page list while the current keys' pagination is still
            // in flight — the single placeholder page would read as
            // "page 1 of 1" (fraction 1.0) for a beat on chapter
            // open. Reporting resumes the moment the real pages
            // land (this effect re-runs on computedPages).
            if (targetPage == null && computedPages != null) {
                val pageFraction = if (pages.isEmpty()) {
                    0f
                } else {
                    ((currentPage + 1).toFloat() / pages.size).coerceIn(0f, 1f)
                }
                val offset = PageLocator.offsetForPage(pages, currentPage)
                lastReportedOffset = offset
                onPosition(offset, pageFraction)
            }
        }

        // v1.7 one-engine (R3-capture / A4): on every settled
        // page, capture the current page's char offset into
        // capturedAnchorOffset. Unlike the reporting effect
        // above this is NOT suspended while computedPages is
        // null: during a re-pagination `pages` is still the
        // outgoing list for this chapter, and a turn settling
        // in that window must move the capture — by anchoring
        // time the new list has replaced it. The placeholder
        // page of a fresh chapter (no list belongs to this
        // chapterText yet) never captures.
        LaunchedEffect(currentPage, targetPage, pages) {
            if (targetPage == null &&
                (computedPages != null || lastPagesText == chapterText)
            ) {
                capturedAnchorOffset =
                    PageLocator.offsetForPage(pages, currentPage)
            }
        }

        // C7 bookkeeping: once the machine is Idle again the
        // recorded turn target has been reached (or rolled
        // back) — the continuous capture rules from there.
        LaunchedEffect(machineState) {
            if (machineState is PageTurnMachine.State.Idle) {
                turnTargetOffset = null
            }
        }

        /** Runs the turn animation to [targetPage]; ALWAYS settles
         * the machine — commit on finish, roll back on cancel. */
        fun animateCommit() {
            scope.launch {
                try {
                    progress.animateTo(1f, SpringMotionEngine.PageAssist.toSpec())
                    machine.animationFinished()
                } catch (cancelled: CancellationException) {
                    machine.animationCancelled()
                    throw cancelled
                } finally {
                    progress.snapTo(0f)
                    bump()
                }
            }
        }

        fun animateRollback() {
            scope.launch {
                try {
                    progress.animateTo(0f, SpringMotionEngine.PageAssist.toSpec())
                } catch (cancelled: CancellationException) {
                    // Rolling back being cancelled still settles below.
                } finally {
                    progress.snapTo(0f)
                    bump()
                }
            }
        }

        fun turn(direction: Int) {
            when (val outcome = machine.requestTurn(direction)) {
                is PageTurnMachine.Outcome.AnimateTo -> {
                    turnDirection = direction
                    // C7: record the turn's target in char
                    // offsets of the CURRENT pagination — if a
                    // re-pagination lands mid-turn, the
                    // anchoring effect aims at these words,
                    // not at wherever the machine happened to
                    // be resting.
                    turnTargetOffset =
                        PageLocator.offsetForPage(pages, outcome.page)
                    bump()
                    if (tier == PageTurnTier.MINIMAL || tier == PageTurnTier.EINK) {
                        machine.animationFinished()
                        bump()
                    } else {
                        animateCommit()
                    }
                }
                PageTurnMachine.Outcome.Boundary -> {
                    if (direction < 0) onPreviousChapter() else onNextChapter()
                }
                PageTurnMachine.Outcome.Ignored -> Unit // busy turning: never wedged, just busy
            }
        }

        // Restore + re-pagination anchoring (v1.2.0): pagination
        // is asynchronous now, so anchoring waits for the page
        // list that belongs to the current keys. A NEW restore
        // generation anchors at the restore hint exactly as
        // before; a fresh page list under the SAME generation
        // (font-size change, rotation) re-anchors at the
        // captured current-page offset instead — the reader
        // stays on the same words under new typography.
        // v1.7 one-engine (R3): the same-generation anchor is
        // capturedAnchorOffset (continuously captured, A4) —
        // lastReportedOffset no longer feeds any jumpTo; and
        // while a turn is in flight (C7) its recorded target
        // offset wins over the capture.
        var restoredGeneration by remember { mutableIntStateOf(-1) }
        LaunchedEffect(restoreGeneration, computedPages) {
            val ready = computedPages ?: return@LaunchedEffect
            if (restoredGeneration != restoreGeneration) {
                restoredGeneration = restoreGeneration
                val anchor = when (restoreHint) {
                    PageHint.FIRST -> 0
                    PageHint.LAST -> ready.lastOrNull()?.endChar ?: 0
                    PageHint.OFFSET -> restoreOffset
                }
                machine.jumpTo(alignPage(PageLocator.pageIndexForOffset(ready, anchor)))
                progress.snapTo(0f)
            } else {
                val anchorOffset = if (
                    machine.state is PageTurnMachine.State.Turning
                ) {
                    turnTargetOffset ?: capturedAnchorOffset
                } else {
                    capturedAnchorOffset
                }
                machine.jumpTo(
                    alignPage(PageLocator.pageIndexForOffset(ready, anchorOffset))
                )
            }
            bump()
        }

        LaunchedEffect(Unit) {
            externalTurns.collectLatest { direction -> turn(direction) }
        }

        // Auto page-turn (v1.0.1): one turn per interval while the
        // setting is on and no touch has paused it. Leaving the
        // chapter (key change) or the reader cancels this effect —
        // the driver can never run in the background.
        LaunchedEffect(config.autoScrollEnabled, config.autoScrollSpeed, chapterText, autoPaused) {
            if (!config.autoScrollEnabled || autoPaused) return@LaunchedEffect
            while (true) {
                delay(AutoScrollController.pageTurnIntervalMs(config.autoScrollSpeed))
                turn(1)
            }
        }

        // Spoken sentence / overlay clip may live on another page:
        // follow them (read-aloud should never speak from off-screen).
        LaunchedEffect(spokenRange, overlayBlockId) {
            val offset = spokenRange?.first
                ?: overlayBlockId?.let { id ->
                    chapterText.ranges.firstOrNull { it.blockId == id }?.start
                }
            if (offset != null && machine.state is PageTurnMachine.State.Idle) {
                val page = alignPage(PageLocator.pageIndexForOffset(pages, offset))
                if (page != currentPage) {
                    machine.jumpTo(page)
                    bump()
                }
            }
        }

        val gestureModifier = Modifier
            // v1.0.6.2 (F7): Initial-pass tap observation (see
            // readerTapToggle) — page taps are decided before any
            // child handler can swallow them; the drag detector
            // below is untouched because a tap only consumes its
            // own up-event after proving it isn't a drag.
            .readerTapToggle(
                listOf(chapterText, config.tapZoneLayout, config.pagedDirection)
            ) { xFraction, yFraction ->
                if (System.currentTimeMillis() - lastDragEndMs >= 250L) {
                    val action = TapZones.action(
                        config.tapZoneLayout, config.pagedDirection,
                        xFraction, yFraction
                    )
                    val act = {
                        when {
                            action > 0 -> { autoPaused = true; turn(1) }
                            action < 0 -> { autoPaused = true; turn(-1) }
                            else -> {
                                // Centre tap = the menu control: it also
                                // RESUMES auto page-turn after a touch pause.
                                autoPaused = false
                                onCenterTap()
                            }
                        }
                    }
                    // v1.7 Patch 3 (Item 4f): on a page carrying
                    // an image, the tap may be the image's own
                    // (the plate viewer opens ~300 ms later, at
                    // the double-tap window's close). Defer the
                    // zone action past that report and stand
                    // down if the image claimed the tap — a
                    // turned page here would move the reader
                    // off the place the viewer opened from.
                    // Pages without images act at once, as
                    // before.
                    val pageHasImage = pages
                        .getOrNull(currentPage)?.slices?.any { slice ->
                            val block = chapterText.content.blocks
                                .getOrNull(slice.blockIndex)
                            block is ChapterBlock.Image
                        } == true
                    if (pageHasImage) {
                        val tapAtMs = System.currentTimeMillis()
                        scope.launch {
                            kotlinx.coroutines.delay(400)
                            if (imageClaimAtMs < tapAtMs) act()
                        }
                    } else {
                        act()
                    }
                }
            }
            .pointerInput(chapterText, tier) {
                if (tier != PageTurnTier.FULL && tier != PageTurnTier.MID) {
                    return@pointerInput
                }
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f; autoPaused = true },
                    onHorizontalDrag = { _, amount ->
                        // Only arm a drag when the machine is Idle —
                        // a commit animation in flight OWNS progress;
                        // snapping it from here was the v1.0.0 wedge.
                        // v1.2.0: a zoomed page image owns drags too
                        // (ZoomableImage's onZoomActiveChanged).
                        if (machine.state is PageTurnMachine.State.Idle &&
                            amount != 0f && !imageZoomActive
                        ) {
                            val direction = if (amount < 0) 1 else -1
                            if (machine.dragStart(direction)) {
                                turnDirection = direction
                                bump()
                            }
                        }
                        dragTotal += amount
                        if (machine.state is PageTurnMachine.State.Dragging) {
                            val fraction = (kotlin.math.abs(dragTotal) / size.width)
                                .coerceIn(0f, 1f)
                            machine.dragBy(fraction)
                            scope.launch { progress.snapTo(fraction) }
                        }
                    },
                    onDragEnd = {
                        lastDragEndMs = System.currentTimeMillis()
                        when (machine.dragEnd()) {
                            is PageTurnMachine.Outcome.AnimateTo -> {
                                bump()
                                animateCommit()
                            }
                            else -> {
                                bump()
                                if (machine.state is PageTurnMachine.State.Idle) {
                                    val widthPx = size.width.toFloat()
                                    if (kotlin.math.abs(dragTotal) > widthPx * 0.22f &&
                                        progress.value == 0f && !imageZoomActive
                                    ) {
                                        // Drag began at a chapter edge
                                        // (no page to arm): change chapter.
                                        if (dragTotal < 0) onNextChapter()
                                        else onPreviousChapter()
                                    } else {
                                        animateRollback()
                                    }
                                }
                            }
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = {
                        lastDragEndMs = System.currentTimeMillis()
                        machine.dragCancel()
                        bump()
                        animateRollback()
                        dragTotal = 0f
                    }
                )
            }

        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    start = marginH,
                    end = marginH,
                    top = marginV + topInset,
                    bottom = marginV + bottomInset
                )
                .then(gestureModifier)
        ) {
            val displayPage = targetPage ?: currentPage
            // Item 4f: page images report their taps through
            // the claiming handler (see imageClaimAtMs above).
            androidx.compose.runtime.CompositionLocalProvider(
                LocalImageTapHandler provides claimImageTapHandler
            ) {
            when (tier) {
                PageTurnTier.EINK -> SpreadOrSingle(
                    pages, currentPage, spread, columnWidth, contentHeight,
                    chapterText, config, palette, fontFamily,
                    highlights, spokenRange, overlayBlockId, assetFile,
                    onZoomActiveChanged = { active -> imageZoomActive = active }
                )
                PageTurnTier.MINIMAL -> AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        fadeIn(fadeTween<Float>()) togetherWith fadeOut(fadeTween<Float>())
                    },
                    label = "pageCrossfade"
                ) { page ->
                    SpreadOrSingle(
                        pages, page, spread, columnWidth, contentHeight,
                        chapterText, config, palette, fontFamily,
                        highlights, spokenRange, overlayBlockId, assetFile,
                        onZoomActiveChanged = { active -> imageZoomActive = active }
                    )
                }
                PageTurnTier.MID -> {
                    if (displayPage != currentPage) {
                        Box(
                            Modifier.graphicsLayer {
                                translationX = turnDirection *
                                    (1f - progress.value) * 24.dp.toPx()
                                alpha = 0.55f + 0.45f * progress.value
                            }
                        ) {
                            SpreadOrSingle(
                                pages, displayPage, spread, columnWidth, contentHeight,
                                chapterText, config, palette, fontFamily,
                                highlights, spokenRange, overlayBlockId, assetFile,
                                onZoomActiveChanged = { active -> imageZoomActive = active }
                            )
                        }
                    }
                    Box(
                        Modifier.graphicsLayer {
                            translationX = -turnDirection * progress.value *
                                size.width * 0.94f
                        }
                    ) {
                        SpreadOrSingle(
                            pages, currentPage, spread, columnWidth, contentHeight,
                            chapterText, config, palette, fontFamily,
                            highlights, spokenRange, overlayBlockId, assetFile,
                            onZoomActiveChanged = { active -> imageZoomActive = active }
                        )
                    }
                    if (displayPage != currentPage) {
                        Canvas(Modifier.fillMaxSize()) {
                            // The gooey leading edge: a soft bulge riding
                            // the moving page edge.
                            val edgeX = if (turnDirection > 0) {
                                size.width - progress.value * size.width * 0.94f
                            } else {
                                progress.value * size.width * 0.94f
                            }
                            val bulge = 26.dp.toPx() * progress.value
                            drawOval(
                                color = palette.background.copy(alpha = 0.9f),
                                topLeft = Offset(
                                    edgeX - bulge / 2,
                                    size.height * 0.22f
                                ),
                                size = androidx.compose.ui.geometry.Size(
                                    bulge, size.height * 0.56f
                                )
                            )
                            drawOval(
                                color = Color.Black.copy(alpha = 0.18f * progress.value),
                                topLeft = Offset(
                                    edgeX - bulge / 2 + 3.dp.toPx(),
                                    size.height * 0.22f
                                ),
                                size = androidx.compose.ui.geometry.Size(
                                    bulge, size.height * 0.56f
                                )
                            )
                        }
                    }
                }
                PageTurnTier.FULL -> {
                    if (displayPage != currentPage) {
                        SpreadOrSingle(
                            pages, displayPage, spread, columnWidth, contentHeight,
                            chapterText, config, palette, fontFamily,
                            highlights, spokenRange, overlayBlockId, assetFile,
                            onZoomActiveChanged = { active -> imageZoomActive = active }
                        )
                    }
                    Box(
                        Modifier.drawWithContent {
                            val p = progress.value
                            if (displayPage == currentPage || p <= 0f) {
                                drawContent()
                            } else {
                                clipPath(curlClipPath(size, p, turnDirection)) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                    ) {
                        SpreadOrSingle(
                            pages, currentPage, spread, columnWidth, contentHeight,
                            chapterText, config, palette, fontFamily,
                            highlights, spokenRange, overlayBlockId, assetFile,
                            onZoomActiveChanged = { active -> imageZoomActive = active }
                        )
                    }
                    if (displayPage != currentPage && progress.value > 0f) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCurlFlap(size, progress.value, turnDirection, palette)
                        }
                    }
                }
            }
            }
        }
    }
}

/** The fold clip: the still-visible part of the turning page. The fold
 * line sweeps from the grabbed edge with a soft mid-page bulge. */
private fun curlClipPath(
    size: androidx.compose.ui.geometry.Size,
    progress: Float,
    direction: Int
): Path {
    val w = size.width
    val h = size.height
    val bulge = w * 0.05f * kotlin.math.sin(progress * Math.PI).toFloat()
    val path = Path()
    if (direction > 0) {
        val foldX = w * (1f - progress * 1.04f)
        path.moveTo(0f, 0f)
        path.lineTo(foldX + bulge, 0f)
        path.quadraticTo(foldX - bulge * 0.7f, h / 2f, foldX + bulge, h)
        path.lineTo(0f, h)
    } else {
        val foldX = w * progress * 1.04f
        path.moveTo(w, 0f)
        path.lineTo(foldX - bulge, 0f)
        path.quadraticTo(foldX + bulge * 0.7f, h / 2f, foldX - bulge, h)
        path.lineTo(w, h)
    }
    path.close()
    return path
}

/** The turning page's back + its soft shadow on the page beneath. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurlFlap(
    size: androidx.compose.ui.geometry.Size,
    progress: Float,
    direction: Int,
    palette: ReaderPalette
) {
    val w = size.width
    val h = size.height
    val paperBack = palette.background.lerp(Color.White, 0.10f)
    val shadow = Color.Black.copy(alpha = 0.28f * progress)
    if (direction > 0) {
        val foldX = w * (1f - progress * 1.04f)
        val flapWidth = (w - foldX) * 0.55f
        // Shadow cast on the page beneath, just left of the fold.
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, shadow),
                startX = foldX - 36.dp.toPx(),
                endX = foldX
            ),
            topLeft = Offset(foldX - 36.dp.toPx(), 0f),
            size = androidx.compose.ui.geometry.Size(36.dp.toPx(), h)
        )
        // The page's back.
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(paperBack, paperBack.lerp(Color.Black, 0.13f)),
                startX = foldX,
                endX = foldX + flapWidth
            ),
            topLeft = Offset(foldX, 0f),
            size = androidx.compose.ui.geometry.Size(flapWidth, h)
        )
    } else {
        val foldX = w * progress * 1.04f
        val flapWidth = foldX * 0.55f
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(shadow, Color.Transparent),
                startX = foldX,
                endX = foldX + 36.dp.toPx()
            ),
            topLeft = Offset(foldX, 0f),
            size = androidx.compose.ui.geometry.Size(36.dp.toPx(), h)
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(paperBack.lerp(Color.Black, 0.13f), paperBack),
                startX = foldX - flapWidth,
                endX = foldX
            ),
            topLeft = Offset(foldX - flapWidth, 0f),
            size = androidx.compose.ui.geometry.Size(flapWidth, h)
        )
    }
}

/** Colour lerp helper (Compose Color has no lerp for arbitrary pairs
 * in this API level's common path — do it manually). */
private fun Color.lerp(other: Color, fraction: Float): Color = Color(
    red = red + (other.red - red) * fraction,
    green = green + (other.green - green) * fraction,
    blue = blue + (other.blue - blue) * fraction,
    alpha = alpha + (other.alpha - alpha) * fraction
)

@Composable
private fun SpreadOrSingle(
    pages: List<Page>,
    pageIndex: Int,
    spread: Boolean,
    columnWidth: androidx.compose.ui.unit.Dp,
    contentHeight: androidx.compose.ui.unit.Dp,
    chapterText: ChapterText,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    spokenRange: IntRange?,
    overlayBlockId: String?,
    assetFile: (String) -> File,
    onZoomActiveChanged: (Boolean) -> Unit = {}
) {
    if (!spread) {
        PageContent(
            page = pages.getOrNull(pageIndex) ?: return,
            chapterText = chapterText, config = config, palette = palette,
            fontFamily = fontFamily, highlights = highlights,
            spokenRange = spokenRange, overlayBlockId = overlayBlockId,
            assetFile = assetFile,
            onZoomActiveChanged = onZoomActiveChanged,
            modifier = Modifier
                .width(columnWidth)
                .height(contentHeight)
        )
        return
    }
    Row {
        PageContent(
            page = pages.getOrNull(pageIndex) ?: Page(emptyList(), 0, 0),
            chapterText = chapterText, config = config, palette = palette,
            fontFamily = fontFamily, highlights = highlights,
            spokenRange = spokenRange, overlayBlockId = overlayBlockId,
            assetFile = assetFile,
            onZoomActiveChanged = onZoomActiveChanged,
            modifier = Modifier
                .width(columnWidth)
                .height(contentHeight)
        )
        Spacer(Modifier.width(36.dp))
        val right = pages.getOrNull(pageIndex + 1)
        if (right != null) {
            PageContent(
                page = right,
                chapterText = chapterText, config = config, palette = palette,
                fontFamily = fontFamily, highlights = highlights,
                spokenRange = spokenRange, overlayBlockId = overlayBlockId,
                assetFile = assetFile,
                onZoomActiveChanged = onZoomActiveChanged,
                modifier = Modifier
                    .width(columnWidth)
                    .height(contentHeight)
            )
        } else {
            Spacer(Modifier.width(columnWidth))
        }
    }
}

@Composable
private fun PageContent(
    page: Page,
    chapterText: ChapterText,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    highlights: List<Highlight>,
    spokenRange: IntRange?,
    overlayBlockId: String?,
    assetFile: (String) -> File,
    onZoomActiveChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val highlightColor = palette.accent.copy(alpha = 0.38f)
    val spokenColor = palette.accent.copy(alpha = 0.30f)
    val overlayColor = palette.accent.copy(alpha = 0.20f)
    // v1.0.1: every page paints its own opaque background. Without
    // it, the FULL/MID turn tiers composite the incoming page UNDER
    // the outgoing one and the two pages' glyphs superimpose through
    // each other for the whole turn ("text mixed in the background").
    Column(modifier.background(palette.background)) {
        for (slice in page.slices) {
            val block = chapterText.content.blocks.getOrNull(slice.blockIndex) ?: continue
            val range = chapterText.rangeOf(slice.blockIndex) ?: continue
            when (block) {
                // v1.2.0 (WS2 reuse contract, per ZoomableImage's
                // KDoc): page images zoom exactly as scroll-mode
                // images do — ContentScale.Fit, the image modifier
                // fills the page, and zoom-active suppresses the
                // page-turn drags in the host above.
                is ChapterBlock.Image -> ZoomableImage(
                    model = assetFile(block.fileName),
                    contentDescription = block.alt,
                    contentScale = ContentScale.Fit,
                    imageModifier = Modifier.fillMaxSize(),
                    doubleTapToZoom = config.doubleTapToZoom,
                    disableZoomOut = config.disableZoomOut,
                    onZoomActiveChanged = onZoomActiveChanged,
                    modifier = if (page.slices.size == 1) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    }
                )
                is ChapterBlock.Rule -> Text(
                    "• • •",
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                )
                // D13: Moon+'s footnote box, whole on one
                // page (a fixed-height block in the paginator,
                // like an image).
                is ChapterBlock.FootnoteBox -> FootnoteBoxView(
                    block = block,
                    config = config,
                    palette = palette,
                    fontFamily = fontFamily,
                    assetFile = assetFile,
                    modifier = Modifier.fillMaxWidth(),
                    onZoomActiveChanged = onZoomActiveChanged
                )
                else -> {
                    // v1.2.0 (Wave 3, audit #2): remembered per
                    // slice — PageContent recomposes on EVERY turn
                    // animation frame, and the AnnotatedString +
                    // washes used to be rebuilt on each one. Keyed
                    // by the block, the slice's char range, and
                    // every rendering input (highlights, spoken
                    // range, overlay, bionic, palette colours).
                    val overlayActive = overlayBlockId != null &&
                        range.blockId == overlayBlockId
                    val backgrounds = remember(
                        block, range.start, range.end, highlights,
                        spokenRange, overlayActive,
                        highlightColor, spokenColor, overlayColor
                    ) {
                        blockBackgrounds(
                            blockStart = range.start,
                            blockEnd = range.end,
                            highlightRanges = highlights.map {
                                it.startOffset to it.endOffset
                            },
                            spokenRange = spokenRange,
                            overlayActive = overlayActive,
                            highlightColor = highlightColor,
                            spokenColor = spokenColor,
                            overlayColor = overlayColor
                        )
                    }
                    val annotated = remember(
                        block, slice.startChar, slice.endChar,
                        config.bionicReading, backgrounds
                    ) {
                        annotatedForBlock(
                            block = block,
                            fromChar = slice.startChar - range.start,
                            toChar = slice.endChar - range.start,
                            bionic = config.bionicReading,
                            backgrounds = backgrounds
                        )
                    }
                    Text(
                        annotated,
                        style = blockTextStyle(block, config, fontFamily)
                            .let { style ->
                                // Code 106 (the fromChar == 0
                                // gate): the block style carries
                                // the first-line indent, which
                                // belongs to the paragraph's TRUE
                                // first line only. A slice that
                                // starts mid-block is a paragraph
                                // continuing onto this page — it
                                // renders with the indent stripped,
                                // or it would grow a false one.
                                if (slice.startChar > range.start) {
                                    style.copy(textIndent = TextIndent.None)
                                } else {
                                    style
                                }
                            },
                        color = palette.text
                    )
                }
            }
        }
    }
}

/**
 * v1.7 Patch 2 (item 2a): the UI-layer [ChapterMeasurer] — the
 * paged reader's measuring machinery (Compose [TextMeasurer] +
 * [Density] + [blockTextStyle] + the bionic/annotated-string
 * production) behind the layout service's seam. The measuring
 * arithmetic is the shipped produceState loop's, moved
 * verbatim in substance: prose measured at the column width,
 * images at half the content height, rules at 30 dp, heading
 * space-before at 0.7 line heights, paragraph space-after from
 * the config.
 */
class ComposeChapterMeasurer(
    private val textMeasurer: TextMeasurer,
    private val density: Density,
    private val fontFamily: FontFamily,
    private val columnWidthPx: Float,
    private val contentHeightPx: Float
) : ChapterMeasurer {

    override fun measure(
        chapterText: ChapterText,
        blockIndex: Int,
        config: ReaderConfig
    ): MeasuredBlock {
        val block = chapterText.content.blocks[blockIndex]
        val range = chapterText.rangeOf(blockIndex)!!
        return when (block) {
            is ChapterBlock.Image -> MeasuredBlock(
                blockIndex = blockIndex, blockStart = range.start,
                blockEnd = range.end,
                fixedHeightPx = contentHeightPx * 0.5f
            )
            is ChapterBlock.Rule -> MeasuredBlock(
                blockIndex = blockIndex, blockStart = range.start,
                blockEnd = range.end,
                fixedHeightPx = with(density) { 30.dp.toPx() }
            )
            is ChapterBlock.FootnoteBox -> {
                // D13: a fixed-height block — its flattened
                // text measured at the box style inside the
                // box's own chrome (margin + padding together
                // are 1 em of the box size on each side), plus
                // a flat reserve for each image inside the
                // notes. Self-spaced (its margin is part of
                // the box), so no paragraph space-after.
                val emPx = with(density) {
                    (config.fontSizeSp * 0.8f).dp.toPx()
                }
                val layout = textMeasurer.measure(
                    block.flattenedText(),
                    footnoteTextStyle(config, fontFamily),
                    constraints = Constraints(
                        maxWidth = (columnWidthPx - 2 * emPx)
                            .toInt().coerceAtLeast(1)
                    )
                )
                val imageCount = block.entries.sumOf { entry ->
                    entry.blocks.count { it is ChapterBlock.Image }
                }
                MeasuredBlock(
                    blockIndex = blockIndex, blockStart = range.start,
                    blockEnd = range.end,
                    fixedHeightPx = layout.size.height + 2 * emPx +
                        imageCount * with(density) { 120.dp.toPx() }
                )
            }
            else -> {
                val style = blockTextStyle(block, config, fontFamily)
                val layout = textMeasurer.measure(
                    annotatedForBlock(block, bionic = config.bionicReading),
                    style,
                    constraints = Constraints(maxWidth = columnWidthPx.toInt())
                )
                val lineStarts = if (layout.lineCount == 0) {
                    listOf(range.start)
                } else {
                    (0 until layout.lineCount).map {
                        range.start + layout.getLineStart(it)
                    }
                }
                val lineHeightPx = if (layout.lineCount > 0) {
                    layout.size.height.toFloat() / layout.lineCount
                } else {
                    with(density) { config.fontSizeSp.sp.toPx() } * 1.4f
                }
                MeasuredBlock(
                    blockIndex = blockIndex, blockStart = range.start,
                    blockEnd = range.end,
                    lineStarts = lineStarts,
                    lineHeightPx = lineHeightPx,
                    spaceBeforePx = if (block is ChapterBlock.Heading) {
                        lineHeightPx * 0.7f
                    } else {
                        0f
                    },
                    spaceAfterPx = with(density) {
                        config.paragraphSpacingDp.dp.toPx()
                    }
                )
            }
        }
    }
}

/** Typography for one block under the current reader config. */
fun blockTextStyle(
    block: ChapterBlock,
    config: ReaderConfig,
    fontFamily: FontFamily
): TextStyle {
    val base = config.fontSizeSp
    return when (block) {
        is ChapterBlock.Heading -> {
            // v1.7 (code 106): Moon+'s heading scales, copied
            // from its HtmlToSpannedConverter HEADER_SIZES —
            // h1 2.0×, h2 1.5×, h3+ 1.17× body size. The
            // chapter title of the owner's proof book behaves
            // as Moon+'s h2, so the crowned title renders at
            // 1.5×; in-chapter headings keep their own level's
            // scale (Moon+ scales by HTML level — the levels
            // are NOT flattened to 1.5). Headings are centred
            // (the photo's title is centred exactly; heading
            // blocks in this model are display headings —
            // body emphasis rides Paragraph spans) and take
            // the BODY line-height multiplier, replacing the
            // hard-coded 1.3, so a wrapped title's pitch is
            // its size × the same ratio as the body's (the
            // photo measures title pitch = 1.51 × body pitch
            // ≈ 1.5 × 1.43).
            val size = when (block.level) {
                1 -> base * 2.0f
                2 -> base * 1.5f
                else -> base * 1.17f
            }
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = size.sp,
                lineHeight = (size * config.lineHeightMultiplier).sp,
                textAlign = TextAlign.Center
            )
        }
        else -> TextStyle(
            fontFamily = fontFamily,
            fontSize = base.sp,
            lineHeight = (base * config.lineHeightMultiplier).sp,
            // v1.0.1 reading comfort: automatic hyphenation where
            // the platform supports it + paragraph line breaking,
            // so justified/left text stops stranding single words.
            hyphens = Hyphens.Auto,
            lineBreak = LineBreak.Paragraph,
            textAlign = if (config.alignment == ReaderAlignment.JUSTIFY) {
                TextAlign.Justify
            } else {
                TextAlign.Left
            },
            // v1.7 (code 106): Moon+'s first-line paragraph
            // indent, the faithful Compose equivalent — a
            // TextIndent (the ParagraphStyle quantity) carried
            // on the block style, so the measurer and every
            // surface that renders through this one function
            // (scroll, paged, PDF Reflow) lay out identically.
            // Layout-only: the text string is never rewritten,
            // so char offsets, highlights, TTS ranges and
            // saved places are untouched. The gate that keeps
            // a paragraph CONTINUING onto a new page from
            // growing a false indent lives at the paged
            // render site (PageContent strips this for
            // mid-block slices — the fromChar == 0 rule); the
            // other surfaces always render whole blocks, whose
            // first line IS the paragraph's first line.
            textIndent = if (config.paragraphIndent) {
                TextIndent(
                    firstLine = (config.paragraphIndentEm * base).sp
                )
            } else {
                TextIndent.None
            }
        )
    }
}
