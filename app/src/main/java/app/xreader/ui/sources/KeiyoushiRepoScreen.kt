package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.extension.KeiyoushiIndex
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.SearchCapsule
import app.xreader.ui.theme.primaryTextColor

/**
 * Manga programme (M3): the Keiyoushi repository's own
 * screen — the manga catalogue rendered from
 * [KeiyoushiIndex.rows] (the M1 contract): every entry with
 * its name, language, rating badge and TRUE state.
 *
 * Keiyoushi player (K3): the states are the player's now.
 * A row is "Ready" when its extension is installed and
 * loaded in the player (or a native conversion exists);
 * every other row shows its real install state — Install /
 * Downloading / Needs your OK / Update available / the
 * failure's plain-words reason — with the action that
 * belongs to it. Installing runs the consent sheet first
 * (the honest DEX wording, the fingerprint, the signer);
 * nothing loads before the user agrees to the exact bytes.
 *
 * The rows arrive preference-filtered from the view model
 * (KeiyoushiIndex.rows applies the content preference); this
 * screen adds only the in-list search + language filter, in
 * the repo screen's own idiom.
 */
@Composable
fun KeiyoushiRepoScreen(
    viewModel: SourcesViewModel,
    repo: ExtensionRepo,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember(repo.id) { mutableStateOf("") }
    var language by remember(repo.id) { mutableStateOf<String?>(null) }

    val rows = state.keiyoushiRows
    // The in-list filter rides the repo screen's pure filter:
    // each catalogue row maps to a filter row (novel = true
    // only so the novels-only rule can never hide a manga
    // row — this screen has no such toggle).
    val filterRows = remember(rows) {
        rows.map { row ->
            row to RepoSourceFilter.Row(
                name = row.entry.name,
                lang = row.entry.lang,
                domain = RepoSourceFilter.hostOf(row.entry.baseUrl),
                novel = true
            )
        }
    }
    val visible = remember(filterRows, query, language) {
        filterRows.filter { (_, filterRow) ->
            RepoSourceFilter.matches(
                filterRow, query, language, novelsOnly = false
            )
        }.map { it.first }
    }
    val readyCount = remember(rows) {
        rows.count { it.state == KeiyoushiIndex.State.READY }
    }

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
                repo.name,
                style = MaterialTheme.typography.headlineSmall
            )
        }
        if (state.busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (rows.isEmpty()) {
                "The manga catalogue"
            } else if (visible.size == rows.size) {
                "${rows.size} manga sources · $readyCount ready"
            } else {
                "${rows.size} manga sources · showing ${visible.size}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            item {
                SearchCapsule(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search this catalogue…",
                    modifier = Modifier.fillMaxWidth()
                )
                val langs = RepoSourceFilter.languages(
                    filterRows.map { it.second }
                )
                if (langs.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    var languageMenuOpen by remember {
                        mutableStateOf(false)
                    }
                    Box {
                        NocturneChip(
                            label = "Language: " +
                                (language?.uppercase() ?: "All") + " ▾",
                            selected = language != null,
                            onClick = { languageMenuOpen = true }
                        )
                        DropdownMenu(
                            expanded = languageMenuOpen,
                            onDismissRequest = { languageMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All languages") },
                                onClick = {
                                    language = null
                                    languageMenuOpen = false
                                }
                            )
                            langs.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.uppercase()) },
                                    onClick = {
                                        language = lang
                                        languageMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                item {
                    KitEmptyState(
                        icon = Icons.Filled.Storefront,
                        message = "No source list is loaded for this " +
                            "repository yet — check for updates " +
                            "and its sources will appear here.",
                        actionText = "Check for updates",
                        onAction = { viewModel.refreshRepo(repo) }
                    )
                }
            }
            items(visible, key = { "keiyoushi-${it.entry.baseUrl}-${it.entry.lang}" }) { row ->
                GlassCard(
                    contentPadding = PaddingValues(
                        horizontal = 14.dp, vertical = 10.dp
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primary
                                .copy(alpha = 0.18f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Column(
                                verticalArrangement =
                                    Arrangement.Center,
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {
                                Text(
                                    row.entry.name.firstOrNull()
                                        ?.uppercase() ?: "?",
                                    style = MaterialTheme.typography
                                        .titleSmall,
                                    color = primaryTextColor()
                                )
                            }
                        }
                        Column(
                            Modifier.weight(1f)
                                .padding(horizontal = 10.dp)
                        ) {
                            Text(
                                row.entry.name,
                                style = MaterialTheme.typography
                                    .bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOfNotNull(
                                    row.entry.lang
                                        .takeIf { it.isNotBlank() }
                                        ?.uppercase(),
                                    RepoSourceFilter.hostOf(
                                        row.entry.baseUrl
                                    ).takeIf { it.isNotBlank() },
                                    // The rating badge rides
                                    // the same quiet line.
                                    row.badge
                                ).joinToString(" · "),
                                style = MaterialTheme.typography
                                    .bodySmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // K3: a failed or incompatible
                            // row says WHY, in plain words,
                            // right under its name.
                            if (row.detail != null) {
                                Text(
                                    row.detail,
                                    style = MaterialTheme.typography
                                        .bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        // K3: the TRUE state + its action.
                        // Ready only when the extension is
                        // installed and loaded (or a native
                        // conversion exists); a busy state
                        // shows its words; an actionable
                        // state shows its button.
                        Column(
                            horizontalAlignment =
                                Alignment.End,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            val showLabel =
                                row.action ==
                                    KeiyoushiIndex.RowAction.NONE ||
                                    row.state ==
                                    KeiyoushiIndex.State.READY ||
                                    row.state ==
                                    KeiyoushiIndex.State.UPDATE_AVAILABLE
                            if (showLabel) {
                                Text(
                                    row.state.label,
                                    style = MaterialTheme.typography
                                        .labelLarge,
                                    color = if (
                                        row.state ==
                                        KeiyoushiIndex.State.READY
                                    ) {
                                        MaterialTheme.colorScheme
                                            .secondary
                                    } else {
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    },
                                    maxLines = 2
                                )
                            }
                            when (row.action) {
                                KeiyoushiIndex.RowAction.INSTALL ->
                                    app.xreader.ui.common.PrimaryPill(
                                        text = "Install",
                                        onClick = {
                                            viewModel.installKeiyoushi(
                                                row.entry.packageName
                                            )
                                        },
                                        enabled = !state.busy
                                    )
                                KeiyoushiIndex.RowAction.REVIEW ->
                                    app.xreader.ui.common.PrimaryPill(
                                        text = "Review",
                                        onClick = {
                                            viewModel
                                                .reviewKeiyoushiConsent(
                                                    row.entry.packageName
                                                )
                                        },
                                        enabled = !state.busy
                                    )
                                KeiyoushiIndex.RowAction.UPDATE ->
                                    app.xreader.ui.common.PrimaryPill(
                                        text = "Update",
                                        onClick = {
                                            viewModel.installKeiyoushi(
                                                row.entry.packageName
                                            )
                                        },
                                        enabled = !state.busy
                                    )
                                KeiyoushiIndex.RowAction.REMOVE ->
                                    androidx.compose.material3
                                        .TextButton(
                                            onClick = {
                                                viewModel
                                                    .uninstallKeiyoushi(
                                                        row.entry.packageName
                                                    )
                                            }
                                        ) { Text("Remove") }
                                KeiyoushiIndex.RowAction.NONE -> Unit
                            }
                        }
                    }
                }
            }
            if (rows.isNotEmpty() && visible.isEmpty()) {
                item {
                    KitEmptyState(
                        icon = Icons.Filled.Storefront,
                        message = "Nothing in this catalogue " +
                            "matches that search."
                    )
                }
            }
        }
    }

    // ── K3: the install consent sheet ─────────────────────
    // The file has been downloaded and verified (signer ==
    // the catalogue's published Keiyoushi key); this sheet
    // is the user's agreement to run THOSE bytes, shown
    // once per install/update — never silently, and the
    // manager loads nothing until it is confirmed.
    state.keiyoushiConsent?.let { prompt ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissKeiyoushiConsent() },
            title = { Text("Install “${prompt.displayName}”?") },
            text = {
                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {
                    Text(prompt.summary)
                    Text(
                        "Version ${prompt.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                    Text(
                        "Signed by the Keiyoushi key " +
                            (prompt.signerSha256
                                ?.take(16) ?: "") + "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                    Text(
                        "File fingerprint: " +
                            prompt.fingerprint.take(16) + "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.confirmKeiyoushiConsent() }
                ) { Text("Install") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.dismissKeiyoushiConsent() }
                ) { Text("Cancel") }
            }
        )
    }
}
