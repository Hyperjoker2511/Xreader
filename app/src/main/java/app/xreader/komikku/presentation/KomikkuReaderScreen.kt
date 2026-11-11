/*
 * Copyright 2024 The Komikku Authors
 * Copyright 2015 Javier Tomás
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * FIX-18 (v1.5.2): the transplanted presentation surface
 * (eu.kanade.presentation.reader.* over the viewer packages),
 * hosted by KomikkuReaderActivity.
 *
 * v1.5.7 (Komikku full): the Compose viewer surfaces are GONE.
 * The page flow is the donor's View reader (DonorReaderView:
 * RecyclerView pager/webtoon, SSIV page views, transition
 * items) hosted under this Compose chrome, exactly the donor
 * ReaderActivity's shape (View viewer + composeOverlay). The
 * chrome is the donor's: top bar with chapter bookmark toggle,
 * configurable bottom bar, ChapterNavigator + vertical page
 * scrubber, ExhUtils strip, centred page indicator, navigation
 * overlay, reading-mode toast, adjacent-chapter Loading dialog,
 * and the donor's dialogs (settings, chapter list, page
 * actions, reading-mode / orientation select). R-19 stands as
 * the port's deliberate choice: an initial chapter failure
 * shows the in-reader error with Try again / Back, not the
 * donor's toast-and-exit.
 */
package app.xreader.komikku.presentation

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.xreader.komikku.KomikkuReaderViewModel
import app.xreader.komikku.model.ReaderPage
import app.xreader.komikku.presentation.settings.ReaderSettingsDialog
import app.xreader.komikku.setting.ReaderOrientation
import app.xreader.komikku.setting.ReaderPreferences
import app.xreader.komikku.setting.ReadingMode
import app.xreader.komikku.viewer.DonorReaderView
import app.xreader.komikku.viewer.pager.PagerConfig
import app.xreader.komikku.viewer.pager.PagerDirection
import app.xreader.komikku.viewer.webtoon.WebtoonConfig
import java.io.File
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** One display unit: a page, or one half of a split wide page
 * (dualPageSplit), in reading order. Kept from the v1.5.2 port —
 * the donor viewer now computes the same units internally
 * (DonorReaderView.split items), this stays for its proofs. */
data class DisplayPage(val page: ReaderPage, val half: Int = 0) {
    val isSplit: Boolean get() = half >= 0 && page.fullPage
}

/** Dual-page split (donor PagerConfig.dualPageSplit): a wide page
 * (fullPage) becomes two display pages, left then right (swapped
 * when dualPageInvert). Pure — JVM-provable. */
fun displayPages(
    pages: List<ReaderPage>,
    dualPageSplit: Boolean,
    dualPageInvert: Boolean
): List<DisplayPage> {
    if (!dualPageSplit) return pages.map { DisplayPage(it, half = -1) }
    return pages.flatMap { page ->
        if (page.fullPage) {
            if (dualPageInvert) {
                listOf(DisplayPage(page, half = 1), DisplayPage(page, half = 0))
            } else {
                listOf(DisplayPage(page, half = 0), DisplayPage(page, half = 1))
            }
        } else {
            listOf(DisplayPage(page, half = -1))
        }
    }
}

/** The page colour matrix for grayscale / inverted colours (the
 * donor's combined-paint recipe). Null when neither is in force. */
