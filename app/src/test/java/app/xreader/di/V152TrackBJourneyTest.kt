package app.xreader.di

import android.app.Application
import android.os.Looper
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
import app.xreader.data.download.ChapterTruth152
import app.xreader.data.sync.SyncKeys
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.extensionapi.ExtensionApi
import app.xreader.ui.book.BookDetailUiState
import app.xreader.ui.book.BookDetailViewModel
import app.xreader.ui.more.HistoryViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows.shadowOf

/**
 * v1.5.2 Track B (round-15) — the wiring journeys the round-1
 * re-scan demanded, over the REAL AppContainer, DAOs and
 * ViewModels (no policy-object shortcuts):
 *
 *  ITEM 9 — every "downloaded" surface agrees by construction,
 *           and the production Clean cache clears read bodies
 *           (never covers / USER downloads / in-flight parts)
 *           and reports honestly.
 *  ITEM 10 — mergeDuplicate performs the favourite UNION in
 *           production (driven through the real ViewModel).
 *  ITEM 11 — a stored stub's wordCount renders NOWHERE on the
 *           book page (the real ViewModel's stub detection).
 *  ITEM 12 — the History UI reads the history_records table
 *           (driven through the real HistoryViewModel): flat,
 *           record-only removal, store-only clear-all,
 *           Add back to Library; full deletion reaps temp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V152TrackBJourneyTest {

    private fun awaitCondition(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("timed out waiting for: $what", cond())
    }

    private suspend fun seedSource(container: AppContainer, pkg: String): Long {
        val db = container.database
        val repoId = db.extensionRepoDao().insert(
            ExtensionRepo(name = "Fixture-$pkg", indexUrl = "https://example.test/i")
        )
        return db.installedExtensionDao().insert(
            InstalledExtension(
                repoId = repoId, displayName = "Track B Source", packageId = pkg,
                versionName = "1.0", versionCode = 1,
                kind = ExtensionKind.DECLARATIVE, artifactPath = null,
                sha256 = "x", apiLevel = ExtensionApi.API_LEVEL,
                languages = "en", sourceIds = pkg, enabled = true,
                trustState = TrustState.TRUSTED, permissionsJson = "{}",
                installedAt = 1L, updatedAt = 1L
            )
        )
    }

    private fun bodyFile(container: AppContainer, bookId: Long, name: String, bytes: Int): Pair<File, String> {
        val dir = container.bookStorage.contentDir(bookId)
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes))
        return file to "content/$bookId/$name"
    }

    @Test
    fun `item 9 - surfaces agree and clean cache clears read bodies only`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val db = container.database
        val extId = seedSource(container, "bundled.bundled-trackb")
        val bookId = db.bookDao().insert(
            Book(
                title = "Surfaces Book", sourceType = SourceType.EXTENSION,
                extensionId = extId, remoteId = "surfaces-book",
                addedAt = 1L, updatedAt = 1L
            )
        )
        val (userFile, userPath) = bodyFile(container, bookId, "u.txt", 400)
        val (autoFile, autoPath) = bodyFile(container, bookId, "a.txt", 600)
        val userCh = db.chapterDao().insert(
            Chapter(
                bookId = bookId, index = 0, title = "One", contentPath = userPath,
                wordCount = 900, downloadState = DownloadState.READY,
                updatedAt = 1L, bodyOrigin = BodyOrigin152.USER
            )
        )
        val autoCh = db.chapterDao().insert(
            Chapter(
                bookId = bookId, index = 1, title = "Two", contentPath = autoPath,
                wordCount = 900, downloadState = DownloadState.READY,
                updatedAt = 2L, bodyOrigin = BodyOrigin152.AUTOMATIC
            )
        )
        val partialCh = db.chapterDao().insert(
            Chapter(
                bookId = bookId, index = 2, title = "Three", contentPath = null,
                wordCount = 0, downloadState = DownloadState.PARTIAL,
                updatedAt = 3L, bodyOrigin = null
            )
        )
        // ── every surface, one answer: exactly ONE download ──
        assertEquals(1, db.chapterDao().downloadedCount(bookId))
        val counts = db.chapterDao().observeDownloadCounts().first()
            .first { it.bookId == bookId }
        assertEquals(1, counts.downloaded)
        val chapters = db.chapterDao().forBook(bookId)
        assertEquals(
            1,
            BookDetailUiState(chapters = chapters).downloadedCount
        )
        assertEquals(
            ChapterTruth152.RowMark.DOWNLOADED,
            ChapterTruth152.rowMark(chapters.first { it.id == userCh })
        )
        assertEquals(
            ChapterTruth152.RowMark.CACHED,
            ChapterTruth152.rowMark(chapters.first { it.id == autoCh })
        )
        assertEquals(
            ChapterTruth152.RowMark.PARTIAL,
            ChapterTruth152.rowMark(chapters.first { it.id == partialCh })
        )

        // ── Clean cache over the production container ────────
        val coverFile = File(container.bookStorage.coversDir(), "$bookId.jpg")
        coverFile.parentFile?.mkdirs()
        coverFile.writeBytes(ByteArray(123))
        db.bookDao().update(
            db.bookDao().getById(bookId)!!.copy(coverPath = "covers/$bookId.jpg")
        )
        val doneItem = db.downloadQueueDao().insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = userCh, state = QueueState.DONE,
                enqueuedAt = 1L, updatedAt = 1L
            )
        )
        val liveItem = db.downloadQueueDao().insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = autoCh, state = QueueState.RUNNING,
                enqueuedAt = 2L, updatedAt = 2L
            )
        )
        val tempDir = container.bookStorage.downloadsTempDir()
        tempDir.mkdirs()
        val donePart = File(tempDir, "queue-$doneItem.part").apply { writeBytes(ByteArray(10)) }
        val livePart = File(tempDir, "queue-$liveItem.part").apply { writeBytes(ByteArray(10)) }

        val result = container.cleanCache()
        assertTrue(
            "clean cache failures: ${result.failures}",
            result.failures.none { "I/O" in it }
        )
        // AUTOMATIC body: file AND row cleared together.
        assertFalse(autoFile.exists())
        assertNull(db.chapterDao().getById(autoCh)!!.contentPath)
        // USER download survives untouched.
        assertTrue(userFile.exists())
        assertEquals(userPath, db.chapterDao().getById(userCh)!!.contentPath)
        assertEquals(1, db.chapterDao().downloadedCount(bookId))
        // Per-book asset (cover) survives.
        assertTrue(coverFile.exists())
        // Terminal part reaped; IN-FLIGHT part protected (§12.1).
        assertFalse(donePart.exists())
        assertTrue(livePart.exists())
    }

    @Test
    fun `item 12 - full deletion reaps the book's temp residue`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val db = container.database
        val extId = seedSource(container, "bundled.bundled-trackb-del")
        val bookId = db.bookDao().insert(
            Book(
                title = "Doomed Book", sourceType = SourceType.EXTENSION,
                extensionId = extId, remoteId = "doomed-book",
                addedAt = 1L, updatedAt = 1L
            )
        )
        val item = db.downloadQueueDao().insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = null, state = QueueState.DONE,
                enqueuedAt = 1L, updatedAt = 1L
            )
        )
        val tempDir = container.bookStorage.downloadsTempDir()
        tempDir.mkdirs()
        val part = File(tempDir, "queue-$item.part").apply { writeBytes(ByteArray(10)) }
        val otherPart = File(tempDir, "queue-999999.part").apply { writeBytes(ByteArray(10)) }
        container.deleteBookFully(db.bookDao().getById(bookId)!!)
        assertFalse(part.exists())
        assertTrue(otherPart.exists())
        assertTrue(db.downloadQueueDao().allForBook(bookId).isEmpty())
    }

    @Test
    fun `item 12 - the History UI reads the records table end to end`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val db = container.database
        val extId = seedSource(container, "bundled.bundled-trackb-hist")
        suspend fun seedBook(title: String, remote: String): Long = db.bookDao().insert(
            Book(
                title = title, sourceType = SourceType.EXTENSION,
                extensionId = extId, remoteId = remote,
                addedAt = 1L, updatedAt = 1L, inLibrary = false
            )
        )
        val firstId = seedBook("First Read", "first-read")
        val secondId = seedBook("Second Read", "second-read")
        // A USER download on the first book: History actions must
        // never touch it (separate domains).
        val (dlFile, dlPath) = bodyFile(container, firstId, "dl.txt", 100)
        db.chapterDao().insert(
            Chapter(
                bookId = firstId, index = 0, title = "One", contentPath = dlPath,
                wordCount = 700, downloadState = DownloadState.READY,
                updatedAt = 1L, bodyOrigin = BodyOrigin152.USER
            )
        )
        container.recordHistoryStart(firstId)
        Thread.sleep(5)
        container.recordHistoryStart(secondId)

        val vm = HistoryViewModel(container)
        val collectScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default
        )
        val collectJob = collectScope.launch { vm.entries.collect { } }
        awaitCondition("history entries load") { vm.entries.value.size == 2 }
        // Flat, most-recent-first, straight from the table.
        assertEquals(
            listOf("Second Read", "First Read"),
            vm.entries.value.map { it.title }
        )
        // Remove-from-History through the VM: ONLY the record goes.
        val firstEntry = vm.entries.value.first { it.title == "First Read" }
        vm.removeEntry(firstEntry.bookKey)
        awaitCondition("record deleted via VM") {
            runBlocking { db.historyRecordDao().getByKey(firstEntry.bookKey) } == null
        }
        assertNotNull(db.bookDao().getById(firstId))
        assertEquals(1, db.chapterDao().downloadedCount(firstId))
        assertTrue(dlFile.exists())
        // Add back to Library through the VM (the §15.1 flip).
        container.removeFromLibrarySoft(db.bookDao().getById(secondId)!!)
        assertFalse(db.bookDao().getById(secondId)!!.inLibrary)
        vm.addBackToLibrary(secondId)
        awaitCondition("add back flips inLibrary") {
            runBlocking { db.bookDao().getById(secondId)?.inLibrary } == true
        }
        // Clear-all through the VM wipes the store ONLY.
        vm.clearAll()
        awaitCondition("history store wiped via VM") {
            runBlocking { db.historyRecordDao().count() } == 0
        }
        assertNotNull(db.bookDao().getById(secondId))
        assertEquals(1, db.chapterDao().downloadedCount(firstId))
        collectJob.cancel()
    }

    @Test
    fun `item 10 - mergeDuplicate performs the favourite union in production`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val db = container.database
        val extId = seedSource(container, "bundled.bundled-trackb-merge")
        // The duplicate lives on ANOTHER source (the BOTI shape:
        // same book, two providers) — its natural key DIFFERS from
        // the survivor's, so only the production UNION transfer
        // can move the favourite across.
        val otherExtId = seedSource(container, "live.other.example")
        val survivorId = db.bookDao().insert(
            Book(
                title = "Same Book", sourceType = SourceType.EXTENSION,
                extensionId = extId, remoteId = "same-book",
                addedAt = 1L, updatedAt = 1L
            )
        )
        val duplicateId = db.bookDao().insert(
            Book(
                title = "Same Book (copy)", sourceType = SourceType.EXTENSION,
                extensionId = otherExtId, remoteId = "same-book",
                addedAt = 2L, updatedAt = 2L
            )
        )
        val survivor = db.bookDao().getById(survivorId)!!
        val duplicate = db.bookDao().getById(duplicateId)!!
        val survivorKey = SyncKeys.bookKey(survivor, "bundled.bundled-trackb-merge")
        val duplicateKey = SyncKeys.bookKey(duplicate, "live.other.example")
        assertTrue(survivorKey != duplicateKey)
        // ONLY the duplicate is favourited. After the production
        // merge the survivor holds the favourite and the
        // duplicate's key is gone — the union, not a coin flip
        // over row order (round 1's exact failure).
        container.bookMetaPrefs.setFavouriteByKey(duplicateKey, true)
        val vm = BookDetailViewModel(container, survivorId)
        vm.mergeDuplicate(duplicateId)
        awaitCondition("merge lands + favourite union transfers") {
            runBlocking {
                val dup = db.bookDao().getById(duplicateId)
                val favs = container.bookMetaPrefs.favouritesByKey.first()
                dup?.mergedIntoId == survivorId &&
                    survivorKey in favs && duplicateKey !in favs
            }
        }
        val favs = container.bookMetaPrefs.favouritesByKey.first()
        assertTrue(survivorKey in favs)
        assertFalse(duplicateKey in favs)
    }

    @Test
    fun `item 11 - a stored stub renders no word count on the book page`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val container = AppContainer(app)
        val db = container.database
        val extId = seedSource(container, "bundled.bundled-trackb-stub")
        val bookId = db.bookDao().insert(
            Book(
                title = "Stubbed Book", sourceType = SourceType.EXTENSION,
                extensionId = extId, remoteId = "stubbed-book",
                addedAt = 1L, updatedAt = 1L
            )
        )
        // A stub body stored by an older build, wordCount and all
        // (the exhibited 64-word shape).
        val stub = ChapterContent(
            blocks = listOf(
                ChapterBlock.Paragraph.plain(
                    "Please sign in to read this chapter. Sign in to continue, members only."
                )
            )
        )
        val chapterId = db.chapterDao().insert(
            Chapter(
                bookId = bookId, index = 0, title = "Chapter 1",
                contentPath = null, wordCount = 64,
                downloadState = DownloadState.READY, updatedAt = 1L,
                bodyOrigin = BodyOrigin152.AUTOMATIC
            )
        )
        val saved2 = container.bookStorage.writeChapterContent(bookId, chapterId, stub)
        val path = (saved2 as app.xreader.core.error.AppResult.Ok).value
        db.chapterDao().update(
            db.chapterDao().getById(chapterId)!!.copy(contentPath = path)
        )
        val vm = BookDetailViewModel(container, bookId)
        val collectScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Default
        )
        val collectJob = collectScope.launch { vm.state.collect { } }
        awaitCondition("stub detected by the book-page VM") {
            vm.state.value.stubChapterIds.contains(chapterId)
        }
        collectJob.cancel()
        val state = vm.state.value
        val chapter = state.chapters.first { it.id == chapterId }
        // The stored 64 must not render anywhere the state feeds:
        // display count is 0, so the row, the reading-time estimate
        // and any aggregate over display counts all see nothing.
        assertEquals(0, ChapterTruth152.displayWordCount(chapter, state.stubChapterIds))
        assertTrue(ChapterTruth152.isStubBody(stub.plainText()))
    }
}
