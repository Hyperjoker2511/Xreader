package app.xreader.ui.sources

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch

/**
 * v1.5.2 (FIX-15): Manage sources — the dedicated source-
 * management screen (owner directive, 2026-11-03). Every
 * installed source is listed here, shown AND hidden, with
 * its provenance, language and kind, a show/hide switch
 * wired to the existing §8.1 enabled flag, and visible
 * labelled filters (name / kind / language / origin /
 * showing). Explore stays the clean browse view of shown
 * sources; this screen is reached in one tap from Explore's
 * add door ("Manage sources"), never through a menu.
 *
 * Slop-check (design gate): built only from the established
 * kit — GlassCard rows, NocturneChip filter groups with
 * plain text labels above each group, NocturneSwitch, and
 * the kit empty state. No new visual devices.
 */
@Composable
fun ManageSourcesScreen(
    viewModel: ManageSourcesViewModel,
    onBack: () -> Unit
) {
    val rows by viewModel.rows.collectAsState()
    val allRows by viewModel.allRows.collectAsState()
    val filters by viewModel.filters.collectAsState()

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
            Text(
                "Manage sources",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Every source in Xreader. Switch one off to hide " +
                "it from Explore and search — your books and " +
                "downloads stay exactly as they are.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = filters.name,
            onValueChange = viewModel::setName,
            label = { Text("Search by name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        FilterGroup(label = "Kind") {
            NocturneChip(
                label = "All",
                selected = filters.kind == KindFilter.ALL,
                onClick = { viewModel.setKind(KindFilter.ALL) }
            )
            NocturneChip(
                label = "Novels",
                selected = filters.kind == KindFilter.NOVELS,
                onClick = { viewModel.setKind(KindFilter.NOVELS) }
            )
            NocturneChip(
                label = "Manga",
                selected = filters.kind == KindFilter.MANGA,
                onClick = { viewModel.setKind(KindFilter.MANGA) }
            )
        }
        FilterGroup(label = "Language") {
            NocturneChip(
                label = "All",
                selected = filters.language == LanguageFilter.ALL,
                onClick = { viewModel.setLanguage(LanguageFilter.ALL) }
            )
            NocturneChip(
                label = "English",
                selected = filters.language == LanguageFilter.EN,
                onClick = { viewModel.setLanguage(LanguageFilter.EN) }
            )
            NocturneChip(
                label = "Japanese",
                selected = filters.language == LanguageFilter.JP,
                onClick = { viewModel.setLanguage(LanguageFilter.JP) }
            )
            NocturneChip(
                label = "Chinese",
                selected = filters.language == LanguageFilter.CN,
                onClick = { viewModel.setLanguage(LanguageFilter.CN) }
            )
            NocturneChip(
                label = "Korean",
                selected = filters.language == LanguageFilter.KR,
                onClick = { viewModel.setLanguage(LanguageFilter.KR) }
            )
            NocturneChip(
                label = "Unknown",
                selected = filters.language == LanguageFilter.UNKNOWN,
                onClick = { viewModel.setLanguage(LanguageFilter.UNKNOWN) }
            )
        }
        FilterGroup(label = "From") {
            NocturneChip(
                label = "All",
                selected = filters.origin == OriginFilter.ALL,
                onClick = { viewModel.setOrigin(OriginFilter.ALL) }
            )
            NocturneChip(
                label = "Shipped with Xreader",
                selected = filters.origin == OriginFilter.SHIPPED,
                onClick = { viewModel.setOrigin(OriginFilter.SHIPPED) }
            )
            NocturneChip(
                label = "Provider pack",
                selected = filters.origin == OriginFilter.PACK,
                onClick = { viewModel.setOrigin(OriginFilter.PACK) }
            )
            NocturneChip(
                label = "Added by link",
                selected = filters.origin == OriginFilter.LINK,
                onClick = { viewModel.setOrigin(OriginFilter.LINK) }
            )
            NocturneChip(
                label = "Repo",
                selected = filters.origin == OriginFilter.REPO,
                onClick = { viewModel.setOrigin(OriginFilter.REPO) }
            )
        }
        FilterGroup(label = "Showing") {
            NocturneChip(
                label = "All",
                selected = filters.showing == ShowingFilter.ALL,
                onClick = { viewModel.setShowing(ShowingFilter.ALL) }
            )
            NocturneChip(
                label = "Shown",
                selected = filters.showing == ShowingFilter.SHOWN,
                onClick = { viewModel.setShowing(ShowingFilter.SHOWN) }
            )
            NocturneChip(
                label = "Hidden",
                selected = filters.showing == ShowingFilter.HIDDEN,
                onClick = { viewModel.setShowing(ShowingFilter.HIDDEN) }
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${rows.size} of ${allRows.size} sources",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        if (rows.isEmpty()) {
            KitEmptyState(
                icon = Icons.Filled.Extension,
                message = if (allRows.isEmpty()) {
                    "No sources installed yet."
                } else {
                    "No sources match these filters."
                }
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(rows, key = { it.extension.id }) { row ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.extension.displayName,
                                    style =
                                        MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${row.provenanceLabel} · " +
                                        "${row.languageLabel} · " +
                                        row.kindLabel,
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                if (row.extension.needsRepair) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        // v1.5.2 (round-15, §2): a
                                        // source saved from an
                                        // unproven probe shows its
                                        // own state here, not the
                                        // generic repair mark.
                                        if (row.extension.needsRepairReason
                                                ?.startsWith(
                                                    "reading not proven"
                                                ) == true
                                        ) {
                                            "Added, reading not proven"
                                        } else {
                                            "Needs repair"
                                        },
                                        style = MaterialTheme.typography
                                            .bodySmall,
                                        color = MaterialTheme.colorScheme
                                            .error
                                    )
                                }
                            }
                            NocturneSwitch(
                                checked = row.shown,
                                onCheckedChange = { enabled ->
                                    viewModel.setEnabled(row, enabled)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One labelled filter group: a plain text label above a
 * horizontally scrollable row of kit chips. */
@Composable
private fun FilterGroup(
    label: String,
    chips: @Composable () -> Unit
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips()
    }
    Spacer(Modifier.height(10.dp))
}
