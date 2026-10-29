package app.xreader.data.sync

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** On-phone backup rules (v1.0.1): naming, recognition, ordering. */
class BackupLogicTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    @Test
    fun `file names are timestamped and recognisable`() {
        // 2026-10-26T14:50:00Z
        val name = BackupLogic.fileName(1_793_026_200_000L, utc)
        assertEquals("xreader-backup-2026-10-26-1450.zip", name)
        assertTrue(BackupLogic.isBackupName(name))
        assertFalse(BackupLogic.isBackupName("xreader-backup-old.txt"))
        assertFalse(BackupLogic.isBackupName("other-backup-2026.zip"))
    }

    @Test
    fun `restore list is newest first and filters strangers`() {
        val entries = listOf(
            BackupLogic.BackupEntry("xreader-backup-2026-10-20-0900.zip", "a", 10, 100),
            BackupLogic.BackupEntry("notes.txt", "x", 10, 999),
            BackupLogic.BackupEntry("xreader-backup-2026-10-28-0900.zip", "b", 10, 300),
            BackupLogic.BackupEntry("xreader-backup-2026-10-25-0900.zip", "c", 10, 200)
        )
        assertEquals(
            listOf("b", "c", "a"),
            BackupLogic.newestFirst(entries).map { it.documentId }
        )
    }

    @Test
    fun `last backup line is plain`() {
        assertEquals(
            "No backup yet",
            BackupLogic.describeLastBackup(0L, 0L, utc)
        )
        val line = BackupLogic.describeLastBackup(1_793_026_200_000L, 3_200_000L, utc)
        assertTrue(line, line.contains("26 Oct 2026"))
        assertTrue(line, line.contains("3.2 MB"))
    }
}
