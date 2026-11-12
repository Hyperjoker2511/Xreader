package app.xreader.ui.localreader

import android.content.Context
import android.os.BatteryManager
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.engine.LocatorJson
import app.xreader.data.engine.OutlineEntry
import app.xreader.data.engine.ReadiumEpubSession
import app.xreader.data.prefs.settingsDataStore
import app.xreader.ui.theme.FloatingChromeLift
import app.xreader.ui.theme.LocalGlassSettings
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.input.KeyEvent as ReadiumKeyEvent
import org.readium.r2.navigator.preferences.Color as ReadiumColor

/**
 * v1.5.7 (Scope B + Scope C-EPUB): the EPUB BOOK reader — flowing
 * text through Readium's EpubNavigatorFragment (3.1.2), with
 * book-mode chrome that is deliberately NOT the PDF reader's:
 *
 *  - Finding 1 fixed per V156-PROOF-FINDING-1 §(d): a
 *    [controlsVisible] state gates the chrome; ONE [InputListener]
 *    on the live fragment turns centre taps into chrome toggles
 *    and edge taps into deterministic page turns; the fragment is
 *    captured OUTSIDE the creation branch so restored fragments
 *    get the listener, the locator collector and jumps.
 *  - Progress is PERCENT + chapter name (a reflowable EPUB has no
 *    honest fixed page count — split report §1/§4).
 *  - The settings sheet is Readium's book-mode surface
 *    (typography, theme, scroll-vs-paginated), submitted as
 *    [EpubPreferences] through the 3.1.2 preferences editor.
 *  - Contents come from the book's navigation outline with the
 *    current chapter highlighted.
 *
 * v1.5.8 (G1–G10, Moon+ template): the book opens SCROLLING
 * (G1); the chrome is rebuilt Moon+-shaped in Xreader's glass
 * language (G3) — slim top bar (Back + title + bookmark), the
 * seek capsule, and a bottom quick row (Day/Night · Contents ·
 * Bookmarks · Search · Aa · Read aloud) whose actions keep
 * their PRE-EXISTING handlers; the mode switch is the settings
 * sheet's FIRST row and is mirrored in the bottom chrome (G2);
 * a permanent slim status line renders with the chrome hidden
 * (G5); comforts (edge brightness, volume keys, keep-screen-on,
 * orientation) ride the shared host (G8); auto-scroll is G9's
 * engine. The tap map is unchanged (centre toggles, edges turn).
 *
 * PLATE FULLSCREEN — named deviation: Readium 3.1.2's [TapEvent]
 * carries only a viewport point (no target element; that arrives
 * in 3.4.0, which this tree cannot take), and the current
 * resource being an image document cannot be distinguished from
 * a text page with a full-page image through any supported
 * 3.1.2 API. No heuristic is faked here; plates render in-flow
 * at full column width and full quality (G10's honest
 * substitute), with the dark-theme treatment applied ONLY via
 * the `imageFilter` preference (below).
 */

private enum class EpubSheet {
    CONTENTS, BOOKMARKS, SEARCH, SETTINGS,
    // v1.5.10: F1 bar customise, G2 control options, F2 book info.
    BAR, CONTROL, BOOK_INFO
}

