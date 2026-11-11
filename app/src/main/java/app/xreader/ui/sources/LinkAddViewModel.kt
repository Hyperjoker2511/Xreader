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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

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
    /** v1.5.2 loop 2 (owner auto-install decision + 2D redirect
     * guard): the probe PROVED a site, but on a DIFFERENT
     * registrable domain than the pasted link's — the one pause
     * in the auto-install path. The user chooses: add the
     * redirected site instead, or cancel. Nothing is installed
     * while this state shows. */
    data class RedirectChoice(
        val draft: LinkProber.Draft,
        val sourceName: String,
        val pastedHost: String,
        val finalHost: String
    ) : LinkAddUiState
    data class Failed(
        val message: String,
        /** v1.0.7 draw-check fix: the probe's stage lines, so
         * "Copy details" carries what was actually tried (the
         * chikari paste of 2026-10-30 had no steps at all). */
        val steps: List<String> = emptyList()
    ) : LinkAddUiState
    /** v1.5.2 fix (FIX-1b, §2): the honest state — a recipe was
     * derived but reading is not proven. Saving from here persists
     * the source MARKED needs-repair with this state. */
    data class NotProven(
        val outcome: LinkProber.Outcome.NotProven,
        val sourceName: String
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

    /** v1.5.5 (§3): the post-save retry gate for this surface
     * — RESUMED while its state has subscribers (the shell
     * collects it with collectAsStateWithLifecycle). */
    private val sessionRetryGate =
        app.xreader.data.extension.SurfaceRetryGate("link-add") {
            _state.subscriptionCount.value > 0
        }

    private val _state = MutableStateFlow<LinkAddUiState>(LinkAddUiState.Idle)
    val state: StateFlow<LinkAddUiState> = _state.asStateFlow()

    /**
     * v1.4.3 (the user's add-link rule): the Explore SECTION
     * the user tapped "Add a link" in is the filing decision
     * — Novels files a novel source, Manga files a manga
     * source, and nothing in the learner overrides it. The
     * navigation layer sets this from the Sources screen's
     * current section right before opening the add flow, and
     * [save] stamps it on the saved source. (Chapter-substance
     * detection exists only as an advisor in Repair's
     * re-classify, never here.)
     */
    @Volatile
    var intendedKind: app.xreader.data.extension.MediaKind? = null
        private set

    fun setIntendedKind(
        kind: app.xreader.data.extension.MediaKind?
    ) {
        intendedKind = kind
    }

    init {
        // v1.5.2 (§1, headline seam): the session-saved subscription
        // lives in viewModelScope — NOT in a LaunchedEffect the
        // navigation cancels when the login screen opens (the
        // v1.5.1 defect: the completion fired with zero collectors
        // and no re-probe was ever issued). The §1 pending
        // operation is the guarantee behind this fast path.
        viewModelScope.launch {
            container.sessionUpdateBus.events.collect { event ->
                onSessionSaved(event.sessionKey)
            }
        }
        // v1.5.5 (§3): a save that landed while this screen
        // was in the backstack retries on its next resume.
        viewModelScope.launch {
            _state.subscriptionCount.collect { count ->
                if (count > 0) {
                    sessionRetryGate.onResumed()?.let { key ->
                        onSessionSaved(key)
                    }
                }
            }
        }
    }

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

    /** v1.2.2 crash net: this screen's submit handlers launch
     * UNCAUGHT — any escape (the probe's AI verify, the
     * browser-view retry, a storage write) would crash the
     * app. Guarded launches land as the honest Failed state
     * instead. (First external crash report, 2026-10-31:
     * tapping Add died on the spot.) */
    private val stoppedMessage =
        "Something went wrong, so Xreader stopped instead of crashing. " +
            "Please try again."

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = LinkAddUiState.Failed(stoppedMessage)
            }
        }
    }

    fun probe(rawUrl: String) {
        lastUrl = rawUrl
        _state.value = LinkAddUiState.Probing
        launchGuarded {
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
            // v1.5.2 (round-5 Branch 1): a NotProven outcome
            // whose unproven legs the browser view may prove
            // gets the same ONE automatic rendered retry — the
            // prober now carries that signal on NotProven too
            // (it previously existed only on Failed, so this
            // state settled in milliseconds and the retry the
            // design promises was unreachable from it). The
            // retry runs forced, so its own outcome never asks
            // for another; whatever it settles into is final.
            if (outcome is LinkProber.Outcome.NotProven &&
                outcome.renderWorthTrying
            ) {
                app.xreader.data.extension.DiagnosticLog.record(
                    "add from link",
                    "the plain check derived the site but " +
                        "couldn't prove every leg — checking " +
                        "again through the browser view",
                    outcome.missingLegs.joinToString()
                )
                outcome = container.linkProber.probe(
                    rawUrl,
                    aiAvailable = aiAvailable.value,
                    forceRender = true
                )
            }
            if (outcome is LinkProber.Outcome.Ready) {
                // v1.5.2 loop 2 (owner decision): a PROVEN probe
                // installs itself — pasting the link WAS the
                // decision to add. No Save tap on this path.
                landOnProven(outcome.draft)
                return@launchGuarded
            }
            _state.value = when (outcome) {
                is LinkProber.Outcome.Ready ->
                    error("unreachable: Ready lands via landOnProven")
                is LinkProber.Outcome.LoginRequired -> {
                    // v1.5.2 (§1): register the durable pending
                    // operation for this flow's vault session key.
                    viewModelScope.launch {
                        container.registerPendingOperation(
                            sessionKey = ExtensionHttpClient
                                .linkProbeSessionKey(outcome.host),
                            flowKind = "LINK_ADD",
                            host = outcome.host,
                            category = if (outcome.siteCheck) {
                                "CHALLENGE"
                            } else {
                                "SIGN_IN"
                            }
                        )
                    }
                    LinkAddUiState.LoginRequired(
                        loginUrl = outcome.loginUrl, host = outcome.host,
                        siteCheck = outcome.siteCheck
                    )
                }
                is LinkProber.Outcome.NotProven -> LinkAddUiState.NotProven(
                    outcome = outcome,
                    sourceName = outcome.config.name
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
        launchGuarded {
            when (
                val outcome = container.selectorAi.buildFromLink(lastUrl)
            ) {
                is app.xreader.data.ai.SelectorAi.Outcome.Ready ->
                    // The AI-proven path lands exactly like the
                    // automatic one: auto-install on proven.
                    landOnProven(outcome.draft)
                is app.xreader.data.ai.SelectorAi.Outcome.LoginRequired ->
                    _state.value = LinkAddUiState.LoginRequired(
                        loginUrl = outcome.loginUrl, host = outcome.host
                    )
                is app.xreader.data.ai.SelectorAi.Outcome.Failed ->
                    _state.value = LinkAddUiState.Failed(outcome.message)
            }
        }
    }

    /**
     * v1.5.2 loop 2 (owner decision, 2026-11-03): the landing
     * for EVERY proven probe — the initial probe, the post-login
     * re-probe and the AI path all come through here. The 2D
     * redirect guard runs first: a final host on a DIFFERENT
     * registrable domain than the pasted link pauses for the
     * user's single explicit choice; anything else (including
     * subdomain moves and SSO bounce-backs on the same
     * registrable domain) installs silently, with the final
     * host already the draft's recorded host.
     */
    private fun landOnProven(draft: LinkProber.Draft) {
        val pastedHost = pastedHost()
        // The probe's FINAL host: where the proven source
        // actually lives — the derived config's base host. (The
        // draft's own host field is the pasted host by the
        // prober's construction; a site that moved domains is
        // visible in the config the drive proved: the rendered
        // road follows the site's redirect and the recipe is
        // built from the final page.) Fallbacks: the proven
        // book's host, then the draft host.
        val finalHost =
            app.xreader.data.extension.CookieParsing
                .hostOf(draft.config.baseUrl)
                ?: app.xreader.data.extension.CookieParsing
                    .hostOf(draft.bookUrl)
                ?: draft.host
        if (pastedHost != null && finalHost.isNotBlank() &&
            registrableDomain(pastedHost) != registrableDomain(finalHost)
        ) {
            _state.value = LinkAddUiState.RedirectChoice(
                draft = draft,
                sourceName = draft.config.name,
                pastedHost = pastedHost,
                finalHost = finalHost
            )
            return
        }
        _state.value = LinkAddUiState.Ready(
            draft = draft,
            sourceName = draft.config.name
        )
        autoInstall()
    }

    /** THE auto-install wiring line: the proven landing calls
     * the existing save path — the same code the Save tap used
     * to call, not a fork of it. (Named for the re-scan's
     * mutation spot-check: cut this call and the §1/§2 journey
     * proofs must turn red.) */
    private fun autoInstall() {
        save()
    }

    /** The redirect guard's "add the redirected site instead":
     * back through the same Ready state and the same save path. */
    fun confirmRedirectedInstall() {
        val current = _state.value as? LinkAddUiState.RedirectChoice
            ?: return
        _state.value = LinkAddUiState.Ready(
            draft = current.draft,
            sourceName = current.sourceName
        )
        autoInstall()
    }

    /** The redirect guard's cancel: nothing was installed. */
    fun cancelRedirect() {
        if (_state.value is LinkAddUiState.RedirectChoice) {
            _state.value = LinkAddUiState.Idle
        }
    }

    private fun pastedHost(): String? {
        val raw = lastUrl.trim()
        if (raw.isBlank()) return null
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        return app.xreader.data.extension.CookieParsing.hostOf(withScheme)
    }

    /** eTLD+1 via the public suffix list; hosts the list has no
     * answer for (IPs, localhost, test domains) fall back to a
     * www-stripped comparison so a plain www move never reads
     * as a different site. */
    private fun registrableDomain(host: String): String {
        val normalised = host.lowercase().trimEnd('.')
        val fromList = runCatching {
            "https://$normalised/".toHttpUrl().topPrivateDomain()
        }.getOrNull()
        return fromList ?: normalised.removePrefix("www.")
    }

    /** After a login completes for this probe's session, try again. */
    fun onSessionSaved(sessionKey: String) {
        val current = _state.value as? LinkAddUiState.LoginRequired ?: return
        if (sessionKey == ExtensionHttpClient.linkProbeSessionKey(current.host)) {
            // v1.5.5 (§3): lifecycle priority — a backstack
            // link-add screen defers its re-probe until it is
            // next resumed (the gate parks the key; the
            // subscription watcher in init replays it).
            if (!sessionRetryGate.onSessionUpdated(sessionKey)) {
                return
            }
            viewModelScope.launch {
                // v1.5.5 (§3): the exactly-once claim is GONE —
                // this surface re-issues its OWN probe for the
                // saved session; the durable operation only
                // records what was blocked, and it reaches a
                // TERMINAL state when the re-probe settles (a
                // later solve re-registers cleanly over the
                // terminal record).
                app.xreader.data.extension.DiagnosticLog.record(
                    "fenrir-unlock",
                    "[FenrirUnlock] surface=link-add retried=true",
                    sessionKey
                )
                val hasOp = container.database.pendingOperationDao()
                    .getByKey(sessionKey) != null
                probe(lastUrl)
                if (hasOp) {
                    // probe() is state-driven; wait for its
                    // verdict, then finish the operation.
                    // v1.5.2 loop 2: a proven re-probe now
                    // flows on through Ready → Saving →
                    // Saved BY ITSELF (auto-install), so the
                    // wait runs to a terminal state.
                    _state.first {
                        it !is LinkAddUiState.Probing &&
                            it !is LinkAddUiState.LoginRequired &&
                            it !is LinkAddUiState.Ready &&
                            it !is LinkAddUiState.Saving
                    }
                    container.finishPendingOperation(
                        sessionKey,
                        _state.value is LinkAddUiState.Saved ||
                            _state.value is LinkAddUiState.RedirectChoice ||
                            _state.value is LinkAddUiState.NotProven
                    )
                }
            }
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
        launchGuarded {
            val config = current.draft.config.copy(
                name = current.sourceName.ifBlank { current.draft.config.name },
                // v1.4.3: the section the user added from is
                // authoritative — a manga site added from the
                // Manga section files as a manga source, never
                // under Novels (the link-added Asura case).
                mediaKind = intendedKind ?: current.draft.config.mediaKind
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
     * v1.5.2 fix (FIX-1b, §2): save a NotProven probe outcome in its
     * honest state — the source persists, marked needs-repair with
     * the reason "reading not proven", and the UI state says
     * exactly [LinkProber.Outcome.NotProven.STATE_LABEL].
     */
    fun saveNotProven() {
        val current = _state.value as? LinkAddUiState.NotProven ?: return
        _state.value = LinkAddUiState.Saving
        launchGuarded {
            val config = current.outcome.config.copy(
                name = current.sourceName.ifBlank { current.outcome.config.name },
                mediaKind = intendedKind ?: current.outcome.config.mediaKind
            )
            when (val saved = container.repoManager.saveLinkSource(
                config, current.outcome.host
            )) {
                is AppResult.Err -> {
                    _state.value = LinkAddUiState.Failed(
                        "The source couldn't be saved: ${saved.error.detail ?: "a storage problem"}"
                    )
                }
                is AppResult.Ok -> {
                    container.database.installedExtensionDao().setNeedsRepair(
                        saved.value.id,
                        "reading not proven: ${current.outcome.missingLegs.joinToString()}"
                    )
                    _state.value = LinkAddUiState.Saved(
                        bookId = null,
                        bookTitle = current.outcome.config.name
                    )
                    _messages.tryEmit(
                        "Source added, reading not proven — “${config.name}”"
                    )
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
        launchGuarded {
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
        // v1.5.2 fix (FIX-2, §1): closing the add flow abandons any
        // pending sign-in operation it registered — cancelled, not
        // left WAITING until expiry.
        val current = _state.value
        if (current is LinkAddUiState.LoginRequired) {
            viewModelScope.launch {
                container.cancelPendingOperation(
                    ExtensionHttpClient.linkProbeSessionKey(current.host)
                )
            }
        }
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
