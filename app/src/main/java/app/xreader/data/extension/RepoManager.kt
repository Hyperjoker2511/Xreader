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
        data class Added(val repo: ExtensionRepo) : AddRepoOutcome
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
    suspend fun addRepoOrDetect(name: String, indexUrl: String): AddRepoOutcome {
        val candidates = RepoLinkNormaliser.candidates(indexUrl)
            .ifEmpty { listOf(indexUrl) }
        var stub: RepoFormat.ForeignRepo? = null
        var firstFailure: AddRepoOutcome.Failed? = null
        for (candidate in candidates) {
            val outcome = detectCandidate(name, candidate, hops = 0)
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
        hops: Int
    ): AddRepoOutcome {
        if (url.endsWith(".pb")) {
            val bytes = when (val raw = downloadBytes(url)) {
                is AppResult.Err -> return AddRepoOutcome.Failed(raw.error)
                is AppResult.Ok -> raw.value
            }
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
                return detectCandidate(name, pointer, hops + 1)
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
        // Pointer files: repo.json names the real (v2) index; a
        // tachiyomix JSON index may likewise point at a separate
        // extension-list file. Follow either, once or twice.
        if (hops < 2) {
            pointerTarget(body)?.let { target ->
                return detectCandidate(name, target, hops + 1)
            }
        }
        return when (val format = RepoFormatDetector.detect(body)) {
            is RepoFormat.ForeignRepo -> AddRepoOutcome.Foreign(format)
            is RepoFormat.Unknown ->
                AddRepoOutcome.Failed(AppError.ParseFailed("repository", format.reason))
            RepoFormat.XreaderIndex ->
                when (val added = addRepo(name, url)) {
                    is AppResult.Ok -> AddRepoOutcome.Added(added.value)
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
        val repo = linkRepo()
        val packageId = packageIdOverride ?: "link.${config.id}"
        val existing = extensionDao.getByPackageId(packageId)
        val fingerprint = DeclarativeFingerprints.of(listOf(config))
        val permissions = ExtensionPermissions(
            domains = listOf(config.baseUrl.normaliseHost())
        )
        store.writeText(
            store.definitionFile(packageId),
            DeclarativeFingerprints.encode(listOf(config))
        )
        val now = nowMs()
        val row = if (existing != null) {
            existing.copy(
                displayName = config.name,
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
        val startUrl = rawUrl.trim()
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
                    name = siteName ?: title?.take(48) ?: name
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
        const val MAX_INDEX_BYTES = 4 * 1024 * 1024
        const val MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
    }
}
