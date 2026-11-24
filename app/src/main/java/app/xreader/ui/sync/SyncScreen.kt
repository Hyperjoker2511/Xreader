package app.xreader.ui.sync
import app.xreader.ui.theme.aiAccentColor

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SectionHeader
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import java.text.DateFormat
import java.util.Date
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.effectiveBottomPadding

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

    // Code 121 W8 (R34, D-121-W7-03 retired): the Material 3
    // Sync & backup page — list rows over the same view model
    // and launchers. Nocturne keeps the card page below.
    if (app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        M3SyncContent(
            state = state,
            viewModel = viewModel,
            onBack = onBack,
            onBackUpNow = { viewModel.backupNow(context) },
            onChooseBackupFolder = { backupFolderLauncher.launch(null) },
            onForgetBackupFolder = {
                viewModel.forgetBackupFolder(context)
            },
            onRestorePhoneBackup = { entry ->
                viewModel.restorePhoneBackup(context, entry)
            },
            onDeletePhoneBackup = { entry ->
                viewModel.deletePhoneBackup(context, entry)
            },
            onSetAutoBackupWeekly = {
                viewModel.setAutoBackupWeekly(context, it)
            },
            onDriveSignIn = {
                signInLauncher.launch(
                    container.googleDriveAuth.signInClient().signInIntent
                )
            },
            onExportBackupFile = {
                exportLauncher.launch("xreader-backup.zip")
            },
            onRestoreBackupFile = {
                importLauncher.launch(arrayOf("*/*"))
            }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Sync & backup",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── On this phone (v1.0.1) — above the cloud, first-class ──
            GlassCard {
                SectionHeader(title = "On this phone")
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
                    PrimaryPill(
                        text = "Choose a backup folder",
                        enabled = true,
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
                    PrimaryPill(
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
                        NocturneSwitch(
                            checked = settings.autoBackupWeekly,
                            onCheckedChange = {
                                viewModel.setAutoBackupWeekly(context, it)
                            },
                            enabled = true
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
                SectionHeader(title = "Sync route")
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
                    NocturneSwitch(
                        checked = settings.autoSync,
                        onCheckedChange = { viewModel.setAutoSync(it) },
                        enabled = true
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
                    NocturneSwitch(
                        checked = settings.syncBookFiles,
                        onCheckedChange = { viewModel.setSyncBookFiles(it) },
                        enabled = true
                    )
                }
            }

            if (settings.backend == SyncBackendKind.DRIVE) {
                GlassCard {
                    SectionHeader(title = "Google Drive")
                    Spacer(Modifier.height(6.dp))
                    if (settings.driveEmail.isBlank()) {
                        Text(
                            "Sign in with your Google account. Xreader only ever sees " +
                                "its own hidden app folder — never your other files.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryPill(
                            text = "Sign in with Google",
                            enabled = true,
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
                    SectionHeader(title = "WebDAV server")
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
                        PrimaryPill(
                            text = "Save",
                            enabled = true,
                            onClick = { viewModel.saveWebdav(url, username, password) },
                            modifier = Modifier.weight(1f)
                        )
                        GhostPill(
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

            PrimaryPill(
                text = if (state.busy) state.busyLabel else "Sync now",
                onClick = { viewModel.syncNow() },
                enabled = !state.busy && settings.backend != SyncBackendKind.NONE,
                modifier = Modifier.fillMaxWidth()
            )

            GlassCard {
                SectionHeader(title = "Backup file")
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
                    PrimaryPill(
                        text = "Save backup",
                        onClick = {
                            exportLauncher.launch("xreader-backup.zip")
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    )
                    GhostPill(
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
                SectionHeader(title = "Sync history")
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
            Spacer(
                Modifier.height(
                    effectiveBottomPadding(88.dp, LocalDockClearance.current)
                )
            )
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

// ────────────────────────────────────────────────────────────────
// Code 121 W8 (R34): the Material 3 Sync & backup page — list
// rows: Back up now (with the last-backup line) · Restore ·
// Drive switch + account line · WebDAV row → form dialog.
// The settled exclusion line is stated ONCE, at the foot.
// Every door is the shipped SyncViewModel's; the file flows
// ride the screen's own launchers, passed in as callbacks.
// ────────────────────────────────────────────────────────────────

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun M3SyncContent(
    state: SyncUiState,
    viewModel: SyncViewModel,
    onBack: () -> Unit,
    onBackUpNow: () -> Unit,
    onChooseBackupFolder: () -> Unit,
    onForgetBackupFolder: () -> Unit,
    onRestorePhoneBackup: (
        app.xreader.data.sync.BackupLogic.BackupEntry
    ) -> Unit,
    onDeletePhoneBackup: (
        app.xreader.data.sync.BackupLogic.BackupEntry
    ) -> Unit,
    onSetAutoBackupWeekly: (Boolean) -> Unit,
    onDriveSignIn: () -> Unit,
    onExportBackupFile: () -> Unit,
    onRestoreBackupFile: () -> Unit
) {
    val settings = state.settings
    var webdavOpen by remember { mutableStateOf(false) }
    var pendingRestore by remember {
        mutableStateOf<app.xreader.data.sync.BackupLogic.BackupEntry?>(null)
    }
    var pendingDelete by remember {
        mutableStateOf<app.xreader.data.sync.BackupLogic.BackupEntry?>(null)
    }
    val lastBackupLine = "Last backup: " +
        app.xreader.data.sync.BackupLogic.describeLastBackup(
            settings.lastBackupAt, settings.lastBackupSizeBytes,
            java.time.ZoneId.systemDefault()
        )
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Sync & backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            if (state.busy) {
                androidx.compose.material3.LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    state.busyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(
                        horizontal = 16.dp, vertical = 8.dp
                    )
                )
            }
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.messageIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(
                        horizontal = 16.dp, vertical = 8.dp
                    )
                )
            }
            Text(
                "On this phone",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = 16.dp, top = 12.dp, bottom = 4.dp
                )
            )
            if (settings.backupFolderUri.isBlank()) {
                androidx.compose.material3.ListItem(
                    headlineContent = { Text("Choose a backup folder") },
                    supportingContent = {
                        Text(
                            "Pick a folder once — Xreader remembers " +
                                "it and keeps your backups there. " +
                                "Nothing is uploaded."
                        )
                    },
                    modifier = Modifier.clickable {
                        onChooseBackupFolder()
                    }
                )
            } else {
                androidx.compose.material3.ListItem(
                    headlineContent = { Text("Back up now") },
                    supportingContent = { Text(lastBackupLine) },
                    modifier = Modifier.clickable(enabled = !state.busy) {
                        onBackUpNow()
                    }
                )
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text("Back up every week on its own")
                    },
                    supportingContent = {
                        Text(
                            "A fresh backup lands in the same folder " +
                                "each week."
                        )
                    },
                    trailingContent = {
                        androidx.compose.material3.Switch(
                            checked = settings.autoBackupWeekly,
                            onCheckedChange = onSetAutoBackupWeekly
                        )
                    }
                )
                TextButton(
                    onClick = onForgetBackupFolder,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("Forget this folder") }
            }
            Text(
                "Restore",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = 16.dp, top = 16.dp, bottom = 4.dp
                )
            )
            state.phoneBackups.forEach { entry ->
                androidx.compose.material3.ListItem(
                    headlineContent = {
                        Text(
                            app.xreader.data.sync.BackupLogic
                                .describeLastBackup(
                                    entry.modifiedAtMillis,
                                    entry.sizeBytes,
                                    java.time.ZoneId.systemDefault()
                                )
                        )
                    },
                    supportingContent = { Text(entry.name) },
                    trailingContent = {
                        Row {
                            TextButton(
                                onClick = { pendingRestore = entry }
                            ) { Text("Restore") }
                            IconButton(
                                onClick = { pendingDelete = entry }
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons
                                        .Filled.Delete,
                                    contentDescription = "Delete backup"
                                )
                            }
                        }
                    }
                )
            }
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Restore from a backup file") },
                supportingContent = {
                    Text("A backup file saved on any device")
                },
                modifier = Modifier.clickable(enabled = !state.busy) {
                    onRestoreBackupFile()
                }
            )
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Save a backup file") },
                supportingContent = {
                    Text("One file, no account needed")
                },
                modifier = Modifier.clickable(enabled = !state.busy) {
                    onExportBackupFile()
                }
            )
            Text(
                "Sync",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(
                    start = 16.dp, top = 16.dp, bottom = 4.dp
                )
            )
            Text(
                if (settings.lastSyncAt > 0) {
                    "Last synced " +
                        DateFormat.getDateTimeInstance()
                            .format(Date(settings.lastSyncAt)) +
                        " — " + settings.lastSyncSummary
                } else {
                    "Not synced yet. Sync carries progress, bookmarks, " +
                        "highlights, lore, glossary, profile and " +
                        "settings between your devices."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Google Drive") },
                supportingContent = {
                    Text(
                        if (settings.driveEmail.isBlank()) {
                            "Not signed in"
                        } else {
                            "Signed in as ${settings.driveEmail}"
                        }
                    )
                },
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = settings.backend ==
                            SyncBackendKind.DRIVE,
                        onCheckedChange = { on ->
                            viewModel.selectBackend(
                                if (on) {
                                    SyncBackendKind.DRIVE
                                } else {
                                    SyncBackendKind.NONE
                                }
                            )
                        }
                    )
                }
            )
            if (settings.backend == SyncBackendKind.DRIVE) {
                if (settings.driveEmail.isBlank()) {
                    Text(
                        "Xreader only ever sees its own hidden app " +
                            "folder — never your other files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    androidx.compose.material3.Button(
                        onClick = onDriveSignIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text("Sign in with Google") }
                } else {
                    TextButton(
                        onClick = { viewModel.driveSignOut() },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) { Text("Sign out") }
                }
            }
            androidx.compose.material3.ListItem(
                headlineContent = { Text("WebDAV") },
                supportingContent = {
                    Text(
                        settings.webdavUrl.ifBlank {
                            "Your own server"
                        }
                    )
                },
                trailingContent = {
                    Icon(
                        androidx.compose.material.icons.Icons
                            .Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier.clickable { webdavOpen = true }
            )
            androidx.compose.material3.ListItem(
                headlineContent = {
                    Text("Sync automatically when the app opens")
                },
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = settings.autoSync,
                        onCheckedChange = { viewModel.setAutoSync(it) }
                    )
                }
            )
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Also sync book files") },
                supportingContent = {
                    Text(
                        "Sends each book's file, cover and downloaded " +
                            "chapters with the sync — a whole library " +
                            "can be gigabytes."
                    )
                },
                trailingContent = {
                    androidx.compose.material3.Switch(
                        checked = settings.syncBookFiles,
                        onCheckedChange = {
                            viewModel.setSyncBookFiles(it)
                        }
                    )
                }
            )
            androidx.compose.material3.Button(
                onClick = { viewModel.syncNow() },
                enabled = !state.busy &&
                    settings.backend != SyncBackendKind.NONE,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(if (state.busy) state.busyLabel else "Sync now")
            }
            if (state.log.isNotEmpty()) {
                Text(
                    "Sync history",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = 16.dp, top = 16.dp, bottom = 4.dp
                    )
                )
                state.log.forEach { entry ->
                    androidx.compose.material3.ListItem(
                        headlineContent = {
                            Text(
                                "${entry.backend} — " +
                                    DateFormat.getDateTimeInstance()
                                        .format(Date(entry.at))
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(entry.summary)
                                entry.conflicts.forEach { conflict ->
                                    Text(conflict)
                                }
                            }
                        }
                    )
                }
            }
            Text(
                "Backups never include keys, cookies or passwords.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = 16.dp, vertical = 16.dp
                )
            )
        }
    }
    if (webdavOpen) {
        M3WebdavForm(
            state = state,
            viewModel = viewModel,
            onClose = { webdavOpen = false }
        )
    }
    pendingRestore?.let { entry ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Your library, progress, bookmarks and settings " +
                        "will be replaced by the ones in this backup. " +
                        "Books added since then may need to be added " +
                        "again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    onRestorePhoneBackup(entry)
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    pendingDelete?.let { entry ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this backup?") },
            text = {
                Text(
                    "The backup file “${entry.name}” will be deleted " +
                        "from your folder. Your library itself is not " +
                        "touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeletePhoneBackup(entry)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** R34: the WebDAV row's form dialog — the shipped fields and
 * doors, in the M3 dialog frame, plus the route switch (the
 * dialog is where WebDAV sync is turned on). The password is
 * a password field; a saved password is never rendered. */
@Composable
private fun M3WebdavForm(
    state: SyncUiState,
    viewModel: SyncViewModel,
    onClose: () -> Unit
) {
    val settings = state.settings
    var url by remember(settings.webdavUrl) {
        mutableStateOf(settings.webdavUrl)
    }
    var username by remember(settings.webdavUsername) {
        mutableStateOf(settings.webdavUsername)
    }
    var password by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("WebDAV server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Use WebDAV for sync",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    androidx.compose.material3.Switch(
                        checked = settings.backend ==
                            SyncBackendKind.WEBDAV,
                        onCheckedChange = { on ->
                            viewModel.selectBackend(
                                if (on) {
                                    SyncBackendKind.WEBDAV
                                } else {
                                    SyncBackendKind.NONE
                                }
                            )
                        }
                    )
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Folder address") },
                    placeholder = {
                        Text(
                            "https://cloud.example.com/remote.php/dav/files/you/Xreader"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                Text(
                    "The password is stored in the encrypted vault on " +
                        "this phone only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (settings.webdavUrl.isNotBlank() ||
                    state.webdavPasswordSaved
                ) {
                    TextButton(onClick = { viewModel.removeWebdav() }) {
                        Text("Remove this WebDAV connection")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    viewModel.saveWebdav(url, username, password)
                    onClose()
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { viewModel.testWebdav() },
                    enabled = !state.busy
                ) {
                    Text(if (state.busy) "Testing…" else "Test connection")
                }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
    )
}
