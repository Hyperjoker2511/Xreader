package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.error.AppResult
import app.xreader.di.AppContainer
import app.xreader.extensionapi.SourceBook
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Global search (v1.0.1 Browse declutter): ONE search across every
 * installed source, results grouped by source on their own screen —
 * the Browse home stays clean. Tapping a result goes through the
 * same openBook identity path as the catalogue, so the book always
 * opens.
 */
data class GlobalSearchGroup(
    val sourceName: String,
    val extensionId: Long,
    val sourceId: String,
    val books: List<SourceBook>
)

data class GlobalSearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val searched: Boolean = false,
    val groups: List<GlobalSearchGroup> = emptyList(),
    val failedSources: List<String> = emptyList(),
    // ── v1.0.2 (R15 + sweep) ─────────────────────────────────────
    val recents: List<String> = emptyList(),
    val saved: List<String> = emptyList(),
    /** Library books whose title/author matches. */
    val libraryBooks: List<app.xreader.data.db.Book> = emptyList(),
    /** Matches inside DOWNLOADED chapter text. */
    val contentHits: List<app.xreader.data.library.ContentHit> = emptyList(),
    /** Normalised work keys already in the library. */
    val libraryKeys: Set<String> = emptySet(),
    /** Work key → how many sources carry it (dedupe annotation). */
    val workCounts: Map<String, Int> = emptyMap()
)

class GlobalSearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(GlobalSearchUiState())
    val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

    private val _openedBook = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val openedBook: SharedFlow<Long> = _openedBook.asSharedFlow()

    init {
        viewModelScope.launch {
            container.sourcePrefs.recentSearches.collect { recents ->
                _state.value = _state.value.copy(recents = recents)
            }
        }
        viewModelScope.launch {
            container.sourcePrefs.savedSearches.collect { saved ->
                _state.value = _state.value.copy(saved = saved)
            }
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun saveCurrentSearch() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        viewModelScope.launch { container.sourcePrefs.saveSearch(query) }
    }

    fun removeSavedSearch(query: String) {
        viewModelScope.launch { container.sourcePrefs.removeSavedSearch(query) }
    }

    fun openLibraryBook(bookId: Long) {
        viewModelScope.launch { _openedBook.emit(bookId) }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                searching = true, searched = true,
                groups = emptyList(), failedSources = emptyList(),
                libraryBooks = emptyList(), contentHits = emptyList()
            )
            container.sourcePrefs.recordSearch(query)
            // Library first (instant, offline): title/author matches
            // + the work keys used to dedupe source results.
            val allBooks = container.database.bookDao()
                .observeAllBooks()
                .first()
            val visible = allBooks.filter { it.mergedIntoId == null }
            val libraryBooks = visible.filter { book ->
                book.title.contains(query, ignoreCase = true) ||
                    (book.author?.contains(query, ignoreCase = true) == true)
            }
            val libraryKeys = visible
                .map {
                    app.xreader.data.extension.WorkMatcher
                        .normaliseTitle(it.title)
                }
                .toSet()
            // Inside downloaded chapters (bounded scan).
            val contentHits = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.Default
            ) {
                runCatching {
                    val chapters = container.database.chapterDao()
                        .getDownloadedChapters(300)
                    val byBook = visible.associateBy { it.id }
                    val texts = chapters.mapNotNull { chapter ->
                        val book = byBook[chapter.bookId] ?: return@mapNotNull null
                        val content = container.bookStorage
                            .readChapterContent(chapter.bookId, chapter.id)
                            .getOrNull() ?: return@mapNotNull null
                        app.xreader.data.library.ChapterTextForSearch(
                            bookId = chapter.bookId,
                            bookTitle = book.title,
                            chapterIndex = chapter.index,
                            chapterTitle = chapter.title,
                            text = content.plainText()
                        )
                    }
                    app.xreader.data.library.ContentSearcher.search(query, texts)
                }.getOrDefault(emptyList())
            }
            val groups = mutableListOf<GlobalSearchGroup>()
            val failed = mutableListOf<String>()
            for (handle in container.extensionService.allSources()) {
                when (val result = container.extensionService.guarded(handle) {
                    it.search(query, 1)
                }) {
                    is AppResult.Ok -> if (result.value.books.isNotEmpty()) {
                        groups += GlobalSearchGroup(
                            sourceName = handle.source.name,
                            extensionId = handle.extension.id,
                            sourceId = handle.source.id,
                            books = result.value.books
                        )
                    }
                    is AppResult.Err -> failed += handle.source.name
                }
            }
            // Work-level dedupe annotation: how many sources carry
            // each work (the same novel is not five discoveries).
            val workCounts = groups.flatMap { it.books }
                .groupingBy {
                    app.xreader.data.extension.WorkMatcher
                        .normaliseTitle(it.title)
                }
                .eachCount()
            _state.value = _state.value.copy(
                searching = false, groups = groups, failedSources = failed,
                libraryBooks = libraryBooks, contentHits = contentHits,
                libraryKeys = libraryKeys, workCounts = workCounts
            )
        }
    }

    fun openBook(group: GlobalSearchGroup, book: SourceBook) {
        viewModelScope.launch {
            val handle = container.extensionService
                .source(group.extensionId, group.sourceId)
                .getOrNull() ?: return@launch
            when (val result = container.sourceBookSync.openBook(handle, book)) {
                is AppResult.Ok -> _openedBook.emit(result.value.id)
                is AppResult.Err -> Unit
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GlobalSearchViewModel(container) as T
            }
    }
}

