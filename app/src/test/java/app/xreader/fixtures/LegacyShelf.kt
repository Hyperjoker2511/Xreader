package app.xreader.fixtures

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.room.withTransaction
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.HistoryRecord
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.PendingOperation
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.db.UpdatePassJournalEntry
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.extension.UpdatePass152
import app.xreader.extensionapi.ExtensionApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * v1.5.10 (L-FIX, build-map A4/I2): THE legacy shelf — the ONE
 * fixture database + DataStore state every v1.5.10 gate suite
 * drives (A4 soak, B2 joined pass, H2 warmth, I3 census). Gate
 * tests import this module; private shelf copies fail the I2
 * census.
 *
 * ## How "legacy" is expressed here (honest construction note)
 *
 * The tree has ONE entity set (schema v11); no legacy entity
 * classes survive, because every migration since v1.5.2 added
 * columns WITH defaults. A "v1.5.2-era shape" is therefore
 * today's entity carrying the OLD FIELD VALUES a pre-migration
 * row would present after migrating forward:
 *
 *  - epoch-0 bundled rows: [Book.syncedEpoch] = 0 (the column's
 *    own "never stamped" value, pre-v1.5.5 population) on books
 *    of bundled-provenance [InstalledExtension] rows whose
 *    `permissionsJson` is a STALE LEAF-GRANT blob — the
 *    pre-epoch-heal shape where the stored grant names a leaf
 *    host the current bundled definition no longer derives
 *    (BrightNovel: the parked SINGULAR `brightnovel.com`, the
 *    exact blob shape `V159BrightNovelHealTest` pins at its
 *    §5.3 leg). The blob is written as the raw JSON string a
 *    stored row carries, in the field order
 *    `ExtensionPermissions.encode()` emits.
 *  - v1.5.2-era chapter rows: `bodyOrigin = null` (pre-§12.4
 *    unstamped — the §12.6 demotion heuristic's input) and
 *    `scanlator = null` (the column did not exist before v11;
 *    null is its migrated value).
 *  - v1.5.2-era journal rows: [UpdatePassJournalEntry] rows at
 *    versionCode 44 (the code the V152 fixture suite drives)
 *    beside the required versionCode-50 set.
 *
 * Nothing here invents a column. Where a plan phrase names a
 * shape the current entities cannot hold, the builder's doc
 * says so instead of faking it (see [build]).
 */

/** An in-memory Preferences DataStore — the tree's own test
 * pattern (V152 suites), shared here so every lane seeds and
 * reads prefs state the same way. NOT a production store. */
class FakePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }

    /** The current raw snapshot (journeys assert on this BEFORE
     * any production first-access migration can run). */
    suspend fun snapshot(): Preferences = data.first()
}

object LegacyShelf {

    // ── Version codes the shelf is frozen at ─────────────────
    /** The UpdatePass152 journal + heal-sweep flag are written
     * at THIS code (build-map A4: "journal written at
     * versionCode 50 shape + a heal-sweep completion flag
     * at 50"). */
    const val JOURNAL_VERSION_CODE = 50

    /** The v1.5.2-era journal set rides at the code the V152
     * fixture suite itself uses. */
    const val V152_JOURNAL_VERSION_CODE = 44

    // ── Row ids (explicit, so journeys can address rows
    //    without a lookup round-trip) ────────────────────────
    const val REPO_ID = 1L

    const val EXT_BRIGHTNOVEL = 11L
    const val EXT_FENRIR = 12L
    const val EXT_BOTI = 13L

    const val EXT_MANGABALL = 21L
    const val EXT_MANGAFIRE = 22L
    const val EXT_ASURA = 23L
    const val EXT_MANGADEX = 24L
    const val EXT_FLAME = 25L

    const val BOOK_BRIGHTNOVEL_LEGACY = 101L
    const val BOOK_LONG_1200 = 102L
    const val BOOK_LONG_1345 = 103L
    const val BOOK_EPUB_D3 = 104L
    const val BOOK_SMALL_V152 = 105L

    const val LONG_BOOK_1200_CHAPTERS = 1200
    const val LONG_BOOK_1345_CHAPTERS = 1345

