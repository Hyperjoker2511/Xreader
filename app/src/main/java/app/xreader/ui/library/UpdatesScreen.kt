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
    onOpenBook: (Long) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }

    val withNew = remember(state.books, state.metas) {
        state.books
            .filter { (state.metas[it.book.id]?.newChapterCount ?: 0) > 0 }
            .sortedByDescending { state.metas[it.book.id]?.newChapterCount ?: 0 }
    }
    val recentByDay = remember(state.books, state.metas) {
        val newIds = withNew.map { it.book.id }.toSet()
        state.books
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
            .padding(horizontal = 20.dp, vertical = 16.dp)
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
                model = coverFile(shelf.book.coverPath),
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
