package app.xreader.di

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
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
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    ).addMigrations(
        app.xreader.data.db.MIGRATION_1_2,
        app.xreader.data.db.MIGRATION_2_3
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

    val parserRegistry: ParserRegistry = ParserRegistry(PdfBoxTextExtractor(appContext))

    val bookImporter: BookImporter = BookImporter(
        storage = bookStorage,
        registry = parserRegistry,
        bookDao = database.bookDao(),
        chapterDao = database.chapterDao()
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
        enqueuer = WorkManagerEnqueuer(appContext)
    )

    val downloadEngine: DownloadEngine = DownloadEngine(
        queueDao = database.downloadQueueDao(),
        chapterDao = database.chapterDao(),
        bookDao = database.bookDao(),
        sourceMapDao = database.chapterSourceMapDao(),
        storage = bookStorage,
        transport = httpTransport,
        resolverForItem = { extensionChapterResolver },
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
        }
    )

    // -- Phase 3: extension sandbox -----------------------------------------

    /** Extensions NEVER use [httpTransport]: their client must not
     * auto-follow redirects (whitelist re-check per hop, §9.1). */
    val extensionTransport: HttpTransport =
        OkHttpTransport(OkHttpTransport.extensionClient())

    val extensionStore: app.xreader.data.extension.ExtensionStore =
        app.xreader.data.extension.ExtensionStore(appContext.filesDir)

    val sessionStore: app.xreader.data.extension.SessionStore =
        app.xreader.data.extension.SessionStore(keyVault)

    val cleanerRuleStore: app.xreader.data.extension.CleanerRuleStore =
        app.xreader.data.extension.CleanerRuleStore(extensionStore)

    val hostContextFactory: app.xreader.data.extension.HostContextFactory =
        app.xreader.data.extension.HostContextFactory(
            store = extensionStore,
            sessionStore = sessionStore,
            transport = extensionTransport
        )

    val extensionService: app.xreader.data.extension.ExtensionService =
        app.xreader.data.extension.ExtensionService(
            extensionDao = database.installedExtensionDao(),
            store = extensionStore,
            contextFactory = hostContextFactory,
            dexLoader = app.xreader.data.extension.AndroidDexLoader(appContext),
            jsRuntimeFactory = { app.xreader.data.extension.QuickJsRuntime() }
        )

    val repoManager: app.xreader.data.extension.RepoManager =
        app.xreader.data.extension.RepoManager(
            repoDao = database.extensionRepoDao(),
            extensionDao = database.installedExtensionDao(),
            store = extensionStore,
            transport = httpTransport,
            service = extensionService,
            sessionStore = sessionStore
        )

    val sourceBookSync: app.xreader.data.extension.SourceBookSync =
        app.xreader.data.extension.SourceBookSync(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            sourceMapDao = database.chapterSourceMapDao(),
            extensionDao = database.installedExtensionDao(),
            service = extensionService,
            storage = bookStorage,
            transport = httpTransport
        )

    val linkProber: app.xreader.data.extension.LinkProber =
        app.xreader.data.extension.LinkProber(hostContextFactory, sessionStore)

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

    // -- Phase 5: Gemini AI layer & sync -------------------------------------

    /** App-lifetime scope for best-effort background work (lore
     * extraction triggers, settings caches). */
    val appScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        )

    val aiPrefs: app.xreader.data.prefs.AiPrefs =
        app.xreader.data.prefs.AiPrefs(appContext.settingsDataStore)

    /** The one Gemini client: the key is read from the KeyVault per
     * call (never held), and the model picker is read live. Since
     * v1.0.3 it is one engine among several — features talk to
     * [aiHub]'s client, and Gemini specifics live behind it. */
    val geminiClient: app.xreader.data.ai.GeminiClient =
        app.xreader.data.ai.GeminiClient(
            transport = httpTransport,
            keyProvider = { keyVault.get(KeyVault.GEMINI_KEY) },
            pickerProvider = { aiPrefs.settings.first().modelChoice }
        )

    /** v1.0.3 (AI hub): user-added providers + per-provider model
     * memory (no keys — those stay in the KeyVault). */
    val aiProviderPrefs: app.xreader.data.prefs.AiProviderPrefs =
        app.xreader.data.prefs.AiProviderPrefs(appContext.settingsDataStore)

    /** v1.0.3 (AI hub): every provider behind one client, with the
     * picking rule (Automatic routes; a specific pick is used as
     * picked). A Specific Gemini pick builds a dedicated client
     * whose picker is pinned to the picked model. */
    val aiHub: app.xreader.data.ai.hub.AiHub =
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

    private val aiSettingsProvider: suspend () -> app.xreader.data.prefs.AiSettings =
        { aiPrefs.settings.first() }

    val summarizer: app.xreader.data.ai.Summarizer =
        app.xreader.data.ai.Summarizer(
            client = aiHub.client,
            summaryDao = database.aiSummaryDao(),
            settingsProvider = aiSettingsProvider
        )

    /** v1.0.3: "Catch me up" — the recap bounded by the saved
     * reading position, filling in summaries for on-device chapters. */
    val catchUp: app.xreader.data.ai.CatchUp =
        app.xreader.data.ai.CatchUp(
            summarizer = summarizer,
            progressDao = database.readingProgressDao(),
            chapterDao = database.chapterDao(),
            storage = bookStorage,
            settingsProvider = aiSettingsProvider
        )

    /** v1.0.3: AI tag suggestions from a book's synopsis. */
    val tagSuggester: app.xreader.data.ai.TagSuggester =
        app.xreader.data.ai.TagSuggester(
            client = aiHub.client,
            settingsProvider = aiSettingsProvider
        )

    /** v1.0.3: AI proposals for "Bring my library" near-matches —
     * proposals only; the user confirms each one. */
    val libraryMatchAi: app.xreader.data.ai.LibraryMatchAi =
        app.xreader.data.ai.LibraryMatchAi(
            client = aiHub.client,
            settingsProvider = aiSettingsProvider
        )

    val loreKeeper: app.xreader.data.ai.LoreKeeper =
        app.xreader.data.ai.LoreKeeper(
            client = aiHub.client,
            entityDao = database.loreEntityDao(),
            relationDao = database.loreRelationDao(),
            storage = bookStorage,
            settingsProvider = aiSettingsProvider
        )

    val loreChat: app.xreader.data.ai.LoreChat =
        app.xreader.data.ai.LoreChat(
            client = aiHub.client,
            settingsProvider = aiSettingsProvider
        )

    val conceptArt: app.xreader.data.ai.ConceptArt =
        app.xreader.data.ai.ConceptArt(
            client = aiHub.client,
            artDao = database.loreArtDao(),
            storage = bookStorage,
            transcoder = app.xreader.data.ai.AndroidImageTranscoder,
            settingsProvider = aiSettingsProvider
        )

    val rawTranslator: app.xreader.data.ai.RawTranslator =
        app.xreader.data.ai.RawTranslator(
            client = aiHub.client,
            storage = bookStorage,
            settingsProvider = aiSettingsProvider
        )

    val selectorAi: app.xreader.data.ai.SelectorAi =
        app.xreader.data.ai.SelectorAi(
            client = aiHub.client,
            prober = linkProber,
            contextFactory = hostContextFactory,
            settingsProvider = aiSettingsProvider
        )

    val aiChapterHooks: app.xreader.data.ai.AiChapterHooks =
        app.xreader.data.ai.AiChapterHooks(
            scope = appScope,
            loreKeeper = loreKeeper,
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            storage = bookStorage,
            client = aiHub.client,
            settingsProvider = aiSettingsProvider
        )

    val syncPrefs: app.xreader.data.prefs.SyncPrefs =
        app.xreader.data.prefs.SyncPrefs(appContext.settingsDataStore)

    val tombstoneStore: app.xreader.data.sync.TombstoneStore =
        app.xreader.data.sync.TombstoneStore(
            storage = bookStorage,
            deviceIdProvider = { syncPrefs.deviceId() }
        )

    val syncLogStore: app.xreader.data.sync.SyncLogStore =
        app.xreader.data.sync.SyncLogStore(bookStorage)

    val googleDriveAuth: app.xreader.data.sync.GoogleDriveAuth =
        app.xreader.data.sync.GoogleDriveAuth(appContext)

    val driveSyncBackend: app.xreader.data.sync.DriveClient =
        app.xreader.data.sync.DriveClient(httpTransport, googleDriveAuth)

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
    val bookFileSync: app.xreader.data.sync.BookFileSync =
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

    val syncService: app.xreader.data.sync.SyncService =
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

    /** Downloads: extension-backed chapters resolve through their
     * sources (with cross-source failover); everything else keeps the
     * Phase 2 URL resolver. */
    val extensionChapterResolver: app.xreader.data.extension.ExtensionChapterResolver =
        app.xreader.data.extension.ExtensionChapterResolver(
            bookDao = database.bookDao(),
            sourceMapDao = database.chapterSourceMapDao(),
            service = extensionService,
            cleanerStore = cleanerRuleStore,
            fallback = RoomChapterSourceResolver(database.chapterSourceMapDao()) {
                UrlChapterFetcher(httpTransport)
            }
        )

    // -- Phase 6: gamification, migration, file sync -------------------------

    val gamePrefs: app.xreader.data.prefs.GamePrefs =
        app.xreader.data.prefs.GamePrefs(appContext.settingsDataStore)

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

    val migrationService: app.xreader.data.migration.MigrationService =
        app.xreader.data.migration.MigrationService(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            progressDao = database.readingProgressDao(),
            extensionDao = database.installedExtensionDao()
        )

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
        // R04 one-time default-scheme priming: fresh installs get
        // Violet Night, established installs keep Ember/their look.
        // Runs before any screen composes (the container is built in
        // XreaderApp.onCreate); idempotent after the first run.
        appScope.launch { runCatching { appearancePrefs.primeDefaultScheme() } }
    }
}
