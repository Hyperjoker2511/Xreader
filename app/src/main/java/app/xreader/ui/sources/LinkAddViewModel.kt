package app.xreader.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.data.extension.ExtensionHttpClient
import app.xreader.data.extension.LinkProber
import app.xreader.di.AppContainer
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "Add source from link" flow states (Amendment 7). */
sealed interface LinkAddUiState {
    data object Idle : LinkAddUiState
    data object Probing : LinkAddUiState
    data class LoginRequired(val loginUrl: String, val host: String) : LinkAddUiState
    data class Ready(
        val draft: LinkProber.Draft,
        val sourceName: String
    ) : LinkAddUiState
    data class Failed(val message: String) : LinkAddUiState
    data object Saving : LinkAddUiState
    data class Saved(val bookId: Long?, val bookTitle: String) : LinkAddUiState
    /** Phase 5: Gemini is studying the page after the automatic
     * detector failed — same mandatory test-drive afterwards. */
    data object AiProbing : LinkAddUiState
}

class LinkAddViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<LinkAddUiState>(LinkAddUiState.Idle)
    val state: StateFlow<LinkAddUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Phase 5: the AI detector is offered only when it can work —
     * the current engine pick resolves to a configured provider
     * (v1.0.3: any hub provider, not just Gemini) and the feature
     * is switched on. */
    val aiAvailable: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        container.aiHub.statesFlow(),
        container.aiPrefs.settings
    ) { states, settings ->
        app.xreader.data.ai.hub.EngineRouting
            .order(settings.enginePick, states)
            .any { it.configured } &&
            settings.enabled(app.xreader.data.prefs.AiFeature.SELECTOR_AI)
    }.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        false
    )

    /** The engine chip's text (v1.0.3) for the AI detector surface. */
    val engineLabel: StateFlow<String> = container.aiHub.chipLabel.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        "AI · Automatic"
    )

    private var lastUrl: String = ""

    fun probe(rawUrl: String) {
        lastUrl = rawUrl
        _state.value = LinkAddUiState.Probing
        viewModelScope.launch {
            _state.value = when (val outcome = container.linkProber.probe(rawUrl)) {
                is LinkProber.Outcome.Ready -> LinkAddUiState.Ready(
                    draft = outcome.draft,
                    sourceName = outcome.draft.config.name
                )
                is LinkProber.Outcome.LoginRequired -> LinkAddUiState.LoginRequired(
                    loginUrl = outcome.loginUrl, host = outcome.host
                )
                is LinkProber.Outcome.Failed -> LinkAddUiState.Failed(outcome.message)
            }
        }
    }

    /**
     * Phase 5 retry path: Gemini reads the page and proposes the
     * selectors. The proposal still has to pass the exact same
     * test-drive inside [app.xreader.data.ai.SelectorAi] before it
     * can reach the Ready state — an untested AI config is never
     * offered for saving.
     */
    fun probeWithAi() {
        if (lastUrl.isBlank()) return
        _state.value = LinkAddUiState.AiProbing
        viewModelScope.launch {
            _state.value = when (
                val outcome = container.selectorAi.buildFromLink(lastUrl)
            ) {
                is app.xreader.data.ai.SelectorAi.Outcome.Ready ->
                    LinkAddUiState.Ready(
                        draft = outcome.draft,
                        sourceName = outcome.draft.config.name
                    )
                is app.xreader.data.ai.SelectorAi.Outcome.LoginRequired ->
                    LinkAddUiState.LoginRequired(
                        loginUrl = outcome.loginUrl, host = outcome.host
                    )
                is app.xreader.data.ai.SelectorAi.Outcome.Failed ->
                    LinkAddUiState.Failed(outcome.message)
            }
        }
    }

    fun setSourceName(name: String) {
        val current = _state.value as? LinkAddUiState.Ready ?: return
        _state.value = current.copy(sourceName = name)
    }

    /** After a login completes for this probe's session, try again. */
    fun onSessionSaved(sessionKey: String) {
        val current = _state.value as? LinkAddUiState.LoginRequired ?: return
        if (sessionKey == ExtensionHttpClient.linkProbeSessionKey(current.host)) {
            probe(lastUrl)
        }
    }

    /** The login request the screen should hand to the sources VM. */
    fun loginRequest(): LoginRequest? {
        val current = _state.value as? LinkAddUiState.LoginRequired ?: return null
        return LoginRequest(
            sessionKey = ExtensionHttpClient.linkProbeSessionKey(current.host),
            url = current.loginUrl,
            title = current.host
        )
    }

    fun save() {
        val current = _state.value as? LinkAddUiState.Ready ?: return
        _state.value = LinkAddUiState.Saving
        viewModelScope.launch {
            val config = current.draft.config.copy(
                name = current.sourceName.ifBlank { current.draft.config.name }
            )
            when (val saved = container.repoManager.saveLinkSource(config, current.draft.host)) {
                is AppResult.Err -> {
                    _state.value = LinkAddUiState.Failed(
                        "The source couldn't be saved: ${saved.error.detail ?: "a storage problem"}"
                    )
                }
                is AppResult.Ok -> {
                    // Add the very book that was test-driven, so the
                    // user lands on something real immediately.
                    val handle = container.extensionService
                        .source(saved.value.id, config.id)
                        .getOrNull()
                    val bookId = if (handle != null) {
                        container.sourceBookSync.openBook(
                            handle,
                            SourceBook(
                                id = current.draft.bookUrl,
                                title = current.draft.testDrive.bookTitle
                            )
                        ).getOrNull()?.id
                    } else {
                        null
                    }
                    _state.value = LinkAddUiState.Saved(
                        bookId = bookId,
                        bookTitle = current.draft.testDrive.bookTitle
                    )
                    _messages.tryEmit("Source added — “${config.name}”")
                }
            }
        }
    }

    fun reset() {
        _state.value = LinkAddUiState.Idle
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LinkAddViewModel(container) as T
            }
    }
}
