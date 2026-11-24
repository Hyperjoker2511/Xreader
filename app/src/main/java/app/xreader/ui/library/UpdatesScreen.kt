package app.xreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.common.StatusPill
import app.xreader.ui.theme.NocturneOverline
import coil.compose.AsyncImage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import app.xreader.ui.theme.LocalUiMetrics

/**
 * The Updates page (v1.0.2, R20 Q2 addendum): QuickNovel-style —
 * books with unseen new chapters first (the `+N` numbers), then
 * everything else that changed recently, grouped by day. Lives
 * inside Library; the pull gesture checks silently, this page is
 * where the results are READ.
 */
@Composable
fun UpdatesScreen(
    viewModel: LibraryViewModel,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    /** Code 122 (R6, audit item #2): the M3 empty state's door
     * lands on Explore. The shell wires this to the Explore
     * tab; the default keeps older call sites compiling. */
    onOpenExplore: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Code 121 (R6): the Material 3 form — small top app bar,
    // segmented list items grouped by book, "New" badges. The
    // Nocturne composition below is unchanged.
    if (app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        M3UpdatesContent(
            viewModel = viewModel,
            coverFile = coverFile,
            onBack = onBack,
            onOpenBook = onOpenBook,
            onOpenExplore = onOpenExplore
        )
        return
    }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }

    // Manga programme (M3): the Updates page reads the
    // Library's active side — the update surface is filtered
    // by kind, so the Novels side's news and the Manga side's
    // news each stand on their own (the switch itself lives
    // on the Library page; this page follows it).
    val sideBooks = remember(state.books, state.bookKinds, state.librarySide) {
        app.xreader.data.library.LibrarySplit.onSide(
            state.books, state.librarySide
        ) { shelf ->
            app.xreader.data.library.LibrarySplit
                .kindOf(shelf.book.id, state.bookKinds)
        }
    }
    val withNew = remember(sideBooks, state.metas) {
        sideBooks
            .filter { (state.metas[it.book.id]?.newChapterCount ?: 0) > 0 }
            .sortedByDescending { state.metas[it.book.id]?.newChapterCount ?: 0 }
    }
    val recentByDay = remember(sideBooks, state.metas) {
        val newIds = withNew.map { it.book.id }.toSet()
        sideBooks
            .filter { it.book.id !in newIds }
            .mapNotNull { shelf ->
                val day = Instant.ofEpochMilli(shelf.book.updatedAt)
                    .atZone(zone).toLocalDate()
                if (day.isBefore(today.minusDays(7))) null else day to shelf
            }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareByDescending { it })
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        // Nocturne §5.2 header: overline + Jakarta headline, with
        // the Check now primary pill on the right.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "LIBRARY",
                    style = NocturneOverline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Updates", style = MaterialTheme.typography.headlineMedium)
            }
            PrimaryPill(
                text = if (state.checkingUpdates) "Checking…" else "Check now",
                enabled = !state.checkingUpdates,
                onClick = { viewModel.checkForUpdates() },
                modifier = Modifier,
            
            )
        }
        TextButton(onClick = onBack) { Text("← Library") }
        Spacer(Modifier.height(8.dp))

        // Nocturne §5.2 summary banner — only when there IS news,
        // and built from the real unseen counts.
        if (withNew.isNotEmpty()) {
            val totalNew = withNew.sumOf {
                state.metas[it.book.id]?.newChapterCount ?: 0
            }
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "$totalNew new chapter(s) across ${withNew.size} book(s)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "Tap a book to mark it seen and open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (withNew.isEmpty() && recentByDay.isEmpty()) {
            KitEmptyState(
                icon = Icons.Filled.CheckCircle,
                message = "All caught up — when a book you're reading gets " +
                    "new chapters, they'll be listed here with how many arrived.",
                actionText = "Check now",
                onAction = { viewModel.checkForUpdates() },
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            if (withNew.isNotEmpty()) {
                item(key = "new-header") {
                    SectionHeader(title = "New chapters",
    modifier = Modifier,
    eyebrow = null,
    actionText = null,
    onAction = null,

                    )
                }
                items(withNew, key = { "new-${it.book.id}" }) { shelf ->
                    UpdateRow(
                        shelf = shelf,
                        coverFile = coverFile,
                        badge = "+${state.metas[shelf.book.id]?.newChapterCount ?: 0}",
                        downloaded = state.downloadCounts[shelf.book.id]?.downloaded ?: 0,
                        onOpen = {
                            viewModel.markSeen(shelf.book.id)
                            onOpenBook(shelf.book.id)
                        }
                    )
                }
            }
            recentByDay.forEach { (day, shelves) ->
                item(key = "day-$day") {
                    SectionHeader(title = dayLabel(day, today),
    modifier = Modifier,
    eyebrow = null,
    actionText = null,
    onAction = null,

                    )
                }
                items(shelves, key = { "day-${it.book.id}" }) { shelf ->
                    UpdateRow(
                        shelf = shelf,
                        coverFile = coverFile,
                        badge = null,
                        downloaded = state.downloadCounts[shelf.book.id]?.downloaded ?: 0,
                        onOpen = { onOpenBook(shelf.book.id) }
                    )
                }
            }
        }
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

@Composable
private fun UpdateRow(
    shelf: ShelfBook,
    coverFile: (String?) -> File?,
    badge: String?,
    downloaded: Int,
    onOpen: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = coverFile(shelf.book.coverPath)
                    ?: shelf.book.coverUrl?.takeIf { it.isNotBlank() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 44.dp, height = 62.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    shelf.book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    listOfNotNull(
                        "${shelf.book.totalChapters} chapters",
                        if (downloaded > 0) "$downloaded downloaded" else null
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (badge != null) {
                // A readout pill, never a fake button (the row
                // itself is the tap target).
                StatusPill(
                    text = badge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

// ── Code 121 (R6): the Material 3 Chapter updates ──────────
// Segmented list items grouped by book. The app holds a new-
// chapter COUNT per book (BookMeta.newChapterCount), not the
// individual new chapters' titles, so each book's item carries
// its real count line and a "New" badge — no chapter rows are
// invented. Tapping a book marks it seen and opens it (the
// settled behaviour).
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3UpdatesContent(
    viewModel: LibraryViewModel,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenExplore: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val sideBooks = remember(state.books, state.bookKinds, state.librarySide) {
        app.xreader.data.library.LibrarySplit.onSide(
            state.books, state.librarySide
        ) { shelf ->
            app.xreader.data.library.LibrarySplit
                .kindOf(shelf.book.id, state.bookKinds)
        }
    }
    val withNew = remember(sideBooks, state.metas) {
        sideBooks
            .filter { (state.metas[it.book.id]?.newChapterCount ?: 0) > 0 }
            .sortedByDescending { state.metas[it.book.id]?.newChapterCount ?: 0 }
    }
    val recentByDay = remember(sideBooks, state.metas) {
        val newIds = withNew.map { it.book.id }.toSet()
        sideBooks
            .filter { it.book.id !in newIds }
            .mapNotNull { shelf ->
                val day = Instant.ofEpochMilli(shelf.book.updatedAt)
                    .atZone(zone).toLocalDate()
                if (day.isBefore(today.minusDays(7))) null else day to shelf
            }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareByDescending { it })
    }
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("New chapters") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.checkForUpdates() },
                        enabled = !state.checkingUpdates
                    ) {
                        Text(if (state.checkingUpdates) "Checking…" else "Check now")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (withNew.isEmpty() && recentByDay.isEmpty()) {
            // S40 empty state (R6's copy).
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "No new chapters",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "Your books are up to date.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Code 122 (R6): the empty state's door is
                // Explore, per the spec — "Check now" stays in
                // the top app bar above.
                androidx.compose.material3.Button(
                    onClick = onOpenExplore
                ) { Text("Explore") }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp
            )
        ) {
            if (withNew.isNotEmpty()) {
                item(key = "m3-new-group") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        withNew.forEachIndexed { index, shelf ->
                            val newCount =
                                state.metas[shelf.book.id]?.newChapterCount ?: 0
                            M3UpdateItem(
                                shelf = shelf,
                                coverFile = coverFile,
                                index = index,
                                count = withNew.size,
                                supporting = listOfNotNull(
                                    "$newCount new chapter(s)",
                                    "${shelf.book.totalChapters} chapters"
                                ).joinToString(" · "),
                                badge = "New",
                                onOpen = {
                                    viewModel.markSeen(shelf.book.id)
                                    onOpenBook(shelf.book.id)
                                }
                            )
                        }
                    }
                }
            }
            recentByDay.forEach { (day, shelves) ->
                item(key = "m3-day-$day") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            dayLabel(day, today),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        shelves.forEachIndexed { index, shelf ->
                            M3UpdateItem(
                                shelf = shelf,
                                coverFile = coverFile,
                                index = index,
                                count = shelves.size,
                                supporting = "${shelf.book.totalChapters} chapters",
                                badge = null,
                                onOpen = { onOpenBook(shelf.book.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun M3UpdateItem(
    shelf: ShelfBook,
    coverFile: (String?) -> File?,
    index: Int,
    count: Int,
    supporting: String,
    badge: String?,
    onOpen: () -> Unit
) {
    androidx.compose.material3.SegmentedListItem(
        onClick = onOpen,
        shapes = androidx.compose.material3.ListItemDefaults
            .segmentedShapes(index = index, count = count),
        leadingContent = {
            app.xreader.ui.common.M3CoverThumbnail(
                title = shelf.book.title,
                author = shelf.book.author.takeIf { it.isNotBlank() },
                coverModel = coverFile(shelf.book.coverPath)
                    ?.takeIf { it.exists() }
                    ?: shelf.book.coverUrl?.takeIf { it.isNotBlank() },
                modifier = Modifier.size(width = 36.dp, height = 52.dp)
            )
        },
        trailingContent = if (badge != null) {
            {
                androidx.compose.material3.Badge {
                    Text(badge)
                }
            }
        } else {
            null
        },
        supportingContent = { Text(supporting) }
    ) {
        Text(
            shelf.book.title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            maxLines = 1
        )
    }
}
