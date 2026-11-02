package app.xreader.di

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import app.xreader.BuildConfig
import app.xreader.core.error.AppResult
import app.xreader.core.error.CrashGuard
import app.xreader.core.security.AndroidKeystoreCipher
import app.xreader.core.security.KeyVault
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.download.AndroidNetworkGate
import app.xreader.data.download.DownloadController
import app.xreader.data.download.DownloadEngine
import app.xreader.data.download.DownloadPrefs
import app.xreader.data.download.NetworkGate
import app.xreader.data.download.RoomChapterSourceResolver
import app.xreader.data.download.UrlChapterFetcher
import app.xreader.data.download.WorkManagerEnqueuer
import app.xreader.data.export.BookExporter
import app.xreader.data.extension.CookieParsing
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.extension.player.PlayerApplicationContext
import app.xreader.data.extension.player.PlayerChallengeBridge
import app.xreader.data.extension.player.PlayerNetworkHelper
import app.xreader.data.extension.player.PlayerRegistrar
import app.xreader.data.extension.player.SessionStoreCookieJar
import app.xreader.data.files.BookStorage
import app.xreader.data.importer.BookImporter
import app.xreader.data.net.HttpTransport
import app.xreader.data.net.OkHttpTransport
import app.xreader.data.opds.OpdsClient
import app.xreader.data.parse.ParserRegistry
import app.xreader.data.parse.PdfBoxTextExtractor
import app.xreader.data.prefs.AppearancePrefs
import app.xreader.data.prefs.OnboardingPrefs
import app.xreader.data.prefs.keyVaultDataStore
import app.xreader.data.prefs.settingsDataStore
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.NetworkPreferences
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.AndroidPreferenceStore