    /** The stored address of [BOOK_BRIGHTNOVEL_LEGACY]: the
     * parked SINGULAR host — the pre-heal foreign-host shape
     * (Finding-2's convicted legacy form; the shipped
     * definition's base is the plural `brightnovels.com`). */
    const val BRIGHTNOVEL_LEGACY_URL =
        "https://brightnovel.com/series/the-dullard"
    const val BRIGHTNOVEL_HEALED_URL =
        "https://brightnovels.com/series/the-dullard"

    /** MangaFire's seven real Mihon source ids (the owner's
     * Finding-6 case, verbatim from `V159Finding6MultiSourceTest`). */
    val MANGAFIRE_SOURCE_IDS = listOf(
        "6084907896154116083",
        "8098539940032331958",
        "3849324770075062403",
        "6228021941803932286",
        "524110931382140732",
        "758400875161895515",
        "7842137233968841729"
    )

    /** Manga Ball shape: ONE package carrying 42 instances.
     * The ids are synthetic Mihon-shaped numerics (the live
     * package's real ids are not recorded in this tree); the
     * SHAPE — one KEIYOUSHI row, 42 comma-joined sourceIds —
     * is the fixture's claim, not the id values. */
    val MANGABALL_SOURCE_IDS: List<String> =
        (1..42).map { (6_100_000_000_000_000_000L + it).toString() }

    /** A fixed legacy timestamp: every shelf row is OLD. */
    const val LEGACY_TIME = 1_700_000_000_000L

    /** The built shelf: the database plus the two DataStore
     * states the journeys need. Caller closes [db]. */
    class Shelf(
        val db: XreaderDatabase,
        /** Stands in for the "sources" preferences store:
         * carries the heal-sweep completion flag at
         * [JOURNAL_VERSION_CODE] (read it through the tree's
         * real `SourcePrefs`). */
        val sourcePrefsStore: FakePreferencesDataStore,
        /** Stands in for the shared "settings" preferences
         * store `LocalReaderPrefs` is constructed over:
         * carries [BOOK_EPUB_D3]'s pre-1.5.9 EPUB snapshot
         * (DARKEN plate filter + `scroll = false`) with NO
         * migration markers — the raw pre-migration state. */
        val settingsStore: FakePreferencesDataStore
    )

    private fun permissionsJson(vararg domains: String): String {
        val list = domains.joinToString(",") { "\"$it\"" }
        return "{\"domains\":[$list],\"canUseNetwork\":true," +
            "\"canNotify\":false,\"blockedDomains\":[],\"dexOptIn\":false}"
    }

    private fun bundledRow(
        id: Long,
        displayName: String,
        packageId: String,
        sourceId: String,
        permissionsJson: String
    ) = InstalledExtension(
        id = id,
        repoId = REPO_ID,
        displayName = displayName,
        packageId = packageId,
        versionName = "1.0",
        versionCode = 1,
        kind = ExtensionKind.DECLARATIVE,
        artifactPath = null,
        sha256 = "legacy-$packageId",
        apiLevel = ExtensionApi.API_LEVEL,
        languages = "en",
        sourceIds = sourceId,
        enabled = true,
        trustState = TrustState.TRUSTED,
        permissionsJson = permissionsJson,
        installedAt = LEGACY_TIME,
        updatedAt = LEGACY_TIME
    )

    private fun keiyoushiRow(
        id: Long,
        displayName: String,
        packageId: String,
        sourceIds: List<String>,
        languages: String
    ) = InstalledExtension(
        id = id,
        repoId = REPO_ID,
        displayName = displayName,
        packageId = packageId,
        versionName = "1.4.0",
        versionCode = 40,
        kind = ExtensionKind.KEIYOUSHI,
        artifactPath = "extensions/$id/base.apk",
        sha256 = "legacy-$packageId",
        apiLevel = ExtensionApi.API_LEVEL,
        languages = languages,
        // The tree's own stored form: comma-joined Mihon ids
        // (KeiyoushiPlayerManager writes exactly this).
        sourceIds = sourceIds.joinToString(","),
        enabled = true,
        trustState = TrustState.TRUSTED,
        permissionsJson = "{}",
        installedAt = LEGACY_TIME,
        updatedAt = LEGACY_TIME
    )

    private fun extensionBook(
        id: Long,
        title: String,
        extensionId: Long,
        remoteId: String,
        totalChapters: Int,
        kind: String? = null,
        syncedEpoch: Int = 0,
        chapterListComplete: Boolean = true
    ) = Book(
        id = id,
        title = title,
        author = "Legacy Author",
        sourceType = SourceType.EXTENSION,
        extensionId = extensionId,
        remoteId = remoteId,
        kind = kind,
        totalChapters = totalChapters,
        addedAt = LEGACY_TIME,
        updatedAt = LEGACY_TIME,
        lastOpenedAt = LEGACY_TIME,
        inLibrary = true,
        chapterListComplete = chapterListComplete,
        syncedEpoch = syncedEpoch
    )

