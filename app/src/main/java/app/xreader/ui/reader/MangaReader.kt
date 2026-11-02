package app.xreader.ui.reader

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.xreader.core.motion.sharedAxisYEnter
import app.xreader.core.motion.sharedAxisYExit
import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import app.xreader.domain.reader.ImageReaderMode
import app.xreader.ui.common.XreaderPrimaryButton
import app.xreader.ui.theme.FloatingChromeLift
import app.xreader.ui.theme.ReaderPalette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manga programme (slice M2): the IMAGE reader — the surface
 * a MANGA source's chapter opens into (ReaderScreen routes a
 * chapter here when its content carries a page list; the text
 * renderers never see it).
 *
 * Two modes, resolved by the ViewModel under the option-scope
 * rule (Settings default, per-series override — see
 * [MangaModeResolution]):
 *
 *  - WEBTOON: every page stacked in one continuous vertical
 *    strip (the plan's default);
 *  - PAGED: one page per screen, swipe or tap-edge turns,
 *    with the app's per-page zoom ([ZoomableImage]).
 *
 * Pages load through the app's one Coil pipeline — its call
 * factory resolves the source's session cookies per request,
 * so signed-in sources' pages load — and a page M3 has
 * downloaded renders from its local file instead (see
 * [MangaPageSources]). Upcoming pages are warmed ahead of
 * the reading position ([MangaPreload]); on the low-RAM tier
 * pages decode at screen width ([MangaDecode]).
 *
 * Position flows through the ViewModel's ordinary
 * onPosition(pageIndex, fraction): the fraction (from
 * [MangaProgress]) is the persisted carrier in the existing
 * ReadingProgress row, so Continue, History and
 * progress-linking treat a manga chapter exactly like a
 * text one, and restore maps the fraction back to the page.
 */
@Composable
fun MangaReader(
    pages: ChapterPages,
    chapterKey: Long?,
    mode: ImageReaderMode,
    lowRam: Boolean,
    palette: ReaderPalette,
    bookTitle: String,
    chapterTitle: String,
    chapterFooter: String,
    hasPrevious: Boolean,
    hasNext: Boolean,
    chromeVisible: Boolean,
    restoreOffset: Int,
    restoreScrollFraction: Float,
    restoreHint: PageHint,
    restoreGeneration: Int,
    /** v1.5.0 (Part C): the resolved manga settings for
     * this series (global defaults + its overrides). */
    settings: MangaSettings,
    /** v1.5.0 (Part C): hardware/volume-key turns, shared
     * with the text readers (direction: +1 next, −1 prev). */
    externalTurns: kotlinx.coroutines.flow.SharedFlow<Int>,
    assetFile: (String) -> File,
    onPosition: (pageIndex: Int, fraction: Float) -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onReachedEnd: () -> Unit,
    onModeChange: (ImageReaderMode) -> Unit,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleChrome: () -> Unit,
    reduceMotion: Boolean = false
) {
    val pageCount = pages.size
    val restorePage = remember(chapterKey, restoreGeneration, pageCount) {
        MangaProgress.restorePage(
            restoreHint, restoreOffset, restoreScrollFraction, pageCount
        )
    }
    var currentPage by remember(chapterKey, restoreGeneration) {
        mutableIntStateOf(restorePage)
    }
    // Where a mode's renderer starts: the restore page for a
    // fresh chapter, but the CURRENT page when the reader
    // flips modes mid-chapter (the switch handler below
    // re-anchors before the mode changes, so flipping never
    // loses the reader's place).
    var anchorPage by remember(chapterKey, restoreGeneration) {
        mutableIntStateOf(restorePage)
    }

    // Preload: warm the window around the reading position.
    // Only remote pages are enqueued (a downloaded page is
    // already on disk); the request shape matches the display
    // request exactly, so a warmed page is a cache hit when
    // its turn comes.
    val context = LocalContext.current
    val screenWidthPx = remember(context) {
        context.resources.displayMetrics.widthPixels
    }
    LaunchedEffect(currentPage, mode, lowRam, pages, settings.cropBorders) {
        val loader = context.imageLoader
        for (index in MangaPreload.window(currentPage, pageCount, mode, lowRam)) {
            val page = pages.pages.getOrNull(index) ?: continue
            if (!MangaPageSources.needsNetwork(page)) continue
            // v1.5.0 (steer B3): zero speculative prefetch
            // for hostile-flagged hosts — pages load when
            // the reader actually reaches them.
            if (app.xreader.data.extension.HostPoliteness
                    .isHostileUrl(page.url)
            ) {
                continue
            }
            runCatching {
                loader.enqueue(
                    mangaPageRequest(
                        context, page.url, lowRam, screenWidthPx,
                        pages.referer, settings.cropBorders
                    )
                )
            }
        }
    }

    // v1.5.0 (Part C): the chrome seekbar's jump request —
    // consumed by whichever mode is on screen.
    var jumpRequest by remember(chapterKey) { mutableStateOf<Int?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        when (mode) {
            ImageReaderMode.WEBTOON -> WebtoonPages(
                pages = pages,
                chapterKey = chapterKey,
                startPage = anchorPage,
                restoreGeneration = restoreGeneration,
                lowRam = lowRam,
                screenWidthPx = screenWidthPx,
                palette = palette,
                hasNext = hasNext,
                settings = settings,
                externalTurns = externalTurns,
                jumpRequest = jumpRequest,
                assetFile = assetFile,
                onPosition = { page, fraction ->
                    currentPage = page
                    onPosition(page, fraction)
                },
                onReachedEnd = onReachedEnd,
                onNextChapter = onNextChapter,
                onToggleChrome = onToggleChrome
            )
            ImageReaderMode.PAGED, ImageReaderMode.PAGED_RTL,
            ImageReaderMode.PAGED_VERTICAL -> PagedPages(
                pages = pages,
                chapterKey = chapterKey,
                startPage = anchorPage,
                restoreGeneration = restoreGeneration,
                lowRam = lowRam,
                screenWidthPx = screenWidthPx,
                palette = palette,
                hasNext = hasNext,
                settings = settings,
                externalTurns = externalTurns,
                jumpRequest = jumpRequest,
                assetFile = assetFile,
                onPosition = { page, fraction ->
                    currentPage = page
                    onPosition(page, fraction)
                },
                onReachedEnd = onReachedEnd,
                onNextChapter = onNextChapter,
                onPreviousChapter = onPreviousChapter,
                onToggleChrome = onToggleChrome
            )
        }

        // v1.5.0 (Part C): the donor's colour filter — one
        // RGBA overlay above the pages, below the chrome
        // (Komikku's ReaderContentOverlay, adapted). A plain
        // non-interactive layer: touches fall through it.
        if (settings.colorFilter && settings.colorFilterArgb != 0L) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Color(
                            settings.colorFilterArgb.toInt()
                        )
                    )
            )
        }

        MangaChrome(
            visible = chromeVisible,
            reduceMotion = reduceMotion,
            palette = palette,
            bookTitle = bookTitle,
            chapterTitle = chapterTitle,
            chapterFooter = chapterFooter,
            pageLine = MangaProgress.pageLine(currentPage, pageCount),
            mode = mode,
            currentPage = currentPage,
            pageCount = pageCount,
            onJumpPage = { target -> jumpRequest = target },
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onBack = onBack,
            onOpenToc = onOpenToc,
            onOpenSettings = onOpenSettings,
            onPreviousChapter = onPreviousChapter,
            onNextChapter = onNextChapter,
            onModeChange = { newMode ->
                anchorPage = currentPage
                onModeChange(newMode)
            }
        )

        // The page position stays readable while the chrome is
        // hidden (a long webtoon strip is read mostly
        // chrome-less) — a small glass pill floated on the
        // same lift as the app's other bottom bars, so it
        // never lands in a dead screen strip.
        if (!chromeVisible) {
            Surface(
                shape = RoundedCornerShape(50),
                color = palette.background.copy(alpha = 0.85f),
                contentColor = palette.text,
                border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(bottom = FloatingChromeLift, end = 16.dp)
            ) {
                Text(
                    MangaProgress.pageLine(currentPage, pageCount),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** The Coil request for one manga page: the display and the
 * preload share this exact shape (same size, same cache key).
 * The width cap is [MangaDecode]'s — screen width on the
 * low-RAM tier, the reader-wide cap elsewhere. The app's
 * session-carrying call factory rides along automatically:
 * it is the loader's factory, not the request's.
 *
 * K6: [referer] rides as a request header when the page
 * record carries one (Keiyoushi player chapters stamp their
 * source's base URL — see ChapterPages.referer). That closes
 * the online half of the Referer gap: a CDN that demands
 * the source's Referer refused reader requests while the
 * same chapter downloaded fine (downloads fetch through the
 * source's own image request). The header is part of the
 * request shape, so display and preload must both pass it —
 * a warmed page stays a cache hit only if the shapes match. */
internal fun mangaPageRequest(
    context: Context,
    model: Any,
    lowRam: Boolean,
    screenWidthPx: Int,
    referer: String? = null,
    cropBorders: Boolean = false
): ImageRequest =
    ImageRequest.Builder(context)
        .data(model)
        .size(
            Size(
                Dimension.Pixels(MangaDecode.widthPx(lowRam, screenWidthPx)),
                Dimension.Undefined
            )
        )
        .apply {
            if (referer != null) addHeader("Referer", referer)
            // v1.5.0 (Part C): crop borders rides the request
            // (display and preload share the shape, so a
            // warmed page stays a cache hit).
            if (cropBorders) {
                transformations(CropBordersTransformation)
            }
        }
        .build()

/** The display model for one page: the local file when M3
 * has downloaded it, the remote URL otherwise — always
 * wrapped in the shared request shape. */
@Composable
private fun mangaPageModel(
    page: ChapterPage,
    lowRam: Boolean,
    screenWidthPx: Int,
    assetFile: (String) -> File,
    referer: String? = null,
    cropBorders: Boolean = false
): ImageRequest {
    val context = LocalContext.current
    return remember(page, lowRam, screenWidthPx, referer, cropBorders) {
        val model: Any = when (val source = MangaPageSources.sourceFor(page)) {
            is MangaPageSource.Local -> assetFile(source.fileName)
            is MangaPageSource.Remote -> source.url
        }
        mangaPageRequest(context, model, lowRam, screenWidthPx, referer, cropBorders)
    }
}

/**
 * v1.5.0 (Part C): Komikku's crop-borders, Xreader-native —
 * a Coil transformation that trims each page's uniform
 * margins per [BorderTrim]'s sampled scan (the donor trims
 * in its native decoder; the behaviour — uniform border
 * lines removed, content untouched — is the same). A page
 * whose scan finds nothing passes through unchanged.
 */
object CropBordersTransformation : coil.transform.Transformation {

    override val cacheKey: String = "xreader-crop-borders-v1"

    override suspend fun transform(
        input: android.graphics.Bitmap,
        size: coil.size.Size
    ): android.graphics.Bitmap {
        val insets = BorderTrim.scan(
            width = input.width,
            height = input.height,
            pixelAt = { x, y -> input.getPixel(x, y) }
        )
        if (insets.isEmpty) return input
        val width = input.width - insets.left - insets.right
        val height = input.height - insets.top - insets.bottom
        if (width <= 0 || height <= 0) return input
        return android.graphics.Bitmap.createBitmap(
            input, insets.left, insets.top, width, height
        )
    }
}

/** v1.5.0 (Part C): the page colour matrix for the
 * greyscale / invert switches (the donor's combined-paint
 * recipe): saturation 0 and/or the −1 invert matrix. Null
 * when neither is in force (the common case costs nothing). */
internal fun mangaColorFilter(
    grayscale: Boolean,
    invert: Boolean
): androidx.compose.ui.graphics.ColorFilter? {
    if (!grayscale && !invert) return null
    // The luminance row Android's saturation-0 matrix uses;
    // inversion after greyscale negates it and adds the
    // 255 offset (the two matrices composed by hand, so the
    // result is exact and allocation-free).
    val lr = 0.213f
    val lg = 0.715f
    val lb = 0.072f
    val values = when {
        grayscale && invert -> floatArrayOf(
            -lr, -lg, -lb, 0f, 255f,
            -lr, -lg, -lb, 0f, 255f,
            -lr, -lg, -lb, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
        grayscale -> floatArrayOf(
            lr, lg, lb, 0f, 0f,
            lr, lg, lb, 0f, 0f,
            lr, lg, lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        else -> floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    }
    return androidx.compose.ui.graphics.ColorFilter.colorMatrix(
        androidx.compose.ui.graphics.ColorMatrix(values)
    )
}

// ── Webtoon (vertical scroll) ────────────────────────────────

@Composable
private fun WebtoonPages(
    pages: ChapterPages,
    chapterKey: Long?,
    startPage: Int,
    restoreGeneration: Int,
    lowRam: Boolean,
    screenWidthPx: Int,
    palette: ReaderPalette,
    hasNext: Boolean,
    settings: MangaSettings,
    externalTurns: kotlinx.coroutines.flow.SharedFlow<Int>,
    jumpRequest: Int?,
    assetFile: (String) -> File,
    onPosition: (pageIndex: Int, fraction: Float) -> Unit,
    onReachedEnd: () -> Unit,
    onNextChapter: () -> Unit,
    onToggleChrome: () -> Unit
) {
    val pageCount = pages.size
    val listState = remember(chapterKey, restoreGeneration) {
        LazyListState(
            firstVisibleItemIndex = startPage.coerceIn(0, maxOf(0, pageCount - 1)),
            firstVisibleItemScrollOffset = 0
        )
    }
    val scope = rememberCoroutineScope()
    var currentPage by remember(chapterKey) { mutableStateOf(startPage) }
    val colorFilter = remember(settings.grayscale, settings.invertColors) {
        mangaColorFilter(settings.grayscale, settings.invertColors)
    }

    // The reading position: the page occupying the largest
    // share of the viewport is the current page; the fraction
    // rides the fractional page position (page + the part of
    // it already scrolled past).
    LaunchedEffect(listState, chapterKey) {
        snapshotFlow { listState.layoutInfo }
            .map { info ->
                webtoonReadingPosition(
                    items = info.visibleItemsInfo.map {
                        VisiblePageItem(it.index, it.offset, it.size)
                    },
                    viewportStart = info.viewportStartOffset,
                    viewportEnd = info.viewportEndOffset,
                    pageCount = pageCount
                )
            }
            .distinctUntilChanged()
            .collect { (page, pagePosition) ->
                currentPage = page
                onPosition(
                    page,
                    MangaProgress.fractionForPagePosition(pagePosition, pageCount)
                )
            }
    }
    // The end card in view = the strip's end reached.
    LaunchedEffect(listState, chapterKey) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { it.index >= pageCount }
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onReachedEnd() }
    }

    fun scrollByViewport(direction: Int) {
        val height = listState.layoutInfo.viewportSize.height
        if (height <= 0) return
        scope.launch {
            listState.animateScrollBy(direction * height * 0.9f)
        }
    }

    // Volume-key / hardware turns scroll the strip a screen.
    LaunchedEffect(chapterKey) {
        externalTurns.collect { direction ->
            scrollByViewport(if (direction >= 0) 1 else -1)
        }
    }
    // The chrome seekbar's jump.
    LaunchedEffect(jumpRequest) {
        jumpRequest?.let { target ->
            listState.animateScrollToItem(
                target.coerceIn(0, maxOf(0, pageCount - 1))
            )
        }
    }
    // Auto-scroll (the reader's shared auto-scroll settings).
    LaunchedEffect(
        settings.autoScrollEnabled, settings.autoScrollSpeed, chapterKey
    ) {
        if (settings.autoScrollEnabled) {
            val step = 1f + settings.autoScrollSpeed.coerceIn(1f, 10f)
            while (true) {
                listState.scrollBy(step)
                delay(16)
            }
        }
    }

    // ── Strip zoom (v1.5.0 Part C — the donor webtoon
    // viewer's double-tap / pinch zoom, Compose-native): a
    // graphicsLayer over the whole strip; double-tap toggles
    // 1× ↔ 2× about the tap point, two-finger pinch zooms,
    // and a mostly-horizontal drag pans while zoomed. The
    // list's own one-finger vertical scroll is untouched:
    // the gesture layer only consumes what it uses.
    var zoomScale by remember(chapterKey) { mutableStateOf(1f) }
    var zoomPanX by remember(chapterKey) { mutableStateOf(0f) }
    var zoomOrigin by remember(chapterKey) {
        mutableStateOf(androidx.compose.ui.unit.IntOffset(0, 0))
    }
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val minZoom = if (settings.webtoonDisableZoomOut) 1f else 0.5f

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .then(
                if (settings.webtoonDoubleTapZoom) {
                    Modifier.pointerInput(chapterKey) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                if (zoomScale > 1.01f) {
                                    zoomScale = 1f
                                    zoomPanX = 0f
                                } else {
                                    zoomOrigin = androidx.compose.ui.unit.IntOffset(
                                        offset.x.toInt(), offset.y.toInt()
                                    )
                                    zoomScale = 2f
                                }
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
            .then(
                if (settings.webtoonPinchZoom) {
                    Modifier.pointerInput(chapterKey) {
                        awaitPointerEventScope {
                            var previousDistance = 0f
                            while (true) {
                                val event = awaitPointerEvent(
                                    androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                )
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    val distance =
                                        kotlin.math.hypot(
                                            (a.x - b.x).toDouble(),
                                            (a.y - b.y).toDouble()
                                        ).toFloat()
                                    if (previousDistance > 0f) {
                                        val ratio = distance / previousDistance
                                        zoomScale = (zoomScale * ratio)
                                            .coerceIn(minZoom, 5f)
                                        if (zoomScale <= 1.001f) zoomPanX = 0f
                                    }
                                    previousDistance = distance
                                    event.changes.forEach { it.consume() }
                                } else {
                                    previousDistance = 0f
                                    // Pan while zoomed: a clearly
                                    // horizontal one-finger drag.
                                    val change = pressed.singleOrNull()
                                    if (change != null && zoomScale > 1.01f) {
                                        val delta = change.positionChange()
                                        if (kotlin.math.abs(delta.x) >
                                            kotlin.math.abs(delta.y) * 1.5f
                                        ) {
                                            zoomPanX += delta.x
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        val sidePadding = with(
            androidx.compose.ui.platform.LocalDensity.current
        ) {
            (viewportSize.width * settings.webtoonSidePadding / 100)
                .toDp()
        }
        LazyColumn(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = sidePadding
            ),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = zoomPanX
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                        if (viewportSize.width > 0) {
                            zoomOrigin.x.toFloat() / viewportSize.width
                        } else {
                            0.5f
                        },
                        if (viewportSize.height > 0) {
                            zoomOrigin.y.toFloat() / viewportSize.height
                        } else {
                            0.5f
                        }
                    )
                }
        ) {
            items(pageCount, key = { index -> "page-$index" }) { index ->
                val page = pages.pages[index]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .readerTapToggle("manga-$chapterKey-$index") { xFraction, yFraction ->
                            // Tap zones are viewport-relative:
                            // the callback's fractions are of the
                            // PAGE box, so translate the vertical
                            // one into the viewport first.
                            val info = listState.layoutInfo
                            val item = info.visibleItemsInfo
                                .firstOrNull { it.index == index }
                            val viewH = info.viewportSize.height
                                .coerceAtLeast(1)
                            val fractionY = if (item != null) {
                                (item.offset + yFraction * item.size) / viewH
                            } else {
                                yFraction
                            }
                            when (
                                MangaTapZones.action(
                                    layout = settings.tapLayout,
                                    invert = settings.tapInvert,
                                    smaller = settings.smallerTapZones,
                                    mode = settings.mode,
                                    x = xFraction,
                                    y = fractionY
                                )
                            ) {
                                MangaTapAction.MENU -> onToggleChrome()
                                MangaTapAction.NEXT,
                                MangaTapAction.RIGHT -> scrollByViewport(1)
                                MangaTapAction.PREV,
                                MangaTapAction.LEFT -> scrollByViewport(-1)
                            }
                        }
                ) {
                    AsyncImage(
                        model = mangaPageModel(
                            page, lowRam, screenWidthPx, assetFile,
                            pages.referer, settings.cropBorders
                        ),
                        contentDescription = "Page ${index + 1}",
                        contentScale = ContentScale.FillWidth,
                        colorFilter = colorFilter,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item(key = "end-card") {
                MangaEndCard(
                    palette = palette,
                    hasNext = hasNext,
                    onNextChapter = onNextChapter
                )
            }
        }
    }
}

/** One visible item of the webtoon strip, reduced to the
 * numbers the position maths needs (so the maths is
 * unit-testable without Compose layout types). */
internal data class VisiblePageItem(
    val index: Int,
    val offset: Int,
    val size: Int
)

/** (current page, fractional page position) from one layout
 * snapshot of the webtoon strip: the visible PAGE item with
 * the largest on-screen height wins; the fractional position
 * adds the part of it above the viewport's top edge. The end
 * card is not a page and never counts. An empty snapshot
 * reads as the first page. */
internal fun webtoonReadingPosition(
    items: List<VisiblePageItem>,
    viewportStart: Int,
    viewportEnd: Int,
    pageCount: Int
): Pair<Int, Float> {
    if (pageCount <= 0) return 0 to 0f
    var bestIndex = -1
    var bestVisible = -1
    var bestPosition = 0f
    for (item in items) {
        if (item.index >= pageCount) continue
        val visibleTop = maxOf(item.offset, viewportStart)
        val visibleBottom = minOf(item.offset + item.size, viewportEnd)
        val visible = visibleBottom - visibleTop
        if (visible > bestVisible) {
            bestVisible = visible
            bestIndex = item.index
            bestPosition = if (item.size > 0) {
                item.index + ((viewportStart - item.offset).toFloat() / item.size)
                    .coerceIn(0f, 1f)
            } else {
                item.index.toFloat()
            }
        }
    }
    if (bestIndex < 0) return 0 to 0f
    return bestIndex to bestPosition
}

// ── Paged ────────────────────────────────────────────────────

@Composable
private fun PagedPages(
    pages: ChapterPages,
    chapterKey: Long?,
    startPage: Int,
    restoreGeneration: Int,
    lowRam: Boolean,
    screenWidthPx: Int,
    palette: ReaderPalette,
    hasNext: Boolean,
    settings: MangaSettings,
    externalTurns: kotlinx.coroutines.flow.SharedFlow<Int>,
    jumpRequest: Int?,
    assetFile: (String) -> File,
    onPosition: (pageIndex: Int, fraction: Float) -> Unit,
    onReachedEnd: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onToggleChrome: () -> Unit
) {
    // The pager state is (re)created per chapter + restore by
    // keying the whole pager on them (rememberPagerState has
    // no keyed form of its own).
    key(chapterKey, restoreGeneration) {
        PagedPager(
            pages = pages,
            chapterKey = chapterKey,
            startPage = startPage,
            lowRam = lowRam,
            screenWidthPx = screenWidthPx,
            palette = palette,
            hasNext = hasNext,
            settings = settings,
            externalTurns = externalTurns,
            jumpRequest = jumpRequest,
            assetFile = assetFile,
            onPosition = onPosition,
            onReachedEnd = onReachedEnd,
            onNextChapter = onNextChapter,
            onPreviousChapter = onPreviousChapter,
            onToggleChrome = onToggleChrome
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PagedPager(
    pages: ChapterPages,
    chapterKey: Long?,
    startPage: Int,
    lowRam: Boolean,
    screenWidthPx: Int,
    palette: ReaderPalette,
    hasNext: Boolean,
    settings: MangaSettings,
    externalTurns: kotlinx.coroutines.flow.SharedFlow<Int>,
    jumpRequest: Int?,
    assetFile: (String) -> File,
    onPosition: (pageIndex: Int, fraction: Float) -> Unit,
    onReachedEnd: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onToggleChrome: () -> Unit
) {
    val pageCount = pages.size
    val rtl = settings.mode ==
        app.xreader.domain.reader.ImageReaderMode.PAGED_RTL
    val vertical = settings.mode ==
        app.xreader.domain.reader.ImageReaderMode.PAGED_VERTICAL
    // One extra pager page: the end card, so turning past the
    // last page lands somewhere honest (and the chapter is
    // finished by arriving there, like the text paged
    // reader's turn onto the next chapter).
    val pagerState = rememberPagerState(
        initialPage = startPage.coerceIn(0, maxOf(0, pageCount - 1)),
        pageCount = { pageCount + 1 }
    )
    var zoomActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colorFilter = remember(settings.grayscale, settings.invertColors) {
        mangaColorFilter(settings.grayscale, settings.invertColors)
    }
    val contentScale = when (settings.scaleType) {
        app.xreader.domain.reader.MangaScaleType.FIT -> ContentScale.Fit
        app.xreader.domain.reader.MangaScaleType.FIT_WIDTH ->
            ContentScale.FillWidth
        app.xreader.domain.reader.MangaScaleType.FIT_HEIGHT ->
            ContentScale.FillHeight
        app.xreader.domain.reader.MangaScaleType.STRETCH ->
            ContentScale.FillBounds
    }

    LaunchedEffect(pagerState, chapterKey) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settled ->
                if (settled >= pageCount) {
                    onReachedEnd()
                } else {
                    onPosition(
                        settled,
                        MangaProgress.fractionForPage(settled, pageCount)
                    )
                }
            }
    }

    fun turnBy(delta: Int) {
        val target = (pagerState.currentPage + delta)
            .coerceIn(0, pageCount)
        if (delta < 0 && pagerState.currentPage == 0) {
            onPreviousChapter()
        } else if (target != pagerState.currentPage) {
            scope.launch { pagerState.animateScrollToPage(target) }
        }
    }

    // Volume-key / hardware turns.
    LaunchedEffect(chapterKey) {
        externalTurns.collect { direction ->
            turnBy(if (direction >= 0) 1 else -1)
        }
    }
    // The chrome seekbar's jump.
    LaunchedEffect(jumpRequest) {
        jumpRequest?.let { target ->
            pagerState.animateScrollToPage(
                target.coerceIn(0, maxOf(0, pageCount - 1))
            )
        }
    }

    val pageContent: @Composable (Int) -> Unit = { index ->
        if (index >= pageCount) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MangaEndCard(
                    palette = palette,
                    hasNext = hasNext,
                    onNextChapter = onNextChapter
                )
            }
        } else {
            val page = pages.pages[index]
            Box(
                Modifier
                    .fillMaxSize()
                    .readerTapToggle("manga-paged-$chapterKey-$index") { xFraction, yFraction ->
                        if (!zoomActive) {
                            // v1.5.0 (Part C): the donor's tap
                            // zones (L-shape / right-and-left /
                            // Kindle-style / edges / off), the
                            // policy already mirrored for RTL.
                            // LEFT/RIGHT are screen moves; the
                            // policy's RTL swap keeps them
                            // reading-correct in both directions.
                            when (
                                MangaTapZones.action(
                                    layout = settings.tapLayout,
                                    invert = settings.tapInvert,
                                    smaller = settings.smallerTapZones,
                                    mode = settings.mode,
                                    x = xFraction,
                                    y = yFraction,
                                    rtl = rtl
                                )
                            ) {
                                MangaTapAction.MENU -> onToggleChrome()
                                MangaTapAction.PREV,
                                MangaTapAction.LEFT -> turnBy(-1)
                                MangaTapAction.NEXT,
                                MangaTapAction.RIGHT -> turnBy(1)
                            }
                        }
                    }
            ) {
                ZoomableImage(
                    model = mangaPageModel(
                        page, lowRam, screenWidthPx, assetFile,
                        pages.referer, settings.cropBorders
                    ),
                    contentDescription = "Page ${index + 1}",
                    contentScale = contentScale,
                    imageModifier = Modifier.fillMaxSize(),
                    doubleTapToZoom = settings.doubleTapToZoomPaged,
                    disableZoomOut = settings.disableZoomOut,
                    onZoomActiveChanged = { active -> zoomActive = active },
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (vertical) {
        androidx.compose.foundation.pager.VerticalPager(
            state = pagerState,
            userScrollEnabled = !zoomActive,
            modifier = Modifier.fillMaxSize()
        ) { index -> pageContent(index) }
    } else {
        HorizontalPager(
            state = pagerState,
            reverseLayout = rtl,
            userScrollEnabled = !zoomActive,
            modifier = Modifier.fillMaxSize()
        ) { index -> pageContent(index) }
    }
}

// ── Shared pieces ────────────────────────────────────────────

/** The chapter's end, in both modes: honest words, and the
 * next chapter one visible tap away when there is one. */
@Composable
private fun MangaEndCard(
    palette: ReaderPalette,
    hasNext: Boolean,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "End of chapter",
            style = MaterialTheme.typography.titleMedium,
            color = palette.text,
            textAlign = TextAlign.Center
        )
        if (hasNext) {
            XreaderPrimaryButton(
                text = "Next chapter",
                onClick = onNextChapter,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                "This is the last chapter for now.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The image reader's chrome, in the text reader's Nocturne
 * language (glass chips up top, one floating capsule below)
 * but only with what a manga chapter honestly has: back,
 * contents, reading settings, chapter turns, the page
 * position, and the reading-mode switch — visible, labelled,
 * never buried. The bottom capsule floats on the app's
 * shared lift above the navigation inset, clear of the
 * phone's dead bottom strip.
 */
@Composable
private fun MangaChrome(
    visible: Boolean,
    reduceMotion: Boolean,
    palette: ReaderPalette,
    bookTitle: String,
    chapterTitle: String,
    chapterFooter: String,
    pageLine: String,
    mode: ImageReaderMode,
    currentPage: Int,
    pageCount: Int,
    onJumpPage: (Int) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenSettings: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onModeChange: (ImageReaderMode) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = sharedAxisYEnter(reduceMotion = reduceMotion),
            exit = sharedAxisYExit(reduceMotion = reduceMotion)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MangaGlassChip(palette = palette, onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = palette.background.copy(alpha = 0.85f),
                    contentColor = palette.text,
                    border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(
                            bookTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.text,
                            maxLines = 1
                        )
                        Text(
                            chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            maxLines = 1
                        )
                    }
                }
                MangaGlassChip(palette = palette, onClick = onOpenToc) {
                    Icon(Icons.Filled.List, "Contents", tint = palette.text)
                }
                MangaGlassChip(palette = palette, onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, "Reading settings", tint = palette.text)
                }
            }
        }

        Box(Modifier.weight(1f))

        AnimatedVisibility(
            visible = visible,
            enter = sharedAxisYEnter(reduceMotion = reduceMotion),
            exit = sharedAxisYExit(reduceMotion = reduceMotion)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = FloatingChromeLift)
                    .padding(horizontal = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = palette.background.copy(alpha = 0.94f),
                    contentColor = palette.text,
                    border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MangaGlassChip(
                                palette = palette,
                                onClick = onPreviousChapter,
                                enabled = hasPrevious
                            ) {
                                Icon(Icons.Filled.SkipPrevious, "Previous chapter")
                            }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    pageLine,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.text,
                                    maxLines = 1
                                )
                                if (chapterFooter.isNotBlank()) {
                                    Text(
                                        chapterFooter,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.muted,
                                        maxLines = 1
                                    )
                                }
                            }
                            MangaGlassChip(
                                palette = palette,
                                onClick = onNextChapter,
                                enabled = hasNext
                            ) {
                                Icon(Icons.Filled.SkipNext, "Next chapter")
                            }
                        }
                        // v1.5.0 (Part C): the page seekbar —
                        // drag to any page in the chapter
                        // (the donor's reader slider).
                        if (pageCount > 1) {
                            androidx.compose.material3.Slider(
                                value = currentPage.toFloat(),
                                onValueChange = { value ->
                                    onJumpPage(value.toInt())
                                },
                                valueRange = 0f..(pageCount - 1).toFloat(),
                                colors = androidx.compose.material3
                                    .SliderDefaults.colors(
                                        thumbColor = palette.text,
                                        activeTrackColor = palette.text
                                            .copy(alpha = 0.85f),
                                        inactiveTrackColor = palette.text
                                            .copy(alpha = 0.25f)
                                    ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // The reading-mode switch: labelled
                        // choices, always visible with the
                        // chrome (the row scrolls sideways now
                        // that there are four modes). Changing
                        // it here rewrites THIS series only
                        // (the ViewModel stores the per-book
                        // override).
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(
                                    androidx.compose.foundation
                                        .rememberScrollState()
                                ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (option in ImageReaderMode.entries) {
                                val selected = option == mode
                                Surface(
                                    onClick = { onModeChange(option) },
                                    shape = RoundedCornerShape(50),
                                    color = if (selected) {
                                        palette.accent
                                    } else {
                                        palette.text.copy(alpha = 0.08f)
                                    },
                                    contentColor = if (selected) {
                                        palette.background
                                    } else {
                                        palette.text
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One frosted chip button in the reader's glass language
 * (the same shape as the text reader's top-bar chips). */
@Composable
private fun MangaGlassChip(
    palette: ReaderPalette,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = palette.background.copy(alpha = 0.85f),
        contentColor = palette.text,
        border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f))
    ) {
        Box(
            Modifier.padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}
