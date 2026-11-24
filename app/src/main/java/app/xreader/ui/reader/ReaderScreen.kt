package app.xreader.ui.reader

import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Description
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.rememberTextMeasurer
import app.xreader.domain.reader.ChapterEndWall
import app.xreader.domain.reader.ChapterLayoutCache
import app.xreader.domain.reader.ChapterLayoutService
import app.xreader.domain.reader.ChapterTransition
import app.xreader.domain.reader.EyeState
import app.xreader.domain.reader.LayoutAheadPolicy
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderMode
import app.xreader.domain.reader.WpmEstimator
import app.xreader.ui.common.ElasticSlider
import app.xreader.ui.common.EmptyState
import app.xreader.ui.localreader.HIGHLIGHT_TINT_ARGB
import app.xreader.ui.theme.ReaderAmoled
import app.xreader.ui.theme.ReaderPalette
import app.xreader.ui.theme.ReaderPalettes
import app.xreader.ui.theme.aiAccentColor
import app.xreader.ui.theme.maxContrast
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private enum class ActiveSheet { SETTINGS, LISTEN, TOC, FOCUS, SEARCH }

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
    /** Patch 11: the settings sheet's "Terms for this book"
     * door — the per-book terms page, pushed from here. */
    onOpenBookTerms: () -> Unit = {},
    /** v1.0.6.2 (F9b): Ask AI is a full page, not a sheet. */
    onOpenAiPage: () -> Unit = {},
    /** v1.3.2 (A2): the one-time check / sign-in hand-off for
     * this book's source, when a chapter fetch hits the gate.
     * v1.3.4 (U3): the second value is the sanctioned screen's
     * target page (the source's reader page for the failed
     * chapter) when the config declares one, else null. */
    onSessionHandoff: (
        app.xreader.data.extension.SessionNeed.Kind, String?
    ) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle(
        initialValue = null
    )
    val overlayState by viewModel.overlayState.collectAsStateWithLifecycle(
        initialValue = null
    )
    val voiceUi by viewModel.voiceUi.collectAsStateWithLifecycle()
    val focus by viewModel.focus.collectAsStateWithLifecycle()
    // Code 120: the design switch picks this screen's chrome —
    // Nocturne's capsule (ReaderChrome) or the Material 3 form
    // (M3ReaderChromeBar: floating toolbar / top-and-bottom
    // bars). Everything else in the reader is design-neutral.
    val designState by container.designPrefs.state
        .collectAsStateWithLifecycle(
            initialValue = app.xreader.data.prefs.DesignState()
        )
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val allHighlights by viewModel.highlights.collectAsStateWithLifecycle()
    // v1.7 Patch 3: the prepared-EPUB surfaces — in-book
    // search (Item 4b), the plate viewer's filter (4f) and
    // the one-time migration note (3e).
    val searchHits by viewModel.searchHits.collectAsStateWithLifecycle()
    val searchBusy by viewModel.searchBusy.collectAsStateWithLifecycle()
    val plateFilter by viewModel.plateFilter.collectAsStateWithLifecycle()
    val footnotesInText by viewModel.footnotesInText
        .collectAsStateWithLifecycle()
    val migrationNote by viewModel.migrationNote.collectAsStateWithLifecycle()
    val cbzSwitchNote by viewModel.cbzSwitchNote.collectAsStateWithLifecycle()
    // v1.7 OE-1: a local PDF read in this one reader — its
    // page-shaped state rides beside the chapter state
    // (the surfaces, the chrome's page label and mode
    // switch, the PDF sheets all read it here).
    val pdfState by viewModel.pdfReader.collectAsStateWithLifecycle()
    val pdfSearchHits by viewModel.pdfSearchHits
        .collectAsStateWithLifecycle()
    val pdfSearchBusy by viewModel.pdfSearchBusy
        .collectAsStateWithLifecycle()
    val pdfSearchMark by viewModel.pdfSearchMark
        .collectAsStateWithLifecycle()
    val pdfPageHighlights by viewModel.pdfPageHighlights
        .collectAsStateWithLifecycle()
    val pdfReflowHighlights by viewModel.pdfReflowHighlights
        .collectAsStateWithLifecycle()
    val pdfReady = pdfState as? PdfReaderState.Ready
    val pdfPageLabel = pdfReady?.let {
        "p. ${it.page + 1} / ${it.pageCount}"
    }
    // The PDF's auto-scroll engine (the local reader's
    // own), hoisted here: the Pages surface's bodies
    // register with it; the settings section drives it.
    val pdfAutoScroll = remember {
        app.xreader.ui.localreader.AutoScrollController()
    }
    var pdfAutoScrollOn by remember {
        mutableStateOf(pdfAutoScroll.engine.running)
    }
    val pdfHandlers = remember(viewModel) {
        PdfPagesHandlers(
            jumps = viewModel.pdfJumps,
            onPosition = { page, fraction ->
                viewModel.onPdfPosition(page, fraction)
            },
            onGoToPage = { page -> viewModel.pdfGoToPage(page) },
            onUpdateSettings = { transform ->
                viewModel.updatePdfSettings(transform)
            }
        )
    }
    // Micro-build M: the chapter summary no longer shows
    // in the Contents sheet, so the screen no longer
    // collects it (the ViewModel flow itself stays — the
    // book page and AI surfaces read summaries their own
    // way).

    // v1.7 Moon+ standard fix: the display-local translations
    // (Part 2, A8) and the chapter-end wall's gate (Part 3).
    // state.chapterIndex stays the SEGMENT index everywhere
    // else — the seek slider, Listen, marks and search are
    // untouched; only the chrome counters, the Contents
    // sheet and the document surface's wall read these.
    val wallActive =
        config.chapterTransition == ChapterTransition.CHAPTER_BY_CHAPTER &&
            when (config.chapterEndWall) {
                ChapterEndWall.EVERY_BOOK -> true
                ChapterEndWall.OFF -> false
                ChapterEndWall.FOR_EPUB_BOOKS -> state.isEpubBook
            }
    // The TOC entry the live position has reached: the last
    // entry at or before (chapterIndex, positionOffset) —
    // the ViewModel's live char offset is the position
    // source. -1 before the first entry (front matter) and
    // for every book without a TOC.
    val currentTocIndex = if (state.tocEntries.isEmpty()) {
        -1
    } else {
        state.tocEntries.indexOfLast { entry ->
            entry.chapterIndex < state.chapterIndex ||
                (entry.chapterIndex == state.chapterIndex &&
                    entry.charOffset <= state.positionOffset)
        }
    }

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
    // v1.7 document engine: the windowed build's activation
    // effect stood here — it (re)opened the stream window at
    // the live place after every loadChapter-path event, and
    // carried the place across mode flips. It is retired
    // WITH the window: the document surface below is composed
    // directly from the published per-segment state, anchor
    // moves are the ViewModel's moveAnchor, and mode flips
    // are carried VM-side by its config collectors. There is
    // nothing here left to activate.
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
    // The settings sheet always opens on its index now (v1.2.0:
    // the old Theme tile that deep-linked to the "page" sub-page
    // folded into the sheet — see ReaderChrome's bottom bar).
    var settingsInitialPage by remember { mutableStateOf("index") }

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
    // v1.7 Patch 3 (Item 4f): the open plate, if any — an
    // illustration tap in any surface reports its asset file
    // through LocalImageTapHandler (provided at the reader
    // root below) into this state; the viewer renders last,
    // over everything, and Back or its Close chip dismisses.
    var plateFile by remember { mutableStateOf<java.io.File?>(null) }
    val imageTapHandler: (Any?) -> Unit = remember {
        { model: Any? ->
            (model as? java.io.File)?.let { plateFile = it }
        }
    }
    // Patch 11: the selection menu's dialog states. A menu tap
    // only ever OPENS one of these (or runs a read-only lookup);
    // the Replace dialog writes nothing until its own Save.
    var replaceText by remember { mutableStateOf<String?>(null) }
    var dictionaryResult by remember {
        mutableStateOf<Pair<String, String?>?>(null)
    }
    var selectionTranslation by remember {
        mutableStateOf<Pair<String, String?>?>(null)
    }

    // SelectionBar §6.4: the TRANSLATE / ASK_AI arms of
    // handleSelectionAction, extracted as local lambdas so
    // the PDF host's callbacks (PdfReaderHost's
    // onSelectionTranslate / onSelectionAskAi) run the SAME
    // code — one handler, one code path. Bodies verbatim
    // from the former arms.
    val translateSelection: (String) -> Unit = { text ->
        selectionTranslation = text to null
        scope.launch {
            val result = translateSelectionText(container, text)
            val open = selectionTranslation
            if (open != null && open.first == text) {
                selectionTranslation = text to result
            }
        }
    }
    val askAiAboutSelection: (String) -> Unit = { text ->
        aiViewModel.askAboutPassage(text)
        onOpenAiPage()
    }

    // Patch 11: the selection menu's screen-level actions,
    // each with the text the menu fetched at tap time.
    val handleSelectionAction: (ReaderSelectionAction, String) -> Unit =
        { action, text ->
            when (action) {
                ReaderSelectionAction.REPLACE -> replaceText = text
                ReaderSelectionAction.TRANSLATE -> {
                    translateSelection(text)
                }
                ReaderSelectionAction.DICTIONARY -> {
                    scope.launch {
                        // The EPUB Dictionary action's exact
                        // path: first word of the selection,
                        // the shared offline DictionaryLookup.
                        val word = text.trim()
                            .split(Regex("\\s+"))
                            .firstOrNull().orEmpty()
                        val definition = if (word.isBlank()) {
                            null
                        } else {
                            app.xreader.ui.library.DictionaryLookup
                                .lookup(context.filesDir, word)
                        }
                        dictionaryResult = word to definition
                    }
                }
                ReaderSelectionAction.SAVE_WORD -> {
                    viewModel.saveWord(text)
                    if (text.trim()
                            .trim('.', ',', '"', '\'', '’', '‘')
                            .length >= 2
                    ) {
                        notice = "Saved to Words I'm learning"
                    }
                }
                ReaderSelectionAction.ASK_AI -> {
                    askAiAboutSelection(text)
                }
            }
        }
    var editingNoteFor by remember { mutableStateOf<Highlight?>(null) }
    // v1.7 one-engine (A5): a pending note carries its chapter
    // — (chapterIndex, start, end) — so the note lands on the
    // paragraph's own chapter even if the reader has crossed
    // into another one before saving.
    // Code 117 (§8): the pending note now carries the model's
    // WHOLE selection — a range spanning chapters writes the
    // split set on save, the note text shared (§8's rule).
    var pendingNoteHighlight by remember {
        mutableStateOf<ReaderTextSelection?>(null)
    }
    var noteText by remember { mutableStateOf("") }

    // SelectionBar §9.2 (Revision 1): the reader back chain.
    // The dialog layer outranks the selection layer: while
    // any follow-on dialog state is open, BOTH the selection
    // handlers and the exit handler stand down and the
    // dialog's own dismissal (its onDismissRequest, back
    // included) handles the press — exactly one layer per
    // press, never a dialog closed AND the reader exited by
    // the same press.
    val coreDialogOpen = pendingNoteHighlight != null ||
        editingNoteFor != null || dictionaryResult != null ||
        selectionTranslation != null || replaceText != null
    var coreSelectionOpen by remember { mutableStateOf(false) }
    var pdfSelectionOpen by remember { mutableStateOf(false) }
    var pdfDialogOpen by remember { mutableStateOf(false) }
    var clearCoreSelection by remember { mutableIntStateOf(0) }
    var clearPdfSelection by remember { mutableIntStateOf(0) }
    // Exit handler FIRST, selection handlers SECOND: among
    // enabled BackHandlers the most recently composed wins,
    // so an armed selection consumes the press before exit
    // is reached; with no selection armed only exit is
    // enabled. (Composed here, ahead of the surface branch
    // and the plateFile block, so the plateFile BackHandler
    // further down still composes later and keeps winning
    // while the plate sheet is open — Revision 1, F2.)
    BackHandler(enabled = !coreDialogOpen && !pdfDialogOpen) {
        onBack()
    }
    BackHandler(enabled = coreSelectionOpen && !coreDialogOpen) {
        clearCoreSelection++
    }
    BackHandler(enabled = pdfSelectionOpen && !pdfDialogOpen) {
        clearPdfSelection++
    }

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

    // v1.7 Patch 3 (Item 3e): the migration note rides the
    // same transient notice, once — the ViewModel cleared the
    // record's pending flag as it took the note.
    LaunchedEffect(migrationNote) {
        migrationNote?.let { text ->
            notice = text
            viewModel.consumeMigrationNote()
        }
    }

    // v1.7 OE-4: the automatic CBZ fallback's one plain line,
    // through the same transient notice, once.
    LaunchedEffect(cbzSwitchNote) {
        cbzSwitchNote?.let { text ->
            notice = text
            viewModel.consumeCbzSwitchNote()
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

    // v1.5.0 (Part C): the manga reader's orientation lock —
    // applies only while an image chapter is open; leaving
    // restores the system default. (The text reader keeps
    // its own behaviour.)
    val mangaOrientation = state.mangaSettings.orientation
    DisposableEffect(state.mangaPages != null, mangaOrientation) {
        val activity = context as? Activity
        if (activity != null && state.mangaPages != null) {
            activity.requestedOrientation = when (mangaOrientation) {
                app.xreader.domain.reader.MangaOrientation.PORTRAIT ->
                    android.content.pm.ActivityInfo
                        .SCREEN_ORIENTATION_PORTRAIT
                app.xreader.domain.reader.MangaOrientation.LANDSCAPE ->
                    android.content.pm.ActivityInfo
                        .SCREEN_ORIENTATION_LANDSCAPE
                app.xreader.domain.reader.MangaOrientation.FREE ->
                    android.content.pm.ActivityInfo
                        .SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            if (state.mangaPages != null) {
                activity?.requestedOrientation =
                    android.content.pm.ActivityInfo
                        .SCREEN_ORIENTATION_UNSPECIFIED
            }
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
        androidx.compose.material3.LocalContentColor provides palette.text,
        // v1.7 Patch 3 (Item 4f): illustration taps anywhere in
        // the reader subtree open the plate viewer.
        LocalImageTapHandler provides imageTapHandler
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

        // v1.7 document engine (Item 2): ONE layout store for
        // the whole reader, hoisted here — the only place the
        // geometry BOTH surfaces consume can be computed. The
        // store's bound follows the tier gate (FULL/STANDARD
        // 64 segment layouts, MINIMAL 8 — LayoutAheadPolicy's
        // tiers); a bound change replaces the store (records
        // are accelerants — losing them costs speed, never a
        // place). The fingerprint is assembled ONCE here and
        // shared by both surfaces and the worker: (config,
        // font family, column width px, content height px,
        // App-size scale, translation selection) — the scroll
        // width and the single-page paged width are the SAME
        // width by construction, so one key family serves
        // both; the spread is the second family in the same
        // store. Widths: (maxWidth − margins) here ==
        // PagedReader's own (maxWidth − marginH×2); the spread
        // subtracts PagedReader's 36.dp column gap — the two
        // computations must stay tied (a drift would only cost
        // cache hits, never correctness: the key carries the
        // width).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val layoutBound = if (state.layoutAheadEnabled) 64 else 8
            val layoutService = remember(layoutBound) {
                ChapterLayoutService(cacheBound = layoutBound)
            }
            val singleColumnWidthPx = with(densityNow) {
                (maxWidth - config.marginHorizontalDp.dp * 2).toPx()
            }
            val spreadColumnWidthPx = with(densityNow) {
                ((maxWidth - config.marginHorizontalDp.dp * 2 -
                    36.dp) / 2).toPx()
            }
            val layoutContentHeightPx = with(densityNow) {
                (maxHeight - config.marginVerticalDp.dp * 2 -
                    readerTopInset - readerBottomInset).toPx()
            }
            val layoutFingerprint = remember(
                config, fontFamily, singleColumnWidthPx,
                layoutContentHeightPx, densityNow.fontScale,
                state.showingTranslation
            ) {
                listOf(
                    config, fontFamily, singleColumnWidthPx,
                    layoutContentHeightPx, densityNow.fontScale,
                    state.showingTranslation
                )
            }
            val spreadFingerprint = remember(
                config, fontFamily, spreadColumnWidthPx,
                layoutContentHeightPx, densityNow.fontScale,
                state.showingTranslation
            ) {
                listOf(
                    config, fontFamily, spreadColumnWidthPx,
                    layoutContentHeightPx, densityNow.fontScale,
                    state.showingTranslation
                )
            }
            // The layout-ahead worker (Item 2): measures the
            // published resident-run candidates in order —
            // anchor first — into the shared store, on
            // Dispatchers.Default, under the tier gate and the
            // 1.5 M-char cap. R4: it owns its OWN measurer, and
            // a visible-chapter request pre-empts it through
            // [preemptAheadLayout]. Each landed record bumps
            // layoutVersion — the document surface's stage-2
            // refinement re-checks the store when it moves.
            val aheadTextMeasurer = rememberTextMeasurer()
            val aheadChapterMeasurer = remember(
                aheadTextMeasurer, densityNow, fontFamily,
                singleColumnWidthPx, layoutContentHeightPx
            ) {
                ComposeChapterMeasurer(
                    textMeasurer = aheadTextMeasurer,
                    density = densityNow,
                    fontFamily = fontFamily,
                    columnWidthPx = singleColumnWidthPx,
                    contentHeightPx = layoutContentHeightPx
                )
            }
            var layoutVersion by remember { mutableIntStateOf(0) }
            var aheadLayoutJob by remember { mutableStateOf<Job?>(null) }
            LaunchedEffect(
                state.layoutAheadCandidates, layoutFingerprint,
                state.layoutAheadEnabled
            ) {
                aheadLayoutJob?.cancel()
                aheadLayoutJob = null
                if (!state.layoutAheadEnabled) return@LaunchedEffect
                val candidates = state.layoutAheadCandidates
                if (candidates.isEmpty()) return@LaunchedEffect
                aheadLayoutJob = scope.launch(Dispatchers.Default) {
                    for ((id, text) in candidates) {
                        ensureActive()
                        if (!LayoutAheadPolicy.withinCap(text.length)) {
                            continue
                        }
                        val key = ChapterLayoutCache.Key(
                            id,
                            ChapterLayoutCache.contentHash(text),
                            layoutFingerprint
                        )
                        if (layoutService.cache.get(key) != null) continue
                        val layout = runCatching {
                            layoutService.layout(
                                text, config, layoutContentHeightPx,
                                layoutFingerprint, aheadChapterMeasurer
                            )
                        }.getOrNull() ?: continue
                        layoutService.cache.put(key, layout)
                        layoutVersion++
                    }
                }
            }
            val preemptAheadLayout: () -> Unit =
                { aheadLayoutJob?.cancel() }
            // A4: records follow residency — a segment leaving
            // the published run loses its records (every key
            // variant); a fingerprint change clears the store
            // (the old entries are unreachable by key anyway).
            var recordRunIds by remember {
                mutableStateOf<Set<Long>>(emptySet())
            }
            LaunchedEffect(state.residentSegmentIds) {
                val gone = recordRunIds - state.residentSegmentIds
                gone.forEach { layoutService.cache.evictChapter(it) }
                recordRunIds = state.residentSegmentIds
            }
            var lastLayoutFingerprints by remember {
                mutableStateOf<Pair<List<Any?>, List<Any?>>?>(null)
            }
            LaunchedEffect(layoutFingerprint, spreadFingerprint) {
                val current = layoutFingerprint to spreadFingerprint
                if (lastLayoutFingerprints != null &&
                    lastLayoutFingerprints != current
                ) {
                    layoutService.cache.clear()
                }
                lastLayoutFingerprints = current
            }

        when {
            // v1.7 OE-1 (R1): a PDF's host stands FIRST —
            // its own states (loading, password, failed,
            // pages/reflow) never fall through to the
            // chapter branches below.
            pdfState != null -> PdfReaderHost(
                state = pdfState!!,
                viewModel = viewModel,
                palette = palette,
                config = config,
                fontFamily = fontFamily,
                handlers = pdfHandlers,
                autoScroll = pdfAutoScroll,
                chromeVisible = chromeVisible,
                bottomInset = readerBottomInset,
                spokenRange = spokenRange,
                reflowHighlights = pdfReflowHighlights,
                pageHighlights = pdfPageHighlights,
                searchMark = pdfSearchMark,
                onToggleControls = { chromeVisible = !chromeVisible },
                onBack = onBack,
                onSelectionTranslate = translateSelection,
                onSelectionAskAi = askAiAboutSelection,
                onSelectionActiveChanged = { pdfSelectionOpen = it },
                onDialogOpenChanged = { pdfDialogOpen = it },
                clearSelectionSignal = clearPdfSelection,
                motionEnabled = !reduceMotion
            )

            // v1.2.0 (Wave 3): the full-screen spinner shows ONLY
            // when there is no previous chapter to keep on screen
            // (first open). While a chapter turn fetches, the
            // ViewModel keeps the previous chapterText and the
            // reader branch below keeps rendering it.
            state.loading && state.chapterText == null -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // OE-3 (code-111 proof): the default 48dp spinner
                // read as "somewhat big" — 26dp everywhere here.
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = palette.accent,
                    strokeWidth = 2.5.dp
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

            // v1.5.5 (§4): a chapter of a local import that is
            // still being extracted in the background — a live
            // progress state, never an error. It fills in by
            // itself when the chapter lands.
            state.preparingChapter -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = palette.accent,
                    strokeWidth = 2.5.dp
                )
                androidx.compose.material3.Text(
                    text = "Preparing chapter…",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            state.errorMessage != null -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EmptyState(
                    title = "Can't open this chapter",
                    message = state.errorMessage ?: "",
                    modifier = Modifier.fillMaxWidth(),
                    // v1.3.2 (A2): a check / sign-in gate leads
                    // with the hand-off; retry stays available
                    // below it. Otherwise retry is the action,
                    // as before (v1.0.6.2 F3).
                    actionLabel = when {
                        state.sessionNeed != null -> null
                        state.chapterFetchFailed -> "Try again"
                        else -> null
                    },
                    onAction = if (state.sessionNeed == null &&
                        state.chapterFetchFailed
                    ) {
                        { viewModel.retryCurrentChapter() }
                    } else {
                        null
                    }
                )
                // v1.5.8 (K5): where ensureStored recorded a
                // failure detail for THIS chapter, it is one
                // tap deeper under "Details" — the headline
                // above stays plain words.
                val errorDetail = remember(state.chapterIndex, state.errorMessage) {
                    val chapterId = state.chapters
                        .getOrNull(state.chapterIndex)?.id
                    chapterId?.let {
                        app.xreader.komikku.ReaderErrorDetail.fromLog(
                            app.xreader.data.extension.DiagnosticLog.entries(),
                            it
                        )
                    }
                }
                if (errorDetail != null) {
                    var detailsOpen by remember { mutableStateOf(false) }
                    androidx.compose.material3.TextButton(
                        onClick = { detailsOpen = !detailsOpen }
                    ) {
                        androidx.compose.material3.Text(
                            if (detailsOpen) "Hide details" else "Details"
                        )
                    }
                    if (detailsOpen) {
                        androidx.compose.material3.Text(
                            text = errorDetail,
                            modifier = Modifier.padding(horizontal = 24.dp),
                            style = androidx.compose.material3
                                .MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (state.sessionNeed != null) {
                    app.xreader.ui.common.XreaderPrimaryButton(
                        text = if (
                            state.sessionNeed ==
                            app.xreader.data.extension.SessionNeed.Kind.SIGN_IN
                        ) {
                            "Sign in to read"
                        } else {
                            "Solve the check"
                        },
                        onClick = {
                            onSessionHandoff(
                                state.sessionNeed
                                    ?: app.xreader.data.extension
                                        .SessionNeed.Kind.SITE_CHECK,
                                state.sessionTargetUrl
                            )
                        },
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .fillMaxWidth()
                    )
                    if (state.chapterFetchFailed) {
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.retryCurrentChapter() }
                        ) {
                            androidx.compose.material3.Text("Try again without it")
                        }
                    }
                }
            }

            // v1.5.6 (§2.1 FAIL-CLOSED GUARD — this branch is a
            // guard, NOT a routing mechanism): routing is decided
            // BEFORE any reader opens, by ReaderRouteResolver at
            // every entry point, so a manga book should never
            // arrive here at all. If one does — a chapter this
            // reader loaded turns out to be an image chapter for
            // a book the resolver did not route to Komikku — the
            // novel reader does NOT render the pages as in-text
            // pictures (the silent-rendering fallback that was
            // the §1.1 defect, removed here): it hands off to
            // KomikkuReaderActivity as an explicit wrong-reader
            // correction and pops itself. Text chapters render
            // through the branches below, untouched.
            state.mangaPages != null -> {
                val mangaContext = androidx.compose.ui.platform
                    .LocalContext.current
                LaunchedEffect(state.mangaChapterId) {
                    android.util.Log.i(
                        "XreaderRouting",
                        "wrong-reader correction: image chapter " +
                            "for book $bookId reached the novel " +
                            "reader; handing off to Komikku"
                    )
                    mangaContext.startActivity(
                        app.xreader.komikku.KomikkuReaderActivity
                            .newIntent(
                                mangaContext,
                                mangaId = bookId,
                                chapterId = state.mangaChapterId
                                    ?: state.chapter?.id
                            )
                    )
                    onBack()
                }
                // No page rendering on this surface, ever: while
                // the correction is in flight the screen says what
                // is happening instead of drawing the chapter's
                // pages as pictures inside a text page.
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.material3.Text(
                        text = "Opening in the manga reader…",
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }

            // v1.7 OE-4: a local CBZ/CBR in this reader renders
            // its stored pages as a page world — never as
            // in-text pictures in the document flow (the branch
            // below), and never through the mangaPages detour
            // above: that guard backstops SOURCE manga, while a
            // covered comic arrives here on purpose (the
            // resolver's Book-reader verdict, or the automatic
            // fallback from the manga reader).
            state.cbzPages.isNotEmpty() -> {
                CbzPagesSurface(
                    pages = state.cbzPages,
                    initialPage = state.cbzPage,
                    jumps = viewModel.cbzJumps,
                    assetFile = { assetFile(bookId, it) },
                    onPage = { page -> viewModel.onCbzPage(page) },
                    onToggleChrome = { chromeVisible = !chromeVisible }
                )
            }

            // v1.7 document engine: in SCROLL mode the
            // document surface renders the WHOLE BOOK as
            // segments under BOTH transitions — it is the only
            // scroll surface. It composes from the published
            // per-segment state; there is no window and no
            // activation gap.
            config.mode == ReaderMode.SCROLL && state.chapterText != null -> {
                // Code 120 F1 (recheck of the L5 fix): the
                // design's default highlight tint, hoisted for
                // the two colour fulfilments below. Under
                // Material 3 an applied tint equal to it is
                // stored as the "scheme" token — the five fixed
                // tokens cannot name it, and the mapping's else
                // filed it as "amber", a colour the user never
                // picked. Under Nocturne the resolver returns
                // HIGHLIGHT_TINT_ARGB, and the fulfilments'
                // guard excludes that value, so no Nocturne
                // store can change.
                val m3DefaultTint = defaultHighlightTintArgb()
                DocumentReader(
                    chapters = state.chapters,
                    segmentStates = state.segmentStates,
                    segmentTexts = state.segmentTexts,
                    segmentFailedReasons = state.segmentFailedReasons,
                    segmentsWithOwnTitle = state.segmentsWithOwnTitle,
                    wallActive = wallActive,
                    onWallSwapNext = { viewModel.nextChapter() },
                    onWallSwapPrevious = {
                        viewModel.previousChapter()
                    },
                    config = config,
                    palette = palette,
                    fontFamily = fontFamily,
                    // Highlights reach the surface
                    // UN-pre-filtered — it filters per
                    // segment, so marks in chapters other than
                    // the current one render too.
                    highlights = allHighlights,
                    spokenRange = spokenRange,
                    overlayBlockId = state.overlayTextId,
                    anchorChapterIndex = state.chapterIndex,
                    restoreOffset = state.restoreOffset,
                    restoreHint = state.restoreHint,
                    restoreGeneration = state.restoreGeneration,
                    externalTurns = viewModel.externalTurns,
                    assetFile = { assetFile(bookId, it) },
                    layoutService = layoutService,
                    layoutFingerprint = layoutFingerprint,
                    layoutColumnWidthPx = singleColumnWidthPx,
                    layoutContentHeightPx = layoutContentHeightPx,
                    layoutVersion = layoutVersion,
                    onDocumentPosition = { chapterIndex, offset, fraction ->
                        viewModel.onDocumentPosition(chapterIndex, offset, fraction)
                    },
                    onChapterCrossed = { chapterIndex ->
                        viewModel.onChapterCrossed(chapterIndex)
                    },
                    // Code 117 (§7.3): the selected text,
                    // assembled from the model — a chapter
                    // index resolves through the chapter list
                    // to its ID, then to the segment text
                    // (segmentTexts is keyed by chapter ID,
                    // never by index). A spanning range joins
                    // its chapters' substrings with a blank
                    // line; within a chapter the flattened
                    // text already carries "\n\n" between
                    // blocks, so paragraph breaks come out as
                    // blank lines by construction.
                    selectionText = { sel ->
                        val parts = ArrayList<String>()
                        var complete = true
                        for (chapterIndex in
                            sel.startChapter..sel.endChapter) {
                            val chapterText = state.chapters
                                .getOrNull(chapterIndex)
                                ?.let { state.segmentTexts[it.id] }
                            if (chapterText == null) {
                                complete = false
                                break
                            }
                            val from = if (
                                chapterIndex == sel.startChapter
                            ) {
                                sel.start
                            } else {
                                0
                            }
                            val to = if (
                                chapterIndex == sel.endChapter
                            ) {
                                sel.end
                            } else {
                                chapterText.length
                            }
                            if (to > from) {
                                parts += chapterText.text.substring(
                                    from, to
                                )
                            }
                        }
                        if (complete) {
                            parts.joinToString("\n\n")
                        } else {
                            ""
                        }
                    },
                    // Code 117 (§8): true-range fulfilment —
                    // the bar's Highlight / Note / Bookmark
                    // arms run against the model's range, not
                    // a paragraph. A spanning range writes one
                    // Highlight per chapter segment (the split
                    // rule); a Note opens the editor once and
                    // splits on save; a Bookmark anchors at the
                    // range's true start.
                    onRangeAction = { sel, action ->
                        when (action) {
                            ParagraphAction.HIGHLIGHT -> {
                                for (chapterIndex in
                                    sel.startChapter..sel.endChapter) {
                                    val chapterText = state.chapters
                                        .getOrNull(chapterIndex)
                                        ?.let {
                                            state.segmentTexts[it.id]
                                        }
                                        ?: continue
                                    val from = if (
                                        chapterIndex == sel.startChapter
                                    ) {
                                        sel.start
                                    } else {
                                        0
                                    }
                                    val to = if (
                                        chapterIndex == sel.endChapter
                                    ) {
                                        sel.end
                                    } else {
                                        chapterText.length
                                    }
                                    if (from < to) {
                                        viewModel.addHighlightInChapter(
                                            chapterIndex, from, to
                                        )
                                    }
                                }
                            }
                            ParagraphAction.NOTE ->
                                pendingNoteHighlight = sel
                            ParagraphAction.BOOKMARK -> {
                                viewModel.addBookmarkInChapter(
                                    sel.startChapter, sel.start
                                )
                                notice = "Bookmarked this passage"
                            }
                        }
                    },
                    // Code 118: the colour row's apply for a
                    // live selection — the Highlight arm's
                    // split loop, writing the picked tint's
                    // token instead of the constant.
                    onApplyHighlight = { sel, tintArgb ->
                        // F1: the design default under M3 is
                        // stored as "scheme" (see the hoist
                        // above); every other tint keeps the
                        // shipped token mapping.
                        val token = if (
                            designState.design ==
                                app.xreader.data.prefs.AppDesign
                                    .MATERIAL3 &&
                            tintArgb == m3DefaultTint &&
                            tintArgb != HIGHLIGHT_TINT_ARGB
                        ) {
                            "scheme"
                        } else {
                            highlightTokenForTint(tintArgb)
                        }
                        for (chapterIndex in
                            sel.startChapter..sel.endChapter) {
                            val chapterText = state.chapters
                                .getOrNull(chapterIndex)
                                ?.let { state.segmentTexts[it.id] }
                                ?: continue
                            val from = if (
                                chapterIndex == sel.startChapter
                            ) {
                                sel.start
                            } else {
                                0
                            }
                            val to = if (
                                chapterIndex == sel.endChapter
                            ) {
                                sel.end
                            } else {
                                chapterText.length
                            }
                            if (from < to) {
                                viewModel.addHighlightInChapter(
                                    chapterIndex, from, to,
                                    color = token
                                )
                            }
                        }
                    },
                    // Code 118: the existing-highlight bar's
                    // doors — re-tint, remove, and the note
                    // editor, the same ViewModel/state doors
                    // the Contents sheet uses.
                    onUpdateHighlightColor = { highlight, tintArgb ->
                        viewModel.updateHighlightColor(
                            highlight,
                            // F1: the same "scheme" store as
                            // the apply door above — a circle-1
                            // re-tint under M3 names the design
                            // default, not "amber".
                            if (
                                designState.design ==
                                    app.xreader.data.prefs
                                        .AppDesign.MATERIAL3 &&
                                tintArgb == m3DefaultTint &&
                                tintArgb != HIGHLIGHT_TINT_ARGB
                            ) {
                                "scheme"
                            } else {
                                highlightTokenForTint(tintArgb)
                            }
                        )
                    },
                    onRemoveHighlight = {
                        viewModel.deleteHighlight(it)
                    },
                    onEditHighlightNote = { editingNoteFor = it },
                    onCenterTap = { chromeVisible = !chromeVisible },
                    onChromeWanted = { wanted -> chromeVisible = wanted },
                    onSelectionAction = handleSelectionAction,
                    onSelectionOpenChanged = { coreSelectionOpen = it },
                    clearSelectionSignal = clearCoreSelection,
                    motionEnabled = !reduceMotion,
                    onDownloadChapter = { chapterId ->
                        viewModel.downloadSegmentChapter(chapterId)
                    },
                    onRetryChapter = { chapterId ->
                        viewModel.retrySegment(chapterId)
                    },
                    onSegmentVisible = { chapterId ->
                        viewModel.onSegmentVisible(chapterId)
                    },
                    onSegmentApproached = { chapterId ->
                        viewModel.onSegmentApproached(chapterId)
                    },
                    onBoundaryEndVisible = { viewModel.onBoundaryEndVisible() },
                    onChapterEndReached = {
                        viewModel.markCurrentChapterFinished()
                    },
                    topInset = readerTopInset,
                    bottomInset = readerBottomInset
                )
            }

            state.chapterText != null -> {
                val chapterText = state.chapterText!!
                // v1.7 one-engine (C1): Paged is the only
                // single-chapter surface left — the retired
                // ScrollReader is gone; in SCROLL mode the
                // unified stream branch above owns the screen,
                // and this branch is only the brief gap before
                // activation, which renders nothing rather than
                // a second, competing surface.
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
                        onPosition = { offset, fraction ->
                            viewModel.onPosition(offset, fraction)
                        },
                        onNextChapter = { viewModel.nextChapter() },
                        onPreviousChapter = { viewModel.previousChapter() },
                        onCenterTap = { chromeVisible = !chromeVisible },
                        topInset = readerTopInset,
                        bottomInset = readerBottomInset,
                        // v1.7 Patch 2 (item 2): the layout
                        // cache key's chapter id.
                        // v1.7 document engine (Item 2): the
                        // shared store + fingerprints +
                        // geometry, hoisted above — paged is
                        // sliced from the same records the
                        // document surface and the worker use.
                        chapterId = state.chapter?.id ?: -1L,
                        layoutService = layoutService,
                        layoutFingerprint = layoutFingerprint,
                        spreadFingerprint = spreadFingerprint,
                        singleColumnWidthPx = singleColumnWidthPx,
                        spreadColumnWidthPx = spreadColumnWidthPx,
                        layoutContentHeightPx = layoutContentHeightPx,
                        preemptAheadLayout = preemptAheadLayout
                    )
                }
            }
        }
        } // BoxWithConstraints (the hoisted layout store's scope)

        // Manga programme (slice M2): while the image reader
        // owns the screen it draws its OWN chrome (its bottom
        // bar carries the page position and the mode switch);
        // the text chrome — seek slider, Listen, time
        // estimates — has nothing honest to say about a page
        // list and stays away.
        if (state.mangaPages == null) {
        if (designState.design ==
            app.xreader.data.prefs.AppDesign.MATERIAL3
        ) {
        M3ReaderChromeBar(
            visible = chromeVisible,
            chromeStyle = designState.readerChrome,
            bookTitle = state.bookTitle,
            // v1.1.2: the header's chapter line and the footer's
            // both derive from the chapter's POSITION through
            // ReaderCounters — the source-given name is shown
            // only when it does not contradict that position.
            chapterTitle = if (pdfReady != null) {
                // OE-1: a PDF's header second line is the
                // outline section the page sits in (or
                // nothing) — never a chapter line.
                pdfReady.anchorTitle ?: ""
            } else {
                ReaderCounters.headerLine(
                    chapterTitle = state.chapter?.title ?: "",
                    chapterIndex = state.chapterIndex,
                    chapterCount = state.chapters.size,
                    // v1.3.2 (item 79): the chapter's OWN number —
                    // the header agrees with the chapter that was
                    // actually opened, never with list position.
                    ownNumber = state.chapter?.let {
                        app.xreader.domain.reader.ChapterDisplayOrder.numberOf(it)
                    }
                )
            },
            // Part 2 (A8): for a TOC book the counters count
            // TOC ENTRIES — the position is the current TOC
            // entry, the count is the TOC's size, and there
            // is no own number (the label must be the TOC
            // position, never a spine number the reader never
            // sees). Non-TOC books pass exactly today's
            // values.
            chapterIndex = if (state.tocEntries.isNotEmpty()) {
                maxOf(0, currentTocIndex)
            } else {
                state.chapterIndex
            },
            chapterCount = if (state.tocEntries.isNotEmpty()) {
                state.tocEntries.size
            } else {
                state.chapters.size
            },
            chapterOwnNumber = if (state.tocEntries.isNotEmpty()) {
                null
            } else {
                state.chapter?.let {
                    app.xreader.domain.reader.ChapterDisplayOrder.numberOf(it)
                }
            },
            bookFraction = pdfReady?.bookFraction ?: state.bookFraction,
            minutesLeftInChapter = state.minutesLeftInChapter,
            minutesLeftInBook = state.minutesLeftInBook,
            showTimeEstimates = pdfReady == null &&
                ReaderCounters.showTimeEstimates(
                    state.chapterText?.content
                ),
            bookmarked = if (pdfReady != null) {
                viewModel.pdfBookmarkedHere(bookmarks)
            } else {
                state.currentPositionBookmarked
            },
            hasPrevious = when {
                pdfReady != null -> pdfReady.page > 0
                // v1.7 OE-4: in the CBZ page world the turn
                // buttons turn pages.
                state.cbzPages.isNotEmpty() -> state.cbzPage > 0
                else -> state.hasPrevious
            },
            hasNext = when {
                pdfReady != null -> pdfReady.page < pdfReady.pageCount - 1
                state.cbzPages.isNotEmpty() ->
                    state.cbzPage < state.cbzPages.lastIndex
                else -> state.hasNext
            },
            onBack = onBack,
            onToggleBookmark = {
                if (pdfReady != null) {
                    // OE-1 (R19): the bookmark is the page.
                    val wasBookmarked =
                        viewModel.pdfBookmarkedHere(bookmarks)
                    viewModel.togglePdfBookmark()
                    notice = if (wasBookmarked) {
                        "Bookmark removed"
                    } else {
                        "Bookmarked"
                    }
                } else if (state.currentPositionBookmarked) {
                    viewModel.removeBookmarkAtCurrentPosition()
                    notice = "Bookmark removed"
                } else {
                    viewModel.addBookmarkHere()
                    notice = "Bookmarked"
                }
            },
            onPreviousChapter = {
                when {
                    pdfReady != null ->
                        viewModel.pdfGoToPage(pdfReady.page - 1)
                    state.cbzPages.isNotEmpty() ->
                        viewModel.cbzGoToPage(state.cbzPage - 1)
                    else -> viewModel.previousChapter()
                }
            },
            onNextChapter = {
                when {
                    pdfReady != null ->
                        viewModel.pdfGoToPage(pdfReady.page + 1)
                    state.cbzPages.isNotEmpty() ->
                        viewModel.cbzGoToPage(state.cbzPage + 1)
                    else -> viewModel.nextChapter()
                }
            },
            onSeekFraction = { fraction ->
                when {
                    pdfReady != null -> {
                        val target = (fraction * pdfReady.pageCount).toInt()
                            .coerceIn(0, maxOf(0, pdfReady.pageCount - 1))
                        viewModel.pdfGoToPage(target)
                    }
                    // v1.7 OE-4: the slider seeks pages in the
                    // CBZ page world.
                    state.cbzPages.isNotEmpty() -> {
                        val target = (fraction * state.cbzPages.size).toInt()
                            .coerceIn(0, state.cbzPages.lastIndex)
                        viewModel.cbzGoToPage(target)
                    }
                    else -> {
                        val target = (fraction * state.chapters.size).toInt()
                            .coerceIn(0, maxOf(0, state.chapters.lastIndex))
                        viewModel.openChapter(target)
                    }
                }
            },
            onOpenToc = { activeSheet = ActiveSheet.TOC },
            onOpenSearch = { activeSheet = ActiveSheet.SEARCH },
            onOpenListen = { activeSheet = ActiveSheet.LISTEN },
            onOpenFocus = { activeSheet = ActiveSheet.FOCUS },
            onOpenSettings = {
                settingsInitialPage = "index"
                activeSheet = ActiveSheet.SETTINGS
            },
            onOpenAi = { onOpenAiPage() },
            pdfPageLabel = pdfPageLabel,
            // OE-1 (R13, OQ4): the mode switch on the bar —
            // the label names the mode a tap enters; a book
            // with no text layer carries the honest line
            // instead, and its tap explains (R22).
            pdfModeLabel = when {
                pdfReady == null -> null
                pdfReady.textAvailable == false -> "No text layer"
                pdfReady.mode == PdfReadMode.PAGES -> "Text reflow"
                else -> "Pages"
            },
            onTogglePdfMode = {
                val ready = pdfReady
                if (ready != null) {
                    if (ready.textAvailable == false) {
                        notice = "This book has no text layer, so " +
                            "there's no text view — the pages " +
                            "are all there is."
                    } else {
                        viewModel.setPdfMode(
                            ready.mode == PdfReadMode.PAGES
                        )
                    }
                }
            },
            // v1.7 OE-4: in the CBZ page world the footer
            // speaks in pages, and the text-only doors
            // (Contents / Search / Listen / Ask AI) stay away —
            // there is no text on an image page to serve them.
            cbzPageLabel = if (state.cbzPages.isNotEmpty()) {
                "Page ${state.cbzPage + 1} / ${state.cbzPages.size}"
            } else {
                null
            },
            onTopBarHeight = { px -> if (px > 0) topChromePx = px },
            onBottomBarHeight = { px -> if (px > 0) bottomChromePx = px }
        )
        } else {
        ReaderChrome(
            visible = chromeVisible,
            palette = palette,
            bookTitle = state.bookTitle,
            // v1.1.2: the header's chapter line and the footer's
            // both derive from the chapter's POSITION through
            // ReaderCounters — the source-given name is shown
            // only when it does not contradict that position.
            chapterTitle = if (pdfReady != null) {
                // OE-1: a PDF's header second line is the
                // outline section the page sits in (or
                // nothing) — never a chapter line.
                pdfReady.anchorTitle ?: ""
            } else {
                ReaderCounters.headerLine(
                    chapterTitle = state.chapter?.title ?: "",
                    chapterIndex = state.chapterIndex,
                    chapterCount = state.chapters.size,
                    // v1.3.2 (item 79): the chapter's OWN number —
                    // the header agrees with the chapter that was
                    // actually opened, never with list position.
                    ownNumber = state.chapter?.let {
                        app.xreader.domain.reader.ChapterDisplayOrder.numberOf(it)
                    }
                )
            },
            // Part 2 (A8): for a TOC book the counters count
            // TOC ENTRIES — the position is the current TOC
            // entry, the count is the TOC's size, and there
            // is no own number (the label must be the TOC
            // position, never a spine number the reader never
            // sees). Non-TOC books pass exactly today's
            // values.
            chapterIndex = if (state.tocEntries.isNotEmpty()) {
                maxOf(0, currentTocIndex)
            } else {
                state.chapterIndex
            },
            chapterCount = if (state.tocEntries.isNotEmpty()) {
                state.tocEntries.size
            } else {
                state.chapters.size
            },
            chapterOwnNumber = if (state.tocEntries.isNotEmpty()) {
                null
            } else {
                state.chapter?.let {
                    app.xreader.domain.reader.ChapterDisplayOrder.numberOf(it)
                }
            },
            bookFraction = pdfReady?.bookFraction ?: state.bookFraction,
            minutesLeftInChapter = state.minutesLeftInChapter,
            minutesLeftInBook = state.minutesLeftInBook,
            showTimeEstimates = pdfReady == null &&
                ReaderCounters.showTimeEstimates(
                    state.chapterText?.content
                ),
            reduceMotion = reduceMotion,
            bookmarked = if (pdfReady != null) {
                viewModel.pdfBookmarkedHere(bookmarks)
            } else {
                state.currentPositionBookmarked
            },
            hasPrevious = when {
                pdfReady != null -> pdfReady.page > 0
                // v1.7 OE-4: in the CBZ page world the turn
                // buttons turn pages.
                state.cbzPages.isNotEmpty() -> state.cbzPage > 0
                else -> state.hasPrevious
            },
            hasNext = when {
                pdfReady != null -> pdfReady.page < pdfReady.pageCount - 1
                state.cbzPages.isNotEmpty() ->
                    state.cbzPage < state.cbzPages.lastIndex
                else -> state.hasNext
            },
            onBack = onBack,
            onToggleBookmark = {
                if (pdfReady != null) {
                    // OE-1 (R19): the bookmark is the page.
                    val wasBookmarked =
                        viewModel.pdfBookmarkedHere(bookmarks)
                    viewModel.togglePdfBookmark()
                    notice = if (wasBookmarked) {
                        "Bookmark removed"
                    } else {
                        "Bookmarked"
                    }
                } else if (state.currentPositionBookmarked) {
                    viewModel.removeBookmarkAtCurrentPosition()
                    notice = "Bookmark removed"
                } else {
                    viewModel.addBookmarkHere()
                    notice = "Bookmarked"
                }
            },
            onPreviousChapter = {
                when {
                    pdfReady != null ->
                        viewModel.pdfGoToPage(pdfReady.page - 1)
                    state.cbzPages.isNotEmpty() ->
                        viewModel.cbzGoToPage(state.cbzPage - 1)
                    else -> viewModel.previousChapter()
                }
            },
            onNextChapter = {
                when {
                    pdfReady != null ->
                        viewModel.pdfGoToPage(pdfReady.page + 1)
                    state.cbzPages.isNotEmpty() ->
                        viewModel.cbzGoToPage(state.cbzPage + 1)
                    else -> viewModel.nextChapter()
                }
            },
            onSeekFraction = { fraction ->
                when {
                    pdfReady != null -> {
                        val target = (fraction * pdfReady.pageCount).toInt()
                            .coerceIn(0, maxOf(0, pdfReady.pageCount - 1))
                        viewModel.pdfGoToPage(target)
                    }
                    // v1.7 OE-4: the slider seeks pages in the
                    // CBZ page world.
                    state.cbzPages.isNotEmpty() -> {
                        val target = (fraction * state.cbzPages.size).toInt()
                            .coerceIn(0, state.cbzPages.lastIndex)
                        viewModel.cbzGoToPage(target)
                    }
                    else -> {
                        val target = (fraction * state.chapters.size).toInt()
                            .coerceIn(0, maxOf(0, state.chapters.lastIndex))
                        viewModel.openChapter(target)
                    }
                }
            },
            onOpenToc = { activeSheet = ActiveSheet.TOC },
            onOpenSearch = { activeSheet = ActiveSheet.SEARCH },
            onOpenListen = { activeSheet = ActiveSheet.LISTEN },
            onOpenFocus = { activeSheet = ActiveSheet.FOCUS },
            onOpenSettings = {
                settingsInitialPage = "index"
                activeSheet = ActiveSheet.SETTINGS
            },
            onOpenAi = { onOpenAiPage() },
            pdfPageLabel = pdfPageLabel,
            // OE-1 (R13, OQ4): the mode switch on the bar —
            // the label names the mode a tap enters; a book
            // with no text layer carries the honest line
            // instead, and its tap explains (R22).
            pdfModeLabel = when {
                pdfReady == null -> null
                pdfReady.textAvailable == false -> "No text layer"
                pdfReady.mode == PdfReadMode.PAGES -> "Text reflow"
                else -> "Pages"
            },
            onTogglePdfMode = {
                val ready = pdfReady
                if (ready != null) {
                    if (ready.textAvailable == false) {
                        notice = "This book has no text layer, so " +
                            "there's no text view — the pages " +
                            "are all there is."
                    } else {
                        viewModel.setPdfMode(
                            ready.mode == PdfReadMode.PAGES
                        )
                    }
                }
            },
            // v1.7 OE-4: in the CBZ page world the footer
            // speaks in pages, and the text-only doors
            // (Contents / Search / Listen / Ask AI) stay away —
            // there is no text on an image page to serve them.
            cbzPageLabel = if (state.cbzPages.isNotEmpty()) {
                "Page ${state.cbzPage + 1} / ${state.cbzPages.size}"
            } else {
                null
            },
            onTopBarHeight = { px -> if (px > 0) topChromePx = px },
            onBottomBarHeight = { px -> if (px > 0) bottomChromePx = px }
        )
        }
        } // (text chrome — skipped while the image reader is up)

        // Eye-tracking status (Labs): small, honest, dismissible.
        if (config.eyeTracking && eyeState != EyeState.OFF) {
            Surface(
                shape = RoundedCornerShape(50),
                color = palette.background.copy(alpha = 0.92f),
                contentColor = palette.text,
                border = BorderStroke(1.dp, palette.text.copy(alpha = 0.15f)),
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
                border = BorderStroke(1.dp, palette.text.copy(alpha = 0.15f)),
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
            onDismissRequest = {
                // Item 4b: leaving the search sheet drops its
                // results (a reopened sheet starts fresh).
                if (sheet == ActiveSheet.SEARCH) {
                    viewModel.clearBookSearch()
                }
                activeSheet = null
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            when (sheet) {
                ActiveSheet.SETTINGS -> ReaderSettingsSheet(
                    initialPage = settingsInitialPage,
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
                    onSaveWord = { word -> viewModel.saveWord(word) },
                    bookTerms = state.bookTerms,
                    onOpenBookTerms = {
                        activeSheet = null
                        onOpenBookTerms()
                    },
                    previewFontFamily = fontFamily,
                    // v1.5.0 (Part C): the manga section rides
                    // the same sheet for image chapters.
                    mangaSettings = if (state.mangaPages != null) {
                        state.mangaSettings
                    } else {
                        null
                    },
                    onMangaModeChange = { viewModel.setImageMode(it) },
                    onMangaTapLayoutChange = {
                        viewModel.setMangaTapLayout(it)
                    },
                    onMangaOrientationChange = {
                        viewModel.setMangaOrientation(it)
                    },
                    onMangaCropChange = { viewModel.setMangaCropBorders(it) },
                    mangaHasOverrides = state.mangaHasOverrides,
                    onResetMangaOverrides = { viewModel.resetMangaOverrides() },
                    // v1.7 OE-1 (R21): a PDF's page settings
                    // ride this sheet (Pages-mode controls);
                    // the mode switch itself is on the bar.
                    pdfPagesSettings = pdfReady?.settings,
                    onPdfSettingsChange = { transform ->
                        viewModel.updatePdfSettings(transform)
                    },
                    pdfAutoResolvedFilter = pdfReady?.autoResolvedFilter
                        ?: app.xreader.data.engine.PdfPageFilter.NONE,
                    pdfAutoScrollOn = pdfAutoScrollOn,
                    onPdfAutoScrollToggle = { on ->
                        pdfAutoScrollOn = on
                        if (on) {
                            pdfAutoScroll.start()
                        } else {
                            pdfAutoScroll.stop()
                        }
                    },
                    // D13: this book's footnotes switch
                    // (prepared EPUBs only — the VM publishes
                    // null otherwise and the section hides).
                    footnotesInText = footnotesInText,
                    onFootnotesInTextChange = { value ->
                        viewModel.setFootnotesInText(value)
                    }
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
                    onSleepChange = { viewModel.armSleep(it) },
                    voiceEngine = voiceUi.engine,
                    voiceEngineIsOverride = voiceUi.isOverride,
                    voiceLanguage = voiceUi.language,
                    voiceChoices = voiceUi.choices,
                    voiceId = voiceUi.voiceId,
                    voiceHasOverride = voiceUi.hasOverride,
                    onVoiceEngineChange = { viewModel.setVoiceEngine(it) },
                    onVoicePick = { viewModel.setEdgeVoice(it) },
                    onVoiceReset = { viewModel.clearVoiceOverrides() }
                )
                ActiveSheet.TOC -> if (pdfReady != null) {
                    PdfContentsSheet(
                        session = pdfReady.session,
                        currentPage = pdfReady.page,
                        bookmarks = bookmarks,
                        pageHighlights = pdfPageHighlights,
                        reflowHighlights = pdfReflowHighlights,
                        bookmarkPageOf = { viewModel.pdfBookmarkPage(it) },
                        onJumpPagesForm = { page ->
                            activeSheet = null
                            viewModel.setPdfMode(false)
                            viewModel.pdfGoToPage(page)
                        },
                        onJumpReflowForm = { page ->
                            activeSheet = null
                            viewModel.setPdfMode(true)
                            viewModel.pdfGoToPage(page)
                        },
                        onJumpBookmark = { page ->
                            activeSheet = null
                            viewModel.pdfGoToPage(page)
                        },
                        onDeleteBookmark = { viewModel.deleteBookmark(it) },
                        onRemoveReflowHighlight = {
                            viewModel.removePdfReflowHighlight(it)
                        },
                        onRemovePageHighlight = {
                            viewModel.removePdfPageHighlight(it)
                        }
                    )
                } else {
                    TocSheet(
                    chapters = state.chapters,
                    chapterLevels = state.chapterLevels,
                    currentChapterIndex = state.chapterIndex,
                    tocEntries = state.tocEntries,
                    currentTocEntryIndex = currentTocIndex,
                    bookmarks = bookmarks,
                    highlights = allHighlights,
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
                                ?.let {
                                    // v1.3.2 (item 79): the
                                    // chapter's own number, not
                                    // its list position.
                                    "Chapter " + (
                                        app.xreader.domain.reader
                                            .ChapterDisplayOrder
                                            .displayNumber(it)
                                            ?: "${it.index + 1}"
                                        )
                                } ?: ""
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
                }
                ActiveSheet.SEARCH -> if (pdfReady != null) {
                    PdfSearchSheet(
                        hits = pdfSearchHits,
                        busy = pdfSearchBusy,
                        onSearch = { query -> viewModel.searchBook(query) },
                        onJump = { hit ->
                            viewModel.clearBookSearch()
                            activeSheet = null
                            viewModel.goToPdfSearchHit(hit)
                        }
                    )
                } else {
                    BookSearchSheet(
                        hits = searchHits,
                        busy = searchBusy,
                        onSearch = { query -> viewModel.searchBook(query) },
                        onJump = { hit ->
                            viewModel.clearBookSearch()
                            activeSheet = null
                            viewModel.openChapterAt(
                                hit.chapterIndex, hit.charOffset
                            )
                        }
                    )
                }
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
                            // Code 117 (§8): saving a note on a
                            // spanning selection writes the
                            // split set — one highlight per
                            // chapter segment, the note text
                            // shared. A single-chapter range is
                            // the one-iteration case.
                            for (chapterIndex in
                                pendingRange.startChapter..
                                pendingRange.endChapter) {
                                val chapterText = state.chapters
                                    .getOrNull(chapterIndex)
                                    ?.let { state.segmentTexts[it.id] }
                                    ?: continue
                                val from = if (
                                    chapterIndex ==
                                    pendingRange.startChapter
                                ) {
                                    pendingRange.start
                                } else {
                                    0
                                }
                                val to = if (
                                    chapterIndex ==
                                    pendingRange.endChapter
                                ) {
                                    pendingRange.end
                                } else {
                                    chapterText.length
                                }
                                if (from < to) {
                                    viewModel.addHighlightInChapter(
                                        chapterIndex, from, to,
                                        noteText
                                    )
                                }
                            }
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

    // -- Patch 11: selection menu dialogs --------------------------------
    // Replace: opens the pair the selection belongs to (or a
    // new one); writes ONLY on the dialog's Save / Remove —
    // dismissing changes nothing.
    replaceText?.let { text ->
        ReplaceTermDialog(
            target = app.xreader.domain.reader.resolveReplaceTarget(
                text, state.bookTerms
            ),
            bookPairs = state.bookTerms,
            onDismiss = { replaceText = null },
            onSave = { pairs ->
                viewModel.setBookTerms(pairs)
                replaceText = null
            },
            onRemove = { pairs ->
                viewModel.setBookTerms(pairs)
                replaceText = null
            }
        )
    }
    dictionaryResult?.let { (word, definition) ->
        DictionaryResultDialog(
            word = word,
            definition = definition,
            onDismiss = { dictionaryResult = null }
        )
    }
    selectionTranslation?.let { (original, translated) ->
        SelectionTranslateDialog(
            original = original,
            translated = translated,
            onDismiss = { selectionTranslation = null }
        )
    }

    // v1.7 Patch 3 (Item 4f): the plate viewer renders LAST in
    // the reader — over the surfaces, the chrome and the
    // sheets. Back or its Close chip dismisses it; the place
    // underneath never moved (PagedReader's claim arbitration
    // is what guarantees that in paged mode).
    plateFile?.let { file ->
        CorePlateViewer(
            file = file,
            filter = plateFilter,
            onDismiss = { plateFile = null }
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
    /** v1.3.2 (item 79): the open chapter's own number — the
     * footer line derives from it when present. */
    chapterOwnNumber: Float? = null,
    bookFraction: Float,
    minutesLeftInChapter: Int,
    minutesLeftInBook: Int,
    /** v1.1.2: false when the chapter has no timed words (an
     * image chapter) — the WPM estimates are then omitted
     * rather than fabricated from fallback word counts. */
    showTimeEstimates: Boolean = true,
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
    /** v1.7 Patch 3 (Item 4b): the in-book search sheet door. */
    onOpenSearch: () -> Unit = {},
    /** v1.7 OE-1 (R7): for a PDF in this reader, the page
     * label ("p. 41 / 212") — when present, the footer
     * speaks in pages beside the book percentage, in both
     * modes, and never in chapters. Null for every other
     * book. */
    pdfPageLabel: String? = null,
    /** v1.7 OE-1 (R13, OQ4): the PDF mode switch rides
     * this bar in the Ask AI tile's seat (a PDF has no
     * chapters for Ask AI to read). The label names the
     * mode a tap ENTERS — "Text reflow" in Pages mode,
     * "Pages" in Reflow; for a book with no text layer it
     * reads "No text layer" (R22) and the tap explains.
     * Null for every non-PDF book. */
    pdfModeLabel: String? = null,
    onTogglePdfMode: () -> Unit = {},
    /** v1.7 OE-4: for a local CBZ/CBR in the book reader's
     * page world, the page label ("Page 5 / 42") — when
     * present, the footer speaks in pages and the text-only
     * doors (Contents / Search / Listen / Ask AI) are not
     * offered: an image page has no text for them to serve.
     * Null for every other book. */
    cbzPageLabel: String? = null,
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
            // Nocturne §5.11: the top bar is TRANSPARENT now —
            // every action rides in its own frosted-glass chip
            // (palette-tinted, reader-local), and the book/chapter
            // context is a glass ribbon pill in the middle.
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { onTopBarHeight(it.height) }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassChipButton(palette = palette, onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = palette.background.copy(alpha = 0.85f),
                        contentColor = palette.text,
                        border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
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
                    // v1.6.2-mr10 (audit B2): in the CBZ page
                    // world the bookmark chip is gated off,
                    // exactly like Listen below — its toggle
                    // writes text bookmarks at char offset 0
                    // whatever page is showing, its filled
                    // state never updates there, and the list
                    // that would show them is hidden in this
                    // world. Page bookmarks for the page worlds
                    // are Patch 5 work; until then, no chip.
                    if (cbzPageLabel == null) {
                        GlassChipButton(palette = palette, onClick = onToggleBookmark) {
                            Icon(
                                if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                if (bookmarked) "Remove bookmark" else "Bookmark here",
                                tint = palette.accent
                            )
                        }
                    }
                    if (cbzPageLabel == null) {
                        GlassChipButton(palette = palette, onClick = onOpenListen) {
                            Icon(Icons.Filled.Headphones, "Listen", tint = palette.text)
                        }
                    }
                    GlassChipButton(palette = palette, onClick = onOpenSettings) {
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
            // v1.2.0 (Wave 3): the bottom bar is ONE neat frosted
            // CAPSULE in the main dock's language (ui/shell's
            // floating pill: full-pill radius, hairline border,
            // raised shadow, 16dp side margins, floating above the
            // nav inset + the shared dead-strip lift). The reader
            // is palette-local, so the glass tint comes from the
            // PALETTE rather than the app scheme (the GlassChipButton
            // rule) — geometry and spacing are the dock's. Two
            // blocks only: a slim progress block (compact merged
            // chapter/position label over the slider, prev/next at
            // its sides — the old separate telemetry pill is folded
            // into it) and ONE row of action tiles. The measured
            // height still includes the lift, so the reader's text
            // insets (F7) stay exact.
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = app.xreader.ui.theme.FloatingChromeLift)
                    .padding(horizontal = 16.dp)
                    .onSizeChanged { onBottomBarHeight(it.height) }
            ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = chromeColor,
                contentColor = palette.text,
                border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    var sliderFraction by remember { mutableStateOf(bookFraction) }
                    var sliderDragging by remember { mutableStateOf(false) }
                    LaunchedEffect(bookFraction) {
                        if (!sliderDragging) sliderFraction = bookFraction
                    }
                    // Progress block: the merged label line (REAL
                    // fields only — chapter position, book %, and
                    // the existing time-left labels; no battery or
                    // clock) sits directly over the slim slider,
                    // with the chapter-turn buttons at its sides.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = onPreviousChapter, enabled = hasPrevious) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous chapter")
                        }
                        Column(Modifier.weight(1f)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (pdfPageLabel != null) {
                                        // OE-1 (R7): a PDF's
                                        // status speaks in
                                        // pages, in both
                                        // modes — never a
                                        // fabricated chapter
                                        // count.
                                        "$pdfPageLabel · " +
                                            "${(bookFraction * 100).toInt()}%"
                                    } else if (cbzPageLabel != null) {
                                        // v1.7 OE-4: a comic's
                                        // status speaks in
                                        // pages too.
                                        "$cbzPageLabel · " +
                                            "${(bookFraction * 100).toInt()}%"
                                    } else {
                                        ReaderCounters.footerLine(
                                            chapterIndex, chapterCount,
                                            chapterOwnNumber
                                        ) + " · ${(bookFraction * 100).toInt()}%"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted,
                                    maxLines = 1
                                )
                                if (showTimeEstimates) {
                                    Text(
                                        "${WpmEstimator.formatMinutes(minutesLeftInChapter)} in chapter · " +
                                            "${WpmEstimator.formatMinutes(minutesLeftInBook)} in book",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.muted,
                                        maxLines = 1
                                    )
                                }
                            }
                            // Patch 13 (Elastic Slider): the
                            // seek slider gains the rubber-band
                            // feel; the contract is unchanged —
                            // drag only moves the preview fraction,
                            // the chapter jump happens ONLY in
                            // onValueChangeFinished.
                            ElasticSlider(
                                value = sliderFraction,
                                onValueChange = {
                                    sliderDragging = true
                                    sliderFraction = it
                                },
                                onValueChangeFinished = {
                                    sliderDragging = false
                                    onSeekFraction(sliderFraction)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        IconButton(onClick = onNextChapter, enabled = hasNext) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next chapter")
                        }
                    }
                    // ONE row of action tiles. Contents / Search /
                    // Listen / Ask AI / Focus keep their doors (the
                    // Search tile is v1.7 Patch 3's Item 4b door).
                    // Type, Theme
                    // and Auto folded into the settings sheet —
                    // verified present there by symbol before
                    // folding (ReaderSheets.kt): typography on the
                    // "Text & colours" sub-page (v1.5.40 rename;
                    // SheetSection "Font", the fontSizeSp
                    // SheetSlider, line height / margins /
                    // alignment), theme in SheetSection
                    // "Page theme" on the same sub-page, and
                    // auto-scroll in SheetSection "Auto-scroll"
                    // (SheetSwitch "Auto-scroll / auto page-turn")
                    // on the "Reading" sub-page. The Settings tile
                    // opens that sheet's index, so each folded
                    // control is at most one extra tap away and
                    // nothing was removed.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (cbzPageLabel == null) {
                            ChromeAction(
                                Icons.Filled.List, "Contents", palette, onOpenToc,
                                modifier = Modifier.weight(1f)
                            )
                            ChromeAction(
                                Icons.Filled.Search, "Search", palette, onOpenSearch,
                                modifier = Modifier.weight(1f)
                            )
                            ChromeAction(
                                Icons.Filled.Headphones, "Listen", palette, onOpenListen,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pdfModeLabel != null) {
                            // OE-1 (R13, OQ4): the mode
                            // switch, one tap on the bar —
                            // never buried in a sheet.
                            ChromeAction(
                                Icons.Filled.Description, pdfModeLabel,
                                palette,
                                onTogglePdfMode,
                                modifier = Modifier.weight(1f)
                            )
                        } else if (cbzPageLabel == null) {
                            ChromeAction(
                                Icons.Filled.AutoAwesome, "Ask AI",
                                palette,
                                onOpenAi,
                                tint = aiAccentColor(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        ChromeAction(
                            Icons.Filled.Timer, "Focus", palette, onOpenFocus,
                            modifier = Modifier.weight(1f)
                        )
                        ChromeAction(
                            Icons.Filled.Settings, "Settings", palette, onOpenSettings,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * Nocturne §5.11: one frosted-glass chip button for the reader's
 * top bar — a palette-tinted circle with a hairline border. The
 * reader is palette-local (page themes are reader-local), so the
 * glass here is built from the PALETTE, not the app scheme.
 */
@Composable
private fun GlassChipButton(
    palette: ReaderPalette,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = palette.background.copy(alpha = 0.85f),
        contentColor = palette.text,
        border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
        modifier = Modifier.size(40.dp)
    ) {
        IconButton(onClick = onClick) { content() }
    }
}

/**
 * Nocturne §5.11: a quick-action TILE — 16dp rounded tile, icon
 * over label, accent-tinted when active. Palette-local like
 * [GlassChipButton]; every tile opens a real sheet or toggles a
 * real setting.
 */
@Composable
private fun ChromeAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    palette: ReaderPalette,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = palette.text,
    active: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) {
            palette.accent.copy(alpha = 0.20f)
        } else {
            palette.text.copy(alpha = 0.07f)
        },
        contentColor = palette.text,
        border = BorderStroke(
            1.dp,
            if (active) palette.accent.copy(alpha = 0.45f)
            else palette.text.copy(alpha = 0.10f)
        ),
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp)
        ) {
            Icon(
                icon, label,
                tint = if (active) palette.accent else tint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) palette.accent else palette.muted,
                maxLines = 1
            )
        }
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

// ── v1.7 OE-1: the PDF host + sheets ─────────────────────

/**
 * A local PDF inside the one reader (R1): the session's
 * own states — loading, the locked-PDF password prompt
 * (K5, the shipped path on its new seat), a plain failure,
 * or the ready book in its Pages / Reflow modes (R4, R8)
 * under the PDF comforts. The chapter surfaces never see
 * a PDF; this host never sees a chapter.
 */
@Composable
private fun PdfReaderHost(
    state: PdfReaderState,
    viewModel: ReaderViewModel,
    palette: ReaderPalette,
    config: ReaderConfig,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    handlers: PdfPagesHandlers,
    autoScroll: app.xreader.ui.localreader.AutoScrollController,
    chromeVisible: Boolean,
    bottomInset: androidx.compose.ui.unit.Dp,
    spokenRange: IntRange?,
    reflowHighlights: List<
        app.xreader.ui.localreader.StoredSelectionHighlight>,
    pageHighlights: List<
        app.xreader.ui.localreader.StoredSelectionHighlight>,
    searchMark: PdfSearchMark?,
    onToggleControls: () -> Unit,
    onBack: () -> Unit,
    // SelectionBar (Revision 1, Readiness F3 — this list is
    // closed): the More doors, the back chain's reports and
    // clear nonce, and the motion gate, threaded to the
    // surfaces. onUpdatePageTint is built INSIDE this host
    // (beside onUpdatePageNote) and passed to PdfPagesSurface
    // only — Reflow has no tint-update path (§7.4).
    onSelectionTranslate: (String) -> Unit,
    onSelectionAskAi: (String) -> Unit,
    onSelectionActiveChanged: (Boolean) -> Unit,
    onDialogOpenChanged: (Boolean) -> Unit,
    clearSelectionSignal: Int,
    motionEnabled: Boolean
) {
    when (val s = state) {
        PdfReaderState.Loading -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(26.dp),
                color = palette.accent,
                strokeWidth = 2.5.dp
            )
            androidx.compose.material3.Text(
                text = "Opening…",
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        PdfReaderState.PasswordRequired -> {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.Text(
                    text = "This PDF asks for a password",
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            var password by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onBack,
                title = { Text("Password needed") },
                text = {
                    Column {
                        Text(
                            "This PDF is locked. Enter its password " +
                                "to read it — the password is used " +
                                "for this session only and is never " +
                                "saved."
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation =
                                PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.retryPdfWithPassword(password)
                        },
                        enabled = password.isNotEmpty()
                    ) { Text("Unlock") }
                },
                dismissButton = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }

        is PdfReaderState.Failed -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.Text(
                text = s.message,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        is PdfReaderState.Ready -> {
            app.xreader.ui.localreader.LocalReaderComfortHost(
                volumeKeyTurn = s.settings.volumeKeyTurn,
                keepScreenOn = s.settings.keepScreenOn,
                orientation = s.settings.orientation,
                onVolumeTurn = { direction ->
                    viewModel.requestExternalTurn(direction)
                },
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize()) {
                    when (s.mode) {
                        PdfReadMode.PAGES -> PdfPagesSurface(
                            session = s.session,
                            settings = s.settings,
                            initialPage = s.initialPage,
                            currentPage = s.page,
                            handlers = handlers,
                            autoScroll = autoScroll,
                            onToggleControls = onToggleControls,
                            onResolvedFilter = { page, filter ->
                                viewModel.onPdfResolvedFilter(page, filter)
                            },
                            pageHighlights = pageHighlights,
                            searchMark = searchMark,
                            textAvailable = s.textAvailable,
                            onAddPageHighlight = { highlight ->
                                viewModel.addPdfPageHighlight(highlight)
                            },
                            onRemovePageHighlight = { highlight ->
                                viewModel.removePdfPageHighlight(highlight)
                            },
                            onUpdatePageNote = { highlight, note ->
                                viewModel.updatePdfPageHighlightNote(
                                    highlight, note
                                )
                            },
                            onUpdatePageTint = { highlight, tintArgb ->
                                viewModel.updatePdfPageHighlightTint(
                                    highlight, tintArgb
                                )
                            },
                            onSelectionTranslate = onSelectionTranslate,
                            onSelectionAskAi = onSelectionAskAi,
                            onSelectionActiveChanged =
                                onSelectionActiveChanged,
                            onDialogOpenChanged = onDialogOpenChanged,
                            clearSelectionSignal = clearSelectionSignal,
                            motionEnabled = motionEnabled
                        )
                        PdfReadMode.REFLOW -> when {
                            s.reflowContent != null -> PdfReflowSurface(
                                content = s.reflowContent,
                                config = config,
                                fontFamily = fontFamily,
                                textColor = palette.text,
                                page = s.page,
                                highlights = reflowHighlights,
                                searchMark = searchMark,
                                spokenRange = spokenRange,
                                onBoundaryCross = { direction ->
                                    viewModel.pdfGoToPage(
                                        s.page + direction
                                    )
                                },
                                onToggleControls = onToggleControls,
                                onAddHighlight = { highlight ->
                                    viewModel.addPdfReflowHighlight(
                                        highlight
                                    )
                                },
                                onSelectionTranslate =
                                    onSelectionTranslate,
                                onSelectionAskAi = onSelectionAskAi,
                                onSelectionActiveChanged =
                                    onSelectionActiveChanged,
                                onDialogOpenChanged = onDialogOpenChanged,
                                clearSelectionSignal =
                                    clearSelectionSignal,
                                motionEnabled = motionEnabled
                            )
                            s.textAvailable == false -> Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {
                                androidx.compose.material3.Text(
                                    text = "This book has no text " +
                                        "layer, so there's no text " +
                                        "view — the pages are all " +
                                        "there is.",
                                    modifier = Modifier
                                        .padding(horizontal = 24.dp)
                                )
                            }
                            else -> Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    color = palette.accent,
                                    strokeWidth = 2.5.dp
                                )
                                androidx.compose.material3.Text(
                                    text = "Preparing text…",
                                    modifier = Modifier
                                        .padding(top = 16.dp)
                                )
                            }
                        }
                    }
                    // R7: the filmstrip is Pages-only — it
                    // never appears over Reflow text.
                    if (s.mode == PdfReadMode.PAGES &&
                        chromeVisible && s.pageCount > 1
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    bottom = bottomInset + 8.dp,
                                    start = 12.dp,
                                    end = 12.dp
                                )
                        ) {
                            PdfFilmstripRow(
                                session = s.session,
                                currentPage = s.page,
                                pageCount = s.pageCount,
                                rtlPaging = s.settings.rtlPaging,
                                onSeek = { page ->
                                    viewModel.pdfGoToPage(page)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * OE-1 (R16, R19, R20): a PDF's Contents sheet — the
 * book's own outline (both modes; a tap shows the
 * destination page in the mode in force), the one shared
 * page-anchored bookmark list, and the two highlight
 * forms, each listed apart: a Pages-form tap reads the
 * page as printed, a Reflow-form tap reads its text.
 */
@Composable
private fun PdfContentsSheet(
    session: app.xreader.data.engine.PdfSession,
    currentPage: Int,
    bookmarks: List<app.xreader.data.db.Bookmark>,
    pageHighlights: List<
        app.xreader.ui.localreader.StoredSelectionHighlight>,
    reflowHighlights: List<
        app.xreader.ui.localreader.StoredSelectionHighlight>,
    bookmarkPageOf: (app.xreader.data.db.Bookmark) -> Int,
    onJumpPagesForm: (Int) -> Unit,
    onJumpReflowForm: (Int) -> Unit,
    onJumpBookmark: (Int) -> Unit,
    onDeleteBookmark: (app.xreader.data.db.Bookmark) -> Unit,
    onRemoveReflowHighlight: (
        app.xreader.ui.localreader.StoredSelectionHighlight
    ) -> Unit,
    onRemovePageHighlight: (
        app.xreader.ui.localreader.StoredSelectionHighlight
    ) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            "Contents",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        if (session.outline.isEmpty()) {
            Text(
                "This PDF has no contents list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            session.outline.forEach { entry ->
                val target =
                    app.xreader.data.engine.LocatorJson
                        .targetIndex(entry.targetJson) ?: 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJumpBookmark(target) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (target == currentPage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "p. ${target + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (bookmarks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Bookmarks",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            bookmarks.forEach { bookmark ->
                val page = bookmarkPageOf(bookmark)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJumpBookmark(page) }
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            bookmark.label.ifBlank { "Bookmark" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1
                        )
                        Text(
                            "p. ${page + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme
                                .colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { onDeleteBookmark(bookmark) }
                    ) { Text("Remove") }
                }
            }
        }
        val pagesForm = pageHighlights.filter { it.page != null }
        if (pagesForm.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Highlights — pages",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            pagesForm.forEach { highlight ->
                val page = highlight.page ?: 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJumpPagesForm(page) }
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            highlight.selectedText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2
                        )
                        Text(
                            "p. ${page + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme
                                .colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { onRemovePageHighlight(highlight) }
                    ) { Text("Remove") }
                }
            }
        }
        val reflowForm = reflowHighlights.filter { it.page != null }
        if (reflowForm.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Highlights — text",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            reflowForm.forEach { highlight ->
                val page = highlight.page ?: 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJumpReflowForm(page) }
                        .padding(vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            highlight.selectedText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2
                        )
                        Text(
                            "p. ${page + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme
                                .colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { onRemoveReflowHighlight(highlight) }
                    ) { Text("Remove") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * OE-1 (R18): a PDF's search sheet — the chapter sheet's
 * shape, with page hits: a tap shows the hit's page (in
 * Reflow, its text with the hit marked).
 */
@Composable
private fun PdfSearchSheet(
    hits: List<PdfSearchHit>?,
    busy: Boolean,
    onSearch: (String) -> Unit,
    onJump: (PdfSearchHit) -> Unit
) {
    var query by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            "Search",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Word or phrase") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onSearch(query) }
                ),
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { onSearch(query) },
                enabled = query.isNotBlank() && !busy
            ) { Text("Search") }
        }
        Spacer(Modifier.height(8.dp))
        when {
            busy -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                CircularProgressIndicator(
                    Modifier.padding(end = 12.dp).size(20.dp)
                )
                Text("Searching page by page…")
            }
            hits == null -> Text(
                "Search the whole book, page by page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            hits.isEmpty() -> Text(
                "No matches in this book.",
                style = MaterialTheme.typography.bodyMedium
            )
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                hits.forEach { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onJump(hit) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            "p. ${hit.page + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            hit.snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
