package app.xreader.ui.sources

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.extension.player.SourcePrefKind
import app.xreader.data.extension.player.SourcePrefRow
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.SectionHeader

/**
 * K5: a player source's own settings, in the app's
 * source-page shape (back arrow, headline, scrolling cards'
 * rhythm). The rows are the source's OWN preferences,
 * hosted headlessly by SourcePreferenceHost — every control
 * writes into the extension's store, so the source reads the
 * new values on its next call.
 *
 * A source with no settings of its own gets the honest line,
 * never an empty form. Editors open as dialogs (a choice
 * list, a text field, a multi-choice list) — the shapes the
 * donor's preference dialogs use, in Compose.
 */
@Composable
fun SourcePreferencesScreen(
    viewModel: SourcePreferencesViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choiceRow by remember { mutableStateOf<SourcePrefRow?>(null) }
    var textRow by remember { mutableStateOf<SourcePrefRow?>(null) }
    var multiRow by remember { mutableStateOf<SourcePrefRow?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.sourceName.ifBlank { "Source" },
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "Source settings — these are this source's own, " +
                        "and apply to it only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.unavailableMessage != null -> {
                EmptyState(
                    title = "Settings unavailable",
                    message = state.unavailableMessage!!,
                    actionLabel = "Back",
                    onAction = onBack
                )
            }
            state.noSettings -> {
                EmptyState(
                    title = "Source settings",
                    message = "This source has no settings of its own.",
                    actionLabel = "Back",
                    onAction = onBack
                )
            }
            state.errorMessage != null && state.rows.isEmpty() -> {
                EmptyState(
                    title = "Source settings",
                    message = state.errorMessage!!,
                    actionLabel = "Back",
                    onAction = onBack
                )
            }
            else -> {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    state.rows.forEach { row ->
                        SourcePrefRowView(
                            row = row,
                            onCheckedChange = { value ->
                                viewModel.setChecked(row, value)
                            },
                            onOpenChoice = { choiceRow = row },
                            onOpenText = { textRow = row },
                            onOpenMulti = { multiRow = row },
                            onClick = { viewModel.click(row) }
                        )
                    }
                }
            }
        }
    }

    // ── editors ─────────────────────────────────────────────
    // Each dialog reads its row FRESH from the state (by id),
    // so a host snapshot landing mid-dialog (a listener
    // changing other rows) never leaves the dialog acting on
    // stale values.

    choiceRow?.let { opened ->
        val row = state.rows.firstOrNull { it.id == opened.id }
            ?: opened
        AlertDialog(
            onDismissRequest = { choiceRow = null },
            title = { Text(row.title) },
            text = {
                Column {
                    row.options.forEachIndexed { index, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setChoice(
                                        row,
                                        row.optionValues
                                            .getOrNull(index) ?: label
                                    )
                                    choiceRow = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (index == row.selectedIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (index == row.selectedIndex) {
                                Text(
                                    "Chosen",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choiceRow = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    textRow?.let { opened ->
        val row = state.rows.firstOrNull { it.id == opened.id }
            ?: opened
        var draft by remember(opened.id) { mutableStateOf(row.text) }
        AlertDialog(
            onDismissRequest = { textRow = null },
            title = { Text(row.title) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setText(row, draft)
                    textRow = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { textRow = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    multiRow?.let { opened ->
        val row = state.rows.firstOrNull { it.id == opened.id }
            ?: opened
        AlertDialog(
            onDismissRequest = { multiRow = null },
            title = { Text(row.title) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                ) {
                    row.options.forEachIndexed { index, label ->
                        val value = row.optionValues
                            .getOrNull(index) ?: label
                        val chosen = value in row.selectedValues
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.toggleMultiValue(row, value)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = chosen,
                                onCheckedChange = {
                                    viewModel.toggleMultiValue(row, value)
                                }
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { multiRow = null }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun SourcePrefRowView(
    row: SourcePrefRow,
    onCheckedChange: (Boolean) -> Unit,
    onOpenChoice: () -> Unit,
    onOpenText: () -> Unit,
    onOpenMulti: () -> Unit,
    onClick: () -> Unit
) {
    when (row.kind) {
        SourcePrefKind.SECTION -> {
            Spacer(Modifier.height(10.dp))
            SectionHeader(title = row.title)
            row.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SourcePrefKind.SWITCH -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = row.enabled) {
                        onCheckedChange(!row.checked)
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrefTitleSummary(row, Modifier.weight(1f))
                NocturneSwitch(
                    checked = row.checked,
                    onCheckedChange = onCheckedChange,
                    enabled = row.enabled
                )
            }
        }
        SourcePrefKind.CHECKBOX -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = row.enabled) {
                        onCheckedChange(!row.checked)
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrefTitleSummary(row, Modifier.weight(1f))
                Checkbox(
                    checked = row.checked,
                    onCheckedChange = { onCheckedChange(it) },
                    enabled = row.enabled
                )
            }
        }
        SourcePrefKind.CHOICE -> {
            EditorRow(
                row = row,
                currentValue = row.options
                    .getOrNull(row.selectedIndex),
                onOpen = onOpenChoice
            )
        }
        SourcePrefKind.TEXT -> {
            EditorRow(
                row = row,
                currentValue = row.text.takeIf { it.isNotBlank() },
                onOpen = onOpenText
            )
        }
        SourcePrefKind.MULTI_CHOICE -> {
            EditorRow(
                row = row,
                currentValue = when (row.selectedValues.size) {
                    0 -> "None chosen"
                    1 -> row.options.getOrNull(
                        row.optionValues.indexOf(
                            row.selectedValues.first()
                        )
                    ) ?: "1 chosen"
                    else -> "${row.selectedValues.size} chosen"
                },
                onOpen = onOpenMulti
            )
        }
        SourcePrefKind.ACTION -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = row.enabled, onClick = onClick)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrefTitleSummary(row, Modifier.weight(1f))
            }
        }
    }
}

/** A row that opens an editor: title + summary, with the
 * current value said plainly under it when the source's own
 * summary doesn't already carry it. */
@Composable
private fun EditorRow(
    row: SourcePrefRow,
    currentValue: String?,
    onOpen: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = row.enabled, onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge
            )
            val summary = row.summary?.takeIf { it.isNotBlank() }
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (currentValue != null) {
                Text(
                    currentValue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            "Change",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PrefTitleSummary(row: SourcePrefRow, modifier: Modifier) {
    Column(modifier) {
        Text(
            row.title,
            style = MaterialTheme.typography.bodyLarge
        )
        row.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
