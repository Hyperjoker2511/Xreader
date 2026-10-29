package app.xreader.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.core.security.KeyVault
import app.xreader.data.prefs.SyncBackendKind
import app.xreader.data.prefs.SyncSettings
import app.xreader.data.sync.SyncBackend
import app.xreader.data.sync.SyncLogEntry
import app.xreader.di.AppContainer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SyncUiState(
    val settings: SyncSettings = SyncSettings(),
    val webdavPasswordSaved: Boolean = false,
    val busy: Boolean = false,
    val busyLabel: String = "",
    val message: String? = null,
    val messageIsError: Boolean = false,
    val log: List<SyncLogEntry> = emptyList(),
    /** v1.0.1: backups found in the picked on-phone folder. */
    val phoneBackups: List<app.xreader.data.sync.BackupLogic.BackupEntry> = emptyList()
)

/**
 * Sync & backup (PLAN §7, screen #25). Honest by construction: with
 * no route chosen, the screen says so and offers the backup file
 * instead; a sync that fails changes nothing and says why (with the
 * Details line); every completed sync lands in the visible log.
 */
class SyncViewModel(private val container: AppContainer) : ViewModel() {

    private val local = MutableStateFlow(SyncUiState())

    val state: StateFlow<SyncUiState> = combine(
        container.syncPrefs.settings,
        container.keyVault.presentKeys,
        local
    ) { settings, presentKeys, localState ->
        localState.copy(
            settings = settings,
            webdavPasswordSaved = KeyVault.WEBDAV_PASSWORD in presentKeys
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState())

    init {
        refreshLog()
        viewModelScope.launch {
            // Keep the shown Drive address honest with the real sign-in.
            val email = container.googleDriveAuth.accountEmail()
            val saved = container.syncPrefs.settings.first().driveEmail
            if (email != null && email != saved) {
                container.syncPrefs.setDriveEmail(email)
            }
            if (email == null && saved.isNotBlank()) {
                container.syncPrefs.setDriveEmail("")
            }
        }
    }

    private fun refreshLog() {
        local.value = local.value.copy(log = container.syncLogStore.recent())
    }

    private fun setMessage(text: String?, isError: Boolean = false) {
        local.value = local.value.copy(message = text, messageIsError = isError)
    }

    fun selectBackend(kind: SyncBackendKind) {
        viewModelScope.launch { container.syncPrefs.setBackend(kind) }
    }

    fun setAutoSync(enabled: Boolean) {
        viewModelScope.launch { container.syncPrefs.setAutoSync(enabled) }
    }

    fun setSyncBookFiles(enabled: Boolean) {
        viewModelScope.launch { container.syncPrefs.setSyncBookFiles(enabled) }
    }

    // -- Drive ---------------------------------------------------------------

    fun onDriveSignedIn(email: String?) {
        viewModelScope.launch {
            container.syncPrefs.setDriveEmail(email ?: "")
            container.syncPrefs.setBackend(SyncBackendKind.DRIVE)
            setMessage(
                if (email != null) {
                    "Signed in as $email — Drive sync is ready."
                } else {
                    "Signed in — Drive sync is ready."
                }
            )
        }
    }

    fun onDriveSignInFailed(detail: String?) {
        setMessage(
            "Google sign-in didn't complete. Xreader's Drive sign-in needs a " +
                "one-time Google Cloud setup for this app; the backup file and " +
                "WebDAV routes work without it." +
                (detail?.let { "\nDetails: $it" } ?: ""),
            isError = true
        )
    }

    fun driveSignOut() {
        viewModelScope.launch {
            container.googleDriveAuth.signOut()
            container.syncPrefs.setDriveEmail("")
            setMessage("Signed out of Google. Your synced data stays in Drive.")
        }
    }

    // -- WebDAV ----------------------------------------------------------------

    fun saveWebdav(url: String, username: String, password: String) {
        viewModelScope.launch {
            container.syncPrefs.setWebdav(url, username)
            if (password.isNotBlank()) {
                container.keyVault.put(KeyVault.WEBDAV_PASSWORD, password)
            }
            container.syncPrefs.setBackend(SyncBackendKind.WEBDAV)
            setMessage("WebDAV details saved.")
        }
    }

    /** v1.0.1 removability: remove the WebDAV connection entirely —
     * address, username and the vault password all go. */
    fun removeWebdav() {
        viewModelScope.launch {
            container.syncPrefs.setWebdav("", "")
            runCatching { container.keyVault.remove(KeyVault.WEBDAV_PASSWORD) }
            val settings = container.syncPrefs.settings.first()
            if (settings.backend == SyncBackendKind.WEBDAV) {
                container.syncPrefs.setBackend(SyncBackendKind.NONE)
            }
            setMessage("WebDAV connection removed.")
        }
    }

    fun testWebdav() {
        local.value = local.value.copy(busy = true, busyLabel = "Testing…", message = null)
        viewModelScope.launch {
            // Warm the URL cache the backend reads from.
            container.webDavBackend
            kotlinx.coroutines.delay(250)
            when (val result = container.webDavBackend.probe()) {
                is AppResult.Ok -> {
                    local.value = local.value.copy(busy = false)
                    setMessage("WebDAV server reached — those details work.")
                }
                is AppResult.Err -> {
                    local.value = local.value.copy(busy = false)
                    setMessage(ErrorText.displayText(result.error), isError = true)
                }
            }
        }
    }

    // -- Sync now ---------------------------------------------------------------

    fun syncNow() {
        viewModelScope.launch {
            val settings = container.syncPrefs.settings.first()
            val backend: SyncBackend = when (settings.backend) {
                SyncBackendKind.DRIVE -> container.driveSyncBackend
                SyncBackendKind.WEBDAV -> container.webDavBackend
                SyncBackendKind.NONE -> {
                    setMessage(
                        "Choose Google Drive or WebDAV above first — or use the " +
                            "backup file below, which needs no account at all.",
                        isError = true
                    )
                    return@launch
                }
            }
            if (settings.backend == SyncBackendKind.DRIVE &&
                container.googleDriveAuth.accountEmail() == null
            ) {
                setMessage("Sign in with Google first, then sync.", isError = true)
                return@launch
            }
            runSync(backend)
        }
    }

    /** Auto-sync entry point (app open, when the toggle is on). */
    fun autoSyncIfDue() {
        viewModelScope.launch {
            val settings = container.syncPrefs.settings.first()
            if (!settings.autoSync) return@launch
            if (System.currentTimeMillis() - settings.lastSyncAt < AUTO_SYNC_INTERVAL_MS) {
                return@launch
            }
            val backend = when (settings.backend) {
                SyncBackendKind.DRIVE -> container.driveSyncBackend
                SyncBackendKind.WEBDAV -> container.webDavBackend
                SyncBackendKind.NONE -> return@launch
            }
            runSync(backend, quiet = true)
        }
    }

    private suspend fun runSync(backend: SyncBackend, quiet: Boolean = false) {
        local.value = local.value.copy(busy = true, busyLabel = "Syncing…", message = null)
        when (val result = container.syncService.syncNow(backend)) {
            is AppResult.Ok -> {
                local.value = local.value.copy(busy = false)
                refreshLog()
                if (!quiet) setMessage(result.value.summary)
            }
            is AppResult.Err -> {
                local.value = local.value.copy(busy = false)
                if (!quiet) {
                    setMessage(ErrorText.displayText(result.error), isError = true)
                }
            }
        }
    }

    // -- Backup -----------------------------------------------------------------

    fun exportBackup(output: OutputStream) {
        local.value = local.value.copy(busy = true, busyLabel = "Writing backup…", message = null)
        viewModelScope.launch {
            when (val result = container.syncService.exportBackup(output)) {
                is AppResult.Ok -> {
                    local.value = local.value.copy(busy = false)
                    val filesOn = container.syncPrefs.settings.first().syncBookFiles
                    setMessage(
                        if (filesOn) {
                            "Backup saved — reading data and book files; " +
                                "no keys, no passwords."
                        } else {
                            "Backup saved — reading data only: no book files, " +
                                "no keys, no passwords."
                        }
                    )
                }
                is AppResult.Err -> {
                    local.value = local.value.copy(busy = false)
                    setMessage(ErrorText.displayText(result.error), isError = true)
                }
            }
        }
    }

    // -- On this phone (v1.0.1) -------------------------------------------------

    fun onBackupFolderPicked(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            container.syncPrefs.setBackupFolderUri(uri.toString())
            refreshPhoneBackups(context)
            setMessage("Backup folder chosen. Backups will be kept there.")
        }
    }

