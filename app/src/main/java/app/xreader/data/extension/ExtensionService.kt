package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.TrustState
import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceContext
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** One loaded source plus the row it came from. */
data class SourceHandle(
    val extension: InstalledExtension,
    val source: Source
)

/** Stored in definition.json for js/dex packages (declarative packages
 * store their rule list there instead). */
@Serializable
data class CodeDefinition(
    val entryClass: String? = null,
    val sourcesMeta: List<IndexSourceMeta> = emptyList()
)

/**
 * The extension runtime (PLAN §5): turns InstalledExtension rows into
 * live [Source] objects, and owns the safety rails around them —
 *
 * - nothing loads unless [TrustEngine.mayRun] passes;
 * - js/dex artifacts are hash-checked against the pinned fingerprint
 *   before EVERY cold load — a mismatch freezes the row
 *   (CHANGED_REQUIRES_RETRUST) and nothing runs;
 * - three consecutive failures from one extension disable it
 *   (§9.1 defect containment), with a plain-words reason surfaced;
 * - loaded sources are cached; install/update/trust changes call
 *   [invalidate] so stale code never lingers.
 */
class ExtensionService(
    private val extensionDao: InstalledExtensionDao,
    private val store: ExtensionStore,
    private val contextFactory: HostContextFactory,
    private val dexLoader: DexLoader,
    private val jsRuntimeFactory: () -> JsRuntime
) {

    private val cache = ConcurrentHashMap<Long, List<SourceHandle>>()
    private val failureCounts = ConcurrentHashMap<Long, Int>()
    private val jsRuntimes = ConcurrentHashMap<Long, JsRuntime>()

    fun invalidate(extensionId: Long) {
        cache.remove(extensionId)
        jsRuntimes.remove(extensionId)?.let { runCatching { it.close() } }
    }

    fun invalidateAll() {
        cache.clear()
        jsRuntimes.values.forEach { runCatching { it.close() } }
        jsRuntimes.clear()
    }

    /** All runnable sources across all extensions. */
    suspend fun allSources(): List<SourceHandle> {
        val rows = extensionDao.observeAllSnapshot()
        return rows.flatMap { row ->
            loadExtension(row).fold({ it }, { emptyList() })
        }
    }

    suspend fun sourcesOf(extensionId: Long): AppResult<List<SourceHandle>> {
        val row = extensionDao.getById(extensionId)
            ?: return AppResult.Err(
                AppError.ExtensionFailed("source", "the source is no longer installed")
            )
        return loadExtension(row)
    }

    suspend fun source(extensionId: Long, sourceId: String): AppResult<SourceHandle> {
        val handles = when (val loaded = sourcesOf(extensionId)) {
            is AppResult.Err -> return loaded
            is AppResult.Ok -> loaded.value
        }
        return handles.firstOrNull { it.source.id == sourceId }?.let { AppResult.Ok(it) }
            ?: AppResult.Err(
                AppError.ExtensionFailed(
                    handles.firstOrNull()?.extension?.displayName ?: "source",
                    "the source wasn't found in its package"
                )
            )
    }

    private suspend fun loadExtension(
        row: InstalledExtension
    ): AppResult<List<SourceHandle>> {
        cache[row.id]?.let { return AppResult.Ok(it) }
        if (!TrustEngine.mayRun(row)) {
            return AppResult.Err(refusalError(row))
        }
        val permissions = ExtensionPermissions.parse(row.permissionsJson)
        val context: SourceContext = contextFactory.contextFor(
            extensionRowId = row.id,
            packageId = row.packageId,
            label = row.displayName,
            permissions = { permissions }
        )
        val sources: List<Source> = try {
            when (row.kind) {
                app.xreader.data.db.ExtensionKind.DECLARATIVE -> {
                    val raw = store.readText(store.definitionFile(row.packageId))
                        ?: return AppResult.Err(
                            AppError.ExtensionFailed(row.displayName, "its rules file is missing")
                        )
                    DeclarativeFingerprints.decode(raw).map { config ->
                        DeclarativeSource(config, context)
                    }
                }
                app.xreader.data.db.ExtensionKind.JS -> loadJs(row, context)
                app.xreader.data.db.ExtensionKind.DEX -> loadDex(row, context)
            }
        } catch (e: Exception) {
            return AppResult.Err(
                AppError.ExtensionFailed(
                    row.displayName,
                    "it failed to start (${e.javaClass.simpleName})"
                )
            )
        }
        val handles = sources.map { SourceHandle(row, it) }
        if (handles.isNotEmpty()) cache[row.id] = handles
        return AppResult.Ok(handles)
    }

    private suspend fun loadJs(row: InstalledExtension, context: SourceContext): List<Source> {
        val artifact = store.artifactFile(row.packageId)
        val onDisk = store.hashFile(artifact)
        if (!TrustEngine.artifactIntact(onDisk, row.sha256)) {
            freeze(row)
            throw SourceException("the script file changed since it was trusted")
        }
        val meta = readCodeDefinition(row).sourcesMeta.firstOrNull()
            ?: throw SourceException("the package doesn't declare its source")
        val script = artifact.readText(Charsets.UTF_8)
        val startedAt = System.nanoTime()
        val bridge = JsBridge(
            http = context.http,
            html = context.html,
            store = context.store,
            log = context.log,
            elapsedMs = { (System.nanoTime() - startedAt) / 1_000_000 }
        )
        val runtime = jsRuntimeFactory()
        runtime.installBridge { method, argsJson -> bridge.call(method, argsJson) }
        runtime.loadBundle(script)
        jsRuntimes[row.id] = runtime
        return listOf(
            JsSource(
                id = meta.id, name = meta.name, lang = meta.lang,
                baseUrl = meta.baseUrl, runtime = runtime
            )
        )
    }

    private suspend fun loadDex(row: InstalledExtension, context: SourceContext): List<Source> {
        val artifact = store.artifactFile(row.packageId)
        val onDisk = store.hashFile(artifact)
        val definition = readCodeDefinition(row)
        val refusal = DexGate.refusalReason(row, definition.entryClass, onDisk)
        if (refusal != null) {
            if (!TrustEngine.artifactIntact(onDisk, row.sha256)) freeze(row)
            throw SourceException(refusal)
        }
        return dexLoader.load(
            installed = row,
            artifact = artifact,
            optDir = store.dexOptDir(row.packageId),
            entryClass = definition.entryClass!!,
            context = context
        )
    }

    private fun readCodeDefinition(row: InstalledExtension): CodeDefinition {
        val raw = store.readText(store.definitionFile(row.packageId))
            ?: return CodeDefinition()
        return runCatching {
            Json { ignoreUnknownKeys = true }
                .decodeFromString(CodeDefinition.serializer(), raw)
        }.getOrDefault(CodeDefinition())
    }

    /** Freeze a row whose bytes no longer match the pin (§9.1). */
    private suspend fun freeze(row: InstalledExtension) {
        if (row.trustState != TrustState.CHANGED_REQUIRES_RETRUST) {
            extensionDao.update(
                row.copy(trustState = TrustState.CHANGED_REQUIRES_RETRUST)
            )
        }
        invalidate(row.id)
    }

    private fun refusalError(row: InstalledExtension): AppError = when {
        row.trustState == TrustState.CHANGED_REQUIRES_RETRUST ->
            AppError.ExtensionFailed(
                row.displayName,
                "it changed since you trusted it — re-trust it from its page to use it again"
            )
        !row.enabled ->
            AppError.ExtensionFailed(row.displayName, "the source is switched off")
        row.kind == app.xreader.data.db.ExtensionKind.DEX ->
            AppError.ExtensionFailed(
                row.displayName,
                "app-code sources need your opt-in on their page before they can run"
            )
        else -> AppError.ExtensionFailed(row.displayName, "the source can't run right now")
    }

    // ── v1.0.2 (R24): account bookshelf ─────────────────────────────

    /** The declarative rules behind a handle, when rule-based. */
    fun declarativeConfigOf(handle: SourceHandle): DeclarativeConfig? =
        (handle.source as? DeclarativeSource)?.config

    /** One page of the account bookshelf (login session applies). */
    suspend fun bookshelf(
        handle: SourceHandle,
        page: Int
    ): AppResult<app.xreader.extensionapi.SourceBookPage> {
        val source = handle.source as? DeclarativeSource
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    handle.extension.displayName,
                    "this kind of source can't show its account bookshelf"
                )
            )
        return guarded(handle) { source.bookshelf(page) }
    }

    // ── failure containment (§9.1) ──────────────────────────────────

    /**
     * Run a source call with defect containment: successes reset the
     * counter; the third consecutive failure disables the extension.
     */
    suspend fun <T> guarded(
        handle: SourceHandle,
        block: (Source) -> T
    ): AppResult<T> {
        return try {
            val value = block(handle.source)
            failureCounts.remove(handle.extension.id)
            AppResult.Ok(value)
        } catch (e: Exception) {
            val count = failureCounts.merge(handle.extension.id, 1, Int::plus) ?: 1
            if (count >= FAILURE_LIMIT) {
                val row = extensionDao.getById(handle.extension.id) ?: handle.extension
                extensionDao.update(row.copy(enabled = false))
                invalidate(handle.extension.id)
                failureCounts.remove(handle.extension.id)
                AppResult.Err(
                    AppError.ExtensionFailed(
                        handle.extension.displayName,
                        "it failed $FAILURE_LIMIT times in a row, so Xreader switched it off. " +
                            "You can turn it back on from Sources."
                    )
                )
            } else {
                AppResult.Err(
                    AppError.ExtensionFailed(
                        handle.extension.displayName,
                        e.message?.take(300) ?: "it failed (${e.javaClass.simpleName})"
                    )
                )
            }
        }
    }

    companion object {
        const val FAILURE_LIMIT = 3
    }
}

/** observeAll() as a one-shot snapshot for the service layer. */
private suspend fun InstalledExtensionDao.observeAllSnapshot(): List<InstalledExtension> =
    observeAll().first()
