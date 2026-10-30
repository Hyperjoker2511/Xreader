package app.xreader.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppError
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
    data class LoginRequired(
        val loginUrl: String,
        val host: String,
        /** v1.0.6 (W4): the site asked for a human check — the
         * login screen opens in SITE_CHECK mode. */
        val siteCheck: Boolean = false
    ) : LinkAddUiState
    data class Ready(
        val draft: LinkProber.Draft,
        val sourceName: String
    ) : LinkAddUiState
    data class Failed(
        val message: String,
        /** v1.0.7 draw-check fix: the probe's stage lines, so
         * "Copy details" carries what was actually tried (the
         * chikari paste of 2026-10-30 had no steps at all). */
        val steps: List<String> = emptyList()
    ) : LinkAddUiState
    data object Saving : LinkAddUiState
    data class Saved(val bookId: Long?, val bookTitle: String) : LinkAddUiState
    /** v1.0.9: a Live Source install is running (identity
     * discovery + row write — no probe, no test read). */
    data object InstallingLive : LinkAddUiState
    /** v1.0.9: the Live Source is installed. [bookId] is set
     * when the pasted book also opened into the Library. */
    data class LiveInstalled(
        val extensionId: Long,
        val sourceId: String,
        val name: String,
        val iconUrl: String?,
        val bookId: Long?
    ) : LinkAddUiState
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

    /** The link as typed for the latest probe — the screen's
     * Copy details reads the site from its normalised form
     * (the raw text of a bare domain has no host to read). */
    val lastProbedRawUrl: String get() = lastUrl

    fun probe(rawUrl: String) {
        lastUrl = rawUrl
        _state.value = LinkAddUiState.Probing
        viewModelScope.launch {
            // v1.0.5 (A10): the failure copy may only mention
            // the AI detector when its button will render.
            var outcome = container.linkProber.probe(
                rawUrl, aiAvailable = aiAvailable.value,
                // v1.0.6.2 (F5): a borderline-shaped book list
                // gets one AI verify call per run; it returns
                // null silently when no key is set or the check
                // can't run.
                semanticVerifier = { titles, excerpt ->
                    container.selectorAi.verifySample(titles, excerpt)
                }
            )
            // v1.0.7: AUTOMATIC escalation — a plain check that
            // couldn't prove the site (app-built pages whose
            // chapter list only exists after the site's scripts
            // run) is retried ONCE through the browser view, by
            // itself. The user never picks a mode and never sees
            // the intermediate failure; the screen just keeps
            // showing the checking state. The retry spends no AI
            // verify call (its lists judge on their own shape).
            if (outcome is LinkProber.Outcome.Failed &&
                outcome.renderWorthTrying
            ) {
                app.xreader.data.extension.DiagnosticLog.record(
                    "add from link",
                    "the plain check couldn't prove this site — " +
                        "checking again through the browser view",
                    outcome.message
                )
                outcome = container.linkProber.probe(
                    rawUrl,
                    aiAvailable = aiAvailable.value,
                    forceRender = true
                )
            }
            _state.value = when (outcome) {
                is LinkProber.Outcome.Ready -> LinkAddUiState.Ready(
                    draft = outcome.draft,
                    sourceName = outcome.draft.config.name
                )
                is LinkProber.Outcome.LoginRequired -> LinkAddUiState.LoginRequired(
                    loginUrl = outcome.loginUrl, host = outcome.host,
                    siteCheck = outcome.siteCheck
                )
                is LinkProber.Outcome.Failed -> LinkAddUiState.Failed(
                    outcome.message,
                    outcome.stageLines.map { it.render() }
                )
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
            title = current.host,
            mode = if (current.siteCheck) {
                LoginMode.SITE_CHECK
            } else {
                LoginMode.SIGN_IN
            }
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

    /**
     * Install the pasted link as a Live Source (v1.0.9): no
     * probe, no test read, no failure state — the site is
     * installed as-is and read live from then on. When the
     * link is a book page, the book is also opened into the
     * Library so the user lands on something real (a failed
     * open doesn't unpick the install — the source is in
     * Browse either way).
     */
    fun installLive(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isBlank()) return
        _state.value = LinkAddUiState.InstallingLive
        viewModelScope.launch {
            when (val installed = container.repoManager.installLiveSource(url)) {
                is AppResult.Err -> {
                    _state.value = LinkAddUiState.Failed(
                        "The Live Source couldn't be installed: " +
                            // v1.0.9.1: say the actual reason —
                            // the old fallback called every
                            // refusal "a storage problem", which
                            // hid a link-parsing refusal behind
                            // the wrong words entirely.
                            ((installed.error as? AppError.ParseFailed)?.reason
                                ?: installed.error.detail
                                ?: "a storage problem")
                    )
                }
                is AppResult.Ok -> {
                    val row = installed.value
                    val sourceId = row.sourceIds.substringBefore(",")
                    val handle = container.extensionService
                        .source(row.id, sourceId).getOrNull()
                    val liveSpec = (handle?.source as?
                        app.xreader.data.extension.LiveSource)?.spec
                    val bookId = if (handle != null) {
                        container.sourceBookSync.openBook(
                            handle,
                            // The spec's normalised address,
                            // not the raw paste (which may be
                            // a bare host the book can't open by).
                            SourceBook(
                                id = liveSpec?.startUrl ?: url,
                                title = row.displayName
                            )
                        ).getOrNull()?.id
                    } else {
                        null
                    }
                    _state.value = LinkAddUiState.LiveInstalled(
                        extensionId = row.id,
                        sourceId = sourceId,
                        name = row.displayName,
                        iconUrl = liveSpec?.iconUrl,
                        bookId = bookId
                    )
                    _messages.tryEmit(
                        "Live Source installed — “${row.displayName}”"
                    )
                }
            }
        }
    }

    /** The installed Live Source's icon bytes, for the
     * home-screen shortcut (best effort; null = use the
     * generated letter icon). */
    suspend fun liveIconBytes(iconUrl: String?): ByteArray? {
        if (iconUrl.isNullOrBlank()) return null
        return container.repoManager.fetchBytes(iconUrl).getOrNull()
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