@OptIn(ExperimentalMaterial3Api::class, org.readium.r2.shared.ExperimentalReadiumApi::class)
@Composable
internal fun EpubBookReader(
    viewModel: LocalDocReaderViewModel,
    ready: LocalReaderState.Ready,
    session: ReadiumEpubSession,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    // v1.5.10 (F2 door): forwarded verbatim to the Book
    // information sheet's onOpenBookPage door; null (the
    // default, when the host supplies none) keeps the
    // "Book page" row absent.
    onOpenBookPage: (() -> Unit)? = null
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    // v1.5.32 (owner order): EpubBody reports which surface is
    // mounted; while the RIVER is up, the always-on status
    // pill is not composed at all (see the statusLine slot).
    var riverModeActive by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<EpubSheet?>(null) }
    val settings by viewModel.epubSettings.collectAsStateWithLifecycle()
    val progress by viewModel.epubProgress.collectAsStateWithLifecycle()
    // v1.5.9 (F-5): collect the plain playing flow — opening
    // the reader constructs ZERO TTS engines.
    val ttsPlaying by viewModel.ttsPlaying.collectAsStateWithLifecycle()
    val autoScroll = remember { AutoScrollController() }
    var autoScrollOn by remember { mutableStateOf(false) }
    // v1.5.33 (R3): ONE auto-scroll toggle for every door — the
    // Book settings sheet's start/stop (the surface chip row is
    // gone, owner order) and the bar's running stop control.
    // Behaviour is the shipped chip's, verbatim.
    val toggleAutoScroll: () -> Unit = {
        if (autoScroll.engine.running) {
            autoScroll.stop()
        } else {
            autoScroll.start()
        }
        autoScrollOn = autoScroll.engine.running
    }
    // v1.5.33 (R3, §7.3): the chrome palette — the book's own
    // page colours, derived from the live settings.
    val chromePalette = epubChromePalette(settings)
    val scope = rememberCoroutineScope()
    // v1.5.9 (F-6): the first-paint bridge — true once the
    // navigator's first locator has landed (hoisted from
    // EpubBody via its onFirstLocator callback).
    var firstLocatorLanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // The book's cover, already in the library record: the
    // local cover file when it exists, else the cover URL.
    val coverModel = remember(ready.book.coverPath, ready.book.coverUrl) {
        ready.book.coverPath
            ?.let { File(context.filesDir, it) }
            ?.takeIf { it.exists() }
            ?: ready.book.coverUrl?.takeIf { it.isNotBlank() }
    }

    // v1.5.35 (restore §C.6): EpubBody's river branch
    // registers its driver here so the ON_STOP observer
    // below can sequence ONE final anchor pull BEFORE the
    // flush — the mid-fling hole's plug (the position
    // loop's last pull can be a mid-fling position; the
    // exit pull reads the resting place). Legacy mode never
    // registers, so its flush below runs exactly as before.
    val riverExitDriver = remember {
        object {
            @Volatile var driver: EpubRiverDriver? = null
        }
    }

    // v1.5.9 delta (sift A-7): when the UI stops (backgrounded,
    // navigated away), flush the pending PROGRESS save — the
    // 800 ms debounce's only other flush is the racy onCleared
    // launch, which stays as backstop only. This is the
    // progress path, NOT the F-4a settings debounce.
    // v1.5.35 (restore §C.6): with a river driver registered,
    // the flush waits for its final pull's callback — or a
    // short timeout, so a wedged pull can never eat the
    // flush.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val pull = riverExitDriver.driver?.requestFinalPull
                if (pull == null) {
                    viewModel.flushPendingProgressSave()
                } else {
                    var flushed = false
                    val flushOnce: () -> Unit = {
                        if (!flushed) {
                            flushed = true
                            viewModel.flushPendingProgressSave()
                        }
                    }
                    pull(flushOnce)
                    scope.launch {
                        delay(600)
                        flushOnce()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // v1.5.9 delta (B-2) → v1.5.10 (D2): the gated bottom
    // chrome's height feeds the WebView's bottom scroll inset
    // through the settle-gated reducer (see EpubInsetState):
    // EpubBody applies ONLY the reducer's terminal output, so
    // the show/hide animation's intermediate heights never
    // reach setPadding.
    var insetState by remember { mutableStateOf(EpubInsetState()) }
    val dispatchInset: (EpubInsetEvent) -> Unit = { event ->
        insetState = epubInsetReducer(insetState, event)
    }
    LaunchedEffect(controlsVisible) {
        dispatchInset(EpubInsetEvent.VisibilityChanged(controlsVisible))
    }

    // v1.5.9 delta (sift A-6, mirroring the PDF placeholder's
    // one-flag pattern): the "Opening…" indicator appears only
    // if the first locator is still absent after ~120 ms —
    // cover + title are immediate, fast opens never flash it.
    var openingIndicatorShown by remember { mutableStateOf(false) }
    LaunchedEffect(firstLocatorLanded) {
        if (!firstLocatorLanded) {
            delay(EPUB_PLACEHOLDER_INDICATOR_DELAY_MS)
            openingIndicatorShown = true
        }
    }

    // v1.5.10 (G3): the user-font file picker. FILES only —
    // the toolkit cannot take Android Downloadable Fonts, and
    // the sheet says so. A successful pick is copied into the
    // app-private fonts dir (fonts are not books; the copy is
    // what lets the session serve the file as a publication
    // asset) and the book reopens on the new font.
    val fontPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val copied = kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.IO
                ) { copyPickedUserFont(context, uri) }
                if (copied != null) {
                    sheet = null
                    viewModel.setEpubUserFontAndReopen(copied)
                } else {
                    snackbar.showSnackbar(
                        "That file isn't a font file — " +
                            "pick a TTF, OTF, WOFF or WOFF2 file."
                    )
                }
            }
        }
    }

    // G3: the handlers are gathered ONCE — the quick row, the
    // top bar and the sheets all invoke these same lambdas.
    val handlers = EpubChromeHandlers(
        onToggleDayNight = {
            viewModel.updateEpubSettings { it.toggleDayNight() }
        },
        onContents = { sheet = EpubSheet.CONTENTS },
        onBookmarks = { sheet = EpubSheet.BOOKMARKS },
        onSearch = { sheet = EpubSheet.SEARCH },
        onSettings = { sheet = EpubSheet.SETTINGS },
        onReadAloud = { viewModel.toggleReadAloud() }
    )

    // ── v1.5.10: the F/G wiring over L-FG's landed components ──
    // F1/G2/F5/F6 globals ride ReaderBarStore and F2's stats
    // ride BookStatsStore — both over the SAME shared
    // "settings" DataStore the reader prefs use. The stores
    // are constructed here, in the reader, exactly as the PDF
    // reader constructs its own.
    val barStore = remember(context) {
        ReaderBarStore(context.settingsDataStore)
    }
    val statsStore = remember(context) {
        BookStatsStore(context.settingsDataStore)
    }
    val barCustomized by barStore.customized
        .collectAsStateWithLifecycle(initialValue = false)
    val barConfig by barStore.config
        .collectAsStateWithLifecycle(initialValue = null)
    val tapPreset by barStore.tapPreset(ReaderFormat.EPUB)
        .collectAsStateWithLifecycle(initialValue = TapLayoutPreset.MOON_DEFAULT)
    val tiltEnabled by barStore.tiltEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val contentsSort by barStore.contentsSort(ReaderFormat.EPUB)
        .collectAsStateWithLifecycle(initialValue = ContentsSortOrder.DOCUMENT_ORDER)
    val bookStats by statsStore.stats(ready.book.id)
        .collectAsStateWithLifecycle(initialValue = BookStats())
    val isFavourite by viewModel.isFavourite.collectAsStateWithLifecycle()

    // F6: tilt-to-turn — the reader's own turn advance is the
    // volume-turn road (EpubBody routes it to the navigator).
    ReaderTiltTurnEffect(
        enabled = tiltEnabled,
        onTurn = { direction -> viewModel.onVolumeKeyTurn(direction) }
    )

    // F2: reading-session accumulation. The accumulator turns
    // progression movement into advanced words (against the
    // counted total, once the sweep lands); slices flush to
    // BookStatsStore every 30 s and on dispose. Time is
    // recorded from THIS build forward — the sheet says so
    // until data exists.
    val sessionAccumulator = remember { ReadingSessionAccumulator() }
    var accumulatorBegan by remember { mutableStateOf(false) }
    var pendingWords by remember { mutableStateOf(0L) }
    var lastFlushMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val statsState = rememberUpdatedState(bookStats)
    val progressState = rememberUpdatedState(progress)
    val flushStatsSlice: () -> Unit = {
        val now = System.currentTimeMillis()
        val delta = now - lastFlushMs
        val words = pendingWords
        val endPercent = (progressState.value?.fraction ?: 0f) * 100f
        lastFlushMs = now
        pendingWords = 0L
        if (delta > 0L || words > 0L) {
            scope.launch {
                statsStore.recordSession(
                    bookId = ready.book.id,
                    dateIso = readingDayIso(now),
                    deltaMillis = delta,
                    wordsAdvanced = words,
                    endBookPercent = endPercent
                )
            }
        }
    }
    LaunchedEffect(progress?.fraction) {
        val fraction = progress?.fraction ?: return@LaunchedEffect
        if (!accumulatorBegan) {
            sessionAccumulator.begin(fraction)
            accumulatorBegan = true
        } else {
            pendingWords += sessionAccumulator.sample(
                fraction, statsState.value.textCounts?.words
            )
        }
    }
    LaunchedEffect(viewModel) {
        while (true) {
            delay(30_000L)
            flushStatsSlice()
        }
    }
    DisposableEffect(viewModel) {
        onDispose { flushStatsSlice() }
    }
    // F2: the one-time whole-book text sweep (Chars/Words),
    // cached in the stats store; the sheet reads "counting…"
    // until it lands. Background, once per book.
    // v1.5.18 (F3 / C-E2): the sweep NO LONGER runs inside the
    // open path. The controller (EpubTextSweep.kt) stays gated
    // until SETTLE — the first locator landed (the code's own
    // first-paint signal, hoisted as [firstLocatorLanded])
    // plus a short idle delay — then processes the book in
    // bounded chunks on IO. Both effects are keyed on the
    // controller (per session+book), so closing the reader or
    // changing the book cancels an in-flight sweep and nothing
    // is saved; the totals it eventually saves are exactly the
    // ones the old inline loop computed.
    val textSweep = remember(session, ready.book.id) {
        EpubTextSweepController(
            sectionCount = session.sectionCount,
            textAt = { index -> session.textAt(index) }
        )
    }
    LaunchedEffect(firstLocatorLanded) {
        if (firstLocatorLanded) {
            delay(EPUB_TEXT_SWEEP_SETTLE_DELAY_MS)
            textSweep.markSettled()
        }
    }
    LaunchedEffect(textSweep, bookStats.textCounts) {
        if (bookStats.textCounts == null) {
            val counts = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO
            ) { textSweep.run() }
            statsStore.saveTextCounts(ready.book.id, counts)
        }
    }

    // v1.5.9 (F-2): the Scaffold keeps ONLY the snackbar host.
    // The chrome no longer lives in Scaffold bar slots (whose
    // live padding resized the Readium surface on every toggle);
    // it overlays a constant-size body inside the body Box.
    // contentWindowInsets = 0 and the PaddingValues(0.dp) below
    // make that structural: the WebView's size cannot move.
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        // `padding` is PaddingValues(0.dp) by construction (no
        // bar slots, zero content insets) and is deliberately
        // NOT applied to the body — see F-2 above.
        //
        // v1.5.9 delta (sift A-8): this whole subtree sits over
        // the live navigator WebView, so it provides the
        // WEBVIEW backdrop kind — no glass primitive composed
        // under it can request backdrop blur, in any GlassStyle
        // (haze #99 flashing class). The sheet block below
        // provides the same for its own subtree.
        CompositionLocalProvider(
            LocalReaderBackdropKind provides ReaderBackdropKind.WEBVIEW
        ) {
        LocalReaderComfortHost(
            volumeKeyTurn = settings.volumeKeyTurn,
            keepScreenOn = settings.keepScreenOn,
            orientation = settings.orientation,
            onVolumeTurn = { viewModel.onVolumeKeyTurn(it) },
            modifier = Modifier.padding(PaddingValues(0.dp))
        ) {
            EpubChromeOverlayHost(
                controlsVisible = controlsVisible,
                onBottomChromeHeightChanged = { height ->
                    dispatchInset(EpubInsetEvent.HeightMeasured(height))
                },
                onBottomChromeSettled = {
                    dispatchInset(EpubInsetEvent.ChromeSettled)
                },
                topBar = {
                    EpubTopBar(
                        title = ready.book.title,
                        chapterTitle = progress?.chapterTitle,
                        coverModel = coverModel,
                        palette = chromePalette,
                        onClose = onClose,
                        onBookmarkHere = { viewModel.bookmarkHere() },
                        // v1.5.10 (F2): the title pill is the
                        // Book information door.
                        onTitleTap = { sheet = EpubSheet.BOOK_INFO }
                    )
                },
                bottomChrome = {
                    EpubBottomChrome(
                        viewModel = viewModel,
                        session = session,
                        progress = progress,
                        settings = settings,
                        handlers = handlers,
                        ttsPlaying = ttsPlaying,
                        autoScrollOn = autoScrollOn,
                        onToggleAutoScroll = toggleAutoScroll,
                        // v1.5.10 (F1): the customised bar —
                        // null actions = untouched store, the
                        // shipped row renders byte-for-byte.
                        barActions = if (barCustomized) {
                            resolveReaderBar(
                                customized = true,
                                config = barConfig,
                                format = ReaderFormat.EPUB
                            )
                        } else {
                            null
                        },
                        barDoubleLine = barConfig?.doubleLineLayout ?: false,
                        barActive = buildSet {
                            if (ttsPlaying) add(ReaderBarActionId.SPEAK)
                            if (autoScrollOn) add(ReaderBarActionId.AUTOSCROLL)
                            if (tiltEnabled) add(ReaderBarActionId.TILT)
                        },
                        onOpenControlSheet = { sheet = EpubSheet.CONTROL },
                        onCycleOrientation = {
                            viewModel.updateEpubSettings {
                                it.copy(
                                    orientation = when (it.orientation) {
                                        ReaderOrientation.FREE ->
                                            ReaderOrientation.PORTRAIT
                                        ReaderOrientation.PORTRAIT ->
                                            ReaderOrientation.LANDSCAPE
                                        ReaderOrientation.LANDSCAPE ->
                                            ReaderOrientation.FREE
                                    }
                                )
                            }
                        },
                        onToggleTilt = {
                            scope.launch {
                                barStore.saveTiltEnabled(!tiltEnabled)
                            }
                        }
                    )
                },
                statusLine = {
                    // G5: the permanent status line — composed
                    // OUTSIDE the chrome visibility gate, always on.
                    // v1.5.10 (F4): the within-chapter segment
                    // rides the ViewModel's positions seam; a
                    // null pair renders the pre-F4 line exactly.
                    // v1.5.32 (OWNER ORDER, river mode only):
                    // the pill NEVER hides and sits over the
                    // text — "it can't hide, it should be gone."
                    // In river mode it is not composed at all,
                    // at rest or with chrome; its facts
                    // (chapter · % · battery · clock) resurface
                    // inside the tap-open chrome in R3. Legacy
                    // surfaces keep the pill exactly as-is.
                    if (!riverModeActive) {
                        LiveReaderStatusLine { battery, clock ->
                            epubStatusLineContent(
                                progress, progress?.withinChapter,
                                battery, clock
                            )
                        }
                    }
                }
            ) {
                EpubBody(
                    viewModel = viewModel,
                    session = session,
                    bookId = ready.book.id,
                    bookFingerprint = ready.book.fileFingerprint,
                    tapPreset = tapPreset,
                    // D2: the reducer's terminal output only.
                    bottomInsetPx = insetState.appliedInsetPx,
                    initialLocator = ready.initialEpubLocator,
                    initialPreferences = remember(ready.initialEpubSettings, session) {
                        ready.initialEpubSettings.toEpubPreferences(session)
                    },
                    settings = settings,
                    autoScroll = autoScroll,
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onFirstLocator = { firstLocatorLanded = true },
                    onRiverModeChange = { riverModeActive = it },
                    onRiverDriverReady = { riverExitDriver.driver = it }
                )
                // v1.5.9 (F-6): the first-paint placeholder —
                // the book's own cover + title + "Opening…",
                // centred, held until the first locator lands
                // so the dead-black interval reads as an opening
                // screen, not a hang. Fades out on first paint.
                AnimatedVisibility(
                    visible = epubOpeningPlaceholderVisible(firstLocatorLanded),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        if (coverModel != null) {
                            AsyncImage(
                                model = coverModel,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 120.dp, height = 170.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                        Text(
                            ready.book.title,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // A-6: the indicator line joins only
                        // after ~120 ms without a first locator.
                        if (openingIndicatorShown) {
                            Text(
                                "Opening…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // G10: a fixed-layout book says so, plainly.
                if (ready.isFixedLayout) {
                    Box(
                        Modifier.fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 8.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        GlassTitlePill(
                            title = "Fixed-layout book",
                            subtitle = "Pages are fixed as published"
                        )
                    }
                }
            }
        }
        } // A-8 CompositionLocalProvider (WEBVIEW backdrop kind)
    }

    if (sheet != null) {
        // v1.5.9 (F-8e): the EPUB sheets take the surface tier
        // (#0c0d12 = surfaceContainerLow in the Nocturne scheme)
        // and Theme.kt's 28 dp sheet-top shape token.
        val glassSettings = LocalGlassSettings.current
        // A-8: the sheets are EPUB chrome too — same WEBVIEW
        // backdrop kind as the reader subtree above.
        CompositionLocalProvider(
            LocalReaderBackdropKind provides ReaderBackdropKind.WEBVIEW
        ) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(
                topStart = glassSettings.sheetRadius,
                topEnd = glassSettings.sheetRadius
            )
        ) {
            when (sheet) {
                EpubSheet.CONTENTS -> EpubContentsPanelSheet(
                    viewModel = viewModel,
                    session = session,
                    currentSection = progress?.sectionIndex,
                    initialTab = 0,
                    onPicked = { sheet = null },
                    onSearch = { sheet = EpubSheet.SEARCH },
                    sortOrder = contentsSort,
                    onSortOrderChange = { order ->
                        scope.launch {
                            barStore.saveContentsSort(ReaderFormat.EPUB, order)
                        }
                    }
                )
                EpubSheet.BOOKMARKS -> EpubContentsPanelSheet(
                    viewModel = viewModel,
                    session = session,
                    currentSection = progress?.sectionIndex,
                    initialTab = 1,
                    onPicked = { sheet = null },
                    onSearch = { sheet = EpubSheet.SEARCH },
                    sortOrder = contentsSort,
                    onSortOrderChange = { order ->
                        scope.launch {
                            barStore.saveContentsSort(ReaderFormat.EPUB, order)
                        }
                    }
                )
                EpubSheet.SEARCH -> EpubSearchSheet(
                    viewModel = viewModel,
                    onHit = { sheet = null }
                )
                EpubSheet.BAR -> CustomisableReaderBarSheetContent(
                    config = barConfig
                        ?: ReaderBarConfig.seededFrom(ReaderFormat.EPUB),
                    onConfigChange = { next ->
                        scope.launch { barStore.saveConfig(next) }
                    }
                )
                EpubSheet.CONTROL -> ControlOptionsSheetContent(
                    state = ControlOptionsState(
                        tapPreset = tapPreset,
                        tiltEnabled = tiltEnabled,
                        // The smart scroll lock is a PDF
                        // continuous-column control — its row
                        // is absent here, by the sheet's own
                        // contract.
                        smartScrollLock = null,
                        volumeKeyTurn = settings.volumeKeyTurn,
                        keepScreenOn = settings.keepScreenOn,
                        // v1.5.34 (item 3b): in EPUB scroll
                        // modes (river AND legacy) tap turn
                        // zones map to NONE — the sheet says so.
                        tapTurnsInert = settings.scroll
                    ),
                    onTapPreset = { preset ->
                        scope.launch {
                            barStore.saveTapPreset(ReaderFormat.EPUB, preset)
                        }
                    },
                    onTiltEnabled = { enabled ->
                        scope.launch { barStore.saveTiltEnabled(enabled) }
                    },
                    onSmartScrollLock = {},
                    onVolumeKeyTurn = { on ->
                        viewModel.updateEpubSettings {
                            it.copy(volumeKeyTurn = on)
                        }
                    },
                    onKeepScreenOn = { on ->
                        viewModel.updateEpubSettings {
                            it.copy(keepScreenOn = on)
                        }
                    }
                )
                EpubSheet.BOOK_INFO -> BookInfoSheetContent(
                    model = buildBookInfoModel(
                        inputs = BookInfoInputs(
                            title = ready.book.title,
                            contributors = listOfNotNull(
                                ready.book.author.takeIf { it.isNotBlank() }
                            ),
                            fileName = ready.book.filePath
                                ?.substringAfterLast('/')
                                ?: ready.book.fileFingerprint
                                    ?.substringBefore('|'),
                            location = ready.book.documentUri
                                ?: ready.book.filePath
                                    ?.substringBeforeLast('/'),
                            fileSizeBytes = ready.book.filePath?.let { path ->
                                runCatching {
                                    File(context.filesDir, path).length()
                                }.getOrNull()?.takeIf { it > 0 }
                            } ?: ready.book.fileFingerprint
                                ?.split('|')?.getOrNull(1)?.toLongOrNull(),
                            format = ReaderFormat.EPUB,
                            totalChapters = session.sectionCount,
                            currentChapterOneBased =
                                (progress?.sectionIndex ?: 0) + 1,
                            currentChapterTitle = progress?.chapterTitle,
                            wordsRemainingInChapter =
                                bookStats.textCounts?.words?.let { totalWords ->
                                    val p = progress
                                    if (p == null || p.sectionCount <= 0) {
                                        null
                                    } else {
                                        val chapterWords =
                                            totalWords / p.sectionCount
                                        (chapterWords *
                                            (1f - p.inSectionFraction))
                                            .toLong().coerceAtLeast(0L)
                                    }
                                },
                            bookProgression = progress?.fraction ?: 0f
                        ),
                        stats = bookStats
                    ),
                    isFavourite = isFavourite,
                    onToggleFavourite = { viewModel.toggleFavourite() },
                    onStarRatingChanged = { rating ->
                        scope.launch {
                            statsStore.saveStarRating(ready.book.id, rating)
                        }
                    },
                    // F2 door, live: the host's book-page
                    // callback for THIS book (the existing
                    // "book/<id>" route, wired by the shell
                    // through LocalDocReaderScreen). Null only
                    // when the host supplies none — the row is
                    // then absent, honestly, exactly as on the
                    // PDF side.
                    onOpenBookPage = onOpenBookPage,
                    onShare = epubBookShareSpec(ready.book)?.let { spec ->
                        { launchEpubBookShare(context, spec) }
                    }
                )
                EpubSheet.SETTINGS -> EpubSettingsSheet(
                    settings = settings,
                    updateSettings = { transform ->
                        viewModel.updateEpubSettings(transform)
                    },
                    autoScrollOn = autoScrollOn,
                    onToggleAutoScroll = toggleAutoScroll,
                    onCustomiseBar = { sheet = EpubSheet.BAR },
                    onControlOptions = { sheet = EpubSheet.CONTROL },
                    onPickUserFont = {
                        fontPicker.launch(
                            arrayOf(
                                "font/ttf", "font/otf",
                                "font/woff", "font/woff2",
                                "application/x-font-ttf",
                                "application/octet-stream"
                            )
                        )
                    },
                    onClearUserFont = {
                        viewModel.updateEpubSettings {
                            it.copy(userFontFileName = null)
                        }
                    }
                )
                null -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }
        } // A-8 CompositionLocalProvider (WEBVIEW backdrop kind)
    }
}

/**
 * v1.5.9 (F-6): the opening placeholder shows exactly while no
 * locator has landed yet. Pure so the predicate is testable
 * without a composition.
 */
internal fun epubOpeningPlaceholderVisible(firstLocatorLanded: Boolean): Boolean =
    !firstLocatorLanded

/** v1.5.9 delta (sift A-6): how long the first locator may be
 * absent before the placeholder's "Opening…" indicator joins
 * the (immediate) cover + title — the same ~120 ms one-flag
 * discipline as the PDF placeholder's spinner delay
 * (`PDF_PLACEHOLDER_SPINNER_DELAY_MS`). */
internal const val EPUB_PLACEHOLDER_INDICATOR_DELAY_MS = 120L

/** v1.5.18 (F3 / C-E2): how long after the first locator
 * lands the whole-book text sweep must still wait before its
 * settle gate opens — the sweep starts only once the reader
 * has visibly settled into reading, never in the open /
 * cover→plate window it used to compete with. */
internal const val EPUB_TEXT_SWEEP_SETTLE_DELAY_MS = 2_000L

/**
 * v1.5.9 delta (B-2): the WebView's bottom scroll inset — the
 * measured height of the gated bottom chrome while it is
 * shown, so the last line of a chapter can scroll clear of
 * the overlay; 0 whenever the chrome is hidden (the overlay
 * then covers nothing the inset would need to clear). Pure
 * so the invariant is testable without a composition.
 */
internal fun epubBottomScrollInsetPx(
    controlsVisible: Boolean,
    bottomChromeHeightPx: Int
): Int = if (controlsVisible) bottomChromeHeightPx.coerceAtLeast(0) else 0

/**
 * v1.5.10 (D2): the settle-gated inset state. B-2 fed the
 * WebView inset from the chrome's ANIMATED size: every
 * show/hide animation streamed intermediate heights through
 * `onSizeChanged` into `setPadding` → relayout → navigator
 * re-anchor, so scrolling visibly fought back around every
 * chrome toggle (Finding 7, candidate (e)). The producer now
 * runs this reducer: height measurements are only RECORDED
 * while a visibility transition is in flight; the APPLIED
 * height changes only at terminal values — hidden → 0
 * immediately; shown → the height measured once the shown
 * layout has settled (and post-settle re-measurements, e.g.
 * rotation, apply at once). B-2's guarantee is preserved in
 * full: the applied value still comes from
 * [epubBottomScrollInsetPx] over (visibility, settled
 * height), so with chrome shown the settled inset still
 * keeps a chapter's last line readable.
 */
internal data class EpubInsetState(
    val visible: Boolean = false,
    val settled: Boolean = false,
    val measuredHeightPx: Int = 0,
    val settledHeightPx: Int = 0
) {
    /** The ONLY inset EpubBody may apply — the reducer's
     * output, never a raw measurement. Gated on [settled]
     * as well as [visible]: a re-show begins UNSETTLED, so
     * the previous show's settled height must not re-apply
     * before the new ChromeSettled lands (the full-cycle
     * gate test's exact {0, settled} applied set). */
    val appliedInsetPx: Int
        get() = epubBottomScrollInsetPx(visible && settled, settledHeightPx)
}

/** The events the chrome host feeds the D2 reducer. */
internal sealed interface EpubInsetEvent {
    data class VisibilityChanged(val visible: Boolean) : EpubInsetEvent
    data class HeightMeasured(val heightPx: Int) : EpubInsetEvent

    /** The chrome's visibility transition has finished
     * (the AnimatedVisibility transition reached its target). */
    data object ChromeSettled : EpubInsetEvent
}

/** D2's pure reducer: (state, event) → next state. The
 * applied-inset sequence over any show/hide cycle is exactly
 * {0, settled} in some order — never an intermediate. */
internal fun epubInsetReducer(
    state: EpubInsetState,
    event: EpubInsetEvent
): EpubInsetState = when (event) {
    is EpubInsetEvent.VisibilityChanged ->
        if (event.visible) {
            // A show begins unsettled: measurements stream in
            // but nothing applies until ChromeSettled.
            state.copy(visible = true, settled = false)
        } else {
            // Hidden → 0 IMMEDIATELY (appliedInsetPx reads 0
            // for any hidden state); the next show re-settles.
            state.copy(visible = false, settled = false)
        }
    is EpubInsetEvent.HeightMeasured -> {
        val height = event.heightPx.coerceAtLeast(0)
        if (state.visible && state.settled) {
            // Post-settle re-measurement (rotation, chrome
            // content change): terminal by definition.
            state.copy(
                measuredHeightPx = height,
                settledHeightPx = height
            )
        } else {
            state.copy(measuredHeightPx = height)
        }
    }
    EpubInsetEvent.ChromeSettled ->
        if (state.visible) {
            state.copy(
                settled = true,
                settledHeightPx = state.measuredHeightPx
            )
        } else {
            state.copy(settled = true)
        }
}

/** The navigator's active WebView inside a fragment view tree.
 * Readium's ViewPager keeps adjacent spine WebViews attached;
 * prefer the visible WebView positioned inside the viewport,
 * falling back to the first WebView in tree order. */
internal fun findNavigatorWebView(root: View?): WebView? {
    if (root == null) return null
    var firstFallback: WebView? = null
    val rootLoc = IntArray(2)
    val childLoc = IntArray(2)
    val hasRootBounds = root.width > 0 && root.height > 0
    if (hasRootBounds) root.getLocationOnScreen(rootLoc)

    fun walk(node: View): WebView? {
        if (node is WebView) {
            if (firstFallback == null) firstFallback = node
            if (node.isShown && node.width > 0 && node.height > 0) {
                if (!hasRootBounds) return node
                node.getLocationOnScreen(childLoc)
                val centerX = childLoc[0] + node.width / 2
                if (centerX in rootLoc[0]..(rootLoc[0] + root.width)) {
                    return node
                }
            }
            return null
        }
        if (node is ViewGroup) {
            for (i in 0 until node.childCount) {
                walk(node.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
    return walk(root) ?: firstFallback
}

/**
 * v1.5.12 (E1+E3 / contract §2.1+§2.2+§2.5): the v1.5.11
 * native touch bridge (`EpubTouchContainer` +
 * `EpubTouchBridgeState`) is DELETED, with the whole
 * drag-advance road it fed. It decided chapter changes inside
 * `dispatchTouchEvent` — synchronously calling `goForward()`
 * before the triggering ACTION_UP was even delivered — and it
 * armed the selection poll on any ≥450 ms drag. No touch-
 * observing container wraps the navigator any more: the
 * fragment container is hosted plainly (see EpubBody's
 * AndroidView), drags belong to the Readium engine wholly,
 * and selection rides the WebView's native ActionMode.
 */

/**
 * v1.5.9 delta (B-2): apply the bottom scroll inset to the
 * navigator's WebView as PADDING with clipping off — the
 * toolkit equivalent of an internal scroll inset. The WebView
 * lays its content out in the padded viewport, so at maximum
 * scroll the chapter's last line rests ABOVE the padded
 * bottom instead of under the chrome overlay. The WebView's
 * own size never changes (F-2's constant surface stands);
 * only its internal viewport shrinks from the bottom.
 */
internal fun applyEpubBottomInset(root: View?, insetPx: Int) {
    val webView = findNavigatorWebView(root) ?: return
    webView.clipToPadding = false
    val target = insetPx.coerceAtLeast(0)
    if (webView.paddingBottom != target) {
        webView.setPadding(
            webView.paddingLeft,
            webView.paddingTop,
            webView.paddingRight,
            target
        )
    }
}

/**
 * v1.5.9 (F-2): the reader's overlay structure — a constant-size
 * body with the top bar and bottom chrome floating OVER it
 * (TopCenter / BottomCenter), and the permanent status line
 * stacked under the bottom chrome OUTSIDE the chrome gate.
 * The body content's size is identical whether the chrome is
 * visible or not (EpubBodySizeStabilityTest pins it); the
 * chrome's own geometry is unchanged because both bars already
 * self-pad (statusBarsPadding / navigationBarsPadding +
 * FloatingChromeLift).
 */
@Composable
internal fun EpubChromeOverlayHost(
    controlsVisible: Boolean,
    modifier: Modifier = Modifier,
    onBottomChromeHeightChanged: (Int) -> Unit = {},
    onBottomChromeSettled: () -> Unit = {},
    topBar: @Composable () -> Unit,
    bottomChrome: @Composable () -> Unit,
    statusLine: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            topBar()
        }
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                // B-2: report the chrome's real height (0 once
                // hidden — the exit animation has left the
                // composition) so the body can inset the
                // WebView's scroll range by exactly that much.
                // D2: the height is only RECORDED — the inset
                // applies at the settle reported below.
                modifier = Modifier.onSizeChanged {
                    onBottomChromeHeightChanged(it.height)
                }
            ) {
                // D2: the settle signal — this visibility
                // transition reaching (and resting at) its
                // Visible target is the terminal moment the
                // inset reducer waits for before applying the
                // measured height.
                val transition = this.transition
                LaunchedEffect(
                    transition.currentState, transition.targetState
                ) {
                    if (transition.currentState ==
                        androidx.compose.animation.EnterExitState.Visible &&
                        transition.targetState ==
                        androidx.compose.animation.EnterExitState.Visible
                    ) {
                        onBottomChromeSettled()
                    }
                }
                bottomChrome()
            }
            statusLine()
        }
    }
}

/**
 * v1.5.33 (R3, plan §7.3): the EPUB chrome palette — the
 * book's OWN page colours, so the chrome glass belongs to
 * the book's theme exactly as the novel reader's chrome
 * belongs to its page palette. Resolution mirrors
 * EpubRiverUserCss's tables (the reader's user colour wins;
 * the theme default sits behind it), so the bar and the
 * page always agree.
 */
private data class EpubChromePalette(
    val background: Color,
    val text: Color,
    val muted: Color,
    val accent: Color
)

private fun epubChromePalette(settings: EpubBookSettings): EpubChromePalette {
    val background = Color(
        settings.backgroundColorArgb ?: when (settings.theme) {
            EpubThemeChoice.LIGHT -> 0xFFFFFFFF.toInt()
            EpubThemeChoice.DARK -> 0xFF000000.toInt()
            EpubThemeChoice.SEPIA -> 0xFFF7ECD2.toInt()
        }
    )
    val text = Color(
        settings.textColorArgb ?: when (settings.theme) {
            EpubThemeChoice.LIGHT -> 0xFF1A1A1A.toInt()
            EpubThemeChoice.DARK -> 0xFFF2F2F2.toInt()
            EpubThemeChoice.SEPIA -> 0xFF4A3520.toInt()
        }
    )
    return EpubChromePalette(
        background = background,
        text = text,
        muted = text.copy(alpha = 0.62f),
        accent = ReaderViolet
    )
}

/** R3: the novel reader's frosted chip, mirrored in
 * localreader code (ui/reader is import-fenced, §7.3) and
 * tinted by the book's palette: a 40 dp circle, page colour
 * at 0.85, hairline border of the text colour at 0.12. */
@Composable
private fun EpubGlassChip(
    palette: EpubChromePalette,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = palette.background.copy(alpha = 0.85f),
        contentColor = palette.text,
        border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * v1.5.33 (R3): the status facts INSIDE the open bar —
 * v1.5.32's recorded R3 obligation. Battery · clock ·
 * chapter · position · book %, the shipped
 * [epubStatusLineContent] payload, rendered as ONE plain
 * line in the capsule: it exists only while the chrome is
 * composed, and vanishes with it. Battery + clock tick
 * once a minute, the LiveReaderStatusLine pattern.
 */
@Composable
private fun EpubBarInfoLine(
    progress: EpubProgress?,
    palette: EpubChromePalette
) {
    val context = LocalContext.current
    var battery by remember { mutableStateOf(-1) }
    var clock by remember {
        mutableStateOf(statusClockText(System.currentTimeMillis()))
    }
    LaunchedEffect(context) {
        while (true) {
            battery = runCatching {
                (context.getSystemService(Context.BATTERY_SERVICE)
                    as BatteryManager)
                    .getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CAPACITY
                    )
            }.getOrDefault(-1)
            clock = statusClockText(System.currentTimeMillis())
            delay(msToNextMinute(System.currentTimeMillis()) + 250L)
        }
    }
    Text(
        epubStatusLineContent(
            progress,
            progress?.withinChapter,
            if (battery >= 0) battery else null,
            clock
        ).render(),
        style = MaterialTheme.typography.labelSmall,
        color = palette.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    )
}

/** R3: one action tile in the novel reader's grammar
 * (§7.2's ChromeAction, mirrored): 16 dp tile, 22 dp icon
 * over a labelSmall label, the palette's text at 0.07 fill
 * (accent wash + accent border while active). */
@Composable
private fun EpubChromeTile(
    icon: ImageVector,
    label: String,
    palette: EpubChromePalette,
    onClick: () -> Unit,
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
                tint = if (active) palette.accent else palette.text,
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

private fun epubTileIcon(
    actionId: String,
    isDark: Boolean,
    ttsPlaying: Boolean
): ImageVector = when (actionId) {
    "dayNight" -> if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode
    "contents" -> Icons.Filled.List
    "bookmarks" -> Icons.Filled.Bookmark
    "search" -> Icons.Filled.Search
    "settings" -> Icons.Filled.TextFields
    "readAloud" -> if (ttsPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow
    else -> Icons.Filled.List
}

/** §7.3's tile labels — the novel reader's short plain
 * words (Listen, Settings, Day/Night); the other tiles
 * keep the quick-row model's own labels. */
private fun epubTileLabel(actionId: String, fallback: String): String =
    when (actionId) {
        "settings" -> "Settings"
        "readAloud" -> "Listen"
        "dayNight" -> "Day/Night"
        else -> fallback
    }

/** R3's default tile row (§7.3's table): Contents ·
 * Bookmarks · Search · Listen · Settings · Day/Night,
 * bound to the SAME shipped handlers through the
 * [epubQuickActions] model — placement, not machinery. */
@Composable
private fun EpubTileRow(
    handlers: EpubChromeHandlers,
    isDark: Boolean,
    ttsPlaying: Boolean,
    palette: EpubChromePalette
) {
    val byId = epubQuickActions(handlers).associateBy { it.id }
    val order = listOf(
        "contents", "bookmarks", "search",
        "readAloud", "settings", "dayNight"
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        order.mapNotNull { byId[it] }.forEach { action ->
            EpubChromeTile(
                icon = epubTileIcon(action.id, isDark, ttsPlaying),
                label = epubTileLabel(action.id, action.label),
                palette = palette,
                active = action.id == "readAloud" && ttsPlaying,
                onClick = action.onClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** R3's top bar: the novel reader's arrangement — Back
 * chip · glass title pill · Bookmark chip, separate
 * frosted pieces, no bar slab. Handlers are G3's; only
 * the styling became the sibling's. It pads itself for
 * the status bar (F2: the route is immersive). */
@Composable
private fun EpubTopBar(
    title: String,
    chapterTitle: String?,
    coverModel: Any? = null,
    palette: EpubChromePalette,
    onClose: () -> Unit,
    onBookmarkHere: () -> Unit,
    onTitleTap: () -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EpubGlassChip(palette = palette, onClick = onClose) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = palette.text
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = palette.background.copy(alpha = 0.85f),
            contentColor = palette.text,
            border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
            modifier = Modifier.weight(1f).clickable { onTitleTap() }
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(width = 30.dp, height = 42.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        title,
                        style = ReaderTitlePillTitleStyle,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!chapterTitle.isNullOrBlank()) {
                        Text(
                            chapterTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        EpubGlassChip(palette = palette, onClick = onBookmarkHere) {
            Icon(
                Icons.Filled.BookmarkAdd,
                contentDescription = "Bookmark here",
                tint = palette.text
            )
        }
    }
}

/** v1.5.33 (R3): the bottom chrome is the novel reader's
 * sibling (§7.3) — ONE palette-tinted frosted capsule holding
 * the info line, the progress block, the auto-scroll stop
 * (while it runs) and one labelled tile row. The mode chips
 * and the auto-scroll start are GONE from the surface (owner
 * order): mode switching and the auto-scroll start live in
 * Book settings, whose first section writes the same state.
 * The capsule pads itself for the navigation bar (F2) plus
 * the shared lift, so nothing essential sits in the bottom
 * cover sliver. */
@Composable
private fun EpubBottomChrome(
    viewModel: LocalDocReaderViewModel,
    session: ReadiumEpubSession,
    progress: EpubProgress?,
    settings: EpubBookSettings,
    handlers: EpubChromeHandlers,
    ttsPlaying: Boolean,
    autoScrollOn: Boolean,
    onToggleAutoScroll: () -> Unit,
    // v1.5.10 (F1): the customised bar. [barActions] null =
    // untouched store → the shipped quick row renders as before.
    barActions: List<ReaderBarActionId>? = null,
    barDoubleLine: Boolean = false,
    barActive: Set<ReaderBarActionId> = emptySet(),
    onOpenControlSheet: () -> Unit = {},
    onCycleOrientation: () -> Unit = {},
    onToggleTilt: () -> Unit = {}
) {
    // F1's brightness action: a slider popover over the same
    // window-brightness seam the 28 dp edge strip drives.
    val context = LocalContext.current
    var brightnessOpen by remember { mutableStateOf(false) }
    var brightness by remember { mutableStateOf(0.5f) }
    fun windowBrightness(): Float {
        val window = (context as? android.app.Activity)?.window
        val current = window?.attributes?.screenBrightness ?: -1f
        return if (current < 0f) 0.5f else current.coerceIn(0.02f, 1f)
    }
    fun applyBrightness(value: Float) {
        brightness = value
        val window = (context as? android.app.Activity)?.window
        window?.let {
            val params = it.attributes
            params.screenBrightness = value
            it.attributes = params
        }
    }
    val barHandlers: Map<ReaderBarActionId, () -> Unit> = mapOf(
        ReaderBarActionId.ORIENTATION to onCycleOrientation,
        ReaderBarActionId.DAY_NIGHT to handlers.onToggleDayNight,
        ReaderBarActionId.SPEAK to handlers.onReadAloud,
        ReaderBarActionId.AUTOSCROLL to onToggleAutoScroll,
        ReaderBarActionId.CHAPTERS to handlers.onContents,
        ReaderBarActionId.BOOKMARKS to handlers.onBookmarks,
        ReaderBarActionId.BRIGHTNESS to {
            if (!brightnessOpen) brightness = windowBrightness()
            brightnessOpen = !brightnessOpen
        },
        ReaderBarActionId.FONT_SIZE to handlers.onSettings,
        ReaderBarActionId.SEARCH to handlers.onSearch,
        ReaderBarActionId.TILT to onToggleTilt,
        ReaderBarActionId.VISUAL_OPTIONS to handlers.onSettings,
        ReaderBarActionId.CONTROL_OPTIONS to onOpenControlSheet
    )
    val palette = epubChromePalette(settings)
    Column(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = FloatingChromeLift),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (barActions != null && brightnessOpen) {
            GlassCapsule(Modifier.fillMaxWidth()) {
                BrightnessSliderRow(
                    value = brightness,
                    onValueChange = { applyBrightness(it) }
                )
            }
        }
        // R3: ONE frosted capsule in the novel reader's
        // grammar (§7.2): full-pill radius, page colour at
        // 0.94, hairline border, 6 dp shadow — holding the
        // info line, the progress block, the auto-scroll
        // stop while it runs, and ONE row of action tiles.
        Surface(
            shape = RoundedCornerShape(50),
            color = palette.background.copy(alpha = 0.94f),
            contentColor = palette.text,
            border = BorderStroke(1.dp, palette.text.copy(alpha = 0.12f)),
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                EpubBarInfoLine(progress = progress, palette = palette)
                EpubProgressBar(
                    viewModel = viewModel,
                    session = session,
                    progress = progress,
                    palette = palette
                )
                if (autoScrollOn) {
                    // The start lives in Book settings now;
                    // while auto-scroll RUNS, the bar carries
                    // its stop — a live control, not a chip.
                    Surface(
                        onClick = onToggleAutoScroll,
                        shape = RoundedCornerShape(50),
                        color = palette.accent.copy(alpha = 0.20f),
                        contentColor = palette.accent,
                        border = BorderStroke(
                            1.dp, palette.accent.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Row(
                            Modifier.padding(
                                horizontal = 12.dp, vertical = 6.dp
                            ),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Pause,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Auto-scroll on — tap to stop",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.accent
                            )
                        }
                    }
                }
                if (barActions != null) {
                    // F1: the shared config's row governs.
                    ReaderBarRow(
                        actions = barActions,
                        handlers = barHandlers,
                        doubleLineLayout = barDoubleLine,
                        activeActions = barActive
                    )
                } else {
                    EpubTileRow(
                        handlers = handlers,
                        isDark = settings.theme == EpubThemeChoice.DARK,
                        ttsPlaying = ttsPlaying,
                        palette = palette
                    )
                }
            }
        }
    }
}

/**
 * Moon+'s quick row, extracted (v1.5.9) so the tap→effect test
 * composes the REAL row: six glass chip buttons bound to the
 * production [epubQuickActions] model over the caller's
 * handlers — the same handlers the reader gathers once.
 */
@Composable
internal fun EpubQuickRow(
    handlers: EpubChromeHandlers,
    isDark: Boolean,
    ttsPlaying: Boolean
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top
    ) {
        epubQuickActions(handlers).forEach { action ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlassChipButton(onClick = action.onClick) {
                    EpubQuickActionIcon(
                        actionId = action.id,
                        isDark = isDark,
                        ttsPlaying = ttsPlaying
                    )
                }
                Text(
                    action.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EpubQuickActionIcon(actionId: String, isDark: Boolean, ttsPlaying: Boolean) {
    when (actionId) {
        "dayNight" -> Icon(
            if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = "Day / night"
        )
        "contents" -> Icon(Icons.Filled.List, contentDescription = "Contents")
        "bookmarks" -> Icon(Icons.Filled.Bookmark, contentDescription = "Bookmarks")
        "search" -> Icon(Icons.Filled.Search, contentDescription = "Search")
        "settings" -> Icon(Icons.Filled.TextFields, contentDescription = "Book settings")
        "readAloud" -> Icon(
            if (ttsPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = "Read aloud"
        )
        else -> Icon(Icons.Filled.List, contentDescription = actionId)
    }
}

@Composable
private fun EpubProgressBar(
    viewModel: LocalDocReaderViewModel,
    session: ReadiumEpubSession,
    progress: EpubProgress?,
    palette: EpubChromePalette
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val fraction = scrub ?: progress?.fraction ?: 0f
    val outline = session.outline
    val currentOutlinePos = remember(outline, progress?.sectionIndex) {
        val section = progress?.sectionIndex ?: 0
        outline.indexOfLast {
            (LocatorJson.targetIndex(it.targetJson) ?: Int.MAX_VALUE) <= section
        }
    }
    // v1.5.33 (R3): the sibling's progress block — ONE
    // merged label line ("Chapter N of M · NN%", the novel
    // footer's form, from EpubProgress's own fields) over
    // the slim slider, chapter-turn arrows at its sides.
    // Behaviour unchanged: scrub live, seek on release;
    // prev/next ride the outline exactly as before.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick = {
                outline.getOrNull(currentOutlinePos - 1)?.let { viewModel.goToOutline(it) }
            },
            enabled = currentOutlinePos > 0
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Previous chapter",
                tint = palette.text
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (progress == null) {
                    "Opening…"
                } else {
                    "Chapter ${progress.sectionIndex + 1} of " +
                        "${progress.sectionCount} · " +
                        "${(fraction * 100).toInt()}%"
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Slider(
                value = fraction.coerceIn(0f, 1f),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { viewModel.seekEpubToFraction(it) }
                    scrub = null
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        IconButton(
            onClick = {
                outline.getOrNull(currentOutlinePos + 1)?.let { viewModel.goToOutline(it) }
            },
            enabled = currentOutlinePos in 0 until outline.lastIndex
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Next chapter",
                tint = palette.text
            )
        }
    }
}

// ── The navigator host (Finding 1) ───────────────────────────────

/**
 * v1.5.9 (F-7a): ONE scroll-mode auto-scroll step. Mid-resource
 * the step goes to the stepped progression and reports true;
 * at the resource end the step BECOMES the page advance and
 * returns [goForward]'s own verdict — false at the end of the
 * book, which is what stops the engine (the paginated branch
 * always worked this way; the scroll branch used to discard
 * the verdict and step forever against the wall). Pure over
 * its two callbacks so the end-of-book test drives the real
 * shape against a fake surface.
 */
internal fun epubScrollAdvance(
    currentProgression: Double,
    step: Double,
    goForward: () -> Boolean,
    goToProgression: (Double) -> Unit
): Boolean {
    val next = currentProgression + step
    return if (next >= 1.0) {
        goForward()
    } else {
        goToProgression(next)
        true
    }
}

/**
 * v1.5.10 (F3): L-FG's [EpubSelectionNavigator] implemented
 * over D1's [EpubNavigatorSeam] — the ONE place Readium's
 * selection/decoration types are flattened into L-FG's
 * toolkit-free snapshot/spec types. Decorations rebuild over
 * the `user_highlights` group wholesale, the re-run's shape.
 */
private class SeamSelectionNavigator(
    private val seam: EpubNavigatorSeam,
    private val session: ReadiumEpubSession
) : EpubSelectionNavigator {

    override suspend fun currentSelection(): SelectionSnapshot? {
        val selection = seam.currentSelection() ?: return null
        val locator = selection.locator
        val rect = selection.rect
        return SelectionSnapshot(
            locatorJson = runCatching { locator.toJSON().toString() }
                .getOrNull() ?: return null,
            text = locator.text.highlight.orEmpty(),
            rect = rect?.let {
                SelectionRect(it.left, it.top, it.right, it.bottom)
            },
            href = locator.href.toString()
        )
    }

    override fun clearSelection() {
        seam.clearSelection()
    }

    override suspend fun applyDecorations(
        decorations: List<SelectionDecorationSpec>
    ) {
        seam.applyDecorations(
            decorations.mapNotNull { spec ->
                val locator = session.locatorFromJson(spec.locatorJson)
                    ?: return@mapNotNull null
                org.readium.r2.navigator.Decoration(
                    id = spec.highlightId,
                    locator = locator,
                    style = org.readium.r2.navigator.Decoration.Style
                        .Highlight(spec.tintArgb)
                )
            },
            SELECTION_DECORATION_GROUP
        )
    }
}

@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
@Composable
private fun EpubBody(
    viewModel: LocalDocReaderViewModel,
    session: ReadiumEpubSession,
    bookId: Long,
    bookFingerprint: String?,
    tapPreset: TapLayoutPreset,
    initialLocator: org.readium.r2.shared.publication.Locator?,
    initialPreferences: EpubPreferences,
    settings: EpubBookSettings,
    autoScroll: AutoScrollController,
    onToggleControls: () -> Unit,
    onFirstLocator: () -> Unit = {},
    onRiverModeChange: (Boolean) -> Unit = {},
    // v1.5.35 (restore §C.6): the river branch hands its
    // driver up so the host's ON_STOP observer can sequence
    // the final anchor pull before its flush. Never invoked
    // on the legacy road.
    onRiverDriverReady: (EpubRiverDriver?) -> Unit = {},
    bottomInsetPx: Int = 0
) {
    // v1.5.31 (EPUB RIVER R1, plan §2/§9): a scroll-mode,
    // reflowable, LTR book opens on the RIVER surface — the
    // merger's single document in one WebView — instead of the
    // Readium fragment below. The legacy install remains the
    // road for Page-by-page, fixed-layout and RTL books,
    // untouched; a book whose river preparation fails rejoins
    // it too (the honest on-screen word for that road landed
    // with R8 in v1.5.34 — see riverFallbackNote below).
    // v1.5.32: the branch carries its own collectors (the
    // legacy ones below never compose here) — position
    // reports feed the ViewModel seam (place save/restore,
    // live position for Bookmark here + Read aloud), jumps,
    // volume/tilt turns and an auto-scroll surface are wired
    // in the branch itself. v1.5.34 adds selection (R4), the
    // read-aloud follow (R6) and plate-tap arbitration.
    var riverUnavailable by remember(session) { mutableStateOf(false) }
    // v1.5.34 (R8, plan §9): when river preparation fails and
    // the book falls back to the classic reader below, the
    // reader SAYS so — the fallback used to be silent.
    var riverFallbackNote by remember(session) { mutableStateOf(false) }
    val riverRouted = settings.scroll && !session.isFixedLayout &&
        !riverUnavailable &&
        session.publication.metadata.readingProgression !=
            ReadingProgression.RTL
    // v1.5.32: the host gates the always-on status pill on
    // this (owner order — gone in river mode, kept in legacy).
    // riverRouted already folds in the unavailable fallback,
    // so a book whose merge fails gets its pill back with the
    // legacy surface it falls back to.
    LaunchedEffect(riverRouted) { onRiverModeChange(riverRouted) }
    if (riverRouted) {
        val riverContext = LocalContext.current
        val riverTapPreset = rememberUpdatedState(tapPreset)
        val riverToggle = rememberUpdatedState(onToggleControls)
        // v1.5.32 (items 5/6 — the dead-control audit's river
        // collectors): everything the legacy body registers
        // below its early return, the river branch registers
        // here against its own surface. The legacy collectors
        // never compose in this branch, so nothing is handled
        // twice. v1.5.34 extends the set: selection (R4), the
        // TTS follow (R6) and the plate arbitration (items
        // 0.3/3c).
        val riverDriver = remember { EpubRiverDriver() }
        // v1.5.35 (restore §C.6): register for the host's
        // ON_STOP final-pull sequence while this branch is
        // composed; leaving the branch (a mode switch to the
        // legacy surface) unregisters, and the host's flush
        // is then the plain legacy one again.
        DisposableEffect(riverDriver) {
            onRiverDriverReady(riverDriver)
            onDispose { onRiverDriverReady(null) }
        }
        val riverScope = rememberCoroutineScope()
        // Items 0.3/3c — PLATE-TAP CHROME ARBITRATION: a clean
        // tap first asks the host-side probe (the WebView's
        // HitTestResult for this very gesture); a plate hit
        // opens the viewer and the tap is spent. While the
        // viewer is open, taps belong to it, never the chrome.
        // The JS fallback road (SVG <image>) claims its tap
        // asynchronously, so the chrome decision waits one
        // short beat for a claim timestamped to this gesture
        // before it toggles.
        val onRiverTap: (Float, Float) -> Unit = remember {
            { xFraction, yFraction ->
                val viewerOpen =
                    riverDriver.plateViewerOpen?.invoke() == true
                val plateHit = !viewerOpen &&
                    riverDriver.probePlateTap
                        ?.invoke(xFraction, yFraction) == true
                if (!viewerOpen && !plateHit &&
                    epubScrollModeTapAction(
                        riverTapPreset.value, xFraction, yFraction
                    ) == TapZoneAction.TOGGLE_CHROME
                ) {
                    val tapAtMs = System.currentTimeMillis()
                    riverScope.launch {
                        delay(150)
                        val claimedAt =
                            riverDriver.lastPlateClaimMs?.invoke()
                                ?: 0L
                        if (claimedAt < tapAtMs - 100) {
                            riverToggle.value()
                        }
                    }
                }
            }
        }
        // Item 5 — JUMPS: contents rows, prev/next chapter,
        // the seek slider, bookmark rows and search hits all
        // arrive as LocalJump on the ViewModel's jumps flow;
        // the river lands them through the same progression
        // model it saves with (section start when the jump
        // carries no progression). v1.5.34 (R6): a search hit's
        // mark payload rides along — the river lands ON the
        // hit with a transient mark.
        LaunchedEffect(viewModel) {
            viewModel.jumps.collect { jump ->
                riverDriver.goToProgression?.invoke(
                    jump.index,
                    LocatorJson.epubProgression(jump.locatorJson)
                        ?.toDouble(),
                    jump.markQuery,
                    jump.markCharOffset
                )
            }
        }
        // Item 6 — VOLUME KEYS + TILT: tilt rides
        // onVolumeKeyTurn into this same flow (ReaderComforts
        // / TiltTurn). A turn is one screenful of scroll
        // (§3.2's scroll-mode equivalent), keeping a slice of
        // the old screen for context.
        LaunchedEffect(viewModel) {
            viewModel.volumeTurnRequests.collect { direction ->
                val screen = riverDriver.viewportHeightPx
                    ?.invoke() ?: 0
                if (screen > 0) {
                    riverDriver.stepBy?.invoke(
                        direction * (screen * 0.9f).toInt()
                    )
                }
            }
        }
        // v1.5.34 (R6) — READ ALOUD FOLLOWS: the spoken section
        // (the VM's ttsSectionFlow — the same live feed the
        // position reports drive) keeps itself in view while
        // read-aloud plays.
        LaunchedEffect(viewModel) {
            viewModel.ttsSectionFlow.collect { section ->
                if (section >= 0 && viewModel.ttsPlaying.value) {
                    riverDriver.followSection?.invoke(section)
                }
            }
        }
        // Item 6 — AUTO-SCROLL: register the river's driving
        // surface (the legacy registration is the mirror
        // below). The engine's STEP cadence at the stored
        // speed; each tick rolls the document by a fraction
        // of the screen, and the surface reports end-of-book
        // so the engine stops itself there. A speed change
        // re-registers (the controller restarts a running
        // engine on the new surface — the slider is live).
        DisposableEffect(autoScroll, settings.autoScrollSpeed) {
            val speed = settings.autoScrollSpeed
            val surface = AutoScrollEngine.Surface {
                val screen = riverDriver.viewportHeightPx
                    ?.invoke() ?: 0
                if (screen <= 0) {
                    false
                } else {
                    val dy = (screen * 0.02 * speed).toInt()
                        .coerceAtLeast(2)
                    riverDriver.advanceBy?.invoke(dy) ?: false
                }
            }
            autoScroll.register(
                scope = riverScope,
                surface = surface,
                intervalMs = AutoScrollEngine.intervalFor(
                    AutoScrollEngine.STEP_INTERVAL_MS, speed
                )
            )
            onDispose { autoScroll.unregister(surface) }
        }
        // ── v1.5.34 (R4): text selection on the river ────────
        // The legacy body's selection machinery, mirrored
        // against the river surface: the SAME store, the SAME
        // pure state machine, the SAME follow-on surfaces. The
        // only difference is the fetch — ONE __xrSelection()
        // pull per tapped ActionMode action (the legacy road
        // fetches the navigator's currentSelection()).
        val riverHighlightStore = remember(riverContext) {
            SelectionHighlightStore(riverContext.settingsDataStore)
        }
        val riverHighlights by riverHighlightStore.highlights(bookId)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        val riverHighlightsState = rememberUpdatedState(riverHighlights)
        var riverPopupState by remember {
            mutableStateOf<SelectionPopupState>(SelectionPopupState.Hidden)
        }
        var riverDictionaryResult by remember {
            mutableStateOf<Pair<String, String?>?>(null)
        }
        val executeRiverSelectionEffect: (SelectionEffect) -> Unit =
            { effect ->
                when (effect) {
                    is SelectionEffect.CopyText -> {
                        val clipboard = riverContext.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE
                        ) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "Xreader selection", effect.text
                            )
                        )
                    }
                    is SelectionEffect.AddHighlight -> riverScope.launch {
                        riverHighlightStore.add(
                            bookId,
                            StoredSelectionHighlight(
                                id = java.util.UUID.randomUUID().toString(),
                                locatorJson = effect.snapshot.locatorJson,
                                selectedText = effect.snapshot.text,
                                note = effect.note,
                                createdAtEpochMillis =
                                    System.currentTimeMillis()
                            )
                        )
                    }
                    is SelectionEffect.RemoveHighlight -> riverScope.launch {
                        riverHighlightStore.remove(
                            bookId, effect.highlightId
                        )
                    }
                    is SelectionEffect.UpdateNote -> riverScope.launch {
                        riverHighlightStore.updateNote(
                            bookId, effect.highlightId, effect.note
                        )
                    }
                    is SelectionEffect.LookupWord -> riverScope.launch {
                        val word = effect.word.trim()
                            .split(Regex("\\s+")).firstOrNull().orEmpty()
                        val definition = if (word.isBlank()) {
                            null
                        } else {
                            app.xreader.ui.library.DictionaryLookup.lookup(
                                riverContext.filesDir, word
                            )
                        }
                        riverDictionaryResult = word to definition
                    }
                    SelectionEffect.ClearSelection ->
                        riverDriver.clearSelection?.invoke()
                }
            }
        val dispatchRiverSelection: (SelectionEvent) -> Unit = { event ->
            val transition = selectionPopupReducer(riverPopupState, event)
            riverPopupState = transition.state
            transition.effects.forEach { effect ->
                executeRiverSelectionEffect(effect)
            }
        }
        val riverDispatchState = rememberUpdatedState(dispatchRiverSelection)
        // A tapped ActionMode action: ONE selection pull, then
        // the Polled + action dispatch pair (the legacy
        // handler's exact shape).
        val handleRiverSelectionAction: (EpubSelectionMenuAction) -> Unit =
            { action ->
                riverScope.launch {
                    val snapshot =
                        kotlinx.coroutines.suspendCancellableCoroutine<
                            SelectionSnapshot?
                        > { cont ->
                            val slot = riverDriver.fetchSelection
                            if (slot == null) {
                                if (cont.isActive) {
                                    cont.resumeWith(Result.success(null))
                                }
                            } else {
                                slot { result ->
                                    if (cont.isActive) {
                                        cont.resumeWith(
                                            Result.success(result)
                                        )
                                    }
                                }
                            }
                        }
                    if (snapshot == null || snapshot.text.isBlank()) {
                        riverDriver.clearSelection?.invoke()
                        return@launch
                    }
                    riverDispatchState.value(
                        SelectionEvent.Polled(snapshot)
                    )
                    riverDispatchState.value(
                        when (action) {
                            EpubSelectionMenuAction.COPY ->
                                SelectionEvent.CopyChosen
                            EpubSelectionMenuAction.HIGHLIGHT ->
                                SelectionEvent.HighlightChosen
                            EpubSelectionMenuAction.NOTE ->
                                SelectionEvent.NoteChosen
                            EpubSelectionMenuAction.DICTIONARY ->
                                SelectionEvent.DictionaryChosen
                        }
                    )
                }
            }
        Box(
            Modifier.fillMaxSize().epubScrollTapObserver(onRiverTap)
        ) {
            EpubRiverView(
                session = session,
                bookId = bookId,
                bookFingerprint = bookFingerprint,
                settings = settings,
                initialSection = initialLocator?.href?.toString()
                    ?.let { session.sectionIndexOfHref(it) } ?: 0,
                initialProgression = initialLocator?.locations
                    ?.progression,
                initialQuote = initialLocator?.text?.highlight,
                driver = riverDriver,
                onPositionReport = { locator ->
                    viewModel.onEpubLocator(locator)
                },
                onFirstPaint = onFirstLocator,
                onUnavailable = {
                    riverUnavailable = true
                    riverFallbackNote = true
                },
                highlights = riverHighlights,
                onHighlightTap = { id ->
                    riverHighlightsState.value.firstOrNull {
                        it.id == id
                    }?.let { highlight ->
                        riverDispatchState.value(
                            SelectionEvent.DecorationTapped(highlight)
                        )
                    }
                },
                onSelectionAction = handleRiverSelectionAction
            )
            // R4 follow-on surfaces (the legacy body's own
            // shapes): the note editor and a tapped highlight's
            // menu, in a capsule at the top of the reader.
            when (val state = riverPopupState) {
                is SelectionPopupState.Shown -> Unit
                is SelectionPopupState.NoteEditing -> {
                    val editing = riverHighlights.firstOrNull {
                        it.id == state.existingHighlightId
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 84.dp)
                    ) {
                        GlassCapsule(Modifier.fillMaxWidth()) {
                            SelectionNoteEditor(
                                selectedText = state.snapshot.text,
                                initialNote = editing?.note.orEmpty(),
                                onSave = { note ->
                                    dispatchRiverSelection(
                                        SelectionEvent.NoteSaved(note)
                                    )
                                },
                                onCancel = {
                                    dispatchRiverSelection(
                                        SelectionEvent.NoteCancelled
                                    )
                                }
                            )
                        }
                    }
                }
                is SelectionPopupState.HighlightMenu -> {
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 84.dp)
                    ) {
                        GlassCapsule(Modifier.fillMaxWidth()) {
                            SelectionHighlightMenu(
                                highlight = state.highlight,
                                onRemove = {
                                    dispatchRiverSelection(
                                        SelectionEvent.RemoveHighlightChosen
                                    )
                                },
                                onEditNote = {
                                    dispatchRiverSelection(
                                        SelectionEvent.EditNoteChosen
                                    )
                                },
                                onDismiss = {
                                    dispatchRiverSelection(
                                        SelectionEvent.Dismissed
                                    )
                                }
                            )
                        }
                    }
                }
                SelectionPopupState.Hidden -> Unit
            }
            riverDictionaryResult?.let { (word, definition) ->
                AlertDialog(
                    onDismissRequest = { riverDictionaryResult = null },
                    title = { Text(word) },
                    text = {
                        Text(
                            definition
                                ?: "No dictionary entry for this word yet."
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { riverDictionaryResult = null }
                        ) {
                            Text("Close")
                        }
                    }
                )
            }
        }
        return
    }
    val context = LocalContext.current
    val activity = remember(context) { context as? FragmentActivity }
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val containerId = remember { View.generateViewId() }
    val bodyScope = rememberCoroutineScope()

    val navigatorState = rememberUpdatedState(navigator)
    val toggleState = rememberUpdatedState(onToggleControls)
    val firstLocatorState = rememberUpdatedState(onFirstLocator)

    // The narrow navigator seam over the LIVE fragment (the
    // selection wiring consumes [EpubNavigatorSeam], never the
    // fragment). Reads the live fragment through the updated-
    // state supplier: the instance is captured outside the
    // creation branch and can be replaced on recreation.
    val navigatorSeam = remember {
        FragmentEpubNavigatorSeam { navigatorState.value }
    }

    // v1.5.12 (E3): the native selection menu's action sink.
    // The fragment Configuration (built in install() below)
    // carries an [EpubSelectionActionModeCallback] that routes
    // menu taps here; the selection section further down
    // publishes the real handler into this holder (it needs the
    // highlight store + dispatch machinery defined there). A
    // menu tap before that publication is a no-op — the
    // navigator cannot exist before install() runs anyway.
    val selectionMenuSink = remember {
        object {
            var handler: (EpubSelectionMenuAction) -> Unit = {}
        }
    }
    val selectionActionModeCallbackValue = remember {
        EpubSelectionActionModeCallback { action ->
            selectionMenuSink.handler(action)
        }
    }

    // Finding 1 §(d) step 2: ONE input listener for the reader's
    // lifetime. Centre third toggles the chrome; edge thirds are
    // the deterministic page-turn path (goForward/goBackward).
    // v1.5.12 (E1 / contract §2.2): the listener is the
    // reference-shaped [EpubReaderInputListener] — taps + keys
    // only; its onDrag returns false for every drag, so in
    // scroll mode the engine owns vertical drags wholly
    // (chapter advancement is the engine's own scrolling
    // machinery) and NO code path converts a drag into a
    // chapter change. The v1.5.10/v1.5.11 drag-advance
    // controller behind the old onDrag is deleted.
    // v1.5.18 (F3 / C-E1): the listener is now registered ONLY
    // in paginated mode ([epubShouldRegisterInputListener]).
    // In 3.1.2 every verdict a registered listener receives —
    // drags included — costs a synchronous JS→main round-trip,
    // so the always-false drag verdict still taxed every
    // scroll attempt. Scroll mode registers nothing: drags are
    // the engine's natively, tap-turns are inert there, and
    // the chrome toggle rides the watch-only Compose observer
    // on the body container below ([epubScrollTapObserver] +
    // [epubScrollModeTapAction]). Link taps and native text
    // selection never travelled through this listener (the
    // navigator's own link handling; the Configuration's
    // ActionMode callback + on-demand currentSelection()) and
    // are untouched.
    val tapPresetState = rememberUpdatedState(tapPreset)
    val pageTurner = remember {
        FragmentEpubPageTurner { navigatorState.value }
    }
    val inputListener = remember {
        EpubReaderInputListener(
            turner = pageTurner,
            tapPreset = { tapPresetState.value },
            onToggleControls = { toggleState.value() }
        )
    }
    // v1.5.18 (F3 / C-E1): scroll mode's chrome toggle — a
    // stable callback (it reads only updated-state holders)
    // for the watch-only observer on the body container.
    val onScrollModeTap: (Float, Float) -> Unit = remember {
        { xFraction, yFraction ->
            if (epubScrollModeTapAction(
                    tapPresetState.value, xFraction, yFraction
                ) == TapZoneAction.TOGGLE_CHROME
            ) {
                toggleState.value()
            }
        }
    }

    // v1.5.34 (R8): the v1.5.19 boundary-push apparatus is
    // REMOVED. It existed because Readium 3.1.2's scroll mode
    // has no cross-resource continuation — a chapter's last
    // screen was a dead wall. The river is the scroll surface
    // now (one document, no walls); the legacy body keeps
    // scroll mode only as the fallback for fixed-layout / RTL
    // / unmergeable books, where a resource simply ends and
    // the explicit roads (contents, chapter buttons, seek)
    // cross — honestly, without a fake push that landed wrong
    // in one direction and could wedge in the other.

    // The fragment transaction must run once the container view
    // is ATTACHED, so installation is posted from the view's own
    // queue below, not from an effect that can race composition.
    val install: () -> Unit = remember(session, activity, containerId, initialPreferences) {
        {
            val manager = activity?.supportFragmentManager
            if (manager != null) {
                if (manager.findFragmentById(containerId) == null) {
                    val factory = EpubNavigatorFactory(session.publication)
                        .createFragmentFactory(
                            initialLocator = initialLocator,
                            initialPreferences = initialPreferences,
                            // v1.5.12 (E3): the Configuration
                            // ALWAYS carries the native selection
                            // ActionMode callback (the platform
                            // selection pattern — the WebView's
                            // own menu, Xreader's actions as
                            // items). v1.5.10 (G3): when the
                            // session serves a user font, its
                            // declaration joins the same
                            // Configuration — the @font-face
                            // source is the synthetic href the
                            // session's container wrap serves
                            // (see ReadiumEpubSession).
                            configuration = EpubNavigatorFragment.Configuration()
                                .apply {
                                    this.selectionActionModeCallback =
                                        selectionActionModeCallbackValue
                                    session.userFont?.let { font ->
                                        addFontFamilyDeclaration(
                                            FontFamily(font.familyName)
                                        ) {
                                            addFontFace {
                                                addSource(font.href)
                                            }
                                        }
                                    }
                                }
                        )
                    // v1.5.12 (E4 / contract + Readium guide):
                    // the REAL factory is assigned to the
                    // manager before the transaction below —
                    // and MainActivity has already assigned
                    // Readium's DUMMY factory before
                    // super.onCreate, so a process-death
                    // restoration of this fragment (which runs
                    // inside super.onCreate, before any Compose
                    // code) can instantiate the class without a
                    // live publication instead of crashing.
                    manager.fragmentFactory = factory
                    runCatching {
                        manager.beginTransaction()
                            .add(containerId, EpubNavigatorFragment::class.java, null)
                            .commitNow()
                    }
                }
                // Finding 1 §(d) step 3 — the capture-hole fix:
                // capture the fragment OUTSIDE the creation branch,
                // so a fragment restored by the FragmentManager
                // (activity recreation) is captured too and gets
                // the listener, the locator collector and jumps.
                navigator = manager.findFragmentById(containerId)
                    as? EpubNavigatorFragment
            }
        }
    }

    // v1.5.9 delta (B-2): keep the navigator WebView's bottom
    // scroll inset in step with the chrome (see
    // applyEpubBottomInset). A SideEffect so it re-applies
    // whenever the fragment view or the inset changes across
    // recompositions; the walk is a handful of views.
    SideEffect {
        applyEpubBottomInset(navigator?.view, bottomInsetPx)
    }

    // Register/unregister the input listener on the LIVE fragment
    // instance (the only place 3.1.2 exposes addInputListener).
    // v1.5.18 (F3 / C-E1): registration is GATED by the reading
    // mode — paginated only. Flipping the mode re-runs this
    // effect: entering paginated registers the one listener,
    // entering scroll removes it, so a mode the user is not in
    // never holds a bridge subscription.
    val registerInputListener =
        epubShouldRegisterInputListener(settings.scroll)
    DisposableEffect(navigator, registerInputListener) {
        val fragment = navigator
        if (registerInputListener) {
            fragment?.let {
                runCatching { it.addInputListener(inputListener) }
            }
        }
        onDispose {
            if (registerInputListener) {
                fragment?.let {
                    runCatching { it.removeInputListener(inputListener) }
                }
            }
        }
    }

    DisposableEffect(session, activity, containerId) {
        onDispose {
            val manager = activity?.supportFragmentManager
            val fragment = manager?.findFragmentById(containerId)
            if (manager != null && fragment != null) {
                runCatching {
                    manager.beginTransaction().remove(fragment).commitNow()
                }
            }
            navigator = null
        }
    }

    LaunchedEffect(navigator) {
        navigator?.currentLocator?.collectLatest { locator ->
            viewModel.onEpubLocator(locator)
            // v1.5.9 (F-6): the first locator is the first
            // paint — the placeholder's cue to fade out.
            firstLocatorState.value()
        }
    }
    LaunchedEffect(navigator, viewModel) {
        viewModel.jumps.collect { jump ->
            val fragment = navigator ?: return@collect
            val locator = jump.locatorJson?.let { session.locatorFromJson(it) }
                ?: session.locatorForSection(jump.index)
            if (locator != null) fragment.go(locator, animated = false)
        }
    }
    // G8: volume-key turns arrive from the comfort host's
    // registry registration and turn the toolkit's pages.
    LaunchedEffect(navigator, viewModel) {
        viewModel.volumeTurnRequests.collect { direction ->
            val fragment = navigator ?: return@collect
            if (direction > 0) fragment.goForward(true) else fragment.goBackward(true)
        }
    }
    // Book-mode settings ride to the navigator as they change.
    LaunchedEffect(navigator, settings) {
        val fragment = navigator ?: return@LaunchedEffect
        runCatching { fragment.submitPreferences(settings.toEpubPreferences(session)) }
    }

    // ── Text selection — native menu, highlights (F3) ───────
    // v1.5.12 (E3 / contract §2.5): 3.1.2 has NO
    // selection-changed callback, and the reader no longer
    // polls for one. The WebView's native ActionMode (the
    // Configuration's selectionActionModeCallback, set in
    // install()) announces selections itself; a tap on one of
    // Xreader's menu items lands in the handler below, which
    // fetches currentSelection() ONCE and drives the pure
    // state machine; effects execute here (clipboard, the
    // highlight store, the dictionary seam). Highlights
    // persist per book in the shared store and re-apply at
    // open over the user_highlights group. Idle cost: zero —
    // no loop, no timer, no periodic JS evaluation.
    val selectionNavigator = remember {
        SeamSelectionNavigator(navigatorSeam, session)
    }
    val highlightStore = remember(context) {
        SelectionHighlightStore(context.settingsDataStore)
    }
    val epubHighlights by highlightStore.highlights(bookId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val highlightsState = rememberUpdatedState(epubHighlights)
    var popupState by remember {
        mutableStateOf<SelectionPopupState>(SelectionPopupState.Hidden)
    }
    var dictionaryResult by remember {
        mutableStateOf<Pair<String, String?>?>(null)
    }
    val executeSelectionEffect: (SelectionEffect) -> Unit = { effect ->
        when (effect) {
            is SelectionEffect.CopyText -> {
                val clipboard = context.getSystemService(
                    android.content.Context.CLIPBOARD_SERVICE
                ) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "Xreader selection", effect.text
                    )
                )
            }
            is SelectionEffect.AddHighlight -> bodyScope.launch {
                highlightStore.add(
                    bookId,
                    StoredSelectionHighlight(
                        id = java.util.UUID.randomUUID().toString(),
                        locatorJson = effect.snapshot.locatorJson,
                        selectedText = effect.snapshot.text,
                        note = effect.note,
                        createdAtEpochMillis = System.currentTimeMillis()
                    )
                )
            }
            is SelectionEffect.RemoveHighlight -> bodyScope.launch {
                highlightStore.remove(bookId, effect.highlightId)
            }
            is SelectionEffect.UpdateNote -> bodyScope.launch {
                highlightStore.updateNote(
                    bookId, effect.highlightId, effect.note
                )
            }
            is SelectionEffect.LookupWord -> bodyScope.launch {
                val word = effect.word.trim()
                    .split(Regex("\\s+")).firstOrNull().orEmpty()
                val definition = if (word.isBlank()) {
                    null
                } else {
                    app.xreader.ui.library.DictionaryLookup.lookup(
                        context.filesDir, word
                    )
                }
                dictionaryResult = word to definition
            }
            SelectionEffect.ClearSelection ->
                selectionNavigator.clearSelection()
        }
    }
    val effectState = rememberUpdatedState(executeSelectionEffect)
    val dispatchSelection: (SelectionEvent) -> Unit = { event ->
        val transition = selectionPopupReducer(popupState, event)
        popupState = transition.state
        transition.effects.forEach { effect -> effectState.value(effect) }
    }
    val dispatchState = rememberUpdatedState(dispatchSelection)

    // v1.5.12 (E3): the native selection menu's handler. ONE
    // on-demand currentSelection() fetch per tapped action —
    // the snapshot is fed to the state machine as a Polled
    // event (which shows the — now never rendered — Shown
    // state for exactly one synchronous step) and the chosen
    // action's event follows immediately, so Copy / Highlight
    // / Dictionary complete through the SAME effects as
    // before, and Note lands in the NoteEditing state whose
    // editor surface renders below. Nothing runs when the
    // user is not acting on a selection.
    val handleSelectionMenuAction: (EpubSelectionMenuAction) -> Unit =
        { action ->
            bodyScope.launch {
                val snapshot = runCatching {
                    selectionNavigator.currentSelection()
                }.getOrNull()
                if (snapshot == null || snapshot.text.isBlank()) {
                    selectionNavigator.clearSelection()
                    return@launch
                }
                dispatchState.value(SelectionEvent.Polled(snapshot))
                dispatchState.value(
                    when (action) {
                        EpubSelectionMenuAction.COPY ->
                            SelectionEvent.CopyChosen
                        EpubSelectionMenuAction.HIGHLIGHT ->
                            SelectionEvent.HighlightChosen
                        EpubSelectionMenuAction.NOTE ->
                            SelectionEvent.NoteChosen
                        EpubSelectionMenuAction.DICTIONARY ->
                            SelectionEvent.DictionaryChosen
                    }
                )
            }
        }
    // Publish the handler into the sink the fragment
    // Configuration's callback routes to (defined above,
    // before install()).
    SideEffect {
        selectionMenuSink.handler = handleSelectionMenuAction
    }

    // Decorations: applied at open, rebuilt on any highlight
    // change (the group replaces wholesale per apply).
    LaunchedEffect(navigator, epubHighlights) {
        if (navigator != null) {
            runCatching {
                selectionNavigator.applyDecorations(
                    decorationsFor(epubHighlights)
                )
            }
        }
    }
    // A tapped decoration opens its highlight's menu.
    DisposableEffect(navigator) {
        val fragment = navigator
        if (fragment == null) {
            onDispose { }
        } else {
            val listener =
                object : org.readium.r2.navigator.DecorableNavigator.Listener {
                    override fun onDecorationActivated(
                        event: org.readium.r2.navigator.DecorableNavigator
                            .OnActivatedEvent
                    ): Boolean {
                        val highlight = highlightsState.value.firstOrNull {
                            it.id == event.decoration.id
                        }
                        return if (highlight != null) {
                            dispatchState.value(
                                SelectionEvent.DecorationTapped(highlight)
                            )
                            true
                        } else {
                            false
                        }
                    }
                }
            runCatching {
                fragment.addDecorationListener(
                    SELECTION_DECORATION_GROUP, listener
                )
            }
            onDispose {
                runCatching { fragment.removeDecorationListener(listener) }
            }
        }
    }
    // v1.5.12 (E3): the selection POLL is deleted — the whole
    // 280 ms LaunchedEffect loop, its foreground gate, and the
    // touch bridge that armed it. Selection discovery is the
    // native ActionMode's job now (see install() and
    // handleSelectionMenuAction above); there is no periodic
    // currentSelection() evaluation on this screen at all.

    // G9: register this body's auto-scroll surface. Scroll mode
    // steps the locator's progression within the current
    // resource (the `go` road search/bookmark jumps travel — a
    // scroll-mode view moves to each step); at the resource's
    // end the step becomes a page-advance into the next one.
    // Paginated mode is a timer over goForward.
    DisposableEffect(navigator, settings.scroll, settings.autoScrollSpeed) {
        val fragment = navigator
        if (fragment == null) {
            onDispose { }
        } else {
            val scrollMode = settings.scroll
            val speed = settings.autoScrollSpeed
            val surface = AutoScrollEngine.Surface {
                if (scrollMode) {
                    val current = fragment.currentLocator.value
                    val progression = current.locations.progression ?: 0.0
                    // v1.5.9 (F-7a): at the resource end the
                    // step returns goForward's verdict, so the
                    // engine stops at the end of the book.
                    epubScrollAdvance(
                        currentProgression = progression,
                        step = AutoScrollEngine.progressionStep(speed),
                        goForward = { fragment.goForward(true) },
                        goToProgression = { next ->
                            fragment.go(
                                current.copy(
                                    locations = current.locations.copy(
                                        progression = next
                                    )
                                ),
                                animated = false
                            )
                        }
                    )
                } else {
                    fragment.goForward(true)
                }
            }
            autoScroll.register(
                scope = bodyScope,
                surface = surface,
                intervalMs = AutoScrollEngine.intervalFor(
                    if (scrollMode) {
                        AutoScrollEngine.STEP_INTERVAL_MS
                    } else {
                        AutoScrollEngine.PAGE_INTERVAL_MS
                    },
                    speed
                )
            )
            onDispose { autoScroll.unregister(surface) }
        }
    }

    Box(
        Modifier.fillMaxSize().then(
            // v1.5.18 (F3 / C-E1): SCROLL mode only — the
            // watch-only tap observer that carries the chrome
            // toggle now that no InputListener is registered.
            // It consumes nothing (the v1.5.12 note below still
            // holds: no container DECIDES anything here; clean
            // taps are merely REPORTED, and only the preset's
            // chrome zone acts on them). In paginated mode the
            // observer is absent — the InputListener owns taps.
            // v1.5.34 (R8): the v1.5.19 boundary-push observer
            // that chained here is gone with the apparatus it
            // fed (see the note at the branch above).
            if (settings.scroll) {
                Modifier
                    .epubScrollTapObserver(onScrollModeTap)
            } else {
                Modifier
            }
        )
    ) {
        // v1.5.12 (E1): the fragment container is hosted
        // PLAINLY — no touch-observing wrapper. The v1.5.11
        // EpubTouchContainer that decided chapter changes
        // inside dispatchTouchEvent is deleted; touches flow
        // straight to the navigator, whose engine owns drags.
        AndroidView(
            factory = { ctx ->
                FragmentContainerView(ctx).apply {
                    id = containerId
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    post { install() }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        // v1.5.12 (E3): there is NO Compose selection popup any
        // more — the native ActionMode menu IS the selection
        // surface. What still renders here are the FOLLOW-ON
        // surfaces a chosen action opens: the note editor (a
        // Note menu tap lands the state machine in NoteEditing)
        // and a tapped highlight's menu (event-driven through
        // the decoration listener, never a poll). The Shown
        // state exists only transiently inside the menu
        // handler's synchronous dispatch pair and renders
        // nothing.
        when (val state = popupState) {
            is SelectionPopupState.Shown -> Unit
            is SelectionPopupState.NoteEditing -> {
                val editing = epubHighlights.firstOrNull {
                    it.id == state.existingHighlightId
                }
                Box(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 84.dp)
                ) {
                    GlassCapsule(Modifier.fillMaxWidth()) {
                        SelectionNoteEditor(
                            selectedText = state.snapshot.text,
                            initialNote = editing?.note.orEmpty(),
                            onSave = { note ->
                                dispatchSelection(
                                    SelectionEvent.NoteSaved(note)
                                )
                            },
                            onCancel = {
                                dispatchSelection(
                                    SelectionEvent.NoteCancelled
                                )
                            }
                        )
                    }
                }
            }
            is SelectionPopupState.HighlightMenu -> {
                Box(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 84.dp)
                ) {
                    GlassCapsule(Modifier.fillMaxWidth()) {
                        SelectionHighlightMenu(
                            highlight = state.highlight,
                            onRemove = {
                                dispatchSelection(
                                    SelectionEvent.RemoveHighlightChosen
                                )
                            },
                            onEditNote = {
                                dispatchSelection(
                                    SelectionEvent.EditNoteChosen
                                )
                            },
                            onDismiss = {
                                dispatchSelection(
                                    SelectionEvent.Dismissed
                                )
                            }
                        )
                    }
                }
            }
            SelectionPopupState.Hidden -> Unit
        }
        dictionaryResult?.let { (word, definition) ->
            AlertDialog(
                onDismissRequest = { dictionaryResult = null },
                title = { Text(word) },
                text = {
                    Text(
                        definition
                            ?: "No dictionary entry for this word yet."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { dictionaryResult = null }) {
                        Text("Close")
                    }
                }
            )
        }
        // v1.5.34 (R8, plan §9): the honest fallback word — a
        // book whose river preparation failed opened HERE, in
        // the classic reader, and says so once instead of
        // silently arriving on a different surface.
        LaunchedEffect(riverFallbackNote) {
            if (riverFallbackNote) {
                delay(6_000)
                riverFallbackNote = false
            }
        }
        if (riverFallbackNote) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp, start = 24.dp, end = 24.dp)
            ) {
                Text(
                    "This book couldn't be prepared for " +
                        "scrolling, so it opened in the classic " +
                        "reader.",
                    modifier = Modifier.padding(
                        horizontal = 14.dp, vertical = 10.dp
                    )
                )
            }
        }
    }
}

/**
 * Builds the [EpubPreferences] for [this] settings set and passes
 * them through the 3.1.2 preferences EDITOR
 * (`EpubNavigatorFactory.createPreferencesEditor`) so values are
 * clamped to the toolkit's supported ranges/effective rules
 * before they reach the navigator. The plate treatment is set
 * ONLY here and ONLY as `imageFilter`, ONLY while the dark theme
 * is active (split report §4).
 */
@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
internal fun EpubBookSettings.toEpubPreferences(
    session: ReadiumEpubSession
): EpubPreferences {
    val raw = EpubPreferences(
        backgroundColor = backgroundColorArgb?.let { ReadiumColor(it) },
        // v1.5.10 (G3): a picked user font (declared by the
        // fragment configuration, served by the session's
        // container) wins over the bundled names; the bundled
        // seven are otherwise exactly as shipped.
        fontFamily = if (userFontFileName != null && session.userFont != null) {
            FontFamily(session.userFont.familyName)
        } else {
            when (fontFamilyName) {
                "SERIF" -> FontFamily.SERIF
                "SANS_SERIF" -> FontFamily.SANS_SERIF
                "MONOSPACE" -> FontFamily.MONOSPACE
                "OPEN_DYSLEXIC" -> FontFamily.OPEN_DYSLEXIC
                "ACCESSIBLE_DFA" -> FontFamily.ACCESSIBLE_DFA
                "IA_WRITER_DUOSPACE" -> FontFamily.IA_WRITER_DUOSPACE
                else -> null
            }
        },
        fontSize = fontSize,
        fontWeight = fontWeight,
        imageFilter = if (theme == EpubThemeChoice.DARK) {
            when (plateFilter) {
                EpubPlateFilter.ORIGINAL -> null
                EpubPlateFilter.DARKEN -> ImageFilter.DARKEN
                EpubPlateFilter.INVERT -> ImageFilter.INVERT
            }
        } else {
            null
        },
        letterSpacing = letterSpacing,
        lineHeight = lineHeight,
        pageMargins = pageMargins,
        paragraphIndent = paragraphIndent,
        paragraphSpacing = paragraphSpacing,
        // v1.5.20: reader settings must win over the book's own
        // publisher styling — left unset, Readium resolves this
        // to TRUE and the advanced ReadiumCSS variables above
        // (paragraph spacing, line height, indent, letter/word
        // spacing, justification, hyphens) are never consumed.
        publisherStyles = false,
        scroll = scroll,
        // v1.5.8 (G6): justified alignment + hyphenation — the
        // toolkit's standard preferences, per-book like every
        // reader setting. Justify defaults ON in the settings.
        textAlign = if (justify) {
            org.readium.r2.navigator.preferences.TextAlign.JUSTIFY
        } else {
            org.readium.r2.navigator.preferences.TextAlign.START
        },
        hyphens = hyphens,
        textColor = textColorArgb?.let { ReadiumColor(it) },
        theme = when (theme) {
            EpubThemeChoice.LIGHT -> Theme.LIGHT
            EpubThemeChoice.DARK -> Theme.DARK
            EpubThemeChoice.SEPIA -> Theme.SEPIA
        },
        wordSpacing = wordSpacing
    )
    return runCatching {
        EpubNavigatorFactory(session.publication)
            .createPreferencesEditor(raw).preferences
    }.getOrDefault(raw)
}

// ── Sheets ───────────────────────────────────────────────────────

/** H2's tabbed panel shape, adopted by the EPUB contents sheet:
 * Chapters | Bookmarks tabs + a footer with the current chapter
 * and Search. */
@Composable
private fun EpubContentsPanelSheet(
    viewModel: LocalDocReaderViewModel,
    session: ReadiumEpubSession,
    currentSection: Int?,
    initialTab: Int,
    onPicked: () -> Unit,
    onSearch: () -> Unit,
    // v1.5.10 (F5): the sort toggle rides the shared panel's
    // own contract, stored per reader in ReaderBarStore.
    sortOrder: ContentsSortOrder = ContentsSortOrder.DOCUMENT_ORDER,
    onSortOrderChange: (ContentsSortOrder) -> Unit = {}
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val chapterRows = epubChapterRows(session.outline, currentSection)
    val currentPos = chapterRows.indexOfFirst { it.isCurrent }
    TabbedContentsPanel(
        chapterRows = chapterRows,
        chaptersEmptyText = "This book carries no contents list.",
        bookmarkRows = bookmarks.map { bookmark ->
            val percent = LocatorJson.epubTotalProgression(bookmark.locatorJson)
            ContentsBookmarkRow(
                label = bookmark.label.ifBlank { "Bookmark" },
                detail = percent?.let { "${(it * 100).toInt()}% through the book" }
            )
        },
        bookmarksEmptyText = "No bookmarks yet — tap the bookmark button to mark this place.",
        onChapterClick = { row ->
            session.outline.firstOrNull {
                LocatorJson.targetIndex(it.targetJson) == row.targetIndex
            }?.let { entry ->
                onPicked()
                viewModel.goToOutline(entry)
            }
        },
        onBookmarkClick = { index ->
            bookmarks.getOrNull(index)?.let { bookmark ->
                onPicked()
                viewModel.goToBookmark(bookmark)
            }
        },
        onBookmarkDelete = { index ->
            bookmarks.getOrNull(index)?.let { viewModel.deleteBookmark(it) }
        },
        footerText = contentsFooterText(
            chapterTitle = chapterRows.getOrNull(currentPos)?.title,
            position = currentPos + 1,
            total = chapterRows.size
        ),
        sortOrder = sortOrder,
        onSortOrderChange = onSortOrderChange,
        initialTab = initialTab,
        footerActions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search in book")
            }
        }
    )
}

