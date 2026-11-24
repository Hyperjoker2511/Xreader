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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import app.xreader.ui.theme.LocalUiMetrics

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
    // Code 121 (R7): the Material 3 form — small top app bar,
    // add toggle buttons on the candidate rows, the pinned
    // bottom button group. The Nocturne composition below is
    // unchanged.
    if (app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        M3AddListContent(viewModel = viewModel, onBack = onBack)
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
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

// ── Code 121 (R7): the Material 3 Add-to-list ──────────────
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun M3AddListContent(
    viewModel: AddListViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    // Rows the reader has toggled on; the bottom group's
    // "Add N books" adds exactly these (the review list is the
    // decision point — nothing is added during search).
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val pickedFound = state.rows.filter {
        it.state == AddListRowState.FOUND && it.title in picked
    }
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Add books") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (state.rows.isNotEmpty()) {
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme
                        .colorScheme.surfaceContainer
                ) {
                    androidx.compose.material3.ButtonGroup(
                        overflowIndicator = { },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        clickableItem(
                            onClick = onBack,
                            label = "Cancel"
                        )
                        clickableItem(
                            onClick = {
                                pickedFound.forEach { viewModel.add(it) }
                                picked = emptySet()
                            },
                            label = "Add ${pickedFound.size} books",
                            enabled = pickedFound.isNotEmpty()
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (state.searching) {
                // Import progress: wavy line under the app bar.
                androidx.compose.material3.LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    "Paste the books you want — one title per line. Pick " +
                        "the source to look on; you'll review every match " +
                        "before anything is added.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (state.sources.isEmpty()) {
                    Text(
                        "You need a source first — add one from Browse, " +
                            "then come back with your list.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    return@Column
                }
                Text(
                    "Look on:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                androidx.compose.material3.Button(
                    onClick = { viewModel.findMatches(text) },
                    enabled = text.isNotBlank() && !state.searching,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.searching) "Looking…" else "Find these books")
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.rows, key = { it.title }) { row ->
                        val line = when (row.state) {
                            AddListRowState.SEARCHING -> "Looking…"
                            AddListRowState.NOT_FOUND -> row.note
                            AddListRowState.ADDED -> "In your Library"
                            AddListRowState.FAILED ->
                                "Couldn't be added — try again"
                            AddListRowState.FOUND -> row.note
                            AddListRowState.WAITING -> ""
                        }
                        androidx.compose.material3.ListItem(
                            headlineContent = {
                                Text(row.match?.title ?: row.title)
                            },
                            supportingContent = if (line.isNotBlank()) {
                                { Text(line) }
                            } else {
                                null
                            },
                            leadingContent = {
                                app.xreader.ui.common.M3CoverThumbnail(
                                    title = row.match?.title ?: row.title,
                                    author = row.match?.author,
                                    coverModel = row.match?.coverUrl
                                        ?.takeIf { it.isNotBlank() },
                                    modifier = Modifier
                                        .size(width = 36.dp, height = 52.dp)
                                )
                            },
                            trailingContent = if (
                                row.state == AddListRowState.FOUND ||
                                row.state == AddListRowState.ADDED
                            ) {
                                {
                                    androidx.compose.material3.ToggleButton(
                                        checked = row.state ==
                                            AddListRowState.ADDED ||
                                            row.title in picked,
                                        onCheckedChange = { checked ->
                                            picked = if (checked) {
                                                picked + row.title
                                            } else {
                                                picked - row.title
                                            }
                                        },
                                        enabled = row.state ==
                                            AddListRowState.FOUND
                                    ) {
                                        Text(
                                            if (row.state ==
                                                AddListRowState.ADDED
                                            ) {
                                                "Added"
                                            } else {
                                                "Add"
                                            }
                                        )
                                    }
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }
    }
}
