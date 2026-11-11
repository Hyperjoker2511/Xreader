package app.xreader.data.extension.player

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ContentRating
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.KeiyoushiIndex
import app.xreader.data.extension.normaliseHost
import app.xreader.data.net.HttpTransport
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the consent sheet shows for one staged install: the
 * slice-A honest DEX wording for the package's sites, the
 * fingerprint the user is agreeing to, and the signer. */
data class KeiyoushiConsentPrompt(
    val packageName: String,
    val displayName: String,
    val summary: String,
    val fingerprint: String,
    val signerSha256: String?,
    val versionName: String
)

/**
 * K3 (Keiyoushi player): the extension manager — download,
 * verify, consent, load, update, uninstall, restore — over
 * the pure state machine in [KeiyoushiPlayerModels]. The
 * donor shape is Komikku's ExtensionManager + ExtensionLoader
 * private path, restated over Xreader's seams:
 *
 * - APKs come from the Keiyoushi index entry's own URL and
 *   are verified BEFORE any code loads: signing certificate
 *   == the index-published Keiyoushi key ([KeiyoushiTrust]),
 *   library gate 1.6, fingerprint pinned, and the user's
 *   consent taken for exactly those bytes
 *   ([KeiyoushiLoadGate] re-checks before EVERY load —
 *   install, update and every cold start alike).
 * - Live sources live in [liveSources], keyed by the
 *   namespaced id "keiyoushi:<packageName>:<mihonId>" —
 *   **this is K4's seam: `sourceById(namespacedId)` returns
 *   the live Mihon [Source], or null when the package isn't
 *   installed/loaded (its record's state says why).**
 * - Every extension entry-point call the manager makes
 *   (instantiation, factory createSources) is bracketed
 *   with [PlayerContextState.withNamespace] inside the
 *   loader, per K2's rule.
 */
