package app.xreader.ui.localreader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.engine.LocatorJson
import app.xreader.data.engine.OutlineEntry
import app.xreader.data.engine.PdfPageClip
import app.xreader.data.engine.PdfPageFilter
import app.xreader.data.engine.PdfProbeState
import app.xreader.data.engine.PdfRenderSlot
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import app.xreader.data.engine.PdfSession
import app.xreader.data.engine.PdfStripState
import app.xreader.data.prefs.settingsDataStore
import app.xreader.ui.theme.FloatingChromeLift
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v1.5.7 (Scope C-PDF): the PDF DOCUMENT reader — the file's own
 * fixed pages, rendered by the platform PdfRenderer through
 * [PdfSession]. Deliberately NOT the EPUB reader:
 *
 *  - No text controls anywhere (no font, size, spacing, text
 *    themes): a PDF's appearance is fixed by the file. This
 *    reader's only display setting is a page-render FILTER
 *    (invert / black-and-white), labelled as a filter.
 *  - Progress is a real page slider — "Page N of Total" — with
 *    go-to-page; printed page labels from the document ride
 *    alongside the file page, never instead of it.
 *  - SCROLL MODEL (chosen): PAGED by default — one true page at
 *    a time, swipe to turn, pinch zoom with REMEMBER-ZOOM across
 *    page turns (persisted per book). A continuous vertical
 *    scroll model is offered in the display sheet for long
 *    documents; the page slider and go-to-page drive BOTH models
 *    from the same page index, which is what keeps zoom + slider
 *    coherent. Pinch zoom itself lives in the paged model (a
 *    per-page transform); continuous mode is fit-width.
 *    v1.5.28: the continuous model gains Komikku's webtoon
 *    surface zoom (a transform of the whole list — pinch +
 *    double-tap + pan-while-zoomed), and the paged model's
 *    zoom is rebuilt on the same donor's rules
 *    (build-logs/V1528-ZOOM-MAP.md).
 *  - Tap toggles the chrome (Finding 1 §(d) step 4): centre tap
 *    via the watch-only [pdfTapObserver]; in paged mode the edge
 *    thirds also turn pages while unzoomed.
 *  - Contents come from the PDF document outline, with an honest
 *    empty state when the document carries none.
 *
 * v1.5.8 (F2/F4 + H1–H10, Moon+ template): the chrome speaks the
 * glass language and pads itself (F2); the book opens WITHOUT
 * the strip tax (F4 — the strip lands in the background and
 * persists). Fit-page/fit-width (H1), the tabbed contents panel
 * (H2), one-tap night + six named presets (H3), margin crop
 * from the persisted bounds (H4), the "A"-badge Text view (H5),
 * strip speech (H6), G9's auto-scroll (H7), the permanent
 * status line (H8), landscape spread (H9).
 */

private enum class PdfSheet {
    CONTENTS, BOOKMARKS, SEARCH, DISPLAY,
    // v1.5.10: F1 bar customise, G2 control options, F2 book info.
    BAR, CONTROL, BOOK_INFO
}

/** The PDF base render scale: 1.0× fit — A-2's two-pass
 * model (preview + refine) rides on this base. */
const val PDF_BASE_SCALE = 1.0f

/** v1.5.28: the paged zoom layer's base slot is rendered at
 * this multiple of the fit width (pixel-capped at the
 * session's refine budget — see [pdfCellSlotWidthPx]), so the
 * layer transforms ONE texture that stays crisp through the
 * common zoom range. */
const val PDF_ZOOM_SUPERSAMPLE = 2

/** v1.5.28: the double-tap glide's duration (Komikku's zoom
 * duration class: SSIV's configured duration, the webtoon's
 * 200 ms AnimatorSet). */
const val PDF_DOUBLE_TAP_ANIM_MS = 250

/** v1.5.28: the settle refine's clip is requested grown by
 * this page-fraction per side (clamped to the page), so the
 * refined slot image over-covers the visible region and no
 * hairline seam can open at its edges. */
const val PDF_REFINE_CLIP_MARGIN = 0.02f

/** The PDF chrome's pre-existing handlers, gathered once.
 * Every lambda is the SAME handler the top bar invoked —
 * U-2 is placement, not new machinery. */
class PdfChromeHandlers(
    val onBookmarkHere: () -> Unit,
    val onBookmarks: () -> Unit,
    val onTextView: () -> Unit,
    val onDisplay: () -> Unit
)

/** The PDF bottom quick row (U-2): Bookmark · Bookmarks ·
 * Text view · Page display, bound through this model so each
 * tile fires its shipped handler. */
fun pdfQuickActions(handlers: PdfChromeHandlers): List<QuickAction> = listOf(
    QuickAction("bookmarkHere", "Bookmark", handlers.onBookmarkHere),
    QuickAction("bookmarks", "Bookmarks", handlers.onBookmarks),
    QuickAction("textView", "Text view", handlers.onTextView),
    QuickAction("display", "Page display", handlers.onDisplay)
)

// ── v1.5.10 (G5): Share ──────────────────────────────────────────

/** The share payload for a book's own file: the registered
 * document URI (never a copy), a read-permission grant, and
 * the share-details text. Pure — the gate test pins it. */
data class BookShareSpec(
    val uriString: String,
    val mimeType: String,
    val subject: String,
    val text: String
)

/** Share exists only for a book whose file is registered by
 * document URI (the in-place import model). A book without one
 * offers NO share action — never a dead button (plan G5). */
fun bookShareAvailable(documentUri: String?): Boolean =
    !documentUri.isNullOrBlank()

/** Fires the share chooser for [spec] — the file streams from
 * its registered URI under a read grant; nothing is copied. */
internal fun launchBookShare(
    context: android.content.Context,
    spec: BookShareSpec
) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_SEND
    ).apply {
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
        )
    }
}