@Composable
private fun EpubSearchSheet(
    viewModel: LocalDocReaderViewModel,
    onHit: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val hits by viewModel.searchHits.collectAsStateWithLifecycle()
    val busy by viewModel.searchBusy.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search in this book") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { viewModel.search(query) },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Search") }
        }
        if (busy) {
            Row(Modifier.padding(16.dp)) {
                CircularProgressIndicator()
                Text("  Searching…")
            }
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(hits.orEmpty()) { hit ->
                TextButton(
                    onClick = {
                        onHit()
                        viewModel.goToSearchHit(hit)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(hit.title, style = MaterialTheme.typography.labelLarge)
                        Text(hit.snippet, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val currentHits = hits
        if (currentHits != null && currentHits.isEmpty() && !busy) {
            Text("No matches in this book.", Modifier.padding(16.dp))
        }
    }
}

// ── Book-mode settings sheet (G2 order: mode, theme, type) ──────

/**
 * v1.5.10 (G3): the stored form of a picked font's file name
 * — a plain name (no directories) with a font extension, or
 * null when the picked file is not a font file. The name is
 * what `EpubBookSettings.userFontFileName` stores and what
 * the session's fonts-dir lookup trusts.
 */
internal fun sanitiseUserFontFileName(displayName: String?): String? {
    val raw = displayName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?: return null
    val extension = raw.substringAfterLast('.', "").lowercase()
    if (extension !in setOf("ttf", "otf", "woff", "woff2")) return null
    val base = raw.substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_')
        .take(48)
    if (base.isBlank()) return null
    return "$base.$extension"
}

/**
 * v1.5.10 (G3): copy a picked font file into the app-private
 * fonts dir under its sanitised name; returns the stored
 * name, or null when the pick is not a usable font file.
 * Runs on IO (called inside a Dispatchers.IO block).
 */
internal fun copyPickedUserFont(
    context: android.content.Context,
    uri: android.net.Uri
): String? {
    val displayName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)
            } else {
                null
            }
        }
    }.getOrNull()
    val name = sanitiseUserFontFileName(displayName) ?: return null
    val dir = ReadiumEpubSession.fontsDir(context).apply { mkdirs() }
    val target = File(dir, name)
    val copied = runCatching {
        val input = context.contentResolver.openInputStream(uri)
            ?: return null
        input.use { stream ->
            target.outputStream().use { stream.copyTo(it) }
        }
        target.isFile && target.length() > 0
    }.getOrDefault(false)
    if (!copied) {
        runCatching { target.delete() }
        return null
    }
    return name
}

