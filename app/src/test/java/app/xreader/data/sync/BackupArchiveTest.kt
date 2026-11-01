package app.xreader.data.sync

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local backup archive (PLAN §7 gate): the no-account path. A
 * payload written into the archive comes back identical, and bytes
 * that aren't an Xreader backup are refused plainly.
 */
class BackupArchiveTest {

    private fun samplePayload() = SyncPayload(
        deviceId = "dev-9",
        exportedAt = 777,
        books = listOf(
            BookRecord(
                syncKey = "local:star fall|sky writer|3",
                title = "Star Fall", author = "Sky Writer",
                totalChapters = 3, updatedAt = 5
            )
        ),
        progress = listOf(
            ProgressRecord("local:star fall|sky writer|3", 2, 120, updatedAt = 6)
        ),
        bookmarks = listOf(
            BookmarkRecord("local:star fall|sky writer|3", 1, 42, label = "fav")
        ),
        lore = listOf(
            LoreRecord("local:star fall|sky writer|3", "CHARACTER", "Ara")
        )
    )

    @Test
    fun `archive round trip preserves the payload`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(samplePayload(), out)
        val result = BackupArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertTrue("expected Ok, got $result", result is AppResult.Ok)
        assertEquals(samplePayload(), (result as AppResult.Ok).value)
    }

    @Test
    fun `foreign bytes are refused as a BadFile`() {
        val result = BackupArchive.read(
            ByteArrayInputStream("this is not a backup".toByteArray())
        )
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.BadFile)
    }

    @Test
    fun `a zip without the xreader manifest is refused`() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("xreader-backup.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        val result = BackupArchive.read(ByteArrayInputStream(out.toByteArray()))
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.BadFile)
    }

    private fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tooLargeReason(result: AppResult<*>): String {
        assertTrue("expected Err, got $result", result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue("expected BadFile, got $error", error is AppError.BadFile)
        return (error as AppError.BadFile).reason
    }

    @Test
    fun `a metadata entry past the size cap is refused as too large`() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(samplePayload(), out)
        // The real payload JSON is far bigger than a 16-byte cap:
        // the read must stop at the cap, not decode whatever came.
        val result = BackupArchive.read(
            ByteArrayInputStream(out.toByteArray()),
            maxEntries = 100,
            maxMetadataBytes = 16
        )
        assertTrue(tooLargeReason(result).contains("too large"))
    }

    @Test
    fun `an archive with too many entries is refused as too large`() {
        val bytes = zipOf(
            listOf(
                BackupArchive.MANIFEST_ENTRY to
                    """{"app":"xreader-backup"}""".toByteArray(),
                BackupArchive.PAYLOAD_ENTRY to
                    SyncPayload.encode(samplePayload()).toByteArray(),
                "extra.txt" to "x".toByteArray()
            )
        )
        val result = BackupArchive.read(
            ByteArrayInputStream(bytes),
            maxEntries = 2,
            maxMetadataBytes = 1_000_000
        )
        assertTrue(tooLargeReason(result).contains("too large"))
    }

    @Test
    fun `a files restore aborts mid-stream when the total size cap is crossed`() {
        val bytes = zipOf(
            listOf(
                BackupArchive.MANIFEST_ENTRY to
                    """{"app":"xreader-backup"}""".toByteArray(),
                BackupArchive.PAYLOAD_ENTRY to
                    SyncPayload.encode(samplePayload()).toByteArray(),
                "files/a.bin" to ByteArray(100) { 1 },
                "files/b.bin" to ByteArray(100) { 2 }
            )
        )
        val sunk = mutableMapOf<String, ByteArray>()
        val result = BackupArchive.readWithFiles(
            input = ByteArrayInputStream(bytes),
            fileSink = { name, stream ->
                sunk[name] = stream.readBytes()
            },
            maxEntries = 100,
            maxMetadataBytes = 1_000_000,
            maxEntryBytes = 1_000_000,
            maxTotalBytes = 150
        )
        // a.bin (100 bytes) fit the 150 budget and arrived whole;
        // b.bin crossed it mid-copy, so the restore is an Err and
        // the payload is never handed back for a partial merge.
        assertEquals(100, sunk["files/a.bin"]?.size)
        assertTrue(tooLargeReason(result).contains("too large"))
    }

    @Test
    fun `a small backup with files still restores fully`() {
        val bookFile = File.createTempFile("book", ".epub")
        bookFile.writeBytes(byteArrayOf(9, 8, 7))
        try {
            val out = ByteArrayOutputStream()
            BackupArchive.writeWithFiles(
                samplePayload(),
                """{"files":[]}""",
                listOf("files/book.epub" to bookFile),
                out
            )
            val sunk = mutableMapOf<String, ByteArray>()
            val result = BackupArchive.readWithFiles(
                ByteArrayInputStream(out.toByteArray())
            ) { name, stream -> sunk[name] = stream.readBytes() }
            assertTrue("expected Ok, got $result", result is AppResult.Ok)
            assertEquals(
                samplePayload(),
                (result as AppResult.Ok).value.first
            )
            assertEquals(
                listOf<Byte>(9, 8, 7),
                sunk["files/book.epub"]?.toList()
            )
        } finally {
            bookFile.delete()
        }
    }
}
