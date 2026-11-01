package app.xreader.data.extension.player

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.TrustState
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

    private val mutex = Mutex()

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
        mutex.withLock {
            val was = playerEnabledFlag
            playerEnabledFlag = enabled
            if (!enabled) {
                liveSources.clear()
            } else if (!was && restored) {
                loadInstalledLocked()
            }
        }
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
        if (parsed is AppResult.Ok) catalogCache = parsed.value
        return parsed
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
        return mutex.withLock { beginInstallLocked(pkg, catalog) }
    }

    private suspend fun beginInstallLocked(
        pkg: KeiyoushiIndex.Package,
        catalog: KeiyoushiIndex.Catalog
    ): AppResult<KeiyoushiPlayerRecord> {
        var record = _records.value[pkg.packageName]
            ?: KeiyoushiPlayerRecord(
                packageName = pkg.packageName,
                displayName = pkg.name
            )

        // The trust anchor first: an index whose key isn't
        // the Keiyoushi key blesses nothing.
        when (val keyVerdict = KeiyoushiTrust.verifyIndexKey(catalog.signingKey)) {
            is KeiyoushiTrust.Verdict.Refuse -> {
                record = applyLocked(
                    record,
                    KeiyoushiEvent.DownloadFailed(keyVerdict.reason)
                )
                return AppResult.Err(
                    AppError.ExtensionFailed(pkg.name, keyVerdict.reason)
                )
            }
            else -> Unit
        }

        record = applyLocked(record, KeiyoushiEvent.DownloadStarted)
        record = record.copy(
            displayName = pkg.name,
            indexVersionCode = pkg.versionCode,
            indexVersionName = pkg.versionName
        )
        publishRecordLocked(record)

        val staging = store.keiyoushiStagingFile(pkg.packageName)
        val sha = try {
            val response = transport.execute(
                HttpTransport.Request(pkg.apkUrl)
            )
            when (response) {
                is AppResult.Err -> {
                    applyLocked(
                        record,
                        KeiyoushiEvent.DownloadFailed(
                            "the download didn't finish",
                            response.error.toString()
                        )
                    )
                    return AppResult.Err(response.error)
                }
                is AppResult.Ok -> {
                    val resp = response.value
                    if (resp.code !in 200..299 || resp.body == null) {
                        val reason =
                            "the download didn't finish (HTTP ${resp.code})"
                        applyLocked(
                            record, KeiyoushiEvent.DownloadFailed(reason)
                        )
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
            if (e is kotlinx.coroutines.CancellationException) throw e
            val reason = "the download didn't finish"
            applyLocked(
                record,
                KeiyoushiEvent.DownloadFailed(reason, e.toString())
            )
            return AppResult.Err(
                AppError.ExtensionFailed(pkg.name, reason)
            )
        }

        record = record.copy(
            stagedSha256 = sha,
            stagedVersionCode = pkg.versionCode,
            stagedVersionName = pkg.versionName
        )
        publishRecordLocked(record)
        record = applyLocked(record, KeiyoushiEvent.Downloaded)

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
                metadata.certSha256s, catalog.signingKey!!
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
    ): AppResult<KeiyoushiPlayerRecord> = mutex.withLock {
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

        val (loaded, ok) = loadRecordLocked(record)
        if (!ok && isUpdate) {
            // Roll the predecessor back: file, pins, version.
            runCatching {
                if (previousFile.exists()) {
                    finalFile.delete()
                    previousFile.copyTo(finalFile, overwrite = true)
                    finalFile.setReadOnly()
                    previousFile.delete()
                }
            }
            val reverted = loaded.copy(
                installedVersionCode = oldRecord.installedVersionCode,
                installedVersionName = oldRecord.installedVersionName,
                apkSha256 = oldRecord.apkSha256,
                consentedSha256 = oldRecord.consentedSha256,
                status = KeiyoushiExtStatus.READY,
                failureReason =
                    "the update couldn't be started — the version you had is back"
            )
            publishRecordLocked(reverted)
            return AppResult.Ok(reverted)
        }
        if (ok) runCatching { previousFile.delete() }
        AppResult.Ok(loaded)
    }

    /** Dismiss a staged install: the staged bytes go, an
     * installed predecessor is untouched. */
    suspend fun cancelInstall(packageName: String) {
        mutex.withLock {
            val record = _records.value[packageName] ?: return
            if (record.status != KeiyoushiExtStatus.AWAITING_CONSENT) {
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
        mutex.withLock {
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
        mutex.withLock {
            if (restored) return
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
            if (!playerEnabledFlag) return
            loadInstalledLocked()
        }
    }

    /**
     * The load phase of restore, shared with the kill
     * switch's turn-on: every record that should be running
     * (post-normalise LOADING, or READY) is re-hashed
     * against its pin and loaded, sequentially, each
     * failure isolated to its own package. FAILED and
     * INCOMPATIBLE records are deliberately NOT touched —
     * no auto-retry, ever. Caller holds [mutex] and has
     * checked the kill switch.
     */
    private suspend fun loadInstalledLocked() {
        for (snapshot in _records.value.values.toList()) {
            val record = _records.value[snapshot.packageName]
                ?: continue
            if (record.consentedSha256 == null) continue
            if (record.status != KeiyoushiExtStatus.LOADING &&
                record.status != KeiyoushiExtStatus.READY
            ) {
                continue
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
                    failureDetail = "onDisk=$onDisk pinned=${record.apkSha256}"
                )
                publishRecordLocked(failed)
                markRowRetrustLocked(failed)
                continue
            }
            loadRecordLocked(record)
        }
    }

    // ── the load itself ─────────────────────────────────────

    /**
     * Gate + load + register one installed package. Returns
     * the resulting record and whether sources registered.
     * Caller holds [mutex].
     */
    private suspend fun loadRecordLocked(
        starting: KeiyoushiPlayerRecord
    ): Pair<KeiyoushiPlayerRecord, Boolean> {
        var record = try {
            applyLocked(starting, KeiyoushiEvent.LoadStarted)
        } catch (e: IllegalStateException) {
            starting.copy(status = KeiyoushiExtStatus.LOADING)
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
        val armed = record.copy(loadInFlight = true)
        publishRecordLocked(armed)
        record = armed.copy(loadInFlight = false)
        val finalFile = store.keiyoushiExtFile(record.packageName)

        val refusal = KeiyoushiLoadGate.refusalReason(
            record, store.hashFile(finalFile)
        )
        if (refusal != null) {
            val failed = applyLocked(
                record, KeiyoushiEvent.LoadFailed(refusal)
            )
            return failed to false
        }

        val metadata = KeiyoushiApkMetadata(
            packageName = record.packageName,
            label = record.displayName,
            versionName = record.installedVersionName,
            versionCode = record.installedVersionCode,
            entryClass = record.entryClass,
            pkgFactory = null,
            extensionLib = record.extensionLib.toFloatOrNull() ?: 0.0f,
            isNsfw = false,
            certSha256s = listOfNotNull(record.certSha256)
        )
        val sources = try {
            loader.load(finalFile, metadata)
        } catch (t: Throwable) {
            val failure = PlayerFailureWords.describe(t)
            DiagnosticLog.record(
                TAG, "Keiyoushi extension failed to load",
                "${record.packageName}: ${failure.detail}"
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
            val failed = applyLocked(
                record,
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
            return failed to false
        }
        if (sources.isEmpty()) {
            val failed = applyLocked(
                record,
                KeiyoushiEvent.LoadFailed(
                    "it loaded but offered no sources"
                )
            )
            return failed to false
        }

        // Register: replace this package's previous sources.
        liveSources.keys.removeAll {
            it.startsWith("keiyoushi:${record.packageName}:")
        }
        for (source in sources) {
            liveSources[record.namespacedSourceId(source.id)] = source
        }
        val hosts = LinkedHashSet<String>()
        hosts += record.sourceHosts
        for (source in sources) {
            if (source is HttpSource) {
                source.baseUrl.normaliseHost()
                    .takeIf { it.isNotEmpty() }?.let { hosts += it }
            }
        }
        catalogCache?.packages
            ?.firstOrNull { it.packageName == record.packageName }
            ?.sources
            ?.forEach { stub ->
                stub.homeUrl.normaliseHost()
                    .takeIf { it.isNotEmpty() }?.let { hosts += it }
            }
        val ready = applyLocked(
            record,
            KeiyoushiEvent.LoadSucceeded(
                sourceIds = sources.map {
                    record.namespacedSourceId(it.id)
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
        return withRow to true
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