fun buildBookShareSpec(book: app.xreader.data.db.Book): BookShareSpec? {
    val uri = book.documentUri?.takeIf { it.isNotBlank() } ?: return null
    val mime = when (book.localFormat) {
        app.xreader.data.db.BookFormat.PDF -> "application/pdf"
        app.xreader.data.db.BookFormat.EPUB -> "application/epub+zip"
        else -> "*/*"
    }
    val details = buildString {
        append(book.title)
        if (book.author.isNotBlank()) append(" — ").append(book.author)
    }
    return BookShareSpec(
        uriString = uri,
        mimeType = mime,
        subject = book.title,
        text = details
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfDocumentReader(
    viewModel: LocalDocReaderViewModel,
    ready: LocalReaderState.Ready,
    session: PdfSession,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    // v1.5.10 (F2 door): forwarded verbatim to the Book
    // information sheet's onOpenBookPage door; null (the
    // default, when the host supplies none) keeps the
    // "Book page" row absent.
    onOpenBookPage: (() -> Unit)? = null
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<PdfSheet?>(null) }
    var goToOpen by remember { mutableStateOf(false) }
    var textViewOpen by remember { mutableStateOf(false) }
    val settings by viewModel.pdfSettings.collectAsStateWithLifecycle()
    val progress by viewModel.pdfProgress.collectAsStateWithLifecycle()
    val tts by viewModel.ttsState.collectAsStateWithLifecycle()
    val stripState by session.stripState.collectAsStateWithLifecycle()
    // v1.5.27: subscribing to the deferred probe's landing
    // recomposes this body's outline / printed-label readers
    // (book-info chapter, status label) when it settles; the
    // contents sheet and the go-to-page dialog collect it
    // themselves, being separate compositions.
    val probeState by session.probeState.collectAsStateWithLifecycle()
    val currentPage = progress?.page ?: ready.initialIndex
    val pageCount = session.sectionCount
    val autoScroll = remember { AutoScrollController() }
    var autoScrollOn by remember { mutableStateOf(false) }

    // ── v1.5.10 (F1/F2/F3/F5/F6/G2/G5): the reader-depth stores
    // over the shared "settings" DataStore (the same store
    // LocalReaderPrefs and BookMetaPrefs are built over).
    val context = androidx.compose.ui.platform.LocalContext.current
    val readerScope = rememberCoroutineScope()
    val barStore = remember(context) {
        ReaderBarStore(context.settingsDataStore)
    }
    val statsStore = remember(context) {
        BookStatsStore(context.settingsDataStore)
    }
    val highlightStore = remember(context) {
        SelectionHighlightStore(context.settingsDataStore)
    }
    val barCustomized by barStore.customized
        .collectAsStateWithLifecycle(initialValue = false)
    val barConfig by barStore.config
        .collectAsStateWithLifecycle(initialValue = null)
    val sortOrder by barStore.contentsSort(ReaderFormat.PDF)
        .collectAsStateWithLifecycle(initialValue = ContentsSortOrder.DOCUMENT_ORDER)
    val tapPreset by barStore.tapPreset(ReaderFormat.PDF)
        .collectAsStateWithLifecycle(initialValue = TapLayoutPreset.MOON_DEFAULT)
    val tiltEnabled by barStore.tiltEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val smartScrollLock by barStore.smartScrollLock
        .collectAsStateWithLifecycle(initialValue = true)
    val bookStats by statsStore.stats(ready.book.id)
        .collectAsStateWithLifecycle(initialValue = BookStats())
    val textHighlights by highlightStore.highlights(ready.book.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // F2 favourite: the production truth is BookMetaPrefs over
    // the same store, keyed by natural key. Local books carry
    // no extension, so their key is derivable here exactly as
    // AppContainer's resolver derives it.
    val bookMetaPrefs = remember(context) {
        app.xreader.data.library.BookMetaPrefs(context.settingsDataStore)
    }
    val favouriteKey = remember(ready.book) {
        if (ready.book.extensionId == null) {
            app.xreader.data.sync.SyncKeys.bookKey(ready.book, null)
        } else {
            null
        }
    }
    val favouriteKeys by bookMetaPrefs.favouritesByKey
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val isFavourite = favouriteKey != null && favouriteKey in favouriteKeys
    var brightnessOpen by remember { mutableStateOf(false) }
    var brightness by remember {
        mutableStateOf(
            (context as? android.app.Activity)?.window?.attributes
                ?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
        )
    }
    val currentPageState = rememberUpdatedState(currentPage)
    // F6: tilt turns through the reader's own page advance.
    ReaderTiltTurnEffect(enabled = tiltEnabled) { delta ->
        viewModel.goToPdfPage(currentPageState.value + delta)
    }
    // F2 stats: this session's slice — elapsed time + visited
    // pages × the strip's average words per page, recorded on
    // dispose (session slices are the accumulation's unit).
    val visitedPages = remember { mutableSetOf<Int>() }
    LaunchedEffect(currentPage) { visitedPages += currentPage }
    val openedAtMs = remember { System.currentTimeMillis() }
    val stripStateForStats = rememberUpdatedState(stripState)
    val statsBookId = ready.book.id
    DisposableEffect(session) {
        onDispose {
            val elapsed = System.currentTimeMillis() - openedAtMs
            val result = (stripStateForStats.value as? PdfStripState.Ready)?.result
            val wordsPerPage = if (result != null && result.texts.isNotEmpty()) {
                result.texts.sumOf { countWords(it) } / result.texts.size
            } else {
                0L
            }
            val wordsAdvanced = visitedPages.size.toLong() * wordsPerPage
            readerScope.launch {
                statsStore.recordSession(
                    bookId = statsBookId,
                    dateIso = readingDayIso(System.currentTimeMillis()),
                    deltaMillis = elapsed,
                    wordsAdvanced = wordsAdvanced,
                    endBookPercent = if (pageCount > 0) {
                        (currentPageState.value + 1) * 100f / pageCount
                    } else {
                        0f
                    }
                )
                // F2 counts cache: the strip's totals, once.
                if (result != null) {
                    statsStore.saveTextCounts(
                        statsBookId,
                        TextCounts(
                            chars = result.texts.sumOf { it.length }.toLong(),
                            words = result.texts.sumOf { countWords(it) },
                            complete = true
                        )
                    )
                }
            }
        }
    }

    // ── v1.5.11 (step 7, D7): per-page Text-view texts ──────
    // While the Text view is open and the whole-book strip is
    // still pending, the current page ± window is extracted
    // page-by-page through the session (never a whole-strip
    // wait), then the extraction expands outward ring by ring
    // so the whole view fills in progressively. A landed
    // strip supersedes this map wholesale (pdfTextViewState).
    var textPageTexts by remember(session) {
        mutableStateOf<Map<Int, String>>(emptyMap())
    }
    LaunchedEffect(textViewOpen, session) {
        if (!textViewOpen) return@LaunchedEffect
        if (stripStateForStats.value is PdfStripState.Ready) return@LaunchedEffect
        val anchor = currentPageState.value.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        suspend fun extract(page: Int) {
            if (page !in 0 until pageCount) return
            if (textPageTexts.containsKey(page)) return
            val text = runCatching { session.pageText(page) }.getOrNull() ?: return
            textPageTexts = textPageTexts + (page to text)
        }
        // The visible window first (anchor ± 3), then outward.
        for (page in (anchor - 3)..(anchor + 3)) extract(page)
        var distance = 4
        while (true) {
            val before = textPageTexts.size
            extract(anchor - distance)
            extract(anchor + distance)
            if (anchor - distance < 0 && anchor + distance >= pageCount) break
            if (textPageTexts.size == before && distance > pageCount) break
            distance++
        }
    }

    // v1.5.30: FLOATING CHROME — the reader root is a plain
    // full-size Box; the top and bottom bars are OVERLAYS drawn
    // above the body, never layout siblings of it. The body
    // takes the full window at all times, so showing or hiding
    // the chrome changes the page's geometry by ZERO pixels
    // (the v1.5.29 Scaffold fed the bars' heights into the
    // body's padding — the owner's proof video showed the page
    // rescale on every chrome toggle). Chrome-hidden geometry
    // is exactly v1.5.29's; chrome-shown geometry now matches
    // it instead of squeezing the page.
    Box(Modifier.fillMaxSize()) {
        LocalReaderComfortHost(
            volumeKeyTurn = settings.volumeKeyTurn,
            keepScreenOn = settings.keepScreenOn,
            orientation = settings.orientation,
            onVolumeTurn = { direction -> viewModel.onVolumeKeyTurn(direction) },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                when (settings.scrollModel) {
                    PdfScrollModel.PAGED -> PdfPagedBody(
                        viewModel = viewModel,
                        session = session,
                        initialPage = ready.initialIndex,
                        currentPage = currentPage,
                        settings = settings,
                        autoScroll = autoScroll,
                        tapPreset = tapPreset,
                        onToggleControls = { controlsVisible = !controlsVisible }
                    )
                    PdfScrollModel.CONTINUOUS -> PdfContinuousBody(
                        viewModel = viewModel,
                        session = session,
                        initialPage = ready.initialIndex,
                        settings = settings,
                        autoScroll = autoScroll,
                        tapPreset = tapPreset,
                        smartScrollLock = smartScrollLock,
                        onToggleControls = { controlsVisible = !controlsVisible }
                    )
                }
                // H5: the Text view overlays the pages.
                // v1.5.10 (F3): with the shared selection
                // actions wired (page-anchored highlights in
                // the selection store + the offline dictionary).
                if (textViewOpen) {
                    PdfTextView(
                        // v1.5.11 (step 7, D7): per-page state —
                        // extracted pages show while the strip
                        // is still pending.
                        state = pdfTextViewState(stripState, textPageTexts, pageCount),
                        initialPage = currentPage,
                        onClose = { page ->
                            textViewOpen = false
                            viewModel.goToPdfPage(page)
                        },
                        highlights = textHighlights,
                        onAddHighlight = { highlight ->
                            readerScope.launch {
                                highlightStore.add(ready.book.id, highlight)
                            }
                        },
                        onRemoveHighlight = { highlight ->
                            readerScope.launch {
                                highlightStore.remove(ready.book.id, highlight.id)
                            }
                        },
                        dictionaryLookup = { word ->
                            app.xreader.ui.library.DictionaryLookup.lookup(
                                context.applicationContext.filesDir, word
                            )
                        }
                    )
                }
            }
        }
        // Top chrome overlay: the shipped slim glass bar,
        // unchanged but for living above the body now.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            PdfTopBar(
                title = ready.book.title,
                onClose = onClose,
                onContents = { sheet = PdfSheet.CONTENTS },
                onSearch = { sheet = PdfSheet.SEARCH },
                onReadAloud = { viewModel.toggleReadAloud() },
                ttsPlaying = tts.playing
            )
        }
        // Bottom overlay: the snackbar (the Scaffold's host,
        // same state) rides directly above the floating
        // bottom bar — the arrangement the Scaffold produced,
        // without its layout cost.
        // v1.5.35 (item 4 — the uniform status rule, owner
        // order from the EPUB river): NO permanent line over
        // the page. The H8 status pill that used to sit here
        // OUTSIDE the chrome gate — visible at rest, carrying
        // its own clearance while the bar hid — is GONE; its
        // facts (chapter · page · battery · clock) now render
        // as the first row INSIDE the bottom capsule
        // (PdfBottomChrome), present only while the bar is
        // open. When the bar closes, nothing sits over the
        // page at all.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SnackbarHost(snackbar)
            AnimatedVisibility(
                visible = controlsVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                PdfBottomChrome(
                    session = session,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    settings = settings,
                    autoScrollOn = autoScrollOn,
                    // v1.5.10 (F1): once any customisation
                    // exists, the shared bar config governs
                    // this row; until then the shipped row
                    // below renders untouched.
                    barActions = if (barCustomized) {
                        resolveReaderBar(true, barConfig, ReaderFormat.PDF)
                    } else {
                        null
                    },
                    barDoubleLine = barConfig?.doubleLineLayout ?: false,
                    barActive = if (tiltEnabled) {
                        setOf(ReaderBarActionId.TILT)
                    } else {
                        emptySet()
                    },
                    barHandlers = mapOf(
                        ReaderBarActionId.ORIENTATION to {
                            viewModel.updatePdfSettings {
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
                        ReaderBarActionId.DAY_NIGHT to {
                            viewModel.updatePdfSettings {
                                it.withFilter(it.filter.toggledNight())
                            }
                        },
                        ReaderBarActionId.SPEAK to { viewModel.toggleReadAloud() },
                        ReaderBarActionId.AUTOSCROLL to {
                            if (autoScroll.engine.running) {
                                autoScroll.stop()
                            } else {
                                autoScroll.start()
                            }
                            autoScrollOn = autoScroll.engine.running
                        },
                        ReaderBarActionId.CHAPTERS to { sheet = PdfSheet.CONTENTS },
                        ReaderBarActionId.BOOKMARKS to { sheet = PdfSheet.BOOKMARKS },
                        ReaderBarActionId.BRIGHTNESS to {
                            brightnessOpen = !brightnessOpen
                        },
                        ReaderBarActionId.SEARCH to { sheet = PdfSheet.SEARCH },
                        ReaderBarActionId.TILT to {
                            readerScope.launch {
                                barStore.saveTiltEnabled(!tiltEnabled)
                            }
                        },
                        ReaderBarActionId.VISUAL_OPTIONS to {
                            sheet = PdfSheet.DISPLAY
                        },
                        ReaderBarActionId.CONTROL_OPTIONS to {
                            sheet = PdfSheet.CONTROL
                        },
                        ReaderBarActionId.BOOKMARK_ADD to {
                            viewModel.bookmarkHere()
                        },
                        ReaderBarActionId.TEXT_VIEW to { textViewOpen = true }
                    ),
                    brightnessOpen = brightnessOpen,
                    brightness = brightness,
                    onBrightnessChange = { value ->
                        brightness = value
                        (context as? android.app.Activity)?.let { activity ->
                            val params = activity.window.attributes
                            params.screenBrightness = value
                            activity.window.attributes = params
                        }
                    },
                    // U-2: the moved top-bar actions keep their
                    // v1.5.8 handlers, now bound through the
                    // quick-action model.
                    handlers = PdfChromeHandlers(
                        onBookmarkHere = { viewModel.bookmarkHere() },
                        onBookmarks = { sheet = PdfSheet.BOOKMARKS },
                        onTextView = { textViewOpen = true },
                        onDisplay = { sheet = PdfSheet.DISPLAY }
                    ),
                    onToggleNight = {
                        viewModel.updatePdfSettings {
                            it.withFilter(it.filter.toggledNight())
                        }
                    },
                    onToggleAutoScroll = {
                        if (autoScroll.engine.running) {
                            autoScroll.stop()
                        } else {
                            autoScroll.start()
                        }
                        autoScrollOn = autoScroll.engine.running
                    },
                    onSeek = { page -> viewModel.goToPdfPage(page) },
                    onGoToPage = { goToOpen = true }
                )
            }
        }
    }

    if (goToOpen) {
        PdfGoToPageDialog(
            session = session,
            currentPage = currentPage,
            pageCount = pageCount,
            onDismiss = { goToOpen = false },
            onPick = { page ->
                goToOpen = false
                viewModel.goToPdfPage(page)
            }
        )
    }

    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = Color(0xFF0C0D12)
        ) {
            when (sheet) {
                PdfSheet.CONTENTS -> PdfContentsPanelSheet(
                    viewModel = viewModel,
                    session = session,
                    currentPage = currentPage,
                    initialTab = 0,
                    sortOrder = sortOrder,
                    onSortOrderChange = { order ->
                        readerScope.launch {
                            barStore.saveContentsSort(ReaderFormat.PDF, order)
                        }
                    },
                    onPicked = { sheet = null },
                    onSearch = { sheet = PdfSheet.SEARCH },
                    onDisplay = { sheet = PdfSheet.DISPLAY }
                )
                PdfSheet.BOOKMARKS -> PdfContentsPanelSheet(
                    viewModel = viewModel,
                    session = session,
                    currentPage = currentPage,
                    initialTab = 1,
                    sortOrder = sortOrder,
                    onSortOrderChange = { order ->
                        readerScope.launch {
                            barStore.saveContentsSort(ReaderFormat.PDF, order)
                        }
                    },
                    onPicked = { sheet = null },
                    onSearch = { sheet = PdfSheet.SEARCH },
                    onDisplay = { sheet = PdfSheet.DISPLAY }
                )
                PdfSheet.SEARCH -> PdfSearchSheet(
                    viewModel = viewModel,
                    onHit = { sheet = null }
                )
                PdfSheet.DISPLAY -> PdfDisplaySheet(
                    viewModel = viewModel,
                    settings = settings,
                    onBookInfo = { sheet = PdfSheet.BOOK_INFO },
                    onCustomiseBar = { sheet = PdfSheet.BAR },
                    onControlOptions = { sheet = PdfSheet.CONTROL },
                    onShare = buildBookShareSpec(ready.book)?.let { spec ->
                        { launchBookShare(context, spec) }
                    }
                )
                PdfSheet.BAR -> CustomisableReaderBarSheetContent(
                    config = barConfig
                        ?: ReaderBarConfig.seededFrom(ReaderFormat.PDF),
                    onConfigChange = { next ->
                        readerScope.launch { barStore.saveConfig(next) }
                    }
                )
                PdfSheet.CONTROL -> ControlOptionsSheetContent(
                    state = ControlOptionsState(
                        tapPreset = tapPreset,
                        tiltEnabled = tiltEnabled,
                        smartScrollLock = smartScrollLock,
                        volumeKeyTurn = settings.volumeKeyTurn,
                        keepScreenOn = settings.keepScreenOn
                    ),
                    onTapPreset = { preset ->
                        readerScope.launch {
                            barStore.saveTapPreset(ReaderFormat.PDF, preset)
                        }
                    },
                    onTiltEnabled = { enabled ->
                        readerScope.launch { barStore.saveTiltEnabled(enabled) }
                    },
                    onSmartScrollLock = { enabled ->
                        readerScope.launch {
                            barStore.saveSmartScrollLock(enabled)
                        }
                    },
                    onVolumeKeyTurn = { on ->
                        viewModel.updatePdfSettings { it.copy(volumeKeyTurn = on) }
                    },
                    onKeepScreenOn = { on ->
                        viewModel.updatePdfSettings { it.copy(keepScreenOn = on) }
                    }
                )
                PdfSheet.BOOK_INFO -> {
                    val stripResult =
                        (stripState as? PdfStripState.Ready)?.result
                    val liveCounts = stripResult?.let { result ->
                        TextCounts(
                            chars = result.texts.sumOf { it.length }.toLong(),
                            words = result.texts.sumOf { countWords(it) },
                            complete = true
                        )
                    }
                    val chapterIndex = session.outline.indexOfLast {
                        (LocatorJson.targetIndex(it.targetJson)
                            ?: Int.MAX_VALUE) <= currentPage
                    }
                    BookInfoSheetContent(
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
                                fileSizeBytes = ready.book.filePath?.let {
                                    runCatching { java.io.File(it).length() }
                                        .getOrNull()?.takeIf { len -> len > 0 }
                                } ?: ready.book.fileFingerprint
                                    ?.split('|')?.getOrNull(1)?.toLongOrNull(),
                                format = ReaderFormat.PDF,
                                totalPages = pageCount,
                                currentPageOneBased = currentPage + 1,
                                currentChapterOneBased =
                                    (chapterIndex + 1).takeIf { chapterIndex >= 0 },
                                currentChapterTitle =
                                    session.anchorTitleFor(currentPage),
                                liveCounts = liveCounts,
                                bookProgression = if (pageCount > 0) {
                                    (currentPage + 1f) / pageCount
                                } else {
                                    0f
                                }
                            ),
                            stats = bookStats
                        ),
                        isFavourite = isFavourite,
                        onToggleFavourite = {
                            val key = favouriteKey
                            if (key != null) {
                                readerScope.launch {
                                    bookMetaPrefs.setFavouriteByKey(
                                        key, !isFavourite
                                    )
                                }
                            }
                        },
                        onStarRatingChanged = { rating ->
                            readerScope.launch {
                                statsStore.saveStarRating(ready.book.id, rating)
                            }
                        },
                        // F2 door, live: the host's book-page
                        // callback for THIS book (the existing
                        // "book/<id>" route, wired by the shell
                        // through LocalDocReaderScreen). Null only
                        // when the host supplies none — the row
                        // is then absent, honestly.
                        onOpenBookPage = onOpenBookPage,
                        onShare = buildBookShareSpec(ready.book)?.let { spec ->
                            { launchBookShare(context, spec) }
                        }
                    )
                }
                null -> Unit
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The PDF top bar in the glass language (F2 self-padding),
 * de-crowded by v1.5.9 (U-2): Back · title · Contents · Search ·
 * Read aloud. Bookmark here / Bookmarks / Text view / Page
 * display moved to the bottom quick row ([pdfQuickActions]) so
 * the title pill always has room to show the book's name. */
