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
 * - failures NEVER disable an extension (v1.3.2, V120-CHECK
 *   item 84 — the §9.1 auto-disable breaker is retired: a
 *   source is off only because its reader turned it off.
 *   Failures surface as the health state on the source's row
 *   and page, in plain words, and the next try simply tries
 *   again);
 * - loaded sources are cached; install/update/trust changes call
 *   [invalidate] so stale code never lingers.
 */
class ExtensionService(
    private val extensionDao: InstalledExtensionDao,
    private val store: ExtensionStore,
    private val contextFactory: HostContextFactory,
    private val dexLoader: DexLoader,
    /** v1.3.4 (T1): the session store read-token
     * coordinators persist through (optional so test
     * constructions stay as they were; production wires the
     * app store). Declared BEFORE [jsRuntimeFactory] on
     * purpose: tests pass the runtime factory as a trailing
     * lambda, which binds to the LAST parameter. */
    private val sessionStore: SessionStore? = null,
    private val jsRuntimeFactory: () -> JsRuntime
) {

    /** The read-token store for declarative sources (see the
     * DeclarativeSource construction in [source]). */
    private fun sessionStoreOrNull(): SessionStore? = sessionStore

    private val cache = ConcurrentHashMap<Long, List<SourceHandle>>()
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
        // v1.0.6 (W1.3): a declarative row's rules are decoded once,
        // up front — the context's render marker reads them. Link
        // packages hold exactly one config, so the marker is exact
        // wherever it can be written; a multi-source rules package
        // would render as a package (its configs never carry the
        // marker — only the probe/Repair write it, into link rows).
        val rawDefinition: String? =
            if (row.kind == app.xreader.data.db.ExtensionKind.DECLARATIVE ||
                row.kind == app.xreader.data.db.ExtensionKind.LIVE
            ) {
                store.readText(store.definitionFile(row.packageId))
            } else {
                null
            }
        val declarativeConfigs: List<DeclarativeConfig> =
            rawDefinition?.let { DeclarativeFingerprints.decode(it) }
                ?: emptyList()
        val context: SourceContext = contextFactory.contextFor(
            extensionRowId = row.id,
            packageId = row.packageId,
            label = row.displayName,
            permissions = { permissions },
            // v1.0.9: a Live Source always may escalate to the
            // hidden browser — app-shell sites are its home turf.
            // v1.2.1 (Defect 4): MAY escalate — not MUST render.
            // The kind check used to hard-wire every Live row to
            // render-first, so a server-rendered site installed
            // as a Live Source paid a full browser render (plus
            // settle waits) for EVERY page, when a plain fetch
            // parses it instantly — the exact gap to the
            // QuickNovel model the user benchmarked. The marker
            // now means only what it means for declarative rows
            // (a config that PROVED it needs rendering); a Live
            // row starts on the plain road, escalates per fetch
            // when a shell is actually served, and the fetch
            // layer's road memory (ShellHostMemory) makes later
            // fetches to a proven shell host render first.
            needsRendering = {
                declarativeConfigs.any { it.needsRendering }
            },
            // v1.0.8.2: the same up-front decode supplies the
            // wait-for-content hint, so a saved app-shell
            // source's own fetches wait for their content just
            // like the drive that proved the source did.
            waitForSelector = {
                declarativeConfigs.firstOrNull {
                    it.renderWaitSelector != null
                }?.renderWaitSelector
            },
            // v1.3.4 (T1): the row's declared header bindings
            // ride its client (exact-host replay only).
            credentialBindings = {
                declarativeConfigs.mapNotNull {
                    it.credentials?.headerBinding
                }
            },
            // v1.3.4 (T6): the row's declared backup hosts —
            // the client's failover list (identity registers
            // below, where the configs are in hand).
            backupHosts = {
                declarativeConfigs.flatMap { it.backupHosts }
            }
        )
        // v1.3.4 (T6): mirror identity — a chapter URL on a
        // backup host IS the primary host's chapter.
        for (config in declarativeConfigs) {
            MirrorHosts.register(config.baseUrl, config.backupHosts)
        }
        val sources: List<Source> = try {
            when (row.kind) {
                app.xreader.data.db.ExtensionKind.DECLARATIVE -> {
                    if (rawDefinition == null) {
                        return AppResult.Err(
                            AppError.ExtensionFailed(row.displayName, "its rules file is missing")
                        )
                    }
                    declarativeConfigs.map { config ->
                        DeclarativeSource(
                            config, context,
                            readTokenStore = sessionStoreOrNull()
                        )
                    }
                }
                app.xreader.data.db.ExtensionKind.JS -> loadJs(row, context)
                app.xreader.data.db.ExtensionKind.DEX -> loadDex(row, context)
                app.xreader.data.db.ExtensionKind.LIVE -> {
                    val spec = rawDefinition?.let { LiveSourceSpec.decode(it) }
                        ?: throw SourceException(
                            "its live-source file is missing or unreadable"
                        )
                    listOf(LiveSource(spec, context))
                }
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

    /**
     * v1.0.6.2: raw bytes for one URL in [handle]'s own context —
     * chapter images. The context an extension lives in only
     * speaks text (the API is string-shaped by design), so this
     * goes through a client the factory builds for the extension
     * itself: same transport, same session key (the saved login
     * session attaches exactly as for page fetches), same parsed
     * whitelist, same rate limiter as its source context. A plugin
     * therefore cannot make the host fetch bytes from a host it
     * isn't allowed to open — image URLs come from plugin-shaped
     * HTML and are held to the same gate as everything else. Any
     * failure, whitelist refusal included, is an Err; callers
     * treat it as "skip this one". Image fetches deliberately do
     * NOT run through [guarded]: a dead image host must not count
     * toward a source's failure health.
     */
    suspend fun fetchBytes(handle: SourceHandle, url: String): AppResult<ByteArray> {
        val permissions = ExtensionPermissions.parse(handle.extension.permissionsJson)
        val client = contextFactory.sourceClientFor(
            extensionRowId = handle.extension.id,
            label = handle.extension.displayName,
            permissions = permissions
        )
        return client.getBytes(url)
    }

    // ── v1.0.2 (R24): account bookshelf ─────────────────────────────

    /** The declarative rules behind a handle, when rule-based. */
    fun declarativeConfigOf(handle: SourceHandle): DeclarativeConfig? =
        (handle.source as? DeclarativeSource)?.config

    /**
     * v1.0.6 (WT): the chapter ids the book page marks as locked IN
     * THE ROW MARKUP (a lock badge on the row), as opposed to locks
     * visible in chapter names. Only declarative sources can know;
     * anything else — and any failure — answers empty, so callers
     * treat the result as an extra filter, never a requirement.
     */
    suspend fun lockedChapterIds(
        handle: SourceHandle,
        book: app.xreader.extensionapi.SourceBook
    ): Set<String> {
        val source = handle.source as? DeclarativeSource ?: return emptySet()
        return guarded(handle) { source.lockedChapterIds(book) }
            .getOrNull() ?: emptySet()
    }

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

    // ── failure containment (§9.1, as amended by item 84) ─────────

    /**
     * Run a source call with defect containment: the failure
     * becomes a plain-words error for the caller, and NOTHING
     * else happens — v1.3.2 (V120-CHECK item 84) retired the
     * failure circuit-breaker that used to switch a source
     * off after three consecutive failures. On a real device
     * that rule turned a run of transient render timeouts
     * (BOTI's Turnstile stalls) into EVERY source silently
     * off by morning. The rule now: a source is off only
     * because its reader turned it off. Failures earn the
     * health badge on the source's row; the next try tries
     * again. (The audit that closed this path: this was the
     * ONLY non-user write of `enabled = false` for a source
     * in the app — install probes save nothing on failure,
     * health checks write health records only, and
     * [RepoManager.setEnabled] is called solely from the
     * source page's own switch.)
     */
    suspend fun <T> guarded(
        handle: SourceHandle,
        block: (Source) -> T
    ): AppResult<T> {
        return try {
            AppResult.Ok(block(handle.source))
        } catch (e: Exception) {
            AppResult.Err(
                AppError.ExtensionFailed(
                    handle.extension.displayName,
                    e.message?.take(300) ?: "it failed (${e.javaClass.simpleName})"
                )
            )
        }
    }
}

/** observeAll() as a one-shot snapshot for the service layer. */
private suspend fun InstalledExtensionDao.observeAllSnapshot(): List<InstalledExtension> =
    observeAll().first()
