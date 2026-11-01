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
    val openingBookId: String? = null,
    /** v1.3.2 (scope A2): the session hand-off the current
     * failure calls for — the failure card offers the
     * one-time solve/sign-in screen as its primary door. */
    val sessionNeed: app.xreader.data.extension.SessionNeed.Kind? = null,
    /** v1.3.2 (item 77): the failure is simply "this source is
     * switched off" — the card offers to turn it back on and
     * retry in one tap. */
    val sourceOff: Boolean = false,
    /** v1.3.4 (U4): the v2 classification of the current load
     * failure — TEMPORARILY_UNAVAILABLE / DNS_FAILURE get
     * their honest words and a Retry; a session action rides
     * along only under [CatalogueCopy.offersSessionAction]'s
     * rules. Raw codes never reach the screen. */
    val fetchClassification:
        app.xreader.data.extension.FetchClassification.Result? = null
)

/**
 * v1.3.4 (U4): the catalogue's failure/empty copy decisions,
 * pure so a JVM test can pin them.
 *
 * Rules: an availability failure (503 "temporarily
 * unavailable" / 429 "too many requests") and a DNS failure
 * ("couldn't find the site's address") are stated honestly
 * and lead with Retry — a sign-in/check action may ride
 * along ONLY when the classification itself is a session
 * kind, or (DNS on a gated source) as a secondary action;
 * it never replaces the truth. An empty listing on a gated
 * source states the reason as UNKNOWN.
 */
object CatalogueCopy {

    fun emptyMessage(
        need: app.xreader.data.extension.SessionNeed.Kind?,
        isSearch: Boolean
    ): String = when {
        isSearch ->
            "No matches in this source — try a different title."
        need != null ->
            "Nothing came back from this source. It may be " +
                "asking for its one-time check or sign-in."
        else ->
            "This source's catalogue came back empty — " +
                "the site may have changed how it lists " +
                "its books."
    }

    /** The honest failure words for a classified load
     * failure, or null when the classification carries no
     * special words (the caller falls back to its ordinary
     * copy). Never contains a raw transport code. */
    fun failureMessage(
        classification:
            app.xreader.data.extension.FetchClassification.Result?
    ): String? = when (classification?.kind) {
        app.xreader.data.extension.FetchClassification
            .Kind.TEMPORARILY_UNAVAILABLE -> {
            val wait = retryWaitSuffix(
                classification.retryAfterSeconds
            )
            if (classification.rateLimited) {
                "Too many requests — the site asked us to " +
                    "slow down.$wait"
            } else {
                "The site is temporarily unavailable — it's " +
                    "having trouble right now.$wait"
            }
        }
        app.xreader.data.extension.FetchClassification
            .Kind.DNS_FAILURE ->
            "This source didn't answer: " +
                app.xreader.data.extension.FetchClassification
                    .DNS_MARKER +
                " — the name didn't resolve. Try again in a " +
                "little while."
        else -> null
    }

    private fun retryWaitSuffix(seconds: Long?): String {
        val waitSeconds = seconds ?: return ""
        return when {
            waitSeconds >= 90 ->
                " Try again in about ${(waitSeconds + 59) / 60} minutes."
            waitSeconds >= 2 ->
                " Try again in about $waitSeconds seconds."
            else -> ""
        }
    }

    /** Whether a sign-in/check action may appear ALONGSIDE
     * the honest failure state: yes for a session-kind
     * classification, yes (secondary only) for DNS on a
     * gated source, never for an availability failure. */
    fun offersSessionAction(
        classification:
            app.xreader.data.extension.FetchClassification.Result?,
        sessionNeed: app.xreader.data.extension.SessionNeed.Kind?
    ): Boolean = when (classification?.kind) {
        app.xreader.data.extension.FetchClassification
            .Kind.SESSION_CHECK,
        app.xreader.data.extension.FetchClassification
            .Kind.SIGN_IN -> true
        app.xreader.data.extension.FetchClassification
            .Kind.DNS_FAILURE -> sessionNeed != null
        app.xreader.data.extension.FetchClassification
            .Kind.TEMPORARILY_UNAVAILABLE -> false
        else -> sessionNeed != null
    }