class KeiyoushiPlayerManager(
    private val store: ExtensionStore,
    private val recordStore: KeiyoushiRecordStore,
    private val transport: HttpTransport,
    private val apkReader: KeiyoushiApkReader,
    private val loader: KeiyoushiExtensionLoader,
    private val extensionDao: InstalledExtensionDao,
    private val repoIdProvider: suspend () -> Long,
    private val uninstallCleaner: KeiyoushiUninstallCleaner,
    /** K6: the kill switch's stored value ("Keiyoushi manga
     * sources" in Customise → Sources & repairs). Read once
     * at restore to seed [playerEnabledFlag]; the shell's
     * collector pushes later changes through
     * [setPlayerEnabled]. Defaults on. */
    private val playerEnabledProvider: suspend () -> Boolean = { true },
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    /** Guards the record STATE (the map, publishes, the
     * record store). Held only for state transitions and
     * quick file moves — NEVER across a network download or
     * an extension load (v1.4.3, §7 of the field diagnosis:
     * one stuck load used to wedge every install for the
     * process lifetime behind this single lock). */
    private val mutex = Mutex()

    /** v1.4.3: serialises the LOADER only — arbitrary
     * extension code runs under this lock and no other, so a
     * slow or stuck load can delay another load but can
     * never block installs, updates or state reads. Lock
     * order where both are needed: loadMutex, then mutex. */
    private val loadMutex = Mutex()

    private val _records =
        MutableStateFlow<Map<String, KeiyoushiPlayerRecord>>(emptyMap())

    /** Every known package's record, by package name. */
    val records: StateFlow<Map<String, KeiyoushiPlayerRecord>> =
        _records.asStateFlow()

    private val _packageStates = MutableStateFlow<
        Map<String, KeiyoushiIndex.PlayerPackageState>>(emptyMap())

    /** The rows() input: per-package player state in the
     * index reader's vocabulary. */
    val packageStates: StateFlow<
        Map<String, KeiyoushiIndex.PlayerPackageState>> =
        _packageStates.asStateFlow()

    /** Live Mihon sources by namespaced id — K4's registry. */
    private val liveSources = ConcurrentHashMap<String, Source>()

    @Volatile
    private var catalogCache: KeiyoushiIndex.Catalog? = null

    @Volatile
    private var restored = false

    /** v1.4.3: restore's load pass has finished (or there
     * was nothing to load). Until then a READY record's
     * empty registry may just mean "not reached yet" — the
     * READY-must-mean-LOADED surfacing in
     * [toPlayerPackageState] only applies once this is set. */
    @Volatile
    private var restoreSettled = false

    /** K6: the kill switch, in memory. Off means no extension
     * loads and the registry reads empty — player sources
     * vanish from Explore/search and library books meet the
     * service's "turned off" answer. Seeded from
     * [playerEnabledProvider] at restore; the DataStore
     * remains the durable home. */
    @Volatile
    private var playerEnabledFlag = true

    /** The kill switch's current state — ExtensionService
     * asks this (synchronously, at lookup time) to word its
     * refusal for player rows. */
    fun isPlayerEnabled(): Boolean = playerEnabledFlag

    /**
     * The kill switch flipped. Off: the live registry empties
     * at once (in-flight reads finish; the next lookup finds
     * nothing). On (after restore): the installed, consented
     * packages load again through the same gated path restore
     * uses — quarantined (FAILED) packages stay out until the
     * user retries them, exactly as at startup.
     */
    suspend fun setPlayerEnabled(enabled: Boolean) {
        val shouldLoad = mutex.withLock {
            val was = playerEnabledFlag
            playerEnabledFlag = enabled
            if (!enabled) {
                liveSources.clear()
            }
            !was && enabled && restored
        }
        // v1.4.3: the loads run OUTSIDE the state lock (they
        // take their own locks per package).
        if (shouldLoad) loadInstalled()
    }

    // ── lookups (K4's seam) ─────────────────────────────────

    fun record(packageName: String): KeiyoushiPlayerRecord? =
        _records.value[packageName]

    /** The live Mihon [Source] for a namespaced id
     * ("keiyoushi:<packageName>:<mihonId>"), or null when
     * its package isn't loaded. K4 brackets every call it
     * makes on the returned source with
     * [PlayerContextState.withNamespace] for the package. */
    fun sourceById(namespacedId: String): Source? =
        if (playerEnabledFlag) liveSources[namespacedId] else null

    /** All live sources of one package, by namespaced id. */
    fun liveSourcesOf(packageName: String): Map<String, Source> =
        if (!playerEnabledFlag) {
            emptyMap()
        } else {
            liveSources.filterKeys {
                it.startsWith("keiyoushi:$packageName:")
            }
        }

    fun allLiveSources(): Map<String, Source> =
        if (playerEnabledFlag) liveSources.toMap() else emptyMap()

    /** v1.5.7 (Finding 4, §6): the normalised hosts the
     * CACHED catalogue claims — every package's sources'
     * homeUrl hosts (KeiyoushiIndex.Entry.host's twin for
     * the installable form). This sits inside source
     * lookups, so it NEVER fetches. v1.5.8 (A6): when the
     * cache is COLD it falls back to the persisted records'
     * source hosts instead of answering the empty set —
     * the v1.5.7 shape made the stamp a first-materialisation
     * race (F1 M1). ExtensionService stamps a link-added
     * declarative config whose host is claimed here as
     * MANGA at load time, so a Keiyoushi site added by link
     * routes to the manga reader instead of the prose road. */
    fun claimedHosts(): Set<String> {
        val catalog = catalogCache
        if (catalog != null) {
            return catalog.packages
                .flatMap { pkg -> pkg.sources.map { it.homeUrl } }
                .map { it.normaliseHost() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
        // v1.5.8 (A6 / F1 M1): the COLD-cache fallback — the
        // stamp must be deterministic, not a race against
        // whoever fetched the catalogue first. The manager's
        // PERSISTED records already carry each installed
        // package's source hosts ([KeiyoushiPlayerRecord
        // .sourceHosts], the data noteCoverHosts maintains
        // and restore publishes into [_records]); a cold
        // cache answers from them, so a declarative row
        // materialised before any catalog() call is stamped
        // exactly as one materialised after. Cache-warm
        // behaviour above is unchanged.
        return _records.value.values
            .filter { it.isInstalled }
            .flatMap { it.sourceHosts }
            .map { it.normaliseHost() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** v1.5.4 (WS-1 item 7b, closed at integration): cover
     * hosts a package's OWN sources actually return, reported
     * by the Mihon adapter from the thumbnails it maps
     * (catalogue rows + book details). A player row's
     * permission domains are derived in [upsertRowLocked]
     * from the record's hosts — at load time those can only
     * cover the sources' own hosts, so a cover served from
     * an unrelated image-CDN host was refused by construction
     * (SourceBookSync's CoverFetch.RefusedPermission) and the
     * book never got a local cover file. Hosts the trusted
     * package's own sources hand out are that package's own
     * traffic: they join the record's sourceHosts and the row
     * is re-derived, so the §9.1 whitelist grows only by the
     * package's observed behaviour — never by a fetch attempt
     * (fetchCoverTyped's check is untouched). Called from the
     * adapter's background threads; the fast path is a plain
     * read, the merge runs under the state mutex and is a
     * no-op when nothing is new. */
    fun noteCoverHosts(packageName: String, hosts: Collection<String>) {
        val cleaned = hosts.map { it.normaliseHost() }
            .filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return
        val snapshot = _records.value[packageName] ?: return
        if (!snapshot.isInstalled) return
        if (snapshot.sourceHosts.map { it.normaliseHost() }
                .containsAll(cleaned)
        ) {
            return
        }
        runBlocking {
            mutex.withLock {
                val current = _records.value[packageName]
                    ?: return@withLock
                if (!current.isInstalled) return@withLock
                val known = current.sourceHosts
                    .map { it.normaliseHost() }.toSet()
                val fresh = cleaned.filter { it !in known }
                if (fresh.isEmpty()) return@withLock
                val updated = current.copy(
                    sourceHosts = current.sourceHosts + fresh
                )
                publishRecordLocked(updated)
                // Re-derive the row NOW so this session's cover
                // fetches see the widened domains. The row's
                // source ids are re-derived from the record's
                // namespaced ids; if they can't be (a record
                // written before they were stamped), the upsert
                // is skipped rather than blanking the row's ids
                // — the merge above still lands, and the next
                // load's host assembly starts from sourceHosts.
                val mihonIds = current.sourceIds.mapNotNull {
                    it.substringAfterLast(':').toLongOrNull()
                }
                if (mihonIds.isNotEmpty()) {
                    upsertRowLocked(updated, mihonIds)
                }
            }
        }
    }

    // ── catalogue ───────────────────────────────────────────

    /** Fetch (or reuse) the installable catalogue. An
     * update check always re-reads the index — a cached
     * catalogue can never flag a new version. */
    suspend fun catalog(
        forceRefresh: Boolean = false
    ): AppResult<KeiyoushiIndex.Catalog> {
        if (!forceRefresh) {
            catalogCache?.let { return AppResult.Ok(it) }
        }
        val response = when (
            val result = transport.execute(
                HttpTransport.Request(KeiyoushiIndex.INDEX_URL)
            )
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        if (response.code !in 200..299) {
            return AppResult.Err(
                AppError.ExtensionFailed(
                    KeiyoushiIndex.REPO_NAME,
                    "the catalogue couldn't be fetched (HTTP ${response.code})"
                )
            )
        }
        val body = response.body?.use { it.readBytes() }
            ?.toString(Charsets.UTF_8)
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    KeiyoushiIndex.REPO_NAME,
                    "the catalogue reply was empty"
                )
            )
        val parsed = KeiyoushiIndex.parseCatalog(body)
        if (parsed is AppResult.Ok) {
            catalogCache = parsed.value
            // v1.5.1 (item 6): stamp every known record with
            // its index rating while the catalogue is in hand,
            // so the rating survives restarts on the record
            // itself (see KeiyoushiPlayerRecord.contentRatingName).
            stampRatingsLocked(parsed.value)
        }
        return parsed
    }

    /** v1.5.1 (item 6): the content rating for one package,
     * answered WITHOUT any network fetch — the record's
     * durable stamp first, the in-memory catalogue second,
     * null when neither knows. (The pre-1.5.1 provider called
     * [catalog] from inside source lookups: a cold cache
     * turned every lookup into a full-index fetch, and a
     * miss failed the source closed to ADULT, hiding
     * installed Ready sources from Browse.) */
    fun ratingOf(packageName: String): ContentRating? {
        _records.value[packageName]?.contentRatingName?.let { name ->
            return runCatching { ContentRating.valueOf(name) }
                .getOrNull()
        }
        return catalogCache?.packages
            ?.firstOrNull { it.packageName == packageName }?.rating
    }

    /** Persist the catalogue's ratings onto the records.
     * Caller holds no lock; takes [mutex]'s sibling path via
     * the same publish plumbing the state machine uses —
     * records only ever move through [publishRecordLocked],
     * which the install flows also call under [mutex]; a
     * racing stamp writes the same value the install flow
     * would, so no extra lock is taken here. */
    private fun stampRatingsLocked(catalog: KeiyoushiIndex.Catalog) {
        val byPackage = catalog.packages.associateBy { it.packageName }
        for (record in _records.value.values) {
            val rating = byPackage[record.packageName]?.rating
                ?: continue
            if (record.contentRatingName != rating.name) {
                publishRecordLocked(
                    record.copy(contentRatingName = rating.name)
                )
            }
        }
    }

    /** v1.5.2 (§8.4 backfill): after restore, every installed
     * record with a null stamp is stamped from the index — from
     * the warm catalogue cache when present, else ONE catalog()
     * fetch. Restore never waits on user action, and the healing
     * fetch is no longer reachable only for sources Browse can
     * already see (Failure E's chicken-and-egg). */
    suspend fun backfillRatings() {
        val unstamped = _records.value.values.any {
            it.isInstalled && it.contentRatingName == null
        }
        if (!unstamped) return
        if (catalogCache != null) {
            catalogCache?.let { stampRatingsLocked(it) }
            return
        }
        runCatching { catalog() }
    }

    /** Compare the catalogue against installed records and
     * flag available updates (a Ready source keeps working;
     * its row offers Update). */
    suspend fun checkForUpdates(): AppResult<Int> {
        val catalog = when (val result = catalog(forceRefresh = true)) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        var flagged = 0
        mutex.withLock {
            val byPackage = catalog.packages.associateBy { it.packageName }
            var changed = false
            val next = _records.value.toMutableMap()
            for ((packageName, record) in next) {
                val pkg = byPackage[packageName] ?: continue
                val available = when {
                    record.isInstalled &&
                        pkg.versionCode > record.installedVersionCode ->
                        pkg.versionCode
                    else -> 0L
                }
                if (available != record.availableVersionCode ||
                    pkg.versionCode != record.indexVersionCode
                ) {
                    next[packageName] = record.copy(
                        indexVersionCode = pkg.versionCode,
                        indexVersionName = pkg.versionName,
                        availableVersionCode = available,
                        updatedAt = nowMs()
                    )
                    changed = true
                    if (available > 0) flagged++
                }
            }
            if (changed) publishLocked(next)
        }
        return AppResult.Ok(flagged)
    }

    // ── install / update ────────────────────────────────────

    /**
     * Download + verify a package's APK and stage it for
     * consent. NOTHING loads in this step — the record lands
     * in AWAITING_CONSENT with the fingerprint pinned, and
     * only [confirmInstall] may move it to a load.
     */
    suspend fun beginInstall(
        packageName: String
    ): AppResult<KeiyoushiPlayerRecord> {
        val catalog = when (val result = catalog()) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        val pkg = catalog.packages.firstOrNull {
            it.packageName == packageName
        } ?: return AppResult.Err(
            AppError.ExtensionFailed(
                packageName,
                "that source isn't in the Keiyoushi catalogue"
            )
        )

        // The trust anchor first: an index whose key isn't
        // the Keiyoushi key blesses nothing.
        when (val keyVerdict = KeiyoushiTrust.verifyIndexKey(catalog.signingKey)) {
            is KeiyoushiTrust.Verdict.Refuse -> {
                mutex.withLock {
                    val record = _records.value[pkg.packageName]
                        ?: KeiyoushiPlayerRecord(
                            packageName = pkg.packageName,
                            displayName = pkg.name
                        )
                    applyLocked(
                        record,
                        KeiyoushiEvent.DownloadFailed(keyVerdict.reason)
                    )
                }
                return AppResult.Err(
                    AppError.ExtensionFailed(pkg.name, keyVerdict.reason)
                )
            }
            else -> Unit
        }

        // Phase 1 (state lock): open the attempt. A second
        // tap while a download/verify is already on the wire
        // is the same attempt, not a new one.
        val started = mutex.withLock {
            val existing = _records.value[pkg.packageName]
            if (existing != null &&
                (existing.status == KeiyoushiExtStatus.DOWNLOADING ||
                    existing.status == KeiyoushiExtStatus.VERIFYING)
            ) {
                return@withLock existing
            }
            var record = existing
                ?: KeiyoushiPlayerRecord(
                    packageName = pkg.packageName,
                    displayName = pkg.name
                )
            record = applyLocked(record, KeiyoushiEvent.DownloadStarted)
            record = record.copy(
                displayName = pkg.name,
                indexVersionCode = pkg.versionCode,
                indexVersionName = pkg.versionName,
                // v1.5.1 (item 6): the rating stamp lands with
                // the install, before any load — Browse never
                // has to guess this package's rating.
                contentRatingName = pkg.rating.name
            )
            publishRecordLocked(record)
            record
        }
        if (started.status != KeiyoushiExtStatus.DOWNLOADING) {
            // The in-flight attempt another caller opened.
            return AppResult.Ok(started)
        }

        // Phase 2 (NO lock): the download itself. v1.4.3: this
        // used to run under the manager's single mutex, so one
        // slow download — or a stuck load queued ahead of it —
        // froze every other row's install for the process
        // lifetime (the field diagnosis's §7 hang).
        val staging = store.keiyoushiStagingFile(pkg.packageName)
        val sha = try {
            val response = transport.execute(
                HttpTransport.Request(pkg.apkUrl)
            )
            when (response) {
                is AppResult.Err -> {
                    downloadFailedLocked(
                        pkg, "the download didn't finish",
                        response.error.toString()
                    )
                    return AppResult.Err(response.error)
                }
                is AppResult.Ok -> {
                    val resp = response.value
                    if (resp.code !in 200..299 || resp.body == null) {
                        val reason =
                            "the download didn't finish (HTTP ${resp.code})"
                        downloadFailedLocked(pkg, reason, null)
                        return AppResult.Err(
                            AppError.ExtensionFailed(pkg.name, reason)
                        )
                    }
                    resp.body.use { input ->
                        store.writeArtifactStream(staging, input).second
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                // Leave the record honest even when the caller
                // went away: a cancelled attempt is a failed
                // stage, never a phantom "Downloading…" forever.
                kotlinx.coroutines.withContext(
                    kotlinx.coroutines.NonCancellable
                ) {
                    downloadFailedLocked(
                        pkg, "the download was stopped", null
                    )
                }
                throw e
            }
            val reason = "the download didn't finish"
            downloadFailedLocked(pkg, reason, e.toString())
            return AppResult.Err(
                AppError.ExtensionFailed(pkg.name, reason)
            )
        }

        // Phase 3 (state lock): pin the staged fingerprint and
        // verify the staged bytes (local file reads only).
        return mutex.withLock {
            verifyStagedLocked(pkg, sha, catalog.signingKey)
        }
    }

    /** Apply a download failure to the CURRENT record (the
     * record may have moved while the download ran unlocked).
     * Caller does NOT hold [mutex]. */
    private suspend fun downloadFailedLocked(
        pkg: KeiyoushiIndex.Package,
        reason: String,
        detail: String?
    ) {
        mutex.withLock {
            val current = _records.value[pkg.packageName]
                ?: return@withLock
            if (current.status == KeiyoushiExtStatus.DOWNLOADING) {
                applyLocked(
                    current,
                    KeiyoushiEvent.DownloadFailed(reason, detail)
                )
            }
        }
    }

    /** Phase 3 of [beginInstall]: the staged file's
     * fingerprint + the full verification chain (package,
     * library gate, certificate vs the index key, entry
     * class). Caller holds [mutex]. */
    private fun verifyStagedLocked(
        pkg: KeiyoushiIndex.Package,
        sha: String,
        signingKey: String?
    ): AppResult<KeiyoushiPlayerRecord> {
        var record = _records.value[pkg.packageName]
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    pkg.name, "the install was interrupted"
                )
            )
        record = record.copy(
            stagedSha256 = sha,
            stagedVersionCode = pkg.versionCode,
            stagedVersionName = pkg.versionName
        )
        publishRecordLocked(record)
        record = applyLocked(record, KeiyoushiEvent.Downloaded)

        val staging = store.keiyoushiStagingFile(pkg.packageName)
        // ── verify the staged bytes ────────────────────────
        val metadata = apkReader.read(staging)
        if (metadata == null) {
            staging.delete()
            val updated = applyLocked(
                record,
                KeiyoushiEvent.VerifyFailed(
                    "the downloaded file couldn't be read as an extension"
                )
            )
            return AppResult.Ok(updated)
        }
        if (metadata.packageName != pkg.packageName) {
            staging.delete()
            val updated = applyLocked(
                record,
                KeiyoushiEvent.VerifyFailed(
                    "the file is for a different extension than the catalogue says"
                )
            )
            return AppResult.Ok(updated)
        }
        val libVersion = KeiyoushiTrust.libVersionOf(
            metadata.extensionLib, metadata.versionName
        )
        when (
            val libVerdict = KeiyoushiTrust.verifyLibVersion(libVersion)
        ) {
            is KeiyoushiTrust.Verdict.Incompatible -> {
                staging.delete()
                val updated = applyLocked(
                    record,
                    KeiyoushiEvent.MarkedIncompatible(
                        libVerdict.reason,
                        "extensionLib=$libVersion"
                    )
                )
                return AppResult.Ok(updated)
            }
            is KeiyoushiTrust.Verdict.Refuse -> {
                staging.delete()
                val updated = applyLocked(
                    record,
                    KeiyoushiEvent.VerifyFailed(libVerdict.reason)
                )
                return AppResult.Ok(updated)
            }
            KeiyoushiTrust.Verdict.Accept -> Unit
        }
        when (
            val certVerdict = KeiyoushiTrust.verifyCertificate(
                metadata.certSha256s, signingKey!!
            )
        ) {
            is KeiyoushiTrust.Verdict.Refuse -> {
                staging.delete()
                val updated = applyLocked(
                    record,
                    KeiyoushiEvent.VerifyFailed(certVerdict.reason)
                )
                return AppResult.Ok(updated)
            }
            else -> Unit
        }
        if (metadata.entryClass.isNullOrBlank()) {
            staging.delete()
            val updated = applyLocked(
                record,
                KeiyoushiEvent.VerifyFailed(
                    "the extension doesn't name its entry class"
                )
            )
            return AppResult.Ok(updated)
        }

        record = record.copy(
            certSha256 = metadata.certSha256s.firstOrNull(),
            entryClass = metadata.entryClass,
            extensionLib = libVersion.toString()
        )
        publishRecordLocked(record)
        val awaiting = applyLocked(record, KeiyoushiEvent.Verified)
        return AppResult.Ok(awaiting)
    }

    /** The consent sheet's data for a staged install, or
     * null when nothing is staged for [packageName]. */
    fun consentPrompt(packageName: String): KeiyoushiConsentPrompt? {
        val record = _records.value[packageName] ?: return null
        if (record.status != KeiyoushiExtStatus.AWAITING_CONSENT) {
            return null
        }
        val fingerprint = record.stagedSha256 ?: return null
        val hosts = catalogCache?.packages
            ?.firstOrNull { it.packageName == packageName }
            ?.sources
            ?.map { it.homeUrl.normaliseHost() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: record.sourceHosts
        val permissions = ExtensionPermissions(
            domains = hosts, canUseNetwork = true
        )
        return KeiyoushiConsentPrompt(
            packageName = packageName,
            displayName = record.displayName,
            summary = permissions.consentSummary(
                app.xreader.data.extension.ExtensionKind.DEX.label
            ),
            fingerprint = fingerprint,
            signerSha256 = record.certSha256,
            versionName = record.stagedVersionName
                .ifBlank { record.indexVersionName }
        )
    }

    /**
     * The user's consent, given for [fingerprint] — the exact
     * bytes the sheet displayed. Only now may code load: the
     * staged file becomes the installed file, the consent and
     * fingerprint pin are recorded, and the load gate
     * re-verifies the bytes before the loader runs.
     */
    suspend fun confirmInstall(
        packageName: String,
        fingerprint: String
    ): AppResult<KeiyoushiPlayerRecord> {
        // Phase 1 (state lock): consent validation, the file
        // moves and the pin updates — quick local work only.
        // The load itself runs AFTER this block, unlocked
        // (v1.4.3: it used to run inside this same mutex,
        // where one extension's slow start froze every other
        // package's install — the §7 hang).
        data class Prepared(
            val record: KeiyoushiPlayerRecord,
            val oldRecord: KeiyoushiPlayerRecord,
            val isUpdate: Boolean
        )
        val prepared = mutex.withLock {
            var record = _records.value[packageName]
                ?: return AppResult.Err(
                    AppError.ExtensionFailed(
                        packageName,
                        "there's nothing waiting for your OK — tap Install first"
                    )
                )
            if (record.status != KeiyoushiExtStatus.AWAITING_CONSENT) {
                return AppResult.Err(
                    AppError.ExtensionFailed(
                        record.displayName,
                        "there's nothing waiting for your OK — tap Install first"
                    )
                )
            }
            val staged = store.keiyoushiStagingFile(packageName)
            val stagedSha = record.stagedSha256
            if (stagedSha == null || !staged.exists()) {
                val updated = applyLocked(
                    record,
                    KeiyoushiEvent.VerifyFailed(
                        "the staged file is gone — tap Install to fetch it again"
                    )
                )
                return AppResult.Ok(updated)
            }
            if (!fingerprint.equals(stagedSha, ignoreCase = true)) {
                // The user agreed to different bytes than were
                // verified. Nothing loads; the staged copy stays
                // for a fresh review.
                return AppResult.Err(
                    AppError.ExtensionFailed(
                        record.displayName,
                        "the file changed since you reviewed it — check it again"
                    )
                )
            }
            val onDisk = store.hashFile(staged)
            if (onDisk == null ||
                !onDisk.equals(stagedSha, ignoreCase = true)
            ) {
                staged.delete()
                val updated = applyLocked(
                    record,
                    KeiyoushiEvent.VerifyFailed(
                        "the file changed after it was checked — it wasn't installed"
                    )
                )
                return AppResult.Ok(updated)
            }

            // An update keeps its predecessor until the new bytes
            // prove they load (the state machine's READY-on-failure
            // rule is only honest if the old file can come back).
            val finalFile = store.keiyoushiExtFile(packageName)
            val previousFile = previousFile(packageName)
            val isUpdate = record.isInstalled && finalFile.exists()
            if (isUpdate) {
                runCatching {
                    finalFile.copyTo(previousFile, overwrite = true)
                }
            }
            val oldRecord = record
            staged.copyTo(finalFile, overwrite = true)
            staged.delete()
            runCatching { finalFile.setReadOnly() }

            record = record.copy(
                consentedSha256 = stagedSha,
                apkSha256 = stagedSha,
                installedVersionCode = record.stagedVersionCode,
                installedVersionName = record.stagedVersionName
                    .ifBlank { record.indexVersionName }
            )
            publishRecordLocked(record)
            Prepared(record, oldRecord, isUpdate)
        }
        val prep = prepared

        // Phase 2 (own locks): gate + load + register.
        val (loaded, ok) = loadRecord(prep.record)
        if (!ok && prep.isUpdate) {
            // Phase 3 (state lock): roll the predecessor back —
            // file, pins, version.
            val previousFile = previousFile(packageName)
            val finalFile = store.keiyoushiExtFile(packageName)
            return mutex.withLock {
                runCatching {
                    if (previousFile.exists()) {
                        finalFile.delete()
                        previousFile.copyTo(finalFile, overwrite = true)
                        finalFile.setReadOnly()
                        previousFile.delete()
                    }
                }
                val reverted = loaded.copy(
                    installedVersionCode = prep.oldRecord.installedVersionCode,
                    installedVersionName = prep.oldRecord.installedVersionName,
                    apkSha256 = prep.oldRecord.apkSha256,
                    consentedSha256 = prep.oldRecord.consentedSha256,
                    status = KeiyoushiExtStatus.READY,
                    failureReason =
                        "the update couldn't be started — the version you had is back"
                )
                publishRecordLocked(reverted)
                AppResult.Ok(reverted)
            }
        }
        if (ok) runCatching { previousFile(packageName).delete() }
        return AppResult.Ok(loaded)
    }

    /**
     * v1.4.3: start an already-installed, already-consented
     * package WITHOUT a new download — the way back for a
     * record whose load failed (the state machine's soft
     * READY-with-notice trap, or a quarantined FAILED the
     * user retries with its Install tap). The bytes are
     * re-hashed against their pin first, exactly as at
     * restore: a mismatch freezes the record FAILED and
     * flips the mirroring row to re-trust, and nothing loads.
     */
    suspend fun retryLoad(
        packageName: String
    ): AppResult<KeiyoushiPlayerRecord> {
        val record = _records.value[packageName]
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    packageName,
                    "that source isn't installed"
                )
            )
        if (!record.isInstalled || record.consentedSha256 == null) {
            return AppResult.Err(
                AppError.ExtensionFailed(
                    record.displayName,
                    "there's no installed copy to start — tap Install"
                )
            )
        }
        val onDisk = store.hashFile(store.keiyoushiExtFile(packageName))
        if (onDisk == null ||
            !onDisk.equals(record.apkSha256, ignoreCase = true)
        ) {
            val failed = mutex.withLock {
                val current = _records.value[packageName] ?: record
                val frozen = current.copy(
                    status = KeiyoushiExtStatus.FAILED,
                    failureReason =
                        "the file changed since it was installed — " +
                            "install it again to review the new file",
                    failureDetail =
                        "onDisk=$onDisk pinned=${record.apkSha256}"
                )
                publishRecordLocked(frozen)
                markRowRetrustLocked(frozen)
                frozen
            }
            return AppResult.Ok(failed)
        }
        val (loaded, _) = loadRecord(record)
        return AppResult.Ok(loaded)
    }

    /** Dismiss a staged install: the staged bytes go, an
     * installed predecessor is untouched. v1.4.3: also the
     * row-Cancel for an in-flight download/verify — the
     * transient states clear the same way, so a cancelled
     * attempt never leaves a phantom "Downloading…" record. */
    suspend fun cancelInstall(packageName: String) {
        mutex.withLock {
            val record = _records.value[packageName] ?: return
            if (record.status != KeiyoushiExtStatus.AWAITING_CONSENT &&
                record.status != KeiyoushiExtStatus.DOWNLOADING &&
                record.status != KeiyoushiExtStatus.VERIFYING
            ) {
                return
            }
            runCatching {
                store.keiyoushiStagingFile(packageName).delete()
            }
            val cleared = record.copy(
                status = if (record.isInstalled) {
                    KeiyoushiExtStatus.READY
                } else {
                    KeiyoushiExtStatus.NOT_INSTALLED
                },
                failureReason = null,
                failureDetail = null,
                stagedSha256 = null,
                stagedVersionCode = 0,
                stagedVersionName = ""
            )
            if (cleared.status == KeiyoushiExtStatus.NOT_INSTALLED &&
                !cleared.isInstalled
            ) {
                publishLocked(_records.value - packageName)
            } else {
                publishRecordLocked(cleared)
            }
        }
    }

    // ── uninstall ───────────────────────────────────────────

    /** The full cascade ([KeiyoushiUninstall.plan]): sources
     * out of the registry, APK deleted, namespaced prefs /
     * cache / session cookies cleaned, the mirroring row
     * deleted, the record dropped. Library books stay; their
     * source reads as gone through the existing orphan
     * handling (no row + no live source). */
    suspend fun uninstall(packageName: String) {
        // v1.4.3: wait out any in-flight load first (the load
        // lock), then run the cascade under the state lock —
        // the one place both locks are held, always in this
        // order (loadMutex, then mutex).
        loadMutex.withLock {
            mutex.withLock {
                uninstallLocked(packageName)
            }
        }
    }

    private suspend fun uninstallLocked(packageName: String) {
        run {
            val record = _records.value[packageName] ?: return
            for (step in KeiyoushiUninstall.plan(record)) {
                when (step) {
                    KeiyoushiUninstall.Step.REMOVE_SOURCES -> {
                        liveSources.keys.removeAll {
                            it.startsWith("keiyoushi:$packageName:")
                        }
                    }
                    KeiyoushiUninstall.Step.REMOVE_APK -> {
                        runCatching {
                            store.keiyoushiExtFile(packageName).delete()
                        }
                        runCatching {
                            store.keiyoushiStagingFile(packageName)
                                .delete()
                        }
                        runCatching { previousFile(packageName).delete() }
                    }
                    KeiyoushiUninstall.Step.REMOVE_NAMESPACED_DATA ->
                        // The cleaner performs BOTH data steps
                        // (namespaced prefs/dirs + session
                        // cookies) in one pass.
                        runCatching { uninstallCleaner.clean(record) }
                    KeiyoushiUninstall.Step.REMOVE_SESSION_COOKIES ->
                        Unit
                    KeiyoushiUninstall.Step.REMOVE_ROW -> {
                        if (record.extensionRowId > 0) {
                            runCatching {
                                extensionDao.getById(record.extensionRowId)
                                    ?.let { extensionDao.delete(it) }
                            }
                        }
                    }
                    KeiyoushiUninstall.Step.REMOVE_RECORD -> {
                        publishLocked(_records.value - packageName)
                    }
                }
            }
        }
    }

    // ── startup restore ─────────────────────────────────────

    /**
     * Cold-start restore: records reload, transient states
     * normalise, and every installed package's bytes are
     * re-hashed against their pin before the loader runs
     * (a mismatch freezes the record FAILED and flips the
     * mirroring row to CHANGED_REQUIRES_RETRUST — the same
     * tamper rule as every other track). Loads run
     * sequentially here on the caller's (background) scope,
     * so app open isn't slowed by parallel dex loads.
     * Idempotent per process.
     *
     * K6 crash safety, two rules on top of K3's shape:
     * - A record persisted with a load in flight means the
     *   last process died WHILE extension code was starting.
     *   That package is quarantined (FAILED with the reason)
     *   and never loaded again on its own — otherwise a
     *   crashing extension would crash-loop every launch.
     * - FAILED records are never auto-retried here. A
     *   package earns its way back only through the user's
     *   Install tap (beginInstall accepts FAILED).
     *
     * Kill switch off: records still publish (the shelf
     * stays truthful about what's installed) but nothing is
     * hashed or loaded.
     */
    suspend fun restoreInstalled() {
        val shouldLoad = mutex.withLock {
            if (restored) return
            restored = true
            restored = true
            playerEnabledFlag = playerEnabledProvider()
            val prepared = recordStore.load().map { stored ->
                var record = stored
                if (record.loadInFlight) {
                    // The process died mid-load. Quarantine —
                    // do not let normalise revive this into
                    // another attempt.
                    record = record.copy(
                        status = KeiyoushiExtStatus.FAILED,
                        failureReason =
                            "it stopped Xreader while it was " +
                                "starting, so it won't be loaded " +
                                "on its own — tap Install to run " +
                                "it again",
                        failureDetail =
                            "a load was in flight when the app " +
                                "last closed",
                        loadInFlight = false,
                        stagedSha256 = null,
                        stagedVersionCode = 0,
                        stagedVersionName = ""
                    )
                }
                if (!record.hasLoadedOnce &&
                    record.status == KeiyoushiExtStatus.READY &&
                    record.sourceIds.isNotEmpty()
                ) {
                    // Migration: a previous build's READY with
                    // registered sources IS a proven load.
                    record = record.copy(hasLoadedOnce = true)
                }
                KeiyoushiStateMachine.normaliseAfterRestart(record)
            }
            publishLocked(prepared.associateBy { it.packageName })
            playerEnabledFlag
        }
        // v1.4.3: the loads run OUTSIDE the state lock (each
        // takes its own locks per package), so a slow cold
        // start can never freeze installs behind restore.
        if (shouldLoad) loadInstalled()
        restoreSettled = true
        // Republish so the settled surfacing (READY must mean
        // LOADED) reaches rows computed during the load pass.
        mutex.withLock { publishLocked(_records.value) }
    }

    /**
     * The load phase of restore, shared with the kill
     * switch's turn-on: every record that should be running
     * (post-normalise LOADING, or READY) is re-hashed
     * against its pin and loaded, sequentially, each
     * failure isolated to its own package. FAILED and
     * INCOMPATIBLE records are deliberately NOT touched —
     * no auto-retry, ever. Callers hold NO lock; the hash
     * pre-check takes the state lock per record and each
     * load runs through [loadRecord]'s own locking.
     */
    private suspend fun loadInstalled() {
        for (snapshot in _records.value.values.toList()) {
            val proceed = mutex.withLock {
                val record = _records.value[snapshot.packageName]
                    ?: return@withLock false
                if (record.consentedSha256 == null) {
                    return@withLock false
                }
                if (record.status != KeiyoushiExtStatus.LOADING &&
                    record.status != KeiyoushiExtStatus.READY
                ) {
                    return@withLock false
                }
                val onDisk = store.hashFile(
                    store.keiyoushiExtFile(record.packageName)
                )
                if (onDisk == null ||
                    !onDisk.equals(record.apkSha256, ignoreCase = true)
                ) {
                    val failed = record.copy(
                        status = KeiyoushiExtStatus.FAILED,
                        failureReason =
                            "the file changed since it was installed — " +
                                "install it again to review the new file",
                        failureDetail =
                            "onDisk=$onDisk pinned=${record.apkSha256}"
                    )
                    publishRecordLocked(failed)
                    markRowRetrustLocked(failed)
                    return@withLock false
                }
                true
            }
            if (!proceed) continue
            val current = _records.value[snapshot.packageName]
                ?: continue
            loadRecord(current)
        }
    }

    // ── the load itself ─────────────────────────────────────

    /**
     * Gate + load + register one installed package. Returns
     * the resulting record and whether sources registered.
     *
     * v1.4.3 locking: the caller holds NO lock. The state
     * transitions take [mutex] in short phases; the loader
     * itself — arbitrary extension code — runs under
     * [loadMutex] alone, so a stuck load can never wedge
     * installs, updates or the shelf (the §7 hang). A record
     * that changed under the load (uninstalled, superseded)
     * fails the final phase's guard and its sources are
     * dropped rather than registered.
     */
    private suspend fun loadRecord(
        starting: KeiyoushiPlayerRecord
    ): Pair<KeiyoushiPlayerRecord, Boolean> {
        // Phase A (state lock): transition to LOADING, arm +
        // persist the in-flight marker, run the load gate.
        var refusalOutcome: KeiyoushiPlayerRecord? = null
        val record = mutex.withLock {
            var current = _records.value[starting.packageName]
                ?: starting
            current = try {
                applyLocked(current, KeiyoushiEvent.LoadStarted)
            } catch (e: IllegalStateException) {
                current.copy(status = KeiyoushiExtStatus.LOADING)
                    .also { publishRecordLocked(it) }
            }
            // K6 (crash safety): arm the in-flight marker and
            // persist it BEFORE the gate or the loader can run
            // extension code. If the process dies during this
            // load, the next restore finds the marker set and
            // quarantines the package (see restoreInstalled)
            // instead of retrying into the same crash on every
            // launch. Every outcome below applies its transition
            // to the DISARMED copy, so a completed load never
            // leaves the marker set.
            val armed = current.copy(loadInFlight = true)
            publishRecordLocked(armed)
            val disarmed = armed.copy(loadInFlight = false)
            val finalFile = store.keiyoushiExtFile(disarmed.packageName)
            val refusal = KeiyoushiLoadGate.refusalReason(
                disarmed, store.hashFile(finalFile)
            )
            if (refusal != null) {
                refusalOutcome = applyLocked(
                    disarmed, KeiyoushiEvent.LoadFailed(refusal)
                )
                return@withLock null
            }
            disarmed
        }
        refusalOutcome?.let { return it to false }
        val loading = record ?: return starting to false
        val finalFile = store.keiyoushiExtFile(loading.packageName)

        val metadata = KeiyoushiApkMetadata(
            packageName = loading.packageName,
            label = loading.displayName,
            versionName = loading.installedVersionName,
            versionCode = loading.installedVersionCode,
            entryClass = loading.entryClass,
            pkgFactory = null,
            extensionLib = loading.extensionLib.toFloatOrNull() ?: 0.0f,
            isNsfw = false,
            certSha256s = listOfNotNull(loading.certSha256)
        )
        // Phase B (load lock only): run the loader.
        val sources = try {
            loadMutex.withLock { loader.load(finalFile, metadata) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            val failure = PlayerFailureWords.describe(t)
            DiagnosticLog.record(
                TAG, "Keiyoushi extension failed to load",
                "${loading.packageName}: ${failure.detail}"
            )
            // K6 (crash safety): a HARD death — an Error
            // anywhere in the chain (linkage failures, stack
            // overflow, VM-level faults) — quarantines the
            // package: FAILED and never auto-retried, even
            // if a proven version exists. An ordinary
            // exception keeps the state machine's rule (a
            // proven version falls back to READY with the
            // notice; an unproven one lands FAILED).
            val hard = generateSequence(t) { it.cause }
                .any { it is Error }
            val failed = mutex.withLock {
                val current = _records.value[loading.packageName]
                    ?: loading
                // The load is OVER: every outcome applies to
                // the DISARMED copy (the pre-1.4.3 rule this
                // restructure must keep) — leaving the marker
                // set would quarantine a healthy package at
                // the next restore.
                applyLocked(
                    current.copy(loadInFlight = false),
                    if (hard) {
                        KeiyoushiEvent.LoadQuarantined(
                            failure.reason, failure.detail
                        )
                    } else {
                        KeiyoushiEvent.LoadFailed(
                            failure.reason, failure.detail
                        )
                    }
                )
            }
            return failed to false
        }
        if (sources.isEmpty()) {
            val failed = mutex.withLock {
                val current = _records.value[loading.packageName]
                    ?: loading
                applyLocked(
                    current.copy(loadInFlight = false),
                    KeiyoushiEvent.LoadFailed(
                        "it loaded but offered no sources"
                    )
                )
            }
            return failed to false
        }

        // Phase C (state lock): register + succeed + row.
        return mutex.withLock {
            val stored = _records.value[loading.packageName]
            if (stored == null ||
                stored.status != KeiyoushiExtStatus.LOADING
            ) {
                // The record moved under the load — it was
                // uninstalled or a newer flow superseded this
                // attempt. Its sources are NOT registered.
                return@withLock (stored ?: loading) to false
            }
            // The load is OVER: the success applies to the
            // DISARMED copy, exactly as the pre-1.4.3 single-
            // lock flow did — a persisted armed marker would
            // quarantine this healthy package at next launch.
            val current = stored.copy(loadInFlight = false)
            // Register: replace this package's previous sources.
            liveSources.keys.removeAll {
                it.startsWith("keiyoushi:${current.packageName}:")
            }
            for (source in sources) {
                liveSources[current.namespacedSourceId(source.id)] = source
            }
            val hosts = LinkedHashSet<String>()
            hosts += current.sourceHosts
            for (source in sources) {
                if (source is HttpSource) {
                    source.baseUrl.normaliseHost()
                        .takeIf { it.isNotEmpty() }?.let { hosts += it }
                }
            }
            catalogCache?.packages
                ?.firstOrNull { it.packageName == current.packageName }
                ?.sources
                ?.forEach { stub ->
                    stub.homeUrl.normaliseHost()
                        .takeIf { it.isNotEmpty() }?.let { hosts += it }
                }
            val ready = applyLocked(
                current,
                KeiyoushiEvent.LoadSucceeded(
                    sourceIds = sources.map {
                        current.namespacedSourceId(it.id)
                    },
                    sourceHosts = hosts.toList(),
                    languages = sources.map {
                        (it as? eu.kanade.tachiyomi.source.CatalogueSource)
                            ?.lang ?: ""
                    }.filter { it.isNotBlank() }
                        .distinct().joinToString(",")
                )
            )
            val withRow = upsertRowLocked(ready, sources.map { it.id })
            withRow to true
        }
    }

    /** Insert/update the mirroring InstalledExtension row
     * (identity for the sources list and library books;
     * an update preserves the row — and with it the user's
     * enabled choice and the books' source link). */
    private suspend fun upsertRowLocked(
        record: KeiyoushiPlayerRecord,
        mihonSourceIds: List<Long>
    ): KeiyoushiPlayerRecord {
        val existing = extensionDao.getByPackageId(record.packageName)
        val permissions = ExtensionPermissions(
            domains = record.sourceHosts,
            canUseNetwork = true,
            dexOptIn = true
        )
        val row = InstalledExtension(
            id = existing?.id ?: 0,
            repoId = existing?.repoId ?: repoIdProvider(),
            displayName = record.displayName,
            packageId = record.packageName,
            versionName = record.installedVersionName,
            versionCode = record.installedVersionCode,
            kind = app.xreader.data.db.ExtensionKind.KEIYOUSHI,
            artifactPath = store.keiyoushiExtFile(record.packageName)
                .absolutePath,
            sha256 = record.apkSha256.orEmpty(),
            // The lib-1.6 marker (×10) — not the Xreader
            // facade API level; nothing on this track
            // reads it as one.
            apiLevel = 16,
            languages = record.languages,
            sourceIds = mihonSourceIds.joinToString(","),
            enabled = existing?.enabled ?: true,
            trustState = TrustState.TRUSTED,
            permissionsJson = permissions.encode(),
            installedAt = existing?.installedAt ?: nowMs(),
            updatedAt = nowMs()
        )
        val rowId = if (existing != null) {
            extensionDao.update(row)
            existing.id
        } else {
            extensionDao.insert(row)
        }
        val updated = record.copy(extensionRowId = rowId)
        publishRecordLocked(updated)
        return updated
    }

    private suspend fun markRowRetrustLocked(
        record: KeiyoushiPlayerRecord
    ) {
        if (record.extensionRowId <= 0) return
        runCatching {
            val row = extensionDao.getById(record.extensionRowId)
                ?: return
            if (row.trustState != TrustState.CHANGED_REQUIRES_RETRUST) {
                extensionDao.update(
                    row.copy(
                        trustState = TrustState.CHANGED_REQUIRES_RETRUST,
                        updatedAt = nowMs()
                    )
                )
            }
        }
    }

    private fun previousFile(packageName: String): File =
        File(
            store.keiyoushiExtDir(),
            store.keiyoushiExtFile(packageName).name + ".previous"
        )

    // ── state plumbing ──────────────────────────────────────

    private fun applyLocked(
        record: KeiyoushiPlayerRecord,
        event: KeiyoushiEvent
    ): KeiyoushiPlayerRecord {
        val next = try {
            KeiyoushiStateMachine.transition(record, event)
        } catch (e: IllegalStateException) {
            // A machine refusal is a programming error, not a
            // user-facing crash: land in FAILED with the
            // detail kept for diagnostics.
            DiagnosticLog.record(
                TAG, "Keiyoushi state machine refusal",
                "${record.packageName}: ${record.status} + $event (${e.message})"
            )
            record.copy(
                status = KeiyoushiExtStatus.FAILED,
                failureReason = "something went wrong while installing it",
                failureDetail = e.message
            )
        }
        val stamped = next.copy(updatedAt = nowMs())
        publishRecordLocked(stamped)
        return stamped
    }

    private fun publishRecordLocked(record: KeiyoushiPlayerRecord) {
        publishLocked(_records.value + (record.packageName to record))
    }

    private fun publishLocked(
        records: Map<String, KeiyoushiPlayerRecord>
    ) {
        _records.value = records
        _packageStates.value = records.mapValues { (_, record) ->
            toPlayerPackageState(record)
        }
        runCatching { recordStore.save(records.values.toList()) }
    }

    private fun toPlayerPackageState(
        record: KeiyoushiPlayerRecord
    ): KeiyoushiIndex.PlayerPackageState {
        val kind = when (record.status) {
            KeiyoushiExtStatus.NOT_INSTALLED ->
                KeiyoushiIndex.PlayerPackageState.Kind.NOT_INSTALLED
            KeiyoushiExtStatus.DOWNLOADING ->
                KeiyoushiIndex.PlayerPackageState.Kind.DOWNLOADING
            KeiyoushiExtStatus.VERIFYING ->
                KeiyoushiIndex.PlayerPackageState.Kind.VERIFYING
            KeiyoushiExtStatus.AWAITING_CONSENT ->
                KeiyoushiIndex.PlayerPackageState.Kind.AWAITING_CONSENT
            KeiyoushiExtStatus.LOADING ->
                KeiyoushiIndex.PlayerPackageState.Kind.LOADING
            KeiyoushiExtStatus.READY ->
                if (record.availableVersionCode >
                    record.installedVersionCode
                ) {
                    KeiyoushiIndex.PlayerPackageState.Kind.UPDATE_AVAILABLE
                } else if (record.failureReason != null &&
                    record.isInstalled && playerEnabledFlag &&
                    restoreSettled &&
                    record.sourceIds.isNotEmpty() &&
                    liveSourcesOf(record.packageName).isEmpty()
                ) {
                    // v1.4.3 (§6.4 of the field diagnosis):
                    // READY must mean LOADED. A record carrying
                    // a failure notice whose sources never made
                    // the registry is NOT installed-and-working,
                    // however its status reads — it surfaces as
                    // FAILED with its reason, and its row's
                    // Install action routes to a fresh load
                    // attempt (retryLoad), not a silent stare.
                    KeiyoushiIndex.PlayerPackageState.Kind.FAILED
                } else {
                    KeiyoushiIndex.PlayerPackageState.Kind.READY
                }
            KeiyoushiExtStatus.FAILED ->
                KeiyoushiIndex.PlayerPackageState.Kind.FAILED
            KeiyoushiExtStatus.INCOMPATIBLE ->
                KeiyoushiIndex.PlayerPackageState.Kind.INCOMPATIBLE
        }
        return KeiyoushiIndex.PlayerPackageState(
            kind = kind,
            reason = record.failureReason
        )
    }

    private companion object {
        const val TAG = "keiyoushi-player"
    }
}
