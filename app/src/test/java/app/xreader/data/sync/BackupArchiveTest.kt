package app.xreader.data.sync

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
}
