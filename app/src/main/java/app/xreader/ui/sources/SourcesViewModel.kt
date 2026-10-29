package app.xreader.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.extension.ExtensionHttpClient
import app.xreader.data.extension.CookieParsing
import app.xreader.data.extension.IndexPackage
import app.xreader.data.extension.LegadoConversionResult
import app.xreader.data.extension.LegadoConverter
import app.xreader.data.extension.LinkProber
import app.xreader.data.extension.PendingUpdate
import app.xreader.data.extension.RepoManager
import app.xreader.data.extension.SessionData
import app.xreader.data.extension.TrustEngine
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A login the user is being asked to perform in the built-in browser. */
data class LoginRequest(
    val sessionKey: String,
    val url: String,
    val title: String
)

/** One row of the Legado import report (v1.0.1). */
data class LegadoImportRow(
    val name: String,
    val url: String,
    val outcome: String,
    val converted: Boolean
)

data class SourcesUiState(
    /** v1.0.1: a foreign repo index (Mihon / LNReader / Legado /
     * generic) the user pasted — shown as an explanation card with
     * per-site "Create Xreader source" actions. */
    val foreignRepo: app.xreader.data.extension.RepoFormat.ForeignRepo? = null,
    /** v1.0.1: per-source results of a Legado import. */
    val legadoRows: List<LegadoImportRow> = emptyList(),
    val repos: List<ExtensionRepo> = emptyList(),
    val extensions: List<InstalledExtension> = emptyList(),
    val catalogues: Map<Long, List<IndexPackage>> = emptyMap(),
    val updates: Map<String, RepoManager.UpdateCandidate> = emptyMap(),
    val pendings: Map<String, PendingUpdate> = emptyMap(),
    val changedRepos: Set<Long> = emptySet(),
    val busy: Boolean = false,
    // ── v1.0.2 (R13/R14) ─────────────────────────────────────────
    /** Last test-drive verdict per source ("extId:sourceId"). */
    val health: Map<String, app.xreader.data.extension.SourceHealthRecord> =
        emptyMap(),
    val pinnedRepoIds: Set<Long> = emptySet(),
    val recentRepoIds: List<Long> = emptyList()
)

/**
 * Sources (PLAN §5, Stitch "Sources" screen): repositories, installs,
 * updates with the §9.1 trust rules, and the assisted-login handoff.
 * All mutations go through [RepoManager]; this VM only sequences them
 * and phrases the outcomes in plain words.
 */
class SourcesViewModel(private val container: AppContainer) : ViewModel() {

    private val manager get() = container.repoManager

    private val _catalogues = MutableStateFlow<Map<Long, List<IndexPackage>>>(emptyMap())
    private val _updates =
        MutableStateFlow<Map<String, RepoManager.UpdateCandidate>>(emptyMap())
    private val _changedRepos = MutableStateFlow<Set<Long>>(emptySet())
    private val _busy = MutableStateFlow(false)
    private val _foreignRepo =
        MutableStateFlow<app.xreader.data.extension.RepoFormat.ForeignRepo?>(null)
    private val _legadoRows = MutableStateFlow<List<LegadoImportRow>>(emptyList())

