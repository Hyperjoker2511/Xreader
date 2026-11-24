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
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
// Code 121 (W4): the scratch-M3 (R15) branch's components.
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar

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

    // ── Code 121 (W4, scratch R15): the Material 3 Manage ──
    // sources — a small top app bar over the same rows as list
    // items: avatar, name, provenance line, the enabled switch,
    // and the "Needs repair" assist chip in error tones where a
    // source is broken. Filters are official filter chips. (The
    // row overflow's Preferences / Details / Remove doors have
    // no navigation callbacks on this route — the shell wires
    // only onBack — so they are not drawn; see D-121-W4-5.)
    // Under Nocturne the shipped screen renders byte-identically.
    if (isM3Design()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Manage sources") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    "Every source in Xreader. Switch one off to hide " +
                        "it from Explore and search — your books and " +
                        "downloads stay exactly as they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = filters.name,
                    onValueChange = viewModel::setName,
                    label = { Text("Search by name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                M3ManageFilterGroup(label = "Kind") {
                    FilterChip(
                        selected = filters.kind == KindFilter.ALL,
                        onClick = { viewModel.setKind(KindFilter.ALL) },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = filters.kind == KindFilter.NOVELS,
                        onClick = { viewModel.setKind(KindFilter.NOVELS) },
                        label = { Text("Novels") }
                    )
                    FilterChip(
                        selected = filters.kind == KindFilter.MANGA,
                        onClick = { viewModel.setKind(KindFilter.MANGA) },
                        label = { Text("Manga") }
                    )
                }
                M3ManageFilterGroup(label = "Language") {
                    FilterChip(
                        selected = filters.language == LanguageFilter.ALL,
                        onClick = {
                            viewModel.setLanguage(LanguageFilter.ALL)
                        },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = filters.language == LanguageFilter.EN,
                        onClick = {
                            viewModel.setLanguage(LanguageFilter.EN)
                        },
                        label = { Text("English") }
                    )
                    FilterChip(
                        selected = filters.language == LanguageFilter.JP,
                        onClick = {
                            viewModel.setLanguage(LanguageFilter.JP)
                        },
                        label = { Text("Japanese") }
                    )
                    FilterChip(
                        selected = filters.language == LanguageFilter.CN,
                        onClick = {
                            viewModel.setLanguage(LanguageFilter.CN)
                        },
                        label = { Text("Chinese") }
                    )
                    FilterChip(
                        selected = filters.language == LanguageFilter.KR,
                        onClick = {
                            viewModel.setLanguage(LanguageFilter.KR)
                        },
                        label = { Text("Korean") }
                    )
                    FilterChip(
                        selected = filters.language ==
                            LanguageFilter.UNKNOWN,
                        onClick = {
                            viewModel.setLanguage(LanguageFilter.UNKNOWN)
                        },
                        label = { Text("Unknown") }
                    )
                }
                M3ManageFilterGroup(label = "From") {
                    FilterChip(
                        selected = filters.origin == OriginFilter.ALL,
                        onClick = {
                            viewModel.setOrigin(OriginFilter.ALL)
                        },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = filters.origin == OriginFilter.SHIPPED,
                        onClick = {
                            viewModel.setOrigin(OriginFilter.SHIPPED)
                        },
                        label = { Text("Shipped with Xreader") }
                    )
                    FilterChip(
                        selected = filters.origin == OriginFilter.PACK,
                        onClick = {
                            viewModel.setOrigin(OriginFilter.PACK)
                        },
                        label = { Text("Provider pack") }
                    )
                    FilterChip(
                        selected = filters.origin == OriginFilter.LINK,
                        onClick = {
                            viewModel.setOrigin(OriginFilter.LINK)
                        },
                        label = { Text("Added by link") }
                    )
                    FilterChip(
                        selected = filters.origin == OriginFilter.REPO,
                        onClick = {
                            viewModel.setOrigin(OriginFilter.REPO)
                        },
                        label = { Text("Repo") }
                    )
                }
                M3ManageFilterGroup(label = "Showing") {
                    FilterChip(
                        selected = filters.showing == ShowingFilter.ALL,
                        onClick = {
                            viewModel.setShowing(ShowingFilter.ALL)
                        },
                        label = { Text("All") }
                    )
                    FilterChip(
                        selected = filters.showing == ShowingFilter.SHOWN,
                        onClick = {
                            viewModel.setShowing(ShowingFilter.SHOWN)
                        },
                        label = { Text("Shown") }
                    )
                    FilterChip(
                        selected = filters.showing == ShowingFilter.HIDDEN,
                        onClick = {
                            viewModel.setShowing(ShowingFilter.HIDDEN)
                        },
                        label = { Text("Hidden") }
                    )
                }
                Text(
                    "${rows.size} of ${allRows.size} sources",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                if (rows.isEmpty()) {
                    Text(
                        if (allRows.isEmpty()) {
                            "No sources installed yet."
                        } else {
                            "No sources match these filters."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(rows, key = { it.extension.id }) { row ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        row.extension.displayName,
                                        style = MaterialTheme
                                            .typography.titleMedium
                                    )
                                },
                                supportingContent = {
                                    Column {
                                        Text(
                                            "${row.provenanceLabel} · " +
                                                "${row.languageLabel} · " +
                                                row.kindLabel,
                                            style = MaterialTheme
                                                .typography.bodySmall,
                                            color = MaterialTheme
                                                .colorScheme.onSurfaceVariant
                                        )
                                        if (row.extension.needsRepair) {
                                            AssistChip(
                                                onClick = {},
                                                enabled = false,
                                                label = {
                                                    Text(
                                                        if (row.extension
                                                                .needsRepairReason
                                                                ?.startsWith(
                                                                    "reading not proven"
                                                                ) == true
                                                        ) {
                                                            "Added, reading not proven"
                                                        } else {
                                                            "Needs repair"
                                                        }
                                                    )
                                                },
                                                colors = AssistChipDefaults
                                                    .assistChipColors(
                                                        disabledLabelColor =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .error,
                                                        disabledContainerColor =
                                                            MaterialTheme
                                                                .colorScheme
                                                                .errorContainer
                                                    )
                                            )
                                        }
                                    }
                                },
                                leadingContent = {
                                    M3InitialAvatar(
                                        row.extension.displayName
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = row.shown,
                                        onCheckedChange = { enabled ->
                                            viewModel.setEnabled(
                                                row, enabled
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
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
                contentPadding = PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
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

/** Code 121 (W4): one labelled filter-chip group in the M3
 * Manage sources branch — the shipped [FilterGroup]'s shape
 * with official filter chips. */
@Composable
private fun M3ManageFilterGroup(
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
