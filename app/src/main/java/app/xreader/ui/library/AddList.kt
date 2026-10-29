package app.xreader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.xreader.di.AppContainer
import app.xreader.core.error.AppResult
import app.xreader.data.extension.SourceHandle
import app.xreader.data.extension.WorkMatcher
import app.xreader.extensionapi.SourceBook
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.XreaderPrimaryButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Add a list" (v1.0.2, R27): paste book titles (one per line),
 * pick the source to find them on, review the matches, add the
 * ones that look right. Nothing is added during the search step —
 * the review list IS the decision point, and every row says
 * plainly what happened to it.
 */

enum class AddListRowState { WAITING, SEARCHING, FOUND, NOT_FOUND, ADDED, FAILED }

data class AddListRow(
    val title: String,
    val state: AddListRowState = AddListRowState.WAITING,
    val match: SourceBook? = null,
    val note: String = ""
)

data class AddListUiState(
    val sources: List<SourceHandle> = emptyList(),
    val selectedSourceIndex: Int = 0,
    val rows: List<AddListRow> = emptyList(),
    val searching: Boolean = false
)

class AddListViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(AddListUiState())
    val state: StateFlow<AddListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val sources = container.extensionService.allSources()
            _state.value = _state.value.copy(sources = sources)
        }
    }

    fun selectSource(index: Int) {
        _state.value = _state.value.copy(selectedSourceIndex = index)
    }

    fun findMatches(text: String) {
        val titles = text.lines().map { it.trim() }
            .filter { it.isNotBlank() }.distinct()
        val handle = _state.value.sources
            .getOrNull(_state.value.selectedSourceIndex) ?: return
        if (titles.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                searching = true,
                rows = titles.map { AddListRow(it, AddListRowState.SEARCHING) }
            )
            val done = titles.map { title ->
                when (val result = container.extensionService.guarded(handle) {
                    it.search(title, 1)
                }) {
                    is AppResult.Ok -> {
                        val books = result.value.books
                        val best = books.firstOrNull { candidate ->
                            WorkMatcher.sameWork(
                                candidate.title, candidate.author ?: "",
                                title, ""
                            )
                        } ?: books.firstOrNull()
                        if (best != null) {
                            AddListRow(
                                title, AddListRowState.FOUND, best,
                                note = if (best.title.equals(title, true)) {
                                    ""
                                } else {
                                    "Closest match: “${best.title}”"
                                }
                            )
                        } else {
                            AddListRow(
                                title, AddListRowState.NOT_FOUND,
                                note = "Not found on ${handle.source.name}"
                            )
                        }
                    }
                    is AppResult.Err -> AddListRow(
                        title, AddListRowState.NOT_FOUND,
                        note = "The source couldn't be searched"
                    )
                }
            }
            _state.value = _state.value.copy(searching = false, rows = done)
        }
    }

    fun add(row: AddListRow) {
        val book = row.match ?: return
        val handle = _state.value.sources
            .getOrNull(_state.value.selectedSourceIndex) ?: return
        viewModelScope.launch {
            val outcome = when (
                container.sourceBookSync.openBook(handle, book)
            ) {
                is AppResult.Ok -> AddListRowState.ADDED
                is AppResult.Err -> AddListRowState.FAILED
            }
            _state.value = _state.value.copy(
                rows = _state.value.rows.map {
                    if (it.title == row.title) it.copy(state = outcome) else it
                }
            )
        }
    }

    fun addAllFound() {
        _state.value.rows
            .filter { it.state == AddListRowState.FOUND }
            .forEach { add(it) }
    }

    companion object {
        fun factory(container: AppContainer) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AddListViewModel(container) as T
            }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AddListScreen(viewModel: AddListViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        ScreenHeader(title = "Add a list", eyebrow = "Your library")
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste the books you want — one title per line. Pick the " +
                "source to look on; you'll review every match before " +
                "anything is added.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (state.sources.isEmpty()) {
            Text(
                "You need a source first — add one from Browse, then " +
                    "come back with your list.",
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            Text(
                "Look on:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // v1.0.5 (B3): a FlowRow — up to six source-name chips
            // in one fixed Row crushed long source names.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.sources.take(6).forEachIndexed { index, handle ->
                    androidx.compose.material3.FilterChip(
                        selected = state.selectedSourceIndex == index,
                        onClick = { viewModel.selectSource(index) },
                        label = { Text(handle.source.name) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Book titles, one per line") },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            XreaderPrimaryButton(
                text = if (state.searching) "Looking…" else "Find these books",
                onClick = { viewModel.findMatches(text) },
                enabled = text.isNotBlank() && !state.searching,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.rows.any { it.state == AddListRowState.FOUND }) {
                TextButton(onClick = { viewModel.addAllFound() }) {
                    Text("Add all found books")
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.rows, key = { it.title }) { row ->
                    GlassCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.match?.title ?: row.title,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                val line = when (row.state) {
                                    AddListRowState.SEARCHING -> "Looking…"
                                    AddListRowState.NOT_FOUND -> row.note
                                    AddListRowState.ADDED -> "In your Library ✓"
                                    AddListRowState.FAILED ->
                                        "Couldn't be added — try again"
                                    AddListRowState.FOUND -> row.note
                                    AddListRowState.WAITING -> ""
                                }
                                if (line.isNotBlank()) {
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                }
                            }
                            if (row.state == AddListRowState.FOUND) {
                                TextButton(onClick = { viewModel.add(row) }) {
                                    Text("Add")
                                }
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