    val state: StateFlow<SourcesUiState> = combine(
        container.database.extensionRepoDao().observeAll(),
        container.database.installedExtensionDao().observeAll(),
        _catalogues, _updates, _changedRepos, _busy,
        _foreignRepo, _legadoRows,
        container.sourcePrefs.sourceHealth,
        container.sourcePrefs.pinnedRepoIds,
        container.sourcePrefs.recentRepoIds
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SourcesUiState(
            repos = values[0] as List<ExtensionRepo>,
            extensions = values[1] as List<InstalledExtension>,
            catalogues = values[2] as Map<Long, List<IndexPackage>>,
            updates = values[3] as Map<String, RepoManager.UpdateCandidate>,
            pendings = readPendings(values[1] as List<InstalledExtension>),
            changedRepos = values[4] as Set<Long>,
            busy = values[5] as Boolean,
            foreignRepo = values[6] as app.xreader.data.extension.RepoFormat.ForeignRepo?,
            legadoRows = values[7] as List<LegadoImportRow>,
            health = values[8] as Map<String, app.xreader.data.extension.SourceHealthRecord>,
            pinnedRepoIds = values[9] as Set<Long>,
            recentRepoIds = values[10] as List<Long>
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesUiState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Emitted with the session key whenever a login session is saved. */
    private val _sessionSaved = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val sessionSaved: SharedFlow<String> = _sessionSaved.asSharedFlow()

    private val _loginRequest = MutableStateFlow<LoginRequest?>(null)
    val loginRequest: StateFlow<LoginRequest?> = _loginRequest

    init {
        // Offline catalogue from the last fetch, so the surface is
        // useful before any refresh.
        viewModelScope.launch {
            container.database.extensionRepoDao().observeAll().collect { repos ->
                val cached = repos.mapNotNull { repo ->
                    manager.cachedIndex(repo.id)?.let { repo.id to it.packages }
                }.toMap()
                if (cached.isNotEmpty()) {
                    _catalogues.value = cached + _catalogues.value
                }
            }
        }
    }

    private fun readPendings(
        extensions: List<InstalledExtension>
    ): Map<String, PendingUpdate> = extensions.mapNotNull { ext ->
        container.extensionStore.readPending(ext.packageId)
            ?.let { ext.packageId to it }
    }.toMap()

    private fun say(message: String) {
        _messages.tryEmit(message)
    }

    // ── repositories ────────────────────────────────────────────────

    fun addRepo(name: String, indexUrl: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                // v1.0.1: detect the format FIRST. A Mihon / LNReader /
                // Legado / generic site list is not an error — it's a
                // different app's list, and it gets an explanation card
                // with a "Create Xreader source" action per site.
                when (val result = manager.addRepoOrDetect(name.trim(), indexUrl.trim())) {
                    is RepoManager.AddRepoOutcome.Added -> {
                        say("Repository added — “${result.repo.name}”")
                        refreshRepo(result.repo)
                    }
                    is RepoManager.AddRepoOutcome.Foreign -> {
                        _foreignRepo.value = result.format
                        if (result.format.ecosystemName == "Legado") {
                            importLegadoText(
                                manager.fetchText(indexUrl.trim()).let {
                                    (it as? AppResult.Ok)?.value ?: ""
                                }
                            )
                        }
                    }
                    is RepoManager.AddRepoOutcome.Failed ->
                        say(
                            "That link isn't a repository Xreader recognises. " +
                                "Check the link, or use Add from link to add the site itself. " +
                                "(${ErrorText.displayText(result.error)})"
                        )
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissForeignRepo() {
        _foreignRepo.value = null
    }

    /** "Create Xreader source" on a foreign-repo site row: the
     * existing Add-from-link probe test-drives the site; on success
     * the source saves into My link sources like any link source. */
    fun createSourceFromSite(site: app.xreader.data.extension.ForeignSite) {
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val outcome = container.linkProber.probe(site.baseUrl)) {
                    is LinkProber.Outcome.Ready -> {
                        when (
                            val saved = manager.saveLinkSource(
                                outcome.draft.config, outcome.draft.host
                            )
                        ) {
                            is AppResult.Ok ->
                                say("Created the Xreader source “${site.name}”. Find it under My link sources.")
                            is AppResult.Err ->
                                say("The source couldn't be saved: ${saved.error.detail ?: "a storage problem"}")
                        }
                    }
                    is LinkProber.Outcome.LoginRequired ->
                        say("“${site.name}” asks you to sign in first. Use Add from link and sign in there, then it can be added.")
                    is LinkProber.Outcome.Failed ->
                        say("Xreader couldn't read “${site.name}” on its own. ${outcome.message}")
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** Legado import from pasted/file text (v1.0.1): convert each
     * source, test-drive the converted ones, save the passes, and
     * report every source plainly. */
    fun importLegadoText(text: String) {
        viewModelScope.launch {
            if (text.isBlank()) return@launch
            _busy.value = true
            try {
                val sources = LegadoConverter.parseLegadoFile(text)
                if (sources.isEmpty()) {
                    say("That file doesn't look like a Legado source list.")
                    return@launch
                }
                val rows = mutableListOf<LegadoImportRow>()
                for (source in sources) {
                    when (val conversion = LegadoConverter.convert(source)) {
                        is LegadoConversionResult.Refused ->
                            rows += LegadoImportRow(
                                source.name, source.url,
                                "Couldn't convert — ${conversion.reason} " +
                                    "Try Add from link with the AI detector for this site.",
                                converted = false
                            )
                        is LegadoConversionResult.Converted -> {
                            val drive = container.linkProber
                                .testDriveFromCatalogue(conversion.config)
                            when {
                                drive is AppResult.Ok -> {
                                    val host = CookieParsing
                                        .hostOf(conversion.config.baseUrl) ?: ""
                                    when (manager.saveLinkSource(conversion.config, host)) {
                                        is AppResult.Ok -> rows += LegadoImportRow(
                                            source.name, source.url,
                                            "Converted and saved — the test read found " +
                                                "${drive.value.chapterCount} chapters.",
                                            converted = true
                                        )
                                        is AppResult.Err -> rows += LegadoImportRow(
                                            source.name, source.url,
                                            "Converted, but it couldn't be saved.",
                                            converted = false
                                        )
                                    }
                                }
                                else -> rows += LegadoImportRow(
                                    source.name, source.url,
                                    "Converted, but the test read didn't pass, so it wasn't saved. " +
                                        "Try Add from link for this site.",
                                    converted = false
                                )
                            }
                        }
                    }
                }
                _legadoRows.value = rows
            } finally {
                _busy.value = false
            }
        }
    }

    fun refreshRepo(repo: ExtensionRepo) {
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = manager.refresh(repo)) {
                    is AppResult.Err ->
                        say("Couldn't check “${repo.name}”. ${ErrorText.displayText(result.error)}")
                    is AppResult.Ok -> when (val fetch = result.value) {
                        is RepoManager.IndexFetch.Fetched -> {
                            _catalogues.value =
                                _catalogues.value + (repo.id to fetch.index.packages)
                            _changedRepos.value = _changedRepos.value - repo.id
                            handleUpdates(repo, fetch.index)
                        }
                        is RepoManager.IndexFetch.Changed -> {
                            _changedRepos.value = _changedRepos.value + repo.id
                            say(
                                "“${repo.name}” has a new list that doesn't match the one " +
                                    "you added. Review it below before accepting."
                            )
                        }
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            val repos = state.value.repos.filter { it.indexUrl != RepoManager.LINK_REPO_URL }
            repos.forEach { refreshRepo(it) }
        }
    }

    fun acceptChangedIndex(repo: ExtensionRepo) {
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = manager.acceptIndexChange(repo)) {
                    is AppResult.Err ->
                        say("Couldn't accept the new list. ${ErrorText.displayText(result.error)}")
                    is AppResult.Ok -> {
                        _catalogues.value =
                            _catalogues.value + (repo.id to result.value.packages)
                        _changedRepos.value = _changedRepos.value - repo.id
                        say("New list accepted for “${repo.name}”")
                        handleUpdates(repo, result.value)
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissChangedIndex(repo: ExtensionRepo) {
        _changedRepos.value = _changedRepos.value - repo.id
    }

    fun removeRepo(repo: ExtensionRepo) {
        viewModelScope.launch {
            manager.removeRepo(repo)
            _catalogues.value = _catalogues.value - repo.id
            say("Repository removed. Its sources were uninstalled too.")
        }
    }

    // ── installs & updates ──────────────────────────────────────────

    fun install(repo: ExtensionRepo, pkg: IndexPackage) {
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = manager.install(repo, pkg)) {
                    is AppResult.Err -> say(ErrorText.displayText(result.error))
                    is AppResult.Ok -> say("“${pkg.name}” installed")
                }
                container.sourcePrefs.touchRecentRepo(repo.id)
            } finally {
                _busy.value = false
            }
        }
    }

    /** R14: the storefront's one Uninstall action (same manager
     * path as the detail page's uninstall). */
    fun uninstallExtension(ext: InstalledExtension) {
        viewModelScope.launch {
            manager.uninstall(ext)
            say("“${ext.displayName}” uninstalled")
        }
    }

    /** R14: pin/unpin a repository (pinned repos list first). */
    fun setRepoPinned(repoId: Long, pinned: Boolean) {
        viewModelScope.launch {
            container.sourcePrefs.setRepoPinned(repoId, pinned)
        }
    }

    /** R14: opening a source marks its repository recently used. */
    fun touchRepo(repoId: Long) {
        viewModelScope.launch { container.sourcePrefs.touchRecentRepo(repoId) }
    }

    private suspend fun handleUpdates(repo: ExtensionRepo, index: app.xreader.data.extension.RepoIndex) {
        val candidates = manager.findUpdates(index)
        if (candidates.isEmpty()) return
        val byPackage = candidates.associateBy { it.installed.packageId }
        _updates.value = _updates.value + byPackage
        for (candidate in candidates) {
            if (candidate.decision is TrustEngine.UpdateDecision.AutoApply) {
                when (val applied = manager.applyUpdate(candidate)) {
                    is AppResult.Ok -> {
                        _updates.value = _updates.value - candidate.installed.packageId
                        say("“${candidate.pkg.name}” updated itself — nothing about it changed")
                    }
                    is AppResult.Err -> say(ErrorText.displayText(applied.error))
                }
            }
        }
    }

    fun applyUpdate(candidate: RepoManager.UpdateCandidate) {
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val result = manager.applyUpdate(candidate)) {
                    is AppResult.Err -> say(ErrorText.displayText(result.error))
                    is AppResult.Ok -> {
                        _updates.value = _updates.value - candidate.installed.packageId
                        when (result.value) {
                            is TrustEngine.UpdateDecision.RetrustRequired ->
                                say(
                                    "“${candidate.pkg.name}” changed, so it's paused. " +
                                        "Open its page to check the change and re-trust it."
                                )
                            else -> say("“${candidate.pkg.name}” updated")
                        }
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    // ── login handoff ───────────────────────────────────────────────

    fun startLogin(sessionKey: String, url: String, title: String) {
        _loginRequest.value = LoginRequest(sessionKey, url, title)
    }

    fun clearLoginRequest() {
        _loginRequest.value = null
    }

    /** Persist a captured login (WebView cookies + UA) into the vault. */
    fun saveLoginSession(
        sessionKey: String,
        host: String,
        cookies: Map<String, String>,
        userAgent: String?
    ) {
        viewModelScope.launch {
            container.sessionStore.mergeCookies(sessionKey, host, cookies, userAgent)
            _sessionSaved.tryEmit(sessionKey)
            say("Sign-in saved for this source")
        }
    }

    suspend fun hasSession(sessionKey: String): Boolean =
        container.sessionStore.has(sessionKey)

    fun sessionKeyFor(extensionRowId: Long): String =
        ExtensionHttpClient.sessionKeyFor(extensionRowId)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SourcesViewModel(container) as T
            }
    }
}