@Composable
private fun PdfTopBar(
    title: String,
    onClose: () -> Unit,
    onContents: () -> Unit,
    onSearch: () -> Unit,
    onReadAloud: () -> Unit,
    ttsPlaying: Boolean
) {
    Row(
        Modifier.fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GlassChipButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        // U-2: the title pill carries a guaranteed minimum
        // width — the book's name is always visible.
        GlassTitlePill(
            title = title,
            subtitle = null,
            modifier = Modifier.weight(1f).defaultMinSize(minWidth = 96.dp)
        )
        GlassChipButton(onClick = onContents) {
            Icon(Icons.Filled.List, contentDescription = "Contents")
        }
        GlassChipButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search in document")
        }
        GlassChipButton(onClick = onReadAloud) {
            Icon(
                if (ttsPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (ttsPlaying) "Stop reading aloud" else "Read aloud"
            )
        }
    }
}

/**
 * The PDF bottom chrome — v1.5.30: ONE floating glass bar in
 * Moon+'s pattern. A single capsule holds the seek row (page
 * indicator + slider on one line; the indicator IS the Go to
 * page button), the two labelled toggles (Night · Auto-scroll,
 * the shipped stadium chips) and ONE primary action row (the
 * shipped labelled tiles, or the customised bar's row). The
 * brightness popover still opens as its own capsule above the
 * bar when a customised bar carries the brightness action.
 * The bar floats as an overlay (see the reader root): it
 * takes no layout space from the page. Bottom clearance is
 * principled spacing — the system navigation-bar inset plus
 * [FloatingChromeLift], a designed margin — so it reads as
 * ordinary floating-bar spacing on any phone.
 *
 * v1.5.35 (item 4 — the parked aesthetics pass): the capsule
 * keeps v1.5.30's geometry rules EXACTLY (overlay-only, zero
 * page-geometry change, same clearance) and gains the
 * Nocturne Violet finish: the status facts (chapter · page
 * · battery · clock) as a quiet first row INSIDE the
 * capsule — the always-on pill over the page is gone, per
 * the uniform status rule — a designed seek plate instead
 * of the stock TextButton page indicator, and the slider
 * in the one accent (violet track + thumb). SLOP-CHECK run
 * recorded in V1535-BUILD-REPORT. */
