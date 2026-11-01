package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.ExtensionRepoDao
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.net.HttpTransport
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Repositories and installs (PLAN §5/§9.1):
 *
 * - A repo is a user-added index URL. The first fetch PINS the index's
 *   SHA-256 (trust on first use); a later index that doesn't match
 *   the pin is NOT silently accepted — the fetch reports
 *   [IndexFetch.Changed] and the user must explicitly accept the new
 *   list. (A changed index can mean a new maintainer — or a hijack.)
 * - Installs verify bytes against the index-declared hash before
 *   anything is written as trusted.
 * - Updates follow [TrustEngine]: changed fingerprint or widened
 *   permissions stage into pending-* files, freeze the extension,
 *   and wait for re-trust; the live artifact is never overwritten
 *   before the user accepts.
 * - "Add source from link" sources live in a built-in repo row
 *   ("My link sources") so they get the same fingerprint/trust life
 *   as everything else (Amendment 7).
 */
class RepoManager(
    private val repoDao: ExtensionRepoDao,
    private val extensionDao: InstalledExtensionDao,
    private val store: ExtensionStore,
    private val transport: HttpTransport,
    private val service: ExtensionService,
    private val sessionStore: SessionStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    sealed interface IndexFetch {
        data class Fetched(val index: RepoIndex, val sha256: String) : IndexFetch
        /** The index changed since it was pinned — needs the user's OK. */
        data class Changed(
            val index: RepoIndex,
            val pinnedSha256: String,
            val newSha256: String
        ) : IndexFetch
    }

    private val repoJson = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── repos ───────────────────────────────────────────────────────

    /** What adding a repository URL turned out to be (v1.0.1). */
    sealed interface AddRepoOutcome {
        /** v1.3.1 (item 59): [sourceCount] is the REAL number of
         * sources the parsed index carried — the add screen's
         * outcome line quotes it, never a guess. */
        data class Added(
            val repo: ExtensionRepo,
            val sourceCount: Int = 0
        ) : AddRepoOutcome
        /** The URL is another reader app's repository — Xreader
         * can't install those packages, but it can build its own
         * sources for the same sites (the UI lists them). */
        data class Foreign(val format: RepoFormat.ForeignRepo) : AddRepoOutcome
        data class Failed(val error: AppError) : AddRepoOutcome
    }

    /**
     * Detection-aware repo add (v1.0.1): the fetched text is
     * classified FIRST ([RepoFormatDetector]) — a Mihon / LNReader /
     * Legado / generic foreign index is never a raw format error.
     *
     * v1.0.9: the paste is normalised FIRST ([RepoLinkNormaliser])
     * and the candidate index URLs are walked in order — a repo
     * page link, a directory link, an `owner/repo` shorthand and
     * every sibling index shape resolve to the same repo. A stub
     * index (upgrade nags only) never ends the walk; it is the
     * answer only when nothing better exists.
     */
    suspend fun addRepoOrDetect(
        name: String,
        indexUrl: String,
        onStage: (String) -> Unit = {}
    ): AddRepoOutcome {
        // v1.2.2 crash net: the walk's parsers guard their own
        // work, but the caller launches uncaught — any escape
        // (a transport throwing past its contract, an Error
        // from a pathological index body) must land as an
        // honest failure, never as an app crash. (First
        // external crash report, 2026-10-31.)
        // v1.3.1 (item 59): [onStage] reports plain-words stages
        // ("Fetching the list…" / "Reading the sources…") so a
        // minutes-long fetch on a slow connection is never a
        // silent form again.
        return try {
            addRepoOrDetectWalk(name, indexUrl, onStage)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            AddRepoOutcome.Failed(
                AppError.ParseFailed(
                    "repository",
                    "something went wrong while reading that link — nothing was added"
                )
            )
        }
    }

    private suspend fun addRepoOrDetectWalk(
        name: String,
        indexUrl: String,
        onStage: (String) -> Unit = {}
    ): AddRepoOutcome {
        val candidates = RepoLinkNormaliser.candidates(indexUrl)
            .ifEmpty { listOf(indexUrl) }
        var stub: RepoFormat.ForeignRepo? = null
        var firstFailure: AddRepoOutcome.Failed? = null
        for (candidate in candidates) {
            val outcome = detectCandidate(name, candidate, hops = 0, onStage = onStage)
            when (outcome) {
                is AddRepoOutcome.Added -> return outcome
                is AddRepoOutcome.Foreign -> {
                    if (outcome.format.stubIndex) {
                        if (stub == null) stub = outcome.format
                    } else {
                        return outcome
                    }
                }
                is AddRepoOutcome.Failed ->
                    if (firstFailure == null) firstFailure = outcome
            }
        }
        stub?.let { return AddRepoOutcome.Foreign(it) }
        return firstFailure ?: AddRepoOutcome.Failed(
            AppError.ParseFailed("repository", "the link couldn't be read")
        )
    }

    /** One candidate index URL: fetch + classify, following the
     * pointer files (repo.json's index_v2, a tachiyomix
     * extensionListUrl, an index.pb pointer) up to two hops. */
    private suspend fun detectCandidate(
        name: String,
        url: String,
        hops: Int,
        onStage: (String) -> Unit = {}
    ): AddRepoOutcome {
        onStage("Fetching the list…")
        if (url.endsWith(".pb")) {
            val bytes = when (val raw = downloadBytes(url)) {
                is AppResult.Err -> return AddRepoOutcome.Failed(raw.error)
                is AppResult.Ok -> raw.value
            }
            onStage("Reading the sources…")
            if (bytes.size > MAX_INDEX_BYTES) {
                return AddRepoOutcome.Failed(
                    AppError.ProviderDown("repository", detail = "the list was too large")
                )
            }
            val index = PbIndexReader.read(bytes)
            val pointer = index?.extensionListUrl
            if (index != null && index.extensions.isEmpty() &&
                !pointer.isNullOrBlank() && hops < 2
            ) {
                return detectCandidate(name, pointer, hops + 1, onStage)
            }
            return when (val format = RepoFormatDetector.detectPb(bytes)) {
                null -> AddRepoOutcome.Failed(
                    AppError.ParseFailed(
                        "repository",
                        "the binary index (index.pb) couldn't be read"
                    )
                )
                else -> AddRepoOutcome.Foreign(format)
            }
        }
        val body = when (val raw = fetchRaw(url)) {
            is AppResult.Err -> return AddRepoOutcome.Failed(raw.error)
            is AppResult.Ok -> raw.value
        }
        onStage("Reading the sources…")
        // Pointer files: repo.json names the real (v2) index; a
        // tachiyomix JSON index may likewise point at a separate
        // extension-list file. Follow either, once or twice.
        if (hops < 2) {
            pointerTarget(body)?.let { target ->
                return detectCandidate(name, target, hops + 1, onStage)
            }
        }
        return when (val format = RepoFormatDetector.detect(body)) {
            is RepoFormat.ForeignRepo -> AddRepoOutcome.Foreign(format)
            is RepoFormat.Unknown ->
                AddRepoOutcome.Failed(AppError.ParseFailed("repository", format.reason))
            RepoFormat.XreaderIndex ->
                when (val added = addRepo(name, url)) {
                    is AppResult.Ok -> {
                        // v1.3.1 (item 59): count from the index
                        // addRepo just stored — a local re-parse,
                        // no second fetch on a slow link.
                        val count = runCatching {
                            store.readText(store.repoIndexFile(added.value.id))
                                ?.let { stored ->
                                    (parseRepoIndex(stored) as? AppResult.Ok)
                                        ?.value?.packages?.size
                                }
                        }.getOrNull() ?: 0
                        AddRepoOutcome.Added(added.value, count)
                    }
                    is AppResult.Err -> AddRepoOutcome.Failed(added.error)
                }
        }
    }

    /** The URL a pointer body names, or null when [body] isn't a
     * pointer: repo.json's `index_v2`, or a tachiyomix object's
     * `extensionListUrl` when it carries no inline list. */
    private fun pointerTarget(body: String): String? {
        val root = runCatching {
            repoJson.parseToJsonElement(body) as? JsonObject
        }.getOrNull() ?: return null
        (root["index_v2"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }
        if (root["extensionList"] == null) {
            (root["extensionListUrl"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.startsWith("http") }
                ?.let { return it }
        }
        return null
    }

    /** Raw text fetch (v1.0.1: the Legado import path reads source
     * files from a URL through the same transport). */
    suspend fun fetchText(url: String): AppResult<String> = fetchRaw(url)

    suspend fun addRepo(name: String, indexUrl: String): AppResult<ExtensionRepo> {
        val (index, rawBody) = when (val fetched = fetchIndexBody(indexUrl)) {
            is AppResult.Err -> return fetched
            is AppResult.Ok -> fetched.value
        }
        val repo = ExtensionRepo(
            name = name.ifBlank { index.name.ifBlank { "Repository" } },
            indexUrl = indexUrl,
            indexSha256 = Hashes.sha256Hex(rawBody),
            lastFetchedAt = nowMs()
        )
        val id = repoDao.insert(repo)
        store.writeText(store.repoIndexFile(id), rawBody)
        return AppResult.Ok(repo.copy(id = id))
    }

    suspend fun removeRepo(repo: ExtensionRepo) {
        // Installed extensions cascade-delete with the repo (FK).
        val installed = installedSnapshot()
            .filter { it.repoId == repo.id }
        installed.forEach { ext ->
            store.deletePackage(ext.packageId)
            sessionStore.clear(ExtensionHttpClient.sessionKeyFor(ext.id))
            service.invalidate(ext.id)
        }
        repoDao.delete(repo)
        runCatching { store.repoIndexFile(repo.id).delete() }
        runCatching { store.foreignRepoFile(repo.id).delete() }
    }

    /**
     * v1.3.2 (scope E): persist a FOREIGN repository (another
     * app's index) as a repo row + a sidecar site list, so the
     * Repositories view can give it its own screen — the full
     * list, searchable — instead of the add flow's one-shot
     * 40-row preview card with its "…and N more" dead end.
     */
    suspend fun saveForeignRepo(
        name: String,
        indexUrl: String,
        format: RepoFormat.ForeignRepo
    ): ExtensionRepo {
        val repo = ExtensionRepo(
            name = name.ifBlank { format.ecosystemName },
            indexUrl = indexUrl,
            lastFetchedAt = nowMs()
        )
        val id = repoDao.insert(repo)
        store.writeText(
            store.foreignRepoFile(id),
            repoJson.encodeToString(
                RepoFormat.ForeignRepo.serializer(), format
            )
        )
        return repo.copy(id = id)
    }

    /** The persisted site list of a foreign repo, if [repoId]
     * is one. */
    fun loadForeignRepo(repoId: Long): RepoFormat.ForeignRepo? {
        val raw = store.readText(store.foreignRepoFile(repoId))
            ?: return null
        return runCatching {
            repoJson.decodeFromString(
                RepoFormat.ForeignRepo.serializer(), raw
            )
        }.getOrNull()
    }

    /** Re-read a foreign repo's index and refresh its sidecar. */
    suspend fun refreshForeignRepo(
        repo: ExtensionRepo
    ): AppResult<RepoFormat.ForeignRepo> = reconstructForeignRepo(repo)

    /**
     * v1.3.3 (V132-CHECK 6a, scope S7): RECONSTRUCTION — give
     * an EXISTING repo row its foreign site list, for repos
     * added before v1.3.2 persisted one (his Keiyoushi among
     * them: the row existed, the sidecar didn't, so the
     * Repositories view had no page for it and he faced a
     * re-add). The index is re-read from the repo's own
     * recorded address — binary (.pb) indexes included, the
     * same detection the add path runs — and the format is
     * persisted against the row that's already there. No new
     * row, no re-add, nothing about the user's sources
     * changes.
     */
    suspend fun reconstructForeignRepo(
        repo: ExtensionRepo
    ): AppResult<RepoFormat.ForeignRepo> {
        val format = if (repo.indexUrl.endsWith(".pb")) {
            val bytes = when (val raw = downloadBytes(repo.indexUrl)) {
                is AppResult.Err -> return raw
                is AppResult.Ok -> raw.value
            }
            RepoFormatDetector.detectPb(bytes)
                ?: return AppResult.Err(
                    AppError.ParseFailed(
                        "repository",
                        "the binary index (index.pb) couldn't be read"
                    )
                )
        } else {
            val text = when (val fetched = fetchText(repo.indexUrl)) {
                is AppResult.Err -> return fetched
                is AppResult.Ok -> fetched.value
            }
            RepoFormatDetector.detect(text)
                as? RepoFormat.ForeignRepo
                ?: return AppResult.Err(
                    AppError.ExtensionFailed(
                        repo.name,
                        "the list no longer reads as a ${repo.name} list"
                    )
                )
        }
        repoDao.update(repo.copy(lastFetchedAt = nowMs()))
        store.writeText(
            store.foreignRepoFile(repo.id),
            repoJson.encodeToString(
                RepoFormat.ForeignRepo.serializer(), format
            )
        )
        return AppResult.Ok(format)
    }

    /** Fetch + TOFU-check a repo's index. */
    suspend fun refresh(repo: ExtensionRepo): AppResult<IndexFetch> {
        val bodyResult = fetchRaw(repo.indexUrl)
        val body = when (bodyResult) {
            is AppResult.Err -> return bodyResult
            is AppResult.Ok -> bodyResult.value
        }
        val index = when (val parsed = parseRepoIndex(body)) {
            is AppResult.Err -> return parsed
            is AppResult.Ok -> parsed.value
        }
        val newHash = Hashes.sha256Hex(body)
        val pinned = repo.indexSha256
        return if (pinned == null) {
            repoDao.update(
                repo.copy(indexSha256 = newHash, lastFetchedAt = nowMs())
            )
            store.writeText(store.repoIndexFile(repo.id), body)
            AppResult.Ok(IndexFetch.Fetched(index, newHash))
        } else if (Hashes.hashesEqual(pinned, newHash)) {
            repoDao.update(repo.copy(lastFetchedAt = nowMs()))
            store.writeText(store.repoIndexFile(repo.id), body)
            AppResult.Ok(IndexFetch.Fetched(index, newHash))
        } else {
            // Stash the new body so acceptIndexChange doesn't refetch.
            pendingIndexes[repo.id] = body
            AppResult.Ok(IndexFetch.Changed(index, pinned, newHash))
        }
    }

    /** The user accepted a changed index: re-pin and keep it. */
    suspend fun acceptIndexChange(repo: ExtensionRepo): AppResult<RepoIndex> {
        val body = pendingIndexes.remove(repo.id)
            ?: return refresh(repo).map { fetch ->
                when (fetch) {
                    is IndexFetch.Fetched -> fetch.index
                    is IndexFetch.Changed -> fetch.index
                }
            }
        val index = when (val parsed = parseRepoIndex(body)) {
            is AppResult.Err -> return parsed
            is AppResult.Ok -> parsed.value
        }
        repoDao.update(
            repo.copy(indexSha256 = Hashes.sha256Hex(body), lastFetchedAt = nowMs())
        )
        store.writeText(store.repoIndexFile(repo.id), body)
        return AppResult.Ok(index)
    }

    /** The last fetched index for a repo (offline catalogue). */
    fun cachedIndex(repoId: Long): RepoIndex? {
        val raw = store.readText(store.repoIndexFile(repoId)) ?: return null
        return parseRepoIndex(raw).getOrNull()
    }

    // ── install / update ────────────────────────────────────────────

    suspend fun install(repo: ExtensionRepo, pkg: IndexPackage): AppResult<InstalledExtension> {
        val kind = pkg.kindEnum
            ?: return AppResult.Err(
                AppError.ExtensionFailed(pkg.name, "Xreader doesn't know this kind of source")
            )
        if (extensionDao.getByPackageId(pkg.pkg) != null) {
            return AppResult.Err(
                AppError.ExtensionFailed(pkg.name, "this source is already installed")
            )
        }
        val prepared = when (val prep = preparePackage(pkg)) {
            is AppResult.Err -> return prep
            is AppResult.Ok -> prep.value
        }
        val decision = TrustEngine.decideInstall(
            pkg, prepared.fingerprint, prepared.artifactVerified
        )
        val accept = when (decision) {
            is TrustEngine.InstallDecision.Refuse -> return AppResult.Err(
                AppError.ExtensionFailed(pkg.name, decision.reason)
            )
            is TrustEngine.InstallDecision.Accept -> decision
        }
        writePrepared(pkg, prepared, pending = false)
        val now = nowMs()
        val row = InstalledExtension(
            repoId = repo.id,
            displayName = pkg.name,
            packageId = pkg.pkg,
            versionName = pkg.versionName,
            versionCode = pkg.versionCode,
            kind = dbKind(kind),
            artifactPath = if (kind == ExtensionKind.DECLARATIVE) null
            else "extensions/${pkg.pkg.sanitisePath()}/artifact",
            sha256 = accept.fingerprint,
            apiLevel = pkg.apiLevel,
            languages = pkg.languages.joinToString(","),
            sourceIds = pkg.sourceIds.joinToString(","),
            enabled = true,
            trustState = TrustState.TRUSTED,
            permissionsJson = accept.permissions.encode(),
            installedAt = now,
            updatedAt = now
        )
        val id = extensionDao.insert(row)
        return AppResult.Ok(row.copy(id = id))
    }

    /** An update candidate found in a refreshed index. */
    data class UpdateCandidate(
        val installed: InstalledExtension,
        val pkg: IndexPackage,
        val decision: TrustEngine.UpdateDecision
    )

    suspend fun findUpdates(index: RepoIndex): List<UpdateCandidate> {
        val installed = installedSnapshot()
        val autoApply = autoApplySafeUpdates
        return index.packages.mapNotNull { pkg ->
            val row = installed.firstOrNull { it.packageId == pkg.pkg }
                ?: return@mapNotNull null
            if (pkg.versionCode <= row.versionCode) return@mapNotNull null
            val fingerprint = fingerprintOf(pkg)
            val decision = TrustEngine.decideUpdate(
                installed = row,
                pkg = pkg,
                newFingerprint = fingerprint,
                newDeclaredPermissions = pkg.installPermissions(),
                autoApplySafeUpdates = autoApply
            )
            UpdateCandidate(row, pkg, decision)
        }
    }

    /**
     * Apply or stage an update per its decision. AutoApply/ApplyOnTap
     * overwrite in place (fingerprint + permissions unchanged, so the
     * trust story is identical); RetrustRequired stages pending files
     * and freezes the row. Returns the decision actually carried out.
     */
    suspend fun applyUpdate(
        candidate: UpdateCandidate
    ): AppResult<TrustEngine.UpdateDecision> {
        val (row, pkg, decision) = candidate
        when (decision) {
            is TrustEngine.UpdateDecision.UpToDate -> return AppResult.Ok(decision)
            is TrustEngine.UpdateDecision.Refuse -> return AppResult.Err(
                AppError.ExtensionFailed(pkg.name, decision.reason)
            )
            else -> Unit
        }
        val prepared = when (val prep = preparePackage(pkg)) {
            is AppResult.Err -> return prep
            is AppResult.Ok -> prep.value
        }
        // Re-verify bytes before staging OR applying.
        if (pkg.kindEnum != ExtensionKind.DECLARATIVE && !prepared.artifactVerified) {
            return AppResult.Err(
                AppError.ExtensionFailed(
                    pkg.name,
                    "the update's file didn't match the fingerprint the repository " +
                        "published, so it was refused"
                )
            )
        }
        val newPermissions = mergePermissions(
            old = ExtensionPermissions.parse(row.permissionsJson),
            declared = pkg.installPermissions()
        )
        return when (decision) {
            is TrustEngine.UpdateDecision.AutoApply,
            is TrustEngine.UpdateDecision.ApplyOnTap -> {
                writePrepared(pkg, prepared, pending = false)
                extensionDao.update(
                    row.copy(
                        displayName = pkg.name,
                        versionName = pkg.versionName,
                        versionCode = pkg.versionCode,
                        sha256 = prepared.fingerprint,
                        apiLevel = pkg.apiLevel,
                        languages = pkg.languages.joinToString(","),
                        sourceIds = pkg.sourceIds.joinToString(","),
                        permissionsJson = newPermissions.encode(),
                        updatedAt = nowMs()
                    )
                )
                service.invalidate(row.id)
                AppResult.Ok(decision)
            }
            is TrustEngine.UpdateDecision.RetrustRequired -> {
                writePrepared(pkg, prepared, pending = true)
                store.writePending(
                    pkg.pkg,
                    PendingUpdate(
                        versionName = pkg.versionName,
                        versionCode = pkg.versionCode,
                        fingerprint = prepared.fingerprint,
                        permissionsJson = newPermissions.encode(),
                        reasons = decision.reasons,
                        stagedAt = nowMs()
                    )
                )
                extensionDao.update(
                    row.copy(trustState = TrustState.CHANGED_REQUIRES_RETRUST)
                )
                service.invalidate(row.id)
                AppResult.Ok(decision)
            }
            else -> AppResult.Ok(decision)
        }
    }

    /** Accept a staged update: promote files, pin the new fingerprint. */
    suspend fun acceptPendingUpdate(row: InstalledExtension): AppResult<InstalledExtension> {
        val pending = store.readPending(row.packageId)
            ?: return AppResult.Err(
                AppError.ExtensionFailed(row.displayName, "there's no waiting update")
            )
        store.promotePending(row.packageId)
        val updated = row.copy(
            versionName = pending.versionName,
            versionCode = pending.versionCode,
            sha256 = pending.fingerprint,
            permissionsJson = pending.permissionsJson,
            trustState = TrustState.TRUSTED,
            enabled = true,
            updatedAt = nowMs()
        )
        extensionDao.update(updated)
        service.invalidate(row.id)
        return AppResult.Ok(updated)
    }

    /** Discard a staged update; the trusted version stays as it was. */
    suspend fun rejectPendingUpdate(row: InstalledExtension) {
        store.clearPending(row.packageId)
        extensionDao.update(
            row.copy(trustState = TrustState.TRUSTED, updatedAt = nowMs())
        )
        service.invalidate(row.id)
    }

    /**
     * Re-trust without a pending update: the user inspected a frozen
     * source (e.g. after a tamper freeze) and accepts what's on disk —
     * the current bytes become the new pin.
     */
    suspend fun retrust(row: InstalledExtension): AppResult<InstalledExtension> {
        if (store.hasPending(row.packageId)) return acceptPendingUpdate(row)
        val newPin = when (row.kind) {
            app.xreader.data.db.ExtensionKind.DECLARATIVE -> {
                val raw = store.readText(store.definitionFile(row.packageId))
                    ?: return AppResult.Err(
                        AppError.ExtensionFailed(row.displayName, "its rules file is missing")
                    )
                DeclarativeFingerprints.of(DeclarativeFingerprints.decode(raw))
            }
            // v1.0.9: a Live Source's pin is its stored spec,
            // hashed exactly as installLiveSource pinned it.
            app.xreader.data.db.ExtensionKind.LIVE -> {
                val raw = store.readText(store.definitionFile(row.packageId))
                    ?: return AppResult.Err(
                        AppError.ExtensionFailed(row.displayName, "its live-source file is missing")
                    )
                Hashes.sha256Hex(raw)
            }
            else -> store.hashFile(store.artifactFile(row.packageId))
                ?: return AppResult.Err(
                    AppError.ExtensionFailed(row.displayName, "its file is missing")
                )
        }
        val updated = row.copy(
            sha256 = newPin,
            trustState = TrustState.TRUSTED,
            enabled = true,
            updatedAt = nowMs()
        )
        extensionDao.update(updated)
        service.invalidate(row.id)
        return AppResult.Ok(updated)
    }

    suspend fun uninstall(row: InstalledExtension) {
        extensionDao.delete(row)
        store.deletePackage(row.packageId)
        sessionStore.clear(ExtensionHttpClient.sessionKeyFor(row.id))
        service.invalidate(row.id)
    }

    suspend fun setEnabled(row: InstalledExtension, enabled: Boolean) {
        extensionDao.update(row.copy(enabled = enabled, updatedAt = nowMs()))
        service.invalidate(row.id)
    }

    suspend fun updatePermissions(row: InstalledExtension, permissions: ExtensionPermissions) {
        extensionDao.update(
            row.copy(permissionsJson = permissions.encode(), updatedAt = nowMs())
        )
        service.invalidate(row.id)
    }

    // ── link sources (Amendment 7) ──────────────────────────────────

    /**
     * Save a probed add-from-link source: a normal declarative install
     * inside the built-in "My link sources" repo, fingerprinted from
     * its inferred rules. The probe session (if the user logged in
     * during probing) moves to the new extension's session key.
     */
    suspend fun saveLinkSource(
        config: DeclarativeConfig,
        probeHost: String,
        // v1.0.6.1 (P5): a Repair save writes into the EXISTING
        // row's package (the row's packageId), never a package
        // derived from the rebuilt config's id — a rebuilt id
        // that differs from the original would fork the source
        // into a second package. Repair callers pass the row's
        // packageId; new-source saves leave this null and keep
        // the derived package.
        packageIdOverride: String? = null
    ): AppResult<InstalledExtension> {
        // v1.2.3: the save path enforces the same catalogue
        // rule as the probe's gate. A config with neither a
        // popular nor a latest spec opens to an empty source
        // and fails Health with "has no catalogue page" before
        // any fetch — refusing it HERE turns every caller
        // (add-from-link, Repair, the heal sweep) honest at
        // once: nothing catalogue-less is ever persisted.
        if (config.popular == null && config.latest == null) {
            return AppResult.Err(
                AppError.BadFile(
                    "These rules have no book list (popular or " +
                        "latest page), so the source would open " +
                        "empty — nothing was saved."
                )
            )
        }
        val repo = linkRepo()
        val packageId = packageIdOverride ?: "link.${config.id}"
        val existing = extensionDao.getByPackageId(packageId)
        val fingerprint = DeclarativeFingerprints.of(listOf(config))
        val permissions = ExtensionPermissions(
            domains = listOf(config.baseUrl.normaliseHost()) +
                config.extraDomains
        )
        store.writeText(
            store.definitionFile(packageId),
            DeclarativeFingerprints.encode(listOf(config))
        )
        val now = nowMs()
        val row = if (existing != null) {
            existing.copy(
                displayName = config.name,
                // v1.1.0 (design §6.3 step 2): converting a
                // LIVE row in place — the stored definition was
                // a LiveSourceSpec and is now a declarative
                // config list, so the row's KIND must flip with
                // it, or the source fails to start on the decode
                // mismatch (ExtensionService reads LIVE rows as
                // LiveSourceSpec, DECLARATIVE rows as configs).
                // Same row, same packageId, same library links.
                kind = if (
                    existing.kind == app.xreader.data.db.ExtensionKind.LIVE
                ) {
                    app.xreader.data.db.ExtensionKind.DECLARATIVE
                } else {
                    existing.kind
                },
                versionCode = existing.versionCode + 1,
                sha256 = fingerprint,
                sourceIds = config.id,
                permissionsJson = permissions.encode(),
                trustState = TrustState.TRUSTED,
                enabled = true,
                updatedAt = now
            ).also { extensionDao.update(it) }
        } else {
            InstalledExtension(
                repoId = repo.id,
                displayName = config.name,
                packageId = packageId,
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                artifactPath = null,
                sha256 = fingerprint,
                apiLevel = app.xreader.extensionapi.ExtensionApi.API_LEVEL,
                languages = config.lang,
                sourceIds = config.id,
                enabled = true,
                trustState = TrustState.TRUSTED,
                permissionsJson = permissions.encode(),
                installedAt = now,
                updatedAt = now
            ).let { it.copy(id = extensionDao.insert(it)) }
        }
        // The probe session becomes the source's session.
        sessionStore.copyTo(
            ExtensionHttpClient.linkProbeSessionKey(probeHost),
            ExtensionHttpClient.sessionKeyFor(row.id)
        )
        service.invalidate(row.id)
        return AppResult.Ok(row)
    }

    /**
     * Install a Live Source (v1.0.9): the user's pasted link
     * becomes a source that derives its catalogue / book /
     * reader views live from the site's pages — there is no
     * probe and no failure state at install time; the worst a
     * site can do is read poorly on a specific page, in place.
     * Display identity (name, icon) is discovered from the
     * site's own manifest / meta tags, falling back to its
     * host name. Re-installing the same host replaces the row.
     */
    suspend fun installLiveSource(rawUrl: String): AppResult<InstalledExtension> {
        // v1.0.9.1 fix (the on-device "storage problem"): the
        // raw input went straight to java.net.URI, whose host
        // is null for a scheme-less paste like "chikari.moe"
        // — the install refused a link the probe accepts.
        // Normalise exactly like the probe does (bare domains
        // gain https://, share-text junk is stripped).
        val startUrl = LinkProber.normaliseUrl(rawUrl)
            ?: return AppResult.Err(
                AppError.ParseFailed("link", "that link doesn't name a website")
            )
        val uri = runCatching { java.net.URI(startUrl) }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotBlank() }
            ?: return AppResult.Err(
                AppError.ParseFailed("link", "that link doesn't name a website")
            )
        val baseUrl = "${uri.scheme ?: "https"}://$host" +
            if (uri.port != -1) ":${uri.port}" else ""
        // v1.0.9: a seeded catalogue name beats the bare host
        // when the site's own pages don't name it better.
        var name = SeedCatalog.nameFor(host) ?: host.removePrefix("www.")
        var iconUrl: String? = "$baseUrl/favicon.ico"
        var namedByManifest = false
        // Best-effort identity discovery — the install never
        // waits on it and never fails because of it.
        val pageText = (fetchRaw(startUrl) as? AppResult.Ok)?.value
        if (pageText != null) {
            runCatching {
                val doc = org.jsoup.Jsoup.parse(pageText, startUrl)
                val manifestHref = doc.selectFirst("link[rel=manifest]")
                    ?.attr("abs:href")?.ifBlank { null }
                val manifestText = manifestHref?.let {
                    (fetchRaw(it) as? AppResult.Ok)?.value
                }
                if (manifestText != null) {
                    val manifest = repoJson.parseToJsonElement(manifestText)
                        as? JsonObject
                    val manifestName = manifest?.let {
                        (it["name"] as? JsonPrimitive)?.contentOrNull
                            ?: (it["short_name"] as? JsonPrimitive)?.contentOrNull
                    }?.ifBlank { null }
                    if (manifestName != null) {
                        name = manifestName
                        namedByManifest = true
                    }
                    val icons = (manifest?.get("icons") as? kotlinx.serialization.json.JsonArray)
                    val bestIcon = icons?.mapNotNull { element ->
                        val icon = element as? JsonObject ?: return@mapNotNull null
                        val src = (icon["src"] as? JsonPrimitive)?.contentOrNull
                            ?: return@mapNotNull null
                        val size = (icon["sizes"] as? JsonPrimitive)?.contentOrNull
                            ?.substringBefore("x")?.toIntOrNull() ?: 0
                        size to src
                    }?.maxByOrNull { it.first }
                    if (bestIcon != null) {
                        iconUrl = java.net.URI(manifestHref)
                            .resolve(bestIcon.second).toString()
                    }
                }
                if (!namedByManifest) {
                    val siteName = doc.selectFirst("meta[property=og:site_name]")
                        ?.attr("content")?.ifBlank { null }
                    val title = doc.title().ifBlank { null }
                    // v1.2.3: the raw <title> fallback used to
                    // be the whole tagline, cut mid-word at 48
                    // chars ("NovelBuddy— Read Web Novels &
                    // Light Novels Onlin") — the exact defect
                    // seen on-device. cleanSiteTitle keeps the
                    // title's HEAD segment: the site's name.
                    name = siteName ?: title?.let { cleanSiteTitle(it) } ?: name
                }
                val touchIcon = doc.selectFirst("link[rel=apple-touch-icon]")
                    ?.attr("abs:href")?.ifBlank { null }
                val icon = doc.selectFirst("link[rel=icon]")
                    ?.attr("abs:href")?.ifBlank { null }
                if (manifestText == null) iconUrl = touchIcon ?: icon ?: iconUrl
            }
        }
        val spec = LiveSourceSpec(
            id = "live." + host.replace('.', '-'),
            name = name,
            baseUrl = baseUrl,
            startUrl = startUrl,
            iconUrl = iconUrl
        )
        val repo = linkRepo()
        val packageId = "live.$host"
        val fingerprint = Hashes.sha256Hex(spec.encode())
        val permissions = ExtensionPermissions(domains = listOf(host))
        store.writeText(store.definitionFile(packageId), spec.encode())
        val now = nowMs()
        val existing = extensionDao.getByPackageId(packageId)
        val row = if (existing != null) {
            existing.copy(
                displayName = spec.name,
                kind = app.xreader.data.db.ExtensionKind.LIVE,
                versionCode = existing.versionCode + 1,
                sha256 = fingerprint,
                sourceIds = spec.id,
                permissionsJson = permissions.encode(),
                trustState = TrustState.TRUSTED,
                enabled = true,
                updatedAt = now
            ).also { extensionDao.update(it) }
        } else {
            InstalledExtension(
                repoId = repo.id,
                displayName = spec.name,
                packageId = packageId,
                versionName = "1.0",
                versionCode = 1,
                kind = app.xreader.data.db.ExtensionKind.LIVE,
                artifactPath = null,
                sha256 = fingerprint,
                apiLevel = app.xreader.extensionapi.ExtensionApi.API_LEVEL,
                languages = spec.lang,
                sourceIds = spec.id,
                enabled = true,
                trustState = TrustState.TRUSTED,
                permissionsJson = permissions.encode(),
                installedAt = now,
                updatedAt = now
            ).let { it.copy(id = extensionDao.insert(it)) }
        }
        service.invalidate(row.id)
        return AppResult.Ok(row)
    }

    /** Raw bytes for a URL (v1.0.9: site icons for home-screen
     * shortcuts) through the same capped download path repos use. */
    suspend fun fetchBytes(url: String): AppResult<ByteArray> = downloadBytes(url)

    private suspend fun linkRepo(): ExtensionRepo {
        val repos = repoDaoSnapshot()
        repos.firstOrNull { it.indexUrl == LINK_REPO_URL }?.let { return it }
        val repo = ExtensionRepo(
            name = "My link sources",
            indexUrl = LINK_REPO_URL,
            indexSha256 = null,
            lastFetchedAt = null,
            enabled = true
        )
        return repo.copy(id = repoDao.insert(repo))
    }

    private suspend fun bundledRepo(): ExtensionRepo {
        val repos = repoDaoSnapshot()
        repos.firstOrNull { it.indexUrl == BUNDLED_REPO_URL }?.let { return it }
        val repo = ExtensionRepo(
            name = "Built-in sources",
            indexUrl = BUNDLED_REPO_URL,
            indexSha256 = null,
            lastFetchedAt = null,
            enabled = true
        )
        return repo.copy(id = repoDao.insert(repo))
    }

    /**
     * v1.3.0: install (or safely refresh) the app's BUILT-IN
     * default sources ([BundledDefaults]) — layer 1 of the
     * three-layer source model. Runs once per process at
     * startup; idempotent and offline (nothing is fetched).
     *
     * - A bundled source that isn't installed is installed
     *   under the "Built-in sources" repo, exactly like a
     *   link source's row (declarative kind, trusted, enabled).
     * - An installed bundled row refreshes to the newly
     *   shipped rules ONLY while its stored fingerprint is
     *   still one the app itself shipped for that source
     *   ([BundledDefaults.SHIPPED_FINGERPRINTS]): the moment a
     *   user Repairs or edits the source, its definition's
     *   fingerprint leaves the shipped set and the row is
     *   never touched again. Rows are NEVER deleted here, and
     *   a row the user disabled stays disabled (a refresh
     *   keeps its enabled state; only the rules move).
     *
     * Returns how many rows were installed or refreshed.
     */
    suspend fun installBundledDefaults(): Int {
        val configs = BundledDefaults.configs()
        if (configs.isEmpty()) return 0
        val repo = bundledRepo()
        val now = nowMs()
        var changed = 0
        for (config in configs) {
            val packageId = "bundled.${config.id}"
            val fingerprint = DeclarativeFingerprints.of(listOf(config))
            val existing = extensionDao.getByPackageId(packageId)
            if (existing == null) {
                store.writeText(
                    store.definitionFile(packageId),
                    DeclarativeFingerprints.encode(listOf(config))
                )
                val permissions = ExtensionPermissions(
                    domains = listOf(config.baseUrl.normaliseHost()) +
                        config.extraDomains
                )
                val row = InstalledExtension(
                    repoId = repo.id,
                    displayName = config.name,
                    packageId = packageId,
                    versionName = "1.0",
                    versionCode = 1,
                    kind = app.xreader.data.db.ExtensionKind.DECLARATIVE,
                    artifactPath = null,
                    sha256 = fingerprint,
                    apiLevel = app.xreader.extensionapi.ExtensionApi.API_LEVEL,
                    languages = config.lang,
                    sourceIds = config.id,
                    enabled = true,
                    trustState = TrustState.TRUSTED,
                    permissionsJson = permissions.encode(),
                    installedAt = now,
                    updatedAt = now
                )
                extensionDao.insert(row)
                changed++
            } else if (existing.sha256 != fingerprint &&
                existing.sha256 in
                (BundledDefaults.SHIPPED_FINGERPRINTS[config.id]
                    ?: emptySet())
            ) {
                // An untouched bundled row from an older app
                // version: move its rules to the shipped ones.
                store.writeText(
                    store.definitionFile(packageId),
                    DeclarativeFingerprints.encode(listOf(config))
                )
                extensionDao.update(
                    existing.copy(
                        displayName = config.name,
                        versionCode = existing.versionCode + 1,
                        sha256 = fingerprint,
                        sourceIds = config.id,
                        updatedAt = now
                    )
                )
                service.invalidate(existing.id)
                changed++
            }
            // Otherwise the row is current, or the user's own
            // version of it — either way it is left exactly as
            // it is.
        }
        return changed
    }

    // ── internals ───────────────────────────────────────────────────

    /** Downloaded/derived content of one package, ready to write. */
    private data class Prepared(
        val fingerprint: String,
        val artifactBytes: ByteArray?,
        val definitionJson: String,
        val artifactVerified: Boolean
    )

    private suspend fun preparePackage(pkg: IndexPackage): AppResult<Prepared> {
        val kind = pkg.kindEnum
            ?: return AppResult.Err(
                AppError.ExtensionFailed(pkg.name, "unknown source kind")
            )
        return when (kind) {
            ExtensionKind.DECLARATIVE -> AppResult.Ok(
                Prepared(
                    fingerprint = DeclarativeFingerprints.of(pkg.sources),
                    artifactBytes = null,
                    definitionJson = DeclarativeFingerprints.encode(pkg.sources),
                    artifactVerified = true
                )
            )
            ExtensionKind.JS, ExtensionKind.DEX -> {
                val url = pkg.artifactUrl
                    ?: return AppResult.Err(
                        AppError.ExtensionFailed(pkg.name, "the index didn't say where its file lives")
                    )
                val bytes = when (val downloaded = downloadBytes(url)) {
                    is AppResult.Err -> return downloaded
                    is AppResult.Ok -> downloaded.value
                }
                val hash = Hashes.sha256Hex(bytes)
                AppResult.Ok(
                    Prepared(
                        fingerprint = hash,
                        artifactBytes = bytes,
                        definitionJson = Json { ignoreUnknownKeys = true }.encodeToString(
                            CodeDefinition.serializer(),
                            CodeDefinition(pkg.entryClass, pkg.sourcesMeta)
                        ),
                        artifactVerified = Hashes.hashesEqual(hash, pkg.sha256)
                    )
                )
            }
        }
    }

    /** The fingerprint used for update decisions: rules-hash for
     * declarative, the index-declared artifact hash otherwise (bytes
     * are verified at stage/apply time). */
    private fun fingerprintOf(pkg: IndexPackage): String =
        if (pkg.kindEnum == ExtensionKind.DECLARATIVE) {
            DeclarativeFingerprints.of(pkg.sources)
        } else {
            pkg.sha256.lowercase()
        }

    private fun writePrepared(pkg: IndexPackage, prepared: Prepared, pending: Boolean) {
        if (prepared.artifactBytes != null) {
            store.writeArtifact(
                if (pending) store.pendingArtifactFile(pkg.pkg)
                else store.artifactFile(pkg.pkg),
                prepared.artifactBytes
            )
        }
        store.writeText(
            if (pending) store.pendingDefinitionFile(pkg.pkg)
            else store.definitionFile(pkg.pkg),
            prepared.definitionJson
        )
    }

    /** Keep the user's per-domain blocks and dex opt-in across updates;
     * declared domains/canNotify come from the new index entry. */
    private fun mergePermissions(
        old: ExtensionPermissions,
        declared: ExtensionPermissions
    ): ExtensionPermissions = declared.copy(
        blockedDomains = old.blockedDomains.filter { blocked ->
            declared.domains.any { it.normaliseHost() == blocked.normaliseHost() }
        },
        dexOptIn = old.dexOptIn
    )

    private suspend fun fetchIndexBody(
        indexUrl: String
    ): AppResult<Pair<RepoIndex, String>> {
        val body = when (val raw = fetchRaw(indexUrl)) {
            is AppResult.Err -> return raw
            is AppResult.Ok -> raw.value
        }
        val index = when (val parsed = parseRepoIndex(body)) {
            is AppResult.Err -> return parsed
            is AppResult.Ok -> parsed.value
        }
        return AppResult.Ok(index to body)
    }

    private suspend fun fetchRaw(url: String): AppResult<String> {
        val response = when (
            val result = transport.execute(HttpTransport.Request(url = url))
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        if (response.code !in 200..299) {
            response.body?.close()
            return AppResult.Err(HttpTransport.statusError(response.code))
        }
        val bytes = response.body?.use { it.readBytes() }
            ?: return AppResult.Err(
                AppError.ProviderDown("repository", detail = "empty response")
            )
        if (bytes.size > MAX_INDEX_BYTES) {
            return AppResult.Err(
                AppError.ProviderDown("repository", detail = "the list was too large")
            )
        }
        return AppResult.Ok(bytes.toString(Charsets.UTF_8))
    }

    private suspend fun downloadBytes(url: String): AppResult<ByteArray> {
        val response = when (
            val result = transport.execute(HttpTransport.Request(url = url))
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        if (response.code !in 200..299) {
            response.body?.close()
            return AppResult.Err(HttpTransport.statusError(response.code))
        }
        val bytes = response.body?.use { stream -> stream.readBytes() }
            ?: return AppResult.Err(
                AppError.ProviderDown("repository", detail = "empty download")
            )
        if (bytes.size > MAX_ARTIFACT_BYTES) {
            return AppResult.Err(
                AppError.ProviderDown("repository", detail = "the source file was too large")
            )
        }
        return AppResult.Ok(bytes)
    }

    private suspend fun installedSnapshot(): List<InstalledExtension> =
        extensionDao.observeAll().first()

    private suspend fun repoDaoSnapshot(): List<ExtensionRepo> =
        repoDao.observeAll().first()

    /** Policy toggle (§9.1): safe updates auto-apply when on. Default on. */
    @Volatile
    var autoApplySafeUpdates: Boolean = true

    private val pendingIndexes = mutableMapOf<Long, String>()

    private fun dbKind(kind: ExtensionKind) = when (kind) {
        ExtensionKind.DECLARATIVE -> app.xreader.data.db.ExtensionKind.DECLARATIVE
        ExtensionKind.JS -> app.xreader.data.db.ExtensionKind.JS
        ExtensionKind.DEX -> app.xreader.data.db.ExtensionKind.DEX
    }

    private fun String.sanitisePath(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "pkg" }

    companion object {
        const val LINK_REPO_URL = "xreader://link-sources"
        /** v1.3.0: the synthetic repo the built-in default
         * sources live under (see [installBundledDefaults]). */
        const val BUNDLED_REPO_URL = "xreader://bundled-sources"
        const val MAX_INDEX_BYTES = 4 * 1024 * 1024
        const val MAX_ARTIFACT_BYTES = 64 * 1024 * 1024

        /** v1.2.3: separators a page <title> uses between the
         * site's name and its tagline ("NovelBuddy— Read Web
         * Novels Online Free", "Site Name | Read Novels"). */
        private val TITLE_SEPARATORS = listOf(
            " — ", " – ", " | ", " · ", " - ", "—", "–"
        )

        /**
         * v1.2.3: a source name from a raw page title — the
         * HEAD segment only ("NovelBuddy"), never the tagline,
         * never a mid-word cut. A head that is implausibly
         * short (< 2 chars) or long (> 40) falls back to the
         * whole title trimmed to 40 chars at a word boundary.
         */
        fun cleanSiteTitle(rawTitle: String): String? {
            val title = rawTitle.trim()
            if (title.isEmpty()) return null
            var head = title
            for (separator in TITLE_SEPARATORS) {
                val index = title.indexOf(separator)
                if (index >= 2) {
                    head = title.substring(0, index).trim()
                    break
                }
            }
            if (head.length in 2..40) return head
            if (head.length <= 40) return head.ifBlank { null }
            val cut = head.substring(0, 40)
            return cut.substringBeforeLast(' ').ifBlank { cut }.trim()
                .ifBlank { null }
        }
    }
}
