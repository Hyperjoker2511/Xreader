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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.core.motion.fadeTween
import app.xreader.core.motion.toSpec
import app.xreader.data.db.Highlight
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.MeasuredBlock
import app.xreader.domain.reader.Page
import app.xreader.domain.reader.PageLocator
import app.xreader.domain.reader.AutoScrollController
import app.xreader.domain.reader.PageTurnMachine
import app.xreader.domain.reader.PageTurnTier
import app.xreader.domain.reader.TapZones
import app.xreader.domain.reader.Paginator
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
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp
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
        var lastPages by remember { mutableStateOf<List<Page>>(emptyList()) }
        var lastPagesText by remember { mutableStateOf<ChapterText?>(null) }
        val computedPages by produceState<List<Page>?>(
            null, chapterText, config, fontFamily, columnWidthPx, contentHeightPx
        ) {
            value = null
            if (lastPagesText == chapterText) {
                delay(PAGINATION_DEBOUNCE_MS)
            }
            val paraSpacingPx = with(density) { config.paragraphSpacingDp.dp.toPx() }
            value = withContext(Dispatchers.Default) {
                val measured = chapterText.content.blocks.mapIndexed { index, block ->
                    val range = chapterText.rangeOf(index)!!
                    when (block) {
                        is ChapterBlock.Image -> MeasuredBlock(
                            blockIndex = index, blockStart = range.start, blockEnd = range.end,
                            fixedHeightPx = contentHeightPx * 0.5f
                        )
                        is ChapterBlock.Rule -> MeasuredBlock(
                            blockIndex = index, blockStart = range.start, blockEnd = range.end,
                            fixedHeightPx = with(density) { 30.dp.toPx() }
                        )
                        else -> {
                            val style = blockTextStyle(block, config, fontFamily)
                            val layout = measurer.measure(
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
                                blockIndex = index, blockStart = range.start,
                                blockEnd = range.end,
                                lineStarts = lineStarts,
                                lineHeightPx = lineHeightPx,
                                spaceBeforePx = if (block is ChapterBlock.Heading) {
                                    lineHeightPx * 0.7f
                                } else {
                                    0f
                                },
                                spaceAfterPx = paraSpacingPx
                            )
                        }
                    }
                }
                Paginator(contentHeightPx).paginate(measured)
            }
        }
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
        // v1.2.0: the last char offset actually reported upward —
        // a re-pagination (font change, rotation) re-anchors the
        // machine at THIS offset in the new page list, never back
        // at the chapter's original restore offset.
        var lastReportedOffset by remember(chapterText) {
            mutableIntStateOf(restoreOffset)
        }
        // v1.2.0 (WS2 contract): a page image reporting zoom
        // active suppresses page-turn drags until it reports back.
        var imageZoomActive by remember(chapterText) { mutableStateOf(false) }

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
        // (font-size change, rotation) re-anchors at the last
        // reported offset instead — the reader stays on the same
        // words under new typography.
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
                machine.jumpTo(
                    alignPage(PageLocator.pageIndexForOffset(ready, lastReportedOffset))
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
                        style = blockTextStyle(block, config, fontFamily),
                        color = palette.text
                    )
                }
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
            val size = when (block.level) {
                1 -> base * 1.45f
                2 -> base * 1.28f
                else -> base * 1.12f
            }
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = size.sp,
                lineHeight = (size * 1.3f).sp
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
            }
        )
    }
}
