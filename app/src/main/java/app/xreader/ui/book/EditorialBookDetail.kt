package app.xreader.ui.book

import android.content.Intent
import androidx.compose.animation.core.Animatable
import app.xreader.core.motion.toSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.db.BookFormat
import app.xreader.data.db.LocalOutline
import app.xreader.data.db.SourceType
import app.xreader.data.library.ReadingStatus
import app.xreader.domain.reader.ChapterDisplayOrder
import app.xreader.ui.theme.FrauncesSerif
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Code 123 — the Editorial book page: the owner's approved
 * Paper & Sage Details copy (DESIGN-M3-BOOKDETAILS-V6-PAPER-COPY),
 * alive. Tonal header band, rotated cover, the progress RING
 * with the book's real percent, Catch me up + Lore tiles, the
 * five statuses in the design's connected-segment form (owner
 * ruling 2026-11-24 ~15:48: our five, everywhere), the alerts
 * card wired to the book's real Track state, the remove card
 * telling the soft-remove truth, and the docked action bar.
 * Beneath the copied page, in the same language: the synopsis
 * and Contents — the book's own table of contents (owner
 * ruling ~15:49) — with the chapters list in the same
 * treatment. Every number and control is real; where the
 * design printed decoration, the app's truth stands in.
 */
/**
 * Patch B (gap 61): the Book colours scope, book-page leg.
 * Nocturne's Customise copy is the semantics: "Each book's
 * cover can lend its colours to the book page" — at
 * BOOK_PAGE_ONLY and BOOK_PAGE_AND_READER the page takes the
 * book-derived colour treatment; at OFF it never does. In
 * this face the treatment is Material 3's own: a scheme
 * generated from the cover's seed through the MCU Content
 * style ([app.xreader.ui.theme.McuScheme.contentScheme] —
 * the same machinery this design's colour picker and the
 * retired cover-scheme branch of BookDetailTheme used),
 * provided to this page's subtree alone, so the tonal band,
 * cards and accents wear the book's colours and the app
 * scheme resumes untouched on exit. The seed rides the
 * shared VM palette load (coverSeedArgb, with the palette's
 * ambient as the fallback seed — the VM's own cache-hit
 * rule). No seed yet (or a cover that yields none) = the
 * page wears the user's chosen colour, exactly as before.
 */
@Composable
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
fun EditorialBookDetail(
    state: BookDetailUiState,
    localOutline: List<LocalOutline>,
    viewModel: BookDetailViewModel,
    container: app.xreader.di.AppContainer,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onReadChapter: (chapterIndex: Int) -> Unit,
    onReadLocalTarget: ((bookId: Long, targetJson: String) -> Unit)?,
    onOpenLore: () -> Unit,
    onOpenAiPage: (
        chapterIndex: Int, chapterId: Long?, illustrate: Boolean
    ) -> Unit = { _, _, _ -> },
    onExportEpub: () -> Unit,
    onExportCbz: () -> Unit,
    onOpenTag: (String) -> Unit = {},
    onOpenRelated: (Long) -> Unit = {},
    onFindTitle: (String) -> Unit = {},
    onOpenSourceDetails: (Long) -> Unit = {},
    onSiteCheck: (Long) -> Unit = {},
    onSessionHandoff: (
        Long, app.xreader.data.extension.SessionNeed.Kind
    ) -> Unit = { _, _ -> }
) {
    val appearance by container.appearancePrefs.state
        .collectAsStateWithLifecycle(
            initialValue = app.xreader.data.prefs.AppearanceState()
        )
    val bookColoursOn = appearance.bookColours !=
        app.xreader.data.prefs.BookColoursScope.OFF
    val coverSeed = state.coverSeedArgb
        ?: state.coverPalette?.ambient?.toArgb()
    val dark = app.xreader.ui.theme.isDarkScheme()
    val coverScheme = remember(coverSeed, dark, bookColoursOn) {
        if (bookColoursOn && coverSeed != null) {
            app.xreader.ui.theme.McuScheme
                .contentScheme(coverSeed, dark)
        } else {
            null
        }
    }
    val page: @Composable () -> Unit = {
        EditorialBookDetailContent(
            state = state,
            localOutline = localOutline,
            viewModel = viewModel,
            container = container,
            coverFile = coverFile,
            onBack = onBack,
            onReadChapter = onReadChapter,
            onReadLocalTarget = onReadLocalTarget,
            onOpenLore = onOpenLore,
            onOpenAiPage = onOpenAiPage,
            onExportEpub = onExportEpub,
            onExportCbz = onExportCbz,
            onOpenTag = onOpenTag,
            onOpenRelated = onOpenRelated,
            onFindTitle = onFindTitle,
            onOpenSourceDetails = onOpenSourceDetails,
            onSiteCheck = onSiteCheck,
            onSessionHandoff = onSessionHandoff
        )
    }
    if (coverScheme != null) {
        MaterialTheme(
            colorScheme = coverScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            page()
        }
    } else {
        page()
    }
}

