package app.xreader.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.extension.SourceHandle
import app.xreader.di.AppContainer
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CatalogMode { POPULAR, LATEST, SEARCH }

data class CatalogUiState(
    val sourceName: String = "",
    val baseUrl: String = "",
    val mode: CatalogMode = CatalogMode.POPULAR,
    val query: String = "",
    val books: List<SourceBook> = emptyList(),
    val page: Int = 1,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    /** v1.3.0 (device spot-check fix): a load for the CURRENT
     * mode/query has completed at least once. The end-of-list
     * label may only render when this is true — on a slow
     * connection the first screenshot of v1.3.0 showed
     * "That's everything here" over a catalogue that was
     * still loading, falsely declaring the list finished. */
    val loadCompleted: Boolean = false,
    val errorMessage: String? = null,
    val openingBookId: String? = null
)

/** One source's catalogue: popular / latest / search, paged. Tapping a
 * book syncs it into the library and opens its page. */
class SourceCatalogViewModel(
    private val container: AppContainer,
    private val extensionId: Long,
    private val sourceId: String
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogUiState(loading = true))
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _openedBook = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val openedBook: SharedFlow<Long> = _openedBook.asSharedFlow()

    private var handle: SourceHandle? = null

    init {
        viewModelScope.launch {
            when (val found = container.extensionService.source(extensionId, sourceId)) {
                is AppResult.Err -> _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = ErrorText.displayText(found.error)
                )
                is AppResult.Ok -> {
                    handle = found.value
                    _state.value = _state.value.copy(
                        sourceName = found.value.source.name,
                        baseUrl = found.value.source.baseUrl
                    )
                    load(reset = true)
                }
            }
        }
    }

    fun setMode(mode: CatalogMode) {
        if (_state.value.mode == mode) return
        _state.value = _state.value.copy(
            mode = mode, books = emptyList(), page = 1,
            loadCompleted = false
        )
        if (mode != CatalogMode.SEARCH || _state.value.query.isNotBlank()) load(reset = true)
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun submitSearch() {
        _state.value = _state.value.copy(
            books = emptyList(), page = 1, loadCompleted = false
        )
        load(reset = true)
    }

    fun loadMore() {
        if (_state.value.loading || !_state.value.hasNext) return
        load(reset = false)
    }

    fun retry() = load(reset = true)

    private fun load(reset: Boolean) {
        val current = handle ?: return
        val snapshot = _state.value
        val page = if (reset) 1 else snapshot.page + 1
        _state.value = snapshot.copy(loading = true, errorMessage = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.extensionService.guarded(current) { source ->
                    when (snapshot.mode) {
                        CatalogMode.POPULAR -> source.popular(page)
                        CatalogMode.LATEST -> source.latest(page)
                        CatalogMode.SEARCH -> source.search(snapshot.query, page)
                    }
                }
            }
            _state.value = when (result) {
                is AppResult.Err -> _state.value.copy(
                    loading = false,
                    errorMessage = if (reset) ErrorText.displayText(result.error) else null
                )
                is AppResult.Ok -> {
                    // v1.0.6.2 (F2): pages are merged by book id —
                    // a book already on screen is never appended
                    // twice (overlapping pages, or a source that
                    // answers every page with its first one).
                    // "More may exist" is the engine's own word
                    // (hasNextPage) OR the plain evidence that
                    // this page brought books we hadn't seen; a
                    // page of pure duplicates ends the list
                    // honestly instead of re-requesting forever.
                    val existing = if (reset) emptyList() else _state.value.books
                    val knownIds = existing.mapTo(HashSet()) { it.id }
                    val newBooks = result.value.books
                        .distinctBy { it.id }
                        .filter { it.id !in knownIds }
                    _state.value.copy(
                        loading = false,
                        loadCompleted = true,
                        books = existing + newBooks,
                        page = page,
                        hasNext = (result.value.hasNextPage || newBooks.isNotEmpty()) &&
                            page < MAX_CATALOG_PAGE
                    )
                }
            }
            if (result is AppResult.Err && !reset) {
                _messages.tryEmit(ErrorText.displayText(result.error))
            }
        }
    }

    fun openBook(ref: SourceBook) {
        val current = handle ?: return
        _state.value = _state.value.copy(openingBookId = ref.id)
        viewModelScope.launch {
            when (val result = container.sourceBookSync.openBook(current, ref)) {
                is AppResult.Err -> {
                    _state.value = _state.value.copy(openingBookId = null)
                    _messages.tryEmit(ErrorText.displayText(result.error))
                }
                is AppResult.Ok -> {
                    _state.value = _state.value.copy(openingBookId = null)
                    _openedBook.tryEmit(result.value.id)
                }
            }
        }
    }

    /** v1.3.1 (item 45): the catalogue's explicit Add — the
     * ONLY catalogue gesture that makes a book a Library
     * member (a plain tap opens a preview; reading or
     * downloading also joins — see SourceBookSync). */
    fun addToLibrary(ref: SourceBook) {
        val current = handle ?: return
        viewModelScope.launch {
            when (
                val result = container.sourceBookSync.addToLibrary(current, ref)
            ) {
                is AppResult.Err ->
                    _messages.tryEmit(ErrorText.displayText(result.error))
                is AppResult.Ok ->
                    _messages.tryEmit("Added to library")
            }
        }
    }

    companion object {
        /** v1.0.6.2 (F2): a hard stop for pathological sources
         * whose pages never repeat and never end — 50 pages of
         * catalogue is beyond any real browsing session. */
        const val MAX_CATALOG_PAGE = 50

        fun factory(
            container: AppContainer,
            extensionId: Long,
            sourceId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SourceCatalogViewModel(container, extensionId, sourceId) as T
        }
    }
}