@Composable
private fun PdfBottomChrome(
    session: PdfSession,
    currentPage: Int,
    pageCount: Int,
    settings: PdfDocSettings,
    autoScrollOn: Boolean,
    handlers: PdfChromeHandlers,
    onToggleNight: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onSeek: (Int) -> Unit,
    onGoToPage: () -> Unit,
    // v1.5.10 (F1): the customised bar. [barActions] null =
    // untouched store → the shipped U-2 row renders as before.
    barActions: List<ReaderBarActionId>? = null,
    barDoubleLine: Boolean = false,
    barActive: Set<ReaderBarActionId> = emptySet(),
    barHandlers: Map<ReaderBarActionId, () -> Unit> = emptyMap(),
    brightnessOpen: Boolean = false,
    brightness: Float = 0.5f,
    onBrightnessChange: (Float) -> Unit = {}
) {
    // The seek slider's scrub state — PdfPageBar's, moved here
    // unchanged with the row it drives (v1.5.30 folded the page
    // bar into this one capsule; the standalone PdfPageBar is
    // gone).
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shownPage = scrub?.toInt()?.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        ?: currentPage
    Column(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = FloatingChromeLift),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // v1.5.10 (F1): the brightness action in a customised
        // bar opens its slider popover above the bar.
        if (barActions != null && brightnessOpen) {
            GlassCapsule(Modifier.fillMaxWidth()) {
                BrightnessSliderRow(
                    value = brightness,
                    onValueChange = onBrightnessChange
                )
            }
        }
        GlassCapsule(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // v1.5.35 (item 4): the status facts, INSIDE
                // the open bar — chapter · page · battery ·
                // clock, one quiet centred line. Nothing
                // renders over the page when the bar closes.
                LiveReaderStatusLine(bare = true) { battery, clock ->
                    pdfStatusLineContent(
                        session.anchorTitleFor(currentPage),
                        currentPage,
                        pageCount,
                        battery,
                        clock
                    )
                }
                // Seek row: page indicator and slider on ONE
                // line. The indicator is a button — tapping it
                // opens Go to page (the same handler the old
                // bar's separate "Go to page" button fired);
                // while scrubbing, it tracks the scrub page.
                // v1.5.35: the indicator is a designed seek
                // PLATE (tier surface, pill radius) instead of
                // the stock TextButton — same tap, same text.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF161822))
                            .clickable(onClick = onGoToPage)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            session.displayLabelFor(shownPage),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                        Text(
                            "of $pageCount pages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (pageCount > 1) {
                        Slider(
                            value = shownPage.toFloat(),
                            onValueChange = { scrub = it },
                            onValueChangeFinished = {
                                scrub?.let { onSeek(it.toInt()) }
                                scrub = null
                            },
                            valueRange = 0f..(pageCount - 1).toFloat(),
                            steps = (pageCount - 2).coerceAtLeast(0),
                            modifier = Modifier.weight(1f),
                            // v1.5.35: the one accent — violet
                            // thumb + track, the inactive track
                            // the same violet at low alpha (no
                            // second colour enters the bar).
                            colors = SliderDefaults.colors(
                                thumbColor = ReaderViolet,
                                activeTrackColor = ReaderViolet,
                                inactiveTrackColor =
                                    ReaderViolet.copy(alpha = 0.25f)
                            )
                        )
                    }
                }
                // Toggle row: the shipped stadium chips (U-3,
                // shared with the EPUB reader) — a toggle that
                // is ON looks on.
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReaderStadiumChip(
                        selected = settings.filter ==
                            PdfPageFilter.INVERT_BLACK_AND_WHITE,
                        onClick = onToggleNight,
                        text = "Night",
                        leadingIcon = {
                            Icon(
                                if (settings.filter ==
                                    PdfPageFilter.INVERT_BLACK_AND_WHITE
                                ) {
                                    Icons.Filled.LightMode
                                } else {
                                    Icons.Filled.DarkMode
                                },
                                contentDescription = null,
                                modifier = Modifier.width(16.dp)
                            )
                        }
                    )
                    ReaderStadiumChip(
                        selected = autoScrollOn,
                        onClick = onToggleAutoScroll,
                        text = "Auto-scroll",
                        primary = true,
                        leadingIcon = {
                            Icon(
                                if (autoScrollOn) {
                                    Icons.Filled.Pause
                                } else {
                                    Icons.Filled.PlayArrow
                                },
                                contentDescription = null,
                                modifier = Modifier.width(16.dp)
                            )
                        }
                    )
                }
                // The ONE primary action row. v1.5.10 (F1): a
                // customised bar renders the shared config's
                // row; otherwise the shipped U-2 tiles — icon +
                // plain-word label, bound through the
                // [pdfQuickActions] model so each tile fires
                // its v1.5.8 handler.
                if (barActions != null) {
                    ReaderBarRow(
                        actions = barActions,
                        handlers = barHandlers,
                        doubleLineLayout = barDoubleLine,
                        activeActions = barActive
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Top
                    ) {
                        pdfQuickActions(handlers).forEach { action ->
                            Column(
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {
                                GlassChipButton(onClick = action.onClick) {
                                    PdfQuickActionIcon(actionId = action.id)
                                }
                                Text(
                                    action.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme
                                        .colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The quick-row tile icons, by [QuickAction.id]. */
@Composable
private fun PdfQuickActionIcon(actionId: String) {
    when (actionId) {
        "bookmarkHere" -> Icon(
            Icons.Filled.BookmarkAdd, contentDescription = "Bookmark"
        )
        "bookmarks" -> Icon(Icons.Filled.Bookmark, contentDescription = "Bookmarks")
        // H5's "A" badge — the document as flowing text.
        "textView" -> Text(
            "A",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        "display" -> Icon(Icons.Filled.Tune, contentDescription = "Page display")
        else -> Icon(Icons.Filled.List, contentDescription = actionId)
    }
}


// ── Paged model: one true page, transient per-page zoom ─────────

/** The display render width (in un-oversampled px) a paged cell
 * requests for [page] — the fit geometry over the cached size
 * table. Shared by the cell itself and the body's prefetch, so
 * a prefetched page lands under the very key the cell asks for. */
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
 * costs more than a refine render. Shared by the cell and the
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
    val pw = points.first.coerceAtLeast(1).toFloat()
    val ph = points.second.coerceAtLeast(1).toFloat()
    val byPixels = kotlin.math.sqrt(
        PdfSession.REFINE_MAX_PIXELS.toDouble() * pw / ph
    ).toInt()
    return (displayWidth * PDF_ZOOM_SUPERSAMPLE)
        .coerceAtMost(byPixels)
        .coerceAtLeast(displayWidth)
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
    viewModel: LocalDocReaderViewModel,
    session: PdfSession,
    initialPage: Int,
    currentPage: Int,
    settings: PdfDocSettings,
    autoScroll: AutoScrollController,
    onToggleControls: () -> Unit,
    tapPreset: TapLayoutPreset = TapLayoutPreset.MOON_DEFAULT
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
                        viewModel.onPdfPosition(firstPage, 0f)
                    }
                }
            }
            LaunchedEffect(session, viewModel, spreadActive) {
                viewModel.jumps.collect { jump ->
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
                        viewModel.goToPdfPage(next)
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
                    pages.forEach { page ->
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
                                viewModel.updatePdfSettings { it.copy(zoom = zoom) }
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
    tapPreset: TapLayoutPreset = TapLayoutPreset.MOON_DEFAULT
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
    val surround = settings.filter.surroundArgb()
    // F-6: the display filter as a draw-time colour filter over
    // the RAW cached render.
    val drawFilter = remember(settings.filter) {
        settings.filter.colorMatrix()?.let { matrix ->
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
        if (zoomState.zoom > PdfSession.REFINE_ZOOM_THRESHOLD) {
            delay(150)
            refineNow()
        } else if (!zoomState.isZoomed) {
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
                                // the chrome.
                                xFraction < 0.33f -> onTurnPage(-1)
                                xFraction > 0.67f -> onTurnPage(1)
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
                                    onTurnPage(-1)
                                }
                            TapZoneAction.TURN_NEXT ->
                                if (zoomState.isZoomed) {
                                    zoomState.reset()
                                } else {
                                    onTurnPage(1)
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
                        if (settings.fitMode == PdfFitMode.FIT_PAGE) {
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
    viewModel: LocalDocReaderViewModel,
    session: PdfSession,
    initialPage: Int,
    settings: PdfDocSettings,
    autoScroll: AutoScrollController,
    onToggleControls: () -> Unit,
    tapPreset: TapLayoutPreset = TapLayoutPreset.MOON_DEFAULT,
    smartScrollLock: Boolean = true
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

    LaunchedEffect(session, viewModel) {
        viewModel.jumps.collect { jump ->
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
            viewModel.onPdfPosition(page, fraction)
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
        val drawFilter = remember(settings.filter) {
            settings.filter.colorMatrix()?.let { matrix ->
                ColorFilter.colorMatrix(
                    androidx.compose.ui.graphics.ColorMatrix(matrix.array.copyOf())
                )
            }
        }
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
                        colorFilter = drawFilter
                    )
                }
            }
        }
    }
}

// ── Go to page: number entry + thumbnail-free page grid ─────────

@Composable
private fun PdfGoToPageDialog(
    session: PdfSession,
    currentPage: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    var text by remember { mutableStateOf((currentPage + 1).toString()) }
    // v1.5.27: printed labels land with the deferred probe —
    // collecting its state refreshes the grid's labels if it
    // settles while this dialog is open.
    val probeState by session.probeState.collectAsStateWithLifecycle()
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("Page (1–$pageCount)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotEmpty() && !valid
                )
                val target = parsed?.minus(1)
                if (target != null && target in 0 until pageCount) {
                    Text(
                        session.displayLabelFor(target),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 52.dp),
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items((0 until pageCount).toList()) { page ->
                        TextButton(
                            onClick = { onPick(page) },
                            modifier = Modifier.background(
                                if (page == currentPage) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Text(
                                session.printedLabelFor(page) ?: "${page + 1}",
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.minus(1)?.let(onPick) },
                enabled = valid
            ) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ── Sheets ───────────────────────────────────────────────────────

/** H2: the ONE tabbed contents panel — Chapters (outline, page
 * number at each row's end) | Bookmarks (page-anchored), with
 * the footer carrying the current chapter + Search + Page
 * display. The honest empty state is kept. */
@Composable
private fun PdfContentsPanelSheet(
    viewModel: LocalDocReaderViewModel,
    session: PdfSession,
    currentPage: Int,
    initialTab: Int,
    onPicked: () -> Unit,
    onSearch: () -> Unit,
    onDisplay: () -> Unit,
    // v1.5.10 (F5): the sort toggle's store state.
    sortOrder: ContentsSortOrder = ContentsSortOrder.DOCUMENT_ORDER,
    onSortOrderChange: (ContentsSortOrder) -> Unit = {}
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    // v1.5.27: while the deferred probe is still out, the
    // chapters tab says it is loading — never that the document
    // has no contents — and the landing recomposes the rows
    // from the landed outline.
    val probeState by session.probeState.collectAsStateWithLifecycle()
    val chapterRows = sortedChapterRows(
        pdfChapterRows(session.outline, currentPage), sortOrder
    )
    TabbedContentsPanel(
        chapterRows = chapterRows,
        sortOrder = sortOrder,
        onSortOrderChange = onSortOrderChange,
        chaptersEmptyText = if (
            probeState == PdfProbeState.Pending && session.outline.isEmpty()
        ) {
            "Loading the contents list…"
        } else {
            "This document has no built-in contents list. " +
                "Its own printed contents page, if it has one, " +
                "is part of the pages — use Go to page to reach it."
        },
        bookmarkRows = bookmarks.map { bookmark ->
            val page = LocatorJson.parsePdfLocator(bookmark.locatorJson)?.first
                ?: bookmark.chapterIndex
            ContentsBookmarkRow(
                label = bookmark.label.ifBlank { "Bookmark" },
                detail = "Page ${page + 1}"
            )
        },
        bookmarksEmptyText = "No bookmarks yet — tap the bookmark button to mark this page.",
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
            chapterTitle = session.anchorTitleFor(currentPage),
            position = pdfFooterPosition(session.outline, currentPage),
            total = session.outline.size
        ),
        initialTab = initialTab,
        footerActions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search in document")
            }
            IconButton(onClick = onDisplay) {
                Icon(Icons.Filled.Tune, contentDescription = "Page display")
            }
        }
    )
}

@Composable
private fun PdfSearchSheet(
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
                label = { Text("Search in this document") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { viewModel.search(query) },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Search") }
        }
        Text(
            "Search uses the document's text layer; scanned pages without text won't match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
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
            Text("No matches in this document.", Modifier.padding(16.dp))
        }
    }
}

// ── Document display sheet: filter + scroll model ONLY ──────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PdfDisplaySheet(
    viewModel: LocalDocReaderViewModel,
    settings: PdfDocSettings,
    // v1.5.10: the More doors (F2 book information, F1 bar
    // customise, G2 control options, G5 share). Null = the
    // door is absent (share without a registered file).
    onBookInfo: (() -> Unit)? = null,
    onCustomiseBar: (() -> Unit)? = null,
    onControlOptions: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Page display", style = MaterialTheme.typography.titleMedium)
        Text(
            "A PDF's pages are fixed as printed — there are no text " +
                "settings to change. What you can change is how the " +
                "pages are shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Text("Reading", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderStadiumChip(
                selected = settings.scrollModel == PdfScrollModel.PAGED,
                onClick = {
                    viewModel.updatePdfSettings { it.copy(scrollModel = PdfScrollModel.PAGED) }
                },
                text = "Page by page"
            )
            ReaderStadiumChip(
                selected = settings.scrollModel == PdfScrollModel.CONTINUOUS,
                onClick = {
                    viewModel.updatePdfSettings {
                        it.copy(scrollModel = PdfScrollModel.CONTINUOUS)
                    }
                },
                text = "Scroll down"
            )
        }
        Text(
            if (settings.scrollModel == PdfScrollModel.PAGED) {
                "Swipe sideways to turn pages. Pinch to zoom — the zoom is remembered when you turn the page. Double-tap resets it."
            } else {
                "Pages follow one after another as you scroll. Zoom lives in Page by page mode."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // H1: fit-page / fit-width (paged model).
        if (settings.scrollModel == PdfScrollModel.PAGED) {
            Spacer(Modifier.height(8.dp))
            Text("Page fit", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderStadiumChip(
                    selected = settings.fitMode == PdfFitMode.FIT_PAGE,
                    onClick = {
                        viewModel.updatePdfSettings { it.copy(fitMode = PdfFitMode.FIT_PAGE) }
                    },
                    text = "Fit page"
                )
                ReaderStadiumChip(
                    selected = settings.fitMode == PdfFitMode.FIT_WIDTH,
                    onClick = {
                        viewModel.updatePdfSettings {
                            it.copy(fitMode = PdfFitMode.FIT_WIDTH)
                        }
                    },
                    text = "Fit width"
                )
            }
            Text(
                "Fit page shows the whole page; fit width fills the screen's width.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // H9: landscape spread.
            PdfSwitchRow(
                label = "Two-page spread in landscape",
                subtitle = "Side-by-side pages when the phone is sideways.",
                checked = settings.spread,
                onCheckedChange = { on ->
                    viewModel.updatePdfSettings { it.copy(spread = on) }
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Night display filter", style = MaterialTheme.typography.titleSmall)
        Text(
            "A filter over the page picture — it does not change the document's text or colours as printed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PdfPageFilter.entries.forEach { filter ->
                ReaderStadiumChip(
                    selected = settings.filter == filter,
                    onClick = {
                        viewModel.updatePdfSettings { it.withFilter(filter) }
                    },
                    text = when (filter) {
                        PdfPageFilter.NONE -> "As printed"
                        PdfPageFilter.INVERT -> "Invert"
                        PdfPageFilter.BLACK_AND_WHITE -> "Black & white"
                        PdfPageFilter.INVERT_BLACK_AND_WHITE -> "Invert B&W"
                        PdfPageFilter.SEPIA_PAGE -> "Sepia page"
                        PdfPageFilter.SOFT_GREY -> "Soft grey"
                    }
                )
            }
        }

        // G8: the shared comforts' settings.
        Spacer(Modifier.height(8.dp))
        Text("Reading comforts", style = MaterialTheme.typography.titleSmall)
        PdfSwitchRow(
            label = "Volume-key page turns",
            subtitle = "Volume down = next, volume up = back.",
            checked = settings.volumeKeyTurn,
            onCheckedChange = { on ->
                viewModel.updatePdfSettings { it.copy(volumeKeyTurn = on) }
            }
        )
        PdfSwitchRow(
            label = "Keep screen on",
            subtitle = "The screen stays awake while you read.",
            checked = settings.keepScreenOn,
            onCheckedChange = { on ->
                viewModel.updatePdfSettings { it.copy(keepScreenOn = on) }
            }
        )
        Text("Orientation", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderOrientation.entries.forEach { orientation ->
                ReaderStadiumChip(
                    selected = settings.orientation == orientation,
                    onClick = {
                        viewModel.updatePdfSettings { it.copy(orientation = orientation) }
                    },
                    text = when (orientation) {
                        ReaderOrientation.FREE -> "Free"
                        ReaderOrientation.PORTRAIT -> "Portrait"
                        ReaderOrientation.LANDSCAPE -> "Landscape"
                    }
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Auto-scroll speed",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "%.1f×".format(settings.autoScrollSpeed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = settings.autoScrollSpeed.toFloat(),
            onValueChange = { v ->
                viewModel.updatePdfSettings { it.copy(autoScrollSpeed = v.toDouble()) }
            },
            valueRange = 0.25f..4f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Brightness: swipe up or down along the left edge of the page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (settings.zoom > 1.01f) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { viewModel.updatePdfSettings { it.copy(zoom = 1f) } }
            ) { Text("Reset zoom to fit width") }
        }

        // v1.5.10: More — the reader's outward doors (F2/F1/G2/G5).
        if (onBookInfo != null || onCustomiseBar != null ||
            onControlOptions != null || onShare != null
        ) {
            Spacer(Modifier.height(8.dp))
            Text("More", style = MaterialTheme.typography.titleSmall)
            if (onBookInfo != null) {
                TextButton(onClick = onBookInfo) { Text("Book information") }
            }
            if (onCustomiseBar != null) {
                TextButton(onClick = onCustomiseBar) { Text("Customise reader bar") }
            }
            if (onControlOptions != null) {
                TextButton(onClick = onControlOptions) { Text("Control options") }
            }
            if (onShare != null) {
                TextButton(onClick = onShare) { Text("Share book file") }
            }
        }
    }
}

@Composable
private fun PdfSwitchRow(
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
        // U-4: switches in the design system — violet on-state.
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF8B5CF6),
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF161822),
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedBorderColor = Color.White.copy(alpha = 0.06f)
            )
        )
    }
}
