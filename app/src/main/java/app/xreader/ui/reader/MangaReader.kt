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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
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
    doubleTapToZoom: Boolean,
    disableZoomOut: Boolean,
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
    LaunchedEffect(currentPage, mode, lowRam, pages) {
        val loader = context.imageLoader
        for (index in MangaPreload.window(currentPage, pageCount, mode, lowRam)) {
            val page = pages.pages.getOrNull(index) ?: continue
            if (!MangaPageSources.needsNetwork(page)) continue
            runCatching {
                loader.enqueue(
                    mangaPageRequest(
                        context, page.url, lowRam, screenWidthPx,
                        pages.referer
                    )
                )
            }
        }
    }

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
                assetFile = assetFile,
                onPosition = { page, fraction ->
                    currentPage = page
                    onPosition(page, fraction)
                },
                onReachedEnd = onReachedEnd,
                onNextChapter = onNextChapter,
                onToggleChrome = onToggleChrome
            )
            ImageReaderMode.PAGED -> PagedPages(
                pages = pages,
                chapterKey = chapterKey,
                startPage = anchorPage,
                restoreGeneration = restoreGeneration,
                lowRam = lowRam,
                screenWidthPx = screenWidthPx,
                palette = palette,
                hasNext = hasNext,
                doubleTapToZoom = doubleTapToZoom,
                disableZoomOut = disableZoomOut,
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

        MangaChrome(
            visible = chromeVisible,
            reduceMotion = reduceMotion,
            palette = palette,
            bookTitle = bookTitle,
            chapterTitle = chapterTitle,
            chapterFooter = chapterFooter,
            pageLine = MangaProgress.pageLine(currentPage, pageCount),
            mode = mode,
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
    referer: String? = null
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
    referer: String? = null
): ImageRequest {
    val context = LocalContext.current
    return remember(page, lowRam, screenWidthPx, referer) {
        val model: Any = when (val source = MangaPageSources.sourceFor(page)) {
            is MangaPageSource.Local -> assetFile(source.fileName)
            is MangaPageSource.Remote -> source.url
        }
        mangaPageRequest(context, model, lowRam, screenWidthPx, referer)
    }
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

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        items(pageCount, key = { index -> "page-$index" }) { index ->
            val page = pages.pages[index]
            Box(
                Modifier
                    .fillMaxWidth()
                    .readerTapToggle("manga-$chapterKey-$index") { _, _ ->
                        onToggleChrome()
                    }
            ) {
                AsyncImage(
                    model = mangaPageModel(
                        page, lowRam, screenWidthPx, assetFile,
                        pages.referer
                    ),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.FillWidth,
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
    doubleTapToZoom: Boolean,
    disableZoomOut: Boolean,
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
            doubleTapToZoom = doubleTapToZoom,
            disableZoomOut = disableZoomOut,
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
    doubleTapToZoom: Boolean,
    disableZoomOut: Boolean,
    assetFile: (String) -> File,
    onPosition: (pageIndex: Int, fraction: Float) -> Unit,
    onReachedEnd: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
    onToggleChrome: () -> Unit
) {
    val pageCount = pages.size
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

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = !zoomActive,
        modifier = Modifier.fillMaxSize()
    ) { index ->
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
                    .readerTapToggle("manga-paged-$chapterKey-$index") { xFraction, _ ->
                        when {
                            zoomActive -> Unit
                            xFraction < EDGE_TAP_FRACTION -> {
                                if (pagerState.currentPage > 0) {
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            pagerState.currentPage - 1
                                        )
                                    }
                                } else {
                                    onPreviousChapter()
                                }
                            }
                            xFraction > 1f - EDGE_TAP_FRACTION -> {
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        pagerState.currentPage + 1
                                    )
                                }
                            }
                            else -> onToggleChrome()
                        }
                    }
            ) {
                ZoomableImage(
                    model = mangaPageModel(
                        page, lowRam, screenWidthPx, assetFile,
                        pages.referer
                    ),
                    contentDescription = "Page ${index + 1}",
                    contentScale = ContentScale.Fit,
                    imageModifier = Modifier.fillMaxSize(),
                    doubleTapToZoom = doubleTapToZoom,
                    disableZoomOut = disableZoomOut,
                    onZoomActiveChanged = { active -> zoomActive = active },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/** The tap-zone split for paged mode: the outer thirds turn,
 * the middle toggles the chrome (the text readers' tap
 * language). */
private const val EDGE_TAP_FRACTION = 1f / 3f

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
                        // The reading-mode switch: two labelled
                        // choices, always visible with the
                        // chrome. Changing it here rewrites
                        // THIS series only (the ViewModel
                        // stores the per-book override).
                        Row(
                            Modifier.fillMaxWidth(),
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
