package app.xreader.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppError
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
import app.xreader.data.extension.normaliseHost
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

/** Why the built-in browser screen is being shown (v1.0.6, W4):
 * a sign-in, or a site's human check. Same screen, different
 * words — the mode is chosen by whoever detected the need, never
 * guessed by the screen. */
enum class LoginMode { SIGN_IN, SITE_CHECK }

/** A login the user is being asked to perform in the built-in browser. */
data class LoginRequest(
    val sessionKey: String,
    val url: String,
    val title: String,
    val mode: LoginMode = LoginMode.SIGN_IN
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
    /** v1.3.0 (device spot-check fix): ids of the user's own
     * rows that share a domain with a built-in default — the
     * bundled row is reordered ahead of them as the primary
     * for the domain and these carry an "Added by you" tag,
     * so same-name twins are never confusable. Nothing is
     * deleted or merged. See SourceTwinArrangement. */
    val addedByYouIds: Set<Long> = emptySet(),
    val catalogues: Map<Long, List<IndexPackage>> = emptyMap(),
    val updates: Map<String, RepoManager.UpdateCandidate> = emptyMap(),
    val pendings: Map<String, PendingUpdate> = emptyMap(),
    val changedRepos: Set<Long> = emptySet(),
    val busy: Boolean = false,
    /** v1.3.1 (item 59): the repo-add flow's current stage in
     * plain words ("Fetching the list…" / "Reading the
     * sources…") while it runs, and its definite outcome line
     * once it lands — the Add screen renders both IN the card,
     * so a slow add is never a silent form again. */
    val repoStage: String? = null,
    val repoOutcome: String? = null,
    val repoOutcomeIsError: Boolean = false,
    // ── v1.0.2 (R13/R14) ─────────────────────────────────────────
    /** Last test-drive verdict per source ("extId:sourceId"). */
    val health: Map<String, app.xreader.data.extension.SourceHealthRecord> =
        emptyMap(),
    val pinnedRepoIds: Set<Long> = emptySet(),
    val recentRepoIds: List<Long> = emptyList(),
    // ── v1.3.2 (scope E + R1 + item 84) ─────────────────────────
    /** Persisted foreign repos by repo row id (their own
     * screens read the full site list from here). */
    val foreignRepos:
        Map<Long, app.xreader.data.extension.RepoFormat.ForeignRepo> =
        emptyMap(),
    /** The repo row created by the most recent foreign add —
     * the Add screen's "See all N sites" door target. */
    val lastForeignRepoId: Long? = null,
    /** R1: the plain session word per source ("extId:sourceId"):
     * "Working" / "Check needed" / "Sign in needed" / "Signed in". */
    val sessionWords: Map<String, String> = emptyMap(),
    /** Item 84: the one-time restore card has been answered. */
    val restoreCardDone: Boolean = true
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
    private val _repoStage = MutableStateFlow<String?>(null)
    private val _repoOutcome = MutableStateFlow<Pair<String, Boolean>?>(null)
    private val _foreignRepos = MutableStateFlow<
        Map<Long, app.xreader.data.extension.RepoFormat.ForeignRepo>
    >(emptyMap())
    private val _lastForeignRepoId = MutableStateFlow<Long?>(null)
    private val _sessionWords = MutableStateFlow<Map<String, String>>(emptyMap())

    val state: StateFlow<SourcesUiState> = combine(
        container.database.extensionRepoDao().observeAll(),
        container.database.installedExtensionDao().observeAll(),
        _catalogues, _updates, _changedRepos, _busy,
        _foreignRepo, _legadoRows,
        container.sourcePrefs.sourceHealth,
        container.sourcePrefs.pinnedRepoIds,
        container.sourcePrefs.recentRepoIds,
        _repoStage, _repoOutcome,
        _foreignRepos, _lastForeignRepoId, _sessionWords,
        container.sourcePrefs.sourceRestoreCardDone
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val rawExtensions = values[1] as List<InstalledExtension>
        val (orderedExtensions, twinIds) = arrangeSourceTwins(rawExtensions)
        SourcesUiState(
            repos = values[0] as List<ExtensionRepo>,
            extensions = orderedExtensions,
            addedByYouIds = twinIds,
            catalogues = values[2] as Map<Long, List<IndexPackage>>,
            updates = values[3] as Map<String, RepoManager.UpdateCandidate>,
            pendings = readPendings(values[1] as List<InstalledExtension>),
            changedRepos = values[4] as Set<Long>,
            busy = values[5] as Boolean,
            foreignRepo = values[6] as app.xreader.data.extension.RepoFormat.ForeignRepo?,
            legadoRows = values[7] as List<LegadoImportRow>,
            health = values[8] as Map<String, app.xreader.data.extension.SourceHealthRecord>,
            pinnedRepoIds = values[9] as Set<Long>,
            recentRepoIds = values[10] as List<Long>,
            repoStage = values[11] as String?,
            repoOutcome = (values[12] as Pair<String, Boolean>?)?.first,
            repoOutcomeIsError =
                (values[12] as Pair<String, Boolean>?)?.second ?: false,
            foreignRepos =
                values[13] as Map<Long, app.xreader.data.extension.RepoFormat.ForeignRepo>,
            lastForeignRepoId = values[14] as Long?,
            sessionWords = values[15] as Map<String, String>,
            restoreCardDone = values[16] as Boolean
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
                // v1.3.2 (scope E): persisted foreign repos ride
                // the same collection — their site lists load
                // from the sidecar files.
                val foreign = repos.mapNotNull { repo ->
                    manager.loadForeignRepo(repo.id)
                        ?.let { repo.id to it }
                }.toMap()
                _foreignRepos.value = foreign
            }
        }
        // v1.3.2 (R1): the plain session word per source row,
        // recomputed when the sources or their verdicts change.
        viewModelScope.launch {
            combine(
                container.database.installedExtensionDao().observeAll(),
                container.sourcePrefs.sourceHealth
            ) { exts, health -> exts to health }
                .collect { (exts, health) ->
                    _sessionWords.value = computeSessionWords(exts, health)
                }
        }
    }

    /** R1: "Signed in" / "Sign in needed" for sign-in sources;
     * "Working" / "Check needed" for browser-view sources —
     * computed from the source's own config, its stored session
     * and its last test-drive verdict. */
    private suspend fun computeSessionWords(
        extensions: List<InstalledExtension>,
        health: Map<String, app.xreader.data.extension.SourceHealthRecord>
    ): Map<String, String> {
        val words = HashMap<String, String>()
        for (ext in extensions) {
            val handles = container.extensionService
                .sourcesOf(ext.id).getOrNull() ?: continue
            val handle = handles.firstOrNull() ?: continue
            val config = container.extensionService
                .declarativeConfigOf(handle) ?: continue
            val key = app.xreader.data.extension.SourceHealthRecord
                .key(ext.id, handle.source.id)
            val hasSession = container.sessionStore
                .has(sessionKeyFor(ext.id))
            var word = app.xreader.data.extension.SessionNeed
                .stateWord(config, hasSession, null)
            if (word == "Working" &&
                health[key]?.state ==
                app.xreader.data.extension.SourceHealthState.BROKEN
            ) {
                word = "Check needed"
            }
            if (word != null) words[key] = word
        }
        return words
    }

    // ── v1.3.2 (item 84): the one-time restore card ─────────────

    /** The user's own tap: turn every listed off source back
     * on — exactly the rows the card listed, no others. */
    fun restoreAllOffSources(rows: List<InstalledExtension>) {
        viewModelScope.launch {
            rows.filter { !it.enabled }.forEach { ext ->
                manager.setEnabled(ext, true)
            }
            container.sourcePrefs.setSourceRestoreCardDone()
            say(
                "Turned ${rows.count { !it.enabled }} " +
                    "source(s) back on."
            )
        }
    }

    /** "Leave them off" — a complete answer; the card never
     * comes back. */
    fun dismissRestoreCard() {
        viewModelScope.launch {
            container.sourcePrefs.setSourceRestoreCardDone()
        }
    }

    private fun readPendings(
        extensions: List<InstalledExtension>
    ): Map<String, PendingUpdate> = extensions.mapNotNull { ext ->
        container.extensionStore.readPending(ext.packageId)
            ?.let { ext.packageId to it }
    }.toMap()

    /**
     * v1.3.0 (device spot-check fix): same-domain twins in the
     * sources list — see [SourceTwinArrangement]. A row's host
     * comes from its stored declarative definition (the first
     * config's base URL); non-declarative rows have no host
     * here and are never twins. Bundled rows are recognised by
     * their `bundled.` package prefix (RepoManager's bundled
     * install path).
     */
    private fun arrangeSourceTwins(
        extensions: List<InstalledExtension>
    ): Pair<List<InstalledExtension>, Set<Long>> {
        val hosts = HashMap<String, String?>()
        fun hostOf(ext: InstalledExtension): String? {
            if (ext.kind != app.xreader.data.db.ExtensionKind.DECLARATIVE) {
                return null
            }
            return hosts.getOrPut(ext.packageId) {
                val raw = container.extensionStore.readText(
                    container.extensionStore.definitionFile(ext.packageId)
                ) ?: return@getOrPut null
                app.xreader.data.extension.DeclarativeFingerprints
                    .decode(raw).firstOrNull()?.baseUrl
                    ?.normaliseHost()
            }
        }
        return SourceTwinArrangement.arrange(
            rows = extensions,
            idOf = { it.id },
            hostOf = { hostOf(it) },
            isBundled = { it.packageId.startsWith("bundled.") }
        )
    }

    private fun say(message: String) {
        _messages.tryEmit(message)
    }

    // ── repositories ────────────────────────────────────────────────

    fun addRepo(name: String, indexUrl: String) {
        viewModelScope.launch {
            _busy.value = true
            // v1.3.1 (item 59): staged, persistent feedback IN
            // the form — the stage shows while work runs and the
            // outcome line stays until dismissed. The screen no
            // longer clears the address up front, so a failure
            // leaves it in place for correction.
            _repoOutcome.value = null
            _repoStage.value = "Fetching the list…"
            try {
                // v1.0.1: detect the format FIRST. A Mihon / LNReader /
                // Legado / generic site list is not an error — it's a
                // different app's list, and it gets an explanation card
                // with a "Create Xreader source" action per site.
                when (
                    val result = manager.addRepoOrDetect(
                        name.trim(), indexUrl.trim()
                    ) { stage -> _repoStage.value = stage }
                ) {
                    is RepoManager.AddRepoOutcome.Added -> {
                        _repoOutcome.value =
                            "“${result.repo.name}” added · " +
                                "${result.sourceCount} sources" to false
                        refreshRepo(result.repo)
                    }
                    is RepoManager.AddRepoOutcome.Foreign -> {
                        _foreignRepo.value = result.format
                        _repoOutcome.value =
                            foreignOutcomeLine(result.format) to false
                        // v1.3.2 (scope E): PERSIST the foreign
                        // repo — it joins the Repositories view
                        // with its own full-list screen, instead
                        // of living only in this one-shot card.
                        // (Plugin-index stubs carry no sites to
                        // persist as a browsable list.)
                        if (!result.format.pluginIndex) {
                            val saved = manager.saveForeignRepo(
                                name.trim(), indexUrl.trim(),
                                result.format
                            )
                            _foreignRepos.value =
                                _foreignRepos.value +
                                    (saved.id to result.format)
                            _lastForeignRepoId.value = saved.id
                        }
                        if (result.format.ecosystemName == "Legado") {
                            importLegadoText(
                                manager.fetchText(indexUrl.trim()).let {
                                    (it as? AppResult.Ok)?.value ?: ""
                                }
                            )
                        }
                    }
                    is RepoManager.AddRepoOutcome.Failed ->
                        _repoOutcome.value =
                            repoFailureLine(result.error) to true
                }
            } catch (t: Throwable) {
                // v1.2.2 crash net: this launch is uncaught —
                // an escape here crashed the app on the Add tap
                // (first external crash report, 2026-10-31).
                if (t is kotlinx.coroutines.CancellationException) throw t
                _repoOutcome.value =
                    "Something went wrong while adding that repository. " +
                        "Nothing was added — the address is still " +
                        "filled in, please try again." to true
            } finally {
                _busy.value = false
                _repoStage.value = null
            }
        }
    }

    fun dismissRepoOutcome() {
        _repoOutcome.value = null
    }

    /** v1.3.1 (item 59): the definite outcome line for a foreign
     * index — real counts from the parse, plain words. */
    private fun foreignOutcomeLine(
        format: app.xreader.data.extension.RepoFormat.ForeignRepo
    ): String = when {
        format.pluginIndex ->
            "That list's sources live inside a single plugin file — " +
                "the note below explains the options."
        format.stubIndex ->
            "That address now holds only an update note for older " +
                "apps — the note below explains."
        format.sites.isEmpty() ->
            "That list was read, but it names no sites Xreader can " +
                "use — the note below explains."
        else ->
            "${format.sites.size} sources found in that list. " +
                "They're made for ${format.ecosystemName} — pick a " +
                "site below and Xreader builds its own source for it."
    }

    /** v1.3.1 (item 59): the plain failure line — a stalled
     * connection says so, in those words. */
    private fun repoFailureLine(error: AppError): String {
        val detail = ErrorText.displayText(error)
        return if (
            detail.contains("too long", ignoreCase = true) ||
            detail.contains("timed out", ignoreCase = true) ||
            detail.contains("timeout", ignoreCase = true)
        ) {
            "Couldn't fetch the list — the connection stalled. " +
                "Check the address or try again."
        } else {
            "Couldn't add that repository — $detail " +
                "The address is still filled in; check it and try again."
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
            } catch (t: Throwable) {
                // v1.2.2 crash net (see addRepo).
                if (t is kotlinx.coroutines.CancellationException) throw t
                say(
                    "Something went wrong while creating that source, so " +
                        "Xreader stopped instead of crashing. Nothing was " +
                        "saved — please try again."
                )
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * "Install ready source" on a foreign-repo site row whose
     * LNReader plugin belongs to a templated family (v1.0.9):
     * the family's reading logic is already known, so the
     * source is generated from the family's template + the
     * plugin's own constructor options and saved directly —
     * no probe, no test read. The plugin file is fetched only
     * for its options bag; when that fetch fails, the family
     * defaults apply (most plugins change nothing else).
     */
    fun installFamilySource(site: app.xreader.data.extension.ForeignSite) {
        val family = site.family ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                val pluginText = site.pluginUrl?.let { pluginUrl ->
                    (manager.fetchText(pluginUrl) as? AppResult.Ok)?.value
                }
                val options = pluginText?.let {
                    app.xreader.data.extension.JsConstructorOptions.extractOptions(it)
                } ?: emptyMap()
                val config = app.xreader.data.extension.FamilyTemplates.configFor(
                    family = family,
                    pluginId = site.pluginId ?: site.name,
                    site = site.baseUrl,
                    name = site.name,
                    lang = site.lang,
                    options = options
                )
                if (config == null) {
                    say("“${site.name}” couldn't be prepared as a ready source.")
                    return@launch
                }
                val host = app.xreader.data.extension.CookieParsing
                    .hostOf(config.baseUrl) ?: ""
                when (val saved = manager.saveLinkSource(config, host)) {
                    is AppResult.Ok ->
                        say("Installed the ready source “${site.name}”. Find it under My link sources.")
                    is AppResult.Err ->
                        say("The source couldn't be saved: ${saved.error.detail ?: "a storage problem"}")
                }
            } catch (t: Throwable) {
                // v1.2.2 crash net (see addRepo).
                if (t is kotlinx.coroutines.CancellationException) throw t
                say(
                    "Something went wrong while installing that source, so " +
                        "Xreader stopped instead of crashing. Nothing was " +
                        "saved — please try again."
                )
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
                                    // v1.0.9 gate fix: save the
                                    // rules that passed — the
                                    // healed config when the
                                    // drive repaired them.
                                    val effective =
                                        drive.value.healedConfig
                                            ?: conversion.config
                                    val host = CookieParsing
                                        .hostOf(effective.baseUrl) ?: ""
                                    when (manager.saveLinkSource(effective, host)) {
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
            } catch (t: Throwable) {
                // v1.2.2 crash net (see addRepo).
                if (t is kotlinx.coroutines.CancellationException) throw t
                say(
                    "Something went wrong while importing that file, so " +
                        "Xreader stopped instead of crashing. Please try again."
                )
            } finally {
                _busy.value = false
            }
        }
    }

    fun refreshRepo(repo: ExtensionRepo) {
        viewModelScope.launch {
            _busy.value = true
            try {
                // v1.3.2 (scope E): a foreign repo refreshes its
                // own way (re-read the index, refresh the
                // sidecar) — the native TOFU walk doesn't apply.
                if (_foreignRepos.value.containsKey(repo.id)) {
                    when (val result = manager.refreshForeignRepo(repo)) {
                        is AppResult.Err ->
                            say(
                                "Couldn't check “${repo.name}”. " +
                                    ErrorText.displayText(result.error)
                            )
                        is AppResult.Ok -> {
                            _foreignRepos.value =
                                _foreignRepos.value +
                                    (repo.id to result.value)
                            say(
                                "“${repo.name}” checked — " +
                                    "${result.value.sites.size} sites."
                            )
                        }
                    }
                    return@launch
                }
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

    fun startLogin(
        sessionKey: String,
        url: String,
        title: String,
        mode: LoginMode = LoginMode.SIGN_IN
    ) {
        _loginRequest.value = LoginRequest(sessionKey, url, title, mode)
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
