package app.xreader.data.extension

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.HistoryRecord
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.db.XreaderDatabase
import app.xreader.data.library.BookMetaPrefs
import app.xreader.data.library.HistoryPrefs
import app.xreader.data.sync.SyncKeys
import app.xreader.extensionapi.ExtensionApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.2 (FIX-10, §8.5): the update pass over the spec's own
 * fixture — fossil "Home" BOTI, a pre-stamp Keiyoushi record,
 * MANGA-stamped FenrirRealm + books, learned Chikari + books,
 * one user-Repaired bundled row, one disabled bundled row, a
 * link-added duplicate of a shipped host (graduation), and one
 * link-added source whose site is down — with count
 * reconciliation, the Phase 0 < 5 s bound, the completion rule,
 * the offline-records-nothing rule, and the F15-01 History
 * shape surviving the residue sweep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V152UpdatePassFixtureTest {

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }

        suspend fun seedFavouriteRow(bookId: Long) {
            updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    this[booleanPreferencesKey("bookfav.$bookId")] = true
                }.toPreferences()
            }
        }
    }

    private fun extRow(
        name: String,
        packageId: String,
        kind: ExtensionKind = ExtensionKind.DECLARATIVE,
        enabled: Boolean = true,
        needsRepair: Boolean = false
    ) = InstalledExtension(
        repoId = 1,
        displayName = name,
        packageId = packageId,
        versionName = "1.0",
        versionCode = 1,
        kind = kind,
        artifactPath = null,
        sha256 = "x",
        apiLevel = ExtensionApi.API_LEVEL,
        languages = "en",
        sourceIds = packageId,
        enabled = enabled,
        trustState = TrustState.TRUSTED,
        permissionsJson = "{}",
        installedAt = 1L,
        updatedAt = 1L,
        needsRepair = needsRepair,
        needsRepairReason = if (needsRepair) "user repaired" else null
    )

    private fun book(
        title: String,
        extensionId: Long?,
        kind: String? = null,
        inLibrary: Boolean = true,
        coverUrl: String? = null,
        remoteId: String = title.lowercase().replace(" ", "-")
    ) = Book(
        title = title,
        sourceType = SourceType.EXTENSION,
        extensionId = extensionId,
        remoteId = remoteId,
        kind = kind,
        coverUrl = coverUrl,
        addedAt = 1L,
        updatedAt = 1L,
        inLibrary = inLibrary
    )

    @Test
    fun `the section 8_5 fixture reconciles`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, XreaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val bookDao = db.bookDao()
        val chapterDao = db.chapterDao()
        val extDao = db.installedExtensionDao()
        val journalDao = db.updatePassJournalDao()
        val historyDao = db.historyRecordDao()

        db.extensionRepoDao().insert(
            ExtensionRepo(name = "Fixture", indexUrl = "https://example.test/i")
        )
        // ── providers ───────────────────────────────────────
        val botiId = extDao.insert(
            extRow("Home", "live.botitranslation.com",
                kind = ExtensionKind.DECLARATIVE)
        )
        val asuraId = extDao.insert(
            extRow("Asura Scans", "keiyoushi.asurascans",
                kind = ExtensionKind.KEIYOUSHI)
        )
        val fenrirId = extDao.insert(
            extRow("FenrirRealm", "bundled.bundled-fenrirealm")
        )
        val chikariId = extDao.insert(
            extRow("Chikari", "link.bundled-chikari")
        )
        val repairedId = extDao.insert(
            extRow("Repaired One", "bundled.bundled-repaired",
                needsRepair = true)
        )
        val disabledId = extDao.insert(
            extRow("Disabled One", "bundled.bundled-disabled",
                enabled = false)
        )
        val fenrirLinkId = extDao.insert(
            extRow("FenrirRealm (link)", "live.fenrirealm.com")
        )
        val downId = extDao.insert(
            extRow("Down Site", "live.down.example")
        )

        // ── books ───────────────────────────────────────────
        // FenrirRealm novels wrongly stamped MANGA by the old
        // source-default rule; one webtoon likewise stamped.
        val fenrirNovelId = bookDao.insert(
            book("Some Fenrir Novel", fenrirId, kind = "MANGA")
        )
        val fenrirToonId = bookDao.insert(
            book("Some Fenrir Toon", fenrirId, kind = "MANGA")
        )
        val chikariBookId = bookDao.insert(
            book("Chikari Book", chikariId, kind = "TEXT")
        )
        val graduatedBookId = bookDao.insert(
            book("Graduated Book", fenrirLinkId)
        )
        val downBookId = bookDao.insert(book("Down Book", downId))
        val coverBookId = bookDao.insert(
            book("Coverless File", fenrirId,
                coverUrl = "https://fenrirealm.com/covers/x.jpg")
        )
        // F15-01: soft-removed, source gone, no local content —
        // WITH a History record. Must survive the sweep.
        val ghostBookId = bookDao.insert(
            book("Ghost Book", extensionId = 999_999L,
                inLibrary = false)
        )
        historyDao.upsert(
            HistoryRecord(
                bookKey = "ghost-key",
                bookId = ghostBookId,
                title = "Ghost Book",
                bookUrl = "ghost-book",
                sourceName = "Gone Source",
                recordedAt = 5L
            )
        )
        // One book with reading progress → History seeding.
        db.readingProgressDao().upsert(
            ReadingProgress(
                bookId = chikariBookId,
                chapterIndex = 2,
                chapterId = 0L,
                scrollFraction = 0.5f,
                updatedAt = 777L
            )
        )
        // One unstamped stored body → §12.6 demotion.
        val demoteChapterId = chapterDao.insert(
            Chapter(
                bookId = chikariBookId,
                index = 0,
                title = "Chapter 1",
                contentPath = "content/x/1.txt",
                wordCount = 900,
                downloadState = DownloadState.READY,
                updatedAt = 1L
            )
        )

        val store = FakeDataStore()
        store.seedFavouriteRow(chikariBookId)
        val metaPrefs = BookMetaPrefs(store)
        val keyForBook: suspend (Book) -> String = { b ->
            val pkg = b.extensionId?.let {
                extDao.getById(it)?.packageId
            }
            SyncKeys.bookKey(b, pkg)
        }
        var online = false
        var ratingsRan = false
        val pass = UpdatePass152(
            bookDao = bookDao,
            chapterDao = chapterDao,
            extensionDao = extDao,
            progressDao = db.readingProgressDao(),
            historyDao = historyDao,
            journalDao = journalDao,
            bookMetaPrefs = metaPrefs,
            historyPrefs = HistoryPrefs(store),
            keyForBook = keyForBook,
            versionCode = 44,
            shelfDao = db.shelfDao(),
            isBothKinds = { row ->
                row.packageId == "bundled.bundled-fenrirealm"
            },
            hostForRow = { row ->
                when {
                    row.packageId.startsWith("live.") ->
                        row.packageId.removePrefix("live.")
                    row.packageId.startsWith("link.") -> null
                    row.packageId == "bundled.bundled-fenrirealm" ->
                        "fenrirealm.com"
                    else -> null
                }
            },
            rederive = { row ->
                if (row.packageId == "live.down.example") {
                    UpdatePass152.RederiveOutcome.Failure(
                        "site unreachable"
                    )
                } else {
                    UpdatePass152.RederiveOutcome.Success(row.id)
                }
            },
            fetchCoverFile = { true },
            ratingBackfill = { ratingsRan = true },
            isOnline = { online }
        )

        // ── PHASE 0 ─────────────────────────────────────────
        val started = System.currentTimeMillis()
        val phase0 = pass.runPhase0()
        val elapsed = System.currentTimeMillis() - started
        assertTrue("Phase 0 took ${elapsed}ms (bound 5s)", elapsed < 5_000)

        assertEquals(1, phase0.renamed)           // "Home" → BotiTranslation
        assertEquals(1, phase0.graduated)         // live fenrir → bundled
        assertEquals(1, phase0.refreshed)
        assertEquals(1, phase0.favouritesConverted)
        assertEquals(1, phase0.bodiesDemoted)
        assertEquals(1, phase0.historySeeded)
        assertEquals(1, phase0.keptNeedsRepair)   // the Repaired row
        assertEquals(2, phase0.kindReset)         // both Fenrir stamps
        assertTrue(phase0.kindUnknown >= 2)

        val boti = extDao.getById(botiId)!!
        assertEquals("BotiTranslation", boti.displayName)
        // Graduation: the link row is gone; its book re-pointed.
        assertNull(extDao.getById(fenrirLinkId))
        assertEquals(
            fenrirId, bookDao.getById(graduatedBookId)!!.extensionId
        )
        // Kind reset: stamps withdrawn, books file Novels-side
        // until first refresh lands tier-1.
        assertNull(bookDao.getById(fenrirNovelId)!!.kind)
        assertNull(bookDao.getById(fenrirToonId)!!.kind)
        // Single-kind source stamps are NOT touched.
        assertEquals("TEXT", bookDao.getById(chikariBookId)!!.kind)
        // Demotion landed on the unstamped body.
        assertEquals(
            "AUTOMATIC",
            chapterDao.forBook(chikariBookId)
                .first { it.id == demoteChapterId }.bodyOrigin
        )
        // The F15-01 shape survived the sweep.
        assertNotNull(bookDao.getById(ghostBookId))
        // Journal truth: link-added rows are PENDING, never
        // validated by Phase 0; the repaired row is kept.
        val journal = journalDao.forVersion(44)
            .associateBy { it.providerKey }
        assertEquals("pending", journal["live.botitranslation.com"]!!.outcome)
        assertEquals("pending", journal["live.down.example"]!!.outcome)
        assertEquals("pending", journal["link.bundled-chikari"]!!.outcome)
        assertEquals(
            "kept-needs-repair", journal["bundled.bundled-repaired"]!!.outcome
        )
        assertEquals("validated", journal["bundled.bundled-fenrirealm"]!!.outcome)
        assertEquals("complete", journal[UpdatePass152.PHASE0_MARKER]!!.outcome)

        // Completion rule: a completed pass does not re-run.
        val again = pass.runPhase0()
        assertTrue(again.alreadyCompleted)
        assertEquals(0, again.renamed)

        // ── DEFERRED, OFFLINE: nothing attempted, nothing
        // recorded ──────────────────────────────────────────
        val offline = pass.runDeferred()
        assertTrue(offline.skippedOffline)
        assertFalse(extDao.getById(downId)!!.needsRepair)
        assertEquals(
            "pending",
            journalDao.forVersion(44)
                .first { it.providerKey == "live.down.example" }.outcome
        )

        // ── DEFERRED, ONLINE: the down site is kept and marked
        online = true
        val deferred = pass.runDeferred()
        assertFalse(deferred.skippedOffline)
        assertEquals(1, deferred.keptNeedsRepair)
        assertTrue(deferred.rederived >= 2) // boti + chikari succeed
        assertEquals(1, deferred.coversBackfilled)
        assertTrue(ratingsRan)
        assertTrue(deferred.completed)
        val down = extDao.getById(downId)!!
        assertTrue(down.needsRepair)
        assertEquals("site unreachable", down.needsRepairReason)
        assertNotNull(bookDao.getById(downBookId)) // record KEPT
        assertEquals(
            "kept-needs-repair",
            journalDao.forVersion(44)
                .first { it.providerKey == "live.down.example" }.outcome
        )
        val line = deferred.summaryLine(
            deferred.rederived + phase0.refreshed, phase0.renamed
        )
        assertTrue(line.startsWith("Sources updated:"))
        assertTrue(line.contains("1 kept and marked needs repair"))
        db.close()
    }

    @Test
    fun `sweep predicate - the F15-01 shape can never complete`() {
        // History record present → all other terms false still
        // cannot complete (the §15.3 protection).
        assertFalse(
            UpdatePass152.sweepMayComplete(
                hasShelfMembership = false,
                inLibrary = false,
                hasLiveSource = false,
                hasLocalContent = false,
                hasHistoryRecord = true
            )
        )
        // Only the fully-orphaned shape may complete.
        assertTrue(
            UpdatePass152.sweepMayComplete(
                hasShelfMembership = false,
                inLibrary = false,
                hasLiveSource = false,
                hasLocalContent = false,
                hasHistoryRecord = false
            )
        )
    }
}
