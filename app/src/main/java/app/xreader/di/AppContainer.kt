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
import dev.mihon.injekt.patchInjekt
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.NetworkPreferences
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.AndroidPreferenceStore

/**
 * The production database factory — the ONLY place the app's Room
 * database is built (AppContainer delegates here). Extracted in
 * v1.4.2 so the unit suite can build the database through the exact
 * production chain (see ProductionDatabaseTest): until then no test
 * built a Room database at all, which is how the break below
 * shipped unnoticed.
 *
 * Deliberately driverless — no `setDriver(...)`, no
 * openHelperFactory: Room's Android default (the framework
 * SupportSQLiteOpenHelper) is what v1.3.4 shipped and what
 * [androidx.room.withTransaction] requires. v1.4.1 pinned
 * `BundledSQLiteDriver()` as a pre-emptive migration step; a
 * configured driver suppresses Room's default open-helper factory,
 * and the first withTransaction on a real phone then threw
 * IllegalStateException ("Cannot return a SupportSQLiteOpenHelper
 * since no SupportSQLiteOpenHelper.Factory was configured with
 * Room"). `build()` succeeded either way — the throw is lazy, at
 * the first transaction. Schema v7, same database file: neither
 * the pin nor this revert touches a single row.
 */
fun buildXreaderDatabase(context: Context): XreaderDatabase =
    Room.databaseBuilder(
        context.applicationContext,
        XreaderDatabase::class.java,
        XreaderDatabase.NAME
    ).addMigrations(
        app.xreader.data.db.MIGRATION_1_2,
        app.xreader.data.db.MIGRATION_2_3,
        app.xreader.data.db.MIGRATION_3_4,
        app.xreader.data.db.MIGRATION_4_5,
        app.xreader.data.db.MIGRATION_5_6,
        app.xreader.data.db.MIGRATION_6_7,
        app.xreader.data.db.MIGRATION_7_8,
        app.xreader.data.db.MIGRATION_8_9,
        app.xreader.data.db.MIGRATION_9_10,
        app.xreader.data.db.MIGRATION_10_11,
        app.xreader.data.db.MIGRATION_11_12
    ).build()

/**
 * Manual DI (PLAN §1.2): one container, constructed in [app.xreader.XreaderApp],
 * constructor injection everywhere. No Hilt — the ReTranslate-proven shape.
 * Later phases add their engines here; nothing else builds its own
 * database, vault, or preference owner.
 */
