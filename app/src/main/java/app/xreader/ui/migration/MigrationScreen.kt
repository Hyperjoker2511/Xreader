package app.xreader.ui.migration
import app.xreader.ui.theme.errorTextColor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.theme.NocturneOverline
import app.xreader.ui.theme.primaryTextColor

/**
 * Migration Studio (screen #19, PLAN §8). Komga / Kavita /
 * Calibre-Web are SERVERS, not backup formats — the explainer says
 * so and points at OPDS instead of pretending to import them.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MigrationScreen(
    viewModel: MigrationViewModel,
    onBack: () -> Unit,
    onAddFromLink: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentState = state
    val step = when (currentState) {
        MigrationUiState.Idle, MigrationUiState.Reading -> 2
        is MigrationUiState.Preview, MigrationUiState.Importing -> 3
        is MigrationUiState.Done -> 4
        is MigrationUiState.Failed -> 2
    }
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
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Migration studio",
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                listOf("Source", "File", "Preview", "Done").forEachIndexed { index, label ->
                    val stepNumber = index + 1
                    Text(
                        "$stepNumber · $label",
                        style = NocturneOverline,
                        color = if (stepNumber == step) {
                            primaryTextColor()
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
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
                    PrimaryPill(
                        text = "Choose a backup file",
                        enabled = true,
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                MigrationUiState.Reading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(
                            "Reading the backup…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is MigrationUiState.Preview -> {
                    val preview = current.preview
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionHeader(title = current.library.formatName)
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
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NocturneChip(
                                label = "${preview.matchedBooks} matched",
                                selected = false,
                                dotColor = MaterialTheme.colorScheme.secondary,
                                onClick = null
                            )
                            if (preview.unmatchedTitles.isNotEmpty()) {
                                NocturneChip(
                                    label = "${preview.unmatchedTitles.size} unmatched",
                                    selected = false,
                                    dotColor = MaterialTheme.colorScheme.error,
                                    onClick = null
                                )
                            }
                        }
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
                        PrimaryPill(
                            text = "Import",
                            enabled = true,
                            onClick = { viewModel.confirmImport() },
                            modifier = Modifier.weight(1f)
                        )
                        GhostPill(
                            text = "Start over",
                            enabled = true,
                            onClick = { viewModel.reset() }
                        )
                    }
                }
                MigrationUiState.Importing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(
                            "Importing…",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is MigrationUiState.Done -> {
                    GlassCard(Modifier.fillMaxWidth()) {
                        SectionHeader(title = "Done")
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
                        PrimaryPill(
                            text = "Add a source from a link",
                            enabled = true,
                            onClick = onAddFromLink,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    GhostPill(
                        text = "Import another backup",
                        enabled = true,
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
                    PrimaryPill(
                        text = "Try another file",
                        enabled = true,
                        onClick = { viewModel.reset() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(88.dp))
        }
    }
}