    private fun chaptersFor(
        bookId: Long,
        count: Int,
        v152Shape: Boolean = false
    ): List<Chapter> = (0 until count).map { i ->
        Chapter(
            bookId = bookId,
            index = i,
            title = "Chapter ${i + 1}",
            remoteId = "chapter-${i + 1}",
            // v1.5.2-era shape: an unstamped stored body on the
            // first row (bodyOrigin null is the §12.6 input);
            // modern rows leave bodies unstored here.
            contentPath = if (v152Shape && i == 0) {
                "content/$bookId/0.txt"
            } else {
                null
            },
            wordCount = if (v152Shape && i == 0) 900 else 0,
            downloadState = if (v152Shape && i == 0) {
                DownloadState.READY
            } else {
                DownloadState.NONE
            },
            read = i == 0,
            updatedAt = LEGACY_TIME,
            dateText = "01.11.2026",
            bodyOrigin = null,
            scanlator = null
        )
    }

    /**
     * Builds the whole shelf. Every element, by entity:
     *
     *  - [ExtensionRepo] id [REPO_ID] (the FK parent).
     *  - Three epoch-0 BUNDLED rows ([EXT_BRIGHTNOVEL],
     *    [EXT_FENRIR], [EXT_BOTI]): DECLARATIVE
     *    [InstalledExtension]s, `bundled.*` packageIds, stale
     *    leaf-grant `permissionsJson` (BrightNovel's names the
     *    parked singular host; Fenrir's/BOTI's name their
     *    pre-epoch leaf hosts). Their books carry
     *    `syncedEpoch = 0`.
     *  - Five KEIYOUSHI packages: [EXT_MANGABALL] with 42
     *    sourceIds, [EXT_MANGAFIRE] with the 7 real ids, plus
     *    Asura (1), MangaDex (5) and Flame (1).
     *  - [BOOK_LONG_1200]: 1,200 [Chapter] rows;
     *    [BOOK_LONG_1345]: 1,345 [Chapter] rows (both Fenrir
     *    books, epoch-0).
     *  - [BOOK_BRIGHTNOVEL_LEGACY]: a [Book] whose stored
     *    `remoteId` is [BRIGHTNOVEL_LEGACY_URL] — the foreign
     *    singular host, pre-heal.
     *  - [BOOK_EPUB_D3]: a LOCAL_FILE/EPUB [Book] whose
     *    `filePath` is [D3EpubFixture.FILE_NAME]; its stored
     *    DARKEN plate filter + `scroll = false` snapshot live
     *    in [Shelf.settingsStore] under the tree's real
     *    per-book keys, with NO scroll-default marker and NO
     *    plate-filter marker (both migrations uncaught-up —
     *    the pre-1.5.9 state E1/A4 judge). NOTE: reading the
     *    book through `LocalReaderPrefs` runs the shipped
     *    scroll upgrade on first access, by design; the raw
     *    seeded state is asserted via
     *    [FakePreferencesDataStore.snapshot] BEFORE any such
     *    read.
     *  - [BOOK_SMALL_V152]: three v1.5.2-era [Chapter] rows
     *    (unstamped body, null scanlator) + a [HistoryRecord]
     *    + a [ReadingProgress].
     *  - [UpdatePassJournalEntry] rows: at
     *    [JOURNAL_VERSION_CODE] the `__phase0__` marker at
     *    stage "done" / outcome "complete" plus one validated
     *    row per bundled package; at
     *    [V152_JOURNAL_VERSION_CODE] the v1.5.2-era set
     *    (marker complete, one pending link row).
     *  - One v1.5.2-era [PendingOperation] (LINK_ADD, WAITING).
     *  - [Shelf.sourcePrefsStore]: heal-sweep flag
     *    `heal.sweep_done_for_code` = [JOURNAL_VERSION_CODE]
     *    (the tree's real key, read by `SourcePrefs`).
     */
    suspend fun build(context: Context): Shelf {
        val db = Room.inMemoryDatabaseBuilder(
            context, XreaderDatabase::class.java
        ).allowMainThreadQueries().build()

        db.withTransaction {
            db.extensionRepoDao().insert(
                ExtensionRepo(
                    id = REPO_ID, name = "Legacy Fixture Repo",
                    indexUrl = "https://example.test/index.json",
                    lastFetchedAt = LEGACY_TIME
                )
            )
            val extDao = db.installedExtensionDao()
            // ── epoch-0 bundled rows, stale leaf grants ──────
            extDao.insert(
                bundledRow(
                    EXT_BRIGHTNOVEL, "BrightNovel",
                    "bundled.bundled-brightnovel", "brightnovel",
                    permissionsJson("brightnovel.com")
                )
            )
            extDao.insert(
                bundledRow(
                    EXT_FENRIR, "FenrirRealm",
                    "bundled.bundled-fenrirealm", "fenrirealm",
                    permissionsJson("fenrirealm.com")
                )
            )
            extDao.insert(
                bundledRow(
                    EXT_BOTI, "BotiTranslation",
                    "bundled.bundled-botitranslation", "botitranslation",
                    permissionsJson("botitranslation.com")
                )
            )
            // ── five Keiyoushi packages ──────────────────────
            extDao.insert(
                keiyoushiRow(
                    EXT_MANGABALL, "Manga Ball",
                    "eu.kanade.tachiyomi.extension.all.mangaball",
                    MANGABALL_SOURCE_IDS, "all"
                )
            )
            extDao.insert(
                keiyoushiRow(
                    EXT_MANGAFIRE, "MangaFire",
                    "eu.kanade.tachiyomi.extension.all.mangafire",
                    MANGAFIRE_SOURCE_IDS, "all"
                )
            )
            extDao.insert(
                keiyoushiRow(
                    EXT_ASURA, "Asura Scans",
                    "eu.kanade.tachiyomi.extension.en.asurascans",
                    listOf("2951175035893987454"), "en"
                )
            )
            extDao.insert(
                keiyoushiRow(
                    EXT_MANGADEX, "MangaDex",
                    "eu.kanade.tachiyomi.extension.all.mangadex",
                    (1..5).map {
                        (5_500_000_000_000_000_000L + it).toString()
                    },
                    "all"
                )
            )
            extDao.insert(
                keiyoushiRow(
                    EXT_FLAME, "Flame Comics",
                    "eu.kanade.tachiyomi.extension.en.flamecomics",
                    listOf("3157284030265592637"), "en"
                )
            )

            val bookDao = db.bookDao()
            val chapterDao = db.chapterDao()
            // ── the foreign-host legacy book ─────────────────
            bookDao.insert(
                extensionBook(
                    BOOK_BRIGHTNOVEL_LEGACY, "The Dullard",
                    EXT_BRIGHTNOVEL, BRIGHTNOVEL_LEGACY_URL,
                    totalChapters = 2, kind = "TEXT"
                )
            )
            chapterDao.insertAll(
                chaptersFor(BOOK_BRIGHTNOVEL_LEGACY, 2)
            )
            // ── the long books ───────────────────────────────
            bookDao.insert(
                extensionBook(
                    BOOK_LONG_1200, "The Twelve Hundred",
                    EXT_FENRIR,
                    "https://fenrirealm.com/series/twelve-hundred",
                    totalChapters = LONG_BOOK_1200_CHAPTERS,
                    kind = "TEXT"
                )
            )
            chaptersFor(BOOK_LONG_1200, LONG_BOOK_1200_CHAPTERS)
                .chunked(400).forEach { chapterDao.insertAll(it) }
            bookDao.insert(
                extensionBook(
                    BOOK_LONG_1345, "The Thirteen Forty-Five",
                    EXT_FENRIR,
                    "https://fenrirealm.com/series/thirteen-forty-five",
                    totalChapters = LONG_BOOK_1345_CHAPTERS,
                    kind = "TEXT"
                )
            )
            chaptersFor(BOOK_LONG_1345, LONG_BOOK_1345_CHAPTERS)
                .chunked(400).forEach { chapterDao.insertAll(it) }
            // ── the EPUB book (settings ride the DataStore) ──
            bookDao.insert(
                Book(
                    id = BOOK_EPUB_D3,
                    title = "D3 Fixture Book",
                    author = "Fixture Author",
                    sourceType = SourceType.LOCAL_FILE,
                    localFormat = BookFormat.EPUB,
                    filePath = D3EpubFixture.FILE_NAME,
                    totalChapters = D3EpubFixture.SPINE.size,
                    addedAt = LEGACY_TIME,
                    updatedAt = LEGACY_TIME,
                    lastOpenedAt = LEGACY_TIME,
                    inLibrary = true
                )
            )
            // ── the v1.5.2-era small book ────────────────────
            bookDao.insert(
                extensionBook(
                    BOOK_SMALL_V152, "Small V152 Book",
                    EXT_BOTI,
                    "https://botitranslation.com/series/small",
                    totalChapters = 3, kind = "TEXT"
                )
            )
            chapterDao.insertAll(
                chaptersFor(BOOK_SMALL_V152, 3, v152Shape = true)
            )
            db.readingProgressDao().upsert(
                ReadingProgress(
                    bookId = BOOK_SMALL_V152,
                    chapterId = 0L,
                    chapterIndex = 1,
                    charOffset = 120,
                    fraction = 0.4f,
                    scrollFraction = 0.4f,
                    updatedAt = LEGACY_TIME
                )
            )
            db.historyRecordDao().upsert(
                HistoryRecord(
                    bookKey = "ext:$EXT_BOTI:small",
                    bookId = BOOK_SMALL_V152,
                    title = "Small V152 Book",
                    bookUrl = "https://botitranslation.com/series/small",
                    sourceName = "BotiTranslation",
                    totalChapters = 3,
                    recordedAt = LEGACY_TIME
                )
            )
            db.pendingOperationDao().upsert(
                PendingOperation(
                    sessionKey = "legacy-link-add-session",
                    flowKind = "LINK_ADD",
                    host = "example.test",
                    state = "WAITING",
                    createdAt = LEGACY_TIME,
                    watermark = "none"
                )
            )
            // ── journal: the versionCode-50 set ──────────────
            val journalDao = db.updatePassJournalDao()
            journalDao.upsert(
                UpdatePassJournalEntry(
                    versionCode = JOURNAL_VERSION_CODE,
                    providerKey = UpdatePass152.PHASE0_MARKER,
                    stage = "done", outcome = "complete",
                    updatedAt = LEGACY_TIME
                )
            )
            for (pkg in listOf(
                "bundled.bundled-brightnovel",
                "bundled.bundled-fenrirealm",
                "bundled.bundled-botitranslation"
            )) {
                journalDao.upsert(
                    UpdatePassJournalEntry(
                        versionCode = JOURNAL_VERSION_CODE,
                        providerKey = pkg,
                        stage = "done", outcome = "validated",
                        updatedAt = LEGACY_TIME
                    )
                )
            }
            // ── journal: the v1.5.2-era set at 44 ────────────
            journalDao.upsert(
                UpdatePassJournalEntry(
                    versionCode = V152_JOURNAL_VERSION_CODE,
                    providerKey = UpdatePass152.PHASE0_MARKER,
                    stage = "done", outcome = "complete",
                    updatedAt = LEGACY_TIME
                )
            )
            journalDao.upsert(
                UpdatePassJournalEntry(
                    versionCode = V152_JOURNAL_VERSION_CODE,
                    providerKey = "live.down.example",
                    stage = "pending", outcome = "pending",
                    updatedAt = LEGACY_TIME
                )
            )
        }

        // ── DataStore state ──────────────────────────────────
        val sourcePrefsStore = FakePreferencesDataStore()
        sourcePrefsStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                // SourcePrefs' real key for the heal flag.
                this[intPreferencesKey("heal.sweep_done_for_code")] =
                    JOURNAL_VERSION_CODE
            }.toPreferences()
        }
        val settingsStore = FakePreferencesDataStore()
        settingsStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                // LocalReaderPrefs' real per-book keys for
                // BOOK_EPUB_D3 — the pre-1.5.9 snapshot: a
                // stored DARKEN plate filter and a stored
                // scroll=false, and (by ABSENCE) neither the
                // scroll-default marker nor any plate-filter
                // migration marker.
                this[stringPreferencesKey(
                    "localreader.$BOOK_EPUB_D3.epub.plate_filter"
                )] = "DARKEN"
                this[booleanPreferencesKey(
                    "localreader.$BOOK_EPUB_D3.epub.scroll"
                )] = false
            }.toPreferences()
        }
        return Shelf(db, sourcePrefsStore, settingsStore)
    }
}
