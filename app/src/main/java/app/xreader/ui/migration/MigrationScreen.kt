package app.xreader.ui.migration
import app.xreader.ui.theme.errorTextColor

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.XreaderPrimaryButton

/**
 * Migration Studio (screen #19, PLAN §8). Komga / Kavita /
 * Calibre-Web are SERVERS, not backup formats — the explainer says
 * so and points at OPDS instead of pretending to import them.
 */
@Composable
fun MigrationScreen(
    viewModel: MigrationViewModel,
    onBack: () -> Unit,
    onAddFromLink: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.pickFile {
                runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            }
        }
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
                "Migration studio",
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
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "Bring your library from another reader app. Xreader reads " +
                        "Mihon / Tachiyomi backups (.tachibk and the older JSON), " +
                        "and library lists exported as JSON or CSV (LNReader, " +
                        "Tsukiyomi, Kototoro, a spreadsheet…). Books, chapter " +
                        "lists and read marks come across; chapter text itself " +
                        "isn't in any backup — it downloads from the source " +
                        "afterwards, like any new book.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Komga, Kavita and Calibre-Web are servers, not backups — " +
                        "add them under Browse → OPDS servers instead.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (val current = state) {
                MigrationUiState.Idle -> {
                    XreaderPrimaryButton(
                        text = "Choose a backup file",
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                MigrationUiState.Reading -> {
                    Text(
                        "Reading the backup…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                is MigrationUiState.Preview -> {
                    val preview = current.preview
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            current.library.formatName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${preview.totalBooks} book(s) · " +
                                "${preview.totalChapters} chapter(s) · " +
                                "${preview.readChapters} already read",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (preview.alreadyPresent > 0) {
                            Text(
                                "${preview.alreadyPresent} are already in your " +
                                    "library — their read marks will be merged.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${preview.matchedBooks} will link to a source you " +
                                "have installed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (preview.unmatchedTitles.isNotEmpty()) {
                            Text(
                                "${preview.unmatchedTitles.size} can't be linked " +
                                    "yet (their source isn't installed):",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            preview.unmatchedTitles.take(10).forEach { title ->
                                Text(
                                    "• $title",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (preview.unmatchedTitles.size > 10) {
                                Text(
                                    "…and ${preview.unmatchedTitles.size - 10} more",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (current.library.categories.isNotEmpty()) {
                            Text(
                                "Categories in the backup: " +
                                    current.library.categories.joinToString(", ") +
                                    " (Xreader has no shelves yet — they'll be " +
                                    "listed in the result, not dropped.)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XreaderPrimaryButton(
                            text = "Import",
                            onClick = { viewModel.confirmImport() },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.reset() }) {
                            Text("Start over")
                        }
                    }
                }
                MigrationUiState.Importing -> {
                    Text(
                        "Importing…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                is MigrationUiState.Done -> {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Done", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        current.lines.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (current.unmatchedTitles.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Still to link:",
                                style = MaterialTheme.typography.titleSmall
                            )
                            current.unmatchedTitles.forEach { title ->
                                Text(
                                    "• $title",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    if (current.unmatchedTitles.isNotEmpty()) {
                        XreaderPrimaryButton(
                            text = "Add a source from a link",
                            onClick = onAddFromLink,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    XreaderPrimaryButton(
                        text = "Import another backup",
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is MigrationUiState.Failed -> {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            current.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = errorTextColor()
                        )
                    }
                    XreaderPrimaryButton(
                        text = "Try another file",
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