    fun forgetBackupFolder(context: android.content.Context) {
        viewModelScope.launch {
            container.syncPrefs.setBackupFolderUri("")
            local.value = local.value.copy(phoneBackups = emptyList())
            setMessage("Folder forgotten. Your existing backup files were not deleted.")
        }
    }

    fun refreshPhoneBackups(context: android.content.Context) {
        viewModelScope.launch {
            val folder = container.syncPrefs.settings.first().backupFolderUri
            if (folder.isBlank()) return@launch
            val entries = app.xreader.data.sync.PhoneBackupStore(context)
                .listBackups(android.net.Uri.parse(folder))
            local.value = local.value.copy(phoneBackups = entries)
        }
    }

    /** "Back up now": one tap once the folder is chosen. */
    fun backupNow(context: android.content.Context) {
        viewModelScope.launch {
            val settings = container.syncPrefs.settings.first()
            if (settings.backupFolderUri.isBlank()) return@launch
            local.value = local.value.copy(
                busy = true, busyLabel = "Writing backup…", message = null
            )
            val store = app.xreader.data.sync.PhoneBackupStore(context)
            val treeUri = android.net.Uri.parse(settings.backupFolderUri)
            val name = app.xreader.data.sync.BackupLogic.fileName(System.currentTimeMillis())
            when (val opened = store.writeBackup(treeUri, name)) {
                is AppResult.Err -> {
                    local.value = local.value.copy(busy = false)
                    setMessage(ErrorText.displayText(opened.error), isError = true)
                }
                is AppResult.Ok -> {
                    val (docUri, stream) = opened.value
                    when (val result = stream.use {
                        container.syncService.exportBackup(it)
                    }) {
                        is AppResult.Ok -> {
                            val size = runCatching {
                                context.contentResolver
                                    .openFileDescriptor(docUri, "r")?.statSize ?: 0L
                            }.getOrDefault(0L)
                            container.syncPrefs.recordBackup(
                                System.currentTimeMillis(), size
                            )
                            local.value = local.value.copy(busy = false)
                            refreshPhoneBackups(context)
                            setMessage("Backup saved to this phone.")
                        }
                        is AppResult.Err -> {
                            local.value = local.value.copy(busy = false)
                            setMessage(
                                ErrorText.displayText(result.error), isError = true
                            )
                        }
                    }
                }
            }
        }
    }

