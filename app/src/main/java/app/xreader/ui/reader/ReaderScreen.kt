package app.xreader.ui.reader

import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.error.AppResult
import app.xreader.core.motion.sharedAxisYEnter
import app.xreader.core.motion.sharedAxisYExit
import app.xreader.data.db.Highlight
import app.xreader.di.AppContainer
import app.xreader.domain.reader.EyeState
import app.xreader.domain.reader.ReaderMode
import app.xreader.domain.reader.WpmEstimator
import app.xreader.ui.common.EmptyState
import app.xreader.ui.theme.ReaderAmoled
import app.xreader.ui.theme.ReaderPalette
import app.xreader.ui.theme.ReaderPalettes
import app.xreader.ui.theme.aiAccentColor
import app.xreader.ui.theme.maxContrast
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ActiveSheet { SETTINGS, LISTEN, TOC, FOCUS }

private val EInkPalette = ReaderPalette(
    name = "e-Ink",
    background = Color.White,
    text = Color.Black,
    muted = Color(0xFF555555),
    accent = Color.Black
)

/**
 * The Phase 4 reader (PLAN §6): paged or scroll canvas under minimal
 * auto-hiding chrome, reader themes incl. true AMOLED, in-reader
 * brightness + warm light, volume-key turns, hybrid read-aloud,
 * focus timer, chapters/bookmarks/highlights, and the Labs eye
 * tracker. All position flows through the char-offset locator in the
 * ViewModel — this screen only renders and reports.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    container: AppContainer,
    reduceMotion: Boolean,
    assetFile: (bookId: Long, fileName: String) -> File,
    bookId: Long,
    onBack: () -> Unit,
    /** v1.0.1: the settings sheet's Customise jump. */
    onOpenCustomise: () -> Unit = {},
    /** v1.0.6.2 (F9b): Ask AI is a full page, not a sheet. */
    onOpenAiPage: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle(
        initialValue = null
    )
    val overlayState by viewModel.overlayState.collectAsStateWithLifecycle(
        initialValue = null
    )
    val focus by viewModel.focus.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val allHighlights by viewModel.highlights.collectAsStateWithLifecycle()
    val chapterSummary by viewModel.chapterSummary.collectAsStateWithLifecycle()

    // Phase 5: the Ask AI sheet brain, bound to the chapter on screen.
    val aiViewModel: app.xreader.ui.ai.AiSheetViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            key = "ai-$bookId",
            factory = app.xreader.ui.ai.AiSheetViewModel.factory(container, bookId)
        )
    LaunchedEffect(state.chapter?.id, state.chapterIndex, state.positionOffset) {
        val chapter = state.chapter
        if (chapter != null) {
            aiViewModel.bind(state.chapterIndex, chapter.id, state.positionOffset)
        }
    }
    // v1.0.2 (R17): stream activation follows the mode+transition
    // pair — entering Continuous scroll opens the stream at the
    // current chapter; leaving it returns to single-chapter loads.
    LaunchedEffect(config.mode, config.chapterTransition, state.chapters.size) {
        val wantsStream = config.mode == ReaderMode.SCROLL &&
            config.chapterTransition ==
            app.xreader.domain.reader.ChapterTransition.CONTINUOUS
        if (wantsStream && !state.streamActive && state.chapters.isNotEmpty()) {
            viewModel.openStream(state.chapterIndex, state.restoreOffset)
        } else if (!wantsStream && state.streamActive) {
            viewModel.openChapter(state.chapterIndex)
        }
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val basePalette = if (config.paletteName == "Custom" && !config.eInkMode) {
        // v1.0.1 custom colour pair (QuickNovel lesson): the user's
        // own page + text colours, muted/accent derived in-family.
        val page = androidx.compose.ui.graphics.Color(config.customPageArgb.toInt())
        val ink = androidx.compose.ui.graphics.Color(config.customTextArgb.toInt())
        app.xreader.ui.theme.ReaderPalette(
            name = "Custom",
            background = page,
            text = ink,
            muted = ink.copy(alpha = 0.62f),
            accent = ink
        )
    } else if (config.eInkMode) {
        EInkPalette
    } else {
        ReaderPalettes.firstOrNull { it.name == config.paletteName } ?: ReaderAmoled
    }
    // v1.0.6.2 (F8b reader parity): on the AMOLED theme the
    // reader's accent IS the active app colour theme's primary —
    // each app theme's reader counterpart, in family, without a
    // second palette list. Sepia/Paper keep their own warm accent
    // (they are the light-reading pair shared by all themes).
    val appPrimary = MaterialTheme.colorScheme.primary
    val palette = (
        if (basePalette.name == "AMOLED") {
            basePalette.copy(accent = appPrimary)
        } else {
            basePalette
        }
        ).maxContrast(config.highContrastText)
    val fontFamily = rememberReaderFontFamily(config, container)

    var chromeVisible by remember { mutableStateOf(true) }

    // v1.0.6.2 (F7): the chrome bars report their REAL measured
    // heights; the readers inset their text by exactly that much
    // while the bars are up (text never renders under chrome),
    // and by the bare safe-area insets while they are hidden
    // (full height for reading). No constants to drift.
    var topChromePx by remember { mutableIntStateOf(0) }
    var bottomChromePx by remember { mutableIntStateOf(0) }
    val densityNow = androidx.compose.ui.platform.LocalDensity.current
    val safeTop = WindowInsets.statusBars.asPaddingValues()
        .calculateTopPadding()
    val safeBottom = WindowInsets.navigationBars.asPaddingValues()
        .calculateBottomPadding()
    val readerTopInset = if (chromeVisible && topChromePx > 0) {
        with(densityNow) { topChromePx.toDp() }
    } else {
        safeTop
    }
    val readerBottomInset = if (chromeVisible && bottomChromePx > 0) {
        with(densityNow) { bottomChromePx.toDp() }
    } else {
        safeBottom
    }

    var activeSheet by remember { mutableStateOf<ActiveSheet?>(null) }

    // v1.0.1 reading comfort: the chrome fades away on its own after
    // a few seconds of reading (the return is the gentle engine-driven
    // AnimatedVisibility in ReaderChrome); any tap brings it back.
    // It never hides while a sheet is open or the chapter is loading.
    LaunchedEffect(chromeVisible, activeSheet, state.loading) {
        if (chromeVisible && activeSheet == null && !state.loading) {
            delay(4_000L)
            chromeVisible = false
        }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    var editingNoteFor by remember { mutableStateOf<Highlight?>(null) }
    var pendingNoteHighlight by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var noteText by remember { mutableStateOf("") }

    LaunchedEffect(aiViewModel) {
        aiViewModel.translationDone.collect { chapterId ->
            viewModel.onTranslationReady(chapterId)
            notice = "Translation ready — you're reading it now"
        }
    }

    // v1.0.6 (W3.4): surface the browser-view note through the
    // same transient notice as everything else, once.
    LaunchedEffect(state.browserViewNotice) {
        state.browserViewNotice?.let { text ->
            notice = text
            viewModel.consumeBrowserNotice()
        }
    }

    val chapterHighlights = remember(allHighlights, state.chapter?.id) {
        allHighlights.filter { it.chapterId == state.chapter?.id }
    }
    val spokenRange = ttsState?.let { tts ->
        if (tts.sentenceStart >= 0) tts.sentenceStart..tts.sentenceEnd - 1 else null
    }

    // -- Window behaviour: brightness + keep-screen-on -------------------
    DisposableEffect(config.brightness, config.keepScreenOn) {
        val activity = context as? Activity
        val window = activity?.window
        if (window != null) {
            val params = window.attributes
            params.screenBrightness = config.brightness
                ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
            if (config.keepScreenOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            if (window != null) {
                val params = window.attributes
                params.screenBrightness =
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = params
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // -- Lifecycle: flush progress/session when backgrounded -------------
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onReaderPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onReaderResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // -- Volume keys -------------------------------------------------------
    DisposableEffect(config.volumeKeyTurn) {
        if (config.volumeKeyTurn) {
            VolumeKeyTurn.handler = { direction -> viewModel.requestExternalTurn(direction) }
        }
        onDispose {
            if (config.volumeKeyTurn) VolumeKeyTurn.handler = null
        }
    }

    // -- Eye tracking (Labs): permission + pipeline -------------------------
    val eyeState by viewModel.eyeTracker.state.collectAsStateWithLifecycle()
    val eyeMessage by viewModel.eyeTracker.message.collectAsStateWithLifecycle()
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onEyePermissionResult(granted)
        if (granted) viewModel.eyeTracker.start(lifecycleOwner)
    }
    LaunchedEffect(config.eyeTracking) {
        if (config.eyeTracking) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.onEyePermissionResult(true)
                viewModel.eyeTracker.start(lifecycleOwner)
            } else if (eyeState == EyeState.OFF) {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }

    // -- Font import ---------------------------------------------------------
    val fontLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = queryDisplayName(context, uri) ?: "Imported font.ttf"
        scope.launch {
            when (val result = container.userFonts.import(context, uri, displayName)) {
                is AppResult.Ok -> {
                    viewModel.onFontImported(result.value)
                    notice = "Font added: ${result.value}"
                }
                is AppResult.Err -> {
                    notice = app.xreader.core.error.ErrorText.displayText(result.error)
                }
            }
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500L)
            notice = null
        }
    }

    // v1.0.1: everything in the reader subtree that doesn't name its
    // own colour (the error/empty state, sheet-adjacent text) inherits
    // the PALETTE's ink — never the app theme's, which may be the
    // opposite luminance (light app theme + AMOLED page rendered the
    // "Can't open this chapter" message dark-on-black).
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides palette.text
    ) {
    val readerFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { readerFocus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .focusRequester(readerFocus)
            .focusable()
            .onKeyEvent { event ->
                // R18/G18: hardware-keyboard turns, suspended
                // while a sheet or the chrome has focus.
                if (event.type != KeyEventType.KeyDown ||
                    activeSheet != null || chromeVisible ||
                    config.keyboardPreset ==
                    app.xreader.domain.reader.KeyboardPreset.OFF
                ) {
                    false
                } else {
                    val forward = when (event.key) {
                        Key.DirectionRight,
                        Key.DirectionDown,
                        Key.PageDown -> true
                        Key.Spacebar ->
                            config.keyboardPreset ==
                                app.xreader.domain.reader.KeyboardPreset.STANDARD
                        else -> false
                    }
                    val backward = when (event.key) {
                        Key.DirectionLeft,
                        Key.DirectionUp,
                        Key.PageUp -> true
                        else -> false
                    }
                    when {
                        forward -> {
                            viewModel.requestExternalTurn(1)
                            true
                        }
                        backward -> {
                            viewModel.requestExternalTurn(-1)
                            true
                        }
                        else -> false
                    }
                }
            }
    ) {
        // Warm-light overlay: UNDER the text since v1.0.1. It used to
        // be drawn over the glyphs, tinting ink toward the page and
        // washing dark-mode contrast out (15.9:1 → ~6.8:1 at full
        // strength on AMOLED). Over the page background only, the
        // page still warms while the ink stays pure (≥11:1).
        if (config.warmLight > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFF9A3C).copy(alpha = config.warmLight * 0.35f))
            )
        }

        when {
            state.loading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = palette.accent)
            }

            state.errorMessage != null -> EmptyState(
                title = "Can't open this chapter",
                message = state.errorMessage ?: "",
                modifier = Modifier.fillMaxSize(),
                // v1.0.6.2 (F3): a failed live fetch can simply
                // be tried again, right here.
                actionLabel = if (state.chapterFetchFailed) "Try again" else null,
                onAction = if (state.chapterFetchFailed) {
                    { viewModel.retryCurrentChapter() }
                } else {
                    null
                }
            )

            // v1.0.2 (R17): the endless stream — scroll mode with
            // the Continuous transition renders the window, not a
            // single chapter.
            state.streamActive && state.streamEntries.isNotEmpty() -> {
                ScrollStreamReader(
                    entries = state.streamEntries,
                    totalChapters = state.chapters.size,
                    config = config,
                    palette = palette,
                    fontFamily = fontFamily,
                    highlights = chapterHighlights,
                    restoreChapterIndex = state.chapterIndex,
                    restoreOffset = state.restoreOffset,
                    restoreGeneration = state.restoreGeneration,
                    externalTurns = viewModel.externalTurns,
                    assetFile = { assetFile(bookId, it) },
                    onStreamPosition = { chapterIndex, offset, fraction ->
                        viewModel.onStreamPosition(chapterIndex, offset, fraction)
                    },
                    onCenterTap = { chromeVisible = !chromeVisible },
                    onChromeWanted = { wanted -> chromeVisible = wanted },
                    onDownloadChapter = { index ->
                        viewModel.downloadStreamChapter(index)
                    },
                    topInset = readerTopInset,
                    bottomInset = readerBottomInset
                )
            }

            state.chapterText != null -> {
                val chapterText = state.chapterText!!
                if (config.mode == ReaderMode.PAGED) {
                    PagedReader(
                        chapterText = chapterText,
                        config = config,
                        palette = palette,
                        fontFamily = fontFamily,
                        tier = state.pageTurnTier,
                        highlights = chapterHighlights,
                        spokenRange = spokenRange,
                        overlayBlockId = state.overlayTextId,
                        restoreOffset = state.restoreOffset,
                        restoreHint = state.restoreHint,
                        restoreGeneration = state.restoreGeneration,
                        externalTurns = viewModel.externalTurns,
                        assetFile = { assetFile(bookId, it) },
                        onPosition = { offset -> viewModel.onPosition(offset, 0f) },
                        onNextChapter = { viewModel.nextChapter() },
                        onPreviousChapter = { viewModel.previousChapter() },
                        onCenterTap = { chromeVisible = !chromeVisible },
                        topInset = readerTopInset,
                        bottomInset = readerBottomInset
                    )
                } else {
                    ScrollReader(
                        chapterText = chapterText,
                        config = config,
                        palette = palette,
                        fontFamily = fontFamily,
                        highlights = chapterHighlights,
                        spokenRange = spokenRange,
                        overlayBlockId = state.overlayTextId,
                        restoreOffset = state.restoreOffset,
                        restoreScrollFraction = state.restoreScrollFraction,
                        restoreGeneration = state.restoreGeneration,
                        externalTurns = viewModel.externalTurns,
                        assetFile = { assetFile(bookId, it) },
                        onPosition = { offset, fraction ->
                            viewModel.onPosition(offset, fraction)
                        },
                        onReachedEnd = { viewModel.onScrollReachedEnd() },
                        autoContinue = config.chapterTransition !=
                            app.xreader.domain.reader.ChapterTransition.CHAPTER_BY_CHAPTER,
                        onParagraphAction = { blockIndex, action ->
                            val range = chapterText.rangeOf(blockIndex)
                            if (range != null) {
                                when (action) {
                                    ParagraphAction.HIGHLIGHT ->
                                        viewModel.addHighlight(range.start, range.end)
                                    ParagraphAction.NOTE ->
                                        pendingNoteHighlight = range.start to range.end
                                    ParagraphAction.BOOKMARK -> {
                                        viewModel.onPosition(range.start, 0f)
                                        viewModel.addBookmarkHere()
                                        notice = "Bookmarked this passage"
                                    }
                                }
                            }
                        },
                        onCenterTap = { chromeVisible = !chromeVisible },
                        onChromeWanted = { wanted -> chromeVisible = wanted },
                        topInset = readerTopInset,
                        bottomInset = readerBottomInset
                    )
                }
            }
        }

        ReaderChrome(
            visible = chromeVisible,
            palette = palette,
            bookTitle = state.bookTitle,
            chapterTitle = state.chapter?.title ?: "",
            chapterIndex = state.chapterIndex,
            chapterCount = state.chapters.size,
            bookFraction = state.bookFraction,
            minutesLeftInChapter = state.minutesLeftInChapter,
            minutesLeftInBook = state.minutesLeftInBook,
            reduceMotion = reduceMotion,
            bookmarked = state.currentPositionBookmarked,
            hasPrevious = state.hasPrevious,
            hasNext = state.hasNext,
            onBack = onBack,
            onToggleBookmark = {
                if (state.currentPositionBookmarked) {
                    viewModel.removeBookmarkAtCurrentPosition()
                    notice = "Bookmark removed"
                } else {
                    viewModel.addBookmarkHere()
                    notice = "Bookmarked"
                }
            },
            onPreviousChapter = { viewModel.previousChapter() },
            onNextChapter = { viewModel.nextChapter() },
            onSeekFraction = { fraction ->
                val target = (fraction * state.chapters.size).toInt()
                    .coerceIn(0, maxOf(0, state.chapters.lastIndex))
                viewModel.openChapter(target)
            },
            onOpenToc = { activeSheet = ActiveSheet.TOC },
            onOpenListen = { activeSheet = ActiveSheet.LISTEN },
            onOpenFocus = { activeSheet = ActiveSheet.FOCUS },
            onOpenSettings = { activeSheet = ActiveSheet.SETTINGS },
            onOpenAi = { onOpenAiPage() },
            onTopBarHeight = { px -> if (px > 0) topChromePx = px },
            onBottomBarHeight = { px -> if (px > 0) bottomChromePx = px }
        )

        // Eye-tracking status (Labs): small, honest, dismissible.
        if (config.eyeTracking && eyeState != EyeState.OFF) {
            Surface(
                shape = RoundedCornerShape(50),
                color = palette.background.copy(alpha = 0.92f),
                contentColor = palette.text,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 108.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when (eyeState) {
                            EyeState.ACTIVE -> "Eye tracking on — double-blink for next page"
                            else -> eyeStatusText(eyeState, eyeMessage)
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (eyeState == EyeState.FAILED || eyeState == EyeState.UNAVAILABLE ||
                        eyeState == EyeState.PERMISSION_DENIED
                    ) {
                        TextButton(
                            onClick = {
                                viewModel.updateConfig { it.copy(eyeTracking = false) }
                            }
                        ) { Text("Turn off") }
                    }
                }
            }
        }

        // v1.0.1 page overlay (QuickNovel lesson): a muted clock +
        // battery readout ON the page, only while the chrome is
        // hidden and only when the reader turned it on.
        if (config.showPageOverlay && !chromeVisible) {
            var overlayTick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(20_000L)
                    overlayTick++
                }
            }
            @Suppress("UNUSED_EXPRESSION") overlayTick
            val batteryPct = remember(overlayTick) {
                runCatching {
                    val intent = context.registerReceiver(
                        null,
                        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    )
                    val level = intent?.getIntExtra(
                        android.os.BatteryManager.EXTRA_LEVEL, -1
                    ) ?: -1
                    val scale = intent?.getIntExtra(
                        android.os.BatteryManager.EXTRA_SCALE, 100
                    ) ?: 100
                    if (level >= 0) (level * 100) / scale else -1
                }.getOrDefault(-1)
            }
            Text(
                java.time.LocalTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) +
                    if (batteryPct >= 0) " · $batteryPct%" else "",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        notice?.let { text ->
            Surface(
                shape = RoundedCornerShape(50),
                color = palette.background.copy(alpha = 0.92f),
                contentColor = palette.text,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // v1.0.6.2 (F7): float above the real bottom
                    // chrome (measured), not a guessed offset.
                    .padding(
                        bottom = if (chromeVisible) {
                            readerBottomInset + 12.dp
                        } else {
                            24.dp
                        }
                    )
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
    } // CompositionLocalProvider (palette ink for the reader subtree)

    // -- Sheets ---------------------------------------------------------------
    activeSheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            when (sheet) {
                ActiveSheet.SETTINGS -> ReaderSettingsSheet(
                    config = config,
                    effectiveTier = state.pageTurnTier,
                    userFonts = container.userFonts.list(),
                    eyeState = eyeState,
                    eyeMessage = eyeMessage,
                    onUpdate = { transform -> viewModel.updateConfig(transform) },
                    onOpenCustomise = {
                        activeSheet = null
                        onOpenCustomise()
                    },
                    onImportFont = { fontLauncher.launch(arrayOf("*/*")) },
                    settingsScope = state.settingsScope,
                    onScopeChange = { viewModel.setSettingsScope(it) },
                    hasBookOverride = state.hasBookOverride,
                    onClearBookOverride = { viewModel.clearBookOverride() },
                    onSaveWord = { word -> viewModel.saveWord(word) }
                )
                ActiveSheet.LISTEN -> ListenSheet(
                    ttsState = ttsState ?: app.xreader.data.reader.ReadAloudController.State(),
                    overlayState = overlayState ?: app.xreader.data.reader.OverlayPlayer.State(),
                    hasOverlay = state.hasOverlay,
                    speechRate = config.speechRate,
                    onTtsPlayPause = { viewModel.listenPlayPause() },
                    onTtsSkipNext = { viewModel.ttsSkipNext() },
                    onTtsSkipPrevious = { viewModel.ttsSkipPrevious() },
                    onOverlayPlayPause = { viewModel.overlayPlayPause() },
                    onOverlaySeek = { viewModel.overlaySeek(it) },
                    onOverlaySkip = { next -> viewModel.overlaySkip(next) },
                    onRateChange = { viewModel.setSpeechRate(it) },
                    onSleepChange = { viewModel.armSleep(it) }
                )
                ActiveSheet.TOC -> TocSheet(
                    chapters = state.chapters,
                    currentChapterIndex = state.chapterIndex,
                    bookmarks = bookmarks,
                    highlights = allHighlights,
                    chapterSummary = chapterSummary,
                    onSummarizeChapter = { aiViewModel.summarizeChapter() },
                    onJumpChapter = { index ->
                        activeSheet = null
                        viewModel.openChapter(index)
                    },
                    onJumpToPosition = { chapterIndex, offset ->
                        activeSheet = null
                        viewModel.openChapterAt(chapterIndex, offset)
                    },
                    onDeleteBookmark = { viewModel.deleteBookmark(it) },
                    onDeleteHighlight = { viewModel.deleteHighlight(it) },
                    onEditHighlightNote = { editingNoteFor = it },
                    onQuoteCard = { highlight ->
                        // Phase 6: render the quote card off the
                        // main thread, then hand it to the share sheet.
                        activeSheet = null
                        scope.launch {
                            val book = container.database.bookDao().getById(bookId)
                            val chapterLabel = state.chapters
                                .firstOrNull { it.id == highlight.chapterId }
                                ?.let { "Chapter ${it.index + 1}" } ?: ""
                            val cover = book?.coverPath?.let {
                                container.bookStorage.resolve(it)
                            }
                            val file = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.IO
                            ) {
                                app.xreader.data.export.QuoteCardRenderer.render(
                                    context = context,
                                    quote = highlight.quotedText,
                                    bookTitle = state.bookTitle,
                                    chapterLabel = chapterLabel,
                                    author = book?.author ?: "",
                                    coverFile = cover
                                )
                            }
                            val uri = androidx.core.content.FileProvider
                                .getUriForFile(
                                    context, "app.xreader.fileprovider", file
                                )
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_SEND
                            ).apply {
                                type = "image/png"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(
                                    android.content.Intent
                                        .FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    intent, "Share quote card"
                                )
                            )
                        }
                    }
                )
                ActiveSheet.FOCUS -> FocusSheet(
                    focus = focus,
                    onStart = { minutes, soundscape ->
                        viewModel.focusStart(minutes, soundscape)
                    },
                    onPauseResume = { viewModel.focusPauseResume() },
                    onStop = { viewModel.focusStop() },
                    onAcknowledgeDone = {
                        viewModel.focusAcknowledgeDone()
                        activeSheet = null
                    }
                )
            }
        }
    }

    // -- Note editor ------------------------------------------------------------
    val noteTarget = editingNoteFor
    val pendingRange = pendingNoteHighlight
    LaunchedEffect(noteTarget, pendingRange) {
        noteText = noteTarget?.note ?: ""
    }
    if (noteTarget != null || pendingRange != null) {
        AlertDialog(
            onDismissRequest = {
                editingNoteFor = null
                pendingNoteHighlight = null
            },
            title = { Text(if (noteTarget != null) "Highlight note" else "Note for this passage") },
            text = {
                TextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text("Write a note…") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (noteTarget != null) {
                            viewModel.updateHighlightNote(noteTarget, noteText)
                        } else if (pendingRange != null) {
                            viewModel.addHighlight(
                                pendingRange.first, pendingRange.second, noteText
                            )
                            notice = "Passage highlighted with your note"
                        }
                        editingNoteFor = null
                        pendingNoteHighlight = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        editingNoteFor = null
                        pendingNoteHighlight = null
                    }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ReaderChrome(
    visible: Boolean,
    reduceMotion: Boolean,
    palette: ReaderPalette,
    bookTitle: String,
    chapterTitle: String,
    chapterIndex: Int,
    chapterCount: Int,
    bookFraction: Float,
    minutesLeftInChapter: Int,
    minutesLeftInBook: Int,
    bookmarked: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onOpenToc: () -> Unit,
    onOpenListen: () -> Unit,
    onOpenFocus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAi: () -> Unit,
    /** v1.0.6.2 (F7): the bars' measured heights (px), so the
     * text insets match the real chrome, never a guess. */
    onTopBarHeight: (Int) -> Unit = {},
    onBottomBarHeight: (Int) -> Unit = {}
) {
    val chromeColor = palette.background.copy(alpha = 0.94f)
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = sharedAxisYEnter(reduceMotion = reduceMotion),
            exit = sharedAxisYExit(reduceMotion = reduceMotion)
        ) {
            Surface(
                color = chromeColor,
                contentColor = palette.text,
                modifier = Modifier.onSizeChanged { onTopBarHeight(it.height) }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Column(Modifier.weight(1f)) {
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
                    IconButton(onClick = onToggleBookmark) {
                        Icon(
                            if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            if (bookmarked) "Remove bookmark" else "Bookmark here",
                            tint = palette.accent
                        )
                    }
                    IconButton(onClick = onOpenListen) {
                        Icon(Icons.Filled.Headphones, "Listen", tint = palette.text)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, "Reading settings", tint = palette.text)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(
            visible = visible,
            enter = sharedAxisYEnter(reduceMotion = reduceMotion),
            exit = sharedAxisYExit(reduceMotion = reduceMotion)
        ) {
            Surface(
                color = chromeColor,
                contentColor = palette.text,
                modifier = Modifier.onSizeChanged { onBottomBarHeight(it.height) }
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        // v1.0.6.2 (F7 + lift): the bar floats —
                        // above the system navigation inset, plus
                        // the shared extra lift, so nothing sits
                        // at the screen's very bottom edge. The
                        // slider lives inside this padded area.
                        .androidx.compose.foundation.layout.navigationBarsPadding()
                        .padding(bottom = app.xreader.ui.theme.FloatingChromeLift)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    var sliderFraction by remember { mutableStateOf(bookFraction) }
                    var sliderDragging by remember { mutableStateOf(false) }
                    LaunchedEffect(bookFraction) {
                        if (!sliderDragging) sliderFraction = bookFraction
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = onPreviousChapter, enabled = hasPrevious) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous chapter")
                        }
                        Slider(
                            value = sliderFraction,
                            onValueChange = {
                                sliderDragging = true
                                sliderFraction = it
                            },
                            onValueChangeFinished = {
                                sliderDragging = false
                                onSeekFraction(sliderFraction)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onNextChapter, enabled = hasNext) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next chapter")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (chapterCount > 0) {
                                "Chapter ${chapterIndex + 1} of $chapterCount"
                            } else {
                                ""
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.muted
                        )
                        Text(
                            "${WpmEstimator.formatMinutes(minutesLeftInChapter)} in chapter · " +
                                "${WpmEstimator.formatMinutes(minutesLeftInBook)} in book",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.muted
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ChromeAction(Icons.Filled.List, "Chapters", palette, onOpenToc)
                        ChromeAction(Icons.Filled.Headphones, "Listen", palette, onOpenListen)
                        ChromeAction(
                            Icons.Filled.AutoAwesome, "Ask AI",
                            palette,
                            onOpenAi,
                            tint = aiAccentColor()
                        )
                        ChromeAction(Icons.Filled.Timer, "Focus", palette, onOpenFocus)
                        ChromeAction(Icons.Filled.Settings, "Display", palette, onOpenSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    palette: ReaderPalette,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = palette.text
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Icon(icon, label, tint = tint)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted
        )
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

/**
 * Volume-key page turns (PLAN §6): MainActivity forwards volume
 * presses here when the reader has registered (setting on). +1 =
 * next (Volume Down), -1 = previous (Volume Up) — the convention of
 * the major reader apps.
 */
object VolumeKeyTurn {
    @Volatile
    var handler: ((Int) -> Unit)? = null

    /** Called from MainActivity.dispatchKeyEvent; true = consumed. */
    fun onKey(keyCode: Int, isDown: Boolean): Boolean {
        val current = handler ?: return false
        if (!isDown) return false
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                current(1)
                true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                current(-1)
                true
            }
            else -> false
        }
    }
}