class AppContainer(
    context: Context,
    /** v1.5.2 loop 2 (owner auto-install decision): a test seam —
     * journey proofs substitute the extension transport so the
     * REAL container (its pending-op store, repo manager, sync
     * and DAOs) drives a scripted site. Production passes null
     * and gets the OkHttp extension lane, unchanged. */
    private val extensionTransportOverride:
        app.xreader.data.net.HttpTransport? = null,
    /** Same seam, one level up: a journey proof may substitute
     * a LinkProber built over a scripted rendered fetcher (the
     * device road that follows a site's cross-domain redirect
     * lives in the browser view, which JVM tests script). The
     * prober itself is the production class; production passes
     * null. */
    private val linkProberOverride:
        app.xreader.data.extension.LinkProber? = null,
    /** Same seam for the rendered road itself: a source the
     * probe marked needsRendering test-drives through the
     * browser view when it installs — JVM journeys script that
     * view instead of loading a real WebView. Production
     * passes null and gets the WebView fetcher, unchanged. */
    private val renderedFetcherOverride:
        app.xreader.data.extension.RenderedPageFetcher? = null
) {

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

    val database: XreaderDatabase = buildXreaderDatabase(appContext)

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
            },
            // v1.5.10 (A2): contained failures from the runtime's
            // collectors land in CrashGuard's contained note writer
            // + DiagnosticLog via the container's one recorder.
            containedFailureSink = { site, throwable ->
                recordContainedFailure(site, throwable)
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

    /** FIX-18 (Komikku transplant): the manga reader's GLOBAL
     * preference store (Settings/Customise defaults) and the
     * per-book overlay factory (in-reader changes land on the
     * book's own layer — the option-scope rule). The global store
     * is also the Injekt-registered ReaderPreferences' backing
     * store (KomikkuReaderRegistrar, registered in init below). */
    val komikkuGlobalStore: app.xreader.komikku.setting.KomikkuPreferenceStore by lazy {
        app.xreader.komikku.setting.DataStoreKomikkuStore(
            appContext.settingsDataStore, appScope, "komikku."
        )
    }

    fun komikkuBookStore(
        bookId: Long
    ): app.xreader.komikku.setting.KomikkuPreferenceStore =
        app.xreader.komikku.setting.DataStoreKomikkuStore(
            appContext.settingsDataStore, appScope, "komikku.book.$bookId."
        )

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

    init {
        // v1.5.2 (§13.1): favourites live by natural key. The
        // resolvers read the CURRENT book set, so a recreated row
        // keeps its favourite and a reused rowid inherits nothing.
        bookMetaPrefs.naturalKeyForId = { bookId ->
            val book = database.bookDao().getById(bookId)
            book?.let { b ->
                val pkg = b.extensionId?.let { extId ->
                    database.installedExtensionDao().getById(extId)?.packageId
                }
                app.xreader.data.sync.SyncKeys.bookKey(b, pkg)
            }
        }
        bookMetaPrefs.idForNaturalKey = { key ->
            val books = database.bookDao().allBooks()
            val byId = books.associateBy { it.id }
            books.firstOrNull { b ->
                val pkg = b.extensionId?.let { extId ->
                    database.installedExtensionDao().getById(extId)?.packageId
                }
                app.xreader.data.sync.SyncKeys.bookKey(b, pkg) == key
            }?.id
        }
    }
    val historyPrefs: app.xreader.data.library.HistoryPrefs =
        app.xreader.data.library.HistoryPrefs(appContext.settingsDataStore)

    // ── v1.5.2 stores ───────────────────────────────────────────
    val pendingOperationStore: app.xreader.data.db.PendingOperationStore by lazy {
        app.xreader.data.db.PendingOperationStore(database.pendingOperationDao())
    }

    val deletedBookKeysStore: app.xreader.data.sync.DeletedBookKeysStore by lazy {
        app.xreader.data.sync.DeletedBookKeysStore(appContext.settingsDataStore)
    }

    /** §15.5 producer: a read START writes/updates the book's ONE
     * History record (QuickNovel copy). Resume position stays in
     * ReadingProgress — never in this record. */
    suspend fun recordHistoryStart(bookId: Long) {
        val book = database.bookDao().getById(bookId) ?: return
        val pkg = book.extensionId?.let {
            database.installedExtensionDao().getById(it)?.packageId
        }
        val key = app.xreader.data.sync.SyncKeys.bookKey(book, pkg)
        val sourceName = book.extensionId?.let {
            database.installedExtensionDao().getById(it)?.displayName
        } ?: ""
        database.historyRecordDao().upsert(
            app.xreader.data.db.HistoryRecord(
                bookKey = key,
                bookId = book.id,
                title = book.title,
                bookUrl = book.remoteId ?: "",
                sourceName = sourceName,
                coverUrl = book.coverUrl,
                author = book.author,
                synopsis = book.description,
                totalChapters = book.totalChapters,
                recordedAt = System.currentTimeMillis()
            )
        )
        // v1.5.3 (finding #9, owner decision 2026-11-04): a read
        // start writes History ONLY — it never flips Library
        // membership. §15.1's "first chapter read = membership"
        // (and its "recovery" retelling of this flip) is
        // SUPERSEDED by the owner's rule: "Reading a ch adds in
        // history not library — library is only books that I
        // add." Membership flips only on deliberate acts: Add,
        // Favourite, Download, status-set. A browsed book stays
        // a preview row, visible in History/Recents only.
        // §15.6: an explicit re-add clears the book's tombstone.
        deletedBookKeysStore.clear(key)
    }

    /** §15.6: FULL book deletion (NOT a History action) — the one
     * path whose reach is: row + cascades, BookMeta, History record,
     * favourite key, queue items, both body stores' files, cover,
     * and a sync tombstone. */
    suspend fun deleteBookFully(book: app.xreader.data.db.Book) {
        val pkg = book.extensionId?.let {
            database.installedExtensionDao().getById(it)?.packageId
        }
        val key = app.xreader.data.sync.SyncKeys.bookKey(book, pkg)
        runCatching { downloadController.cancelAllForBook(book.id) }
        // v1.5.2 fix (Track B, §15.6): downloads-temp residue — the
        // book's queue items' .part files are deleted BEFORE the
        // queue rows go, while their ids are still known.
        runCatching {
            val tempDir = bookStorage.downloadsTempDir()
            for (item in database.downloadQueueDao().allForBook(book.id)) {
                java.io.File(tempDir, "queue-${item.id}.part").delete()
            }
        }
        // v1.5.2 fix (FIX-9, §15.6): queue items are DELETED in every
        // state (DONE included), not merely cancelled, and the
        // book's per-book reader-preference keys are cleared.
        runCatching { database.downloadQueueDao().deleteForBook(book.id) }
        runCatching { bookReaderPrefs.clear(book.id) }
        database.bookDao().delete(book)
        bookMetaPrefs.set(book.id, app.xreader.data.library.BookMeta())
        bookMetaPrefs.clearFavouriteByKey(key)
        database.historyRecordDao().deleteByKey(key)
        runCatching { bookStorage.deleteBookFiles(book) }
        deletedBookKeysStore.record(key, System.currentTimeMillis())
    }

    /** §15.1 SOFT removal: the inLibrary flip ONLY — shelves and
     * the hero (both derive from the same flag) stop offering the
     * book in the same commit; downloads, progress, History and
     * the favourite all survive. Read-cache (AUTOMATIC) bodies are
     * freed; USER downloads are kept (§12/ED-10). */
    suspend fun removeFromLibrarySoft(book: app.xreader.data.db.Book) {
        if (book.inLibrary) {
            database.bookDao().update(book.copy(inLibrary = false))
        }
        val chapters = database.chapterDao().forBook(book.id)
        for (chapter in chapters) {
            if (chapter.bodyOrigin ==
                app.xreader.data.download.BodyOrigin152.AUTOMATIC &&
                chapter.contentPath != null
            ) {
                runCatching {
                    bookStorage.resolve(chapter.contentPath!!).delete()
                }
                database.chapterDao().update(
                    chapter.copy(
                        contentPath = null,
                        downloadState = app.xreader.data.db.DownloadState.NONE
                    )
                )
            }
        }
    }

    /** v1.5.2 fix (FIX-10): the §8.5 one-time Explore summary —
     * set when the deferred phase completes; Explore shows it once. */
    val updatePassSummary =
        kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val updatePass152: app.xreader.data.extension.UpdatePass152 by lazy {
        app.xreader.data.extension.UpdatePass152(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            extensionDao = database.installedExtensionDao(),
            progressDao = database.readingProgressDao(),
            historyDao = database.historyRecordDao(),
            journalDao = database.updatePassJournalDao(),
            bookMetaPrefs = bookMetaPrefs,
            historyPrefs = historyPrefs,
            keyForBook = { book ->
                val pkg = book.extensionId?.let {
                    database.installedExtensionDao().getById(it)?.packageId
                }
                app.xreader.data.sync.SyncKeys.bookKey(book, pkg)
            },
            versionCode = 44,
            shelfDao = database.shelfDao(),
            isBothKinds = { row ->
                val bundled = app.xreader.data.extension.BundledDefaults
                    .configs().firstOrNull {
                        "bundled.${it.id}" == row.packageId
                    }
                val config = bundled ?: runCatching {
                    val sourceId = row.sourceIds.split(",")
                        .firstOrNull()?.trim().orEmpty()
                    val handle = extensionService
                        .source(row.id, sourceId).getOrNull()
                    handle?.let { extensionService.declarativeConfigOf(it) }
                }.getOrNull()
                config?.popular?.kindField != null &&
                    config.popular.kindValueMap.isNotEmpty()
            },
            hostForRow = { row ->
                when {
                    row.packageId.startsWith("live.") ->
                        row.packageId.removePrefix("live.")
                    row.packageId.startsWith("link.") ->
                        row.packageId.removePrefix("link.")
                    else -> app.xreader.data.extension.BundledDefaults.configs()
                        .firstOrNull { "bundled.${it.id}" == row.packageId }
                        ?.let { app.xreader.data.extension.CookieParsing.hostOf(it.baseUrl) }
                }
            },
            rederive = { row ->
                val host = when {
                    row.packageId.startsWith("live.") ->
                        row.packageId.removePrefix("live.")
                    row.packageId.startsWith("link.") ->
                        row.packageId.removePrefix("link.")
                    else -> null
                }
                if (host == null) {
                    app.xreader.data.extension.UpdatePass152.RederiveOutcome
                        .Failure("derivation failed")
                } else {
                    when (val outcome = linkProber.probe("https://$host/")) {
                        is app.xreader.data.extension.LinkProber.Outcome.Ready -> {
                            val saved = repoManager.saveLinkSource(
                                outcome.draft.config, host,
                                packageIdOverride = row.packageId
                            )
                            when (saved) {
                                is app.xreader.core.error.AppResult.Ok ->
                                    app.xreader.data.extension.UpdatePass152
                                        .RederiveOutcome.Success(saved.value.id)
                                is app.xreader.core.error.AppResult.Err ->
                                    app.xreader.data.extension.UpdatePass152
                                        .RederiveOutcome.Failure("derivation failed")
                            }
                        }
                        is app.xreader.data.extension.LinkProber.Outcome.LoginRequired ->
                            app.xreader.data.extension.UpdatePass152
                                .RederiveOutcome.Challenge
                        is app.xreader.data.extension.LinkProber.Outcome.NotProven ->
                            app.xreader.data.extension.UpdatePass152
                                .RederiveOutcome.Failure("reading not proven")
                        is app.xreader.data.extension.LinkProber.Outcome.Failed ->
                            app.xreader.data.extension.UpdatePass152
                                .RederiveOutcome.Failure(
                                    if (!networkGate.hasNetwork()) "offline"
                                    else "site unreachable"
                                )
                    }
                }
            },
            fetchCoverFile = { book -> sourceBookSync.backfillCoverFile(book) },
            ratingBackfill = { keiyoushiPlayerManager.backfillRatings() },
            isOnline = { networkGate.hasNetwork() }
        )
    }
    /** v1.5.2 fix (FIX-6b): the production eviction enforcer. */
    val cacheEviction152: app.xreader.data.download.CacheEviction152 by lazy {
        app.xreader.data.download.CacheEviction152(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            extensionDao = database.installedExtensionDao(),
            storage = bookStorage
        )
    }

    /** Outcome of the one Clean cache implementation (§12.3). */
    data class CleanCacheResult(val failures: List<String>)

    /**
     * v1.5.2 fix (FIX-6c, §12.1/§12.3): THE Clean cache — shared by
     * the Downloads screen and Customise → Storage so the two can
     * never diverge again. Clears CACHE-classified storage only:
     * read chapter bodies (AUTOMATIC rows: file + row together),
     * the Coil image cache, downloads temp/partials and import
     * staging. It does NOT touch: stored covers (per-book assets),
     * lore-art, USER downloads, the session vault, the cookie jar,
     * the database or settings. Failures are returned with §12.3's
     * taxonomy so the calling screen can show them in plain words.
     */
    suspend fun cleanCache(): CleanCacheResult {
        val failures = mutableListOf<String>()
        // Read chapter bodies (the exhibited 29.2 MB class).
        var bodyFailure = false
        runCatching {
            for (book in database.bookDao().allBooks()) {
                for (chapter in database.chapterDao().forBook(book.id)) {
                    if (chapter.bodyOrigin ==
                        app.xreader.data.download.BodyOrigin152.AUTOMATIC &&
                        chapter.contentPath != null
                    ) {
                        // v1.5.2 fix (Track B, §12.3): a body file
                        // that cannot be deleted is REPORTED, not
                        // swallowed — and its row is only cleared
                        // when the file actually went, so the row
                        // never claims a body the disk still holds.
                        val file = bookStorage.resolve(chapter.contentPath!!)
                        val deleted = !file.exists() ||
                            runCatching { file.delete() }.getOrDefault(false)
                        if (deleted) {
                            database.chapterDao().clearBody(chapter.id)
                        } else {
                            bodyFailure = true
                        }
                    }
                }
            }
        }.onFailure { bodyFailure = true }
        if (bodyFailure) {
            failures += "an I/O error stopped some read-chapter cache from clearing"
        }
        runCatching { clearImageCache() }
            .onFailure { failures += "an I/O error stopped the image cache from clearing" }
        // v1.5.2 fix (Track B, §12.1/§12.3): WebView WEB STORAGE
        // (local storage / IndexedDB) is cache and clears here —
        // the cookie JAR is credential state and is never touched
        // (deleteAllData does not clear cookies). If a live
        // WebView is holding its storage, that is reported in
        // §12.3's own words.
        runCatching {
            android.webkit.WebStorage.getInstance().deleteAllData()
        }.onFailure {
            failures += "a live WebView is holding its storage"
        }
        runCatching {
            // v1.5.2 fix (Track B, §12.1): temp/partials clear, but
            // NEVER an in-flight download's part file — queue items
            // that are not in a terminal state keep queue-<id>.part.
            val tempDir = bookStorage.downloadsTempDir()
            val inFlightIds = database.downloadQueueDao().observeAll()
                .first()
                .filter {
                    it.state != app.xreader.data.db.QueueState.DONE &&
                        it.state != app.xreader.data.db.QueueState.FAILED &&
                        it.state != app.xreader.data.db.QueueState.CANCELLED
                }
                .map { it.id }.toSet()
            tempDir.listFiles()?.forEach { child ->
                val itemId = child.name.removePrefix("queue-")
                    .removeSuffix(".part").toLongOrNull()
                if (itemId != null && itemId in inFlightIds) {
                    return@forEach // in flight — not cache prey
                }
                runCatching { child.deleteRecursively() }
            }
            app.xreader.data.files.StorageMeter.clearContents(bookStorage.importStagingRoot())
        }.onFailure { failures += "an I/O error stopped temporary files from clearing" }
        // NOTE (§12.1): coversDir() and lore-art are per-book assets
        // and deliberately NOT cleared here; the vault and cookie
        // jar are credential state and never cache prey.
        bookStorage.invalidateAllBookFileSizes()
        return CleanCacheResult(failures)
    }

    // ── §1 pending-operation helpers (FIX-2) ─────────────────────

    /** Register (or re-register after a terminal state) a pending
     * operation for [sessionKey], watermarked from the vault. */
    suspend fun registerPendingOperation(
        sessionKey: String, flowKind: String, host: String, category: String = "NONE"
    ) {
        runCatching {
            val session = sessionStore.load(sessionKey)
            pendingOperationStore.register(
                sessionKey = sessionKey, flowKind = flowKind, host = host,
                currentWatermark = app.xreader.data.db.PendingOperationStore.watermarkOf(
                    session?.let { it.cookies.toString() + it.userAgent }
                ),
                category = category, now = System.currentTimeMillis()
            )
        }
    }

    /** Event-free evaluation + atomic claim for one key: true when
     * THIS caller won the claim and must run the blocked action.
     *
     * v1.5.5 (§3): NO LONGER A RETRY GATE — the post-save
     * surfaces stopped claiming in this build (the claim was
     * the starvation mechanism: one surface's win silently
     * dropped every other surface's blocked operation). The
     * helper remains for the store's own state bookkeeping;
     * session saves now broadcast on [sessionUpdateBus] and
     * every surface retries its OWN recorded operation. */
    suspend fun claimPendingOperation(sessionKey: String): Boolean {
        val session = runCatching { sessionStore.load(sessionKey) }.getOrNull()
        val op = runCatching {
            pendingOperationStore.evaluate(
                sessionKey,
                app.xreader.data.db.PendingOperationStore.watermarkOf(
                    session?.let { it.cookies.toString() + it.userAgent }
                ),
                System.currentTimeMillis()
            )
        }.getOrNull() ?: return false
        if (op.state != "READY") return false
        return runCatching {
            pendingOperationStore.claim(sessionKey, "claim-" + System.nanoTime())
        }.getOrDefault(false)
    }

    suspend fun finishPendingOperation(sessionKey: String, success: Boolean) {
        runCatching { pendingOperationStore.finish(sessionKey, success) }
    }

    /** v1.5.2 fix (FIX-2, §1): the named abandonment path — a flow
     * the user explicitly closed, or a source that was deleted,
     * cancels its pending operation instead of leaving it to expire. */
    suspend fun cancelPendingOperation(sessionKey: String) {
        runCatching { pendingOperationStore.cancel(sessionKey) }
    }

    /**
     * v1.5.2 fix (FIX-2, §1): promotion is reconciled at evaluation
     * points — surface resume/(re)creation and app foreground call
     * this; it also recovers RUNNING records at process start and
     * prunes terminal traces after 7 days. Returns the session keys
     * whose operations are READY (a surface holding one of those
     * keys claims it through [claimPendingOperation]).
     */
    suspend fun evaluatePendingOperations(processStart: Boolean = false): List<String> {
        val ready = mutableListOf<String>()
        runCatching {
            val ops = database.pendingOperationDao().allNow()
            for (op in ops) {
                if (op.state in app.xreader.data.db.PendingOperationStore.TERMINAL) continue
                val session = runCatching { sessionStore.load(op.sessionKey) }.getOrNull()
                val evaluated = pendingOperationStore.evaluate(
                    op.sessionKey,
                    app.xreader.data.db.PendingOperationStore.watermarkOf(
                        session?.let { it.cookies.toString() + it.userAgent }
                    ),
                    System.currentTimeMillis(), processStart = processStart
                )
                if (evaluated?.state == "READY") ready += op.sessionKey
            }
            pendingOperationStore.prune(System.currentTimeMillis())
        }
        return ready
    }

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
        // v1.5.6 (§3): the v10 stores — in-place outline +
        // reading-memory restore on (re-)registration.
        readingMemoryDao = database.readingMemoryDao(),
        localOutlineDao = database.localOutlineDao(),
        readingProgressDao = database.readingProgressDao(),
        bookmarkDao = database.bookmarkDao(),
        highlightDao = database.highlightDao(),
        // D9: an import's thousands of chapter writes commit once.
        inTransaction = { block -> database.withTransaction(block) }
    )

    /** v1.5.6 (§3.7/§3.5): the local-book delete operations and
     * the manual-only Clear Legacy Data data-op. Nothing calls
     * clearLegacyData automatically — Settings' owner tap only. */
    val localBookDeletion: app.xreader.data.library.LocalBookDeletion =
        app.xreader.data.library.LocalBookDeletion(
            bookDao = database.bookDao(),
            chapterDao = database.chapterDao(),
            readingProgressDao = database.readingProgressDao(),
            bookmarkDao = database.bookmarkDao(),
            highlightDao = database.highlightDao(),
            readingMemoryDao = database.readingMemoryDao(),
            localOutlineDao = database.localOutlineDao(),
            storage = bookStorage,
            // v1.5.8 (F4 gap): deleting a local book also
            // clears its PDF strip/render cache under
            // cacheDir/local-pdf/<bookId>/.
            cacheDir = appContext.cacheDir,
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
        extensionTransportOverride ?: run {
            // v1.3.3 (V132-CHECK 24): extension static fetches
            // run on their own lane — a walled or stalling
            // source can never starve a healthy source's
            // catalogue by exhausting the shared IO pool.
            OkHttpTransport(
                OkHttpTransport.extensionClient(),
                app.xreader.data.net.FetchLanes.static
            )
        }
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
        renderedFetcherOverride
            ?: app.xreader.data.extension.WebViewRenderedFetcher(
                appContext, sessionStore,
                { renderRuntime.currentTier },
                { renderRuntime.appInForeground }
            )
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
                // v1.5.10 (A2): a failed mark is a contained failure
                // (note + log), never a process death.
                appScope.launch {
                    runCatching { sourcePrefs.markBrowserViewNeeded(rowId) }
                        .onFailure {
                            recordContainedFailure("runtime-shell:mark", it)
                        }
                }
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
                // v1.5.1 (item 6/7): NEVER a fetch — this read
                // sits inside source lookups (catalogue, book
                // open, search), and the old wiring called
                // catalog() here, so a cold in-memory cache
                // turned a lookup into a full-index network
                // fetch on the navigation path and a miss
                // failed the source closed to ADULT (installed
                // Ready sources vanished from Browse → Manga
                // after every restart). The manager now
                // answers from the record's durable rating
                // stamp or the warm cache only.
                keiyoushiPlayerManager.ratingOf(packageName)
            },
            // Keiyoushi player (K6): the kill switch's live
            // state, read at lookup time from the manager.
            keiyoushiPlayerEnabled = {
                keiyoushiPlayerManager.isPlayerEnabled()
            },
            // v1.5.4 (WS-1 item 7b, integration): cover hosts
            // a package's own sources return — the manager
            // folds them into the package's derived permission
            // domains so its covers can land as local files.
            keiyoushiCoverHostSink = { packageName, hosts ->
                keiyoushiPlayerManager.noteCoverHosts(
                    packageName, hosts
                )
            },
            // v1.5.7 (Finding 4, §6): the Keiyoushi claim —
            // the cached catalogue's hosts, read at load
            // time from the manager (never a fetch: an
            // unfetched catalogue answers empty and every
            // source loads exactly as before). A link-added
            // declarative source on a claimed host is
            // stamped MANGA when ExtensionService builds it.
            keiyoushiClaimedHosts = {
                keiyoushiPlayerManager.claimedHosts()
            },
            jsRuntimeFactory = { app.xreader.data.extension.QuickJsRuntime() },
            bookDao = database.bookDao(),
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
            service = extensionService,
            // v1.5.1 (item 1): superseded rows hand their
            // saved session to the survivor.
            sessionStore = sessionStore
        )
    }

    /** v1.5.1 (item 1): the update-time source migration —
     * once per app version, learned/link-added sources are
     * re-derived with the current learner and merged into
     * shipped sources where the site now ships (the user's
     * no-duplicates rule). Triggered from the shell's
     * startup effect, after the bundled defaults install. */
    val sourceUpdateMigration:
        app.xreader.data.extension.SourceUpdateMigration by lazy {
            app.xreader.data.extension.SourceUpdateMigration(
                extensionDao = database.installedExtensionDao(),
                repoDao = database.extensionRepoDao(),
                store = extensionStore,
                linkProber = linkProber,
                repoManager = repoManager,
                sourceDedupe = sourceDedupe,
                sourcePrefs = sourcePrefs
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
            // v1.5.26: the runner is background work, so its
            // refreshes take the same details-only player gate
            // as the worker and the sweeps.
            repairScheduler = { book ->
                appScope.launch {
                    renderRuntime.asBackgroundWork {
                        runCatching { storedBookRepair.repairBook(book) }
                    }
                }
            },
            // v1.5.26: the sync's background-work provider —
            // the one marker every bulk initiator (worker,
            // Check-for-updates, version sweeps, repair runner)
            // already runs under.
            backgroundWork = { renderRuntime.inBackgroundWork }
        )
    }

    val linkProber: app.xreader.data.extension.LinkProber by lazy {
        linkProberOverride
            ?: app.xreader.data.extension.LinkProber(
                hostContextFactory, sessionStore
            )
    }

    /** v1.0.2 (R20): the shared whole-library refresher — Library
     * button, pull gesture (via LibraryRefreshWorker) and any
     * future scheduled check all run this one implementation. */
    val libraryRefresher: app.xreader.data.library.LibraryRefresher by lazy {
        app.xreader.data.library.LibraryRefresher(
            bookDao = database.bookDao(),
            bookMetaPrefs = bookMetaPrefs,
            refreshBook = { book -> sourceBookSync.refresh(book) },
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

    /** v1.5.10 (A2): the container's one contained-failure
     * recorder — CrashGuard's contained note writer (NO crash
     * marker: the recovery screen stays reserved for real process
     * deaths) AND a DiagnosticLog line carrying A1's top frames.
     * Every root-scope containment site in this container routes
     * here, so a contained failure always leaves both artefacts.
     * Cancellation is never a failure. */
    fun recordContainedFailure(
        site: String,
        throwable: Throwable,
        threadName: String = Thread.currentThread().name
    ) {
        if (throwable is CancellationException) return
        CrashGuard.recordContainedFailure(appContext, site, threadName, throwable)
        DiagnosticLog.record(
            tag = "contained",
            message = "Contained failure at $site: " +
                "${throwable.javaClass.name} — the app kept running.",
            detail = CrashGuard.framesText(throwable)
        )
    }

    /** App-lifetime scope for best-effort background work (lore
     * extraction triggers, settings caches).
     *
     * v1.5.10 (A2): SupervisorJob (a child failure never cancels
     * siblings — unchanged) PLUS CrashGuard's containment handler:
     * an uncaught failure in any launch on this scope — including
     * the children handed this scope (AiChapterHooks, the Komikku
     * DataStore stores) — is recorded as a CONTAINED failure
     * instead of reaching the default uncaught handler and
     * killing the process. Sites that already wrap their bodies
     * in runCatching (failover/repair schedulers, pending
     * operations, startup passes) keep that first line; this
     * handler is the backstop for everything else. */
    val appScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO +
                CrashGuard.containmentHandler("appScope") { site, threadName, throwable ->
                    recordContainedFailure(site, throwable, threadName)
                }
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

    /** v1.5.5 (§3): the source-scoped session-update bus —
     * the broadcast that replaced the exactly-once claim.
     * Every surface holding a blocked operation for the saved
     * session re-issues its OWN operation when this fires;
     * the bus also stamps the solved-at registry the
     * read-token road consults. The String flow above stays
     * for the player bridge, whose latch is verification-
     * based, not claim-based. */
    val sessionUpdateBus =
        app.xreader.data.extension.SessionUpdateBus()

    fun notifySessionSaved(sessionKey: String) {
        sessionSavedEvents.tryEmit(sessionKey)
        // v1.5.5 (§3): stamp the solve FIRST (synchronously,
        // so a collector's immediate re-fetch already sees
        // the warm window), then broadcast on the bus with
        // the source id resolved from the row when the key
        // names one ("ext-<rowId>" → the row's first source).
        app.xreader.data.extension.SessionSolvedRegistry
            .noteSolved(sessionKey)
        val rowId = sessionKey.removePrefix("ext-").toLongOrNull()
        if (rowId == null) {
            sessionUpdateBus.notifySaved(sessionKey, sourceId = null)
            return
        }
        appScope.launch {
            val sourceId = runCatching {
                database.installedExtensionDao().getById(rowId)
                    ?.sourceIds?.split(",")
                    ?.firstOrNull()?.trim()?.ifEmpty { null }
            }.getOrNull()
            sessionUpdateBus.notifySaved(sessionKey, sourceId)
        }
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

    /** v1.5.0 (Part B): the player's network preferences —
     * hoisted so the UA alignment and the helper share ONE
     * instance (the alignment writes the device UA here;
     * the helper reads it for every extension request). */
    val playerNetworkPreferences: NetworkPreferences by lazy {
        NetworkPreferences(AndroidPreferenceStore(appContext))
    }

    /** K2: the NetworkHelper extensions resolve via Injekt — the
     * donor helper with our cookie jar and our Cloudflare path. */
    val playerNetworkHelper: PlayerNetworkHelper by lazy {
        PlayerNetworkHelper(
            appContext,
            playerNetworkPreferences,
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
            // v1.5.3 (finding #1, spec §5.8): the mihonapp fork's
            // Injekt patch — which REPLACES the global scope with
            // a fresh empty one — runs exactly ONCE per process,
            // here, before BOTH registrars import their modules
            // into it (the donor's App.onCreate order: patch at
            // App.kt:115, then importModule repeatedly). It used
            // to live inside each registrar, so the Komikku
            // reader's patch wiped the player's six registrations
            // and the whole Keiyoushi shelf read "0 ready".
            patchInjekt()
            PlayerRegistrar.register(playerApplication, playerNetworkHelper)
        }.onFailure { error ->
            DiagnosticLog.record(
                tag = "player-start",
                message = "Keiyoushi player didn't start; " +
                    "manga sources are unavailable this session.",
                detail = "${error.javaClass.name}: ${error.message}"
            )
        }
        // FIX-18: the Komikku reader's Injekt registration, in the
        // same pattern — a failure here must not take startup (or
        // novels) down with it; the manga reader reports honestly.
        runCatching {
            app.xreader.komikku.KomikkuReaderRegistrar.register(komikkuGlobalStore)
        }.onFailure { error ->
            DiagnosticLog.record(
                tag = "komikku-reader-start",
                message = "Komikku manga reader didn't start; " +
                    "manga reading is unavailable this session.",
                detail = "${error.javaClass.name}: ${error.message}"
            )
        }
        // v1.5.0 (Part B): the player's WebView bridges —
        // the cookie sync extensions' own WebViews rely on
        // (seed before a source call, harvest after), and
        // the one-time UA alignment so the player's HTTP
        // and WebView traffic present this device's one UA.
        app.xreader.data.extension.player.PlayerWebViewSync.install(
            appContext, sessionStore, keyVault
        )
        appScope.launch {
            runCatching {
                app.xreader.data.extension.player.PlayerWebViewSync
                    .alignUserAgent(appContext, playerNetworkPreferences)
            }
        }
        // v1.5.0 (steer A4): pin the device WebView's UA for
        // the declarative engine's HTTP road (see
        // PinnedUserAgent) — captured once, off the critical
        // path.
        appScope.launch {
            runCatching {
                app.xreader.data.extension.PinnedUserAgent
                    .capture(appContext)
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
            syncPrefs.settings.collect { settings ->
                // v1.5.10 (A2): defensive per-emission apply, same
                // shape as the player-enabled collector above.
                runCatching { webdavUrlCache.set(settings.webdavUrl) }
                    .onFailure { recordContainedFailure("webdav-url-cache", it) }
            }
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
            deletedBookKeysProvider = { deletedBookKeysStore.all() },
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
                    // v1.5.2 (§13.6): the store already speaks
                    // natural keys — export reads it directly.
                    favouriteBookKeys = bookMetaPrefs.favouritesByKey
                        .first().toList(),
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
                    // v1.5.2 (§13.6): apply by natural key — no
                    // row-id round-trip that could drop the key.
                    bookMetaPrefs.setFavouriteByKey(key, true)
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
            storage = bookStorage,
            // v1.5.17 (F1): the memo heal's chapter-list
            // refresh — the sync apply path, resolved lazily
            // at call time like every other cross-lazy wire.
            chapterListHealer = { book -> sourceBookSync.refresh(book) }
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
            fetchPageFiles = true,
            // v1.5.17 (F1): the download road heals memo-less
            // Keiyoushi chapters the same way (see the reader
            // resolver above).
            chapterListHealer = { book -> sourceBookSync.refresh(book) }
        )
    }

    /** Manga programme (M3): a book's media kind, derived from
     * its source's config — the Library split, the Updates
     * filter and search grouping all resolve through this. */
    val bookKindResolver: app.xreader.data.library.BookKindResolver by lazy {
        app.xreader.data.library.BookKindResolver(extensionService, database.bookDao())
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

    /** Source-update notification → app handoff (v1.5.39,
     * item 4): set by MainActivity from the notification's
     * tap intent; the shell observes it and opens the
     * Keiyoushi shelf — the widget-tap pattern above. */
    val pendingOpenSourceShelf =
        kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * v1.5.39 (item 4): the daily Keiyoushi manga-source
     * update check, run from the shell's startup chain
     * beside the provider-pack check. Throttled to once a
     * day by the SourcePrefs stamp (the ProviderPacks
     * lastCheckAtMs pattern; the attempt stamps first, so a
     * failed check doesn't retry until tomorrow either).
     * Silent on failure — an update is something the app
     * notices quietly, never an error the user must read.
     * Detection itself is the player's own (its flags
     * persist and drive the Explore badge and banner); this
     * adds the one notification per NEWLY-flagged set: the
     * (package, availableVersion) pairs already notified
     * are remembered in SourcePrefs, so a still-waiting
     * update never re-notifies. The player kill switch
     * off means no check at all.
     */
    suspend fun checkKeiyoushiSourceUpdates() {
        if (!sourcePrefs.keiyoushiPlayerEnabled.first()) {
            return
        }
        val now = System.currentTimeMillis()
        val lastCheck = sourcePrefs.keiyoushiLastCheckAtMs.first()
        if (lastCheck > 0 &&
            now - lastCheck < KEIYOUSHI_AUTO_CHECK_INTERVAL_MS
        ) {
            return
        }
        sourcePrefs.setKeiyoushiLastCheckAtMs(now)
        val checked = keiyoushiPlayerManager.checkForUpdates()
        if (checked !is app.xreader.core.error.AppResult.Ok) return
        val pending = keiyoushiPlayerManager.records.value.values
            .filter {
                it.isInstalled &&
                    it.availableVersionCode > it.installedVersionCode
            }
        if (pending.isEmpty()) return
        val pendingKeys = pending
            .map { "${it.packageName}:${it.availableVersionCode}" }
            .toSet()
        val notified = sourcePrefs.keiyoushiNotifiedUpdates.first()
        val fresh = pending.filter {
            "${it.packageName}:${it.availableVersionCode}" !in notified
        }
        if (fresh.isEmpty()) return
        sourcePrefs.setKeiyoushiNotifiedUpdates(notified + pendingKeys)
        app.xreader.data.update.SourceUpdateNotifier(appContext)
            .showUpdates(fresh.map { it.displayName })
    }

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
        // v1.5.22: this launch deliberately lives in the LAST
        // init block — an init-launched coroutine may only touch
        // properties declared above its init block. It used to
        // launch from the player init block further up, which
        // runs before keiyoushiPlayerManager's lazy delegate
        // exists; a first emission fast enough to win that race
        // read the still-null delegate and NPE'd (device-caught
        // 2026-11-06, contained site "player-enabled"). Here
        // every delegate exists, and the shell's restore is
        // composition-driven after onCreate, so the first
        // emission still lands before restore.
        // Keiyoushi player (K6): the kill switch's live wire —
        // every flip of "Keiyoushi manga sources" (Customise →
        // Sources & repairs) is pushed into the player manager,
        // which empties or refills its live registry. The flow's
        // first emission also re-seeds the flag before the
        // shell's restore runs, so a switch left off is honoured
        // from the first lookup of the session.
        appScope.launch {
            sourcePrefs.keiyoushiPlayerEnabled.collect { enabled ->
                // v1.5.10 (A2): a bad emission is skipped + recorded
                // as contained; the collector keeps collecting and
                // the scope handler backstops a flow-side throw.
                runCatching { keiyoushiPlayerManager.setPlayerEnabled(enabled) }
                    .onFailure { recordContainedFailure("player-enabled", it) }
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

/** v1.5.39 (item 4): the Keiyoushi source-update auto-check
 * interval — once a day, ProviderPacks' own value. */
private const val KEIYOUSHI_AUTO_CHECK_INTERVAL_MS =
    24L * 60 * 60 * 1000
