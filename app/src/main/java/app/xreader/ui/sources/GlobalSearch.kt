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
import kotlinx.coroutines.async
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
    val books: List<SourceBook>,
    /** Manga programme (M3): the source's media kind — the
     * results list groups groups under Novels / Manga
     * headings by it. */
    val kind: app.xreader.data.extension.MediaKind =
        app.xreader.data.extension.MediaKind.TEXT
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
    /** P0 (field bug F1): the library + downloaded-content scan
     * still running alongside the source fan-out. Labelled
     * truthfully on the progress line instead of masquerading
     * as a source count. */
    val searchingLibrary: Boolean = false,
    // ── v1.0.2 (R15 + sweep) ─────────────────────────────────────
    val recents: List<String> = emptyList(),
    val saved: List<String> = emptyList(),
    /** Library books whose title/author matches. */
    val libraryBooks: List<app.xreader.data.db.Book> = emptyList(),
    /** M3: media kinds of the library matches (MANGA entries
     * only; absence = Novels) — the results list groups the
     * library section by kind like the source groups. */
    val libraryKinds: Map<Long, app.xreader.data.extension.MediaKind> =
        emptyMap(),
    /** Matches inside DOWNLOADED chapter text. */
    val contentHits: List<app.xreader.data.library.ContentHit> = emptyList(),
    /** Normalised work keys already in the library. */
    val libraryKeys: Set<String> = emptySet(),
    /** Work key → how many sources carry it (dedupe annotation). */
    val workCounts: Map<String, Int> = emptyMap()
)

/**
 * P0 (field bug F1, 2026-11-01): the search progress line's
 * rules, kept pure so they are unit-testable.
 *
 * The v1.3.4 line was built as `progressDone + 1 of
 * progressTotal` while the LOCAL phase (library + downloaded
 * content scan) ran first with the total still 0 — the screen
 * sat on "Searching source 1 of 0…" looking frozen. The rules
 * now: while sources are being searched the total is the real
 * source count and the position never exceeds it; the local
 * phase is named, never counted as a source; with no sources
 * installed the line says so instead of inventing a count.
 */
object SearchProgressLine {

    fun text(done: Int, total: Int, searchingLibrary: Boolean): String =
        when {
            total > 0 -> {
                val base = "Searching source " +
                    "${(done + 1).coerceAtMost(total)} of $total…"
                if (searchingLibrary) {
                    "$base · also searching your library"
                } else {
                    base
                }
            }
            searchingLibrary -> "Searching your library…"
            else -> "Searching…"
        }

    /** Bar fraction in [0, 1]; 0 while the total is unknown or
     * zero (no sources) — never a NaN, never past full. */
    fun fraction(done: Int, total: Int): Float =
        if (total > 0) {
            (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

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

    /** P0 field fix (2026-11-01): long-press on a recent-search
     * row removes just that entry. */
    fun removeRecent(recent: String) {
        viewModelScope.launch {
            container.sourcePrefs.removeRecentSearch(recent)
        }
    }

    /** P0 field fix (2026-11-01): the Recent searches header's
     * Clear action — wires the long-existing
     * [app.xreader.data.prefs.SourcePrefs.clearSearchHistory],
     * which no screen called before. */
    fun clearRecents() {
        viewModelScope.launch {
            container.sourcePrefs.clearSearchHistory()
        }
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
            container.sourcePrefs.recordSearch(query)
            // P0 (field bug F1): the source fan-out goes FIRST.
            // v1.3.4 ran the local phase (library match + a scan
            // of up to 300 downloaded chapter files) BEFORE the
            // fan-out and set the progress total only afterwards,
            // so the screen sat on "Searching source 1 of 0…"
            // looking frozen. Now the total is the real source
            // count from the first frame and the fan-out starts
            // immediately.
            // Manga programme (M1): the content preference
            // applies inside search too (the plan's rule) —
            // sources whose rating the preference hides never
            // join the fan-out, so the progress total is the
            // count actually searched from the first frame.
            // The rating read is the service's one accessor:
            // a Keiyoushi player source carries its extension's
            // index rating (K4), a source with no rating at
            // all (JS/Dex) is Safe.
            val visibility = container.sourcePrefs
                .contentVisibility.first()
            val handles = container.extensionService.allSources()
                .filter { handle ->
                    val rating = container.extensionService
                        .contentRatingOf(handle)
                    app.xreader.data.extension.ContentAccess
                        .allows(rating, visibility)
                }
            _state.value = _state.value.copy(
                searching = true, searched = true,
                groups = emptyList(), failedSources = emptyList(),
                libraryBooks = emptyList(), libraryKinds = emptyMap(),
                contentHits = emptyList(),
                progressDone = 0, progressTotal = handles.size,
                searchingLibrary = true
            )
            // The local phase runs ALONGSIDE the fan-out, off
            // the critical path (bounded scan unchanged: 300
            // downloaded chapters on Dispatchers.Default), and
            // publishes its sections the moment it lands.
            val libraryJob = async {
                // Library (offline): title/author matches + the
                // work keys used to dedupe source results.
                val allBooks = container.database.bookDao()
                    .observeAllBooks()
                    .first()
                val visible = allBooks.filter { it.mergedIntoId == null }
                val libraryBooks = visible.filter { book ->
                    book.title.contains(query, ignoreCase = true) ||
                        (book.author?.contains(
                            query, ignoreCase = true
                        ) == true)
                }
                // M3: the library matches' kinds, for the
                // Novels / Manga grouping of the results.
                val libraryKinds = runCatching {
                    container.bookKindResolver.kindsOf(libraryBooks)
                }.getOrDefault(emptyMap())
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
                            val book = byBook[chapter.bookId]
                                ?: return@mapNotNull null
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
                        app.xreader.data.library.ContentSearcher
                            .search(query, texts)
                    }.getOrDefault(emptyList())
                }
                if (isActive) {
                    _state.value = _state.value.copy(
                        libraryBooks = libraryBooks,
                        libraryKinds = libraryKinds,
                        contentHits = contentHits,
                        libraryKeys = libraryKeys,
                        searchingLibrary = false
                    )
                }
            }
            // v1.3.2 (scope D): every source searches at once
            // behind a small permit pool; each group's results
            // publish the moment its source answers, the progress
            // line counts settled sources, and a hung source can
            // no longer hold the others hostage (the v1.3.1 hang).
            try {
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
                                books = outcome.value.books,
                                kind = container.bookKindResolver
                                    .kindOf(handle)
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
            } finally {
                // On CANCELLATION the local scan stops with the
                // search — it never runs on behind a dead search.
                // On normal completion it is left to land: its
                // library sections publish when ready, below the
                // streamed source groups.
                if (!isActive) libraryJob.cancel()
            }
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
            libraryBooks = emptyList(), libraryKinds = emptyMap(),
            contentHits = emptyList(),
            progressDone = 0, progressTotal = 0, searchingLibrary = false
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