    fun restorePhoneBackup(
        context: android.content.Context,
        entry: app.xreader.data.sync.BackupLogic.BackupEntry
    ) {
        viewModelScope.launch {
            val folder = container.syncPrefs.settings.first().backupFolderUri
            if (folder.isBlank()) return@launch
            val stream = app.xreader.data.sync.PhoneBackupStore(context)
                .openBackup(android.net.Uri.parse(folder), entry.documentId)
            if (stream == null) {
                setMessage("That backup file couldn't be opened.", isError = true)
                return@launch
            }
            stream.use { importBackup(it) }
        }
    }

    fun deletePhoneBackup(
        context: android.content.Context,
        entry: app.xreader.data.sync.BackupLogic.BackupEntry
    ) {
        viewModelScope.launch {
            val folder = container.syncPrefs.settings.first().backupFolderUri
            if (folder.isBlank()) return@launch
            val gone = app.xreader.data.sync.PhoneBackupStore(context)
                .deleteBackup(android.net.Uri.parse(folder), entry.documentId)
            if (gone) {
                refreshPhoneBackups(context)
                setMessage("Deleted that backup file.")
            } else {
                setMessage("That backup file couldn't be deleted.", isError = true)
            }
        }
    }

    fun setAutoBackupWeekly(context: android.content.Context, enabled: Boolean) {
        viewModelScope.launch {
            container.syncPrefs.setAutoBackupWeekly(enabled)
            if (enabled) {
                app.xreader.data.sync.AutoBackupWorker.schedule(context)
            } else {
                app.xreader.data.sync.AutoBackupWorker.cancel(context)
            }
        }
    }

    fun importBackup(input: InputStream) {
        local.value = local.value.copy(busy = true, busyLabel = "Restoring…", message = null)
        viewModelScope.launch {
            when (val result = container.syncService.importBackup(input)) {
                is AppResult.Ok -> {
                    local.value = local.value.copy(busy = false)
                    refreshLog()
                    setMessage("Restored: ${result.value.summary}")
                }
                is AppResult.Err -> {
                    local.value = local.value.copy(busy = false)
                    setMessage(ErrorText.displayText(result.error), isError = true)
                }
            }
        }
    }

    companion object {
        const val AUTO_SYNC_INTERVAL_MS = 60L * 60L * 1000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SyncViewModel(container) as T
            }
    }
}