@Composable
fun GlobalSearchScreen(
    viewModel: GlobalSearchViewModel,
    onOpenBook: (Long) -> Unit,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.openedBook.collect { onOpenBook(it) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        ScreenHeader(title = "Search", eyebrow = "All your sources")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.setQuery(it) },
            label = { Text("Search every source") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Button(
            onClick = { viewModel.search() },
            enabled = state.query.isNotBlank() && !state.searching,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Search") }
        if (state.searched && state.query.isNotBlank() &&
            state.query.trim() !in state.saved
        ) {
            TextButton(onClick = { viewModel.saveCurrentSearch() }) {
                Text("Save this search")
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.searching -> CircularProgressIndicator()
            !state.searched -> {
                // v1.0.2 (sweep): recent + saved searches, one tap.
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.recents.isNotEmpty()) {
                        item {
                            Text(
                                "Recent searches",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(state.recents, key = { "recent-$it" }) { recent ->
                            GlassCard(onClick = {
                                viewModel.setQuery(recent)
                                viewModel.search()
                            }) {
                                Text(recent, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    if (state.saved.isNotEmpty()) {
                        item {
                            Text(
                                "Saved searches",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(state.saved, key = { "saved-$it" }) { saved ->
                            GlassCard(onClick = {
                                viewModel.setQuery(saved)
                                viewModel.search()
                            }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        saved,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { viewModel.removeSavedSearch(saved) }
                                    ) { Text("Remove") }
                                }
                            }
                        }
                    }
                    if (state.recents.isEmpty() && state.saved.isEmpty()) {
                        item {
                            Text(
                                "Search your library, your downloads and " +
                                    "every source at once.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            state.groups.isEmpty() && state.libraryBooks.isEmpty() &&
                state.contentHits.isEmpty() -> Text(
                    "Nothing found for “${state.query}”. " +
                        if (state.failedSources.isNotEmpty()) {
                            "These sources couldn't be searched: " +
                                state.failedSources.joinToString(", ") + "."
                        } else {
                            "Try a different word, or check the spelling."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.libraryBooks.isNotEmpty()) {
                    item(key = "lib-head") {
                        Text(
                            "In your library",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    items(state.libraryBooks, key = { "lib-${it.id}" }) { book ->
                        GlassCard(onClick = { viewModel.openLibraryBook(book.id) }) {
                            Text(book.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Already on your shelf — tap to open.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (state.contentHits.isNotEmpty()) {
                    item(key = "content-head") {
                        Text(
                            "Inside your downloads",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    items(
                        state.contentHits,
                        key = { "hit-${it.bookId}-${it.chapterIndex}" }
                    ) { hit ->
                        GlassCard(onClick = { viewModel.openLibraryBook(hit.bookId) }) {
                            Text(
                                hit.bookTitle,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                hit.chapterTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "…${hit.snippet}…",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                state.groups.forEach { group ->
                    item(key = "head-${group.sourceId}") {
                        Text(
                            group.sourceName,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    items(group.books, key = { "gs-${group.sourceId}-${it.id}" }) { book ->
                        val workKey = app.xreader.data.extension.WorkMatcher
                            .normaliseTitle(book.title)
                        val inLibrary = workKey in state.libraryKeys
                        val copies = state.workCounts[workKey] ?: 1
                        GlassCard(onClick = { viewModel.openBook(group, book) }) {
                            Text(book.title, style = MaterialTheme.typography.bodyLarge)
                            book.author?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (inLibrary || copies > 1) {
                                Text(
                                    listOfNotNull(
                                        if (inLibrary) "In your library" else null,
                                        if (copies > 1) "On $copies sources" else null
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
                if (state.failedSources.isNotEmpty()) {
                    item {
                        Text(
                            "These sources couldn't be searched: " +
                                state.failedSources.joinToString(", ") + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
