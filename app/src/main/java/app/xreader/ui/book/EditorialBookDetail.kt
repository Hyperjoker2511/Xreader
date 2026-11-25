package app.xreader.ui.book

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt
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
    val engineLabel by viewModel.engineLabel
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

    if (book == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loading) {
                CircularProgressIndicator(color = colors.primary)
            } else {
                Text(
                    "This book could not be opened.",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
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
                                    if (cover != null) {
                                        AsyncImage(
                                            model = cover,
                                            contentDescription =
                                                book.title,
                                            contentScale =
                                                ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
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
                                    Row(
                                        horizontalArrangement =
                                            Arrangement.spacedBy(8.dp)
                                    ) {
                                        EditorialBandChip(formatLabel)
                                        extentLabel?.let {
                                            EditorialBandChip(it)
                                        }
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
                                        viewModel.setReadingStatus(status)
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
                            EditorialActionPill(
                                icon = Icons.Filled.Download,
                                label = if (
                                    state.hasUndownloadedChapters
                                ) {
                                    "Download all"
                                } else {
                                    "Downloaded"
                                },
                                enabled = state.hasUndownloadedChapters,
                                horizontalPadding = 10.dp,
                                onClick = {
                                    viewModel.downloadAllChapters()
                                }
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
                                // The shared page's law: the
                                // entry sets the search — a
                                // number lands on the exact
                                // chapter through the filter's
                                // number rung.
                                chapterQuery = value.trim()
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
                items(displayChapters, key = { "ch-${it.id}" }) { chapter ->
                    EditorialContentsRow(
                        number = ChapterDisplayOrder
                            .displayNumber(chapter)?.toIntOrNull()
                            ?: (chapter.index + 1),
                        title = chapter.title,
                        read = chapter.read,
                        current = chapter.index ==
                            state.continueChapterIndex &&
                            state.progress != null,
                        downloadState = chapter.downloadState,
                        canDownload = !chapter.remoteId.isNullOrBlank(),
                        dateText = chapter.dateText,
                        onDownload = { viewModel.downloadChapter(chapter) },
                        onClick = { onReadChapter(chapter.index) }
                    )
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
                localOutline.isEmpty()
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
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = book.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
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
                                                    spring(
                                                        stiffness = Spring
                                                            .StiffnessMediumLow
                                                    )
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
            color = if (current) {
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
                    color = if (current) {
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
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) {
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
                overflow = TextOverflow.Ellipsis
            )
            dateLine?.let { date ->
                Text(
                    date,
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
            app.xreader.data.db.DownloadState.READY -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Downloaded",
                tint = colors.tertiary,
                modifier = Modifier.size(20.dp)
            )
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
