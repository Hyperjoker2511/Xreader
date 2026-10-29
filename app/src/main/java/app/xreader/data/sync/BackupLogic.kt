package app.xreader.data.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure naming/listing rules for ON-PHONE backups (v1.0.1): one
 * timestamped archive per backup in the user-picked folder, newest
 * first in the Restore list. Kept Android-free so the rules are
 * unit-tested.
 */
object BackupLogic {

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    const val PREFIX = "xreader-backup-"
    const val SUFFIX = ".zip"

    fun fileName(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = STAMP.format(Instant.ofEpochMilli(atMillis).atZone(zone))
        return "$PREFIX$stamp$SUFFIX"
    }

    fun isBackupName(name: String): Boolean =
        name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    data class BackupEntry(
        val name: String,
        val documentId: String,
        val sizeBytes: Long,
        val modifiedAtMillis: Long
    )

    /** Newest first; ties broken by name so the order is stable. */
    fun newestFirst(entries: List<BackupEntry>): List<BackupEntry> =
        entries.filter { isBackupName(it.name) }
            .sortedWith(
                compareByDescending<BackupEntry> { it.modifiedAtMillis }
                    .thenByDescending { it.name }
            )

    /** "12 Oct 2026 · 3.2 MB" style line for the last-backup row. */
    fun describeLastBackup(atMillis: Long, sizeBytes: Long, zone: ZoneId): String {
        if (atMillis <= 0L) return "No backup yet"
        val date = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .format(Instant.ofEpochMilli(atMillis).atZone(zone))
        val size = when {
            sizeBytes <= 0L -> ""
            sizeBytes >= 1_000_000L -> " · %.1f MB".format(sizeBytes / 1_000_000.0)
            sizeBytes >= 1_000L -> " · %.0f KB".format(sizeBytes / 1_000.0)
            else -> " · $sizeBytes B"
        }
        return "$date$size"
    }
}
