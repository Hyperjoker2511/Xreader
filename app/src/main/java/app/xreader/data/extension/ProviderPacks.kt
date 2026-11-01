package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ExtensionPermissions.Companion.encode
import app.xreader.data.net.HttpTransport
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Provider packs (Cycle 1 — layer 2 of the three-layer source
 * model: built-in verified defaults · PROVIDER PACKS through
 * the repo channel · the paste-a-link learner as safety net).
 *
 * A pack is a versioned JSON document the maintainer publishes
 * in the Xreader GitHub repo. It carries declarative source
 * configs in EXACTLY the shape the bundled defaults use
 * ([DeclarativeConfig]) and feeds the EXACT same install
 * pipeline: applying a pack materialises one trusted
 * declarative row per source under a synthetic "Provider
 * packs" repo, with the rules written to the same definition
 * files [ExtensionService] already loads. There is no second
 * engine.
 *
 * Trust rules (the plan's pack-trust recommendation):
 * - A pack is validated COMPLETELY — schema, every config
 *   through the declarative decoder, semantic checks — before
 *   anything is applied. An invalid pack changes nothing.
 * - Pack sources live in their own namespace: every source id
 *   starts with `pack-`, rows carry package ids under
 *   `packs.`, and a pack may only ever add/update/remove ITS
 *   OWN rows. User-added (link) sources, repo-installed
 *   sources and bundled defaults are structurally out of
 *   reach.
 * - The previous pack version is kept whole; one-tap rollback
 *   re-materialises it exactly (sources the newer pack added
 *   are removed, sources it dropped come back).
 * - Fetch failures are silent: the app keeps working with
 *   whatever pack state it already has.
 */

/** One pack document, as published. [sources] may be empty —
 * a live channel that has shipped nothing yet is a valid,
 * no-op pack (that is the state of the hosted file until the
 * first site conversions land). */
@Serializable
data class ProviderPack(
    val format: String = ProviderPackCodec.FORMAT_TAG,
    val formatVersion: Int = ProviderPackCodec.FORMAT_VERSION,
    val id: String,
    val version: Int,
    /** ISO calendar date (yyyy-MM-dd) the pack was published. */
    val date: String,
    val sources: List<PackSource> = emptyList()
)

/** One source inside a pack: display metadata beside the
 * config itself. The metadata is what list UIs show before
 * (or without) materialising; [config] is the source of
 * truth and validation requires the two to agree on identity
 * (id, and the base URL's host). */
@Serializable
data class PackSource(
    val id: String,
    val name: String,
    val lang: String = "en",
    val baseUrl: String,
    val config: DeclarativeConfig
)

object ProviderPackCodec {

    const val FORMAT_TAG = "xreader-provider-pack"
    const val FORMAT_VERSION = 1

    /** The namespace every pack-managed source id lives in. */
    const val SOURCE_ID_PREFIX = "pack-"

    private val json = Json { ignoreUnknownKeys = true }
    private val packIdPattern = Regex("^[a-z0-9][a-z0-9-]*$")
    private val sourceIdPattern = Regex("^pack-[a-z0-9][a-z0-9-]*$")

    /**
     * Parse + validate a pack document. ANY problem — bad
     * JSON, wrong format/version shape, one bad config among
     * good ones, a duplicate or out-of-namespace id, metadata
     * that disagrees with its config — rejects the WHOLE pack
     * (an invalid pack changes nothing, by construction: this
     * runs before any apply step exists).
     */
    fun parse(raw: String): AppResult<ProviderPack> {
        val pack = runCatching {
            json.decodeFromString(ProviderPack.serializer(), raw)
        }.getOrElse {
            return AppResult.Err(
                AppError.ParseFailed(
                    "provider pack", "that pack file couldn't be read"
                )
            )
        }
        fun reject(detail: String): AppResult<ProviderPack> =
            AppResult.Err(AppError.ParseFailed("provider pack", detail))

        // Shape gate for the two version fields: kotlinx's JSON
        // decoder coerces a QUOTED number ("1") into an Int field
        // without complaint, so the decode above cannot tell a
        // well-shaped pack from a misshapen one. The published
        // format is strict — both versions are bare integer
        // tokens — so inspect the raw elements before trusting
        // the decoded values. (Gate-found at v1.4.0: the codec
        // accepted "version":"1".)
        val root = runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrNull()
        fun versionShapeOk(field: String): Boolean {
            val prim = root?.get(field) as? JsonPrimitive
                ?: return false
            return !prim.isString &&
                prim.content.toLongOrNull() != null
        }
        if (!versionShapeOk("formatVersion") ||
            !versionShapeOk("version")
        ) {
            return reject("that pack's version is misshapen")
        }

        if (pack.format != FORMAT_TAG) {
            return reject("that file isn't an Xreader provider pack")
        }
        if (pack.formatVersion != FORMAT_VERSION) {
            return reject("that pack needs a newer Xreader")
        }
        if (!packIdPattern.matches(pack.id)) {
            return reject("that pack's id is misshapen")
        }
        if (pack.version < 1) {
            return reject("that pack's version is misshapen")
        }
        val dateOk = runCatching {
            LocalDate.parse(pack.date, DateTimeFormatter.ISO_LOCAL_DATE)
        }.isSuccess
        if (!dateOk) return reject("that pack's date is misshapen")

        val seen = mutableSetOf<String>()
        for (source in pack.sources) {
            if (!sourceIdPattern.matches(source.id)) {
                return reject(
                    "a source in that pack sits outside the pack namespace"
                )
            }
            if (!seen.add(source.id)) {
                return reject("that pack lists one source twice")
            }
            if (source.name.isBlank() || source.lang.isBlank()) {
                return reject("a source in that pack is missing its name")
            }
            val config = source.config
            if (config.id != source.id) {
                return reject("a source in that pack disagrees with its rules")
            }
            val metaHost = source.baseUrl.normaliseHost()
            val configHost = config.baseUrl.normaliseHost()
            if (metaHost.isBlank() || metaHost != configHost) {
                return reject("a source in that pack points two ways at once")
            }
            // Beyond parsing: the selectors a source cannot
            // work without must actually be present.
            if (config.details.chapters.list.isBlank() ||
                config.details.chapters.name.isBlank() ||
                config.details.chapters.link.isBlank() ||
                config.chapter.content.isBlank()
            ) {
                return reject("a source in that pack is missing its reading rules")
            }
        }
        return AppResult.Ok(pack)
    }

    fun encode(pack: ProviderPack): String =
        json.encodeToString(ProviderPack.serializer(), pack)
}

/** The persisted state of one pack channel: the active pack
 * document and the previous one, kept WHOLE (raw JSON) so a
 * rollback restores the previous pack exactly, plus the
 * check bookkeeping the throttle runs on. */
@Serializable
data class StoredPackState(
    val packId: String,
    val activeRaw: String? = null,
    val activeVersion: Int = 0,
    val activeDate: String = "",
    val activeSourceIds: List<String> = emptyList(),
    val previousRaw: String? = null,
    val previousVersion: Int = 0,
    val lastCheckAtMs: Long = 0L,
    val lastOutcome: String = ""
)

/** The store seam pack state persists through — file-backed
 * in the app ([FileProviderPackStateStore] over
 * [ExtensionStore]), fakeable in tests. */
interface ProviderPackStateStore {
    fun load(packId: String): StoredPackState
    fun save(state: StoredPackState)
    fun knownPackIds(): List<String>
}

class FileProviderPackStateStore(
    private val store: ExtensionStore
) : ProviderPackStateStore {

    private val json = Json { ignoreUnknownKeys = true }

    override fun load(packId: String): StoredPackState {
        val raw = store.readText(store.packStateFile(packId))
            ?: return StoredPackState(packId = packId)
        return runCatching {
            json.decodeFromString(StoredPackState.serializer(), raw)
        }.getOrNull()?.takeIf { it.packId == packId }
            ?: StoredPackState(packId = packId)
    }

    override fun save(state: StoredPackState) {
        store.writeText(
            store.packStateFile(state.packId),
            json.encodeToString(StoredPackState.serializer(), state)
        )
    }

    override fun knownPackIds(): List<String> = store.packIdsOnDisk()
}

/** What one apply did, in source ids. */
data class PackApplyReport(
    val packId: String,
    val version: Int,
    val date: String,
    val added: List<String>,
    val updated: List<String>,
    val removed: List<String>
)

/** The pack channel's user-facing state (the pack row). */
data class PackInfo(
    val packId: String,
    val version: Int,
    val date: String,
    val sourceIds: List<String>,
    val canRollback: Boolean,
    val previousVersion: Int
)

sealed interface PackCheckOutcome {
    /** The automatic check ran too recently — nothing fetched. */
    data object SkippedThrottled : PackCheckOutcome
    data class UpToDate(val version: Int) : PackCheckOutcome
    data class Updated(val report: PackApplyReport) : PackCheckOutcome
    /** Silent by design at call sites: whatever pack state the
     * app already has keeps working. */
    data class Failed(val error: AppError) : PackCheckOutcome
}

/**
 * Applies, updates and rolls back provider packs. Mirrors
 * [RepoManager.installBundledDefaults]' materialisation
 * exactly — same row shape, same definition files, same
 * fingerprint bookkeeping — under the synthetic pack repo.
 */
class ProviderPackManager(
    private val repoDao: app.xreader.data.db.ExtensionRepoDao,
    private val extensionDao: app.xreader.data.db.InstalledExtensionDao,
    private val store: ExtensionStore,
    private val transport: HttpTransport,
    private val service: ExtensionService,
    private val stateStore: ProviderPackStateStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    /** The pack channel's live state, for the pack row. */
    fun info(): PackInfo? {
        val state = stateStore.load(PACK_ID)
        if (state.activeRaw == null) return null
        return PackInfo(
            packId = state.packId,
            version = state.activeVersion,
            date = state.activeDate,
            sourceIds = state.activeSourceIds,
            canRollback = state.previousRaw != null,
            previousVersion = state.previousVersion
        )
    }

    /** The plain-words badge a pack-managed source carries:
     * "Updated by pack · <date>". Null for any source the
     * active pack doesn't manage. */
    fun badgeFor(sourceId: String): String? {
        if (!sourceId.startsWith(ProviderPackCodec.SOURCE_ID_PREFIX)) {
            return null
        }
        val state = stateStore.load(PACK_ID)
        if (state.activeRaw == null) return null
        if (sourceId !in state.activeSourceIds) return null
        return "Updated by pack · ${state.activeDate}"
    }

    /**
     * The pack check. [manual] = the user tapped "Check for
     * pack updates" (never throttled); the automatic check is
     * throttled to once per [AUTO_CHECK_INTERVAL_MS]. Every
     * failure — network, HTTP, an invalid document, a version
     * that isn't newer — leaves the applied pack untouched.
     */
    suspend fun checkForUpdates(manual: Boolean): PackCheckOutcome {
        val state = stateStore.load(PACK_ID)
        val now = nowMs()
        if (!manual &&
            state.lastCheckAtMs > 0 &&
            now - state.lastCheckAtMs < AUTO_CHECK_INTERVAL_MS
        ) {
            return PackCheckOutcome.SkippedThrottled
        }
        val raw = when (val fetched = fetchRaw(PACK_URL)) {
            is AppResult.Err -> {
                recordCheck(state, now, "failed")
                return PackCheckOutcome.Failed(fetched.error)
            }
            is AppResult.Ok -> fetched.value
        }
        val pack = when (val parsed = ProviderPackCodec.parse(raw)) {
            is AppResult.Err -> {
                recordCheck(state, now, "failed")
                return PackCheckOutcome.Failed(parsed.error)
            }
            is AppResult.Ok -> parsed.value
        }
        if (pack.id != PACK_ID) {
            recordCheck(state, now, "failed")
            return PackCheckOutcome.Failed(
                AppError.ParseFailed(
                    "provider pack", "that pack belongs to another channel"
                )
            )
        }
        if (pack.version <= state.activeVersion) {
            recordCheck(state, now, "up-to-date")
            return PackCheckOutcome.UpToDate(state.activeVersion)
        }
        return when (
            val applied = applyInternal(pack, raw, state.activeRaw)
        ) {
            is AppResult.Err -> {
                recordCheck(state, now, "failed")
                PackCheckOutcome.Failed(applied.error)
            }
            is AppResult.Ok -> {
                // applyInternal saved its own state; re-stamp
                // the check bookkeeping on top of it.
                val fresh = stateStore.load(PACK_ID)
                stateStore.save(
                    fresh.copy(lastCheckAtMs = now, lastOutcome = "updated")
                )
                PackCheckOutcome.Updated(applied.value)
            }
        }
    }

    /**
     * Apply a validated pack document directly (the manual /
     * test entry point; the check path calls the same code).
     * The pack being applied becomes active; the pack that
     * was active becomes the stored previous version.
     */
    suspend fun applyPack(pack: ProviderPack, rawJson: String): AppResult<PackApplyReport> {
        val state = stateStore.load(pack.id)
        return applyInternal(pack, rawJson, state.activeRaw)
    }

    /**
     * One-tap rollback: the stored previous pack becomes
     * active again — its sources re-materialised exactly,
     * sources the newer pack added removed, sources it had
     * dropped restored. The pack rolled away from becomes
     * the new previous (rollback is itself reversible).
     */
    suspend fun rollback(): AppResult<PackApplyReport> {
        val state = stateStore.load(PACK_ID)
        val previousRaw = state.previousRaw ?: return AppResult.Err(
            AppError.ParseFailed(
                "provider pack", "there's no earlier pack to go back to"
            )
        )
        val previous = when (val parsed = ProviderPackCodec.parse(previousRaw)) {
            is AppResult.Err -> return parsed
            is AppResult.Ok -> parsed.value
        }
        return applyInternal(previous, previousRaw, state.activeRaw)
    }

    private fun recordCheck(state: StoredPackState, atMs: Long, outcome: String) {
        stateStore.save(state.copy(lastCheckAtMs = atMs, lastOutcome = outcome))
    }

    private suspend fun applyInternal(
        pack: ProviderPack,
        rawJson: String,
        previousRaw: String?
    ): AppResult<PackApplyReport> {
        // ── Pre-flight: EVERY refusal happens before a single
        // write, so a rejected pack changes nothing. ─────────
        val existingRepo = packRepoOrNull()
        for (source in pack.sources) {
            val row = extensionDao.getByPackageId(packageIdFor(source.id))
            if (row != null &&
                (existingRepo == null || row.repoId != existingRepo.id)
            ) {
                return AppResult.Err(
                    AppError.ParseFailed(
                        "provider pack",
                        "one of that pack's sources is already yours another way"
                    )
                )
            }
        }
        for (otherId in stateStore.knownPackIds()) {
            if (otherId == pack.id) continue
            val other = stateStore.load(otherId)
            val clash = pack.sources.firstOrNull {
                it.id in other.activeSourceIds
            }
            if (clash != null) {
                return AppResult.Err(
                    AppError.ParseFailed(
                        "provider pack",
                        "another pack already provides ${clash.name}"
                    )
                )
            }
        }

        // ── Materialise, exactly like the bundled defaults. ─
        val repo = existingRepo ?: createPackRepo()
        val state = stateStore.load(pack.id)
        val now = nowMs()
        val added = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val removed = mutableListOf<String>()

        for (source in pack.sources) {
            val config = source.config
            MirrorHosts.register(config.baseUrl, config.backupHosts)
            val packageId = packageIdFor(config.id)
            val fingerprint = DeclarativeFingerprints.of(listOf(config))
            store.writeText(
                store.definitionFile(packageId),
                DeclarativeFingerprints.encode(listOf(config))
            )
            val permissions = ExtensionPermissions(
                domains = listOf(config.baseUrl.normaliseHost()) +
                    config.extraDomains
            )
            val existing = extensionDao.getByPackageId(packageId)
            if (existing != null && existing.sha256 == fingerprint) {
                // Already exactly this pack's rules — the row
                // is current; counting it as updated would be
                // a lie in the report.
            } else if (existing == null) {
                extensionDao.insert(
                    InstalledExtension(
                        repoId = repo.id,
                        displayName = config.name,
                        packageId = packageId,
                        versionName = "Pack v${pack.version}",
                        versionCode = pack.version.toLong(),
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
                )
                added += config.id
            } else {
                extensionDao.update(
                    existing.copy(
                        displayName = config.name,
                        versionName = "Pack v${pack.version}",
                        versionCode = existing.versionCode + 1,
                        sha256 = fingerprint,
                        sourceIds = config.id,
                        languages = config.lang,
                        permissionsJson = permissions.encode(),
                        updatedAt = now
                    )
                )
                service.invalidate(existing.id)
                updated += config.id
            }
        }

        // Sources the previous active pack shipped that this
        // one doesn't: their rows and rules go away. Only
        // rows under the pack repo with pack package ids are
        // ever touched.
        val newIds = pack.sources.map { it.id }.toSet()
        for (oldId in state.activeSourceIds) {
            if (oldId in newIds) continue
            val packageId = packageIdFor(oldId)
            val row = extensionDao.getByPackageId(packageId) ?: continue
            if (row.repoId != repo.id) continue
            extensionDao.delete(row)
            store.deletePackage(packageId)
            service.invalidate(row.id)
            removed += oldId
        }

        val previousVersion = previousRaw?.let { raw ->
            when (val parsed = ProviderPackCodec.parse(raw)) {
                is AppResult.Ok -> parsed.value.version
                is AppResult.Err -> 0
            }
        } ?: 0
        stateStore.save(
            StoredPackState(
                packId = pack.id,
                activeRaw = rawJson,
                activeVersion = pack.version,
                activeDate = pack.date,
                activeSourceIds = pack.sources.map { it.id },
                previousRaw = previousRaw,
                previousVersion = previousVersion,
                lastCheckAtMs = state.lastCheckAtMs,
                lastOutcome = "applied"
            )
        )
        return AppResult.Ok(
            PackApplyReport(
                packId = pack.id,
                version = pack.version,
                date = pack.date,
                added = added,
                updated = updated,
                removed = removed
            )
        )
    }

    private suspend fun packRepoOrNull(): ExtensionRepo? =
        repoDao.observeAll().first()
            .firstOrNull { it.indexUrl == PACK_REPO_URL }

    private suspend fun createPackRepo(): ExtensionRepo {
        val repo = ExtensionRepo(
            name = "Provider packs",
            indexUrl = PACK_REPO_URL,
            indexSha256 = null,
            lastFetchedAt = null,
            enabled = true
        )
        return repo.copy(id = repoDao.insert(repo))
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
                AppError.ProviderDown("provider pack", detail = "empty response")
            )
        if (bytes.size > MAX_PACK_BYTES) {
            return AppResult.Err(
                AppError.ProviderDown(
                    "provider pack", detail = "the pack was too large"
                )
            )
        }
        return AppResult.Ok(bytes.toString(Charsets.UTF_8))
    }

    companion object {
        /** The one pack channel this build ships. */
        const val PACK_ID = "novels"

        /** Where the pack document lives — the repo must host
         * a file at exactly this path (packs/novels.json). */
        const val PACK_URL =
            "https://raw.githubusercontent.com/Hyperjoker2511/Xreader" +
                "/main/packs/novels.json"

        /** The synthetic repo pack sources live under. */
        const val PACK_REPO_URL = "xreader://provider-packs"

        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val MAX_PACK_BYTES = 4 * 1024 * 1024

        /** A pack source's extension package id — the `packs.`
         * prefix is the marker that keeps pack rows distinct
         * from bundled (`bundled.`), repo and link rows. */
        fun packageIdFor(sourceId: String): String = "packs.$sourceId"
    }
}
