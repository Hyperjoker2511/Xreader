package app.xreader.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.library.HistoryBuilder
import app.xreader.di.AppContainer
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.rememberHoverEmptyState
import coil.compose.AsyncImage
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    var confirmClear by remember { mutableStateOf(false) }
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
                if (groups.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear history")
                    }
                }
            }
        )
        if (groups.isEmpty()) {
            EmptyState(
                title = "Nothing here yet",
                message = "Books you read will show up here, newest first.",
                modifier = Modifier.weight(1f),
                hover = rememberHoverEmptyState(reduceMotion)
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                groups.forEach { group ->
                    item(key = "day-${group.label}") {
                        Text(
                            group.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(group.entries, key = { "hist-${it.bookId}" }) { entry ->
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
                                    modifier = Modifier.size(width = 40.dp, height = 58.dp)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1
                                    )
                                    Text(
                                        "Chapter ${entry.chapterIndex + 1} · " +
                                            "${(entry.fraction * 100).toInt()}% through the book",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    LinearProgressIndicator(
                                        progress = { entry.fraction.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
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
