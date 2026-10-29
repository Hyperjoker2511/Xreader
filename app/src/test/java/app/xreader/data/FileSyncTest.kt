package app.xreader.data

import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.files.BookStorage
import app.xreader.data.sync.BackupArchive
import app.xreader.data.sync.BookFileSync
import app.xreader.data.sync.SyncBackend
import app.xreader.data.sync.SyncKeys
import app.xreader.data.sync.SyncPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 6 gate (PLAN §8): the opt-in book-file channel that closes
 * the Phase 5 gap. Two temp-dir "devices" share one in-memory
 * backend: binary files (deliberately invalid UTF-8) must arrive
 * byte-identical, unchanged files must not move twice, books the
 * other device doesn't have are skipped and counted, and the
 * backup-archive path round-trips the same files.
 */
class FileSyncTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class FakeBackend : SyncBackend {
        override val id: String = "fake"
        override val label: String = "Fake backend"
        val files = mutableMapOf<String, ByteArray>()
        var blobWrites = 0

        override suspend fun read(name: String): AppResult<ByteArray?> =
            AppResult.Ok(files[name])

        override suspend fun write(name: String, bytes: ByteArray): AppResult<String?> {
            files[name] = bytes
            if (name != BookFileSync.MANIFEST_NAME) blobWrites++
            return AppResult.Ok(null)
        }
    }

    private val bookBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0xFF.toByte(), 0x00, 0xFE.toByte(), 0x61)
    private val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x42)
    private val chapterJson =
        """{"blocks":[{"type":"paragraph","text":"Hello world again"}]}"""

    /** A device: daos + storage + one book with file, cover, chapter. */
    private inner class Device(name: String) {
        val dir: File = temp.newFolder(name)
        val storage = BookStorage(dir)
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        var bookId: Long = -1
        var chapterId: Long = -1

        suspend fun seed(withFiles: Boolean) {
            bookId = bookDao.insert(
                Book(
                    title = "Synced Novel", author = "Sync Author",
                    totalChapters = 1, addedAt = 1, updatedAt = 1,
                    filePath = if (withFiles) "books/1/novel.epub" else null,
                    coverPath = if (withFiles) "covers/1.jpg" else null
                )
            )
            chapterId = chapterDao.insert(
                Chapter(
                    bookId = bookId, index = 0, title = "Chapter 1",
                    updatedAt = 1
                )
            )
            if (withFiles) {
                val bookFile = storage.resolve("books/$bookId/novel.epub")
                bookFile.parentFile?.mkdirs()
                bookFile.writeBytes(bookBytes)
                val cover = storage.resolve("covers/$bookId.jpg")
                cover.parentFile?.mkdirs()
                cover.writeBytes(coverBytes)
                val content = storage.resolve("content/$bookId/$chapterId.json")
                content.parentFile?.mkdirs()
                content.writeText(chapterJson)
                chapterDao.setContent(
                    chapterId, "content/$bookId/$chapterId.json", 3,
                    app.xreader.data.db.DownloadState.READY
                )
            }
        }

        fun fileSync(): BookFileSync = BookFileSync(
            bookDao, chapterDao, storage,
            keyProvider = { book -> SyncKeys.bookKey(book, null) },
            clock = { 5_000L }
        )
    }

    @Test
    fun `push then pull moves binary files byte-identically`() = runTest {
        val a = Device("a").apply { seed(withFiles = true) }
        val b = Device("b").apply { seed(withFiles = false) }
        val backend = FakeBackend()

        val push = a.fileSync().push(backend)
        assertTrue(push is AppResult.Ok)
        assertEquals(3, (push as AppResult.Ok).value.uploaded)

        val pull = b.fileSync().pull(backend)
        assertTrue(pull is AppResult.Ok)
        assertEquals(3, (pull as AppResult.Ok).value.downloaded)

        assertArrayEquals(
            bookBytes,
            b.storage.resolve("books/${b.bookId}/novel.epub").readBytes()
        )
        assertArrayEquals(
            coverBytes,
            b.storage.resolve("covers/${b.bookId}.jpg").readBytes()
        )
        val chapter = b.chapterDao.getByIndex(b.bookId, 0)!!
        assertEquals(chapterJson, b.storage.resolve(chapter.contentPath!!).readText())
        // The pulled book file AND cover are pointed at by the book
        // row — placing several files for one book must not let a
        // later placement clobber an earlier pointer.
        assertEquals(
            "books/${b.bookId}/novel.epub",
            b.bookDao.getById(b.bookId)?.filePath
        )
        assertEquals(
            "covers/${b.bookId}.jpg",
            b.bookDao.getById(b.bookId)?.coverPath
        )
    }

    @Test
    fun `a second push uploads nothing new`() = runTest {
        val a = Device("a").apply { seed(withFiles = true) }
        val backend = FakeBackend()
        a.fileSync().push(backend)
        val writesAfterFirst = backend.blobWrites
        val second = a.fileSync().push(backend)
        assertEquals(0, (second as AppResult.Ok).value.uploaded)
        assertEquals(writesAfterFirst, backend.blobWrites)
    }

    @Test
    fun `files for books this device doesn't have are skipped and counted`() =
        runTest {
            val a = Device("a").apply { seed(withFiles = true) }
            val backend = FakeBackend()
            a.fileSync().push(backend)

            val stranger = Device("c") // no books at all
            val pull = stranger.fileSync().pull(backend)
            val report = (pull as AppResult.Ok).value
            assertEquals(0, report.downloaded)
            assertEquals(3, report.skippedUnmatched)
        }

    @Test
    fun `backup archive carries the files and restores them`() = runTest {
        val a = Device("a").apply { seed(withFiles = true) }
        val b = Device("b").apply { seed(withFiles = false) }

        val (manifest, files) = a.fileSync().collectForBackup()
        assertEquals(3, manifest.entries.size)
        val zipOut = ByteArrayOutputStream()
        BackupArchive.writeWithFiles(
            SyncPayload(), BookFileSync.encodeManifest(manifest), files, zipOut
        )

        // Extract like SyncService does: files/ entries to staging.
        val staging = b.fileSync().newStagingDir()
        val extracted = mutableMapOf<String, File>()
        val restored = BackupArchive.readWithFiles(
            ByteArrayInputStream(zipOut.toByteArray())
        ) { entryName, stream ->
            val target = File(staging, entryName.removePrefix("files/"))
            target.parentFile?.mkdirs()
            target.outputStream().use { stream.copyTo(it) }
            extracted[entryName] = target
        }
        assertTrue(restored is AppResult.Ok)

        val report = b.fileSync().applyFromBackup(manifest, extracted)
        assertEquals(3, report.downloaded)
        assertArrayEquals(
            bookBytes,
            b.storage.resolve("books/${b.bookId}/novel.epub").readBytes()
        )
    }

    @Test
    fun `with no local files the channel moves nothing`() = runTest {
        // The opt-in OFF world: a device that never collects files
        // has an empty manifest, so nothing is ever written for it.
        val empty = Device("empty")
        val (manifest, files) = empty.fileSync().collectForBackup()
        assertTrue(manifest.entries.isEmpty())
        assertTrue(files.isEmpty())
        val backend = FakeBackend()
        val push = empty.fileSync().push(backend)
        assertEquals(0, (push as AppResult.Ok).value.uploaded)
    }
}