/**
 * Manual DI (PLAN §1.2): one container, constructed in [app.xreader.XreaderApp],
 * constructor injection everywhere. No Hilt — the ReTranslate-proven shape.
 * Later phases add their engines here; nothing else builds its own
 * database, vault, or preference owner.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Upgrade signal (v1.0.2, R04): whether the Room database file
     * already existed when this container was constructed — checked
     * BEFORE any Room access can create it. True = an established
     * install whose appearance choices must be preserved exactly;
     * false = a genuinely fresh install (Violet Night default).
     */
    val databaseFileExisted: Boolean =
        appContext.getDatabasePath(XreaderDatabase.NAME).exists()

    val database: XreaderDatabase = Room.databaseBuilder(
        appContext,
        XreaderDatabase::class.java,
        XreaderDatabase.NAME
        // v1.4.1: the SQLite driver is pinned explicitly (Room 2.7/2.8
        // migration path). v1.4.0 bumped Room 2.6.1 → 2.8.3 and left
        // driver resolution to the runtime default, which is unproven
        // on a real device; the bundled driver opens the SAME database
        // file and schema (v7) as-is — no migration, no data change.
    ).setDriver(
        androidx.sqlite.driver.bundled.BundledSQLiteDriver()
    ).addMigrations(
        app.xreader.data.db.MIGRATION_1_2,
        app.xreader.data.db.MIGRATION_2_3,
        app.xreader.data.db.MIGRATION_3_4,
        app.xreader.data.db.MIGRATION_4_5,
        app.xreader.data.db.MIGRATION_5_6,
        app.xreader.data.db.MIGRATION_6_7
    ).build()

    val appearancePrefs: AppearancePrefs = AppearancePrefs(
        appContext.settingsDataStore,
        establishedInstall = databaseFileExisted
    )
    val onboardingPrefs: OnboardingPrefs = OnboardingPrefs(appContext.settingsDataStore)

    /** v1.0.2: Best-source ranking, repo pins/recents, search memory. */
    /** v1.0.2 (R10): the full-path source test-drive. */
    val contractTester: app.xreader.data.extension.ContractTester by lazy {
        app.xreader.data.extension.ContractTester(extensionService)
    }

    val sourcePrefs: app.xreader.data.prefs.SourcePrefs =
        app.xreader.data.prefs.SourcePrefs(appContext.settingsDataStore)

    /** v1.0.6 (W1/W5): the browser-view runtime — the master
     * switch and performance tier mirrored from prefs, foreground
     * state fed by the lifecycle callbacks in init, and the
     * background-work depth the workers and the heal sweep count
     * through `asBackgroundWork`. */
    val renderRuntime: app.xreader.data.extension.RenderRuntime =
        app.xreader.data.extension.RenderRuntime(
            masterEnabledFlow = sourcePrefs.browserViewEnabled,
            tierFlow = appearancePrefs.state.map { appearance ->
                app.xreader.data.prefs.PerformancePolicy.resolve(
                    appearance.performanceMode, totalRamMb(), false
                ).tier
            }
        )

    /** v1.0.2 (R05): cover palettes, bounded by the performance
     * policy's budget for this device (resized when the policy's
     * tier changes — see the book page / Library call sites). */
    val coverPaletteCache: app.xreader.ui.theme.CoverPaletteCache =
        app.xreader.ui.theme.CoverPaletteCache(
            app.xreader.data.prefs.PerformancePolicy.resolve(
                app.xreader.data.prefs.PerformanceMode.AUTOMATIC, totalRamMb(), false
            ).paletteCacheSize
        )

    // -- Phase 4: reader canvas ----------------------------------------------

    val readerPrefs: app.xreader.data.prefs.ReaderPrefs =
        app.xreader.data.prefs.ReaderPrefs(appContext.settingsDataStore)

    /** v1.0.2 (R18): per-book reader overrides (palette + text
     * size) layered over the global reader settings. */
    val bookReaderPrefs: app.xreader.data.prefs.BookReaderPrefs =
        app.xreader.data.prefs.BookReaderPrefs(appContext.settingsDataStore)

    /** P0 (Reader Terms): display-time term replacements — one
     * global list plus a list per book. The stored chapter text
     * is never modified; this only changes what the reader shows. */
    val termsPrefs: app.xreader.data.prefs.TermsPrefs =
        app.xreader.data.prefs.TermsPrefs(appContext.settingsDataStore)

    /** Cycle 1 (F3, progress-linking): the refusals behind
     * "Not the same book" — the only persisted fact of the
     * link system; links themselves are derived from the
     * library by the same-story matcher on every use. */
    val progressLinkPrefs: app.xreader.data.prefs.ProgressLinkPrefs =
        app.xreader.data.prefs.ProgressLinkPrefs(appContext.settingsDataStore)

    /** Cycle 1 (F3): same-story copies in the Library share
     * reading progress by true chapter number. Reconcile runs
     * when a book page or the reader opens a library book and
     * writes through the existing reading_progress row. */
    val progressLinkService: app.xreader.data.library.ProgressLinkService by lazy {
        app.xreader.data.library.ProgressLinkService(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            progressDao = database.readingProgressDao(),
            prefs = progressLinkPrefs,
            inTransaction = { block -> database.withTransaction(block) }
        )
    }

    /** v1.0.2 (R26): AI "More like this" cache + dismissals. */
    val aiSuggestionPrefs: app.xreader.data.prefs.AiSuggestionPrefs =
        app.xreader.data.prefs.AiSuggestionPrefs(appContext.settingsDataStore)

    // v1.0.1 sidecars (no Room schema change): per-book metadata,
    // history visibility, and library/navigation customisation.
    val bookMetaPrefs: app.xreader.data.library.BookMetaPrefs =
        app.xreader.data.library.BookMetaPrefs(appContext.settingsDataStore)
    val historyPrefs: app.xreader.data.library.HistoryPrefs =
        app.xreader.data.library.HistoryPrefs(appContext.settingsDataStore)
    val libraryPrefs: app.xreader.data.library.LibraryPrefs =
        app.xreader.data.library.LibraryPrefs(appContext.settingsDataStore)

    val userFonts: app.xreader.data.reader.UserFonts =
        app.xreader.data.reader.UserFonts(File(appContext.filesDir, "fonts"))

    val sessionRecorder: app.xreader.data.reader.SessionRecorder =
        app.xreader.data.reader.SessionRecorder(
            sessionDao = database.readingSessionDao(),
            dailyDao = database.dailyActivityDao()
        )

    /** One app-wide WPM estimate, warmed from stored sessions. */
    val wpmEstimator: app.xreader.domain.reader.WpmEstimator
        get() = sessionRecorder.wpmEstimator

    /** The only holder of user secrets (Gemini key, OPDS passwords,
     * session cookies) — Keystore-encrypted, never backed up (§9.5). */
    val keyVault: KeyVault = KeyVault(appContext.keyVaultDataStore, AndroidKeystoreCipher())

    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    // -- Phase 2: document engine -------------------------------------------

    val bookStorage: BookStorage = BookStorage(appContext.filesDir)

    val httpTransport: HttpTransport = OkHttpTransport()

    val pdfTextExtractor: PdfBoxTextExtractor = PdfBoxTextExtractor(appContext)

    val parserRegistry: ParserRegistry = ParserRegistry(pdfTextExtractor)

    /** v1.0.6.2 (F6a): fills an empty local book's synopsis from
     * its own file metadata when the details screen opens. */
    val localSynopsisHeal: app.xreader.data.parse.LocalSynopsisHeal =
        app.xreader.data.parse.LocalSynopsisHeal(bookStorage, pdfTextExtractor)

    val bookImporter: BookImporter = BookImporter(
        storage = bookStorage,
        registry = parserRegistry,
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao(),
        // D9: an import's thousands of chapter writes commit once.
        inTransaction = { block -> database.withTransaction(block) }
    )

    val bookExporter: BookExporter = BookExporter(bookStorage, database.chapterDao())

    val opdsClient: OpdsClient = OpdsClient(httpTransport)

    /** OPDS sign-in from the server row + KeyVault (passwords never in Room). */
    val opdsCredentials: OpdsClient.CredentialsProvider = OpdsClient.CredentialsProvider { serverId ->
        val server = database.opdsServerDao().getById(serverId)
        val username = server?.username
        val password = if (server != null) {
            keyVault.get(KeyVault.opdsPassword(serverId))
        } else {
            null
        }
        if (username != null && password != null) username to password else null
    }

    val downloadPrefs: DownloadPrefs = DownloadPrefs(appContext)

    val networkGate: NetworkGate = AndroidNetworkGate(appContext)

    val downloadController: DownloadController = DownloadController(
        queueDao = database.downloadQueueDao(),
        chapterDao = database.chapterDao(),
        bookDao = database.bookDao(),
        storage = bookStorage,
        enqueuer = WorkManagerEnqueuer(appContext),
        // G3: batch enqueues run inside one Room transaction.
        inTransaction = { block -> database.withTransaction(block) }
    )

    /** v1.0.6.2 (F9): the one chapter-text resolution the reader
     * and every AI feature share (stored copy, else a direct live
     * fetch). G1: the live path no longer rides the download
     * queue, so a paused queue can never stall a read. The
     * resolver defers to extensionChapterResolver (declared
     * further down this container) at call time. */
    val chapterTextLoader: app.xreader.data.download.ChapterTextLoader =
        app.xreader.data.download.ChapterTextLoader(
            storage = bookStorage,
            resolver = object :
                app.xreader.data.download.ChapterSourceResolver {
                override suspend fun candidatesFor(
                    chapter: app.xreader.data.db.Chapter
                ): List<app.xreader.data.download.ResolvedSource> =
                    extensionChapterResolver.candidatesFor(chapter)
            },
            chapterDao = database.chapterDao(),
            sourceMapDao = database.chapterSourceMapDao(),
            onChapterDownloaded = { bookId, chapterId ->
                aiChapterHooks.onChapterDownloaded(bookId, chapterId)
            }
        )

    val downloadEngine: DownloadEngine = DownloadEngine(
        queueDao = database.downloadQueueDao(),
        chapterDao = database.chapterDao(),
        bookDao = database.bookDao(),
        sourceMapDao = database.chapterSourceMapDao(),
        storage = bookStorage,
        transport = httpTransport,
        resolverForItem = { downloadChapterResolver },
        networkGate = networkGate,
        settingsProvider = { downloadPrefs.settings.first() },
        onChapterDownloaded = { bookId, chapterId ->
            aiChapterHooks.onChapterDownloaded(bookId, chapterId)
        },
        fileDownloaded = { item, file ->
            // OPDS acquisition landed: import it, then retire the
            // placeholder book row the acquisition was queued against.
            val placeholder = database.bookDao().getById(item.bookId)
            if (placeholder == null) {
                app.xreader.core.error.AppError.BadFile("the book this download belongs to is gone")
            } else {
                when (val staged = bookImporter.stageDownloadedFile(placeholder.title, file)) {
                    is AppResult.Err -> staged.error
                    is AppResult.Ok -> when (val imported = bookImporter.importStaged(staged.value)) {
                        is AppResult.Err -> imported.error
                        is AppResult.Ok -> {
                            if (imported.value.id != placeholder.id) {
                                runCatching { database.bookDao().delete(placeholder) }
                            }
                            null
                        }
                    }
                }
            }
        },
        basicAuthForBook = { bookId ->
            database.bookDao().getById(bookId)?.opdsServerId?.let { serverId ->
                opdsCredentials.credentialsFor(serverId)
            }
        },
        browserDownloadDeferral = { item ->
            // v1.0.6 (Q4): a browser-view source's downloads run
            // only while the app is open — rendering never happens
            // in the background. Checked per item, against the
            // book's source marker.
            val book = database.bookDao().getById(item.bookId)
            val extensionId = book?.extensionId
            val marked = if (
                book != null &&
                book.sourceType == app.xreader.data.db.SourceType.EXTENSION &&
                extensionId != null
            ) {
                extensionService.sourcesOf(extensionId).getOrNull()
                    ?.any { handle ->
                        extensionService.declarativeConfigOf(handle)
                            ?.needsRendering == true
                    } == true
            } else {
                false
            }
            if (marked && !renderRuntime.appInForeground) {
                "This book's source reads its pages with the browser view, " +
                    "which only runs while the app is open — this download " +
                    "will continue when you're back."
            } else {
                null
            }
        }
    )

    // -- Phase 3: extension sandbox -----------------------------------------
    //
    // v1.2.0 (audit finding 7 / S1): the whole extension stack is
    // `by lazy`. Cold start used to construct it eagerly on the
    // main thread in XreaderApp.onCreate — the WebView fetcher's
    // class load, the second OkHttp client, the QuickJS plumbing
    // seam, the extension store's mkdirs — before first paint,
    // for features the first screen doesn't use. The public API
    // is unchanged (same property names/types); a lazy property
    // is built on first ACCESS, and every eager consumer left in
    // this container reaches these only through call-time lambdas
    // (the download engine's resolver/deferral, the chapter text
    // loader), so nothing's construction order or behaviour
    // changes — the cost just moves off the startup path. No
    // constructor here started doing I/O it didn't before.

    /** Extensions NEVER use [httpTransport]: their client must not
     * auto-follow redirects (whitelist re-check per hop, §9.1). */
    val extensionTransport: HttpTransport by lazy {
        // v1.3.3 (V132-CHECK 24): extension static fetches
        // run on their own lane — a walled or stalling
        // source can never starve a healthy source's
        // catalogue by exhausting the shared IO pool.
        OkHttpTransport(
            OkHttpTransport.extensionClient(),
            app.xreader.data.net.FetchLanes.static
        )
    }

    val extensionStore: app.xreader.data.extension.ExtensionStore by lazy {
        app.xreader.data.extension.ExtensionStore(appContext.filesDir)
    }

    val sessionStore: app.xreader.data.extension.SessionStore by lazy {
        app.xreader.data.extension.SessionStore(keyVault)
    }

    val cleanerRuleStore: app.xreader.data.extension.CleanerRuleStore by lazy {
        app.xreader.data.extension.CleanerRuleStore(extensionStore)
    }

    /** v1.0.6 (W1.2): the device browser view behind the
     * [app.xreader.data.extension.RenderedPageFetcher] seam —
     * JVM tests substitute a fake and never load this class. */
    val renderedPageFetcher: app.xreader.data.extension.RenderedPageFetcher by lazy {
        app.xreader.data.extension.WebViewRenderedFetcher(
            appContext, sessionStore
        ) { renderRuntime.currentTier }
    }

    val hostContextFactory: app.xreader.data.extension.HostContextFactory by lazy {
        app.xreader.data.extension.HostContextFactory(
            store = extensionStore,
            sessionStore = sessionStore,
            transport = extensionTransport,
            renderedFetcher = renderedPageFetcher,
            renderRuntime = renderRuntime,
            onRuntimeShell = { rowId ->
                // TR4: recorded in prefs; Details offers Repair.
                appScope.launch { sourcePrefs.markBrowserViewNeeded(rowId) }
            }
        )
    }

    val extensionService: app.xreader.data.extension.ExtensionService by lazy {
        app.xreader.data.extension.ExtensionService(
            extensionDao = database.installedExtensionDao(),
            store = extensionStore,
            contextFactory = hostContextFactory,
            dexLoader = app.xreader.data.extension.AndroidDexLoader(appContext),
            // Keiyoushi player (K4): the adapter's two doors
            // into the player — its live source registry and
            // its index ratings (the manager's catalogue is
            // cached after the install flow's first fetch, so
            // the rating read is a memory hit in practice).
            // Both are lambdas: they resolve the manager lazily
            // at call time, after the container exists.
            keiyoushiLiveSources = { packageName ->
                keiyoushiPlayerManager.liveSourcesOf(packageName)
            },
            keiyoushiRatingProvider = { packageName ->
                keiyoushiPlayerManager.catalog().getOrNull()
                    ?.packages
                    ?.firstOrNull { it.packageName == packageName }
                    ?.rating
            },
            // Keiyoushi player (K6): the kill switch's live
            // state, read at lookup time from the manager.
            keiyoushiPlayerEnabled = {
                keiyoushiPlayerManager.isPlayerEnabled()
            },
            jsRuntimeFactory = { app.xreader.data.extension.QuickJsRuntime() },
            // v1.3.4 (T1): read-token persistence rides the
            // app's encrypted session store.
            sessionStore = sessionStore
        )
    }

    val repoManager: app.xreader.data.extension.RepoManager by lazy {
        app.xreader.data.extension.RepoManager(
            repoDao = database.extensionRepoDao(),
            extensionDao = database.installedExtensionDao(),
            store = extensionStore,
            transport = httpTransport,
            service = extensionService,
            sessionStore = sessionStore,
            // Keiyoushi player (K6): KEIYOUSHI rows leave
            // through the player's own uninstall cascade from
            // every manager removal path (repo removal, the
            // detail page's uninstall). A lambda for the same
            // reason as the service's player doors: it resolves
            // the manager lazily at call time.
            keiyoushiUninstaller = { packageName ->
                keiyoushiPlayerManager.uninstall(packageName)
            }
        )
    }

    /** v1.3.5 (Cycle 1): provider packs — the quiet channel
     * that ships new/updated declarative sources between app
     * releases (see ProviderPackManager). State persists in
     * the extension store, beside the repo indexes. */
    val providerPackManager: app.xreader.data.extension.ProviderPackManager by lazy {
        app.xreader.data.extension.ProviderPackManager(
            repoDao = database.extensionRepoDao(),
            extensionDao = database.installedExtensionDao(),
            store = extensionStore,
            transport = httpTransport,
            service = extensionService,
            stateStore = app.xreader.data.extension.FileProviderPackStateStore(
                extensionStore
            )
        )
    }

    /** v1.3.1 (item 52): the install-time source dedupe —
     * runs once per launch after the bundled defaults. */
    val sourceDedupe: app.xreader.data.extension.SourceDedupe by lazy {
        app.xreader.data.extension.SourceDedupe(
            extensionDao = database.installedExtensionDao(),
            repoDao = database.extensionRepoDao(),
            bookDao = database.bookDao(),
            sourceMapDao = database.chapterSourceMapDao(),
            store = extensionStore,
            service = extensionService
        )
    }

    val sourceBookSync: app.xreader.data.extension.SourceBookSync by lazy {
        app.xreader.data.extension.SourceBookSync(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            sourceMapDao = database.chapterSourceMapDao(),
            extensionDao = database.installedExtensionDao(),
            service = extensionService,
            storage = bookStorage,
            transport = httpTransport,
            // v1.2.0 (audit D10, Wave-1 seam): a chapter-list
            // refresh's row writes land in ONE Room transaction.
            inTransaction = { block -> database.withTransaction(block) },
            // v1.3.0 delta (items 44/49): failover maps build
            // on the app scope, off every user path.
            failoverScheduler = { book ->
                appScope.launch {
                    runCatching { sourceBookSync.buildFailoverMapsNow(book) }
                }
            },
            // v1.3.3 (scope S6): a stored book opened with
            // repair symptoms is re-synced in the background.
            repairScheduler = { book ->
                appScope.launch {
                    runCatching { storedBookRepair.repairBook(book) }
                }
            }
        )
    }

    val linkProber: app.xreader.data.extension.LinkProber by lazy {
        app.xreader.data.extension.LinkProber(hostContextFactory, sessionStore)
    }

    /** v1.0.2 (R20): the shared whole-library refresher — Library
     * button, pull gesture (via LibraryRefreshWorker) and any
     * future scheduled check all run this one implementation. */
    val libraryRefresher: app.xreader.data.library.LibraryRefresher by lazy {
        app.xreader.data.library.LibraryRefresher(
            bookDao = database.bookDao(),
            bookMetaPrefs = bookMetaPrefs,
            sourceBookSync = sourceBookSync,
            libraryPrefs = libraryPrefs,
            downloadController = downloadController
        )
    }

    /** v1.0.5 (E1): the heal-my-library sweep — once per app
     * version it re-drives every link source with the honest
     * engine, repairs the failures and refreshes their books.
     * Triggered quietly from the shell's startup effect. */
    val healSweep: app.xreader.data.extension.HealSweep by lazy {
        app.xreader.data.extension.HealSweep(
            extensionDao = database.installedExtensionDao(),
            bookDao = database.bookDao(),
            extensionService = extensionService,
            contractTester = contractTester,
            linkProber = linkProber,
            repoManager = repoManager,
            extensionStore = extensionStore,
            sourcePrefs = sourcePrefs,
            sourceBookSync = sourceBookSync,
            networkGate = networkGate,
            battery = {
                val manager = appContext.getSystemService(
                    android.os.BatteryManager::class.java
                )
                app.xreader.data.extension.HealSweep.Battery(
                    percent = manager?.getIntProperty(
                        android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY
                    ) ?: -1,
                    isCharging = manager?.getIntProperty(
                        android.os.BatteryManager.BATTERY_PROPERTY_STATUS
                    )?.let { status ->
                        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == android.os.BatteryManager.BATTERY_STATUS_FULL
                    } ?: false
                )
            },
            // v1.0.6.2 (Revision 2, P1): the MINIMAL tier skips
            // the sweep's background book refresh (the F1 walk
            // belongs to book-open there).
            minimalTier = {
                val appearance = appearancePrefs.state
                    .first()
                app.xreader.data.prefs.PerformancePolicy.resolve(
                    appearance.performanceMode, totalRamMb(),
                    appearance.coverGlow
                ).tier == app.xreader.data.prefs.PerfTier.MINIMAL
            }
        )
    }

    // -- Phase 5: Gemini AI layer & sync -------------------------------------

    /** App-lifetime scope for best-effort background work (lore
     * extraction triggers, settings caches). */
    val appScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        )

    /** v1.3.3 (scope S4): the session-saved bus. When the user
     * completes the one-time sign-in / site-check screen, the
     * surfaces that were waiting on that session (catalogue,
     * book page, reader) hear it here and retry the action
     * that was blocked — the sign-in completion is a RETURN,
     * not a dead end. Carries the session key ("ext-<id>"). */
    val sessionSavedEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<String>(
            extraBufferCapacity = 8
        )

    fun notifySessionSaved(sessionKey: String) {
        sessionSavedEvents.tryEmit(sessionKey)
    }

    // ── Keiyoushi player core (K2) ──────────────────────────────────
    //
    // The player's pieces live here and are REGISTERED during
    // container construction (the init block at the end of this
    // section), so the Injekt singletons every Keiyoushi extension
    // looks up exist before any extension class can load — loads are
    // K3, and every load path runs after this container exists
    // (XreaderApp.onCreate builds it first). The pieces stay `by lazy`
    // for their other consumers; the init forces the two the
    // registrar needs.

    /** K2: extension cookies, resolved per-source-host from the
     * encrypted session store (never the WebView jar). */
    val playerCookieJar: SessionStoreCookieJar by lazy {
        SessionStoreCookieJar(sessionStore, keyVault)
    }

    /** K2: Cloudflare challenges from extension traffic surface here;
     * the shell turns a prompt into the sanctioned screen. A solve
     * counts only when a cf_clearance cookie resolves for the host
     * from the player's session record. */
    val playerChallengeBridge: PlayerChallengeBridge by lazy {
        PlayerChallengeBridge(
            sessionSavedEvents = sessionSavedEvents,
            hasClearance = { host ->
                val header = sessionStore
                    .load(SessionStoreCookieJar.PLAYER_SESSION_KEY)
                    ?.cookieHeaderFor(host)
                header != null && CookieParsing.parseCookieHeader(header)
                    .containsKey("cf_clearance")
            }
        )
    }

    /** K2: the NetworkHelper extensions resolve via Injekt — the
     * donor helper with our cookie jar and our Cloudflare path. */
    val playerNetworkHelper: PlayerNetworkHelper by lazy {
        PlayerNetworkHelper(
            appContext,
            NetworkPreferences(AndroidPreferenceStore(appContext)),
            playerCookieJar,
            playerChallengeBridge
        )
    }

    /** K2: the Application extensions resolve via Injekt — the real
     * app behind a per-extension namespace for prefs/cache/activity. */
    val playerApplication: PlayerApplicationContext by lazy {
        PlayerApplicationContext(appContext as Application)
    }

    init {
        // AppInfo first (an extension may read the host version from
        // its very first class load), then the Injekt registration.
        AppInfo.setVersionInfo(
            BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME
        )
        // v1.4.1 (defensive startup): registration is the Keiyoushi
        // player's front door, and nothing else in the app may die
        // with it. v1.4.0 crashed in this init because the release
        // build's R8 pass broke Injekt's TypeReference under the
        // registrar; release builds no longer minify, but the wrap
        // stays: if registration ever throws again, startup
        // CONTINUES (novels unaffected), the failure lands in the
        // diagnostic log with its exception, and the player reads
        // as not-started — PlayerRegistrar.isRegistered() stays
        // false and extension loads fail honestly as source errors
        // instead of taking the process down.
        runCatching {
            PlayerRegistrar.register(playerApplication, playerNetworkHelper)
        }.onFailure { error ->
            DiagnosticLog.record(
                tag = "player-start",
                message = "Keiyoushi player didn't start; " +
                    "manga sources are unavailable this session.",
                detail = "${error.javaClass.name}: ${error.message}"
            )
        }
        // Keiyoushi player (K6): the kill switch's live wire —
        // every flip of "Keiyoushi manga sources" (Customise →
        // Sources & repairs) is pushed into the player manager,
        // which empties or refills its live registry. The flow's
        // first emission also re-seeds the flag before the
        // shell's restore runs, so a switch left off is honoured
        // from the first lookup of the session.
        appScope.launch {
            sourcePrefs.keiyoushiPlayerEnabled.collect { enabled ->
                keiyoushiPlayerManager.setPlayerEnabled(enabled)
            }
        }
    }

    /** K3: the Keiyoushi player manager — download / verify /
     * consent / load / update / uninstall / restore for
     * Mihon-format manga extensions. Lazy like the rest of the
     * extension stack; the shell's startup block calls
     * restoreInstalled() once per launch. Loads only ever run
     * after this container exists, so the K2 Injekt
     * registration above always precedes them. */
    val keiyoushiPlayerManager:
        app.xreader.data.extension.player.KeiyoushiPlayerManager by lazy {
        app.xreader.data.extension.player.KeiyoushiPlayerManager(
            store = extensionStore,
            recordStore =
                app.xreader.data.extension.player.FileKeiyoushiRecordStore(
                    extensionStore
                ),
            transport = httpTransport,
            apkReader =
                app.xreader.data.extension.player
                    .PackageManagerKeiyoushiApkReader(appContext),
            loader =
                app.xreader.data.extension.player
                    .AndroidKeiyoushiExtensionLoader(appContext),
            extensionDao = database.installedExtensionDao(),
            repoIdProvider = { repoManager.registerKeiyoushiRepo().id },
            uninstallCleaner =
                app.xreader.data.extension.player
                    .AndroidKeiyoushiUninstallCleaner(
                        appContext, sessionStore
                    ),
            // Keiyoushi player (K6): the kill switch's stored
            // value seeds the manager's flag at restore; the
            // init-block collector below pushes later flips.
            playerEnabledProvider = {
                sourcePrefs.keiyoushiPlayerEnabled.first()
            }
        )
    }

    /** v1.3.3 (scope S6): the stored-book repair runner —
     * canonical re-syncs for Library books whose stored
     * chapter lists or covers predate the merge (see
     * StoredBookRepair). Wired into the shell's startup
     * sweep and into SourceBookSync's open-book trigger. */
    val storedBookRepair:
        app.xreader.data.extension.StoredBookRepair by lazy {
        app.xreader.data.extension.StoredBookRepair(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            sourceBookSync = sourceBookSync,
            sourcePrefs = sourcePrefs,
            storage = bookStorage,
            hasNetwork = { networkGate.hasNetwork() }
        )
    }

    val aiPrefs: app.xreader.data.prefs.AiPrefs =
        app.xreader.data.prefs.AiPrefs(appContext.settingsDataStore)

    // v1.2.0 (audit finding 7 / S1): the AI stack is `by lazy`
    // for the same reason as the extension stack above — none of
    // it is needed for first paint, and the eager consumers
    // (chapter text loader, download engine) reach aiChapterHooks
    // only through call-time lambdas. Same names/types; the cost
    // moves to first actual AI use.

    /** The one Gemini client: the key is read from the KeyVault per
     * call (never held), and the model picker is read live. Since
     * v1.0.3 it is one engine among several — features talk to
     * [aiHub]'s client, and Gemini specifics live behind it. */
    val geminiClient: app.xreader.data.ai.GeminiClient by lazy {
        app.xreader.data.ai.GeminiClient(
            transport = httpTransport,
            keyProvider = { keyVault.get(KeyVault.GEMINI_KEY) },
            pickerProvider = { aiPrefs.settings.first().modelChoice }
        )
    }

    /** v1.0.3 (AI hub): user-added providers + per-provider model
     * memory (no keys — those stay in the KeyVault). */
    val aiProviderPrefs: app.xreader.data.prefs.AiProviderPrefs =
        app.xreader.data.prefs.AiProviderPrefs(appContext.settingsDataStore)

    /** v1.0.3 (AI hub): every provider behind one client, with the
     * picking rule (Automatic routes; a specific pick is used as
     * picked). A Specific Gemini pick builds a dedicated client
     * whose picker is pinned to the picked model. */
    val aiHub: app.xreader.data.ai.hub.AiHub by lazy {
        app.xreader.data.ai.hub.AiHub(
            transport = httpTransport,
            keyVault = keyVault,
            aiPrefs = aiPrefs,
            providerPrefs = aiProviderPrefs,
            geminiFactory = { pickerOverride ->
                if (pickerOverride == null) {
                    geminiClient
                } else {
                    app.xreader.data.ai.GeminiClient(
                        transport = httpTransport,
                        keyProvider = { keyVault.get(KeyVault.GEMINI_KEY) },
                        pickerProvider = { pickerOverride }
                    )
                }
            }
        )
    }

    private val aiSettingsProvider: suspend () -> app.xreader.data.prefs.AiSettings =
        { aiPrefs.settings.first() }

    val summarizer: app.xreader.data.ai.Summarizer by lazy {
        app.xreader.data.ai.Summarizer(
            client = aiHub.client,
            summaryDao = database.aiSummaryDao(),
            settingsProvider = aiSettingsProvider
        )
    }

    /** v1.0.3: "Catch me up" — the recap bounded by the saved
     * reading position, filling in summaries for on-device chapters. */
    val catchUp: app.xreader.data.ai.CatchUp by lazy {
        app.xreader.data.ai.CatchUp(
            summarizer = summarizer,
            progressDao = database.readingProgressDao(),
            chapterDao = database.chapterDao(),
            loader = chapterTextLoader,
            settingsProvider = aiSettingsProvider
        )
    }

    /** v1.0.3: AI tag suggestions from a book's synopsis. */
    val tagSuggester: app.xreader.data.ai.TagSuggester by lazy {
        app.xreader.data.ai.TagSuggester(
            client = aiHub.client,
            settingsProvider = aiSettingsProvider
        )
    }

    /** v1.0.3: AI proposals for "Bring my library" near-matches —
     * proposals only; the user confirms each one. */
    val libraryMatchAi: app.xreader.data.ai.LibraryMatchAi by lazy {
        app.xreader.data.ai.LibraryMatchAi(
            client = aiHub.client,
            settingsProvider = aiSettingsProvider
        )
    }

    val loreKeeper: app.xreader.data.ai.LoreKeeper by lazy {
        app.xreader.data.ai.LoreKeeper(
            client = aiHub.client,
            entityDao = database.loreEntityDao(),
            relationDao = database.loreRelationDao(),
            storage = bookStorage,
            settingsProvider = aiSettingsProvider
        )
    }

    val loreChat: app.xreader.data.ai.LoreChat by lazy {
        app.xreader.data.ai.LoreChat(
            client = aiHub.client,
            settingsProvider = aiSettingsProvider
        )
    }

    val conceptArt: app.xreader.data.ai.ConceptArt by lazy {
        app.xreader.data.ai.ConceptArt(
            client = aiHub.client,
            artDao = database.loreArtDao(),
            storage = bookStorage,
            transcoder = app.xreader.data.ai.AndroidImageTranscoder,
            settingsProvider = aiSettingsProvider
        )
    }

    val rawTranslator: app.xreader.data.ai.RawTranslator by lazy {
        app.xreader.data.ai.RawTranslator(
            client = aiHub.client,
            storage = bookStorage,
            settingsProvider = aiSettingsProvider
        )
    }

    val selectorAi: app.xreader.data.ai.SelectorAi by lazy {
        app.xreader.data.ai.SelectorAi(
            client = aiHub.client,
            prober = linkProber,
            contextFactory = hostContextFactory,
            settingsProvider = aiSettingsProvider
        )
    }

    val aiChapterHooks: app.xreader.data.ai.AiChapterHooks by lazy {
        app.xreader.data.ai.AiChapterHooks(
            scope = appScope,
            loreKeeper = loreKeeper,
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            storage = bookStorage,
            client = aiHub.client,
            settingsProvider = aiSettingsProvider
        )
    }

    val syncPrefs: app.xreader.data.prefs.SyncPrefs =
        app.xreader.data.prefs.SyncPrefs(appContext.settingsDataStore)

    // v1.2.0 (audit finding 7 / S1): the sync stack is `by lazy`
    // too — Drive auth/client, tombstone/log stores and the sync
    // service itself are only needed when the user syncs, backs
    // up, or the weekly backup worker runs. Same names/types.
    val tombstoneStore: app.xreader.data.sync.TombstoneStore by lazy {
        app.xreader.data.sync.TombstoneStore(
            storage = bookStorage,
            deviceIdProvider = { syncPrefs.deviceId() }
        )
    }

    val syncLogStore: app.xreader.data.sync.SyncLogStore by lazy {
        app.xreader.data.sync.SyncLogStore(bookStorage)
    }

    val googleDriveAuth: app.xreader.data.sync.GoogleDriveAuth by lazy {
        app.xreader.data.sync.GoogleDriveAuth(appContext)
    }

    val driveSyncBackend: app.xreader.data.sync.DriveClient by lazy {
        app.xreader.data.sync.DriveClient(httpTransport, googleDriveAuth)
    }

    /** Latest WebDAV folder URL, kept warm for the backend's
     * synchronous provider (settings themselves live in DataStore). */
    private val webdavUrlCache =
        java.util.concurrent.atomic.AtomicReference("")

    val webDavBackend: app.xreader.data.sync.WebDavBackend by lazy {
        appScope.launch {
            syncPrefs.settings.collect { webdavUrlCache.set(it.webdavUrl) }
        }
        app.xreader.data.sync.WebDavBackend(
            transport = httpTransport,
            baseUrlProvider = { webdavUrlCache.get() },
            credentialsProvider = {
                val settings = syncPrefs.settings.first()
                val password = keyVault.get(KeyVault.WEBDAV_PASSWORD)
                if (settings.webdavUsername.isNotBlank() && password != null) {
                    settings.webdavUsername to password
                } else {
                    null
                }
            }
        )
    }

    /** Phase 6: the opt-in book-file channel (Drive/WebDAV/backup).
     * Constructed before [syncService] with its own key provider so
     * there's no DI cycle — it computes the same sync keys. */
    val bookFileSync: app.xreader.data.sync.BookFileSync by lazy {
        app.xreader.data.sync.BookFileSync(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            storage = bookStorage,
            keyProvider = { book ->
                val packageId = book.extensionId?.let { extId ->
                    database.installedExtensionDao().getById(extId)?.packageId
                }
                app.xreader.data.sync.SyncKeys.bookKey(book, packageId)
            }
        )
    }

    val syncService: app.xreader.data.sync.SyncService by lazy {
        app.xreader.data.sync.SyncService(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            progressDao = database.readingProgressDao(),
            bookmarkDao = database.bookmarkDao(),
            highlightDao = database.highlightDao(),
            loreEntityDao = database.loreEntityDao(),
            loreRelationDao = database.loreRelationDao(),
            terminologyDao = database.terminologyDao(),
            profileDao = database.profileDao(),
            dailyActivityDao = database.dailyActivityDao(),
            syncStateDao = database.syncStateDao(),
            installedExtensionDao = database.installedExtensionDao(),
            syncPrefs = syncPrefs,
            tombstones = tombstoneStore,
            syncLog = syncLogStore,
            readerPrefsProvider = {
                app.xreader.data.sync.ReaderPrefsCodec.encode(
                    readerPrefs.config.first()
                )
            },
            applyReaderPrefs = { map ->
                readerPrefs.update {
                    app.xreader.data.sync.ReaderPrefsCodec.decode(map, it)
                }
            },
            aiModelProvider = { aiPrefs.settings.first().modelChoice },
            applyAiModel = { aiPrefs.setModelChoice(it) },
            avatarColourProvider = {
                gamePrefs.avatarColour.first()
            },
            applyAvatarColour = { colour ->
                gamePrefs.setAvatarColour(colour)
            },
            bookStatusProvider = { bookId ->
                bookMetaPrefs.get(bookId).status.name
            },
            applyBookStatus = { bookId, statusName ->
                val parsed = runCatching {
                    app.xreader.data.library.ReadingStatus.valueOf(statusName)
                }.getOrDefault(app.xreader.data.library.ReadingStatus.NONE)
                val meta = bookMetaPrefs.get(bookId)
                if (meta.status != parsed) {
                    bookMetaPrefs.set(bookId, meta.copy(status = parsed))
                }
            },
            fileSync = bookFileSync,
            fileSyncEnabled = { syncPrefs.settings.first().syncBookFiles },
            organisationProvider = { keyByBookId ->
                val shelfDao = database.shelfDao()
                val tagDao = database.tagDao()
                val memberships = shelfDao.all().map { shelf ->
                    app.xreader.data.sync.ShelfRecord(
                        name = shelf.name,
                        kind = shelf.kind.name,
                        rule = shelf.rule?.name,
                        ruleValue = shelf.ruleValue,
                        sortOrder = shelf.sortOrder,
                        hidden = shelf.hidden,
                        sort = shelf.sort,
                        updatedAt = shelf.updatedAt,
                        memberBookKeys = shelfDao.bookIdsOn(shelf.id)
                            .mapNotNull { keyByBookId[it] }
                    )
                }
                val allBookTags = mutableListOf<app.xreader.data.sync.BookTagRecord>()
                for ((bookId, key) in keyByBookId) {
                    tagDao.forBook(bookId).forEach { row ->
                        allBookTags += app.xreader.data.sync.BookTagRecord(
                            bookKey = key,
                            slug = row.slug,
                            origin = row.origin.name,
                            suppressed = row.suppressed
                        )
                    }
                }
                val appearance = appearancePrefs.state.first()
                app.xreader.data.sync.OrganisationSection(
                    shelves = memberships,
                    tags = tagDao.allTags().map {
                        app.xreader.data.sync.TagRecord(it.slug, it.displayName, it.family)
                    },
                    bookTags = allBookTags,
                    followedTagSlugs = tagDao.allFollows().map { it.slug },
                    favouriteBookKeys = bookMetaPrefs.favourites.first()
                        .mapNotNull { keyByBookId[it] },
                    themeSync = app.xreader.data.sync.ThemeSyncRecord(
                        customSchemesEncoded =
                            app.xreader.data.prefs.AppearanceValidation
                                .encodeCustomSchemes(appearance.customSchemes),
                        colourScheme = appearance.colourScheme,
                        trueBlack = appearance.trueBlack,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            },
            applyOrganisation = { section, keyToBookId ->
                val shelfDao = database.shelfDao()
                val tagDao = database.tagDao()
                val now = System.currentTimeMillis()
                for (record in section.tags) {
                    tagDao.insertTag(
                        app.xreader.data.db.Tag(
                            slug = record.slug,
                            displayName = record.displayName,
                            family = record.family,
                            createdAt = now
                        )
                    )
                }
                for (record in section.shelves) {
                    val existing = shelfDao.all()
                        .firstOrNull { it.name.equals(record.name, ignoreCase = true) }
                    val shelfId = if (existing == null) {
                        shelfDao.insert(
                            app.xreader.data.db.Shelf(
                                name = record.name,
                                kind = runCatching {
                                    app.xreader.data.db.ShelfKind.valueOf(record.kind)
                                }.getOrDefault(app.xreader.data.db.ShelfKind.MANUAL),
                                rule = record.rule?.let { r ->
                                    runCatching {
                                        app.xreader.data.db.SmartRule.valueOf(r)
                                    }.getOrNull()
                                },
                                ruleValue = record.ruleValue,
                                sortOrder = record.sortOrder,
                                hidden = record.hidden,
                                sort = record.sort,
                                createdAt = now,
                                updatedAt = record.updatedAt
                            )
                        )
                    } else {
                        if (record.updatedAt > existing.updatedAt) {
                            shelfDao.update(
                                existing.copy(
                                    hidden = record.hidden,
                                    sort = record.sort,
                                    sortOrder = record.sortOrder,
                                    updatedAt = record.updatedAt
                                )
                            )
                        }
                        existing.id
                    }
                    for (key in record.memberBookKeys) {
                        val bookId = keyToBookId[key] ?: continue
                        shelfDao.addBook(
                            app.xreader.data.db.ShelfBook(shelfId, bookId, now)
                        )
                    }
                }
                for (record in section.bookTags) {
                    val bookId = keyToBookId[record.bookKey] ?: continue
                    tagDao.upsertBookTag(
                        app.xreader.data.db.BookTag(
                            bookId = bookId,
                            slug = record.slug,
                            origin = runCatching {
                                app.xreader.data.db.TagOrigin.valueOf(record.origin)
                            }.getOrDefault(app.xreader.data.db.TagOrigin.SOURCE),
                            suppressed = record.suppressed
                        )
                    )
                }
                for (slug in section.followedTagSlugs) {
                    tagDao.follow(app.xreader.data.db.TagFollow(slug, now))
                }
                for (key in section.favouriteBookKeys) {
                    val bookId = keyToBookId[key] ?: continue
                    bookMetaPrefs.setFavourite(bookId, true)
                }
                section.themeSync?.let { theme ->
                    val decoded = app.xreader.data.prefs.AppearanceValidation
                        .decodeCustomSchemes(theme.customSchemesEncoded)
                    if (decoded.isNotEmpty()) {
                        val current = appearancePrefs.state.first().customSchemes
                        val merged = (current + decoded)
                            .distinctBy { it.id }
                            .take(app.xreader.data.prefs.AppearanceValidation.MAX_CUSTOM_SCHEMES)
                        appearancePrefs.setCustomSchemes(merged)
                    }
                }
            }
        )
    }

    /** Downloads: extension-backed chapters resolve through their
     * sources (with cross-source failover); everything else keeps the
     * Phase 2 URL resolver. */
    val extensionChapterResolver: app.xreader.data.extension.ExtensionChapterResolver by lazy {
        app.xreader.data.extension.ExtensionChapterResolver(
            bookDao = database.bookDao(),
            sourceMapDao = database.chapterSourceMapDao(),
            service = extensionService,
            cleanerStore = cleanerRuleStore,
            fallback = RoomChapterSourceResolver(database.chapterSourceMapDao()) {
                // G4: storage set — web chapters keep their images.
                UrlChapterFetcher(httpTransport, storage = bookStorage)
            },
            // v1.0.6.2: storage set — plugin-source chapters keep
            // their images too (fetched through each source's own
            // session; see ExtensionService.fetchBytes).
            storage = bookStorage
        )
    }

    /** Manga programme (M3): the download engine's own
     * resolver — identical to [extensionChapterResolver] but
     * its fetchers also land manga page files, so a finished
     * manga download works offline. The reader's resolver
     * above never does this (reading online is not
     * downloading). */
    val downloadChapterResolver: app.xreader.data.extension.ExtensionChapterResolver by lazy {
        app.xreader.data.extension.ExtensionChapterResolver(
            bookDao = database.bookDao(),
            sourceMapDao = database.chapterSourceMapDao(),
            service = extensionService,
            cleanerStore = cleanerRuleStore,
            fallback = RoomChapterSourceResolver(database.chapterSourceMapDao()) {
                UrlChapterFetcher(httpTransport, storage = bookStorage)
            },
            storage = bookStorage,
            fetchPageFiles = true
        )
    }

    /** Manga programme (M3): a book's media kind, derived from
     * its source's config — the Library split, the Updates
     * filter and search grouping all resolve through this. */
    val bookKindResolver: app.xreader.data.library.BookKindResolver by lazy {
        app.xreader.data.library.BookKindResolver(extensionService)
    }

    // -- Phase 6: gamification, migration, file sync -------------------------

    val gamePrefs: app.xreader.data.prefs.GamePrefs =
        app.xreader.data.prefs.GamePrefs(appContext.settingsDataStore)

    /** v1.3.5 (Cycle 1): the in-app updater — its prefs (the
     * auto-check switch, the once-a-day stamps, the cached
     * release) over the settings store, and the repository
     * that checks GitHub Releases against the installed
     * version (BuildConfig) and verifies downloads by
     * SHA-256 before any install is offered. */
    val updatePrefs: app.xreader.data.prefs.UpdatePrefs =
        app.xreader.data.prefs.UpdatePrefs(appContext.settingsDataStore)

    val updateRepository: app.xreader.data.update.AppUpdateRepository by lazy {
        app.xreader.data.update.AppUpdateRepository(
            http = app.xreader.data.update.OkHttpUpdateHttp(),
            prefs = updatePrefs,
            installedVersion = { app.xreader.BuildConfig.VERSION_NAME }
        )
    }

    /** The EXP engine: every settle() is idempotent, so callers
     * (reader close, shell start, Profile open) can fire it freely. */
    val expService: app.xreader.data.game.ExpService =
        app.xreader.data.game.ExpService(
            profileDao = database.profileDao(),
            sessionDao = database.readingSessionDao(),
            dailyDao = database.dailyActivityDao(),
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            extensionDao = database.installedExtensionDao(),
            expEventDao = database.expEventDao(),
            achievementDao = database.achievementDao(),
            loreChatUsedProvider =
                app.xreader.data.game.ExpService.loreChatProvider(gamePrefs)
        )

    /** Widget → app handoff: a book id the shell should open, set by
     * the home-screen widget's tap intent via MainActivity. */
    val pendingOpenBook =
        kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** Site-shortcut → app handoff (v1.0.9): the (extension row
     * id, source id) a pinned Live Source shortcut should open,
     * set by MainActivity from the shortcut's intent. */
    val pendingOpenSource =
        kotlinx.coroutines.flow.MutableStateFlow<Pair<Long, String>?>(null)

    val migrationService: app.xreader.data.migration.MigrationService by lazy {
        app.xreader.data.migration.MigrationService(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            progressDao = database.readingProgressDao(),
            extensionDao = database.installedExtensionDao()
        )
    }

    /** Total device RAM in MB (0 when unreadable — the motion tier treats
     * unknown as Full, never stranding a device on Minimal by accident). */    fun totalRamMb(): Long = runCatching {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    }.getOrDefault(0L)

    /** The application context, for engines that must outlive any
     * single screen (TTS, camera analysis). Never an Activity. */
    val applicationContext: Context get() = appContext

    /** Per-app memory class in MB — the page-turn tier's fallback
     * signal when total RAM is unreadable (PLAN §6). */
    fun memoryClassMb(): Int = runCatching {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.memoryClass
    }.getOrDefault(0)

    /** The crash note a previous process left, if the recovery screen
     * hasn't been shown for it yet (PLAN §1.5). */
    fun unseenCrashNote(): String? {
        if (!CrashGuard.hasUnseenCrash(appContext)) return null
        return runCatching {
            File(appContext.filesDir, CrashGuard.MARKER_FILE).readText()
        }.getOrNull()
    }

    fun acknowledgeCrash() = CrashGuard.acknowledgeCrash(appContext)

    init {
        // v1.0.6 (W5): foreground tracking for the browser view —
        // rendering permission and the browser-source download
        // gate both key off whether a person is looking at the app.
        (appContext as? android.app.Application)
            ?.registerActivityLifecycleCallbacks(
                object : android.app.Application.ActivityLifecycleCallbacks {
                    private var startedActivities = 0

                    override fun onActivityStarted(activity: android.app.Activity) {
                        startedActivities++
                        renderRuntime.appInForeground = startedActivities > 0
                    }

                    override fun onActivityStopped(activity: android.app.Activity) {
                        startedActivities--
                        renderRuntime.appInForeground = startedActivities > 0
                    }

                    override fun onActivityCreated(
                        activity: android.app.Activity,
                        savedInstanceState: android.os.Bundle?
                    ) = Unit

                    override fun onActivityResumed(
                        activity: android.app.Activity
                    ) = Unit

                    override fun onActivityPaused(
                        activity: android.app.Activity
                    ) = Unit

                    override fun onActivitySaveInstanceState(
                        activity: android.app.Activity,
                        outState: android.os.Bundle
                    ) = Unit

                    override fun onActivityDestroyed(
                        activity: android.app.Activity
                    ) = Unit
                }
            )
        // R04 one-time default-scheme priming: fresh installs get
        // Violet Night, established installs keep Ember/their look.
        // Runs before any screen composes (the container is built in
        // XreaderApp.onCreate); idempotent after the first run.
        // v1.1.0 (Nocturne flip, design §2.3): the one-time v2
        // migration runs FIRST at the same prime point — it must
        // snapshot/migrate the pre-flip stored scheme before
        // priming can write a fresh-install default over the
        // empty slot (a fresh install then gets Nocturne from
        // priming, with no snapshot, exactly as designed).
        appScope.launch {
            runCatching { appearancePrefs.migrateToNocturneV2() }
            runCatching { appearancePrefs.primeDefaultScheme() }
        }
        // v1.2.1 (Defect 3): the orphan sweep — files owned by
        // book ids that no longer have a row (a cleared library,
        // rows removed with "keep files" and never re-added)
        // are reaped once per process start, before any writer
        // is active. A book whose row exists is never touched,
        // whatever state its files are in.
        appScope.launch(ioDispatcher) {
            runCatching {
                val validIds = database.bookDao().allBooks()
                    .map { it.id }.toSet()
                bookStorage.sweepOrphanedBookFiles(validIds)
            }
        }
    }

    /** v1.2.1 (Defect 3): the real image cache's size on disk —
     * Coil's disk cache under cacheDir, where browsed catalogue
     * and library covers actually accumulate (capped at
     * [AppImageLoader.DISK_CACHE_MAX_BYTES]). The Downloads
     * screen's old "Cached covers" line measured only the
     * covers/ directory (library-book cover files), so it read
     * "0 B" while this cache held the actual megabytes. */
    fun imageCacheSizeBytes(): Long =
        app.xreader.data.files.StorageMeter.dirSize(
            File(appContext.cacheDir, AppImageLoader.DISK_CACHE_DIR)
        )

    /** Clears Coil's disk cache through Coil itself (its
     * journal stays consistent); the covers/ files are the
     * Downloads screen's own Clean Cache path, not this one. */
    suspend fun clearImageCache() {
        runCatching {
            coil.Coil.imageLoader(appContext).diskCache?.clear()
        }
    }
}
