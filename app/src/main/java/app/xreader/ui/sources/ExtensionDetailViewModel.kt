package app.xreader.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.db.InstalledExtension
import app.xreader.data.extension.CleanerEngine
import app.xreader.data.extension.CleanerRuleData
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.extension.PendingUpdate
import app.xreader.data.extension.toRecord
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExtensionDetailUiState(
    val extension: InstalledExtension? = null,
    val pending: PendingUpdate? = null,
    val sessionSaved: Boolean = false,
    /** v1.0.1: the saved rules when this is a link-added source. */
    val linkConfig: app.xreader.data.extension.DeclarativeConfig? = null,
    val cleanerRules: List<CleanerRuleData> = emptyList(),
    val testOutput: String? = null,
    val testNotes: List<String> = emptyList(),
    val loading: Boolean = true,
    // ── v1.0.2 (R10): health / test-drive / repair ───────────────
    val health: app.xreader.data.extension.SourceHealthRecord? = null,
    val healthResults: List<app.xreader.data.extension.CapabilityResult> =
        emptyList(),
    val testing: Boolean = false,
    val repairing: Boolean = false,
    /** v1.0.6: the last Repair's persisted record (note +
     * probe stage log), shown under the Health card. */
    val lastRepair: app.xreader.data.extension.SourceRepairRecord? = null,
    /** v1.0.6 (TR4): this source's site started serving JS shells
     * while its rules are unmarked — the page offers Repair,
     * which may flip it to the browser view. */
    val browserViewNeeded: Boolean = false,
    // ── v1.0.2 (R24): "Bring my library" ─────────────────────────
    val hasBookshelf: Boolean = false,
    val bringBusy: Boolean = false,
    val bringPreview: app.xreader.data.extension.ImportPreview? = null,
    val bringMessage: String? = null,
    // ── v1.0.3 (AI hub): AI match proposals for the preview ──────
    /** True while the AI is checking unmatched books. */
    val aiMatchBusy: Boolean = false,
    /** remoteKey → the AI's proposed existing book. A proposal is
     * only a proposal until the user confirms it. */
    val aiProposals: Map<String, app.xreader.data.ai.LibraryMatchAi.Proposal> =
        emptyMap(),
    /** remoteKeys the user confirmed as matches (skipped at import,
     * counted as already-in-library). */
    val aiConfirmedMatches: Set<String> = emptySet(),
    /** A plain line about the AI matching pass (or why it's off). */
    val aiMatchNote: String? = null,
    // ── v1.0.4 (D14): Edit parser rules ──────────────────────────
    val parserEditorOpen: Boolean = false,
    val parserSaving: Boolean = false,
    /** Why an edited candidate was NOT saved (test read failed…). */
    val parserMessage: String? = null
) {
    val permissions: ExtensionPermissions
        get() = ExtensionPermissions.parse(extension?.permissionsJson)
}

/**
 * One source's page (PLAN §5/§9.1): trust state + re-trust, the
 * fingerprint, per-domain permission toggles, the dex opt-in, the
 * saved-login state, and the cleaner rule editor with its test bench.
 */
