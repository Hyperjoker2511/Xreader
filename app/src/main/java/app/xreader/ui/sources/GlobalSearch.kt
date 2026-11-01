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
import kotlinx.coroutines.isActive
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
    /** v1.3.2 (scope D): live fan-out progress — sources settled
     * out of the total, for the "Searching source N of M" line. */
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
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

    private var searchJob: kotlinx.coroutines.Job? = null

    /** v1.3.2 (scope D): stop the fan-out — the sources still in
     * flight are abandoned and the results already on screen
     * stay exactly as they are. */
    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        if (_state.value.searching) {
            _state.value = _state.value.copy(searching = false)
        }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                searching = true, searched = true,
                groups = emptyList(), failedSources = emptyList(),
                libraryBooks = emptyList(), contentHits = emptyList(),
                progressDone = 0, progressTotal = 0
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
            // The local sections land first (instant, offline);
            // the source fan-out then STREAMS in below them.
            _state.value = _state.value.copy(
                libraryBooks = libraryBooks, contentHits = contentHits,
                libraryKeys = libraryKeys
            )
            // v1.3.2 (scope D): every source searches at once
            // behind a small permit pool; each group's results
            // publish the moment its source answers, the progress
            // line counts settled sources, and a hung source can
            // no longer hold the others hostage (the v1.3.1 hang).
            val handles = container.extensionService.allSources()
            _state.value = _state.value.copy(
                progressTotal = handles.size, progressDone = 0
            )
            StreamingSearch.run(
                items = handles,
                parallelism = 5,
                search = { handle ->
                    container.extensionService.guarded(handle) {
                        it.search(query, 1)
                    }
                },
                onResult = { handle, result ->
                    val outcome = result.getOrNull()
                    when {
                        outcome is AppResult.Ok &&
                            outcome.value.books.isNotEmpty() -> {
                            val group = GlobalSearchGroup(
                                sourceName = handle.source.name,
                                extensionId = handle.extension.id,
                                sourceId = handle.source.id,
                                books = outcome.value.books
                            )
                            _state.value = _state.value.copy(
                                groups = _state.value.groups + group,
                                workCounts = _state.value.groups
                                    .plus(group)
                                    .flatMap { it.books }
                                    .groupingBy {
                                        app.xreader.data.extension
                                            .WorkMatcher
                                            .normaliseTitle(it.title)
                                    }
                                    .eachCount()
                            )
                        }
                        outcome is AppResult.Err || outcome == null -> {
                            _state.value = _state.value.copy(
                                failedSources =
                                    _state.value.failedSources +
                                        handle.source.name
                            )
                        }
                        else -> Unit
                    }
                },
                onProgress = { done, total ->
                    _state.value = _state.value.copy(
                        progressDone = done, progressTotal = total
                    )
                }
            )
            if (isActive) {
                _state.value = _state.value.copy(searching = false)
            }
        }
    }

    /** v1.3.2 (scope D): leave the results view — back to the
     * plain Explore list. */
    fun clearSearch() {
        cancelSearch()
        _state.value = _state.value.copy(
            query = "", searched = false, searching = false,
            groups = emptyList(), failedSources = emptyList(),
            libraryBooks = emptyList(), contentHits = emptyList(),
            progressDone = 0, progressTotal = 0
        )
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
