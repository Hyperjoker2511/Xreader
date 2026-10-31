package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.library.HistoryBuilder
import app.xreader.di.AppContainer
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.ProgressPillBar
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.common.rememberHoverEmptyState
import coil.compose.AsyncImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * v1.1.0 (§5.13 + Q11): the History Reading Pulse — today's real
 * aggregates, read from the SAME store Profile reads:
 * `daily_activity` (one row per local day, written by the session
 * recorder and paid into by [app.xreader.data.game.ExpService]).
 * minutesRead / chaptersFinished / expEarned are that row's own
 * fields — the exact values Profile's "today" surfaces use.
 * Books-today is counted in the screen from the Today group's
 * entries (ReadingProgress rows updated today) — no other book
 * count for "today" exists in the app, and none is invented.
 */
data class ReadingPulse(
    val minutesToday: Int = 0,
    val chaptersToday: Int = 0,
    val expToday: Long = 0
)

/**
 * History (v1.0.1): everything the reader has been reading, grouped
 * by day, newest first. Built from ReadingProgress rows (the exact
 * saved locator) + the HistoryPrefs sidecar for remove/clear — so
 * clearing history never touches progress, bookmarks or downloads.
 */
class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    /** Chapter titles resolved off the combine path (DAO reads). */
    private val chapterTitles =
        kotlinx.coroutines.flow.MutableStateFlow<Map<Pair<Long, Int>, String>>(emptyMap())

    init {
        viewModelScope.launch {
            container.database.readingProgressDao().observeRecent().collect { rows ->
                val resolved = mutableMapOf<Pair<Long, Int>, String>()
                for (row in rows) {
                    val chapter = runCatching {
                        container.database.chapterDao()
                            .getByIndex(row.bookId, row.chapterIndex)
                    }.getOrNull()
                    if (chapter != null) {
                        resolved[row.bookId to row.chapterIndex] = chapter.title
                    }
                }
                chapterTitles.value = resolved
            }
        }
    }

    val groups: StateFlow<List<HistoryBuilder.DayGroup>> = combine(
        container.database.readingProgressDao().observeRecent(),
        container.database.bookDao().observeShelf(),
        container.historyPrefs.visibility,
        chapterTitles
    ) { progress, books, visibility, titles ->
        val booksById = books.associateBy { it.id }
        val sources = progress.mapNotNull { row ->
            val book = booksById[row.bookId] ?: return@mapNotNull null
            HistoryBuilder.HistorySource(
                bookId = row.bookId.toString(),
                title = book.title,
                coverPath = book.coverPath,
                chapterTitle = titles[row.bookId to row.chapterIndex],
                chapterIndex = row.chapterIndex,
                fraction = row.fraction,
                updatedAtMillis = row.updatedAt
            )
        }
        HistoryBuilder.build(
            sources = sources,
            hiddenBookIds = visibility.hiddenBookIds,
            clearedBeforeMillis = visibility.clearedBeforeMillis,
            zone = ZoneId.systemDefault()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The Pulse's numbers: today's `daily_activity` row, observed
     * live — the same DAO + row ProfileViewModel reads for its
     * today stats (`dailyActivityDao().get(today)` there,
     * `observeRange(today, today)` here so the card stays current
     * while the screen is open).
     */
    val pulse: StateFlow<ReadingPulse> = run {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        container.database.dailyActivityDao().observeRange(today, today)
            .map { rows ->
                val row = rows.firstOrNull()
                if (row == null) {
                    ReadingPulse()
                } else {
                    ReadingPulse(
                        minutesToday = row.minutesRead,
                        chaptersToday = row.chaptersFinished,
                        expToday = row.expEarned
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingPulse())
    }

    fun removeEntry(bookId: String) {
        viewModelScope.launch {
            bookId.toLongOrNull()?.let { container.historyPrefs.hide(it) }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            container.historyPrefs.clearAll(System.currentTimeMillis())
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HistoryViewModel(container) as T
            }
    }
}

/** Plain relative time for a history entry — real field only
 * (HistorySource.updatedAtMillis). */
private fun timeAgo(updatedAtMillis: Long, nowMillis: Long): String {
    val minutes = ((nowMillis - updatedAtMillis) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "Just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 60L * 24L -> "${minutes / 60L} h ago"
        else -> "${minutes / (60L * 24L)} d ago"
    }
}

private fun formatMinutes(totalMinutes: Int): String = when {
    totalMinutes < 60 -> "$totalMinutes min"
    totalMinutes % 60 == 0 -> "${totalMinutes / 60} h"
    else -> "${totalMinutes / 60} h ${totalMinutes % 60} min"
}

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onOpenBook: (bookId: Long, chapterIndex: Int) -> Unit,
    /** v1.0.4 (D13): gates the empty-state hover (shell passes it). */
    reduceMotion: Boolean = false
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val pulse by viewModel.pulse.collectAsStateWithLifecycle()
    // Hoisted locals (run #54's lesson).
    val groupsNow = groups
    val pulseNow = pulse
    var confirmClear by remember { mutableStateOf(false) }
    var dayFilter by remember { mutableStateOf<String?>(null) }
    val booksToday = groupsNow.firstOrNull { it.label == "Today" }?.entries?.size ?: 0
    val visibleGroups = if (dayFilter == null) {
        groupsNow
    } else {
        groupsNow.filter { it.label == dayFilter }
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // v1.0.4 (D13): History is a bottom TAB — no back arrow
        // (the D13 mock's arrow is dropped, same ruling as Me).
        // The shared ScreenHeader carries the title; Clear history
        // stays visible in its action slot. [onBack] remains in the
        // signature for the shell's wiring compatibility.
        ScreenHeader(
            title = "History",
            eyebrow = "Xreader",
            action = {
                if (groupsNow.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear history")
                    }
                }
            }
        )
        if (groupsNow.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                message = "Books you read will show up here, newest first.",
                modifier = Modifier.weight(1f),
                hover = rememberHoverEmptyState(reduceMotion)
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                // ── Reading Pulse (Q11): real aggregates only —
                // see ReadingPulse's doc for the exact sources.
                item(key = "pulse") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionHeader(
                            title = "Reading pulse",
                            modifier = Modifier.fillMaxWidth(),
                            eyebrow = "Today"
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            PulseCell(
                                value = formatMinutes(pulseNow.minutesToday),
                                label = "reading today",
                                modifier = Modifier.weight(1f)
                            )
                            PulseCell(
                                value = "$booksToday",
                                label = if (booksToday == 1) "book today" else "books today",
                                modifier = Modifier.weight(1f)
                            )
                            PulseCell(
                                value = "${pulseNow.chaptersToday}",
                                label = "chapters today",
                                modifier = Modifier.weight(1f)
                            )
                            PulseCell(
                                value = "${pulseNow.expToday}",
                                label = "EXP today",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                // ── Day filter chips (§5.13): the builder's real
                // groups are Today / Yesterday / Earlier — the
                // chips filter those, nothing is relabelled.
                item(key = "filters") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NocturneChip(
                            label = "All",
                            modifier = Modifier,
                            selected = dayFilter == null,
                            dotColor = null,
                            onClick = { dayFilter = null }
                        )
                        groupsNow.forEach { group ->
                            NocturneChip(
                                label = group.label,
                                modifier = Modifier,
                                selected = dayFilter == group.label,
                                dotColor = null,
                                onClick = { dayFilter = group.label }
                            )
                        }
                    }
                }
                visibleGroups.forEach { group ->
                    item(key = "day-${group.label}") {
                        SectionHeader(title = group.label, modifier = Modifier.fillMaxWidth())
                    }
                    items(group.entries, key = { "hist-${it.bookId}" }) { entry ->
                        val compact = group.label == "Earlier"
                        GlassCard(
                            onClick = {
                                entry.bookId.toLongOrNull()?.let {
                                    onOpenBook(it, entry.chapterIndex)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            bouncePress = true
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AsyncImage(
                                    model = coverFile(entry.coverPath),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(width = 40.dp, height = 58.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            entry.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        NocturneChip(
                                            label = "Ch. ${entry.chapterIndex + 1}",
                                            modifier = Modifier,
                                            selected = false,
                                            dotColor = null,
                                            onClick = null
                                        )
                                    }
                                    val chapterTitleNow = entry.chapterTitle
                                    Text(
                                        if (chapterTitleNow.isNullOrBlank()) {
                                            "Chapter ${entry.chapterIndex + 1}"
                                        } else {
                                            chapterTitleNow
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    Text(
                                        "${timeAgo(entry.updatedAtMillis, System.currentTimeMillis())} · " +
                                            "${(entry.fraction * 100).toInt()}% through the book",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    ProgressPillBar(
                                        fraction = entry.fraction.coerceIn(0f, 1f),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (compact) {
                                    GhostPill(
                                        text = "Resume",
                                        modifier = Modifier,
                                        enabled = true,
                                        onClick = {
                                            entry.bookId.toLongOrNull()?.let {
                                                onOpenBook(it, entry.chapterIndex)
                                            }
                                        }
                                    )
                                } else {
                                    PrimaryPill(
                                        text = "Continue · Ch. ${entry.chapterIndex + 1}",
                                        modifier = Modifier,
                                        enabled = true,
                                        onClick = {
                                            entry.bookId.toLongOrNull()?.let {
                                                onOpenBook(it, entry.chapterIndex)
                                            }
                                        }
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                TextButton(
                                    onClick = { viewModel.removeEntry(entry.bookId) }
                                ) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = {
                Text(
                    "This clears the list on this screen only. Your place in " +
                        "every book, your bookmarks and your downloads stay " +
                        "exactly as they are."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("Clear history") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Keep it") }
            }
        )
    }
}

@Composable
private fun PulseCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