@Composable
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
private fun EditorialBookDetailContent(
    state: BookDetailUiState,
    localOutline: List<LocalOutline>,
    viewModel: BookDetailViewModel,
    container: app.xreader.di.AppContainer,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onReadChapter: (chapterIndex: Int) -> Unit,
    onReadLocalTarget: ((bookId: Long, targetJson: String) -> Unit)?,
    onOpenLore: () -> Unit,
    /** Code 125 (F9): Ask AI is a full page now — the AI
     * doors navigate to it (continue chapter + chapter id;
     * illustrate starts the paint) instead of opening the
     * sheet. Back returns here. */
    onOpenAiPage: (
        chapterIndex: Int, chapterId: Long?, illustrate: Boolean
    ) -> Unit = { _, _, _ -> },
    onExportEpub: () -> Unit,
    onExportCbz: () -> Unit,
    onOpenTag: (String) -> Unit = {},
    onOpenRelated: (Long) -> Unit = {},
    onFindTitle: (String) -> Unit = {},
    onOpenSourceDetails: (Long) -> Unit = {},
    onSiteCheck: (Long) -> Unit = {},
    onSessionHandoff: (
        Long, app.xreader.data.extension.SessionNeed.Kind
    ) -> Unit = { _, _ -> }
) {
    val book = state.book
    val colors = MaterialTheme.colorScheme
    // Code 125 (F9): the Ask AI page's bind target — the
    // continue chapter and its id (the sheet host's
    // arguments, now the page route's).
    val aiChapterId = state.chapters.firstOrNull {
        it.index == state.continueChapterIndex
    }?.id
    val context = LocalContext.current
    var coverViewerOpen by remember { mutableStateOf(false) }
    var removeDialogOpen by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    // Code 124 (D1): the chapter tools' state — the same
    // ChapterListFilter / ChapterDisplayOrder pure logic the
    // shared page runs, over this face's own list.
    var chapterQuery by remember { mutableStateOf("") }
    var jumpFieldText by remember { mutableStateOf("") }
    var chapterStateFilter by remember {
        mutableStateOf(
            app.xreader.domain.reader.ChapterListFilter.State.ALL
        )
    }
    var chapterPartFilter by remember { mutableStateOf<String?>(null) }
    var newestFirst by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    // D1 (re-check fix): the chapter-settings sheet's door
    // state — the shared page's sheet, this face's surface.
    var chapterSettingsOpen by remember { mutableStateOf(false) }
    // Patch B (gap 3): the Download choices sheet's open
    // state — Whole book / Next 10 / Up to latest unread,
    // the shared page's three ranges.
    var downloadSheetOpen by remember { mutableStateOf(false) }
    // Patch B (gaps 4–5): the chapter list's own state —
    // the jump scrolls this list, and its window + sentinel
    // grow the rendered rows exactly as the shared page's
    // list does (100 at a time, +200 at the sentinel).
    val chapterListState =
        androidx.compose.foundation.lazy.rememberLazyListState()
    var chapterWindow by remember(
        state.book?.id, chapterQuery, chapterStateFilter,
        chapterPartFilter, newestFirst
    ) { mutableStateOf(100) }
    /** The chapter row a jump landed on (transient
     * highlight, the current chapter's own treatment). */
    var jumpTargetId by remember { mutableStateOf<Long?>(null) }
    /** Display position a jump is waiting to scroll to,
     * once the window has grown enough to compose the row. */
    var pendingJumpPos by remember { mutableStateOf(-1) }
    val engineLabel by viewModel.engineLabel
        .collectAsStateWithLifecycle()
    // MR-6 Item 5: the per-book encoding door's state (the same
    // shared section the Nocturne page renders — parity).
    val encodingDoor by viewModel.encodingDoor
        .collectAsStateWithLifecycle()
    val clipboard = androidx.compose.ui.platform
        .LocalClipboardManager.current
    // Phase 6 (shared with the Nocturne page): highlights →
    // Markdown, saved where the user picks.
    val markdownLauncher = androidx.activity.compose
        .rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .CreateDocument("text/markdown")
        ) { uri ->
            if (uri != null) {
                val stream = runCatching {
                    context.contentResolver.openOutputStream(uri)
                }.getOrNull()
                if (stream != null) {
                    viewModel.exportHighlightsMarkdown(stream) { message ->
                        viewModel.notifyMessage(message)
                    }
                }
            }
        }

    // Patch B (gap 1): provider enrichment (Reviews /
    // Comments / "Readers also read") loads on this face
    // too — the shared page calls loadEnrichment() after
    // its Editorial early-return, so this face never asked
    // and EnrichmentSections below stayed permanently
    // unloaded. The VM's call is once-only (enrichmentTried),
    // so re-entry is free.
    LaunchedEffect(book?.id) {
        if (book != null) {
            viewModel.loadEnrichment()
            // Patch B (gap 61): the cover palette + seed —
            // the shared page calls loadPalette() after its
            // Editorial early-return, so this face never asked
            // and state.coverSeedArgb stayed null (the wrapper
            // above had nothing to build the book's scheme
            // from). Once-only in the VM, so re-entry is free.
            viewModel.loadPalette()
        }
    }

    if (book == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loading) {
                CircularProgressIndicator(color = colors.primary)
            } else {
                // Patch B (gap 12): the shared page's
                // book-not-found state — a designed empty
                // state with the way back, not bare text.
                // (EmptyState itself takes the M3 form
                // under this design.)
                app.xreader.ui.common.EmptyState(
                    title = "Book not found",
                    message = "This book isn't in your library anymore.",
                    actionLabel = "Back to library",
                    onAction = onBack
                )
            }
        }
        return
    }

    // The book's real percent — the same derivation the shared
    // page computes (read chapters + the in-chapter fraction,
    // over the loaded list; a locator-keyed local book's stored
    // fraction when it has no chapter rows).
    val readCount = state.chapters.count { it.read }
    val continueChapter = state.chapters.firstOrNull {
        it.index == state.continueChapterIndex
    }
    val bookPercent = if (state.chapters.isEmpty()) {
        if (book.sourceType == SourceType.LOCAL_FILE &&
            state.progress?.locatorJson != null
        ) {
            (state.progress!!.fraction * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
    } else {
        val progressNow = state.progress
        val inChapter = if (progressNow != null &&
            continueChapter?.read == false
        ) {
            progressNow.fraction
        } else {
            0f
        }
        (((readCount + inChapter) / state.chapters.size) * 100)
            .toInt().coerceIn(0, 100)
    }
    val continueNumber = continueChapter?.let {
        ChapterDisplayOrder.displayNumber(it)
    }
    val placeLabel = when {
        state.progress == null -> "Not started"
        book.localFormat == BookFormat.PDF ->
            "Page ${continueNumber ?: (continueChapter?.index?.plus(1))?.toString() ?: "1"}"
        continueNumber != null -> "Chapter $continueNumber"
        !continueChapter?.title.isNullOrBlank() ->
            continueChapter?.title?.trim().orEmpty()
        else -> "Chapter 1"
    }
    val updatedLabel = state.progress?.let { progress ->
        val minutes = ((System.currentTimeMillis() - progress.updatedAt) /
            60_000L).coerceAtLeast(0)
        when {
            minutes < 1 -> "Updated just now"
            minutes < 60 -> "Updated ${minutes}m ago"
            minutes < 60 * 24 -> "Updated ${minutes / 60}h ago"
            else -> "Updated ${minutes / (60 * 24)}d ago"
        }
    } ?: "Open the book to begin"
    val formatLabel = when (book.localFormat) {
        BookFormat.EPUB -> "EPUB"
        BookFormat.PDF -> "PDF"
        BookFormat.CBZ, BookFormat.CBR -> "Comic"
        BookFormat.TXT -> "Text"
        BookFormat.MOBI, BookFormat.AZW3 -> "MOBI"
        BookFormat.FB2 -> "FB2"
        BookFormat.HTML, BookFormat.MHTML -> "HTML"
        BookFormat.MARKDOWN -> "Markdown"
        BookFormat.DOCX -> "DOCX"
        BookFormat.ODT -> "ODT"
        BookFormat.RTF -> "RTF"
        BookFormat.CHM -> "CHM"
        BookFormat.UMD -> "UMD"
        BookFormat.UNKNOWN -> if (book.sourceType == SourceType.LOCAL_FILE) {
            "Local book"
        } else {
            "Novel"
        }
    }
    val extentLabel = when {
        book.localFormat == BookFormat.PDF && book.totalChapters > 0 ->
            "${book.totalChapters} pages"
        localOutline.isNotEmpty() -> "${localOutline.size} sections"
        state.chapters.isNotEmpty() -> "${state.chapters.size} chapters"
        book.totalChapters > 0 -> "${book.totalChapters} chapters"
        else -> null
    }
    val statusSegments = listOf(
        ReadingStatus.READING to "Reading",
        ReadingStatus.ON_HOLD to "On hold",
        ReadingStatus.PLAN_TO_READ to "Plan to read",
        ReadingStatus.COMPLETED to "Completed",
        ReadingStatus.DROPPED to "Dropped"
    )

    // Code 124 (D1): the chapter tools run the shared page's
    // own pure logic — filter (search + state + part) and
    // display order — over this face's list.
    val chapterParts = remember(state.chapters) {
        app.xreader.domain.reader.ChapterListFilter
            .availableParts(state.chapters)
    }
    val visibleChapters = remember(
        state.chapters, chapterQuery, chapterStateFilter, chapterPartFilter
    ) {
        app.xreader.domain.reader.ChapterListFilter.apply(
            state.chapters, chapterQuery, chapterStateFilter,
            chapterPartFilter
        )
    }
    val displayChapters = remember(visibleChapters, newestFirst) {
        ChapterDisplayOrder.ordered(visibleChapters, newestFirst)
    }
    // Patch B (gap 5): the windowed display — 100 rows at a
    // time, grown by the sentinel at the window's end (the
    // shared page's v1.3.1/V120-CHECK 54 window, mirrored).
    val displayedChapters = remember(displayChapters, chapterWindow) {
        displayChapters.take(chapterWindow)
    }
    // A jump target (the Continue chapter) beyond the window
    // expands the window to reach it — search and Continue
    // must never be fenced off by the window.
    LaunchedEffect(displayChapters, state.continueChapterIndex) {
        val pos = displayChapters.indexOfFirst {
            it.index == state.continueChapterIndex
        }
        if (pos >= chapterWindow) chapterWindow = pos + 1
    }
    // Patch B (gap 5): the near-edge growth trigger, hoisted
    // exactly as the shared page hoists it — one snapshotFlow
    // over the list's visible items reports the furthest
    // visible chapter's stored index to the VM's growth seam.
    LaunchedEffect(chapterListState, displayChapters) {
        androidx.compose.runtime.snapshotFlow {
            val byId = displayChapters.associateBy { it.id }
            chapterListState.layoutInfo.visibleItemsInfo
                .mapNotNull { item ->
                    ((item.key as? String)
                        ?.removePrefix("ch-")
                        ?.toLongOrNull())
                        ?.let { byId[it]?.index }
                }
                .maxOrNull()
        }
            .distinctUntilChanged()
            .collect { maxIndex ->
                if (maxIndex != null) {
                    viewModel.onChapterNearEdge(maxIndex)
                }
            }
    }
    // Patch B (gap 4): the jump scroll. The chapter rows sit
    // deep inside this page's single LazyColumn, so the scroll
    // index is the chapter's display position plus the number
    // of items the column emits before the first chapter row —
    // counted from the same conditions that gate those items
    // below (band, progress, source banner, tiles, AI tiles,
    // status, encoding door, alerts, remove, synopsis,
    // local-readd offer, contents head, contents tools).
    val chapterRowPrefixCount = 9 +
        (if (book.sourceType == SourceType.EXTENSION) 1 else 0) +
        (if (encodingDoor.visible) 1 else 0) +
        (if (!book.description.isNullOrBlank()) 1 else 0) +
        (if (book.sourceType == SourceType.LOCAL_FILE &&
            book.documentUri == null && book.filePath == null &&
            localOutline.isEmpty()
        ) 1 else 0)
    LaunchedEffect(pendingJumpPos, displayedChapters.size) {
        val pos = pendingJumpPos
        if (pos >= 0 && pos < displayChapters.size &&
            pos >= displayedChapters.size
        ) {
            chapterWindow = pos + 1
        }
        if (pos >= 0 && pos < displayedChapters.size) {
            // The trigger clears in the FINALLY, after the
            // scroll returns — clearing it first would change
            // this effect's key mid-flight and cancel the
            // animation in its first frame (the shared page's
            // Fix C1 lesson, mirrored).
            try {
                chapterListState.animateScrollToItem(
                    chapterRowPrefixCount + pos
                )
            } finally {
                if (pendingJumpPos == pos) pendingJumpPos = -1
            }
        }
    }
    // The jump highlight is transient: it fades back to the
    // plain row after a few seconds.
    LaunchedEffect(jumpTargetId) {
        if (jumpTargetId != null) {
            kotlinx.coroutines.delay(2_800)
            jumpTargetId = null
        }
    }
    val maxChapterIndex = remember(state.chapters) {
        state.chapters.maxOfOrNull { it.index } ?: -1
    }
    // Patch B (gap 9): the progress card's time-left estimate
    // — the shared page's own WpmEstimator derivation over
    // this book's chapters at the reader's measured pace.
    val remainingEstimate = remember(state.chapters, state.wpm) {
        app.xreader.domain.reader.WpmEstimator
            .estimateRemainingMinutes(
                state.chapters.map {
                    app.xreader.domain.reader.WpmEstimator.ChapterWords(
                        it.read, it.wordCount
                    )
                },
                state.wpm
            )
    }
    val nextUnread = state.chapters.firstOrNull { !it.read }
    // A2: Save as EPUB exists when there is something to
    // save — a local book's own file, or chapters already
    // downloaded from a source.
    val canSaveEpub = book.sourceType == SourceType.LOCAL_FILE ||
        state.chapters.any {
            it.downloadState == app.xreader.data.db.DownloadState.READY
        }
    // D1: the Chapters section's recovery verdict — the
    // shared page's pure ChaptersSection decision.
    val section = ChaptersSection.state(
        chapterCount = state.chapters.size,
        chaptersLoading = state.chaptersLoading,
        chaptersError = state.chaptersError,
        isExtensionBook = book.sourceType == SourceType.EXTENSION,
        remoteId = book.remoteId,
        isLinkSource = state.isLinkSource,
        needsSiteCheck = state.sourceNeedsSiteCheck
    )

    Box(Modifier.fillMaxSize().background(colors.surface)) {
        // Code 125 (F10): pull to refresh at the top, the
        // Library's own behaviour — the same refresh seam
        // the Contents Refresh pill calls, so a pull runs
        // exactly what the pill runs. No scroll behaviour
        // is attached anywhere on this page (the code-122
        // orphaned-behavior lesson), so the pull gesture
        // reaches the list.
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = state.chaptersLoading,
            onRefresh = { viewModel.refreshChapters() },
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Patch B (gap 4): the list state the chapter
            // jump scrolls (previously this column had no
            // state, so no programmatic scroll was possible).
            state = chapterListState,
            contentPadding = PaddingValues(bottom = 108.dp)
        ) {
            // ── The tonal header band ──────────────────────────
            item(key = "band") {
                Surface(
                    color = colors.secondaryContainer,
                    shape = RoundedCornerShape(
                        bottomStart = 44.dp, bottomEnd = 44.dp
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 56.dp, y = (-56).dp)
                                .size(192.dp)
                                .clip(CircleShape)
                                .background(
                                    colors.primaryContainer
                                        .copy(alpha = 0.6f)
                                )
                        )
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .offset(x = (-32).dp, y = 48.dp)
                                .size(128.dp)
                                .rotate(12f)
                                .clip(RoundedCornerShape(32.dp))
                                .background(
                                    colors.tertiaryContainer
                                        .copy(alpha = 0.5f)
                                )
                        )
                        Column(
                            Modifier.padding(
                                start = 20.dp, end = 20.dp,
                                top = 12.dp, bottom = 28.dp
                            )
                        ) {
                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled
                                            .ArrowBack,
                                        contentDescription = "Back",
                                        tint = colors.onSecondaryContainer
                                    )
                                }
                                Text(
                                    "Details",
                                    style = MaterialTheme.typography
                                        .titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSecondaryContainer,
                                    modifier = Modifier.weight(1f),
                                    textAlign = androidx.compose.ui.text
                                        .style.TextAlign.Center
                                )
                                // Code 124 (A3): no header menu —
                                // Refresh, Download all and Save
                                // as EPUB are visible doors on
                                // this page now, so nothing is
                                // left to bury. The spacer keeps
                                // the title centred.
                                Spacer(Modifier.size(48.dp))
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(20.dp)
                            ) {
                                val cover = coverFile(book.coverPath)
                                Box(
                                    Modifier
                                        .size(width = 124.dp, height = 180.dp)
                                        .rotate(-3f)
                                        .shadow(
                                            16.dp,
                                            RoundedCornerShape(24.dp)
                                        )
                                        .clip(RoundedCornerShape(24.dp))
                                        .border(
                                            4.dp, colors.surface,
                                            RoundedCornerShape(24.dp)
                                        )
                                        .background(
                                            colors.surfaceContainerHigh
                                        )
                                        .clickable { coverViewerOpen = true }
                                ) {
                                    // Code 130: the shipped hero's
                                    // fallback order (M3BookCover's,
                                    // mirrored) — local file, else
                                    // the cover URL, else the
                                    // designed title placeholder;
                                    // the placeholder is also the
                                    // loading and error content. A
                                    // missing or failed cover is
                                    // never the empty dark box the
                                    // code-129 proof showed.
                                    when {
                                        cover != null &&
                                            cover.exists() ->
                                            coil.compose
                                                .SubcomposeAsyncImage(
                                                    model = cover,
                                                    contentDescription =
                                                        book.title,
                                                    contentScale =
                                                        ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxSize(),
                                                    loading = {
                                                        app.xreader.ui
                                                            .library
                                                            .PlaceholderCover(
                                                                title =
                                                                    book.title,
                                                                author =
                                                                    book.author,
                                                                modifier =
                                                                    Modifier
                                                                        .fillMaxSize()
                                                            )
                                                    },
                                                    error = {
                                                        app.xreader.ui
                                                            .library
                                                            .PlaceholderCover(
                                                                title =
                                                                    book.title,
                                                                author =
                                                                    book.author,
                                                                modifier =
                                                                    Modifier
                                                                        .fillMaxSize()
                                                            )
                                                    }
                                                )
                                        !book.coverUrl
                                            .isNullOrBlank() ->
                                            coil.compose
                                                .SubcomposeAsyncImage(
                                                    model = book.coverUrl,
                                                    contentDescription =
                                                        book.title,
                                                    contentScale =
                                                        ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxSize(),
                                                    loading = {
                                                        app.xreader.ui
                                                            .library
                                                            .PlaceholderCover(
                                                                title =
                                                                    book.title,
                                                                author =
                                                                    book.author,
                                                                modifier =
                                                                    Modifier
                                                                        .fillMaxSize()
                                                            )
                                                    },
                                                    error = {
                                                        app.xreader.ui
                                                            .library
                                                            .PlaceholderCover(
                                                                title =
                                                                    book.title,
                                                                author =
                                                                    book.author,
                                                                modifier =
                                                                    Modifier
                                                                        .fillMaxSize()
                                                            )
                                                    }
                                                )
                                        else ->
                                            app.xreader.ui.library
                                                .PlaceholderCover(
                                                    title = book.title,
                                                    author = book.author,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                )
                                    }
                                }
                                Column(
                                    Modifier.padding(top = 8.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        book.title,
                                        fontFamily = FrauncesSerif,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 21.sp,
                                        lineHeight = 26.sp,
                                        color = colors.onSecondaryContainer,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (book.author.isNotBlank()) {
                                        Text(
                                            "by ${book.author}",
                                            style = MaterialTheme
                                                .typography.bodyMedium,
                                            color = colors
                                                .onSecondaryContainer
                                                .copy(alpha = 0.8f)
                                        )
                                    }
                                    // Patch B (gap 10): the hero
                                    // chips at parity, in the
                                    // shared page's order —
                                    // provider first, then format,
                                    // the reader's own rating, the
                                    // extent, and the downloaded
                                    // fraction. A FlowRow so the
                                    // full set wraps whole instead
                                    // of crushing on narrow phones.
                                    androidx.compose.foundation.layout
                                        .FlowRow(
                                            horizontalArrangement =
                                                Arrangement.spacedBy(8.dp),
                                            verticalArrangement =
                                                Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (state.providerName
                                                    .isNotBlank() &&
                                                book.sourceType ==
                                                SourceType.EXTENSION
                                            ) {
                                                EditorialBandChip(
                                                    state.providerName
                                                )
                                            }
                                            EditorialBandChip(formatLabel)
                                            book.userRating?.let { rating ->
                                                EditorialBandChip(
                                                    "★ $rating"
                                                )
                                            }
                                            extentLabel?.let {
                                                EditorialBandChip(it)
                                            }
                                            EditorialBandChip(
                                                "${state.downloadedCount}/" +
                                                    "${state.chapters.size} " +
                                                    "downloaded"
                                            )
                                        }
                                }
                            }
                        }
                    }
                }
            }

            // ── The progress card with the ring ────────────────
            item(key = "progress") {
                Surface(
                    color = colors.surfaceContainerLow,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp)
                ) {
                    // Patch B (gap 9): a Column now — the ring
                    // row on top, the metric tiles and notes
                    // below it, inside the one card.
                    Column {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            Modifier.size(76.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { bookPercent / 100f },
                                modifier = Modifier.fillMaxSize(),
                                color = colors.primary,
                                trackColor = colors.secondaryContainer,
                                strokeWidth = 8.dp,
                                strokeCap = StrokeCap.Round
                            )
                            Text(
                                "$bookPercent%",
                                fontFamily = FrauncesSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = colors.onSurface
                            )
                        }
                        Column(
                            verticalArrangement =
                                Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically,
                                horizontalArrangement =
                                    Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "READING PROGRESS",
                                    style = MaterialTheme.typography
                                        .labelSmall,
                                    letterSpacing = 1.2.sp,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Text(
                                placeLabel,
                                fontFamily = FrauncesSerif,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 22.sp,
                                color = colors.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                state.readBlockedReason ?: updatedLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    // Patch B (gap 9): the progress card's
                    // missing pieces, in this face's language —
                    // the two micro-metric tiles (time left at
                    // the reader's own pace, what's on the
                    // device), the update-rhythm line, and the
                    // progress carry-over notes. The values are
                    // the shared page's own derivations.
                    Row(
                        Modifier.padding(
                            start = 16.dp, end = 16.dp, bottom = 16.dp
                        ),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceContainer,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment =
                                    Alignment.CenterVertically,
                                horizontalArrangement =
                                    Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column {
                                    Text(
                                        "TIME LEFT",
                                        style = MaterialTheme.typography
                                            .labelSmall,
                                        letterSpacing = 1.2.sp,
                                        color = colors.onSurfaceVariant
                                    )
                                    Text(
                                        if (remainingEstimate.first > 0) {
                                            app.xreader.domain.reader
                                                .WpmEstimator.formatMinutes(
                                                    remainingEstimate.first
                                                ) + " left"
                                        } else {
                                            "—"
                                        },
                                        style = MaterialTheme.typography
                                            .labelMedium,
                                        color = colors.onSurface
                                    )
                                }
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.surfaceContainer,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment =
                                    Alignment.CenterVertically,
                                horizontalArrangement =
                                    Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column {
                                    Text(
                                        "ON DEVICE",
                                        style = MaterialTheme.typography
                                            .labelSmall,
                                        letterSpacing = 1.2.sp,
                                        color = colors.onSurfaceVariant
                                    )
                                    Text(
                                        "${state.downloadedCount} chapters",
                                        style = MaterialTheme.typography
                                            .labelMedium,
                                        color = colors.onSurface
                                    )
                                }
                            }
                        }
                    }
                    // The rhythm line — shown only when the
                    // cadence is actually knowable (UpdateRhythm).
                    state.expectedNextLabel?.let { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, bottom = 16.dp
                            )
                        )
                    }
                    // The progress carry-over notes — they
                    // describe the resume this card reports.
                    state.carriedOverNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, bottom = 16.dp
                            )
                        )
                    }
                    state.progressLinkNote?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, bottom = 16.dp
                            )
                        )
                    }
                    }
                }
            }

            // Patch B (gap 11): the source banner — the host
            // the book came from, how much of it is loaded,
            // and the site-check door on a HEALTHY book (the
            // shared page's banner, in this face's language;
            // until now the site check existed here only
            // inside the failure card).
            if (book.sourceType == SourceType.EXTENSION) {
                item(key = "source-banner") {
                    val host = remember(section.sourcePageUrl) {
                        section.sourcePageUrl?.let { url ->
                            runCatching { java.net.URI(url).host }
                                .getOrNull()
                        }
                    }
                    Surface(
                        color = colors.surfaceContainerLow,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 20.dp, end = 20.dp, top = 16.dp
                            )
                    ) {
                        Row(
                            Modifier.padding(
                                horizontal = 14.dp, vertical = 10.dp
                            ),
                            verticalAlignment =
                                Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(
                                        if (state.sourceNeedsSiteCheck) {
                                            Color(0xFFF59E0B)
                                        } else {
                                            Color(0xFF34D399)
                                        },
                                        CircleShape
                                    )
                            )
                            Text(
                                host ?: "Source book",
                                style = MaterialTheme.typography
                                    .labelMedium,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "· ${state.chapters.size} loaded",
                                style = MaterialTheme.typography
                                    .labelSmall,
                                color = colors.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            if (state.sourceNeedsSiteCheck) {
                                TextButton(
                                    onClick = {
                                        book.extensionId?.let {
                                            onSiteCheck(it)
                                        }
                                    }
                                ) { Text("Site check") }
                            }
                        }
                    }
                }
            }

            // ── Catch me up + Lore ─────────────────────────────
            item(key = "tiles") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EditorialDetailTile(
                        title = "Catch me up",
                        sub = "Recap so far",
                        icon = Icons.Filled.History,
                        container = colors.primaryContainer,
                        onContainer = colors.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.storySoFar() }
                    )
                    EditorialDetailTile(
                        title = "Lore",
                        sub = "Characters & world",
                        icon = Icons.Filled.AutoStories,
                        container = colors.tertiaryContainer,
                        onContainer = colors.onTertiaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenLore
                    )
                }
            }
            // Code 125 (F9): the AI doors, beside Catch me up
            // + Lore as the owner ordered — Ask AI opens the
            // full Ask AI page (chat, summary, illustrate —
            // the same AiSheetContent the reader's AI page
            // hosts); Illustrate opens it and starts the
            // paint of this chapter's moment.
            item(key = "ai-tiles") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EditorialDetailTile(
                        title = "Ask AI",
                        sub = "Chat & answers",
                        icon = Icons.Filled.AutoAwesome,
                        container = colors.secondaryContainer,
                        onContainer = colors.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOpenAiPage(
                                state.continueChapterIndex,
                                aiChapterId, false
                            )
                        }
                    )
                    EditorialDetailTile(
                        title = "Illustrate",
                        sub = "Paint this moment",
                        icon = Icons.Filled.Image,
                        container = colors.primaryContainer,
                        onContainer = colors.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onOpenAiPage(
                                state.continueChapterIndex,
                                aiChapterId, true
                            )
                        }
                    )
                }
            }

            // ── Status: our five, connected ────────────────────
            item(key = "status") {
                Column(
                    Modifier.padding(
                        start = 20.dp, end = 20.dp, top = 28.dp
                    )
                ) {
                    Text(
                        "Status",
                        fontFamily = FrauncesSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        statusSegments.forEachIndexed { index, (status, label) ->
                            val picked = state.bookMeta.status == status
                            val shape = when (index) {
                                0 -> RoundedCornerShape(
                                    topStart = 24.dp, bottomStart = 24.dp,
                                    topEnd = 6.dp, bottomEnd = 6.dp
                                )
                                statusSegments.lastIndex ->
                                    RoundedCornerShape(
                                        topStart = 6.dp, bottomStart = 6.dp,
                                        topEnd = 24.dp, bottomEnd = 24.dp
                                    )
                                else -> RoundedCornerShape(6.dp)
                            }
                            Surface(
                                color = if (picked) {
                                    colors.primary
                                } else {
                                    colors.surfaceContainer
                                },
                                shape = shape,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clickable {
                                        // Patch B (gap 8):
                                        // re-tapping the picked
                                        // status clears it back to
                                        // NONE, like the shared
                                        // page — the segment was a
                                        // one-way door before.
                                        viewModel.setReadingStatus(
                                            if (picked) {
                                                ReadingStatus.NONE
                                            } else {
                                                status
                                            }
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        label,
                                        fontSize = 11.sp,
                                        fontWeight = if (picked) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Medium
                                        },
                                        color = if (picked) {
                                            colors.onPrimary
                                        } else {
                                            colors.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── MR-6 Item 5: the encoding door (local TXT) ─────
            if (encodingDoor.visible) {
                item(key = "encoding-door") {
                    EncodingDoorSection(
                        door = encodingDoor,
                        onPick = { viewModel.setBookEncoding(it) },
                        onDismissNote = { viewModel.clearEncodingNote() }
                    )
                }
            }

            // ── The alerts card (the book's Track state) ───────
            item(key = "alerts") {
                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, colors.outlineVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Track this book",
                                style = MaterialTheme.typography
                                    .titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )
                            Text(
                                "New chapters are counted on its " +
                                    "cover as they arrive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.bookMeta.tracked,
                            onCheckedChange = { viewModel.setTracked(it) }
                        )
                    }
                }
            }

            // ── The remove card ────────────────────────────────
            item(key = "remove") {
                Surface(
                    color = colors.errorContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Remove from library",
                                style = MaterialTheme.typography
                                    .titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onErrorContainer
                            )
                            Text(
                                "Your place, bookmarks and " +
                                    "downloads are kept.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onErrorContainer
                                    .copy(alpha = 0.75f)
                            )
                        }
                        Surface(
                            color = colors.error,
                            shape = CircleShape,
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { removeDialogOpen = true }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription =
                                        "Remove from library",
                                    tint = colors.onError,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Synopsis ───────────────────────────────────────
            if (!book.description.isNullOrBlank()) {
                item(key = "synopsis") {
                    Column(
                        Modifier.padding(
                            start = 20.dp, end = 20.dp, top = 28.dp
                        )
                    ) {
                        Text(
                            "About this book",
                            fontFamily = FrauncesSerif,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp,
                            color = colors.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            book.description!!.trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            // Code 124 (D1): the legacy lane's re-add offer,
            // under the shared page's own condition.
            if (book.sourceType == SourceType.LOCAL_FILE &&
                book.documentUri == null && book.filePath == null &&
                localOutline.isEmpty()
            ) {
                item(key = "local-readd-offer") {
                    Box(
                        Modifier.padding(
                            start = 20.dp, end = 20.dp, top = 28.dp
                        )
                    ) {
                        LegacyReAddCard(viewModel = viewModel)
                    }
                }
            }

            // ── Contents: the book's own TOC ───────────────────
            item(key = "contents-head") {
                Column(
                    Modifier.padding(
                        start = 20.dp, end = 20.dp, top = 28.dp
                    )
                ) {
                    Text(
                        "Contents",
                        fontFamily = FrauncesSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp,
                        color = colors.onSurface
                    )
                    val countLine = when {
                        localOutline.isNotEmpty() ->
                            "${localOutline.size} entries from the " +
                                "book's own table of contents"
                        state.chapters.isNotEmpty() ->
                            "${state.chapters.size} chapters"
                        state.chaptersLoading -> "Finding chapters…"
                        else -> "No chapters yet"
                    }
                    Text(
                        countLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    // Code 124 (A2/A3): ONE visible actions
                    // row — Refresh · Download all · Save as
                    // EPUB — nothing primary lives in a menu
                    // on this page any more.
                    // Code 125 (F11): a FlowRow, not a Row —
                    // the three pills at shipped padding need
                    // ~375 dp against ~371 dp available at
                    // 411 dp width, so the Row starved "Save
                    // as EPUB" into an ellipsis. The pills run
                    // at 10 dp side padding here (24 dp saved
                    // across the three) and wrap WHOLE to a
                    // second line if a device's font metrics
                    // still overflow — no label ever truncates.
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        EditorialActionPill(
                            icon = Icons.Filled.Refresh,
                            label = if (state.chaptersLoading) {
                                "Refreshing…"
                            } else {
                                "Refresh"
                            },
                            enabled = !state.chaptersLoading,
                            horizontalPadding = 10.dp,
                            onClick = { viewModel.refreshChapters() }
                        )
                        if (book.sourceType == SourceType.EXTENSION &&
                            state.chapters.isNotEmpty()
                        ) {
                            // Patch B (gap 3): the pill opens
                            // the Download choices sheet (Whole
                            // book / Next 10 / Up to latest
                            // unread, with counts) — the shared
                            // page's three ranges — instead of
                            // firing "Download all" directly.
                            EditorialActionPill(
                                icon = Icons.Filled.Download,
                                label = if (
                                    state.hasUndownloadedChapters
                                ) {
                                    "Download"
                                } else {
                                    "Downloaded"
                                },
                                enabled = state.hasUndownloadedChapters,
                                horizontalPadding = 10.dp,
                                onClick = { downloadSheetOpen = true }
                            )
                        }
                        if (canSaveEpub) {
                            EditorialActionPill(
                                icon = Icons.Filled.Download,
                                label = "Save as EPUB",
                                enabled = true,
                                horizontalPadding = 10.dp,
                                onClick = onExportEpub
                            )
                        }
                    }
                    // D1 (re-check fix): the chapter-settings
                    // door + the Latest line — the shared
                    // page's Contents-header furniture, which
                    // the first Stage-3 pass missed: the door
                    // opens the sheet below (order, show,
                    // scanlator filter — the exclusion itself
                    // is applied by the view model to
                    // state.chapters, so this face's list
                    // obeys it exactly as the shared page's
                    // does).
                    if (localOutline.isEmpty() &&
                        state.chapters.isNotEmpty()
                    ) {
                        Row(
                            Modifier.padding(top = 4.dp),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { chapterSettingsOpen = true }
                            ) { Text("Chapter settings") }
                            Spacer(Modifier.weight(1f))
                            val headerLatest = remember(
                                state.chapters
                            ) {
                                app.xreader.domain.reader
                                    .ChapterDateText.latestRelative(
                                        state.chapters.map {
                                            it.dateText
                                        }
                                    )
                            }
                            headerLatest?.let { label ->
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(
                                            colors.primary,
                                            CircleShape
                                        )
                                )
                                Text(
                                    " Latest · $label",
                                    style = MaterialTheme.typography
                                        .labelSmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // D1: Next unread — one tap to the first
                    // chapter not finished (the shared page's
                    // R16 line).
                    if (nextUnread != null && state.progress != null &&
                        localOutline.isEmpty()
                    ) {
                        TextButton(
                            onClick = {
                                onReadChapter(nextUnread.index)
                            }
                        ) {
                            Text("Next unread: ${nextUnread.title}")
                        }
                    }
                }
            }
            // Code 124 (D1): the chapter tools — search, the
            // All / Unread / Downloaded filters, part chips,
            // the order toggle and Jump to chapter, over the
            // shared page's ChapterListFilter logic.
            if (localOutline.isEmpty() && state.chapters.isNotEmpty()) {
                item(key = "contents-tools") {
                    Column(
                        Modifier.padding(
                            start = 20.dp, end = 20.dp, top = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        androidx.compose.material3.OutlinedTextField(
                            value = chapterQuery,
                            onValueChange = { chapterQuery = it },
                            placeholder = { Text("Search chapters") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                app.xreader.domain.reader
                                    .ChapterListFilter.State.ALL
                                    to "All",
                                app.xreader.domain.reader
                                    .ChapterListFilter.State.UNREAD
                                    to "Unread",
                                app.xreader.domain.reader
                                    .ChapterListFilter.State.DOWNLOADED
                                    to "Downloaded"
                            ).forEach { (value, label) ->
                                val picked = chapterStateFilter == value
                                Surface(
                                    color = if (picked) {
                                        colors.primary
                                    } else {
                                        colors.surfaceContainer
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            chapterStateFilter = value
                                        }
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme
                                            .typography.labelLarge,
                                        color = if (picked) {
                                            colors.onPrimary
                                        } else {
                                            colors.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(
                                            horizontal = 14.dp,
                                            vertical = 8.dp
                                        )
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { newestFirst = !newestFirst }
                            ) {
                                Text(
                                    if (newestFirst) {
                                        "Newest first"
                                    } else {
                                        "Oldest first"
                                    }
                                )
                            }
                        }
                        if (chapterParts.size > 1) {
                            Row(
                                Modifier.horizontalScroll(
                                    rememberScrollState()
                                ),
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                val allPicked = chapterPartFilter == null
                                Surface(
                                    color = if (allPicked) {
                                        colors.primary
                                    } else {
                                        colors.surfaceContainer
                                    },
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            chapterPartFilter = null
                                        }
                                ) {
                                    Text(
                                        "All parts",
                                        style = MaterialTheme
                                            .typography.labelLarge,
                                        color = if (allPicked) {
                                            colors.onPrimary
                                        } else {
                                            colors.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(
                                            horizontal = 14.dp,
                                            vertical = 8.dp
                                        )
                                    )
                                }
                                chapterParts.forEach { part ->
                                    val picked =
                                        chapterPartFilter == part
                                    Surface(
                                        color = if (picked) {
                                            colors.primary
                                        } else {
                                            colors.surfaceContainer
                                        },
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable {
                                                chapterPartFilter = part
                                            }
                                    ) {
                                        Text(
                                            part,
                                            style = MaterialTheme
                                                .typography.labelLarge,
                                            color = if (picked) {
                                                colors.onPrimary
                                            } else {
                                                colors.onSurfaceVariant
                                            },
                                            modifier = Modifier.padding(
                                                horizontal = 14.dp,
                                                vertical = 8.dp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        androidx.compose.material3.OutlinedTextField(
                            value = jumpFieldText,
                            onValueChange = { value ->
                                jumpFieldText = value
                                // Patch B (gap 4): the REAL jump,
                                // the shared page's wiring — a
                                // purely numeric entry resolves
                                // through ChapterJump against the
                                // list as the new query filters it,
                                // loads forward past the loaded
                                // edge, and queues the scroll +
                                // highlight; it ALSO sets
                                // chapterQuery (the filter's number
                                // rung is an exact identity match).
                                // Non-numeric text is the text
                                // search, as before. Before this
                                // fix the field only filtered.
                                if (ChapterJump.isJumpQuery(value)) {
                                    chapterQuery = value
                                    ChapterJump.parseNumber(value)
                                        ?.let { number ->
                                            viewModel
                                                .onChapterNumberSearch(
                                                    number.toInt()
                                                )
                                        }
                                    val jumpDisplay =
                                        ChapterDisplayOrder.ordered(
                                            app.xreader.domain.reader
                                                .ChapterListFilter.apply(
                                                    state.chapters,
                                                    value,
                                                    chapterStateFilter,
                                                    chapterPartFilter
                                                ),
                                            newestFirst
                                        )
                                    val pos = ChapterJump
                                        .resolvePosition(
                                            value, jumpDisplay
                                        )
                                    if (pos >= 0) {
                                        jumpTargetId =
                                            jumpDisplay[pos].id
                                        if (pos >= chapterWindow) {
                                            chapterWindow = pos + 1
                                        }
                                        pendingJumpPos = pos
                                    } else {
                                        jumpTargetId = null
                                        pendingJumpPos = -1
                                    }
                                } else {
                                    jumpTargetId = null
                                    pendingJumpPos = -1
                                    chapterQuery = value
                                }
                            },
                            placeholder = { Text("Jump to chapter…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (chapterQuery.isNotBlank() ||
                            chapterStateFilter !=
                            app.xreader.domain.reader
                                .ChapterListFilter.State.ALL ||
                            chapterPartFilter != null
                        ) {
                            Text(
                                "${displayChapters.size} of " +
                                    "${state.chapters.size} chapters",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        // Patch B (gap 5): a load-more attempt
                        // that FAILED gets the plain-words retry
                        // row — the shared page's one allowed
                        // surfacing of a failed grow (partiality
                        // itself stays invisible). This replaces
                        // the bare error text below for exactly
                        // this state.
                        if (section.kind ==
                            ChaptersSectionKind.LIST &&
                            !book.chapterListComplete &&
                            state.chaptersError != null
                        ) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = colors.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 8.dp
                                    ),
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "More chapters couldn't be " +
                                            "loaded — the source " +
                                            "didn't answer.",
                                        style = MaterialTheme.typography
                                            .bodySmall,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            viewModel.loadFullChapterList()
                                        }
                                    ) {
                                        Text("Try again")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Patch B (gap 6): the "no chapters match" empty
            // state with the one-tap way out — the shared
            // page's state, in this face's language. (It sits
            // before the chapter items; it renders only when
            // the filtered list is empty, so it never shifts
            // the jump's row-prefix count on a real jump.)
            if (section.kind == ChaptersSectionKind.LIST &&
                visibleChapters.isEmpty() &&
                state.chapters.isNotEmpty() &&
                localOutline.isEmpty()
            ) {
                item(key = "chapters-filter-empty") {
                    Column(
                        Modifier.padding(
                            start = 20.dp, end = 20.dp, top = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "No chapters match that search or filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                chapterQuery = ""
                                jumpFieldText = ""
                                jumpTargetId = null
                                pendingJumpPos = -1
                                chapterStateFilter =
                                    app.xreader.domain.reader
                                        .ChapterListFilter.State.ALL
                                chapterPartFilter = null
                            }
                        ) { Text("Clear search & filters") }
                    }
                }
            }
            if (localOutline.isNotEmpty()) {
                items(localOutline, key = { "toc-${it.id}" }) { entry ->
                    EditorialContentsRow(
                        number = entry.idx + 1,
                        title = entry.title,
                        read = false,
                        current = false,
                        onClick = {
                            if (onReadLocalTarget != null) {
                                onReadLocalTarget(book.id, entry.targetJson)
                            } else {
                                onReadChapter(state.continueChapterIndex)
                            }
                        }
                    )
                }
            } else {
                items(displayedChapters, key = { "ch-${it.id}" }) { chapter ->
                    // Patch B (gap 7): the row's full
                    // information set, computed exactly as the
                    // shared page computes it for ChapterRow —
                    // proven word count (0 for stub bodies),
                    // ≈min / ≈min left at the reader's pace,
                    // the NEW verdict, the current percent.
                    val displayWordCount =
                        app.xreader.data.download.ChapterTruth152
                            .displayWordCount(
                                chapter, state.stubChapterIds
                            )
                    EditorialContentsRow(
                        number = ChapterDisplayOrder
                            .displayNumber(chapter)?.toIntOrNull()
                            ?: (chapter.index + 1),
                        title = chapter.title,
                        read = chapter.read,
                        current = chapter.index ==
                            state.continueChapterIndex &&
                            state.progress != null,
                        highlighted = chapter.id == jumpTargetId,
                        isNew = chapter.index == maxChapterIndex &&
                            !chapter.read && state.chapters.size > 1,
                        currentPercent = if (
                            chapter.index == state.continueChapterIndex
                        ) {
                            (state.progress?.fraction?.times(100))
                                ?.toInt()
                        } else {
                            null
                        },
                        isStubBody = chapter.id in state.stubChapterIds,
                        provenWordCount = displayWordCount,
                        minutesLeft = if (displayWordCount > 0) {
                            (displayWordCount / state.wpm).toInt()
                                .coerceAtLeast(1)
                        } else {
                            null
                        },
                        hasStartedReading = hasStartedReading(
                            state.progress?.fraction,
                            state.chapters.any { it.read }
                        ),
                        rowMark = app.xreader.data.download
                            .ChapterTruth152.rowMark(chapter),
                        scanlator = chapter.scanlator,
                        downloadState = chapter.downloadState,
                        canDownload = !chapter.remoteId.isNullOrBlank(),
                        dateText = chapter.dateText,
                        onDownload = { viewModel.downloadChapter(chapter) },
                        onClick = { onReadChapter(chapter.index) }
                    )
                }
                // Patch B (gap 5): the window's end is a
                // SENTINEL, not a button — composing it grows
                // the window by 200 and reports the new window
                // end to the same near-edge growth the visible
                // rows trigger, so reveal and fetch-streaming
                // are driven by the scroll itself.
                if (chapterWindow < displayChapters.size) {
                    item(key = "chapters-window-more") {
                        LaunchedEffect(Unit) {
                            chapterWindow += 200
                            viewModel.onChapterNearEdge(chapterWindow)
                        }
                        Text(
                            "Showing ${displayedChapters.size} of " +
                                "${displayChapters.size} chapters",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(
                                horizontal = 20.dp, vertical = 10.dp
                            )
                        )
                    }
                }
            }
            if (section.showRecoveryDoors && localOutline.isEmpty()) {
                item(key = "contents-recovery") {
                    val uriHandler = androidx.compose.ui.platform
                        .LocalUriHandler.current
                    Surface(
                        color = colors.surfaceContainerLow,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                if (section.kind ==
                                    ChaptersSectionKind.ERROR
                                ) {
                                    "The chapter list couldn't be " +
                                        "loaded. " +
                                        (state.chaptersError ?: "")
                                } else {
                                    "No chapters were found on the " +
                                        "source — the book is in your " +
                                        "library, but its chapter list " +
                                        "came back empty."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (section.kind ==
                                    ChaptersSectionKind.ERROR
                                ) {
                                    colors.error
                                } else {
                                    colors.onSurfaceVariant
                                }
                            )
                            state.recheckNote?.let { note ->
                                Text(
                                    note,
                                    style = MaterialTheme
                                        .typography.bodyMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            if (state.recheckBusy) {
                                Row(
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        "Checking the source…",
                                        style = MaterialTheme
                                            .typography.bodyMedium,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                            }
                            if (state.sourceOff) {
                                EditorialActionPill(
                                    icon = Icons.Filled.Refresh,
                                    label = "Turn back on and try again",
                                    enabled = true,
                                    onClick = {
                                        viewModel.turnBackOnAndRetry()
                                    }
                                )
                            }
                            state.sessionNeed?.let { need ->
                                EditorialActionPill(
                                    icon = Icons.Filled.Check,
                                    label = when (need) {
                                        app.xreader.data.extension
                                            .SessionNeed.Kind.SITE_CHECK ->
                                            "Solve the check"
                                        app.xreader.data.extension
                                            .SessionNeed.Kind.SIGN_IN ->
                                            "Sign in to read"
                                    },
                                    enabled = true,
                                    onClick = {
                                        book.extensionId?.let {
                                            onSessionHandoff(it, need)
                                        }
                                    }
                                )
                            }
                            EditorialActionPill(
                                icon = Icons.Filled.Refresh,
                                label = if (state.recheckBusy) {
                                    "Re-checking…"
                                } else {
                                    "Re-check now"
                                },
                                enabled = !state.recheckBusy,
                                onClick = { viewModel.recheck() }
                            )
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(4.dp)
                            ) {
                                if (section.canOpenSourcePage) {
                                    TextButton(
                                        onClick = {
                                            uriHandler.openUri(
                                                section.sourcePageUrl!!
                                            )
                                        }
                                    ) { Text("Open the source's page") }
                                }
                                if (section.canGoToRepair) {
                                    TextButton(
                                        onClick = {
                                            book.extensionId?.let {
                                                onOpenSourceDetails(it)
                                            }
                                        }
                                    ) { Text("Go to Repair") }
                                }
                                if (section.canSiteCheck) {
                                    TextButton(
                                        onClick = {
                                            book.extensionId?.let {
                                                onSiteCheck(it)
                                            }
                                        }
                                    ) { Text("Do the site check") }
                                }
                            }
                        }
                    }
                }
            } else if (state.chaptersError != null &&
                localOutline.isEmpty() &&
                // Patch B (gap 5): a failed grow on a partial
                // list is carried by the "Try again" row in
                // the contents tools now — never both.
                !(section.kind == ChaptersSectionKind.LIST &&
                    !book.chapterListComplete)
            ) {
                item(key = "contents-error") {
                    Text(
                        state.chaptersError ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                        modifier = Modifier.padding(
                            start = 20.dp, end = 20.dp, top = 8.dp
                        )
                    )
                }
            }

            // ── Code 124 (D1): the shared page's clusters,
            // restored — the SAME section composables the
            // Nocturne page renders, in this face's spacing:
            // Tags (+ AI suggestions), linked copies,
            // duplicates / other sources / move / merge,
            // Reviews + Comments + Readers also read, AI
            // similar titles, the book tools row and the
            // cleanup actions. One feature set, two faces.
            item(key = "ed-tags") {
                Column(
                    Modifier.padding(
                        start = 20.dp, end = 20.dp, top = 28.dp
                    )
                ) {
                    TagsSection(
                        state = state,
                        engineLabel = engineLabel,
                        onOpenTag = onOpenTag,
                        onAddTag = { viewModel.addTag(it) },
                        onRemoveTag = { viewModel.removeTag(it) },
                        onSuggest = { viewModel.suggestTagsFromSynopsis() },
                        onSuggestAi = { viewModel.suggestTagsWithAi() },
                        onAcceptSuggestion = {
                            viewModel.acceptTagSuggestion(it)
                        },
                        onDismissSuggestion = {
                            viewModel.dismissTagSuggestion(it)
                        }
                    )
                }
            }
            if (state.linkedCopies.isNotEmpty()) {
                item(key = "ed-progress-links") {
                    Column(
                        Modifier.padding(
                            start = 20.dp, end = 20.dp, top = 24.dp
                        )
                    ) {
                        ProgressLinkSection(
                            state = state,
                            onOpenCopy = { id -> onOpenRelated(id) },
                            onUnlink = { viewModel.unlinkCopy(it) }
                        )
                    }
                }
            }
            if (book.sourceType == SourceType.EXTENSION ||
                state.duplicates.isNotEmpty() ||
                state.mergedCopies.isNotEmpty()
            ) {
                item(key = "ed-sources-merge") {
                    Column(
                        Modifier.padding(
                            start = 20.dp, end = 20.dp, top = 24.dp
                        )
                    ) {
                        SourcesMergeSection(
                            state = state,
                            onLoadOtherSources = {
                                viewModel.loadOtherSources()
                            },
                            onOpenCopy = { id -> onOpenRelated(id) },
                            onPreviewMove = { viewModel.previewSwitch(it) },
                            onMerge = { viewModel.mergeDuplicate(it) },
                            onUnmerge = { viewModel.unmerge(it) }
                        )
                    }
                }
            }
            item(key = "ed-reviews") {
                Column(
                    Modifier.padding(
                        start = 20.dp, end = 20.dp, top = 24.dp
                    )
                ) {
                    EnrichmentSections(
                        state = state,
                        onOpenRelated = { related ->
                            viewModel.openRelated(related) { id ->
                                onOpenRelated(id)
                            }
                        }
                    )
                }
            }
            item(key = "ed-ai-suggestions") {
                Column(
                    Modifier.padding(
                        start = 20.dp, end = 20.dp, top = 24.dp
                    )
                ) {
                    AiSuggestionsSection(
                        state = state,
                        engineLabel = engineLabel,
                        onAsk = { viewModel.askAiSuggestions() },
                        onDismiss = { viewModel.dismissAiSuggestion(it) },
                        onFind = onFindTitle
                    )
                }
            }
            item(key = "ed-book-tools") {
                Column(
                    Modifier.padding(
                        start = 20.dp, end = 20.dp, top = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Book tools",
                        fontFamily = FrauncesSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = colors.onSurface
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        EditorialActionPill(
                            icon = Icons.Filled.Download,
                            label = "Export EPUB",
                            enabled = true,
                            onClick = onExportEpub
                        )
                        EditorialActionPill(
                            icon = Icons.Filled.Download,
                            label = "Export CBZ",
                            enabled = true,
                            onClick = onExportCbz
                        )
                        EditorialActionPill(
                            icon = Icons.Filled.AutoStories,
                            label = "Highlights (.md)",
                            enabled = true,
                            onClick = {
                                markdownLauncher.launch(
                                    (book.title.replace(
                                        Regex("[^A-Za-z0-9]+"), "-"
                                    ).trim('-') + "-highlights.md")
                                )
                            }
                        )
                        EditorialActionPill(
                            icon = Icons.Filled.Share,
                            label = "Copy details",
                            enabled = true,
                            onClick = {
                                clipboard.setText(
                                    androidx.compose.ui.text
                                        .AnnotatedString(
                                            app.xreader.ui.book
                                                .BookCopyDetails.build(
                                                    title = book.title,
                                                    author = book.author,
                                                    chapterCount =
                                                        state.chapters.size,
                                                    downloadedCount =
                                                        state.downloadedCount,
                                                    statusLabel =
                                                        editorialStatusLabel(
                                                            state.bookMeta
                                                                .status
                                                        ),
                                                    description =
                                                        book.description,
                                                    chaptersError =
                                                        state.chaptersError,
                                                    isFenrirBook =
                                                        app.xreader.ui.book
                                                            .BookCopyDetails
                                                            .isFenrir(
                                                                state
                                                                    .providerName
                                                            ),
                                                    logEntries =
                                                        app.xreader
                                                            .data.extension
                                                            .DiagnosticLog
                                                            .entries()
                                                )
                                        )
                                )
                                viewModel.notifyMessage(
                                    "Book details copied"
                                )
                            }
                        )
                    }
                    if (book.description.isNullOrBlank() &&
                        book.sourceType == SourceType.EXTENSION
                    ) {
                        TextButton(
                            onClick = {
                                viewModel.findDetailsFromSources()
                            }
                        ) {
                            Text("Find details from a source")
                        }
                    }
                }
            }
            item(key = "ed-cleanup") {
                Column(
                    Modifier.padding(
                        start = 20.dp, end = 20.dp, top = 24.dp
                    )
                ) {
                    BookCleanupActions(
                        viewModel = viewModel,
                        bookTitle = book.title,
                        isLocalFile = book.sourceType ==
                            SourceType.LOCAL_FILE,
                        onRemoved = onBack,
                        confirmRemove = confirmRemove,
                        onConfirmRemoveChange = { confirmRemove = it }
                    )
                }
            }
        }

        // ── The docked action bar ──────────────────────────────
        Surface(
            color = colors.surfaceContainer,
            shadowElevation = 12.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = {
                    viewModel.setTracked(!state.bookMeta.tracked)
                }) {
                    Icon(
                        if (state.bookMeta.tracked) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.RadioButtonUnchecked
                        },
                        contentDescription = if (state.bookMeta.tracked) {
                            "Tracked — tap to stop tracking"
                        } else {
                            "Track this book"
                        },
                        tint = if (state.bookMeta.tracked) {
                            colors.primary
                        } else {
                            colors.onSurfaceVariant
                        },
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = {
                    viewModel.setFavourite(!state.favourite)
                }) {
                    Icon(
                        if (state.favourite) {
                            Icons.Filled.Bookmark
                        } else {
                            Icons.Filled.BookmarkBorder
                        },
                        contentDescription = if (state.favourite) {
                            "Remove from favourites"
                        } else {
                            "Add to favourites"
                        },
                        tint = if (state.favourite) {
                            colors.primary
                        } else {
                            colors.onSurfaceVariant
                        },
                        modifier = Modifier.size(26.dp)
                    )
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "${book.title} by ${book.author}"
                        )
                    }
                    context.startActivity(
                        Intent.createChooser(send, "Share book")
                    )
                }) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                // Patch B (gap 13): the Continue honesty states.
                // The SplitButton FORM is deliberate and stays
                // for the ready state — but on the finding /
                // stalled / unavailable states the shared page
                // swaps in the inert unavailable pill (with a
                // visible Retry once the wait crosses the honest
                // bound), and this face must too: a blocked read
                // never renders as a live Continue here.
                when (state.readCtaState) {
                    ReadCtaState.CHAPTERS_UNAVAILABLE -> {
                        EditorialUnavailablePill(
                            reason = state.readBlockedReason!!,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    ReadCtaState.FINDING_CHAPTERS -> {
                        Column(Modifier.weight(1f)) {
                            EditorialUnavailablePill(
                                title = "Finding chapters…",
                                reason = when {
                                    state.challengePending ->
                                        "Waiting for the site's check…"
                                    state.chaptersStalled ->
                                        "Still finding — " +
                                            "this source is slow"
                                    else ->
                                        "The chapter list is still " +
                                            "loading from the source."
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (state.chaptersStalled &&
                                !state.challengePending
                            ) {
                                TextButton(
                                    onClick = {
                                        viewModel.refreshChapters()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                    ReadCtaState.READY -> {
                Spacer(Modifier.weight(1f))
                // Code 124 (E4): the Read door as the official
                // SplitButton — main = Read / Continue, the
                // chevron carries the Save-as family. The
                // Download doors stay separately visible at
                // the Contents header (A2/A3).
                Box {
                    androidx.compose.material3.SplitButtonLayout(
                        leadingButton = {
                            androidx.compose.material3.SplitButtonDefaults
                                .LeadingButton(
                                    onClick = {
                                        onReadChapter(
                                            state.continueChapterIndex
                                        )
                                    },
                                    enabled =
                                        state.readBlockedReason == null,
                                    modifier = Modifier.height(56.dp),
                                    colors = androidx.compose.material3
                                        .ButtonDefaults.buttonColors(
                                            containerColor = colors.primary,
                                            contentColor = colors.onPrimary
                                        )
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (state.progress == null) {
                                            "Start reading"
                                        } else {
                                            "Continue"
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                        },
                        trailingButton = {
                            androidx.compose.material3.SplitButtonDefaults
                                .TrailingButton(
                                    onClick = { exportMenuOpen = true },
                                    enabled = canSaveEpub ||
                                        book.localFormat == BookFormat.CBZ,
                                    modifier = Modifier.height(56.dp),
                                    colors = androidx.compose.material3
                                        .ButtonDefaults.buttonColors(
                                            containerColor = colors.primary,
                                            contentColor = colors.onPrimary
                                        )
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Save as…",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                        }
                    )
                    DropdownMenu(
                        expanded = exportMenuOpen,
                        onDismissRequest = { exportMenuOpen = false }
                    ) {
                        if (canSaveEpub) {
                            DropdownMenuItem(
                                text = { Text("Save as EPUB") },
                                onClick = {
                                    exportMenuOpen = false
                                    onExportEpub()
                                }
                            )
                        }
                        if (book.localFormat == BookFormat.CBZ) {
                            DropdownMenuItem(
                                text = { Text("Save as CBZ") },
                                onClick = {
                                    exportMenuOpen = false
                                    onExportCbz()
                                }
                            )
                        }
                    }
                }
                    }
                }
            }
        }
    }

    // The story-so-far recap (the shared page's dialog, in the
    // Editorial dress).
    if (state.storyOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissStory() },
            title = { Text("Catch me up — the story so far") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        state.storyBusy ->
                            Text("Reading your summaries…")
                        state.storyError != null -> Text(
                            state.storyError ?: "",
                            color = colors.error
                        )
                        else -> state.storyBullets?.forEach { bullet ->
                            Text(
                                "• $bullet",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissStory() }) {
                    Text("Done")
                }
            }
        )
    }

    // The un-favourite prompt (shared state, Editorial dress).
    if (state.askRemoveOnUnfavourite) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnfavouriteRemove() },
            title = { Text("Also remove from library?") },
            text = {
                Text(
                    "Favourites was this book's only claim on " +
                        "your library. Remove the book as well?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmUnfavouriteRemove { onBack() }
                    }
                ) { Text("Yes, remove it") }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissUnfavouriteRemove() }
                ) { Text("No, keep it") }
            }
        )
    }

    // Patch B (gap 2): the deliberate-switch preview — the
    // shared page's confirm dialog in Material 3 language.
    // SourcesMergeSection above already wires onPreviewMove
    // to the VM; until now nothing on this face rendered the
    // preview, so a move could be started but never confirmed.
    // Nothing has changed yet; the numbers here are exactly
    // what "Move this book" will do.
    val preview = state.switchPreview
    val switchCandidate = state.switchCandidate
    if (preview != null && switchCandidate != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSwitch() },
            title = { Text("Move to ${switchCandidate.sourceName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${preview.matchedChapters} of your chapters match " +
                            "on the new source (${preview.newSourceChapters} there).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (preview.progressChapterIndex >= 0) {
                        Text(
                            "Your place lands at chapter " +
                                "${preview.progressChapterIndex + 1}" +
                                if (preview.progressApproximate) {
                                    " (the nearest earlier match)."
                                } else {
                                    "."
                                },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "Downloaded chapters stay with the old copy — " +
                            "you can download again on the new source. " +
                            "The old copy is kept, merged, and can be " +
                            "brought back any time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                    if (!preview.canSwitch) {
                        Text(
                            "Too few chapters match to move safely — " +
                                "this move isn't available.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmSwitch()
                        onBack()
                    },
                    enabled = preview.canSwitch
                ) { Text("Move this book") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSwitch() }) {
                    Text("Stay here")
                }
            }
        )
    }

    // Patch B (gap 3): the Download choices sheet — the three
    // ranges a reader actually picks, each showing the count
    // it will queue (BookDownloadRanges computes both the
    // sheet's numbers and the tap's queue, so they can't
    // disagree). Material 3 form: a modal sheet of list rows.
    if (downloadSheetOpen) {
        val counts = BookDownloadRanges.counts(state.chapters)
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { downloadSheetOpen = false },
            sheetState = androidx.compose.material3
                .rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Download chapters",
                    style = MaterialTheme.typography.titleMedium
                )
                EditorialDownloadChoiceRow(
                    icon = Icons.Filled.Download,
                    label = "Whole book",
                    caption = "${counts[BookDownloadChoice.ALL] ?: 0} " +
                        "chapters not on this phone yet",
                    enabled = (counts[BookDownloadChoice.ALL] ?: 0) > 0,
                    onClick = {
                        downloadSheetOpen = false
                        viewModel.downloadChoice(BookDownloadChoice.ALL)
                    }
                )
                EditorialDownloadChoiceRow(
                    icon = Icons.Filled.FastForward,
                    label = "Next ${BookDownloadRanges.NEXT_COUNT}",
                    caption = "The next " +
                        "${counts[BookDownloadChoice.NEXT_10] ?: 0} " +
                        "chapters in reading order",
                    enabled = (counts[BookDownloadChoice.NEXT_10] ?: 0) > 0,
                    onClick = {
                        downloadSheetOpen = false
                        viewModel.downloadChoice(BookDownloadChoice.NEXT_10)
                    }
                )
                EditorialDownloadChoiceRow(
                    icon = Icons.Filled.FiberNew,
                    label = "Up to the latest unread",
                    caption = "${counts[BookDownloadChoice.UP_TO_LATEST_UNREAD] ?: 0} " +
                        "chapters you haven't read yet",
                    enabled = (counts[
                        BookDownloadChoice.UP_TO_LATEST_UNREAD
                    ] ?: 0) > 0,
                    onClick = {
                        downloadSheetOpen = false
                        viewModel.downloadChoice(
                            BookDownloadChoice.UP_TO_LATEST_UNREAD
                        )
                    }
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Remove: the soft door and the delete door, both honest.
    if (removeDialogOpen) {
        AlertDialog(
            onDismissRequest = { removeDialogOpen = false },
            title = { Text("Remove ${book.title}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Remove from library keeps your place, " +
                            "bookmarks and downloads — the book " +
                            "leaves your shelves and can come back."
                    )
                    Text(
                        "Delete removes the book and its " +
                            "downloaded files from this phone."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeDialogOpen = false
                        viewModel.removeFromLibrary(false) { onBack() }
                    }
                ) { Text("Remove from library") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            removeDialogOpen = false
                            viewModel.removeFromLibrary(true) { onBack() }
                        }
                    ) {
                        Text(
                            "Delete book and files",
                            color = colors.error
                        )
                    }
                    TextButton(onClick = { removeDialogOpen = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    // Code 125 (F9): the Ask AI sheet host is gone — the AI
    // doors navigate to the full Ask AI page now.
    // D1 (re-check fix): the chapter-settings sheet — the
    // shared page's own controls (BookDetailScreen's J2/E3
    // sheet) bound to THIS page's list state: order ->
    // newestFirst, show -> chapterStateFilter, scanlators ->
    // the view model's per-book exclusion set.
    if (chapterSettingsOpen) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { chapterSettingsOpen = false },
            sheetState = androidx.compose.material3
                .rememberModalBottomSheetState(
                    skipPartiallyExpanded = true
                )
        ) {
            Column(
                Modifier
                    .verticalScroll(
                        androidx.compose.foundation
                            .rememberScrollState()
                    )
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Chapter settings",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Order",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !newestFirst,
                        onClick = { newestFirst = false },
                        label = { Text("Oldest first") }
                    )
                    FilterChip(
                        selected = newestFirst,
                        onClick = { newestFirst = true },
                        label = { Text("Newest first") }
                    )
                }
                Text(
                    "Show",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = chapterStateFilter ==
                            app.xreader.domain.reader
                                .ChapterListFilter.State.ALL,
                        onClick = {
                            chapterStateFilter =
                                app.xreader.domain.reader
                                    .ChapterListFilter.State.ALL
                        },
                        label = { Text("All chapters") }
                    )
                    FilterChip(
                        selected = chapterStateFilter ==
                            app.xreader.domain.reader
                                .ChapterListFilter.State.UNREAD,
                        onClick = {
                            chapterStateFilter =
                                app.xreader.domain.reader
                                    .ChapterListFilter.State.UNREAD
                        },
                        label = { Text("Unread only") }
                    )
                    FilterChip(
                        selected = chapterStateFilter ==
                            app.xreader.domain.reader
                                .ChapterListFilter.State.DOWNLOADED,
                        onClick = {
                            chapterStateFilter =
                                app.xreader.domain.reader
                                    .ChapterListFilter.State.DOWNLOADED
                        },
                        label = { Text("Downloaded only") }
                    )
                }
                if (state.allScanlators.isNotEmpty()) {
                    Text(
                        "Scanlators",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary
                    )
                    Text(
                        "Untick a scanlator to hide its chapters " +
                            "from this book's list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    state.allScanlators.forEach { scanlator ->
                        val included =
                            scanlator !in state.excludedScanlators
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setExcludedScanlators(
                                        if (included) {
                                            state.excludedScanlators +
                                                scanlator
                                        } else {
                                            state.excludedScanlators -
                                                scanlator
                                        }
                                    )
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = included,
                                onCheckedChange = { nowIncluded ->
                                    viewModel.setExcludedScanlators(
                                        if (nowIncluded) {
                                            state.excludedScanlators -
                                                scanlator
                                        } else {
                                            state.excludedScanlators +
                                                scanlator
                                        }
                                    )
                                }
                            )
                            Text(
                                scanlator,
                                style = MaterialTheme.typography
                                    .bodyMedium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        }
    }
    // The full-screen cover viewer (K14): tap the cover, view
    // it large; drag down or tap to dismiss.
    if (coverViewerOpen) {
        val cover = coverFile(book.coverPath)
        Dialog(
            onDismissRequest = { coverViewerOpen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { coverViewerOpen = false },
                contentAlignment = Alignment.Center
            ) {
                // Code 130: one drag-carrying modifier for
                // whichever cover form renders — the real
                // image (file, else URL) or, when there is no
                // cover to show, the designed title
                // placeholder at poster size. A missing cover
                // never leaves this viewer a black screen.
                val coverModifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .offset {
                        IntOffset(0, drag.value.roundToInt())
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (kotlin.math.abs(drag.value) >
                                    140f
                                ) {
                                    coverViewerOpen = false
                                } else {
                                    scope.launch {
                                        drag.animateTo(
                                            0f,
                                            // Code-129 motion
                                            // pass: the rebound
                                            // rides the engine's
                                            // Spatial spring, not
                                            // an ad-hoc Compose
                                            // stiffness constant.
                                            app.xreader.core.motion
                                                .SpringMotionEngine
                                                .Spatial.toSpec()
                                        )
                                    }
                                }
                            },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                scope.launch {
                                    drag.snapTo(drag.value + amount)
                                }
                            }
                        )
                    }
                when {
                    cover != null && cover.exists() ->
                        coil.compose.SubcomposeAsyncImage(
                            model = cover,
                            contentDescription = book.title,
                            contentScale = ContentScale.Fit,
                            modifier = coverModifier,
                            loading = {
                                app.xreader.ui.library
                                    .PlaceholderCover(
                                        title = book.title,
                                        author = book.author,
                                        modifier = Modifier.fillMaxSize()
                                    )
                            },
                            error = {
                                app.xreader.ui.library
                                    .PlaceholderCover(
                                        title = book.title,
                                        author = book.author,
                                        modifier = Modifier.fillMaxSize()
                                    )
                            }
                        )
                    !book.coverUrl.isNullOrBlank() ->
                        coil.compose.SubcomposeAsyncImage(
                            model = book.coverUrl,
                            contentDescription = book.title,
                            contentScale = ContentScale.Fit,
                            modifier = coverModifier,
                            loading = {
                                app.xreader.ui.library
                                    .PlaceholderCover(
                                        title = book.title,
                                        author = book.author,
                                        modifier = Modifier.fillMaxSize()
                                    )
                            },
                            error = {
                                app.xreader.ui.library
                                    .PlaceholderCover(
                                        title = book.title,
                                        author = book.author,
                                        modifier = Modifier.fillMaxSize()
                                    )
                            }
                        )
                    else ->
                        app.xreader.ui.library.PlaceholderCover(
                            title = book.title,
                            author = book.author,
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .aspectRatio(124f / 180f)
                                .offset {
                                    IntOffset(
                                        0, drag.value.roundToInt()
                                    )
                                }
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (kotlin.math.abs(
                                                    drag.value
                                                ) > 140f
                                            ) {
                                                coverViewerOpen = false
                                            } else {
                                                scope.launch {
                                                    drag.animateTo(
                                                        0f,
                                                        app.xreader.core
                                                            .motion
                                                            .SpringMotionEngine
                                                            .Spatial
                                                            .toSpec()
                                                    )
                                                }
                                            }
                                        },
                                        onVerticalDrag = {
                                                change, amount ->
                                            change.consume()
                                            scope.launch {
                                                drag.snapTo(
                                                    drag.value + amount
                                                )
                                            }
                                        }
                                    )
                                }
                        )
                }
                IconButton(
                    onClick = { coverViewerOpen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorialBandChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EditorialDetailTile(
    title: String,
    sub: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color,
    onContainer: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = container,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .height(96.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    sub,
                    fontSize = 12.sp,
                    color = onContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun EditorialContentsRow(
    number: Int,
    title: String,
    read: Boolean,
    current: Boolean,
    onClick: () -> Unit,
    /** Patch B (gap 4): a jump landed on this row — it takes
     * the current chapter's treatment while the highlight
     * lasts. */
    highlighted: Boolean = false,
    /** Patch B (gap 7): the shared page's ChapterRow
     * information set — the NEW verdict, the current
     * chapter's percent, the stub-body lock, the PROVEN word
     * count (0 for stubs), ≈min at the reader's pace, whether
     * reading has begun (gates "≈min left"), the §12.10 row
     * mark (Downloaded vs Cached), and the scanlator credit.
     * Defaults keep the local-outline rows unchanged. */
    isNew: Boolean = false,
    currentPercent: Int? = null,
    isStubBody: Boolean = false,
    provenWordCount: Int = 0,
    minutesLeft: Int? = null,
    hasStartedReading: Boolean = false,
    rowMark: app.xreader.data.download.ChapterTruth152.RowMark? = null,
    scanlator: String? = null,
    /** Code 124 (A2): this chapter's download state — the
     * same DownloadState the shared page's ChapterRow keys
     * its trailing button on. Null = a local-outline row,
     * which carries no download door. */
    downloadState: app.xreader.data.db.DownloadState? = null,
    canDownload: Boolean = false,
    onDownload: () -> Unit = {},
    /** Code 125 (F5): the release date the source printed
     * for this chapter ([Chapter.dateText]) — null when the
     * source prints none, and then no date line renders at
     * all (never an invented date). The shared page's
     * ChapterRow and M3ChapterRow already carried it; this
     * row is the one that didn't. */
    dateText: String? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            color = if (current || highlighted) {
                colors.primaryContainer
            } else {
                colors.surfaceContainerHigh
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$number",
                    fontFamily = FrauncesSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = if (current || highlighted) {
                        colors.onPrimaryContainer
                    } else {
                        colors.onSurfaceVariant
                    }
                )
            }
        }
        // Code 125 (F5): the release date, small under the
        // title — the source's own date, relatively worded
        // by the same ChapterDateText the other rows use
        // (null when the source prints none: no line at all).
        val dateLine = remember(dateText) {
            app.xreader.domain.reader.ChapterDateText
                .relative(dateText)
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (current || highlighted) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    color = if (read && !current) {
                        colors.onSurfaceVariant
                    } else {
                        colors.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // Patch B (gap 7): the "Current · N%" and
                // "NEW" chips, the shared page's verdicts in
                // this face's chip language.
                if (current) {
                    Surface(
                        shape = CircleShape,
                        color = colors.secondaryContainer
                    ) {
                        Text(
                            "Current" + (currentPercent?.let {
                                " · $it%"
                            } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.padding(
                                horizontal = 6.dp, vertical = 2.dp
                            )
                        )
                    }
                } else if (isNew) {
                    Surface(
                        shape = CircleShape,
                        color = colors.primaryContainer
                    ) {
                        Text(
                            "NEW",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onPrimaryContainer,
                            modifier = Modifier.padding(
                                horizontal = 6.dp, vertical = 2.dp
                            )
                        )
                    }
                }
            }
            // Patch B (gap 7): the status line — the shared
            // page's ChapterRow derivation, verbatim: the
            // stub-body lock, Read · Downloaded/Cached,
            // Continue here (+ ≈min left once reading has
            // begun), or the word count with ≈min — joined
            // dot-separated with the release date and the
            // scanlator credit, each absent when the source
            // offers none.
            val statusLine = when {
                isStubBody -> "Locked — sign in to read this chapter"
                read -> "Read" + when (rowMark) {
                    app.xreader.data.download.ChapterTruth152
                        .RowMark.DOWNLOADED -> " · Downloaded"
                    app.xreader.data.download.ChapterTruth152
                        .RowMark.CACHED -> " · Cached"
                    else -> ""
                }
                current -> "Continue here" +
                    (if (hasStartedReading) {
                        minutesLeft?.let { " · ≈$it min left" } ?: ""
                    } else {
                        ""
                    })
                provenWordCount > 0 -> "$provenWordCount words" +
                    (minutesLeft?.let { " · ≈$it min" } ?: "")
                else -> ""
            }
            val subtitle = listOfNotNull(
                statusLine.ifEmpty { null },
                dateLine,
                scanlator?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (read) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Read",
                tint = colors.tertiary,
                modifier = Modifier.size(16.dp)
            )
        }
        // A2: the per-chapter download button, on every
        // chapter row, with its live state.
        when (downloadState) {
            // Patch B (gap 7): §12.10's marks — Cached
            // (automatic + on device) is the HOLLOW circle,
            // Downloaded (the reader's own act) the filled
            // one; the two are never the same glyph again.
            app.xreader.data.db.DownloadState.READY -> if (
                rowMark == app.xreader.data.download.ChapterTruth152
                    .RowMark.CACHED
            ) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Cached",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = colors.tertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
            app.xreader.data.db.DownloadState.DOWNLOADING,
            app.xreader.data.db.DownloadState.QUEUED -> Icon(
                Icons.Filled.Schedule,
                contentDescription = "Queued",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            app.xreader.data.db.DownloadState.FAILED -> IconButton(
                onClick = onDownload,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = "Download failed — retry",
                    tint = colors.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            app.xreader.data.db.DownloadState.PARTIAL -> IconButton(
                onClick = onDownload,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription =
                        "Partly downloaded — download the rest",
                    tint = colors.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            app.xreader.data.db.DownloadState.NONE -> {
                if (canDownload) {
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Download chapter",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            null -> Unit
        }
    }
}

/** Patch B (gap 13): the Continue honesty pill in Material 3
 * language — an inert, tonal pill that REPLACES the live
 * SplitButton while chapters are finding / stalled /
 * unavailable, carrying the reason itself. The headline
 * follows the state: "Chapters unavailable" only on a settled
 * failure; the finding headline while a fetch is in flight. */
@Composable
private fun EditorialUnavailablePill(
    reason: String,
    modifier: Modifier = Modifier,
    title: String = "Chapters unavailable"
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurfaceVariant
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
        }
    }
}

/** Patch B (gap 3): one Download-choices row in Material 3
 * language — a tonal list item with the range's icon, its
 * plain-words label and the live count it will queue. */
@Composable
private fun EditorialDownloadChoiceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    caption: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(caption) },
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) {
                    colors.primary
                } else {
                    colors.onSurfaceVariant
                }
            )
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = colors.surfaceContainer
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, onClick = onClick)
    )
}

/** The settled status names (D23), for Copy details —
 * the shared page's statusLabel table, verbatim. */
private fun editorialStatusLabel(
    status: ReadingStatus
): String? = when (status) {
    ReadingStatus.READING -> "Reading"
    ReadingStatus.COMPLETED -> "Completed"
    ReadingStatus.ON_HOLD -> "On hold"
    ReadingStatus.PLAN_TO_READ -> "Plan to read"
    ReadingStatus.DROPPED -> "Dropped"
    ReadingStatus.NONE -> null
}

/** Code 124 (A2/A3): one visible action pill in this
 * face's language — icon + whole label, never crushed. */
@Composable
private fun EditorialActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Code 125 (F11): the pill's side padding — 14 dp
     * everywhere except the Contents actions row, where
     * 10 dp buys the three pills the room to sit whole on
     * one 411 dp line (see that row). Every other caller
     * keeps the shipped 14 dp. */
    horizontalPadding: androidx.compose.ui.unit.Dp = 14.dp
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (enabled) {
            colors.secondaryContainer
        } else {
            colors.surfaceContainer
        },
        shape = CircleShape,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            Modifier.padding(
                horizontal = horizontalPadding,
                vertical = 9.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) {
                    colors.onSecondaryContainer
                } else {
                    colors.onSurfaceVariant
                },
                modifier = Modifier.size(17.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    colors.onSecondaryContainer
                } else {
                    colors.onSurfaceVariant
                },
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
