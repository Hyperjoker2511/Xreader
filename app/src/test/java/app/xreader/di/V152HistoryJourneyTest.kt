package app.xreader.di

import android.app.Application
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.DownloadState
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.QueueState
import app.xreader.data.db.SourceType
import app.xreader.data.db.TrustState
import app.xreader.data.download.BodyOrigin152
import app.xreader.data.sync.SyncKeys
import app.xreader.extensionapi.ExtensionApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * v1.5.2 (FIX-9, §15 / §10 item 12): the History model over the
 * REAL AppContainer operations — a read start writes the ONE
 * per-book record; soft removal flips inLibrary only, frees
 * AUTOMATIC bodies and keeps USER downloads (History and
 * downloads are separate domains); removing the History entry
 * deletes ONLY the record; re-reading writes a FRESH record;
 * full deletion takes everything of the book's — queue items
 * in every state (DONE included), per-book reader preferences,
 * the History record — and leaves a tombstone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V152HistoryJourneyTest {

    @Test
    fun `the full section 15 journey`() = runBlocking {
        // v1.5.3 (gate-repair): the journey's steps are direct
        // suspend calls, so the body runs under a hard budget —
        // gate run 1 parked on this method for ~105 minutes when
        // a step stalled inside the long-lived worker JVM
        // (build-logs/V153-GATE-RUN1-threaddump.txt); every
        // sibling harness already awaits with a deadline. The
        // bound changes NO assertion: a step that cannot finish
        // inside it now FAILS the test — bounded and diagnosable
        // — instead of eating the gate. Verified green isolated
        // and in the sdk-35 AppContainer family company before
        // the bound was added (isolated runtime ~0.5–16 s).
        withTimeout(JOURNEY_BUDGET_MS) { journey() }
    }

    private suspend fun journey() {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val db = container.database
        db.extensionRepoDao().insert(
            ExtensionRepo(name = "Fixture", indexUrl = "https://example.test/i")
        )
        val extId = db.installedExtensionDao().insert(
            InstalledExtension(
                repoId = 1, displayName = "Hist Source",
                packageId = "bundled.bundled-hist",
                versionName = "1.0", versionCode = 1,
                kind = ExtensionKind.DECLARATIVE, artifactPath = null,
                sha256 = "x", apiLevel = ExtensionApi.API_LEVEL,
                languages = "en", sourceIds = "hist", enabled = true,
                trustState = TrustState.TRUSTED, permissionsJson = "{}",
                installedAt = 1L, updatedAt = 1L
            )
        )
        val bookId = db.bookDao().insert(
            Book(
                title = "Journey Book",
                sourceType = SourceType.EXTENSION,
                extensionId = extId, remoteId = "journey-book",
                addedAt = 1L, updatedAt = 1L, inLibrary = false
            )
        )
        val book = db.bookDao().getById(bookId)!!
        val key = SyncKeys.bookKey(book, "bundled.bundled-hist")
        val userChapter = db.chapterDao().insert(
            Chapter(
                bookId = bookId, index = 0, title = "One",
                contentPath = "content/$bookId/1.txt", wordCount = 800,
                downloadState = DownloadState.READY, updatedAt = 1L,
                bodyOrigin = BodyOrigin152.USER
            )
        )
        val autoChapter = db.chapterDao().insert(
            Chapter(
                bookId = bookId, index = 1, title = "Two",
                contentPath = "content/$bookId/2.txt", wordCount = 800,
                downloadState = DownloadState.READY, updatedAt = 1L,
                bodyOrigin = BodyOrigin152.AUTOMATIC
            )
        )

        // ── read start: ONE record, NO membership flip ─────
        // v1.5.3 (finding #9, owner decision 2026-11-04):
        // §15.1's read-equals-membership doctrine is superseded
        // — a read start writes the History record and the book
        // STAYS out of the Library until a deliberate Add.
        container.recordHistoryStart(bookId)
        var record = db.historyRecordDao().getByKey(key)
        assertNotNull(record)
        assertEquals("Hist Source", record!!.sourceName)
        assertFalse(db.bookDao().getById(bookId)!!.inLibrary)
        container.recordHistoryStart(bookId)
        assertEquals(1, db.historyRecordDao().count())
        val firstStamp = record.recordedAt

        // ── soft removal: Library flip + cache freed, USER
        // download and History untouched (separate domains) ──
        container.removeFromLibrarySoft(db.bookDao().getById(bookId)!!)
        assertFalse(db.bookDao().getById(bookId)!!.inLibrary)
        assertEquals(
            BodyOrigin152.USER,
            db.chapterDao().getById(userChapter)!!.bodyOrigin
        )
        assertEquals(
            DownloadState.READY,
            db.chapterDao().getById(userChapter)!!.downloadState
        )
        assertNull(db.chapterDao().getById(autoChapter)!!.contentPath)
        assertNotNull(db.historyRecordDao().getByKey(key))

        // ── remove from History: ONLY the record goes ───────
        db.historyRecordDao().deleteByKey(key)
        assertNull(db.historyRecordDao().getByKey(key))
        assertNotNull(db.bookDao().getById(bookId))
        assertEquals(
            DownloadState.READY,
            db.chapterDao().getById(userChapter)!!.downloadState
        )

        // ── re-reading writes a FRESH record ────────────────
        // (and STILL no membership flip, v1.5.3 doctrine)
        container.recordHistoryStart(bookId)
        record = db.historyRecordDao().getByKey(key)
        assertNotNull(record)
        assertTrue(record!!.recordedAt >= firstStamp)
        assertFalse(db.bookDao().getById(bookId)!!.inLibrary)

        // ── an explicit Add is the membership act ───────────
        container.sourceBookSync.markInLibrary(bookId)
        assertTrue(db.bookDao().getById(bookId)!!.inLibrary)

        // ── full deletion: everything of the book's goes ────
        db.downloadQueueDao().insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = userChapter,
                state = QueueState.DONE, enqueuedAt = 1L, updatedAt = 1L
            )
        )
        db.downloadQueueDao().insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = autoChapter,
                state = QueueState.QUEUED, enqueuedAt = 2L, updatedAt = 2L
            )
        )
        container.bookReaderPrefs.setFontSize(bookId, 19f)
        assertEquals(
            19f,
            container.bookReaderPrefs.overrides(bookId).first().fontSizeSp
        )
        container.deleteBookFully(db.bookDao().getById(bookId)!!)
        assertNull(db.bookDao().getById(bookId))
        assertNull(db.historyRecordDao().getByKey(key))
        assertTrue(db.downloadQueueDao().allForBook(bookId).isEmpty())
        assertTrue(
            container.bookReaderPrefs.overrides(bookId).first().isEmpty
        )
        assertTrue(
            container.deletedBookKeysStore.all().any { it.bookKey == key }
        )
    }

    private companion object {
        /** Generous against the measured isolated runtime and
         * the suite's ~70 s cold-start precedent in a sibling
         * harness; a step needing longer is broken, not slow. */
        const val JOURNEY_BUDGET_MS = 180_000L
    }
}