class ExtensionDetailViewModel(
    private val container: AppContainer,
    private val extensionId: Long
) : ViewModel() {

    private val _sessionSaved = MutableStateFlow(false)
    private val _rules = MutableStateFlow<List<CleanerRuleData>>(emptyList())
    private val _test = MutableStateFlow<Pair<String?, List<String>>>(null to emptyList())
    private val _linkConfig =
        MutableStateFlow<app.xreader.data.extension.DeclarativeConfig?>(null)

    /** v1.0.2 (R10): health card state (record, per-step results,
     * busy flags) in one local flow. */
    private data class HealthLocal(
        val record: app.xreader.data.extension.SourceHealthRecord? = null,
        val results: List<app.xreader.data.extension.CapabilityResult> = emptyList(),
        val testing: Boolean = false,
        val repairing: Boolean = false,
        /** v1.0.6: the last Repair's persisted record. */
        val repairRecord: app.xreader.data.extension.SourceRepairRecord? = null
    )

    private val _health = MutableStateFlow(HealthLocal())

    /** v1.0.6.1 (P2): bumped every time Repair saves new rules.
     * A test-drive captures the epoch (and the config id) it
     * started under; if either has moved by the time its
     * verdict lands, the verdict describes rules that no longer
     * exist and is dropped instead of persisted — the stale
     * verdict overwriting a fresh Repair was the device case
     * (Health kept showing the pre-Repair failure). */
    @Volatile
    private var healthEpoch = 0

    /** v1.0.2 (R24): "Bring my library" scan → preview → import. */
    private data class BringLocal(
        val hasBookshelf: Boolean = false,
        val busy: Boolean = false,
        val preview: app.xreader.data.extension.ImportPreview? = null,
        val message: String? = null,
        /** v1.0.3: AI match assist state for the current preview. */
        val aiBusy: Boolean = false,
        val aiProposals: Map<String, app.xreader.data.ai.LibraryMatchAi.Proposal> =
            emptyMap(),
        val aiConfirmed: Set<String> = emptySet(),
        val aiRejected: Set<String> = emptySet(),
        val aiNote: String? = null
    )

    private val _bring = MutableStateFlow(BringLocal())

    /** v1.0.4 (D14): parser-rules editor state. */
    private data class ParserLocal(
        val open: Boolean = false,
        val saving: Boolean = false,
        val message: String? = null
    )

    private val _parser = MutableStateFlow(ParserLocal())

    /** The engine chip's text (v1.0.3) for the AI-match surface. */
    val engineLabel: StateFlow<String> = container.aiHub.chipLabel
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            "AI · Automatic"
        )

    /** The library snapshot the last scan matched against — the AI
     * assist shortlists from the same snapshot, so a proposal can
     * never point at a book the scan didn't see. */
    private var lastLibrarySnapshot: List<app.xreader.data.extension.ExistingBookState> =
        emptyList()

    /** Source books from the last bookshelf scan, keyed by the
     * source's own book id — the import step resolves decisions
     * against this map. */
    private var scannedBooks: Map<String, app.xreader.extensionapi.SourceBook> =
        emptyMap()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _gone = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val gone: SharedFlow<Unit> = _gone.asSharedFlow()

    val state: StateFlow<ExtensionDetailUiState> = combine(
        container.database.installedExtensionDao().observeById(extensionId),
        _sessionSaved, _rules, _test, _linkConfig, _health, _bring, _parser,
        container.sourcePrefs.browserViewNeededIds
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val ext = values[0] as InstalledExtension?
        val test = values[3] as Pair<String?, List<String>>
        val health = values[5] as HealthLocal
        val bring = values[6] as BringLocal
        val parser = values[7] as ParserLocal
        @Suppress("UNCHECKED_CAST")
        val browserNeededIds = values[8] as Set<Long>
        ExtensionDetailUiState(
            extension = ext,
            pending = ext?.let { container.extensionStore.readPending(it.packageId) },
            sessionSaved = values[1] as Boolean,
            cleanerRules = values[2] as List<CleanerRuleData>,
            testOutput = test.first,
            testNotes = test.second,
            linkConfig = values[4] as app.xreader.data.extension.DeclarativeConfig?,
            loading = false,
            health = health.record,
            healthResults = health.results,
            testing = health.testing,
            repairing = health.repairing,
            lastRepair = health.repairRecord,
            browserViewNeeded = extensionId in browserNeededIds,
            hasBookshelf = bring.hasBookshelf,
            bringBusy = bring.busy,
            bringPreview = bring.preview,
            bringMessage = bring.message,
            aiMatchBusy = bring.aiBusy,
            aiProposals = bring.aiProposals,
            aiConfirmedMatches = bring.aiConfirmed,
            aiMatchNote = bring.aiNote,
            parserEditorOpen = parser.open,
            parserSaving = parser.saving,
            parserMessage = parser.message
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        ExtensionDetailUiState()
    )

    init {
        viewModelScope.launch {
            container.database.installedExtensionDao().getById(extensionId)?.let { ext ->
                if (ext.packageId.startsWith("link.")) {
                    val raw = container.extensionStore.readText(
                        container.extensionStore.definitionFile(ext.packageId)
                    )
                    _linkConfig.value = raw?.let {
                        app.xreader.data.extension.DeclarativeFingerprints.decode(it)
                    }?.firstOrNull()
                }
                _rules.value = container.cleanerRuleStore.extensionRules(ext.packageId)
                _sessionSaved.value = container.sessionStore.has(
                    container.repoManagerSessionKey(extensionId)
                )
            }
            // R10: show the last known health immediately.
            val handles = container.extensionService.sourcesOf(extensionId)
                .getOrNull()
            val firstSourceId = handles?.firstOrNull()?.source?.id
            if (firstSourceId != null) {
                val key = app.xreader.data.extension.SourceHealthRecord
                    .key(extensionId, firstSourceId)
                val record = container.sourcePrefs.sourceHealth
                    .first()[key]
                if (record != null) {
                    _health.value = _health.value.copy(record = record)
                }
                // v1.0.6: the last Repair's record lives under
                // the same key — show it with the health.
                val repairRecord = container.sourcePrefs.sourceRepairLog
                    .first()[key]
                if (repairRecord != null) {
                    _health.value = _health.value.copy(
                        repairRecord = repairRecord
                    )
                }
            }
            // R24: does this source know its account bookshelf?
            val firstHandle = handles?.firstOrNull()
            if (firstHandle != null) {
                val config = container.extensionService
                    .declarativeConfigOf(firstHandle)
                if (config?.bookshelf != null) {
                    _bring.value = _bring.value.copy(hasBookshelf = true)
                }
            }
        }
    }

    /** R24 step 1 — scan the account bookshelf (login session
     * applies) and show the reconciliation preview. Nothing is
     * written by a scan. */
    fun scanBookshelf() {
        viewModelScope.launch {
            val ext = current() ?: return@launch
            val sourceId = ext.sourceIds.split(",").firstOrNull()?.trim()
                ?: return@launch
            val handle = container.extensionService
                .source(ext.id, sourceId).getOrNull() ?: return@launch
            _bring.value = _bring.value.copy(
                busy = true, message = null,
                aiBusy = false, aiProposals = emptyMap(),
                aiConfirmed = emptySet(), aiRejected = emptySet(),
                aiNote = null
            )
            val found = mutableListOf<app.xreader.extensionapi.SourceBook>()
            var page = 1
            var failed = false
            while (page <= 10) {
                when (val result =
                    container.extensionService.bookshelf(handle, page)) {
                    is AppResult.Ok -> {
                        if (result.value.books.isEmpty()) break
                        found += result.value.books
                        if (!result.value.hasNextPage) break
                        page++
                    }
                    is AppResult.Err -> {
                        failed = true
                        break
                    }
                }
            }
            if (failed) {
                _bring.value = _bring.value.copy(
                    busy = false,
                    message = "Your bookshelf didn't load. Log in on " +
                        "this source first, then scan again."
                )
                return@launch
            }
            scannedBooks = found.associateBy { it.id }
            val library = container.database.bookDao().observeAllBooks()
                .first()
                .filter { it.mergedIntoId == null }
                .map { book ->
                    app.xreader.data.extension.ExistingBookState(
                        bookId = book.id,
                        remoteKey = if (book.extensionId == ext.id) {
                            book.remoteId
                        } else {
                            null
                        },
                        title = book.title,
                        author = book.author ?: ""
                    )
                }
            lastLibrarySnapshot = library
            val preview = app.xreader.data.extension.AccountImportPlanner
                .plan(
                    accountBooks = found.map { book ->
                        app.xreader.data.extension.AccountBook(
                            remoteKey = book.id,
                            title = book.title,
                            author = book.author ?: ""
                        )
                    },
                    existing = library,
                    selectedCategories = setOf(
                        app.xreader.data.extension.AccountCategory.BOOKSHELF
                    ),
                    chapterIndexForKey = { _, _ -> null },
                    nearestEarlier = { _, _ -> null }
                )
            _bring.value = _bring.value.copy(busy = false, preview = preview)
        }
    }

    /** R24 step 2 — apply the preview: CREATE decisions open the
     * book through the identity-safe path. No chapter bodies are
     * downloaded, ever. */
    fun importBookshelf() {
        viewModelScope.launch {
            val preview = _bring.value.preview ?: return@launch
            val ext = current() ?: return@launch
            val sourceId = ext.sourceIds.split(",").firstOrNull()?.trim()
                ?: return@launch
            val handle = container.extensionService
                .source(ext.id, sourceId).getOrNull() ?: return@launch
            _bring.value = _bring.value.copy(busy = true)
            // v1.0.3: books the user confirmed as AI-proposed matches
            // are already in the Library under another listing — they
            // are counted, never added a second time.
            val confirmedMatches = _bring.value.aiConfirmed
            var imported = 0
            var failedCount = 0
            var matchedCount = 0
            for (decision in preview.decisions) {
                if (decision.action !=
                    app.xreader.data.extension.ImportAction.CREATE
                ) {
                    continue
                }
                if (decision.account.remoteKey in confirmedMatches) {
                    matchedCount++
                    continue
                }
                val book = scannedBooks[decision.account.remoteKey]
                    ?: continue
                when (container.sourceBookSync.openBook(handle, book)) {
                    is AppResult.Ok -> imported++
                    is AppResult.Err -> failedCount++
                }
            }
            _bring.value = _bring.value.copy(
                busy = false,
                preview = null,
                message = buildString {
                    append("Brought over $imported new books. ")
                    append(
                        "${preview.alreadyPresent + matchedCount} were already in " +
                            "your Library."
                    )
                    if (matchedCount > 0) {
                        append(
                            " ($matchedCount matched to your existing copies " +
                                "with AI help, as you confirmed.)"
                        )
                    }
                    if (failedCount > 0) {
                        append(" $failedCount couldn't be added.")
                    }
                    if (preview.mappablePositions == 0) {
                        append(
                            " This site's bookshelf page doesn't show " +
                                "reading places, so only the books " +
                                "came over."
                        )
                    }
                }
            )
        }
    }

    fun dismissBringPreview() {
        _bring.value = _bring.value.copy(preview = null)
    }

    /**
     * v1.0.3 (AI hub): ask the AI to spot same-work matches among
     * the preview's CREATE decisions — the ones exact matching left
     * behind. Every answer becomes a PROPOSAL in state; nothing is
     * treated as a match until [confirmAiMatch]. Deliberate-tap
     * only (the user's engine quota is spent by asking, and the
     * button says so), capped per pass, and honest when the engine
     * is off, unconfigured, or finds nothing.
     */
    fun checkAiMatches() {
        val preview = _bring.value.preview ?: return
        if (_bring.value.aiBusy) return
        viewModelScope.launch {
            _bring.value = _bring.value.copy(aiBusy = true, aiNote = null)
            if (!container.aiHub.client.hasKey()) {
                _bring.value = _bring.value.copy(
                    aiBusy = false,
                    aiNote = "AI matching needs an AI key — add one in " +
                        "More → Providers & keys (a free Gemini key works)."
                )
                return@launch
            }
            val claimed = preview.decisions
                .mapNotNull { it.existingBookId }.toSet()
            val creates = preview.decisions.filter {
                it.action == app.xreader.data.extension.ImportAction.CREATE
            }
            val proposals = mutableMapOf<String, app.xreader.data.ai.LibraryMatchAi.Proposal>()
            var checked = 0
            var failed = false
            for (decision in creates) {
                if (checked >= MAX_AI_MATCH_CHECKS) break
                val shortlist = app.xreader.data.ai.FuzzyShortlist.shortlist(
                    decision.account, lastLibrarySnapshot, claimed
                )
                if (shortlist.isEmpty()) continue
                checked++
                when (
                    val result = container.libraryMatchAi.adjudicate(
                        decision.account, shortlist
                    )
                ) {
                    is AppResult.Ok -> result.value?.let { proposal ->
                        proposals[decision.account.remoteKey] = proposal
                    }
                    is AppResult.Err -> {
                        failed = true
                        break
                    }
                }
            }
            _bring.value = _bring.value.copy(
                aiBusy = false,
                aiProposals = proposals,
                aiNote = when {
                    failed -> "The AI couldn't finish checking — the matches " +
                        "it found so far are shown above."
                    proposals.isEmpty() ->
                        "The AI didn't spot any likely matches among the " +
                            "new books."
                    else -> null
                }
            )
        }
    }

    /** The user confirms an AI proposal: at import, this book is
     * counted as already in the Library and is NOT added again. */
    fun confirmAiMatch(remoteKey: String) {
        _bring.value = _bring.value.copy(
            aiConfirmed = _bring.value.aiConfirmed + remoteKey
        )
    }

    /** The user rejects a proposal: it leaves the list and the book
     * imports as new, as the exact planner decided. */
    fun rejectAiMatch(remoteKey: String) {
        _bring.value = _bring.value.copy(
            aiProposals = _bring.value.aiProposals - remoteKey,
            aiRejected = _bring.value.aiRejected + remoteKey
        )
    }

    /** R10: run the full-path test-drive on this extension's
     * (first) source and persist the verdict. */
    fun runTestDrive() {
        if (_health.value.testing) return
        _health.value = _health.value.copy(testing = true)
        // v1.0.6.1 (P2): what this drive is testing — checked
        // again when the verdict lands (see healthEpoch).
        val epochAtStart = healthEpoch
        val testedConfigId = _linkConfig.value?.id
        viewModelScope.launch {
            try {
                val handles = container.extensionService.sourcesOf(extensionId)
                    .getOrNull()
                val handle = handles?.firstOrNull()
                if (handle == null) {
                    say("This source couldn't be loaded to test it.")
                    return@launch
                }
                val verdict = container.contractTester.test(handle)
                val record = verdict.toRecord()
                if (epochAtStart != healthEpoch ||
                    testedConfigId != _linkConfig.value?.id
                ) {
                    // The rules changed under this drive (a
                    // Repair saved while it ran). Its verdict
                    // describes the OLD rules — persisting it
                    // would resurrect the stale failure the
                    // Repair just cleared.
                    say(
                        "The rules changed while that test-drive " +
                            "was running, so its result was set " +
                            "aside — run the test-drive again."
                    )
                    return@launch
                }
                container.sourcePrefs.setSourceHealth(
                    app.xreader.data.extension.SourceHealthRecord.key(
                        extensionId, handle.source.id
                    ),
                    record
                )
                _health.value = _health.value.copy(
                    record = record, results = verdict.results
                )
                // v1.0.6.1 (P6): a drive that found a failing
                // core step joins the session's problem history,
                // so its evidence rides along in Copy details.
                if (record.failedCore.isNotEmpty()) {
                    app.xreader.data.extension.DiagnosticLog.record(
                        "test-drive",
                        record.summaryLine(),
                        app.xreader.data.extension.CookieParsing
                            .hostOf(_linkConfig.value?.baseUrl.orEmpty())
                            ?.let { "site: $it" }
                            ?: "source: ${handle.source.id}"
                    )
                }
                say("Test-drive finished: ${record.summaryLine()}")
            } finally {
                _health.value = _health.value.copy(testing = false)
            }
        }
    }

    /**
     * v1.0.6.1 (P2): a Repair just saved new rules — the OLD
     * health verdict describes rules that no longer exist, so
     * clear it everywhere: the persisted record (under every
     * identity it may have been stored by — the pre-save record
     * can sit under the old config's source id) and the
     * in-memory state, which falls back to the Health card's
     * existing honest "not checked yet" presentation. The epoch
     * bump also retires any test-drive still running against
     * the old rules (see runTestDrive).
     */
    private suspend fun clearStaleHealth(oldConfigId: String) {
        healthEpoch++
        val candidateIds = buildSet {
            add(oldConfigId)
            _linkConfig.value?.id?.let { add(it) }
            container.extensionService.sourcesOf(extensionId).getOrNull()
                ?.firstOrNull()?.source?.id?.let { add(it) }
        }
        candidateIds.forEach { sourceId ->
            runCatching {
                container.sourcePrefs.clearSourceHealth(
                    app.xreader.data.extension.SourceHealthRecord.key(
                        extensionId, sourceId
                    )
                )
            }
        }
        _health.value = _health.value.copy(record = null, results = emptyList())
    }

    /**
     * R10 repair for link-added sources: the site's pages are
     * re-read and the rules rebuilt (the same probe that created
     * the source), then the test-drive runs again. The current
     * definition is backed up first; if the rebuild can't be saved
     * or the probe fails, the backup is restored and the user is
     * told plainly — a repair never leaves a source worse off.
     * Repo-installed sources can't be rebuilt this way; their
     * repair is the test-drive plus an honest note.
     */
    fun repair() {
        val ext = state.value.extension ?: return
        if (!ext.packageId.startsWith("link.")) {
            say(
                "This source came from a repository, so Xreader can't " +
                    "rebuild its rules. Run the test-drive; if a step " +
                    "fails, the source's repository needs to update it."
            )
            return
        }
        val config = _linkConfig.value ?: return
        if (_health.value.repairing) return
        _health.value = _health.value.copy(repairing = true)
        viewModelScope.launch {
            try {
                val file = container.extensionStore.definitionFile(ext.packageId)
                val backup = container.extensionStore.readText(file)
                // v1.0.6: never seed the probe blindly from the
                // stored sampleBookUrl — a stored sample can be
                // the one book no config could ever prove
                // against (the device case: a book whose every
                // listed chapter is paid or locked). Pick a
                // fresh sample from the live catalogue,
                // preferring a book with an unlocked chapter;
                // fall back to the stored seed only when the
                // catalogue can't be read at all.
                val probeUrl = runCatching {
                    container.linkProber.freshSampleUrl(config)
                }.getOrNull() ?: config.sampleBookUrl ?: config.baseUrl
                // v1.0.6: the persisted outcome — filled in by
                // the branches below and written to SourcePrefs
                // when the repair finishes, so what Repair did
                // (and what each proposer did) stays on this
                // page instead of vanishing with its message.
                var repairSaved = false
                var repairNote = ""
                var repairStageLines: List<String> = emptyList()
                // v1.0.5 (A4): Repair drives EVERY automatic
                // proposer (fast lane → templates/inference →
                // heuristic) and keeps the best passing candidate;
                // when all fail, the AI detector gets its turn too
                // (below), and the final message names the step
                // that actually broke. aiAvailable stays false:
                // this flow never renders an AI button, so the
                // probe's own failure copy must not promise one.
                when (val outcome = container.linkProber.probe(
                    probeUrl, aiAvailable = false, tryAllProposers = true,
                    // v1.0.6.2 (F5): the borderline-list AI verify
                    // shares the Add flow's single call per run.
                    semanticVerifier = { titles, excerpt ->
                        container.selectorAi.verifySample(titles, excerpt)
                    }
                )) {
                    is app.xreader.data.extension.LinkProber.Outcome.Ready -> {
                        repairStageLines = outcome.stageLines.map { it.render() }
                        when (
                            val saved = container.repoManager.saveLinkSource(
                                outcome.draft.config, outcome.draft.host,
                                // v1.0.6.1 (P5): write into THIS
                                // row's package, never one derived
                                // from the rebuilt config's id.
                                packageIdOverride = ext.packageId
                            )
                        ) {
                            is AppResult.Ok -> {
                                _linkConfig.value = outcome.draft.config
                                // v1.0.6.1 (P2): the old health
                                // verdict described the old
                                // rules — clear it before the
                                // fresh test-drive runs.
                                clearStaleHealth(config.id)
                                repairSaved = true
                                repairNote =
                                    "Repair rebuilt the rules from the live site."
                                say(
                                    "Repair rebuilt the rules from the live " +
                                        "site. Running the test-drive…"
                                )
                                runTestDrive()
                            }
                            is AppResult.Err -> {
                                if (backup != null) {
                                    container.extensionStore.writeText(file, backup)
                                }
                                repairNote =
                                    "Repair couldn't save the rebuilt rules — " +
                                        "your old ones were kept."
                                say(
                                    "Repair couldn't save the rebuilt rules — " +
                                        "your old ones were kept."
                                )
                            }
                        }
                    }
                    is app.xreader.data.extension.LinkProber.Outcome.LoginRequired -> {
                        repairNote =
                            "Repair needs you signed in first — use " +
                                "“Sign in” on this source, then try Repair again. " +
                                "Nothing was changed."
                        say(
                            "Repair needs you signed in first — use " +
                                "“Sign in” on this source, then try Repair again. " +
                                "Nothing was changed."
                        )
                    }
                    is app.xreader.data.extension.LinkProber.Outcome.Failed -> {
                        repairStageLines = outcome.stageLines.map { it.render() }
                        // v1.0.5 (A4): the last proposer — the AI
                        // detector, when a key exists. Its proposal
                        // faces the same mandatory test-drive
                        // inside SelectorAi before it can be saved.
                        val aiOutcome = if (
                            runCatching { container.aiHub.client.hasKey() }
                                .getOrDefault(false)
                        ) {
                            container.selectorAi.buildFromLink(probeUrl)
                        } else {
                            null
                        }
                        when (aiOutcome) {
                            is app.xreader.data.ai.SelectorAi.Outcome.Ready -> {
                                repairStageLines = repairStageLines +
                                    "AI detector — passed: its rules passed " +
                                    "the test-drive"
                                when (
                                    container.repoManager.saveLinkSource(
                                        aiOutcome.draft.config, aiOutcome.draft.host,
                                        // v1.0.6.1 (P5): this
                                        // row's package (see the
                                        // probe save above).
                                        packageIdOverride = ext.packageId
                                    )
                                ) {
                                    is AppResult.Ok -> {
                                        _linkConfig.value = aiOutcome.draft.config
                                        // v1.0.6.1 (P2): clear
                                        // the stale verdict (see
                                        // the probe save above).
                                        clearStaleHealth(config.id)
                                        repairSaved = true
                                        repairNote =
                                            "Repair rebuilt the rules with the " +
                                                "AI detector."
                                        say(
                                            "Repair rebuilt the rules with the AI " +
                                                "detector. Running the test-drive…"
                                        )
                                        runTestDrive()
                                    }
                                    is AppResult.Err -> {
                                        repairNote =
                                            "Repair found working rules with " +
                                                "the AI detector, but they " +
                                                "couldn't be saved — your old " +
                                                "ones were kept."
                                        say(
                                            "Repair found working rules with the AI " +
                                                "detector, but they couldn't be saved — " +
                                                "your old ones were kept."
                                        )
                                    }
                                }
                            }
                            else -> {
                                val failureMessage =
                                    "Repair tried the site's known shape, the " +
                                    "automatic detectors" +
                                    (if (aiOutcome != null) " and the AI detector" else "") +
                                    ", and none could read the site well enough. " +
                                    "The step that got furthest said: ${outcome.message} " +
                                    "Your old rules were kept." +
                                    // v1.0.5.1: name the stages the
                                    // probe actually evaluated, so
                                    // the next diagnosis starts
                                    // from facts, not guesses.
                                    (if (outcome.proposersTried.isNotEmpty()) {
                                        " Repair tried: " +
                                            outcome.proposersTried
                                                .joinToString(", ") + "."
                                    } else {
                                        ""
                                    }) +
                                    // v1.0.5.1: when the AI sat this
                                    // repair out for want of a key
                                    // (aiOutcome null means it was
                                    // never called), say so out
                                    // loud — the AI's existence
                                    // must never be invisible (the
                                    // D16 lesson, in reverse).
                                    (if (aiOutcome == null) {
                                        " The AI detector could also " +
                                            "try — add a free Gemini key " +
                                            "in Me → Providers & keys " +
                                            "and run Repair again."
                                    } else {
                                        ""
                                    })
                                repairNote = failureMessage
                                repairStageLines = repairStageLines +
                                    when (aiOutcome) {
                                        is app.xreader.data.ai.SelectorAi
                                            .Outcome.Failed ->
                                            "AI detector — failed: " +
                                                aiOutcome.message.take(160)
                                        is app.xreader.data.ai.SelectorAi
                                            .Outcome.LoginRequired ->
                                            "AI detector — sign-in needed: " +
                                                "it asked for a sign-in first"
                                        else ->
                                            "AI detector — not tried: no " +
                                                "AI key is saved"
                                    }
                                say(failureMessage)
                            }
                        }
                    }
                }
                // v1.0.6: persist the finished Repair — when,
                // saved-or-not, the final note, and the probe's
                // stage log — under the same per-source key as
                // the health record, and show it on this page.
                val repairRecord =
                    app.xreader.data.extension.SourceRepairRecord(
                        repairedAtMillis = System.currentTimeMillis(),
                        saved = repairSaved,
                        note = repairNote,
                        stageLines = repairStageLines
                    )
                val repairKeySourceId = container.extensionService
                    .sourcesOf(extensionId).getOrNull()
                    ?.firstOrNull()?.source?.id
                    ?: _linkConfig.value?.id
                    ?: config.id
                container.sourcePrefs.setSourceRepairLog(
                    app.xreader.data.extension.SourceHealthRecord.key(
                        extensionId, repairKeySourceId
                    ),
                    repairRecord
                )
                // v1.0.6.1 (P6): a Repair that didn't save joins
                // the session's problem history — the note and
                // the site, so Copy details carries what Repair
                // concluded, not just that it ran.
                if (!repairSaved && repairNote.isNotBlank()) {
                    app.xreader.data.extension.DiagnosticLog.record(
                        "repair",
                        repairNote,
                        "site: " + (
                            app.xreader.data.extension.CookieParsing
                                .hostOf(config.baseUrl) ?: config.baseUrl
                            )
                    )
                }
                // v1.0.6 (TR4): a Repair that saved new rules has
                // answered the "site changed" flag one way or the
                // other — clear it; if the site still serves
                // shells unmarked, the next fetch re-records it.
                if (repairSaved) {
                    runCatching {
                        container.sourcePrefs.clearBrowserViewNeeded(extensionId)
                    }
                }
                _health.value = _health.value.copy(repairRecord = repairRecord)
            } finally {
                _health.value = _health.value.copy(repairing = false)
            }
        }
    }

    private fun say(message: String) {
        _messages.tryEmit(message)
    }

    /** v1.0.1: "Set up search" retry for link-added sources whose
     * search never verified — re-runs detection against the book the
     * source was proven on, and saves the fixed rules in place. */
    fun setupSearch() {
        val config = _linkConfig.value ?: return
        viewModelScope.launch {
            when (val result = container.linkProber.setupSearch(config)) {
                is AppResult.Ok -> {
                    val updated = result.value
                    val host = app.xreader.data.extension.CookieParsing
                        .hostOf(updated.baseUrl) ?: ""
                    when (container.repoManager.saveLinkSource(updated, host)) {
                        is AppResult.Ok -> {
                            _linkConfig.value = updated
                            say("Search is set up for this source now.")
                        }
                        is AppResult.Err ->
                            say("Search was found, but the fixed rules couldn't be saved.")
                    }
                }
                is AppResult.Err -> say(ErrorText.displayText(result.error))
            }
        }
    }

    // ── v1.0.4 (D14): Edit parser rules ──────────────────────────

    fun openParserEditor() {
        _parser.value = ParserLocal(open = true)
    }

    fun closeParserEditor() {
        _parser.value = _parser.value.copy(open = false, message = null)
    }

    /**
     * The gated save: build the candidate from the edited fields,
     * run the SAME mandatory test-drive every built source faces
     * ([app.xreader.data.extension.LinkProber.testDrive]), and only
     * a passing candidate replaces the stored rules. A failed test
     * read keeps the working rules and says why — the editor stays
     * open so the user can keep fixing or back out.
     */
    fun saveParserRules(
        fields: app.xreader.data.extension.ParserRulesEditor.Fields
    ) {
        val original = _linkConfig.value ?: return
        val ext = state.value.extension ?: return
        if (!ext.packageId.startsWith("link.")) return
        if (_parser.value.saving) return
        when (
            val built = app.xreader.data.extension.ParserRulesEditor
                .buildCandidate(original, fields)
        ) {
            is AppResult.Err -> _parser.value = _parser.value.copy(
                message = ErrorText.displayText(built.error)
            )
            is AppResult.Ok -> {
                val candidate = built.value
                _parser.value = _parser.value.copy(
                    saving = true, message = null
                )
                viewModelScope.launch {
                    try {
                        val drive = if (candidate.sampleBookUrl != null) {
                            container.linkProber.testDrive(
                                candidate, candidate.sampleBookUrl
                            )
                        } else {
                            container.linkProber.testDriveFromCatalogue(
                                candidate
                            )
                        }
                        when (drive) {
                            is AppResult.Err -> _parser.value =
                                _parser.value.copy(
                                    message = "The test read failed, so " +
                                        "your old rules were kept. " +
                                        ErrorText.displayText(drive.error)
                                )
                            is AppResult.Ok -> {
                                // Mirror the probe's own bookkeeping:
                                // search counts as set up only when
                                // the test read verified it.
                                val proven = candidate.copy(
                                    searchNeedsSetup =
                                        candidate.search == null ||
                                            !drive.value.searchVerified
                                )
                                val host = app.xreader.data.extension
                                    .CookieParsing.hostOf(proven.baseUrl)
                                    ?: ""
                                when (
                                    container.repoManager.saveLinkSource(
                                        proven, host
                                    )
                                ) {
                                    is AppResult.Ok -> {
                                        _linkConfig.value = proven
                                        _parser.value = ParserLocal()
                                        say(
                                            "Parser rules saved — the test " +
                                                "read passed: opened " +
                                                "“${drive.value.bookTitle}”, " +
                                                "${drive.value.chapterCount} " +
                                                "chapters, and the first " +
                                                "chapter's text read fine."
                                        )
                                    }
                                    is AppResult.Err -> _parser.value =
                                        _parser.value.copy(
                                            message = "The new rules passed " +
                                                "the test read, but they " +
                                                "couldn't be saved — your " +
                                                "old rules are still in " +
                                                "place."
                                        )
                                }
                            }
                        }
                    } finally {
                        _parser.value = _parser.value.copy(saving = false)
                    }
                }
            }
        }
    }

    fun refreshSession() {
        viewModelScope.launch {
            _sessionSaved.value = container.sessionStore.has(
                container.repoManagerSessionKey(extensionId)
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        withExtension { ext -> container.repoManager.setEnabled(ext, enabled) }
    }

    fun toggleDomain(domain: String, allowed: Boolean) {
        withExtension { ext ->
            val permissions = ExtensionPermissions.parse(ext.permissionsJson)
            val blocked = permissions.blockedDomains.toMutableSet()
            if (allowed) blocked.remove(domain) else blocked.add(domain)
            container.repoManager.updatePermissions(
                ext, permissions.copy(blockedDomains = blocked.toList())
            )
            say(
                if (allowed) "$domain allowed again" else "$domain blocked for this source"
            )
        }
    }

    fun setCanNotify(canNotify: Boolean) {
        withExtension { ext ->
            val permissions = ExtensionPermissions.parse(ext.permissionsJson)
            container.repoManager.updatePermissions(
                ext, permissions.copy(canNotify = canNotify)
            )
        }
    }

    fun setDexOptIn(optIn: Boolean) {
        withExtension { ext ->
            val permissions = ExtensionPermissions.parse(ext.permissionsJson)
            container.repoManager.updatePermissions(
                ext, permissions.copy(dexOptIn = optIn)
            )
            say(
                if (optIn) "App code allowed for this source"
                else "App code switched off for this source"
            )
        }
    }

    fun retrust() {
        viewModelScope.launch {
            val ext = current() ?: return@launch
            when (val result = container.repoManager.retrust(ext)) {
                is AppResult.Err -> say(ErrorText.displayText(result.error))
                is AppResult.Ok -> say("“${ext.displayName}” trusted again")
            }
        }
    }

    fun acceptPending() {
        viewModelScope.launch {
            val ext = current() ?: return@launch
            when (val result = container.repoManager.acceptPendingUpdate(ext)) {
                is AppResult.Err -> say(ErrorText.displayText(result.error))
                is AppResult.Ok -> say("Update applied — “${ext.displayName}” is on v${result.value.versionName}")
            }
        }
    }

    fun rejectPending() {
        viewModelScope.launch {
            val ext = current() ?: return@launch
            container.repoManager.rejectPendingUpdate(ext)
            say("Update discarded — the version you trusted stays")
        }
    }

    fun forgetSession() {
        viewModelScope.launch {
            container.sessionStore.clear(container.repoManagerSessionKey(extensionId))
            _sessionSaved.value = false
            say("Saved sign-in forgotten")
        }
    }

    fun uninstall() {
        viewModelScope.launch {
            val ext = current() ?: return@launch
            container.repoManager.uninstall(ext)
            say("“${ext.displayName}” uninstalled")
            _gone.tryEmit(Unit)
        }
    }

    // ── cleaner rules ───────────────────────────────────────────────

    fun addRule(pattern: String, replacement: String) {
        val ext = state.value.extension ?: return
        if (pattern.isBlank()) return
        val updated = _rules.value + CleanerRuleData(pattern.trim(), replacement)
        saveRules(ext, updated)
    }

    fun toggleRule(index: Int, enabled: Boolean) {
        val ext = state.value.extension ?: return
        val updated = _rules.value.mapIndexed { i, rule ->
            if (i == index) rule.copy(enabled = enabled) else rule
        }
        saveRules(ext, updated)
    }

    fun deleteRule(index: Int) {
        val ext = state.value.extension ?: return
        saveRules(ext, _rules.value.filterIndexed { i, _ -> i != index })
    }

    fun testRules(sample: String) {
        val stack = _rules.value.map { CleanerEngine.TimedRule(it, "extension") }
        viewModelScope.launch {
            val outcome = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.Default
            ) {
                CleanerEngine.clean(sample, stack, timeoutMsPerRule = 500)
            }
            _test.value = outcome.text to outcome.notes
            // Timed-out rules get disabled in the editor too — the same
            // consequence as in the fetch path (§9.4).
            if (outcome.disabledRules.isNotEmpty()) {
                val ext = state.value.extension ?: return@launch
                val disabledPatterns = outcome.disabledRules.map { it.rule.pattern }.toSet()
                saveRules(
                    ext,
                    _rules.value.map { rule ->
                        if (rule.pattern in disabledPatterns) rule.copy(enabled = false)
                        else rule
                    }
                )
            }
        }
    }

    private fun saveRules(ext: InstalledExtension, rules: List<CleanerRuleData>) {
        _rules.value = rules
        container.cleanerRuleStore.saveExtensionRules(ext.packageId, rules)
    }

    private suspend fun current(): InstalledExtension? =
        container.database.installedExtensionDao().getById(extensionId)

    private fun withExtension(block: suspend (InstalledExtension) -> Unit) {
        viewModelScope.launch {
            val ext = current() ?: return@launch
            block(ext)
        }
    }

    companion object {
        /** v1.0.3: per-pass cap on AI match adjudications — a huge
         * bookshelf shouldn't turn one tap into hundreds of calls. */
        const val MAX_AI_MATCH_CHECKS = 12

        fun factory(
            container: AppContainer,
            extensionId: Long
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ExtensionDetailViewModel(container, extensionId) as T
        }
    }
}

/** Small helper so the VM doesn't reach for the client companion. */
private fun AppContainer.repoManagerSessionKey(extensionId: Long): String =
    app.xreader.data.extension.ExtensionHttpClient.sessionKeyFor(extensionId)