fun komikkuColorFilter(grayscale: Boolean, invert: Boolean): ColorFilter? {
    if (!grayscale && !invert) return null
    val lr = 0.213f
    val lg = 0.715f
    val lb = 0.072f
    val matrix = when {
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
    return ColorFilter.colorMatrix(ColorMatrix(matrix))
}

/** Applies [filter] to everything drawn under this modifier, or
 * is a no-op when it is null. GraphicsLayerScope has no
 * colorFilter in Compose UI 1.7 (BOM 2024.12.01), so the whole
 * surface (incl. the hosted AndroidView) is drawn into an
 * offscreen layer whose Paint carries the filter:
 * Canvas.saveLayer + Paint.colorFilter is plain Canvas work
 * with no API floor (works at minSdk 26, no RenderEffect). */
private fun Modifier.colorFiltered(filter: ColorFilter?): Modifier =
    if (filter == null) {
        this
    } else {
        drawWithContent {
            val paint = Paint().apply { colorFilter = filter }
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
            try {
                drawContent()
            } finally {
                drawContext.canvas.restore()
            }
        }
    }

/** The reader theme's canvas colour (donor PagerConfig.themeToColor). */
fun komikkuCanvasColor(theme: Int, automaticIsDark: Boolean): Color = when (theme) {
    1 -> Color.Black
    2 -> Color(0xFF202125)
    3 -> if (automaticIsDark) Color.Black else Color.White
    else -> Color.White
}

@Composable
fun KomikkuReaderScreen(
    viewModel: KomikkuReaderViewModel,
    volumeTurns: SharedFlow<Int>,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val prefs = viewModel.scopedPreferences
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var chromeVisible by remember { mutableStateOf(false) }
    /** v1.5.8 (I4): chrome visibility before the settings
     * sheet's filter preview hid it — restored on leaving. */
    var chromeBeforeFilterPreview by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var chaptersVisible by remember { mutableStateOf(false) }
    var modeSelectVisible by remember { mutableStateOf(false) }
    var orientationSelectVisible by remember { mutableStateOf(false) }
    var exhVisible by remember { mutableStateOf(false) }
    var pageActionsPage by remember { mutableStateOf<ReaderPage?>(null) }
    var coverConfirmPage by remember { mutableStateOf<ReaderPage?>(null) }
    var autoScrollOn by remember { mutableStateOf(false) }
    var resubmitNonce by remember { mutableStateOf(0) }
    var hostRef by remember { mutableStateOf<DonorReaderView?>(null) }

    val isWebtoon = state.readingMode.type == ReadingMode.ViewerType.Webtoon
    val isVerticalViewer = isWebtoon || state.readingMode == ReadingMode.VERTICAL
    val pagerDirection = when (state.readingMode) {
        ReadingMode.RIGHT_TO_LEFT -> PagerDirection.R2L
        ReadingMode.VERTICAL -> PagerDirection.VERTICAL
        else -> PagerDirection.L2R
    }
    val pagerConfig = remember(state.readingMode) {
        if (!isWebtoon) PagerConfig(pagerDirection, scope, prefs) else null
    }
    val webtoonConfig = remember(state.readingMode) {
        if (isWebtoon) WebtoonConfig(scope, prefs) else null
    }
    val viewerConfig = pagerConfig ?: webtoonConfig

    val readerTheme by prefs.readerTheme().changes()
        .collectAsState(initial = prefs.readerTheme().get())
    val canvas = komikkuCanvasColor(readerTheme, automaticIsDark = true)
    val grayscale by prefs.grayscale().changes().collectAsState(initial = false)
    val inverted by prefs.invertedColors().changes().collectAsState(initial = false)
    val pageColorFilter = komikkuColorFilter(grayscale, inverted)

    // Fullscreen (donor General): hide the system bars.
    val fullscreen by prefs.fullscreen().changes().collectAsState(initial = true)
    LaunchedEffect(fullscreen) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, view)
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
    }

    // Orientation applies immediately (the Activity also applies
    // it on resume from the same resolution).
    LaunchedEffect(state.orientation) {
        (context as? Activity)?.requestedOrientation = state.orientation.flag
    }

    // Reading-mode toast (R-21): menus opening with "show
    // reading mode" off still announce the mode, by toast.
    val showReadingMode by prefs.showReadingMode().changes()
        .collectAsState(initial = true)
    LaunchedEffect(chromeVisible) {
        if (chromeVisible && !showReadingMode) {
            Toast.makeText(context, state.readingMode.label, Toast.LENGTH_SHORT).show()
        }
    }

    // Volume keys turn pages on BOTH surfaces (E-08) — the
    // Activity emits, the donor host consumes.
    LaunchedEffect(hostRef) {
        val host = hostRef ?: return@LaunchedEffect
        volumeTurns.collect { direction -> host.handleVolumeTurn(direction) }
    }

    // Page-layout preference changes rebuild the pager pairing.
    val pageLayout by prefs.pageLayout().changes()
        .collectAsState(initial = prefs.pageLayout().get())
    LaunchedEffect(pageLayout) { resubmitNonce++ }

    // Auto-scroll interval/smooth changes re-arm the engine.
    val autoScrollInterval by prefs.autoscrollInterval().changes()
        .collectAsState(initial = prefs.autoscrollInterval().get())
    val smoothAutoScroll by prefs.smoothAutoScroll().changes()
        .collectAsState(initial = prefs.smoothAutoScroll().get())
    LaunchedEffect(hostRef, autoScrollOn, autoScrollInterval, smoothAutoScroll) {
        hostRef?.setAutoScroll(autoScrollOn, autoScrollInterval, smoothAutoScroll)
    }

    val listener = remember(viewModel) {
        object : DonorReaderView.Listener {
            override fun onCurrentPageChanged(pageIndex: Int) =
                viewModel.onPageChanged(pageIndex)

            override fun onMenuTap() {
                chromeVisible = !chromeVisible
            }

            override fun onChapterCross(next: Boolean) {
                if (next) viewModel.nextChapter() else viewModel.previousChapter()
            }

            override fun onTransitionVisible(next: Boolean) {
                if (next) viewModel.preloadUpcoming()
            }

            override fun onLongPressPage(page: ReaderPage) {
                if (prefs.readWithLongTap().get()) {
                    pageActionsPage = page
                }
            }

            override fun onNearEnd() = viewModel.preloadUpcoming()

            override fun imageFileFor(page: ReaderPage): File? =
                viewModel.pageImageFile(page)
        }
    }

    // Navigation overlay (R-15): first run (one-shot, consumed
    // by ViewerConfig) or on every start per its preference.
    var navOverlayVisible by remember {
        mutableStateOf(
            (viewerConfig?.forceNavigationOverlay ?: false) ||
                (viewerConfig?.navigationOverlayOnStart ?: false)
        )
    }
    LaunchedEffect(viewerConfig) {
        navOverlayVisible = (viewerConfig?.forceNavigationOverlay ?: false) ||
            (viewerConfig?.navigationOverlayOnStart ?: false)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(canvas)
    ) {
        when {
            state.loading -> {
                // v1.5.8 (J15): the state kit — a loading state
                // says what is happening and that it is still
                // loading; never a bare spinner, never a verdict.
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    app.xreader.ui.common.LoadingState(
                        whatIsHappening = "Loading this chapter's pages"
                    )
                    // v1.5.10 (B4): past the bound the surface stops
                    // being inert — it says the chapter is taking
                    // long. Back works as always (leaving cancels
                    // the wait); no fake failure verdict.
                    if (state.stalled) {
                        androidx.compose.material3.Text(
                            text = app.xreader.data.extension.ReaderStall.STALL_MESSAGE,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
            }
            state.errorMessage != null && state.pageCount == 0 -> {
                // R-19 (deliberate, kept): in-reader error.
                // v1.5.8 (K5): the plain-words headline stays;
                // the recorded failure detail is one tap deeper
                // under "Details" when one was recorded.
                var detailsOpen by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        state.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (state.errorDetail != null) {
                        TextButton(onClick = { detailsOpen = !detailsOpen }) {
                            Text(if (detailsOpen) "Hide details" else "Details")
                        }
                        if (detailsOpen) {
                            Text(
                                state.errorDetail ?: "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    TextButton(onClick = { viewModel.retry() }) {
                        Text("Try again")
                    }
                    TextButton(onClick = onBack) { Text("Back") }
                }
            }
            state.pageCount > 0 -> {
                val viewerChapters = state.viewerChapters
                if (viewerChapters != null) {
                    AndroidView(
                        factory = { ctx ->
                            DonorReaderView(ctx).also { host ->
                                host.listener = listener
                                hostRef = host
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .colorFiltered(pageColorFilter)
                    )
                    LaunchedEffect(
                        hostRef, viewerChapters, state.readingMode, resubmitNonce
                    ) {
                        hostRef?.submit(
                            viewerChapters = viewerChapters,
                            mode = state.readingMode,
                            pagerConfig = pagerConfig,
                            webtoonConfig = webtoonConfig,
                            initialPage = state.currentPage
                        )
                    }
                }
            }
        }

        // Colour-filter overlay (donor ReaderContentOverlay:
        // the ARGB filter blended over the pages).
        val colorFilterOn by prefs.colorFilter().changes()
            .collectAsState(initial = false)
        if (colorFilterOn && state.pageCount > 0) {
            val filterValue by prefs.colorFilterValue().changes()
                .collectAsState(initial = 0)
            val filterMode by prefs.colorFilterMode().changes()
                .collectAsState(initial = 0)
            val blend = ReaderPreferences.ColorFilterMode
                .getOrNull(filterMode)?.second
                ?: androidx.compose.ui.graphics.BlendMode.SrcOver
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val paint = androidx.compose.ui.graphics.Paint().apply {
                    color = Color(filterValue)
                    blendMode = blend
                }
                drawContext.canvas.drawRect(
                    androidx.compose.ui.geometry.Rect(
                        androidx.compose.ui.geometry.Offset.Zero, size
                    ),
                    paint
                )
            }
        }

        FlashOverlay(viewModel, state.currentPage)

        // Page indicator (R-14): centred, stroked, primary.
        val showPageNumber by prefs.showPageNumber().changes()
            .collectAsState(initial = true)
        if (showPageNumber && state.pageCount > 0) {
            ReaderPageIndicator(
                currentPage = state.currentPage,
                totalPages = state.pageCount,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            )
        }

        // Tap-zone navigation overlay (R-15).
        if (navOverlayVisible && viewerConfig != null && state.pageCount > 0) {
            NavigationOverlay(
                navigator = viewerConfig.navigator,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { navOverlayVisible = false }
                    }
            )
        }

        // Chrome: top bar.
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val currentChapterId = state.chapters
                .getOrNull(state.chapterIndex)?.id
            ReaderTopBar(
                bookTitle = state.bookTitle,
                chapterTitle = state.currentChapter?.title ?: "",
                chapterBookmarked = currentChapterId != null &&
                    currentChapterId in state.bookmarkedChapterIds,
                onBack = onBack,
                onToggleBookmark = {
                    currentChapterId?.let { viewModel.toggleChapterBookmark(it) }
                }
            )
        }

        // Chrome: the vertical page scrubber (R-09) replaces the
        // horizontal navigator for vertical viewers in portrait
        // (donor NavBarType rule), left edge when left-handed.
        val forceHorizontal by prefs.forceHorizontalSeekbar().changes()
            .collectAsState(initial = false)
        val landscapeVertical by prefs.landscapeVerticalSeekbar().changes()
            .collectAsState(initial = false)
        val leftHanded by prefs.leftVerticalSeekbar().changes()
            .collectAsState(initial = false)
        val isLandscape = configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val useVerticalScrubber = chromeVisible && isVerticalViewer &&
            !forceHorizontal && (!isLandscape || landscapeVertical)
        if (useVerticalScrubber) {
            ChapterNavigatorVert(
                currentPage = state.currentPage,
                totalPages = state.pageCount,
                hasPreviousChapter = state.hasPreviousChapter,
                hasNextChapter = state.hasNextChapter,
                onSeekPage = { hostRef?.seekToPage(it) },
                onPreviousChapter = viewModel::previousChapter,
                onNextChapter = viewModel::nextChapter,
                modifier = Modifier.align(
                    if (leftHanded) Alignment.CenterStart else Alignment.CenterEnd
                )
            )
        }

        // Chrome: ExhUtils strip + horizontal navigator + bottom bar.
        AnimatedVisibility(
            visible = chromeVisible,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            // v1.5.35 (item 3): the whole bottom stack rides
            // the system navigation-bar inset (the donor's
            // bottom app bar carries
            // windowInsetsPadding(navigationBars)) — the
            // shipped stack padded nothing, so on
            // gesture-nav phones the bar sat in the nav
            // area the owner's standing rule reserves.
            Column(Modifier.navigationBarsPadding()) {
                if (exhVisible) {
                    ExhUtilsStrip(
                        autoScrollOn = autoScrollOn,
                        intervalSeconds = autoScrollInterval,
                        onToggleAutoScroll = { autoScrollOn = !autoScrollOn },
                        onRetryAll = {
                            val count = viewModel.retryAllErrored()
                            Toast.makeText(
                                context,
                                if (count > 0) {
                                    "Retrying $count pages"
                                } else {
                                    "No pages to retry"
                                },
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onBoostPage = { viewModel.boostCurrentPage() }
                    )
                }
                if (!useVerticalScrubber) {
                    ChapterNavigator(
                        currentPage = state.currentPage,
                        totalPages = state.pageCount,
                        hasPreviousChapter = state.hasPreviousChapter,
                        hasNextChapter = state.hasNextChapter,
                        onSeekPage = { hostRef?.seekToPage(it) },
                        onPreviousChapter = viewModel::previousChapter,
                        onNextChapter = viewModel::nextChapter
                    )
                }
                val buttons by prefs.readerBottomButtons().changes()
                    .collectAsState(
                        initial = app.xreader.komikku.setting
                            .ReaderBottomButton.BUTTONS_DEFAULTS
                    )
                val cropActive = when {
                    isWebtoon && state.readingMode ==
                        ReadingMode.CONTINUOUS_VERTICAL ->
                        prefs.cropBordersContinuousVertical().get()
                    isWebtoon -> prefs.cropBordersWebtoon().get()
                    else -> prefs.cropBorders().get()
                }
                ReaderBottomBar(
                    buttons = buttons,
                    cropBordersActive = cropActive,
                    isPager = !isWebtoon,
                    showShiftDoublePages = !isWebtoon &&
                        (pagerConfig?.doublePages == true),
                    onViewChapters = { chaptersVisible = true },
                    onShare = {
                        shareChapterLink(
                            context, state.bookTitle,
                            state.currentChapter?.title ?: ""
                        )
                    },
                    onReadingMode = { modeSelectVisible = true },
                    onRotation = { orientationSelectVisible = true },
                    onToggleCropBorders = {
                        when {
                            isWebtoon && state.readingMode ==
                                ReadingMode.CONTINUOUS_VERTICAL ->
                                prefs.cropBordersContinuousVertical()
                                    .set(!cropActive)
                            isWebtoon -> prefs.cropBordersWebtoon().set(!cropActive)
                            else -> prefs.cropBorders().set(!cropActive)
                        }
                        resubmitNonce++
                    },
                    onPageLayout = {
                        val next = (prefs.pageLayout().get() + 1) % 3
                        prefs.pageLayout().set(next)
                    },
                    onShiftDoublePages = {
                        pagerConfig?.let {
                            it.shiftDoublePage = !it.shiftDoublePage
                            resubmitNonce++
                        }
                    },
                    onToggleExhUtils = { exhVisible = !exhVisible },
                    onSettings = { settingsVisible = true }
                )
            }
        }
        if (chromeVisible && showReadingMode) {
            Text(
                state.readingMode.label,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, bottom = 4.dp)
            )
        }
    }

    // Adjacent-chapter Loading dialog (R-18).
    if (state.isLoadingAdjacent) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Loading chapter…") },
            text = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {}
        )
    }

    if (settingsVisible) {
        ReaderSettingsDialog(
            viewModel = viewModel,
            onDismiss = { settingsVisible = false },
            // v1.5.8 (I4): entering the filter tab hides the
            // chrome for the live preview; leaving RESTORES
            // the chrome visibility the reader had before.
            onCustomFilterTabVisible = { visible ->
                if (visible) {
                    chromeBeforeFilterPreview = chromeVisible
                    chromeVisible = false
                } else {
                    chromeVisible = chromeBeforeFilterPreview
                }
            }
        )
    }
    if (chaptersVisible) {
        ReaderChapterListDialog(
            chapters = state.chapters,
            currentIndex = state.chapterIndex,
            bookmarkedChapterIds = state.bookmarkedChapterIds,
            onSelect = { index ->
                chaptersVisible = false
                viewModel.jumpToChapter(index)
            },
            onToggleBookmark = { chapter ->
                viewModel.toggleChapterBookmark(chapter.id)
            },
            onDismiss = { chaptersVisible = false }
        )
    }
    if (modeSelectVisible) {
        val modes = ReadingMode.entries.filter { it != ReadingMode.DEFAULT }
        ModeSelectionDialog(
            title = "Reading mode",
            options = modes.map { it.label },
            selectedIndex = modes.indexOf(state.readingMode).coerceAtLeast(0),
            onSelect = { index ->
                modes.getOrNull(index)?.let { viewModel.onChangeReadingMode(it) }
                modeSelectVisible = false
            },
            onDismiss = { modeSelectVisible = false }
        )
    }
    if (orientationSelectVisible) {
        val orientations = ReaderOrientation.entries
        ModeSelectionDialog(
            title = "Rotation",
            options = orientations.map { it.label },
            selectedIndex = orientations.indexOf(state.orientation).coerceAtLeast(0),
            onSelect = { index ->
                orientations.getOrNull(index)?.let { viewModel.onChangeOrientation(it) }
                orientationSelectVisible = false
            },
            onDismiss = { orientationSelectVisible = false }
        )
    }
    pageActionsPage?.let { page ->
        ReaderPageActionsDialog(
            pageLabel = "Page ${page.index + 1}",
            secondPageLabel = null,
            onSetAsCover = {
                pageActionsPage = null
                coverConfirmPage = page
            },
            onCopy = {
                pageActionsPage = null
                val file = viewModel.pageImageFile(page)
                if (file != null) {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            copyPageToClipboard(context, file)
                        }
                        Toast.makeText(context, "Page copied", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(
                        context, "Page is still loading", Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onShare = {
                pageActionsPage = null
                val file = viewModel.pageImageFile(page)
                if (file != null) {
                    // v1.5.11 (transplant — Gemini hunk 5A,
                    // sift-corrected split): sharePage's staged
                    // file copy must not run on the main thread
                    // (the synchronous copyTo inside its
                    // stagedUri was a direct ANR contributor —
                    // the Page-1 long-press menu freeze), but
                    // its terminal startActivity(chooser) must
                    // not run on IO either. So: stage the copy
                    // on IO here — into the exact location
                    // PageActions.stagedUri stages into, under
                    // its own freshness rule (same name, copy
                    // only when missing or a different length),
                    // which makes sharePage's own staging a
                    // no-op when it runs — then call sharePage
                    // back on Main for the chooser intent alone.
                    scope.launch {
                        kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO
                        ) {
                            runCatching {
                                val dir = File(
                                    context.cacheDir, "quote-cards"
                                ).apply { mkdirs() }
                                val staged = File(dir, "page-${file.name}")
                                if (!staged.isFile ||
                                    staged.length() != file.length()
                                ) {
                                    file.copyTo(staged, overwrite = true)
                                }
                            }
                        }
                        sharePage(context, file)
                    }
                } else {
                    Toast.makeText(
                        context, "Page is still loading", Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onSave = {
                pageActionsPage = null
                val file = viewModel.pageImageFile(page)
                if (file != null) {
                    scope.launch {
                        val ok = kotlinx.coroutines.withContext(
                            kotlinx.coroutines.Dispatchers.IO
                        ) { savePageToDevice(context, file) }
                        Toast.makeText(
                            context,
                            if (ok) "Page saved" else "Couldn't save the page",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        context, "Page is still loading", Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { pageActionsPage = null }
        )
    }
    coverConfirmPage?.let { page ->
        AlertDialog(
            onDismissRequest = { coverConfirmPage = null },
            title = { Text("Set as cover?") },
            text = { Text("Use page ${page.index + 1} as this book's cover?") },
            confirmButton = {
                TextButton(onClick = {
                    coverConfirmPage = null
                    viewModel.setPageAsCover(page) { ok ->
                        Toast.makeText(
                            context,
                            if (ok) "Cover updated" else "Couldn't set the cover",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }) { Text("Set as cover") }
            },
            dismissButton = {
                TextButton(onClick = { coverConfirmPage = null }) { Text("Cancel") }
            }
        )
    }
}

/** Flash-on-page-change overlay (donor DisplayRefreshHost,
 * R-16): now hosted over the whole reader, every viewer. */
@Composable
private fun FlashOverlay(
    viewModel: KomikkuReaderViewModel,
    currentPage: Int
) {
    val prefs = viewModel.scopedPreferences
    val enabled by prefs.flashOnPageChange().changes().collectAsState(initial = false)
    if (!enabled) return
    val interval by prefs.flashPageInterval().changes().collectAsState(initial = 1)
    val duration by prefs.flashDurationMillis().changes().collectAsState(initial = 100)
    val flashColor by prefs.flashColor().changes()
        .collectAsState(initial = ReaderPreferences.FlashColor.BLACK)
    var alpha by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(currentPage) {
        if (currentPage > 0 && interval > 0 && currentPage % interval == 0) {
            alpha = 0.85f
            kotlinx.coroutines.delay(30)
            alpha = 0f
        }
    }
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = alpha,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = duration.coerceAtLeast(30)
        ),
        label = "flash"
    )
    if (animated > 0f) {
        val color = when (flashColor) {
            ReaderPreferences.FlashColor.WHITE -> Color.White
            ReaderPreferences.FlashColor.WHITE_BLACK ->
                if (currentPage % 2 == 0) Color.White else Color.Black
            else -> Color.Black
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(color.copy(alpha = animated))
        )
    }
}
