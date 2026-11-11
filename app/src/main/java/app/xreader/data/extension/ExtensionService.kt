package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.InstalledExtensionDao
import app.xreader.data.db.TrustState
import app.xreader.data.extension.player.BridgeCallContext
import app.xreader.data.extension.player.MihonSourceAdapter
import app.xreader.extensionapi.Source
import app.xreader.extensionapi.SourceContext
import app.xreader.extensionapi.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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
    /** Keiyoushi player (K4): the player's live sources for
     * one package, by namespaced id — wired to
     * KeiyoushiPlayerManager.liveSourcesOf in production;
     * empty in tests that don't exercise the player track.
     * Declared BEFORE [jsRuntimeFactory] for the same reason
     * as [sessionStore]: tests pass the runtime factory as a
     * trailing lambda, which binds to the LAST parameter. */
    private val keiyoushiLiveSources:
        (packageName: String) -> Map<String, eu.kanade.tachiyomi.source.Source> =
        { emptyMap() },
    /** Keiyoushi player (K4): a package's content rating
     * from the index's contentWarning (production reads the
     * player manager's cached catalogue). Null = the rating
     * couldn't be read — the KEIYOUSHI branch then fails
     * CLOSED to ADULT, so an unrated player source is hidden
     * by the content preference until its rating is known,
     * never shown by default. */
    private val keiyoushiRatingProvider:
        suspend (packageName: String) -> ContentRating? =
        { null },
    /** Keiyoushi player (K6): the player's kill switch
     * ("Keiyoushi manga sources" in Customise). Production
     * wires KeiyoushiPlayerManager.isPlayerEnabled; tests
     * that don't exercise the switch leave it on. When off,
     * a player row's lookup refuses with the player's own
     * turned-off sentence instead of the empty-package one —
     * a library book on a player source shows exactly why
     * its source is off and where to turn it back on. */
    private val keiyoushiPlayerEnabled: () -> Boolean = { true },
    /** v1.5.4 (WS-1 item 7b, closed at integration): the
     * cover-host sink for player packages — the adapter
     * reports the hosts of the covers a package's own
     * sources return, and production wires
     * KeiyoushiPlayerManager.noteCoverHosts, which folds
     * them into the package's derived permission domains.
     * Declared with the other player providers, before
     * [jsRuntimeFactory] (trailing-lambda rule). */
    private val keiyoushiCoverHostSink:
        (packageName: String, hosts: Set<String>) -> Unit =
        { _, _ -> },
    /** v1.5.7 (Finding 4, §6): the hosts the Keiyoushi
     * catalogue claims — the normalised homeUrl hosts of
     * the player manager's CACHED catalogue (production
     * wires KeiyoushiPlayerManager.claimedHosts). A
     * link-added DECLARATIVE config whose baseUrl host is
     * claimed is stamped MANGA at materialisation (see
     * [stampKeiyoushiClaimedKind]): Keiyoushi entries are
     * manga sources by construction, so a claimed host IS
     * a manga site whatever the learner filed the link
     * under. Empty (the default, and whenever the
     * catalogue was never fetched) = today's behaviour,
     * exactly — the same honest-limit shape as the rating
     * provider's null. */
    private val keiyoushiClaimedHosts: () -> Set<String> =
        { emptySet() },
    /** v1.3.4 (T1): the session store read-token
     * coordinators persist through (optional so test
     * constructions stay as they were; production wires the
     * app store). Declared BEFORE [jsRuntimeFactory] on
     * purpose: tests pass the runtime factory as a trailing
     * lambda, which binds to the LAST parameter. */
    private val sessionStore: SessionStore? = null,
    /** v1.5.10 (B5): the book store the stable-identity
     * resolver heals through — when a book's stored row id
     * is stale, [resolveBookSource] rewrites the book's
     * extensionId to the re-found row's id via this DAO.
     * Optional so existing test constructions stay as they
     * were; when absent, resolution still succeeds and the
     * heal is left to callers that hold a DAO (the chapter
     * resolver does exactly that). Declared BEFORE
     * [jsRuntimeFactory] for the trailing-lambda rule. */
    private val bookDao: app.xreader.data.db.BookDao? = null,
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

    suspend fun sourcesOf(extensionId: Long): AppResult<List<SourceHandle>> =
        sourcesOf(extensionId, sourceNameHint = null)

    /**
     * v1.5.10 (B5): [sourcesOf] with an honest name for the
     * row-miss error. The pre-fix miss branch hardcoded the
     * literal "source" as the source's NAME, so every stale
     * pointer rendered as 'The source "source" ran into a
     * problem'. A caller that knows the book's stored
     * sourceId passes it as [sourceNameHint]; book-aware
     * callers use [resolveBookSource] / [resolveBookSources],
     * which name the error from the book itself.
     */
    suspend fun sourcesOf(
        extensionId: Long,
        sourceNameHint: String?
    ): AppResult<List<SourceHandle>> {
        val row = extensionDao.getById(extensionId)
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    sourceNameHint?.takeIf { it.isNotBlank() }
                        ?: "extension #$extensionId",
                    "the source is no longer installed"
                )
            )
        return loadExtension(row)
    }

    /**
     * v1.5.10 (B5): resolve THE source a stored book belongs
     * to, by stable identity. Convicted design flaw (see
     * V1510-FINDING-ASURA-SOURCE): a book's stored
     * extensionId is the mirror row's AUTOGENERATED primary
     * key — any row re-creation (an earlier release's row
     * regeneration, an uninstall→reinstall cycle) orphans
     * every bound book while catalogue roads, which resolve
     * by the CURRENT row, keep working.
     *
     *  (i) Fast path unchanged: [source] on the stored row
     *      id + the book's decoded sourceId.
     *  (ii) On a ROW-ID MISS only (the row itself is gone —
     *      a present row's own verdicts, e.g. switched off,
     *      stand untouched): decode the book's sourceId from
     *      its stored remoteId ([SourceBookIds]) and scan
     *      installed rows whose declared sourceIds contain
     *      it — player rows match on the bare Mihon id,
     *      mirroring [source]'s existing fallback, and a
     *      namespaced stored id also matches its package.
     *  (iii) A candidate that resolves HEALS the book: its
     *      stored extensionId is rewritten to the found
     *      row's id (via the injected book DAO when present)
     *      and a DiagnosticLog line records old → new.
     *      A candidate that exists but refuses (disabled /
     *      needs repair) surfaces ITS state through the
     *      existing load path — switched off is not "no
     *      longer installed".
     *  (iv) No installed row declares the book's sourceId:
     *      the source is genuinely gone — the error NAMES it
     *      by the book's stored sourceId, never the bare
     *      word "source".
     */
    suspend fun resolveBookSource(
        book: app.xreader.data.db.Book
    ): AppResult<SourceHandle> {
        val storedSourceId = SourceBookIds.decode(book.remoteId)?.first
        val extensionId = book.extensionId
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    storedSourceId ?: "extension",
                    "this book didn't come from a source"
                )
            )
        if (storedSourceId == null) {
            return AppResult.Err(
                AppError.ExtensionFailed(
                    "extension #$extensionId",
                    "the book's source link is damaged"
                )
            )
        }
        val fast = source(extensionId, storedSourceId)
        if (fast is AppResult.Ok) return fast
        // Only a ROW-ID MISS earns the stable-identity scan:
        // a present row already gave its verdict above.
        if (extensionDao.getById(extensionId) != null) return fast
        val rows = extensionDao.observeAll().first()
        val candidates = rows.filter { row ->
            rowDeclaresSource(row, storedSourceId)
        }
        var firstRefusal: AppResult.Err? = null
        for (candidate in candidates) {
            when (val resolved = source(candidate.id, storedSourceId)) {
                is AppResult.Ok -> {
                    healBookExtensionId(book, resolved.value.extension.id)
                    return resolved
                }
                is AppResult.Err -> {
                    if (firstRefusal == null) firstRefusal = resolved
                }
            }
        }
        // A declaring row exists but refused (e.g. switched
        // off): its own state is the honest answer.
        firstRefusal?.let { return it }
        return AppResult.Err(
            AppError.ExtensionFailed(
                storedSourceId,
                "the source is no longer installed"
            )
        )
    }

    /** v1.5.10 (B5): the [sourcesOf] twin of
     * [resolveBookSource] — every handle of the book's
     * source, healing the stored row id on a miss. */
    suspend fun resolveBookSources(
        book: app.xreader.data.db.Book
    ): AppResult<List<SourceHandle>> {
        val extensionId = book.extensionId
            ?: return AppResult.Err(
                AppError.ExtensionFailed(
                    SourceBookIds.decode(book.remoteId)?.first
                        ?: "extension",
                    "this book didn't come from a source"
                )
            )
        val fast = sourcesOf(extensionId)
        if (fast is AppResult.Ok) return fast
        if (extensionDao.getById(extensionId) != null) return fast
        val storedSourceId = SourceBookIds.decode(book.remoteId)?.first
            ?: return fast
        val rows = extensionDao.observeAll().first()
        val candidate = rows.firstOrNull { row ->
            rowDeclaresSource(row, storedSourceId)
        } ?: return AppResult.Err(
            AppError.ExtensionFailed(
                storedSourceId,
                "the source is no longer installed"
            )
        )
        val loaded = loadExtension(candidate)
        if (loaded is AppResult.Ok && loaded.value.isNotEmpty()) {
            healBookExtensionId(book, candidate.id)
        }
        return loaded
    }

    /** Does [row] declare [storedSourceId]? Declared ids are
     * compared verbatim, and — mirroring [source]'s player
     * fallback — a stored NAMESPACED id ("keiyoushi:<pkg>:
     * <bare>") also matches a row declaring the bare Mihon
     * id, and a row of the named package. */
    private fun rowDeclaresSource(
        row: InstalledExtension,
        storedSourceId: String
    ): Boolean {
        val declared = row.sourceIds.split(",")
            .map { it.trim() }.filter { it.isNotBlank() }
        if (storedSourceId in declared) return true
        val bare = storedSourceId.substringAfterLast(':')
        if (bare != storedSourceId && bare in declared) return true
        val namespacedPackage = storedSourceId
            .removePrefix("keiyoushi:")
            .let { rest ->
                if (rest == storedSourceId) null
                else rest.substringBeforeLast(':')
                    .takeIf { it.isNotBlank() }
            }
        return namespacedPackage != null &&
            namespacedPackage == row.packageId
    }

    /** v1.5.10 (B5): the heal — rewrite the book's stored
     * row id to the row its stable identity actually found,
     * and leave the DiagnosticLog line the census greps.
     * No-op when the pointer already agrees or no book DAO
     * was injected (callers holding a DAO heal themselves —
     * the chapter resolver does). */
    private suspend fun healBookExtensionId(
        book: app.xreader.data.db.Book,
        newExtensionId: Long
    ) {
        if (book.extensionId == newExtensionId) return
        DiagnosticLog.record(
            "source-resolve",
            "book ${book.id} healed: stored extensionId " +
                "${book.extensionId} pointed at a row that no " +
                "longer exists; its source was found again by " +
                "stable identity at row $newExtensionId",
            "sourceId=${SourceBookIds.decode(book.remoteId)?.first ?: "?"}"
        )
        runCatching {
            bookDao?.update(book.copy(extensionId = newExtensionId))
        }
    }

    suspend fun source(extensionId: Long, sourceId: String): AppResult<SourceHandle> {
        val handles = when (val loaded = sourcesOf(extensionId)) {
            is AppResult.Err -> return loaded
            is AppResult.Ok -> loaded.value
        }
        return handles.firstOrNull { it.source.id == sourceId }
            ?.let { AppResult.Ok(it) }
            // Keiyoushi player (K4): a player handle's facade
            // id is namespaced, but the mirroring row's
            // declared source ids are the bare Mihon ids (the
            // shape K3 wrote) — surfaces that look a source up
            // by the row's own id (the detail page's bookshelf
            // scan, health keys) resolve through this fallback.
            ?: handles.firstOrNull { handle ->
                (handle.source as? MihonSourceAdapter)
                    ?.mihonId?.toString() == sourceId
            }
            ?.let { AppResult.Ok(it) }
            ?: AppResult.Err(
                AppError.ExtensionFailed(
                    // v1.5.10 (B5): never the bare word "source"
                    // as a name — the row's display name, else
                    // the id that was asked for.
                    handles.firstOrNull()?.extension?.displayName
                        ?: sourceId,
                    "the source wasn't found in its package"
                )
            )
    }

    private suspend fun loadExtension(
        row: InstalledExtension
    ): AppResult<List<SourceHandle>> {
        // Keiyoushi player (K4): player rows are NOT cached
        // here — the manager's live registry is the authority
        // and adapters are cheap to build, so an install,
        // update or uninstall takes effect on the very next
        // lookup with no invalidation wiring. Every other
        // kind keeps the cache it has always had.
        if (row.kind != app.xreader.data.db.ExtensionKind.KEIYOUSHI) {
            cache[row.id]?.let { return AppResult.Ok(it) }
        }
        if (!TrustEngine.mayRun(row)) {
            return AppResult.Err(refusalError(row))
        }
        // Keiyoushi player (K6): the kill switch outranks an
        // otherwise-runnable player row. The wording is the
        // player's own — it must NOT borrow the row-level
        // "the source is switched off" sentence, whose book-
        // page card offers to re-enable the ROW (already on);
        // the fix here is the master switch, and the sentence
        // says where it lives.
        if (row.kind == app.xreader.data.db.ExtensionKind.KEIYOUSHI &&
            !keiyoushiPlayerEnabled()
        ) {
            return AppResult.Err(
                AppError.ExtensionFailed(
                    row.displayName,
                    "Keiyoushi manga sources are turned off — " +
                        "turn them on in Customise, under " +
                        "Sources & repairs"
                )
            )
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
            // v1.5.8 (D1/F2): the same up-front decode supplies
            // the declared render budget, threaded exactly like
            // the wait-for selector above.
            renderBudgetMs = {
                declarativeConfigs.firstOrNull {
                    it.renderBudgetMs != null
                }?.renderBudgetMs
            },
            // v1.3.4 (T1): the row's declared header bindings
            // ride its client (exact-host replay only).
            credentialBindings = {
                declarativeConfigs.mapNotNull {
                    it.credentials?.headerBinding
                }
            },
            // v1.5.2 (§10 item 1): the row's declared static
            // replay headers ride its client too (exact-host
            // replay only, like the bindings).
            staticHeaders = {
                declarativeConfigs.flatMap {
                    it.credentials?.staticHeaders ?: emptyList()
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
                    // v1.5.7 (Finding 4, §6): the claim stamp
                    // runs HERE — the one materialisation seam
                    // every declarative config passes on its
                    // way to becoming a source — so mediaKindOf,
                    // DeclarativeSource.chapterContent's own
                    // dispatch and every kind filter read the
                    // stamped kind and agree. Load-time only:
                    // the stored definition is never rewritten,
                    // so an existing link-added row (the owner's
                    // Asura) self-heals on its next load with no
                    // re-add and no migration.
                    val claimedHosts = keiyoushiClaimedHosts()
                    declarativeConfigs.map { rawConfig ->
                        val config = stampKeiyoushiClaimedKind(
                            rawConfig, claimedHosts
                        )
                        DeclarativeSource(
                            config, context,
                            readTokenStore = sessionStoreOrNull(),
                            // v1.5.5 (§2/§3): the rendered-session
                            // access — the render-backed endpoint
                            // fetch and the post-solve in-WebView
                            // mint ride it. Null when this host has
                            // no renderer (tests/probes); every
                            // road then keeps its plain path.
                            sessionAccess = contextFactory.renderedFetcher
                                ?.let { renderer ->
                                    val key = ExtensionHttpClient
                                        .sessionKeyFor(row.id)
                                    DeclarativeSource.RenderedSessionAccess(
                                        fetcher = renderer,
                                        sessionKey = key,
                                        permissions = permissions,
                                        hasRecentSolve = {
                                            SessionSolvedRegistry
                                                .hasRecentSolve(key)
                                        }
                                    )
                                }
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
                // Keiyoushi player (K4): the row's live Mihon
                // sources, adapted into the pipeline. A
                // package that isn't installed-and-loaded in
                // the player (Failed / Incompatible / still
                // starting) yields NO handles — its sources
                // simply don't appear anywhere, and its state
                // already shows on the Keiyoushi shelf.
                app.xreader.data.db.ExtensionKind.KEIYOUSHI ->
                    loadKeiyoushiSources(row)
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
        if (handles.isNotEmpty() &&
            row.kind != app.xreader.data.db.ExtensionKind.KEIYOUSHI
        ) {
            cache[row.id] = handles
        }
        return AppResult.Ok(handles)
    }

    /**
     * v1.5.7 (Finding 4, §6): the Keiyoushi claim stamp. A
     * declarative config whose baseUrl host is claimed by a
     * Keiyoushi catalogue entry is built as MANGA unless it
     * already is: the catalogue is a manga-extension index
     * by construction, so host ⇒ MANGA cannot misfire on a
     * novel-only site. The match mirrors
     * [KnownSites.entryFor]'s normalisation — the config's
     * host, normalised, equal to a claimed host or a
     * subdomain of one — with one addition: a leading
     * "www." is ignored on both sides, because the index
     * and a pasted link may spell the same site either way
     * (entryFor's list carries both spellings explicitly;
     * the catalogue carries one). Data-driven from the
     * catalogue — no site is ever named here. NOT a row
     * conversion: the row stays DECLARATIVE, no package is
     * installed, and the Mihon branch is untouched.
     */
    private fun stampKeiyoushiClaimedKind(
        config: DeclarativeConfig,
        claimedHosts: Set<String>
    ): DeclarativeConfig {
        if (config.mediaKind.isManga) return config
        if (claimedHosts.isEmpty()) return config
        val host = config.baseUrl.normaliseHost()
            .removePrefix("www.")
        if (host.isEmpty()) return config
        val claimed = claimedHosts.any { raw ->
            val candidate = raw.normaliseHost().removePrefix("www.")
            candidate.isNotEmpty() &&
                (host == candidate || host.endsWith(".$candidate"))
        }
        return if (claimed) {
            config.copy(mediaKind = MediaKind.MANGA)
        } else {
            config
        }
    }

    /**
     * Keiyoushi player (K4): build the facade handles for one
     * player row from the manager's live registry. The rating
     * is the extension's index contentWarning, asked per load
     * (the production provider answers from the catalogue the
     * install flow already fetched); when it can't be read,
     * the sources fail CLOSED to ADULT — the content
     * preference then hides them until the rating is known,
     * which is the safe direction for an unverified rating.
     */
    private suspend fun loadKeiyoushiSources(
        row: InstalledExtension
    ): List<Source> {
        val live = keiyoushiLiveSources(row.packageId)
        if (live.isEmpty()) return emptyList()
        // v1.5.2 (§8.4, settled ruling i): a MISSING stamp is
        // UNKNOWN — visible with a "rating pending" badge, never
        // resolved to ADULT (the v1.5.1 fail-closed default hid a
        // pre-stamp Asura forever). The handle carries SAFE as the
        // transport value because the enum has no UNKNOWN; the
        // pending state is tracked by the absent stamp itself
        // (KeiyoushiPlayerManager.ratingOf == null), and Browse
        // renders the badge from that absence.
        val rating = keiyoushiRatingProvider(row.packageId)
            ?: ContentRating.SAFE
        return live.entries
            .map { (namespacedId, source) ->
                MihonSourceAdapter(
                    packageName = row.packageId,
                    namespacedId = namespacedId,
                    mihon = source,
                    contentRating = rating,
                    // v1.5.8 (B4): the kind comes from the
                    // wrapped source's OWN type — a NovelSource
                    // files TEXT, everything else MANGA.
                    kind = if (
                        source is eu.kanade.tachiyomi.source.NovelSource
                    ) {
                        MediaKind.TEXT
                    } else {
                        MediaKind.MANGA
                    },
                    onCoverHostsSeen = { hosts ->
                        keiyoushiCoverHostSink(row.packageId, hosts)
                    }
                )
            }
            .sortedBy { it.name.lowercase() }
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
        // Keiyoushi player (K4): a page this handle's adapter
        // resolved is fetched through the SOURCE's own image
        // request (its headers, its client, the player's
        // session bridge) — never as a bare GET of the final
        // URL, which is exactly the request shape signed /
        // referer-guarded CDNs refuse. A URL the adapter
        // doesn't know (a cover, a text-chapter image) takes
        // the ordinary path below.
        (handle.source as? MihonSourceAdapter)?.let { adapter ->
            adapter.pageBytes(url)?.let { return it }
        }
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

    /** Keiyoushi player (K4): the player adapter behind a
     * handle, when the handle is one. */
    fun mihonAdapterOf(handle: SourceHandle): MihonSourceAdapter? =
        handle.source as? MihonSourceAdapter

    /**
     * The ONE media-kind read for a handle (manga programme
     * M1/M3, completed by K4): a player source is MANGA, a
     * declarative source carries its config's kind, and
     * every other kind serves prose. Surfaces that used to
     * ask [declarativeConfigOf] for the kind read through
     * here now, so player sources land on the Manga side of
     * the split identically to converted ones.
     */
    fun mediaKindOf(handle: SourceHandle): MediaKind =
        when (val source = handle.source) {
            is MihonSourceAdapter -> source.mediaKind
            is DeclarativeSource -> source.config.mediaKind
            else -> MediaKind.TEXT
        }

    /**
     * v1.5.2 fix (FIX-5, §7): per-book resolution where a book
     * exists — the book's stored tier-1/tier-2 kind wins over the
     * source's single config kind; a book with no stored evidence
     * on a both-kinds source resolves UNKNOWN → visual Novels
     * (TEXT here), never the source stamp.
     */
    fun mediaKindOf(handle: SourceHandle, book: app.xreader.data.db.Book): MediaKind {
        when (app.xreader.data.extension.BookKind152.verdictOf(book.kind)) {
            BookKind152.Verdict.MANGA -> return MediaKind.MANGA
            BookKind152.Verdict.TEXT -> return MediaKind.TEXT
            BookKind152.Verdict.UNKNOWN -> Unit
        }
        // v1.5.8 (B4): a player book with no stored evidence
        // takes the ADAPTER's kind — TEXT for a NovelSource
        // extension, MANGA otherwise (pre-fix this returned
        // MANGA unconditionally, the mirror hole beside the
        // adapter's own hardcoded getter).
        (handle.source as? MihonSourceAdapter)?.let { return it.mediaKind }
        val config = (handle.source as? DeclarativeSource)?.config
        val bothKinds = config != null && listOfNotNull(
            config.popular, config.latest, config.search, config.related, config.bookshelf
        ).any { it.kindField != null && it.kindValueMap.isNotEmpty() }
        return if (bothKinds) MediaKind.TEXT else mediaKindOf(handle)
    }

    /**
     * The ONE content-rating read for a handle: a player
     * source carries its extension's index rating (stamped
     * on the adapter at load), a declarative source its
     * config's, and an unrated kind (JS/Dex) is Safe — the
     * same default every surface applied before, now in one
     * place so the content preference governs player
     * sources identically.
     */
    fun contentRatingOf(handle: SourceHandle): ContentRating =
        when (val source = handle.source) {
            is MihonSourceAdapter -> source.contentRating
            is DeclarativeSource -> source.config.contentRating
            else -> ContentRating.SAFE
        }

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
     *
     * v1.5.4 (WS-1 item 1 — the seam owns the thread, donor
     * rule D1): the facade is blocking, so the call executes
     * inside `withContext(Dispatchers.IO)` HERE, at the one
     * door every source call passes through. No caller needs
     * (or may rely on) its own dispatcher switch: a call site
     * on the Main thread — the book page's enrichment, the
     * other-sources fan-out, the link-add matcher — is safe by
     * construction, which is the whole fix for the v1.5.3
     * main-thread deadlock (diagnosis F2/K1).
     *
     * The calling coroutine's [Job] is published in
     * [BridgeCallContext] for the call's duration, so the
     * player adapter's bridge can carry THIS coroutine's
     * cancellation across the blocking hop and stop the work
     * (item 2) — a deadline that only stops the waiter is not
     * a deadline (D2).
     *
     * Cancellation is never converted to an Err: it
     * propagates, as coroutine discipline requires. Every
     * other failure keeps the containment shape above.
     */
    suspend fun <T> guarded(
        handle: SourceHandle,
        block: (Source) -> T
    ): AppResult<T> {
        return try {
            val callerJob = currentCoroutineContext()[Job]
            AppResult.Ok(
                withContext(Dispatchers.IO) {
                    BridgeCallContext.withJob(callerJob) {
                        block(handle.source)
                    }
                }
            )
        } catch (e: CancellationException) {
            throw e
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
