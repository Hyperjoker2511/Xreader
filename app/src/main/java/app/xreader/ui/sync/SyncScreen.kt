package app.xreader.ui.sync
import app.xreader.ui.theme.aiAccentColor

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.prefs.SyncBackendKind
import app.xreader.di.AppContainer
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.XreaderPrimaryButton
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import java.text.DateFormat
import java.util.Date

/**
 * Sync & backup (screen #25): choose a route (Drive / WebDAV), sync
 * now, or use the account-free backup file. The log at the bottom is
 * the plan's promise — sync is never a silent overwrite.
 */
@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    container: AppContainer,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = state.settings

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            viewModel.onDriveSignInFailed("sign-in was cancelled or didn't complete")
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            viewModel.onDriveSignedIn(account?.email)
        } catch (e: ApiException) {
            viewModel.onDriveSignInFailed("Google sign-in status ${e.statusCode}")
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            val stream = runCatching {
                context.contentResolver.openOutputStream(uri)
            }.getOrNull()
            if (stream != null) {
                viewModel.exportBackup(stream)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val stream = runCatching {
                context.contentResolver.openInputStream(uri)
            }.getOrNull()
            if (stream != null) {
                viewModel.importBackup(stream)
            }
        }
    }

    val backupFolderLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        // Cancelling is a silent no-op by design.
        if (uri != null) viewModel.onBackupFolderPicked(context, uri)
    }
    var pendingRestore by remember {
        mutableStateOf<app.xreader.data.sync.BackupLogic.BackupEntry?>(null)
    }
    var pendingBackupDelete by remember {
        mutableStateOf<app.xreader.data.sync.BackupLogic.BackupEntry?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(settings.backupFolderUri) {
        viewModel.refreshPhoneBackups(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Sync & backup",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── On this phone (v1.0.1) — above the cloud, first-class ──
            GlassCard {
                Text(
                    "On this phone",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Kept only on this phone. Nothing is uploaded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Last backup: " + app.xreader.data.sync.BackupLogic.describeLastBackup(
                        settings.lastBackupAt, settings.lastBackupSizeBytes,
                        java.time.ZoneId.systemDefault()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                if (settings.backupFolderUri.isBlank()) {
                    XreaderPrimaryButton(
                        text = "Choose a backup folder",
                        onClick = { backupFolderLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pick a folder once — Xreader remembers it and keeps your backups there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    XreaderPrimaryButton(
                        text = "Back up now",
                        onClick = { viewModel.backupNow(context) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.phoneBackups.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Restore",
                            style = MaterialTheme.typography.titleSmall
                        )
                        state.phoneBackups.forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        app.xreader.data.sync.BackupLogic.describeLastBackup(
                                            entry.modifiedAtMillis, entry.sizeBytes,
                                            java.time.ZoneId.systemDefault()
                                        ),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { pendingRestore = entry }) {
                                    Text("Restore")
                                }
                                TextButton(onClick = { pendingBackupDelete = entry }) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Back up every week on its own",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "A fresh backup lands in the same folder each week.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.autoBackupWeekly,
                            onCheckedChange = {
                                viewModel.setAutoBackupWeekly(context, it)
                            }
                        )
                    }
                    TextButton(onClick = { viewModel.forgetBackupFolder(context) }) {
                        Text("Forget this folder")
                    }
                }
            }

            GlassCard {
                Text(
                    "Sync carries your reading life between your phone and tablet: " +
                        "progress, bookmarks, highlights, lore, glossary, profile and " +
                        "settings." +
                        if (settings.syncBookFiles) {
                            " Book files and covers travel too — you've turned that on below."
                        } else {
                            " Book files stay on each device unless you turn on " +
                                "book-file sync below — add the same book on both " +
                                "and everything else lines up."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (settings.lastSyncAt > 0) {
                        "Last synced ${DateFormat.getDateTimeInstance().format(Date(settings.lastSyncAt))}\n" +
                            settings.lastSyncSummary
                    } else {
                        "Not synced yet."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                state.message?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.messageIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                }
            }

            GlassCard {
                Text("Sync route", style = MaterialTheme.typography.titleMedium)
                BackendRow(
                    label = "Google Drive (private app folder)",
                    selected = settings.backend == SyncBackendKind.DRIVE,
                    onClick = { viewModel.selectBackend(SyncBackendKind.DRIVE) }
                )
                BackendRow(
                    label = "WebDAV (your own server)",
                    selected = settings.backend == SyncBackendKind.WEBDAV,
                    onClick = { viewModel.selectBackend(SyncBackendKind.WEBDAV) }
                )
                BackendRow(
                    label = "Off",
                    selected = settings.backend == SyncBackendKind.NONE,
                    onClick = { viewModel.selectBackend(SyncBackendKind.NONE) }
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sync automatically when the app opens", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = settings.autoSync,
                        onCheckedChange = { viewModel.setAutoSync(it) }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Also sync book files", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Sends each book's file, cover and downloaded chapters " +
                                "with the sync. A whole library can be gigabytes — " +
                                "most people leave this off and keep files per device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.syncBookFiles,
                        onCheckedChange = { viewModel.setSyncBookFiles(it) }
                    )
                }
            }

            if (settings.backend == SyncBackendKind.DRIVE) {
                GlassCard {
                    Text("Google Drive", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    if (settings.driveEmail.isBlank()) {
                        Text(
                            "Sign in with your Google account. Xreader only ever sees " +
                                "its own hidden app folder — never your other files.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        XreaderPrimaryButton(
                            text = "Sign in with Google",
                            onClick = {
                                signInLauncher.launch(
                                    container.googleDriveAuth.signInClient().signInIntent
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            "Signed in as ${settings.driveEmail}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        TextButton(onClick = { viewModel.driveSignOut() }) {
                            Text("Sign out")
                        }
                    }
                }
            }

            if (settings.backend == SyncBackendKind.WEBDAV) {
                var url by remember(settings.webdavUrl) {
                    mutableStateOf(settings.webdavUrl)
                }
                var username by remember(settings.webdavUsername) {
                    mutableStateOf(settings.webdavUsername)
                }
                var password by remember { mutableStateOf("") }
                GlassCard {
                    Text("WebDAV server", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Folder address") },
                        placeholder = { Text("https://cloud.example.com/remote.php/dav/files/you/Xreader") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = {
                            Text(
                                if (state.webdavPasswordSaved) {
                                    "Password (saved — leave blank to keep)"
                                } else {
                                    "Password"
                                }
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XreaderPrimaryButton(
                            text = "Save",
                            onClick = { viewModel.saveWebdav(url, username, password) },
                            modifier = Modifier.weight(1f)
                        )
                        XreaderPrimaryButton(
                            text = if (state.busy) "Testing…" else "Test connection",
                            onClick = { viewModel.testWebdav() },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (settings.webdavUrl.isNotBlank() || state.webdavPasswordSaved) {
                        TextButton(onClick = { viewModel.removeWebdav() }) {
                            Text("Remove this WebDAV connection")
                        }
                    }
                    Text(
                        "The password is stored in the encrypted vault on this phone only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            XreaderPrimaryButton(
                text = if (state.busy) state.busyLabel else "Sync now",
                onClick = { viewModel.syncNow() },
                enabled = !state.busy && settings.backend != SyncBackendKind.NONE,
                modifier = Modifier.fillMaxWidth()
            )

            GlassCard {
                Text("Backup file", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "No account needed: save one backup file anywhere (USB, a folder, " +
                        "another app) and restore it on the other device. It holds " +
                        "reading data — plus book files when \"Also sync book files\" " +
                        "is on — and never keys or passwords.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XreaderPrimaryButton(
                        text = "Save backup",
                        onClick = {
                            exportLauncher.launch("xreader-backup.zip")
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    )
                    XreaderPrimaryButton(
                        text = "Restore backup",
                        onClick = {
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (state.log.isNotEmpty()) {
                Text("Sync history", style = MaterialTheme.typography.titleMedium)
                state.log.forEach { entry ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${entry.backend} — " +
                                DateFormat.getDateTimeInstance().format(Date(entry.at)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
                        entry.conflicts.forEach { conflict ->
                            Text(
                                conflict,
                                style = MaterialTheme.typography.bodySmall,
                                color = aiAccentColor()
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    pendingRestore?.let { entry ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Your library, progress, bookmarks and settings will be replaced " +
                        "by the ones in this backup. Books added since then may need " +
                        "to be added again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    viewModel.restorePhoneBackup(context, entry)
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            }
        )
    }
    pendingBackupDelete?.let { entry ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingBackupDelete = null },
            title = { Text("Delete this backup?") },
            text = {
                Text(
                    "The backup file “${entry.name}” will be deleted from your folder. " +
                        "Your library itself is not touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingBackupDelete = null
                    viewModel.deletePhoneBackup(context, entry)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBackupDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BackendRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
    }

}