/**
 * v1.5.10 (G5): the EPUB share payload for a book's own file —
 * the registered document URI under a read grant, never a
 * copy (the same [BookShareSpec] shape the PDF door uses).
 * A book without a document URI offers NO share action —
 * never a dead button.
 */
internal fun epubBookShareSpec(
    book: app.xreader.data.db.Book
): BookShareSpec? {
    val uri = book.documentUri?.takeIf { it.isNotBlank() } ?: return null
    return BookShareSpec(
        uriString = uri,
        mimeType = "application/epub+zip",
        subject = book.title,
        text = listOf(book.title, book.author)
            .filter { it.isNotBlank() }
            .joinToString(" — ")
    )
}

/** Fires the share chooser for [spec]: the file streams from
 * its registered URI under the read grant; nothing is copied. */
internal fun launchEpubBookShare(
    context: android.content.Context,
    spec: BookShareSpec
) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
        .apply {
            type = spec.mimeType
            putExtra(
                android.content.Intent.EXTRA_STREAM,
                android.net.Uri.parse(spec.uriString)
            )
            putExtra(android.content.Intent.EXTRA_SUBJECT, spec.subject)
            putExtra(android.content.Intent.EXTRA_TEXT, spec.text)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(intent, "Share book")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EpubSettingsSheet(
    settings: EpubBookSettings,
    updateSettings: ((EpubBookSettings) -> EpubBookSettings) -> Unit,
    // v1.5.33 (R3): the auto-scroll start moved here from
    // the reader surface (owner order) — the same toggle
    // the shipped surface chip drove.
    autoScrollOn: Boolean = false,
    onToggleAutoScroll: () -> Unit = {},
    onPickUserFont: () -> Unit = {},
    onClearUserFont: () -> Unit = {},
    onCustomiseBar: () -> Unit = {},
    onControlOptions: () -> Unit = {}
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            // v1.5.9 (F-1): the sheet SCROLLS. The plain Column
            // it replaces stacked ≈1,600 dp of controls inside
            // the height-capped ModalBottomSheet — everything
            // from mid-Typography down was not composed into any
            // reachable place on the phone.
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Book settings", style = MaterialTheme.typography.titleMedium)
        Text(
            "For this book — how the flowing text looks and turns.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // v1.5.10 (F1/G2 doors): the reader-wide doors live at
        // the head of this sheet — visible, plain words, never
        // buried. The bar and the controls are global (one bar
        // for both readers), unlike this book's settings.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderStadiumChip(
                selected = false,
                onClick = onCustomiseBar,
                label = { Text("Customise reader bar") }
            )
            ReaderStadiumChip(
                selected = false,
                onClick = onControlOptions,
                label = { Text("Control options") }
            )
        }
        Spacer(Modifier.height(8.dp))

        for (section in epubSheetSections()) {
            when (section) {
                EpubSheetSection.MODE -> {
                    // G2: the reading mode is the sheet's FIRST
                    // control, in plain words.
                    Text("Read as", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderStadiumChip(
                            selected = settings.scroll,
                            onClick = {
                                updateSettings { it.copy(scroll = true) }
                            },
                            label = { Text("Scrolling") }
                        )
                        ReaderStadiumChip(
                            selected = !settings.scroll,
                            onClick = {
                                updateSettings { it.copy(scroll = false) }
                            },
                            label = { Text("Page by page") }
                        )
                    }
                    Text(
                        if (settings.scroll) {
                            "The book flows in one continuous column."
                        } else {
                            "Pages turn sideways, like a printed book."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // v1.5.33 (R3): the auto-scroll start lives
                    // here now, beside the mode it serves — the
                    // surface chip is gone (owner order). Its
                    // speed stays under Reading comforts.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Auto-scroll",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderStadiumChip(
                            selected = autoScrollOn,
                            onClick = onToggleAutoScroll,
                            primary = true,
                            label = {
                                Text(
                                    if (autoScrollOn) "Stop auto-scroll"
                                    else "Start auto-scroll"
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (autoScrollOn) Icons.Filled.Pause
                                    else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                    Text(
                        "The page drifts down on its own while you " +
                            "read. Its speed is under Reading " +
                            "comforts, below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                EpubSheetSection.THEME -> {
                    // v1.5.40: this section is "Text & colours",
                    // ordered like the novel reader's page of the
                    // same name — text size and paragraph spacing
                    // first (moved here from Typography), then the
                    // colours (swatches plus the colour wheel),
                    // and the named themes below them.
                    Text(
                        "Text & colours",
                        style = MaterialTheme.typography.labelLarge
                    )
                    EpubSliderRow(
                        label = "Text size",
                        display = "${(settings.fontSize * 100).toInt()}%",
                        value = settings.fontSize.toFloat(),
                        range = 0.5f..2.0f,
                        onChange = { v ->
                            updateSettings { it.copy(fontSize = v.toDouble()) }
                        },
                        dragDisplay = { v -> "${(v * 100).toInt()}%" }
                    )
                    EpubSliderRow(
                        label = "Space between paragraphs",
                        display = settings.paragraphSpacing?.let { "%.1f em".format(it) } ?: "Book's own",
                        value = (settings.paragraphSpacing ?: 0.5).toFloat(),
                        range = 0f..2f,
                        onChange = { v ->
                            updateSettings {
                                it.copy(paragraphSpacing = v.toDouble())
                            }
                        }
                    )

                    // v1.5.40: the shared colour wheel, one per
                    // colour. With no stored colour ("Theme") the
                    // wheel starts from the row's plain swatch —
                    // Black for words, White for the page.
                    var wheelFor by remember {
                        mutableStateOf<String?>(null)
                    }
                    Text("Text colour", style = MaterialTheme.typography.labelLarge)
                    EpubColourRow(
                        selected = settings.textColorArgb,
                        colours = listOf(
                            null to "Theme",
                            0xFF000000.toInt() to "Black",
                            0xFF1F2430.toInt() to "Ink",
                            0xFF5B4636.toInt() to "Warm brown",
                            0xFFF5EFE0.toInt() to "Cream",
                            0xFFFFFFFF.toInt() to "White"
                        ),
                        onPick = { argb ->
                            updateSettings { it.copy(textColorArgb = argb) }
                        }
                    )
                    // v1.5.41: the wheel's door is the shared
                    // full-width button, not the small chip
                    // that hid it on the v1.5.40 phone proof.
                    app.xreader.ui.common.PickExactColourButton(
                        onClick = { wheelFor = "text" }
                    )
                    Text("Page colour", style = MaterialTheme.typography.labelLarge)
                    EpubColourRow(
                        selected = settings.backgroundColorArgb,
                        colours = listOf(
                            null to "Theme",
                            0xFFFFFFFF.toInt() to "White",
                            0xFFF7F0DC.toInt() to "Cream paper",
                            0xFFEAD9B0.toInt() to "Sepia paper",
                            0xFF0B0B12.toInt() to "Midnight",
                            0xFF000000.toInt() to "Black"
                        ),
                        onPick = { argb ->
                            updateSettings {
                                it.copy(backgroundColorArgb = argb)
                            }
                        }
                    )
                    // v1.5.41: the second door, for the page
                    // colour — same button, same wheel.
                    app.xreader.ui.common.PickExactColourButton(
                        onClick = { wheelFor = "page" }
                    )
                    when (wheelFor) {
                        "text" -> app.xreader.ui.common
                            .ColourWheelPickerDialog(
                                title = "Text colour",
                                initialArgb =
                                    (settings.textColorArgb
                                        ?: 0xFF000000.toInt())
                                        .toLong() and 0xFFFFFFFFL,
                                onDismiss = { wheelFor = null },
                                onPick = { argb ->
                                    updateSettings {
                                        it.copy(textColorArgb = argb.toInt())
                                    }
                                    wheelFor = null
                                }
                            )
                        "page" -> app.xreader.ui.common
                            .ColourWheelPickerDialog(
                                title = "Page colour",
                                initialArgb =
                                    (settings.backgroundColorArgb
                                        ?: 0xFFFFFFFF.toInt())
                                        .toLong() and 0xFFFFFFFFL,
                                onDismiss = { wheelFor = null },
                                onPick = { argb ->
                                    updateSettings {
                                        it.copy(
                                            backgroundColorArgb = argb.toInt()
                                        )
                                    }
                                    wheelFor = null
                                }
                            )
                    }

                    // G7: named presets over the same keys the
                    // free swatches write.
                    Text("Theme", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EpubThemePreset.entries.forEach { preset ->
                            ReaderStadiumChip(
                                selected = settings.currentPreset() == preset,
                                onClick = {
                                    updateSettings {
                                        it.applyPreset(preset)
                                    }
                                },
                                label = {
                                    Text(
                                        when (preset) {
                                            EpubThemePreset.PAPER -> "Paper"
                                            EpubThemePreset.SEPIA -> "Sepia"
                                            EpubThemePreset.NIGHT -> "Night"
                                            EpubThemePreset.AMOLED -> "AMOLED"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }

                EpubSheetSection.TYPOGRAPHY -> {
                    Text("Font", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val fonts = listOf(
                            null to "Book's own",
                            "SERIF" to "Serif",
                            "SANS_SERIF" to "Sans serif",
                            "MONOSPACE" to "Monospace",
                            "OPEN_DYSLEXIC" to "Open Dyslexic",
                            "ACCESSIBLE_DFA" to "Accessible",
                            "IA_WRITER_DUOSPACE" to "Duospace"
                        )
                        fonts.forEach { (key, label) ->
                            ReaderStadiumChip(
                                selected = settings.userFontFileName == null &&
                                    settings.fontFamilyName == key,
                                onClick = {
                                    // Picking a bundled font
                                    // retires the user font for
                                    // this book (G3: one font
                                    // road per book).
                                    updateSettings {
                                        it.copy(
                                            fontFamilyName = key,
                                            userFontFileName = null
                                        )
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    // v1.5.10 (G3): a font from the owner's own
                    // files — FILES only (the toolkit cannot
                    // take Android Downloadable Fonts). Per
                    // book, like every setting in this sheet.
                    Text(
                        "Your font file",
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (settings.userFontFileName != null) {
                        Text(
                            "This book is using your file " +
                                settings.userFontFileName + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReaderStadiumChip(
                                selected = false,
                                onClick = onPickUserFont,
                                label = { Text("Choose a different file") }
                            )
                            ReaderStadiumChip(
                                selected = false,
                                onClick = onClearUserFont,
                                label = { Text("Use bundled fonts") }
                            )
                        }
                    } else {
                        Text(
                            "Read this book in a font file of " +
                                "your own (TTF, OTF, WOFF or " +
                                "WOFF2). The book reopens on the " +
                                "new font.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ReaderStadiumChip(
                            selected = false,
                            onClick = onPickUserFont,
                            label = { Text("Choose a font file") }
                        )
                    }

                    // v1.5.40: "Text size" moved up into
                    // "Text & colours" (the THEME section above).
                    EpubSliderRow(
                        label = "Text weight",
                        display = when {
                            settings.fontWeight == null -> "Book's own"
                            settings.fontWeight!! >= 1.25 -> "Bold"
                            settings.fontWeight!! <= 0.85 -> "Light"
                            else -> "Normal"
                        },
                        value = (settings.fontWeight ?: 1.0).toFloat(),
                        range = 0.7f..1.6f,
                        onChange = { v ->
                            updateSettings { it.copy(fontWeight = v.toDouble()) }
                        }
                    )
                    EpubSliderRow(
                        label = "Line height",
                        display = settings.lineHeight?.let { "%.1f".format(it) } ?: "Book's own",
                        value = (settings.lineHeight ?: 1.4).toFloat(),
                        range = 1.0f..2.0f,
                        onChange = { v ->
                            updateSettings { it.copy(lineHeight = v.toDouble()) }
                        }
                    )
                    EpubSliderRow(
                        label = "Letter spacing",
                        display = settings.letterSpacing?.let { "%.2f em".format(it) } ?: "Book's own",
                        value = (settings.letterSpacing ?: 0.0).toFloat(),
                        range = -0.05f..0.2f,
                        onChange = { v ->
                            updateSettings { it.copy(letterSpacing = v.toDouble()) }
                        }
                    )
                    EpubSliderRow(
                        label = "Word spacing",
                        display = settings.wordSpacing?.let { "%.2f em".format(it) } ?: "Book's own",
                        value = (settings.wordSpacing ?: 0.0).toFloat(),
                        range = 0f..0.5f,
                        onChange = { v ->
                            updateSettings { it.copy(wordSpacing = v.toDouble()) }
                        }
                    )
                    // v1.5.40: "Space between paragraphs" moved
                    // up into "Text & colours" as well.
                    EpubSliderRow(
                        label = "Paragraph indent",
                        display = settings.paragraphIndent?.let { "%.1f em".format(it) } ?: "None",
                        value = (settings.paragraphIndent ?: 0.0).toFloat(),
                        range = 0f..3f,
                        onChange = { v ->
                            updateSettings {
                                it.copy(paragraphIndent = v.toDouble())
                            }
                        }
                    )
                    EpubSliderRow(
                        label = "Page margins",
                        display = "%.1f×".format(settings.pageMargins),
                        value = settings.pageMargins.toFloat(),
                        range = 0.5f..2.0f,
                        onChange = { v ->
                            updateSettings { it.copy(pageMargins = v.toDouble()) }
                        }
                    )

                    // G6: justify + hyphenation.
                    EpubSwitchRow(
                        label = "Justified text",
                        subtitle = "Lines stretch to both margins, like print.",
                        checked = settings.justify,
                        onCheckedChange = { on ->
                            updateSettings { it.copy(justify = on) }
                        }
                    )
                    EpubSwitchRow(
                        label = "Hyphenate",
                        subtitle = "Break long words at line ends.",
                        checked = settings.hyphens,
                        onCheckedChange = { on ->
                            updateSettings { it.copy(hyphens = on) }
                        }
                    )
                }

                EpubSheetSection.PLATES -> {
                    if (settings.theme == EpubThemeChoice.DARK) {
                        Text(
                            "Illustrations in dark theme",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            "Colour plates are kept as printed unless you soften them here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            EpubPlateFilter.entries.forEach { filter ->
                                ReaderStadiumChip(
                                    selected = settings.plateFilter == filter,
                                    onClick = {
                                        updateSettings {
                                            it.copy(plateFilter = filter)
                                        }
                                    },
                                    label = {
                                        Text(
                                            when (filter) {
                                                EpubPlateFilter.ORIGINAL -> "As printed"
                                                EpubPlateFilter.DARKEN -> "Darken"
                                                EpubPlateFilter.INVERT -> "Invert"
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                EpubSheetSection.COMFORTS -> {
                    // G8: the shared comforts' settings.
                    Text("Reading comforts", style = MaterialTheme.typography.labelLarge)
                    EpubSwitchRow(
                        label = "Volume-key page turns",
                        subtitle = "Volume down = next, volume up = back.",
                        checked = settings.volumeKeyTurn,
                        onCheckedChange = { on ->
                            updateSettings { it.copy(volumeKeyTurn = on) }
                        }
                    )
                    EpubSwitchRow(
                        label = "Keep screen on",
                        subtitle = "The screen stays awake while you read.",
                        checked = settings.keepScreenOn,
                        onCheckedChange = { on ->
                            updateSettings { it.copy(keepScreenOn = on) }
                        }
                    )
                    Text("Orientation", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderOrientation.entries.forEach { orientation ->
                            ReaderStadiumChip(
                                selected = settings.orientation == orientation,
                                onClick = {
                                    updateSettings {
                                        it.copy(orientation = orientation)
                                    }
                                },
                                label = {
                                    Text(
                                        when (orientation) {
                                            ReaderOrientation.FREE -> "Free"
                                            ReaderOrientation.PORTRAIT -> "Portrait"
                                            ReaderOrientation.LANDSCAPE -> "Landscape"
                                        }
                                    )
                                }
                            )
                        }
                    }
                    EpubSliderRow(
                        label = "Auto-scroll speed",
                        display = "%.1f×".format(settings.autoScrollSpeed),
                        value = settings.autoScrollSpeed.toFloat(),
                        range = 0.25f..4f,
                        onChange = { v ->
                            updateSettings {
                                it.copy(autoScrollSpeed = v.toDouble())
                            }
                        },
                        dragDisplay = { v -> "%.1f×".format(v) }
                    )
                    Text(
                        "Brightness: swipe up or down along the left edge of the page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                EpubSheetSection.RESET -> {
                    TextButton(
                        onClick = { updateSettings { EpubBookSettings() } }
                    ) { Text("Reset this book's settings") }
                }
            }
        }
    }
}

@Composable
private fun EpubSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // v1.5.9 (F-8): the design's switch — violet track when
        // on (DESIGN.md Interactive Toggles), not stock M3.
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = ReaderViolet,
                checkedThumbColor = Color.White,
                checkedBorderColor = ReaderViolet,
                uncheckedTrackColor = Color(0xFF161822),
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

/**
 * v1.5.9 (F-4b): a settings slider row keeps a LOCAL drag
 * value and commits ONCE, on `onValueChangeFinished` — the
 * seek slider's existing pattern. A drag no longer submits a
 * preference rebuild + a (now debounced) save per tick; chips
 * and switches stay instant. [dragDisplay] formats the live
 * drag value for the readout while dragging; [display] is the
 * idle readout.
 */
@Composable
internal fun EpubSliderRow(
    label: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    dragDisplay: ((Float) -> String)? = null
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = (drag ?: value).coerceIn(range.start, range.endInclusive)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        Text(
            if (drag != null && dragDisplay != null) dragDisplay(shown) else display,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Slider(
        value = shown,
        onValueChange = { drag = it },
        onValueChangeFinished = {
            drag?.let { onChange(it) }
            drag = null
        },
        valueRange = range,
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpubColourRow(
    selected: Int?,
    colours: List<Pair<Int?, String>>,
    onPick: (Int?) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colours.forEach { (argb, name) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (argb == null) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color(argb)
                            }
                        )
                        .border(
                            width = if (selected == argb) 3.dp else 1.dp,
                            color = if (selected == argb) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape
                        )
                        .clickable { onPick(argb) }
                )
                Text(name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
