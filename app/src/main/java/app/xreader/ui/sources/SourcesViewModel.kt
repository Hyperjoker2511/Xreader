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
    val mode: LoginMode = LoginMode.SIGN_IN,
    /** v1.3.4 (U2/U3): the exact page the sanctioned screen
     * should open when the failure happened somewhere specific
     * — e.g. a source's READER page, where its human check
     * widget lives (FenrirRealm's read-token check). Null =
     * the site root ([url]), exactly as before. */
    val targetUrl: String? = null,
    /** v1.5.2 fix (FIX-2, §1 residue a): the source's configured
     * hosts — the capture candidate set is widened to these, so a
     * session whose cookies live on a configured host the login
     * page never visited is captured too. */
    val configuredHosts: List<String> = emptyList()
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
    /** v1.3.3 (scope S7c): the repo row created by the most
     * recent NATIVE add — a successful add navigates straight
     * to the new repo's own page. */
    val lastAddedRepoId: Long? = null,
    /** R1: the plain session word per source ("extId:sourceId"):
     * "Working" / "Check needed" / "Sign in needed" / "Signed in". */
    val sessionWords: Map<String, String> = emptyMap(),
    /** Item 84: the one-time restore card has been answered. */
    val restoreCardDone: Boolean = true,
    /** Manga programme (M1): the reader's ONE content
     * preference — which source ratings are listed. */
    val contentVisibility:
        app.xreader.data.extension.ContentVisibility =
        app.xreader.data.extension.ContentVisibility.DEFAULT,
    /** Manga programme (M1): each installed source's content
     * rating, keyed "extId:sourceId" like [sessionWords] —
     * the Explore list hides an extension only when EVERY
     * source it carries is hidden, and badges the strongest
     * rating still visible. Sources with no declarative
     * config carry no entry and read as Safe. */
    val sourceRatings:
        Map<String, app.xreader.data.extension.ContentRating> =
        emptyMap(),
    /** Manga programme (M3): each installed source's media
     * kind, keyed "extId:sourceId" like [sourceRatings] —
     * Explore's Novels | Manga switch filters on it. Sources
     * with no declarative config carry no entry and read as
     * Novels (TEXT). */
    val sourceKinds:
        Map<String, app.xreader.data.extension.MediaKind> =
        emptyMap(),
    /** M3: the split's active side — the SAME remembered
     * switch the Library carries (LibraryPrefs), so the two
     * screens always agree. */
    val librarySide: app.xreader.data.extension.MediaKind =
        app.xreader.data.extension.MediaKind.TEXT,
    /** M3: the Keiyoushi catalogue's display rows, built by
     * [app.xreader.data.extension.KeiyoushiIndex.rows] from
     * the repo's loaded site list — preference-filtered, each
     * with its true conversion state and rating badge. Empty
     * until the repo's list has loaded.
     * v1.4.3: PER-PACKAGE shelf rows (the donor's unit) from
     * [app.xreader.data.extension.KeiyoushiIndex.shelfRows]. */
    val keiyoushiRows:
        List<app.xreader.data.extension.KeiyoushiIndex.PackageRow> =
        emptyList(),
    /** v1.4.3: the packages whose install/update is on the
     * wire right now — the shelf's per-row progress + Cancel.
     * (The pre-1.4.3 shelf keyed every row's enabled state to
     * the one global [busy] flag, so a single stuck install
     * faded the whole list behind a progress bar, forever.) */
    val keiyoushiBusyPackages: Set<String> = emptySet(),
    /** K3: a staged Keiyoushi install waiting for the user's
     * consent — the sheet's data (honest DEX wording,
     * fingerprint, signer). Non-null only between a verified
     * download and the user's answer. */
    val keiyoushiConsent:
        app.xreader.data.extension.player.KeiyoushiConsentPrompt? =
        null,
    /** v1.5.9 (Finding 6): the browsing DEFAULT per installed
     * multi-source package, extension row id → sourceId —
     * the remembered pick when one exists, else the package's
     * primary-language instance, else its first declared one
     * ([MultiSourceDefault]). A multi-source row's tap opens
     * this instance's catalogue directly. Absent = no default
     * could be resolved (the package's handles didn't load);
     * the row then keeps the Details route, where the labelled
     * instance list lives. Single-source packages never appear
     * here — their one source is the default by construction. */
    val defaultSourceIds: Map<Long, String> = emptyMap(),
    /** v1.5.39 (item 4): how many installed Keiyoushi
     * packages have an update waiting (available > installed
     * in the player's PERSISTED records) — the Explore tab's
     * badge and the Explore banner read this one number. */
    val keiyoushiPendingUpdateCount: Int = 0,
    /** v1.5.39 (item 4): each installed Keiyoushi package's
     * signer (its verified cert SHA-256), package name →
     * cert — the shelf row's quiet signer line reads it. */
    val keiyoushiInstalledSigners: Map<String, String> = emptyMap()
)

/**
 * v1.5.8 (C4 / F7 F4): the Done-tap solve-quality record.
 * Writes ONE DiagnosticLog line stating whether the cookie
 * jar, as captured at Done-tap, held a clearance cookie
 * (`cf_clearance` — the name the challenge machinery itself
 * checks) for the source's host: the fallback host's own
 * captured set first, then any captured origin (the capture
 * is per-origin and the solve's page may sit on a sibling
 * host). Pure record — no behaviour change. Split out as a
 * plain function so the gate can drive it without a
 * ViewModel.
 */
