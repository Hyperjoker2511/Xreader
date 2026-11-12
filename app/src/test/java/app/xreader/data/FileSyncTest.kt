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
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // -- v1.5.40: adversarial manifests (sync review P1 + P2) --------

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /** Plants a hand-made manifest + blobs on the backend, as a
     * crafted sync folder / backup would present them. */
    private fun plantManifest(
        backend: FakeBackend,
        sync: BookFileSync,
        entries: List<BookFileSync.FileEntry>,
        blobs: Map<String, ByteArray>
    ) {
        for ((syncPath, blob) in blobs) {
            backend.files[sync.remoteName(entries.first().bookKey, syncPath)] = blob
        }
        backend.files[BookFileSync.MANIFEST_NAME] =
            BookFileSync.encodeManifest(
                BookFileSync.FileManifest(entries = entries)
            ).toByteArray(Charsets.UTF_8)
    }

    @Test
    fun `pull refuses traversal sync paths in both vulnerable branches`() =
        runTest {
            val b = Device("victim").apply { seed(withFiles = false) }
            val backend = FakeBackend()
            val sync = b.fileSync()
            val key = SyncKeys.bookKey(b.bookDao.getById(b.bookId)!!, null)
            val blob = byteArrayOf(0x0B, 0x0A, 0x0D)
            // Both entries are integrity-VALID (size + hash match
            // the blob): only the containment check can stop them.
            val entries = listOf(
                BookFileSync.FileEntry(
                    key, "book-file/../../../escaped.epub",
                    blob.size.toLong(), sha256Hex(blob)
                ),
                BookFileSync.FileEntry(
                    key, "content/img-../../../../escaped.png",
                    blob.size.toLong(), sha256Hex(blob)
                )
            )
            plantManifest(
                backend, sync, entries,
                mapOf(
                    "book-file/../../../escaped.epub" to blob,
                    "content/img-../../../../escaped.png" to blob
                )
            )

            val report = (sync.pull(backend) as AppResult.Ok).value
            assertEquals(0, report.downloaded)
            assertEquals(2, report.skippedUnmatched)
            // Nothing landed at either escape target: the
            // book-file payload aimed above the device dir, the
            // image payload at the device dir root.
            assertFalse(File(temp.root, "escaped.epub").exists())
            assertFalse(File(b.dir, "escaped.png").exists())
            assertEquals(null, b.bookDao.getById(b.bookId)?.filePath)
        }

    @Test
    fun `pull and backup refuse paths that land on the folder itself`() =
        runTest {
            val b = Device("rooted").apply { seed(withFiles = false) }
            val sync = b.fileSync()
            val key = SyncKeys.bookKey(b.bookDao.getById(b.bookId)!!, null)
            // The folders the crafted entries aim at, standing
            // as directories before anything runs.
            val bookDir = File(b.dir, "books/${b.bookId}").apply { mkdirs() }
            val contentDir =
                File(b.dir, "content/${b.bookId}").apply { mkdirs() }
            val blob = byteArrayOf(0x0B, 0x0A, 0x0D)
            // Integrity-VALID entries (size + hash match) whose
            // paths land ON the book/content directory itself:
            // an empty rest, a "." rest, an img name that climbs
            // back to the content root. Containment must refuse
            // the root, not just escapes — before the fix these
            // reached placeFile, which threw on the directory
            // (aborting the pull, leaving a .tmp) or replaced an
            // empty folder with the blob file.
            val paths = listOf("book-file/", "book-file/.", "content/img-/..")
            val entries = paths.map {
                BookFileSync.FileEntry(
                    key, it, blob.size.toLong(), sha256Hex(blob)
                )
            }

            // Pull leg: all three refused, nothing thrown.
            val backend = FakeBackend()
            plantManifest(backend, sync, entries, paths.associateWith { blob })
            val pullReport = (sync.pull(backend) as AppResult.Ok).value
            assertEquals(0, pullReport.downloaded)
            assertEquals(3, pullReport.skippedUnmatched)

            // Backup leg: same manifest, staged integrity-valid
            // files under the entries' backup names.
            val staging = temp.newFolder("rooted-staged")
            val extracted = entries.mapIndexed { index, entry ->
                sync.backupEntryName(entry.bookKey, entry.syncPath) to
                    File(staging, "blob-$index").apply { writeBytes(blob) }
            }.toMap()
            val backupReport = sync.applyFromBackup(
                BookFileSync.FileManifest(entries = entries), extracted
            )
            assertEquals(0, backupReport.downloaded)
            assertEquals(3, backupReport.skippedUnmatched)

            // Both folders survived as directories, no staging
            // temp was left beside either, and no row was
            // pointed at a folder.
            assertTrue(bookDir.isDirectory)
            assertTrue(contentDir.isDirectory)
            assertFalse(File(b.dir, "books/${b.bookId}.tmp").exists())
            assertFalse(File(b.dir, "content/${b.bookId}.tmp").exists())
            assertEquals(null, b.bookDao.getById(b.bookId)?.filePath)
        }

    @Test
    fun `pull never places bytes that fail the manifest check`() = runTest {
        val b = Device("checker").apply { seed(withFiles = false) }
        val backend = FakeBackend()
        val sync = b.fileSync()
        val key = SyncKeys.bookKey(b.bookDao.getById(b.bookId)!!, null)
        // Entry 1: the blob is TRUNCATED — size and hash both
        // disagree with the manifest.
        // Entry 2: the blob is the right SIZE but different
        // bytes — only the hash can catch it.
        val entries = listOf(
            BookFileSync.FileEntry(
                key, "book-file/novel.epub",
                bookBytes.size.toLong(), sha256Hex(bookBytes)
            ),
            BookFileSync.FileEntry(
                key, "cover/cover.jpg",
                coverBytes.size.toLong(), sha256Hex(coverBytes)
            )
        )
        plantManifest(
            backend, sync, entries,
            mapOf(
                "book-file/novel.epub" to bookBytes.dropLast(1).toByteArray(),
                "cover/cover.jpg" to
                    ByteArray(coverBytes.size) { 0x11.toByte() }
            )
        )

        val report = (sync.pull(backend) as AppResult.Ok).value
        assertEquals(0, report.downloaded)
        assertEquals(2, report.skippedUnmatched)
        assertFalse(
            b.storage.resolve("books/${b.bookId}/novel.epub").exists()
        )
        assertFalse(b.storage.resolve("covers/${b.bookId}.jpg").exists())
        val book = b.bookDao.getById(b.bookId)!!
        assertEquals(null, book.filePath)
        assertEquals(null, book.coverPath)
    }

    @Test
    fun `backup restore never places a tampered staged file`() = runTest {
        val a = Device("src").apply { seed(withFiles = true) }
        val b = Device("dst").apply { seed(withFiles = false) }
        val (manifest, _) = a.fileSync().collectForBackup()
        val bookEntry = manifest.entries.first {
            it.syncPath.startsWith("book-file/")
        }
        // The staged bytes under the entry's name are NOT the
        // bytes the manifest describes.
        val staging = temp.newFolder("tampered")
        val tampered = File(staging, "book.epub")
        tampered.writeBytes(byteArrayOf(0x50, 0x4B, 0x03))
        val extracted = mapOf(
            a.fileSync().backupEntryName(bookEntry.bookKey, bookEntry.syncPath) to
                tampered
        )

        val report = b.fileSync().applyFromBackup(manifest, extracted)
        assertEquals(0, report.downloaded)
        assertEquals(1, report.skippedUnmatched)
        assertFalse(
            b.storage.resolve("books/${b.bookId}/novel.epub").exists()
        )
        assertEquals(null, b.bookDao.getById(b.bookId)?.filePath)
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
