package app.xreader.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SectionHeader
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * K5: the catalogue filter sheet — the first browse-filter
 * surface in the app (no source kind had one before). It
 * renders the source's own filter list through the pure
 * model in CatalogFilters (rows + state operations) and
 * edits the live filter objects in place, the donor's model;
 * Apply / Cancel / Reset belong to the catalogue ViewModel
 * (Apply hands the list to the search; Cancel restores the
 * states the sheet opened with; Reset fetches the source's
 * defaults afresh).
 *
 * Shape follows the reader's settings sheets: same sheet,
 * same radius, generous bottom padding so nothing sits under
 * the dock or the phone's dead bottom strip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceFilterSheet(
    viewModel: SourceCatalogViewModel,
    onDismiss: () -> Unit
) {
    val filters = viewModel.currentFilters() ?: return
    // Mutations land on the filter objects themselves (not
    // Compose state), so a revision counter drives re-reads
    // of the row models after each edit.
    var revision by remember { mutableIntStateOf(0) }
    val rows = remember(revision, filters) {
        FilterSheetModel.rows(filters)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    viewModel.resetFilters()
                    revision++
                }) { Text("Reset") }
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rows.forEach { row ->
                    FilterRowView(
                        row = row,
                        root = filters,
                        onMutated = { revision++ }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GhostPill(
                    text = "Cancel",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                PrimaryPill(
                    text = "Apply filters",
                    onClick = { viewModel.applyFilters() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * One filter row. The live filter is resolved from [root] by
 * the row's tree path on every render, so group children (and
 * any row) always mutate the object the search will read.
 * A row whose path no longer resolves renders label-only —
 * never a crash.
 */
@Composable
private fun FilterRowView(
    row: FilterRow,
    root: FilterList,
    onMutated: () -> Unit
) {
    val filter = FilterSheetModel.filterAt(root, row.id)
    when (row) {
        is FilterRow.Header -> {
            Spacer(Modifier.height(8.dp))
            SectionHeader(title = row.name)
        }
        is FilterRow.Separator -> {
            HorizontalDivider(
                Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        is FilterRow.Text -> {
            val text = filter as? Filter.Text
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { value ->
                        if (text != null) {
                            FilterStateOps.setText(text, value)
                            onMutated()
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is FilterRow.Check -> {
            val checkBox = filter as? Filter.CheckBox
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = checkBox != null) {
                        if (checkBox != null) {
                            FilterStateOps.toggle(checkBox)
                            onMutated()
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                NocturneSwitch(
                    checked = row.checked,
                    onCheckedChange = {
                        if (checkBox != null) {
                            FilterStateOps.toggle(checkBox)
                            onMutated()
                        }
                    }
                )
            }
        }
        is FilterRow.Tri -> {
            val triState = filter as? Filter.TriState
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                NocturneChip(
                    label = FilterStateOps.triStateWord(row.state),
                    selected = row.state != Filter.TriState.STATE_IGNORE,
                    onClick = {
                        if (triState != null) {
                            FilterStateOps.cycle(triState)
                            onMutated()
                        }
                    }
                )
            }
        }
        is FilterRow.Select -> {
            val select = filter as? Filter.Select<*>
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                row.options.forEachIndexed { index, option ->
                    OptionRow(
                        label = option,
                        chosen = index == row.selectedIndex,
                        onClick = {
                            if (select != null) {
                                FilterStateOps.select(select, index)
                                onMutated()
                            }
                        }
                    )
                }
            }
        }
        is FilterRow.Sort -> {
            val sort = filter as? Filter.Sort
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                row.options.forEachIndexed { index, option ->
                    val chosen = index == row.selectedIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = sort != null) {
                                if (sort != null) {
                                    FilterStateOps.sortTap(sort, index)
                                    onMutated()
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (chosen) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (chosen) {
                            Icon(
                                if (row.ascending) {
                                    Icons.Filled.ArrowUpward
                                } else {
                                    Icons.Filled.ArrowDownward
                                },
                                contentDescription = if (row.ascending) {
                                    "Ascending"
                                } else {
                                    "Descending"
                                },
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (row.ascending) "Ascending" else "Descending",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
        is FilterRow.Group -> {
            Spacer(Modifier.height(8.dp))
            SectionHeader(title = row.name)
            Column(Modifier.padding(start = 12.dp)) {
                row.children.forEach { child ->
                    FilterRowView(
                        row = child,
                        root = root,
                        onMutated = onMutated
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    chosen: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (chosen) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        if (chosen) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Chosen",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
