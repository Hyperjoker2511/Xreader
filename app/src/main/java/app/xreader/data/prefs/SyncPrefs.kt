package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Which sync route the user picked (PLAN §7 + Amendment 3). */
enum class SyncBackendKind { NONE, DRIVE, WEBDAV }

data class SyncSettings(
    val backend: SyncBackendKind = SyncBackendKind.NONE,
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    /** Display only — the Drive address the user signed in with. */
    val driveEmail: String = "",
    val autoSync: Boolean = false,
    /** Also sync book files + covers (off by default, §0.2/A4).
     * Since Phase 6 the file channel is real: when on, a sync also
     * carries each book's file, cover, downloaded chapters and lore
     * art; when off, sync stays metadata-only. */
    val syncBookFiles: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastSyncSummary: String = "",
    /** v1.0.1 on-phone backup: the SAF tree URI the user picked once
     * ("" = not chosen yet). */
    val backupFolderUri: String = "",
    /** v1.0.1: weekly automatic on-phone backup (recommendation D). */
    val autoBackupWeekly: Boolean = false,
    val lastBackupAt: Long = 0L,
    val lastBackupSizeBytes: Long = 0L
)

/**
 * The single writer/reader of sync choices + this device's sync
 * identity. The WebDAV password and any Drive token NEVER live here —
 * KeyVault only (§9.5). The device id is generated once and kept: it
 * stamps every record this device writes.
 */
class SyncPrefs(private val store: DataStore<Preferences>) {

    val settings: Flow<SyncSettings> = store.data.map { prefs ->
        SyncSettings(
            backend = runCatching {
                SyncBackendKind.valueOf(prefs[KEY_BACKEND] ?: "")
            }.getOrDefault(SyncBackendKind.NONE),
            webdavUrl = prefs[KEY_WEBDAV_URL] ?: "",
            webdavUsername = prefs[KEY_WEBDAV_USER] ?: "",
            driveEmail = prefs[KEY_DRIVE_EMAIL] ?: "",
            autoSync = prefs[KEY_AUTO] ?: false,
            syncBookFiles = prefs[KEY_FILES] ?: false,
            lastSyncAt = prefs[KEY_LAST_AT] ?: 0L,
            lastSyncSummary = prefs[KEY_LAST_SUMMARY] ?: "",
            backupFolderUri = prefs[KEY_BACKUP_FOLDER] ?: "",
            autoBackupWeekly = prefs[KEY_AUTO_BACKUP] ?: false,
            lastBackupAt = prefs[KEY_LAST_BACKUP_AT] ?: 0L,
            lastBackupSizeBytes = prefs[KEY_LAST_BACKUP_SIZE] ?: 0L
        )
    }

    /** This device's stable sync identity (created on first ask). */
    suspend fun deviceId(): String {
        val existing = store.data.first()[KEY_DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val created = "dev-" + UUID.randomUUID().toString().take(13)
        store.edit { it[KEY_DEVICE_ID] = created }
        return created
    }

    suspend fun setBackupFolderUri(uri: String) =
        store.edit { it[KEY_BACKUP_FOLDER] = uri }

    suspend fun setAutoBackupWeekly(enabled: Boolean) =
        store.edit { it[KEY_AUTO_BACKUP] = enabled }

    suspend fun recordBackup(atMillis: Long, sizeBytes: Long) =
        store.edit {
            it[KEY_LAST_BACKUP_AT] = atMillis
            it[KEY_LAST_BACKUP_SIZE] = sizeBytes
        }

    suspend fun setBackend(kind: SyncBackendKind) =
        store.edit { it[KEY_BACKEND] = kind.name }

    suspend fun setWebdav(url: String, username: String) =
        store.edit {
            it[KEY_WEBDAV_URL] = url.trim()
            it[KEY_WEBDAV_USER] = username.trim()
        }

    suspend fun setDriveEmail(email: String) =
        store.edit { it[KEY_DRIVE_EMAIL] = email }

    suspend fun setAutoSync(enabled: Boolean) =
        store.edit { it[KEY_AUTO] = enabled }

    suspend fun setSyncBookFiles(enabled: Boolean) =
        store.edit { it[KEY_FILES] = enabled }

    suspend fun recordSync(at: Long, summary: String) =
        store.edit {
            it[KEY_LAST_AT] = at
            it[KEY_LAST_SUMMARY] = summary
        }

    private companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("sync.device_id")
        val KEY_BACKEND = stringPreferencesKey("sync.backend")
        val KEY_WEBDAV_URL = stringPreferencesKey("sync.webdav_url")
        val KEY_WEBDAV_USER = stringPreferencesKey("sync.webdav_user")
        val KEY_DRIVE_EMAIL = stringPreferencesKey("sync.drive_email")
        val KEY_AUTO = booleanPreferencesKey("sync.auto")
        val KEY_FILES = booleanPreferencesKey("sync.book_files")
        val KEY_LAST_AT = longPreferencesKey("sync.last_at")
        val KEY_LAST_SUMMARY = stringPreferencesKey("sync.last_summary")
        val KEY_BACKUP_FOLDER = stringPreferencesKey("sync.backup_folder")
        val KEY_AUTO_BACKUP = booleanPreferencesKey("sync.auto_backup")
        val KEY_LAST_BACKUP_AT = longPreferencesKey("sync.last_backup_at")
        val KEY_LAST_BACKUP_SIZE = longPreferencesKey("sync.last_backup_size")
    }
}