    fun sessionActionLabel(
        need: app.xreader.data.extension.SessionNeed.Kind?
    ): String = when (need) {
        app.xreader.data.extension.SessionNeed.Kind.SIGN_IN ->
            "Sign in"
        else -> "Solve the check"
    }
}

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
        // v1.3.3 (scope S4): when the user finishes the
        // one-time solve/sign-in screen for THIS source, the
        // catalogue retries itself — the hand-off is a
        // round trip, not a dead end with homework.
        viewModelScope.launch {
            container.sessionSavedEvents.collect { key ->
                if (key ==
                    app.xreader.data.extension.ExtensionHttpClient
                        .sessionKeyFor(extensionId)
                ) {
                    if (handle != null) load(reset = true)
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

    /** v1.3.2 (item 77): one tap — turn the source back on AND
     * retry the load it failed. */
    fun turnBackOnAndRetry() {
        viewModelScope.launch {
            val row = container.database.installedExtensionDao()
                .getById(extensionId) ?: return@launch
            container.repoManager.setEnabled(row, true)
            _state.value = _state.value.copy(sourceOff = false)
            load(reset = true)
        }
    }

    private fun load(reset: Boolean) {
        val current = handle ?: return
        val snapshot = _state.value
        val page = if (reset) 1 else snapshot.page + 1
        _state.value = snapshot.copy(
            loading = true, errorMessage = null,
            sessionNeed = if (reset) null else snapshot.sessionNeed,
            fetchClassification = if (reset) {
                null
            } else {
                snapshot.fetchClassification
            }
        )
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
                    errorMessage = if (reset) ErrorText.displayText(result.error) else null,
                    // v1.3.4 (U4): classification v2 for the
                    // failure — availability/DNS get honest
                    // words on the card (CatalogueCopy), raw
                    // codes never reach the screen.
                    fetchClassification = if (reset) {
                        app.xreader.data.extension.FetchClassification
                            .classify(
                                container.extensionService
                                    .declarativeConfigOf(current),
                                result.error
                            )
                    } else {
                        _state.value.fetchClassification
                    },
                    // v1.3.2 (scope A2): classify the failure
                    // against this source's config — bundled
                    // and user-added sources alike.
                    sessionNeed = if (reset) {
                        app.xreader.data.extension.SessionNeed.classify(
                            container.extensionService
                                .declarativeConfigOf(current),
                            result.error
                        )
                    } else {
                        _state.value.sessionNeed
                    },
                    // v1.3.2 (item 77): a switched-off source is
                    // its own plain state, with its own one-tap
                    // way back.
                    sourceOff = if (reset) {
                        result.error.detail
                            ?.contains("switched off") == true
                    } else {
                        _state.value.sourceOff
                    }
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
                    val merged = existing + newBooks
                    // v1.3.4 (U4/T4): record the extracted-row
                    // count against this source's last page
                    // shape — an empty listing's 0 is exactly
                    // the evidence the diagnostics screen needs.
                    app.xreader.data.extension.PageShapeLog.noteRows(
                        app.xreader.data.extension.ExtensionHttpClient
                            .sessionKeyFor(extensionId),
                        merged.size
                    )
                    _state.value.copy(
                        loading = false,
                        loadCompleted = true,
                        books = merged,
                        page = page,
                        hasNext = (result.value.hasNextPage || newBooks.isNotEmpty()) &&
                            page < MAX_CATALOG_PAGE,
                        // v1.3.3 (scope S1): an EMPTY catalogue
                        // on a gated source is a suspected wall,
                        // not an empty shelf — the empty state
                        // itself carries the hand-off (BrightNovel:
                        // the rendered road got the "Attention
                        // Required" page and extracted nothing).
                        sessionNeed = if (
                            reset && merged.isEmpty() &&
                            snapshot.mode != CatalogMode.SEARCH
                        ) {
                            val config = container.extensionService
                                .declarativeConfigOf(current)
                            val hasSession = withContext(Dispatchers.IO) {
                                container.sessionStore.has(
                                    app.xreader.data.extension
                                        .ExtensionHttpClient
                                        .sessionKeyFor(extensionId)
                                )
                            }
                            app.xreader.data.extension.SessionNeed
                                .forEmptyCatalogue(config, hasSession)
                        } else {
                            _state.value.sessionNeed
                        }
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