internal fun recordSolveQuality(
    sessionKey: String,
    cookiesByHost: Map<String, Map<String, String>>,
    fallbackHost: String
) {
    val clearanceName = "cf_clearance"
    val held = cookiesByHost[fallbackHost]
        ?.containsKey(clearanceName) == true ||
        cookiesByHost.values.any { it.containsKey(clearanceName) }
    app.xreader.data.extension.DiagnosticLog.record(
        "solve-quality",
        "session saved for $sessionKey: clearance cookie for " +
            "host '${fallbackHost.ifBlank { "?" }}' " +
            if (held) {
                "HELD at Done-tap (cf_clearance present in the jar)"
            } else {
                "NOT held at Done-tap (no cf_clearance in the jar) — " +
                    "the save certifies a capture ran, not that a " +
                    "clearance was earned"
            },
        sessionKey
    )
}

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
    private val _lastAddedRepoId = MutableStateFlow<Long?>(null)
    private val _sessionWords = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _sourceRatings = MutableStateFlow<
        Map<String, app.xreader.data.extension.ContentRating>
    >(emptyMap())
    private val _sourceKinds = MutableStateFlow<
        Map<String, app.xreader.data.extension.MediaKind>
    >(emptyMap())
    private val _keiyoushiRows = MutableStateFlow<
        List<app.xreader.data.extension.KeiyoushiIndex.PackageRow>
    >(emptyList())
    private val _keiyoushiBusy = MutableStateFlow<Set<String>>(emptySet())
    /** v1.4.3: the running install/update job per package —
     * the row's Cancel lands on exactly its own job. */
    private val keiyoushiJobs =
        mutableMapOf<String, kotlinx.coroutines.Job>()
    private val _keiyoushiConsent = MutableStateFlow<
        app.xreader.data.extension.player.KeiyoushiConsentPrompt?
    >(null)
    /** v1.5.9 (Finding 6): the resolved browsing defaults
     * (see [SourcesUiState.defaultSourceIds]) + the tick that
     * re-resolves them when the screen returns to the
     * foreground — a pick made on a Details page in between
     * must move the Sources-list default. */
    private val _defaultSourceIds =
        MutableStateFlow<Map<Long, String>>(emptyMap())
    private val _instanceDefaultsTick = MutableStateFlow(0)

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
        container.sourcePrefs.sourceRestoreCardDone,
        _lastAddedRepoId,
        container.sourcePrefs.contentVisibility,
        _sourceRatings,
        container.libraryPrefs.librarySide,
        _sourceKinds, _keiyoushiRows, _keiyoushiConsent,
        _keiyoushiBusy, _defaultSourceIds,
        container.keiyoushiPlayerManager.records
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val rawExtensions = values[1] as List<InstalledExtension>
        val (orderedExtensions, twinIds) = arrangeSourceTwins(rawExtensions)
        // v1.5.39 (item 4): the player's persisted records —
        // the pending-update count and the installed signers
        // derive from them in the state below.
        @Suppress("UNCHECKED_CAST")
        val keiyoushiRecords = values[26] as Map<String,
            app.xreader.data.extension.player.KeiyoushiPlayerRecord>
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
            restoreCardDone = values[16] as Boolean,
            lastAddedRepoId = values[17] as Long?,
            contentVisibility =
                values[18] as app.xreader.data.extension.ContentVisibility,
            sourceRatings =
                values[19] as Map<String, app.xreader.data.extension.ContentRating>,
            librarySide =
                values[20] as app.xreader.data.extension.MediaKind,
            sourceKinds =
                values[21] as Map<String, app.xreader.data.extension.MediaKind>,
            keiyoushiRows =
                values[22] as List<app.xreader.data.extension.KeiyoushiIndex.PackageRow>,
            keiyoushiConsent =
                values[23] as
                    app.xreader.data.extension.player.KeiyoushiConsentPrompt?,
            keiyoushiBusyPackages = values[24] as Set<String>,
            defaultSourceIds = values[25] as Map<Long, String>,
            keiyoushiPendingUpdateCount = keiyoushiRecords.values
                .count {
                    it.isInstalled &&
                        it.availableVersionCode >
                        it.installedVersionCode
                },
            keiyoushiInstalledSigners = keiyoushiRecords.values
                .mapNotNull { record ->
                    record.certSha256
                        ?.takeIf { record.isInstalled }
                        ?.let { record.packageName to it }
                }.toMap()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourcesUiState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Emitted with the session key whenever a login session is saved. */
    private val _sessionSaved = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val sessionSaved: SharedFlow<String> = _sessionSaved.asSharedFlow()

    private val _loginRequest = MutableStateFlow<LoginRequest?>(null)
    val loginRequest: StateFlow<LoginRequest?> = _loginRequest

    /** v1.3.3 (scope S7a): repos whose sidecar reconstruction
     * this process has already attempted (success or not —
     * a dead repo must not be re-fetched on every emission). */
    private val reconstructionTried = mutableSetOf<Long>()

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
                // v1.3.3 (scope S7a): RECONSTRUCTION — a repo
                // added before v1.3.2 has a row but no sidecar,
                // so it never got its own page. Rebuild its
                // list from its own recorded address, once per
                // repo per process, quietly in the background.
                val missing = repos.filter { repo ->
                    repo.id !in foreign && repo.id !in cached &&
                        repo.indexUrl != RepoManager.LINK_REPO_URL &&
                        repo.indexUrl != RepoManager.BUNDLED_REPO_URL &&
                        reconstructionTried.add(repo.id)
                }
                missing.forEach { repo ->
                    viewModelScope.launch {
                        val rebuilt =
                            manager.reconstructForeignRepo(repo)
                        if (rebuilt is AppResult.Ok) {
                            _foreignRepos.value =
                                _foreignRepos.value +
                                    (repo.id to rebuilt.value)
                        }
                    }
                }
            }
        }
        // v1.5.10 (B2): ONE joined background pass replaces the
        // five separate observeAll() collectors that used to
        // sit here (session words, ratings, kinds, defaults,
        // Keiyoushi rows — Finding 7's Main-thread passes).
        // Collection stays on the collecting thread (cheap
        // flow plumbing); ALL computation runs inside the
        // pass on Dispatchers.Default; the five derived maps
        // publish together as one state write. The
        // fingerprint (ids + the fields the computations
        // actually read + picks-map hash + the
        // instance-defaults tick) gates recomputation: an
        // emission that changes nothing the maps read does
        // not recompute, while a pick change (seen via the
        // tick and the cached picks hash) and the tick both
        // force one — refreshInstanceDefaults() keeps working
        // by construction. Every compute body is
        // runCatching-wrapped per A2: a failure keeps the
        // last good map and logs; it never blanks the screen
        // and never kills the collector.
        viewModelScope.launch {
            combine(
                container.database.installedExtensionDao().observeAll(),
                container.sourcePrefs.sourceHealth,
                container.keiyoushiPlayerManager.packageStates,
                container.database.extensionRepoDao().observeAll(),
                _foreignRepos,
                container.sourcePrefs.contentVisibility,
                _instanceDefaultsTick
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                JoinedPassInputs(
                    extensions = values[0] as List<InstalledExtension>,
                    health = values[1] as Map<String, app.xreader.data.extension.SourceHealthRecord>,
                    playerStates = values[2] as Map<String, app.xreader.data.extension.KeiyoushiIndex.PlayerPackageState>,
                    repos = values[3] as List<ExtensionRepo>,
                    foreignRepos = values[4] as Map<Long, app.xreader.data.extension.RepoFormat.ForeignRepo>,
                    visibility = values[5] as app.xreader.data.extension.ContentVisibility,
                    tick = values[6] as Int
                )
            }.collect { inputs ->
                val fingerprint = sourcesPassFingerprint(
                    extensions = inputs.extensions,
                    health = inputs.health,
                    playerStates = inputs.playerStates,
                    repos = inputs.repos,
                    foreignRepos = inputs.foreignRepos,
                    visibility = inputs.visibility,
                    tick = inputs.tick,
                    picksHash = joinedPassPicksHash
                )
                val maps = joinedPass.run(
                    fingerprint, container.defaultDispatcher
                ) {
                    // Inside the pass (Default dispatcher):
                    // the picks read is memory-served (B3) and
                    // its hash joins the NEXT fingerprint, so a
                    // pick change forces the following pass —
                    // no file read ever runs on the collecting
                    // thread (C2 proxy).
                    joinedPassPicksHash =
                        container.extensionStore.readInstancePicks()
                            .hashCode()
                    SourcesDerivedMaps(
                        kinds = runCatching {
                            computeSourceKinds(inputs.extensions)
                        }.getOrElse {
                            logPassFailure("kinds", it); _sourceKinds.value
                        },
                        ratings = runCatching {
                            computeSourceRatings(inputs.extensions)
                        }.getOrElse {
                            logPassFailure("ratings", it); _sourceRatings.value
                        },
                        sessionWords = runCatching {
                            computeSessionWords(inputs.extensions, inputs.health)
                        }.getOrElse {
                            logPassFailure("sessionWords", it); _sessionWords.value
                        },
                        defaultSourceIds = runCatching {
                            computeDefaultSourceIds(inputs.extensions)
                        }.getOrElse {
                            logPassFailure("defaults", it); _defaultSourceIds.value
                        },
                        keiyoushiRows = runCatching {
                            computeKeiyoushiRows(
                                KeiyoushiInputs(
                                    inputs.repos, inputs.extensions,
                                    inputs.foreignRepos, inputs.visibility,
                                    inputs.playerStates
                                )
                            )
                        }.getOrElse {
                            logPassFailure("keiyoushiRows", it); _keiyoushiRows.value
                        }
                    )
                } ?: return@collect
                // One publish block: all five maps move
                // together, no suspension between them.
                _sourceKinds.value = maps.kinds
                _sourceRatings.value = maps.ratings
                _sessionWords.value = maps.sessionWords
                _defaultSourceIds.value = maps.defaultSourceIds
                _keiyoushiRows.value = maps.keiyoushiRows
            }
        }
    }

    /** v1.5.10 (B2): the joined pass's gate + counters. */
    private val joinedPass = JoinedSourcesPass()

    /** v1.5.10 (B2): hash of the picks map as last read
     * INSIDE the pass (Default thread, memory-served post-B3)
     * — part of the next emission's fingerprint, so a pick
     * change forces recompute without any file read ever
     * happening on the collecting thread (C2 proxy). */
    @Volatile
    private var joinedPassPicksHash: Int = 0

    private fun logPassFailure(part: String, error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        app.xreader.data.extension.DiagnosticLog.record(
            "sources-pass",
            "joined derived pass: $part failed — last good " +
                "state kept",
            error.javaClass.simpleName
        )
    }

    /** The joined pass's combined inputs (B2). */
    private data class JoinedPassInputs(
        val extensions: List<InstalledExtension>,
        val health: Map<String, app.xreader.data.extension.SourceHealthRecord>,
        val playerStates: Map<String, app.xreader.data.extension.KeiyoushiIndex.PlayerPackageState>,
        val repos: List<ExtensionRepo>,
        val foreignRepos: Map<Long, app.xreader.data.extension.RepoFormat.ForeignRepo>,
        val visibility: app.xreader.data.extension.ContentVisibility,
        val tick: Int
    )

    /** M3: flip the shared Novels | Manga switch (the Library
     * reads the same preference). */
    fun setLibrarySide(side: app.xreader.data.extension.MediaKind) {
        viewModelScope.launch { container.libraryPrefs.setLibrarySide(side) }
    }

    /** v1.5.9 (Finding 6): re-resolve the browsing defaults —
     * the Sources screen calls this when it returns to the
     * foreground, so a remembered pick made on a Details page
     * moves this list's default without waiting for the
     * installed set to change. */
    fun refreshInstanceDefaults() {
        _instanceDefaultsTick.value++
    }

    /** v1.5.9 (Finding 6): extension row id → the sourceId a
     * multi-source row's tap should open ([MultiSourceDefault]
     * over the same label join the Details page uses — handles
     * and declarative configs; the catalogue stubs aren't
     * needed here because the default turns on language,
     * which the handles and configs carry identically). A
     * package whose handles don't load resolves no default:
     * its row keeps the Details route rather than guessing. */
    private suspend fun computeDefaultSourceIds(
        extensions: List<InstalledExtension>
    ): Map<Long, String> {
        val defaults = HashMap<Long, String>()
        for (ext in extensions) {
            val declaredIds = ext.sourceIds.split(",")
                .map { it.trim() }.filter { it.isNotBlank() }
            if (declaredIds.size <= 1) continue
            val handles = container.extensionService
                .sourcesOf(ext.id).getOrNull().orEmpty()
            if (handles.isEmpty()) continue
            val configs = container.extensionStore.readText(
                container.extensionStore.definitionFile(ext.packageId)
            )?.let {
                app.xreader.data.extension.DeclarativeFingerprints
                    .decode(it)
            }.orEmpty()
            val instances = InstanceLabels.join(
                declaredIds = declaredIds,
                stubs = emptyMap(),
                handles = handles.map { handle ->
                    InstanceSeed(
                        sourceId = container.extensionService
                            .mihonAdapterOf(handle)?.mihonId?.toString()
                            ?: handle.source.id,
                        name = handle.source.name,
                        lang = handle.source.lang,
                        homeUrl = handle.source.baseUrl
                    )
                },
                configs = configs.map { config ->
                    InstanceSeed(
                        sourceId = config.id,
                        name = config.name,
                        lang = config.lang,
                        homeUrl = config.baseUrl
                    )
                }
            )
            val pick = container.extensionStore
                .instancePick(ext.packageId)
            MultiSourceDefault.resolve(instances, pick)?.let {
                defaults[ext.id] = it
            }
        }
        return defaults
    }

    /** M3: "extId:sourceId" → media kind for every installed
     * source (absence reads as Novels). The kind read is the
     * service's one accessor (K4): a Keiyoushi player source
     * is MANGA, a declarative source carries its config's
     * kind, the rest are Novels. A player handle is ALSO
     * keyed by the bare Mihon id its row declares — the
     * Explore list looks sources up by the row's own ids. */
    private suspend fun computeSourceKinds(
        extensions: List<InstalledExtension>
    ): Map<String, app.xreader.data.extension.MediaKind> {
        val kinds =
            HashMap<String, app.xreader.data.extension.MediaKind>()
        for (ext in extensions) {
            val handles = container.extensionService
                .sourcesOf(ext.id).getOrNull() ?: continue
            for (handle in handles) {
                val kind = container.extensionService
                    .mediaKindOf(handle)
                kinds[
                    app.xreader.data.extension.SourceHealthRecord
                        .key(ext.id, handle.source.id)
                ] = kind
                container.extensionService.mihonAdapterOf(handle)
                    ?.let { adapter ->
                        kinds[
                            app.xreader.data.extension
                                .SourceHealthRecord.key(
                                    ext.id, adapter.mihonId.toString()
                                )
                        ] = kind
                    }
            }
        }
        return kinds
    }

    private data class KeiyoushiInputs(
        val repos: List<app.xreader.data.db.ExtensionRepo>,
        val extensions: List<InstalledExtension>,
        val foreignRepos:
            Map<Long, app.xreader.data.extension.RepoFormat.ForeignRepo>,
        val visibility: app.xreader.data.extension.ContentVisibility,
        /** K3: the player's per-package states — an installed
         * and loaded package makes its rows Ready. */
        val playerStates:
            Map<String, app.xreader.data.extension.KeiyoushiIndex.PlayerPackageState> =
            emptyMap()
    )

    /** M3: the Keiyoushi rows for the repo screen — the
     * repo's loaded sites become index entries, the installed
     * MANGA configs decide which hosts are converted, and
     * KeiyoushiIndex.rows() applies the content preference
     * and stamps each row's true state. No sites loaded yet
     * (or no Keiyoushi row) = no rows; the screen keeps its
     * "check for updates" empty state. */
    private suspend fun computeKeiyoushiRows(
        inputs: KeiyoushiInputs
    ): List<app.xreader.data.extension.KeiyoushiIndex.PackageRow> {
        // v1.4.3: the repo is found by IDENTITY (name / repo
        // shape / canonical address), never the exact-URL
        // string alone — a hand-added variant row used to
        // leave the shelf empty here while opening the wrong
        // screen there (the two-shelves bug).
        val repo = inputs.repos.firstOrNull {
            app.xreader.data.extension.KeiyoushiIndex
                .isKeiyoushiRepo(it.name, it.indexUrl)
        } ?: return emptyList()
        val sites = inputs.foreignRepos[repo.id]?.sites
            ?: return emptyList()
        val configs = mutableListOf<
            app.xreader.data.extension.DeclarativeConfig>()
        for (ext in inputs.extensions) {
            val handles = container.extensionService
                .sourcesOf(ext.id).getOrNull() ?: continue
            for (handle in handles) {
                container.extensionService
                    .declarativeConfigOf(handle)?.let { configs += it }
            }
        }
        return app.xreader.data.extension.KeiyoushiIndex.shelfRows(
            entries = keiyoushiEntries(sites),
            convertedHosts = app.xreader.data.extension
                .KeiyoushiIndex.convertedHosts(configs),
            visibility = inputs.visibility,
            playerStates = inputs.playerStates
        )
    }

    /** M1: "extId:sourceId" → content rating for every
     * installed source. The rating read is the service's
     * one accessor (K4): a Keiyoushi player source carries
     * its extension's index rating, a declarative source its
     * config's, an unrated kind (JS/Dex) is Safe. A player
     * handle is ALSO keyed by the bare Mihon id its row
     * declares — the Explore list looks sources up by the
     * row's own ids. */
    private suspend fun computeSourceRatings(
        extensions: List<InstalledExtension>
    ): Map<String, app.xreader.data.extension.ContentRating> {
        val ratings =
            HashMap<String, app.xreader.data.extension.ContentRating>()
        for (ext in extensions) {
            val handles = container.extensionService
                .sourcesOf(ext.id).getOrNull() ?: continue
            for (handle in handles) {
                val rating = container.extensionService
                    .contentRatingOf(handle)
                ratings[
                    app.xreader.data.extension.SourceHealthRecord
                        .key(ext.id, handle.source.id)
                ] = rating
                container.extensionService.mihonAdapterOf(handle)
                    ?.let { adapter ->
                        ratings[
                            app.xreader.data.extension
                                .SourceHealthRecord.key(
                                    ext.id, adapter.mihonId.toString()
                                )
                        ] = rating
                    }
            }
        }
        return ratings
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
            // v1.3.4 (T6): the backup-address suffix — the
            // last recorded page shape names the host that
            // actually answered; when it is one of the
            // source's declared mirrors, the state word says
            // so instead of implying the primary answered.
            val lastShape = app.xreader.data.extension.PageShapeLog
                .lastFor(sessionKeyFor(ext.id))
            val viaBackup = lastShape != null &&
                config.backupHosts.isNotEmpty() &&
                app.xreader.data.extension.MirrorHosts.isMirrorOf(
                    lastShape.host,
                    app.xreader.data.extension.CookieParsing
                        .hostOf(config.baseUrl) ?: ""
                )
            var word = app.xreader.data.extension.SessionNeed
                .stateWord(config, hasSession, null, viaBackup)
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
                        // v1.3.3 (scope S7c): the Add screen
                        // navigates straight to the new repo's
                        // own page off this id.
                        _lastAddedRepoId.value = result.repo.id
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
                    is LinkProber.Outcome.NotProven ->
                        say("“${site.name}” was added, reading not proven — Xreader found rules for it but couldn't prove reading yet (${outcome.missingLegs.joinToString()}). It is marked as needing a check.")
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
        // v1.3.3 (addition 7): synthetic rows (built-ins,
        // link sources) are NEVER update-checked — they are
        // app furniture, and checking one fetched its
        // internal address as if it were a web URL. Any
        // caller, any path: a synthetic repo is a silent
        // no-op here, by construction.
        if (RepoCheckPolicy.isSynthetic(repo)) return
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
                                RepoCheckCopy.failureLine(
                                    repo.name, result.error
                                )
                            )
                        is AppResult.Ok -> {
                            _foreignRepos.value =
                                _foreignRepos.value +
                                    (repo.id to result.value)
                            say(
                                "“${repo.name}” checked — " +
                                    "${result.value.sites.size} sites."
                            )
                            // K3: a Keiyoushi refresh also
                            // re-checks installed packages
                            // against the fresh catalogue —
                            // newer versions flag their rows
                            // "Update available". v1.4.3: by
                            // repo IDENTITY (the exact-URL gate
                            // meant detection never ran for a
                            // hand-added row), and the outcome
                            // is SAID — a check that fails or
                            // finds updates is never silent.
                            if (app.xreader.data.extension
                                    .KeiyoushiIndex.isKeiyoushiRepo(
                                        repo.name, repo.indexUrl
                                    )
                            ) {
                                when (
                                    val check = container
                                        .keiyoushiPlayerManager
                                        .checkForUpdates()
                                ) {
                                    is AppResult.Err -> say(
                                        "Keiyoushi's own update " +
                                            "check didn't finish: " +
                                            ErrorText.displayText(
                                                check.error
                                            )
                                    )
                                    is AppResult.Ok ->
                                        if (check.value > 0) {
                                            say(
                                                "${check.value} " +
                                                    "Keiyoushi " +
                                                    "update(s) are " +
                                                    "waiting — they're " +
                                                    "at the top of " +
                                                    "the shelf."
                                            )
                                        }
                                }
                            }
                        }
                    }
                    return@launch
                }
                when (val result = manager.refresh(repo)) {
                    is AppResult.Err ->
                        say(
                            RepoCheckCopy.failureLine(
                                repo.name, result.error
                            )
                        )
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
            // v1.3.3 (addition 7): only REAL added repos are
            // checked — built-ins update with the app and the
            // synthetic rows never reach a fetch.
            val repos = RepoCheckPolicy.checkable(state.value.repos)
            if (repos.isEmpty()) {
                say(
                    "Built-in sources update with the app — " +
                        "there's nothing to check here."
                )
                return@launch
            }
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
     * path as the detail page's uninstall). Keiyoushi player
     * rows route to the player's own cascade (K3) — the
     * generic path would leave the player record, the APK
     * and the live sources behind. */
    fun uninstallExtension(ext: InstalledExtension) {
        if (ext.kind ==
            app.xreader.data.db.ExtensionKind.KEIYOUSHI
        ) {
            uninstallKeiyoushi(ext.packageId)
            return
        }
        viewModelScope.launch {
            manager.uninstall(ext)
            say("“${ext.displayName}” uninstalled")
        }
    }

    // ── Keiyoushi player (K3) ───────────────────────────────

    /** A row's Install/Update tap: download + verify, then
     * the consent sheet (state.keiyoushiConsent) when the
     * file checks out. A failure lands on the row itself as
     * its Failed/Incompatible state.
     *
     * v1.4.3 (the field diagnosis's §6/§7): the tap is NEVER
     * a silent no-op and never freezes the shelf —
     * - progress is PER ROW ([keiyoushiBusyPackages] + the
     *   row's own job, cancellable from the row), never the
     *   global busy flag;
     * - an installed, consented package with no newer
     *   version waiting doesn't re-download: its tap means
     *   "run it" and routes to the player's retryLoad (the
     *   way back for a failed load, incl. a quarantined
     *   record — the pre-1.4.3 tap looped silently on
     *   exactly this state);
     * - EVERY outcome is said: the consent sheet, a ready
     *   line, or the record's plain-words reason. */
    fun installKeiyoushi(packageName: String) {
        if (keiyoushiJobs[packageName]?.isActive == true) return
        val job = viewModelScope.launch {
            _keiyoushiBusy.value =
                _keiyoushiBusy.value + packageName
            try {
                val player = container.keiyoushiPlayerManager
                val record = player.record(packageName)
                val updateWaiting = record != null &&
                    record.availableVersionCode >
                    record.installedVersionCode
                // v1.5.39 (item 4): the INSTALLED copy's
                // signer, captured BEFORE staging — the
                // staged file's verification overwrites the
                // record's cert field with its own, so this
                // pre-staging value is the only honest
                // "same signer as your copy" witness for the
                // two-tap apply below.
                val installedCertBefore = record
                    ?.takeIf { it.isInstalled }?.certSha256
                val result = if (
                    record != null && record.isInstalled &&
                    !updateWaiting && record.consentedSha256 != null
                ) {
                    player.retryLoad(packageName)
                } else {
                    player.beginInstall(packageName)
                }
                when (result) {
                    is AppResult.Err ->
                        say(ErrorText.displayText(result.error))
                    is AppResult.Ok -> {
                        val prompt = player.consentPrompt(packageName)
                        val rec = result.value
                        if (prompt != null) {
                            // v1.5.39 (item 4): TWO-TAP APPLY —
                            // an already-installed package whose
                            // staged update carries the SAME
                            // signer as the installed copy
                            // confirms straight through the
                            // player's own consent entry point
                            // (the stage/verify pipeline is
                            // unchanged; confirmInstall
                            // re-verifies the staged bytes
                            // against the fingerprint). First
                            // installs, signer changes and any
                            // doubt keep the full consent sheet
                            // — and a failed fast confirm falls
                            // back to it too.
                            val sameSignerUpdate = updateWaiting &&
                                installedCertBefore != null &&
                                prompt.signerSha256 != null &&
                                prompt.signerSha256.equals(
                                    installedCertBefore,
                                    ignoreCase = true
                                )
                            if (sameSignerUpdate) {
                                when (
                                    val confirmed =
                                        player.confirmInstall(
                                            packageName,
                                            prompt.fingerprint
                                        )
                                ) {
                                    is AppResult.Ok -> {
                                        val done = confirmed.value
                                        if (done.status ==
                                            app.xreader.data.extension
                                                .player
                                                .KeiyoushiExtStatus
                                                .READY
                                        ) {
                                            say(
                                                "“${done.displayName}” updated"
                                            )
                                        } else {
                                            say(
                                                "“${done.displayName}”: " +
                                                    (done.failureReason
                                                        ?: "couldn't be started")
                                            )
                                        }
                                    }
                                    is AppResult.Err -> {
                                        say(
                                            ErrorText.displayText(
                                                confirmed.error
                                            )
                                        )
                                        player.consentPrompt(
                                            packageName
                                        )?.let {
                                            _keiyoushiConsent.value = it
                                        }
                                    }
                                }
                            } else {
                                _keiyoushiConsent.value = prompt
                            }
                        } else {
                            say(
                                "“${rec.displayName}”: " +
                                    when (rec.status) {
                                        app.xreader.data.extension
                                            .player.KeiyoushiExtStatus
                                            .READY ->
                                            rec.failureReason
                                                ?: "it's ready"
                                        else ->
                                            rec.failureReason
                                                ?: "couldn't be installed"
                                    }
                            )
                        }
                    }
                }
            } finally {
                _keiyoushiBusy.value =
                    _keiyoushiBusy.value - packageName
                keiyoushiJobs.remove(packageName)
            }
        }
        keiyoushiJobs[packageName] = job
    }

    /** v1.4.3: a busy row's Cancel — stops exactly that
     * row's job, then clears the staged/transient state in
     * the player. Nothing else on the shelf is touched. */
    fun cancelKeiyoushiInstall(packageName: String) {
        val job = keiyoushiJobs[packageName] ?: return
        job.cancel()
        viewModelScope.launch {
            job.join()
            container.keiyoushiPlayerManager
                .cancelInstall(packageName)
        }
    }

    /** v1.4.3: the shelf's own update check — runs when the
     * shelf opens (donor parity: detection is not something
     * the user must remember to ask for). A failure is said;
     * waiting updates announce themselves. */
    fun checkKeiyoushiUpdates() {
        viewModelScope.launch {
            when (
                val check = container.keiyoushiPlayerManager
                    .checkForUpdates()
            ) {
                is AppResult.Err -> say(
                    "Keiyoushi's update check didn't finish: " +
                        ErrorText.displayText(check.error)
                )
                is AppResult.Ok -> if (check.value > 0) {
                    say(
                        "${check.value} Keiyoushi update(s) are " +
                            "waiting — they're at the top of the shelf."
                    )
                }
            }
        }
    }

    /** v1.4.3: "Update all" in the shelf's Updates-pending
     * section (donor parity). Each package still stages and
     * takes its own consent — the consent sheet is per
     * package by design; packages the sheet isn't showing
     * wait as "Needs your OK" rows with a Review button. */
    fun updateAllKeiyoushi() {
        val pending = container.keiyoushiPlayerManager
            .records.value.values
            .filter {
                it.availableVersionCode > it.installedVersionCode
            }
            .map { it.packageName }
        if (pending.isEmpty()) {
            say("No updates are waiting.")
            return
        }
        pending.forEach { installKeiyoushi(it) }
    }

    /** v1.4.3: the 18+ quick toggle on the source screens
     * writes the SAME ContentVisibility preference the
     * Customise screen's chips drive — one source of truth,
     * both surfaces stay in sync. */
    fun setContentVisibility(
        visibility: app.xreader.data.extension.ContentVisibility
    ) {
        viewModelScope.launch {
            container.sourcePrefs.setContentVisibility(visibility)
        }
    }

    /** v1.5.1 (audit F5): one 18+ quick-toggle step — restores
     * the exact remembered three-way mode on the step back. */
    fun toggleAdultVisibility() {
        viewModelScope.launch {
            container.sourcePrefs.toggleContentVisibility()
        }
    }

    /** A row's Review tap (an install staged in an earlier
     * visit to this screen): re-show the consent sheet for
     * the already-verified file — no new download. */
    fun reviewKeiyoushiConsent(packageName: String) {
        _keiyoushiConsent.value =
            container.keiyoushiPlayerManager.consentPrompt(packageName)
    }

    /** The consent sheet's Install tap: the fingerprint the
     * sheet displayed is the consent — the manager loads
     * only if the staged bytes still hash to exactly it. */
    fun confirmKeiyoushiConsent() {
        val prompt = _keiyoushiConsent.value ?: return
        _keiyoushiConsent.value = null
        // v1.4.3: the confirm's load is per-row work too —
        // the global busy flag stays out of it.
        if (keiyoushiJobs[prompt.packageName]?.isActive == true) return
        val job = viewModelScope.launch {
            _keiyoushiBusy.value =
                _keiyoushiBusy.value + prompt.packageName
            try {
                when (
                    val result = container.keiyoushiPlayerManager
                        .confirmInstall(
                            prompt.packageName, prompt.fingerprint
                        )
                ) {
                    is AppResult.Err ->
                        say(ErrorText.displayText(result.error))
                    is AppResult.Ok -> {
                        val record = result.value
                        if (
                            record.status ==
                            app.xreader.data.extension.player
                                .KeiyoushiExtStatus.READY
                        ) {
                            say("“${record.displayName}” installed")
                        } else {
                            say(
                                "“${record.displayName}”: " +
                                    (record.failureReason
                                        ?: "couldn't be started")
                            )
                        }
                    }
                }
            } finally {
                _keiyoushiBusy.value =
                    _keiyoushiBusy.value - prompt.packageName
                keiyoushiJobs.remove(prompt.packageName)
            }
        }
        keiyoushiJobs[prompt.packageName] = job
    }

    /** The consent sheet dismissed: the staged file is
     * dropped, nothing was installed or loaded. */
    fun dismissKeiyoushiConsent() {
        val prompt = _keiyoushiConsent.value ?: return
        _keiyoushiConsent.value = null
        viewModelScope.launch {
            container.keiyoushiPlayerManager
                .cancelInstall(prompt.packageName)
        }
    }

    /** A Ready row's Remove: the player's full uninstall
     * cascade (sources, file, prefs, cookies, row, record). */
    fun uninstallKeiyoushi(packageName: String) {
        viewModelScope.launch {
            val name = container.keiyoushiPlayerManager
                .record(packageName)?.displayName ?: packageName
            container.keiyoushiPlayerManager.uninstall(packageName)
            say("“$name” uninstalled")
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
        mode: LoginMode = LoginMode.SIGN_IN,
        targetUrl: String? = null
    ) {
        // v1.5.2 (challenge re-prompt cooldown): this tap IS the
        // explicit user-initiated check — it clears the host's
        // bridge cooldown, so a user who dismissed a check ten
        // seconds ago and now taps sign-in/check themselves is
        // never throttled by their own dismissal.
        app.xreader.data.extension.CookieParsing.hostOf(url)?.let { host ->
            runCatching { container.playerChallengeBridge.clearCooldown(host) }
        }
        // v1.5.2 fix (FIX-2, §1): EVERY sanctioned solve/sign-in
        // registers its durable pending operation here — the one
        // door catalogue, book page and reader all walk through —
        // so their blocked actions are covered by the §1 guarantee,
        // not only the link-add flow's.
        viewModelScope.launch {
            container.registerPendingOperation(
                sessionKey = sessionKey,
                flowKind = "SOURCE_LOGIN",
                host = app.xreader.data.extension.CookieParsing.hostOf(url) ?: title,
                category = if (mode == LoginMode.SITE_CHECK) "CHALLENGE" else "SIGN_IN"
            )
        }
        _loginRequest.value =
            LoginRequest(sessionKey, url, title, mode, targetUrl)
    }

    /** v1.5.2 fix (FIX-2, §1 residue a): the configured hosts for a
     * session key's source (base + backups + declared extras), for
     * the widened login capture. */
    suspend fun configuredHosts(sessionKey: String): List<String> {
        val rowId = sessionKey.removePrefix("ext-").toLongOrNull()
        if (rowId != null) {
            val handles = container.extensionService.sourcesOf(rowId)
                .getOrNull().orEmpty()
            val hosts = LinkedHashSet<String>()
            for (handle in handles) {
                val config = container.extensionService
                    .declarativeConfigOf(handle) ?: continue
                app.xreader.data.extension.CookieParsing.hostOf(config.baseUrl)
                    ?.let { hosts += it }
                config.backupHosts.forEach { raw ->
                    (app.xreader.data.extension.CookieParsing.hostOf(raw)
                        ?: raw)?.let { hosts += it }
                }
                hosts += config.extraDomains
            }
            return hosts.toList()
        }
        val linkHost = sessionKey.removePrefix("linkadd_")
        return if (linkHost != sessionKey && linkHost.isNotBlank()) {
            listOf(linkHost)
        } else emptyList()
    }

    fun clearLoginRequest() {
        _loginRequest.value = null
    }

    /** Persist a captured login (WebView cookies + UA) into the vault. */
    fun saveLoginSession(
        sessionKey: String,
        cookiesByHost: Map<String, Map<String, String>>,
        fallbackHost: String,
        userAgent: String?
    ) {
        // v1.5.8 (C4 / F7 F4): record the SOLVE QUALITY beside
        // the capture — "Sign-in saved" certifies that a
        // capture RAN, not that a clearance was earned. One
        // DiagnosticLog line states whether the jar, at
        // Done-tap, held a clearance cookie for the source's
        // host, so a saved session that never earned
        // clearance is visible as such. No behaviour change.
        recordSolveQuality(sessionKey, cookiesByHost, fallbackHost)
        viewModelScope.launch {
            // v1.3.5 (P0): the captured cookies are stored PER
            // ORIGIN — the jar read for one host lands under that
            // host's key only, never merged into another origin's
            // record (SessionData's map is host → cookies).
            if (cookiesByHost.isEmpty()) {
                // Nothing captured (a check screen, usually):
                // the user agent is still recorded against the
                // page's host, as before.
                container.sessionStore.mergeCookies(
                    sessionKey, fallbackHost, emptyMap(), userAgent
                )
            } else {
                for ((host, cookies) in cookiesByHost) {
                    container.sessionStore.mergeCookies(
                        sessionKey, host, cookies, userAgent
                    )
                }
            }
            _sessionSaved.tryEmit(sessionKey)
            // v1.3.3 (scope S4): the waiting surfaces (the
            // catalogue, book page or reader that sent the
            // user here) retry their blocked action off the
            // container's bus.
            container.notifySessionSaved(sessionKey)
            say("Sign-in saved for this source")
        }
    }

    suspend fun hasSession(sessionKey: String): Boolean =
        container.sessionStore.has(sessionKey)

    /** v1.5.2 fix (FIX-10, §8.5): the one-time update-pass summary
     * for Explore — real counts from the completed deferred phase. */
    val updatePassSummary: StateFlow<String?> = container.updatePassSummary

    fun dismissUpdatePassSummary() {
        container.updatePassSummary.value = null
    }

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

/**
 * Manga programme (M3): the Keiyoushi repo's loaded site list
 * (the foreign-repo sidecar's shape) as Keiyoushi index
 * entries — the input [app.xreader.data.extension.KeiyoushiIndex.rows]
 * consumes. The sidecar's per-site rating (stamped from the
 * index's contentWarning when the list was built) and its
 * carrying package name (K3) ride across unchanged; mirror
 * fields the sidecar doesn't keep stay at their defaults.
 */
fun keiyoushiEntries(
    sites: List<app.xreader.data.extension.ForeignSite>
): List<app.xreader.data.extension.KeiyoushiIndex.Entry> =
    sites.map { site ->
        app.xreader.data.extension.KeiyoushiIndex.Entry(
            name = site.name,
            lang = site.lang,
            baseUrl = site.baseUrl,
            rating = site.contentRating,
            // K3: the carrying package — the player's install
            // unit and row-state join key.
            packageName = site.packageName,
            // v1.4.4: the package's library version — the
            // shelf's "Needs a newer Xreader" check.
            extensionLib = site.extensionLib
        )
    }

/** The five derived maps the joined pass publishes (B2). */
internal data class SourcesDerivedMaps(
    val kinds: Map<String, app.xreader.data.extension.MediaKind>,
    val ratings: Map<String, app.xreader.data.extension.ContentRating>,
    val sessionWords: Map<String, String>,
    val defaultSourceIds: Map<Long, String>,
    val keiyoushiRows: List<app.xreader.data.extension.KeiyoushiIndex.PackageRow>
)

/**
 * v1.5.10 (B2): the joined pass's gate — the single compute
 * site for the Sources screen's five derived maps.
 *
 * [run] executes [compute] on [dispatcher] (production:
 * Dispatchers.Default) ONLY when [fingerprint] differs from
 * the last successful pass's; an unchanged fingerprint is a
 * skip (returns null). A failed compute keeps the last good
 * fingerprint UNSET, so the next emission retries, and the
 * failure is logged — the caller keeps its last published
 * maps either way (A2's rule). [computeCount] is the gate's
 * counting seam: the B2/C2 tests assert exactly one compute
 * per distinct fingerprint and zero expensive work on the
 * collecting thread.
 */
internal class JoinedSourcesPass {
    @Volatile
    var lastFingerprint: String? = null
        private set

    var computeCount: Int = 0
        private set

    /** Thread the last compute body ran on (C2 proxy seam). */
    @Volatile
    var lastComputeThread: String? = null
        private set

    suspend fun <T> run(
        fingerprint: String,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        compute: suspend () -> T
    ): T? {
        if (fingerprint == lastFingerprint) return null
        val result = try {
            kotlinx.coroutines.withContext(dispatcher) {
                lastComputeThread = Thread.currentThread().name
                compute()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            app.xreader.data.extension.DiagnosticLog.record(
                "sources-pass",
                "joined derived pass failed — last good state kept",
                e.javaClass.simpleName
            )
            return null
        }
        lastFingerprint = fingerprint
        computeCount++
        return result
    }
}

/**
 * v1.5.10 (B2): the joined pass's fingerprint — the emitted
 * rows' ids + the fields the five computations actually
 * read, plus the health map, the player states, the repo /
 * foreign inputs, the content preference, the picks-map
 * hash and the instance-defaults tick. Pure and cheap: it
 * reads only values already in memory on the collecting
 * thread (the picks hash is supplied by the caller from its
 * in-pass read — no file IO happens here, C2 proxy).
 */
internal fun sourcesPassFingerprint(
    extensions: List<InstalledExtension>,
    health: Map<String, app.xreader.data.extension.SourceHealthRecord>,
    playerStates: Map<String, app.xreader.data.extension.KeiyoushiIndex.PlayerPackageState>,
    repos: List<ExtensionRepo>,
    foreignRepos: Map<Long, app.xreader.data.extension.RepoFormat.ForeignRepo>,
    visibility: app.xreader.data.extension.ContentVisibility,
    tick: Int,
    picksHash: Int
): String = buildString {
    append("tick=").append(tick)
    append("|picks=").append(picksHash)
    append("|vis=").append(visibility.name)
    for (ext in extensions) {
        append("|e:").append(ext.id).append(',')
            .append(ext.packageId).append(',')
            .append(ext.sourceIds).append(',')
            .append(ext.kind.name).append(',')
            .append(ext.enabled).append(',')
            .append(ext.trustState.name).append(',')
            .append(ext.permissionsJson.hashCode()).append(',')
            .append(ext.needsRepair)
    }
    for ((key, value) in health.toSortedMap()) {
        append("|h:").append(key).append('=').append(value.state.name)
    }
    for ((key, value) in playerStates.toSortedMap()) {
        append("|p:").append(key).append('=').append(value.kind.name).append(',').append(value.reason)
    }
    for (repo in repos) {
        append("|r:").append(repo.id).append(',')
            .append(repo.name).append(',').append(repo.indexUrl)
    }
    for ((key, value) in foreignRepos.toSortedMap()) {
        append("|f:").append(key).append('=').append(value.sites.size)
    }
}
