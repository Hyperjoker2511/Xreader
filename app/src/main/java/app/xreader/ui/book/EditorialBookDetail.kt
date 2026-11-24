package app.xreader.ui.book

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoStories
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
fun EditorialBookDetail(
    state: BookDetailUiState,
    localOutline: List<LocalOutline>,
    viewModel: BookDetailViewModel,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onReadChapter: (chapterIndex: Int) -> Unit,
    onReadLocalTarget: ((bookId: Long, targetJson: String) -> Unit)?,
    onOpenLore: () -> Unit,
    onExportEpub: () -> Unit,
    onExportCbz: () -> Unit
) {
    val book = state.book
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    var coverViewerOpen by remember { mutableStateOf(false) }
    var removeDialogOpen by remember { mutableStateOf(false) }
    var headerMenuOpen by remember { mutableStateOf(false) }

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
        ReadingStatus.PLAN_TO_READ to "Plan",
        ReadingStatus.COMPLETED to "Completed",
        ReadingStatus.DROPPED to "Dropped"
    )

    Box(Modifier.fillMaxSize().background(colors.surface)) {
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
                                Box {
                                    IconButton(
                                        onClick = { headerMenuOpen = true }
                                    ) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = "More",
                                            tint = colors
                                                .onSecondaryContainer
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = headerMenuOpen,
                                        onDismissRequest = {
                                            headerMenuOpen = false
                                        }
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text("Refresh chapters")
                                            },
                                            onClick = {
                                                headerMenuOpen = false
                                                viewModel.refreshChapters()
                                            }
                                        )
                                        if (book.localFormat ==
                                            BookFormat.EPUB
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text("Export EPUB")
                                                },
                                                onClick = {
                                                    headerMenuOpen = false
                                                    onExportEpub()
                                                }
                                            )
                                        }
                                        if (book.localFormat ==
                                            BookFormat.CBZ
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text("Export CBZ")
                                                },
                                                onClick = {
                                                    headerMenuOpen = false
                                                    onExportCbz()
                                                }
                                            )
                                        }
                                    }
                                }
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
                                        fontSize = 12.sp,
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
                items(state.chapters, key = { "ch-${it.id}" }) { chapter ->
                    EditorialContentsRow(
                        number = ChapterDisplayOrder
                            .displayNumber(chapter)?.toIntOrNull()
                            ?: (chapter.index + 1),
                        title = chapter.title,
                        read = chapter.read,
                        current = chapter.index ==
                            state.continueChapterIndex &&
                            state.progress != null,
                        onClick = { onReadChapter(chapter.index) }
                    )
                }
            }
            if (state.chaptersError != null && localOutline.isEmpty()) {
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
                Surface(
                    color = colors.primary,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .height(56.dp)
                        .clickable(
                            enabled = state.readBlockedReason == null
                        ) {
                            onReadChapter(state.continueChapterIndex)
                        }
                ) {
                    Row(
                        Modifier.padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = colors.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            if (state.progress == null) {
                                "Start reading"
                            } else {
                                "Continue"
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = colors.onPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
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
    onClick: () -> Unit
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
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (read) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Read",
                tint = colors.tertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
