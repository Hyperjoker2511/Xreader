package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.xreader.core.motion.listPlacementSpec
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.extension.ProviderPackManager
import app.xreader.data.extension.RepoManager
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.theme.primaryTextColor
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding

/**
 * v1.3.2 (scope E): the Repositories view — one card per
 * repository (name, source count, last-checked line), "Add
 * repository" at the top, and every card opening THAT
 * repository's own screen ([RepoSourcesScreen]) with its full
 * source list. The built-in sources get a card of their own
 * with the same treatment. (Before v1.3.2 this screen expanded
 * every repo's packages inline under store-wide filters; the
 * per-repo screens carry the searching and filtering now.)
 */
@Composable
fun SourceStoreScreen(
    viewModel: SourcesViewModel,
    onBack: () -> Unit,
    onOpenRepo: (Long) -> Unit = {},
    onOpenBuiltIn: () -> Unit = {},
    onAddRepo: () -> Unit = {},
    packViewModel: ProviderPackViewModel? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingRepoRemoval by remember { mutableStateOf<ExtensionRepo?>(null) }
    // Code 120 completion (M1): the M3 store-view knob — the
    // repository cards as a card grid under Material 3. Under
    // Nocturne (and at the List view) the shipped column below
    // renders exactly as before.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val isM3Store = designState.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3

    // v1.3.3 (V132-CHECK 9, scope S7e): the built-in sources
    // appear ONCE — as the friendly card below — never ALSO
    // as a raw repository row carrying Pin / Check for
    // updates / Remove and an internal xreader:// address.
    // Built-ins are not a user repo: they update with the
    // app, full stop, and no one should be one tap from
    // removing the default shelf.
    // v1.3.5 (Cycle 1): the provider-pack repo gets the same
    // treatment — it is a delivery channel, not a user repo,
    // so it never appears as a raw repository row either; the
    // pack card below carries its state and actions.
    val orderedRepos = state.repos
        .filter { it.indexUrl != RepoManager.BUNDLED_REPO_URL }
        .filter { it.indexUrl != ProviderPackManager.PACK_REPO_URL }
        .sortedWith(
            compareBy<ExtensionRepo>(
                { if (it.id in state.pinnedRepoIds) 0 else 1 },
                {
                    val idx = state.recentRepoIds.indexOf(it.id)
                    if (idx < 0) Int.MAX_VALUE else idx
                },
                { it.name.lowercase() }
            )
        )
    val builtInCount = state.extensions.count {
        it.packageId.startsWith("bundled.")
    }
    fun sourceCount(repo: ExtensionRepo): Int =
        state.foreignRepos[repo.id]?.sites?.size
            ?: state.catalogues[repo.id]?.size
            ?: state.extensions.count { it.repoId == repo.id }

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
                "Repositories",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        if (state.busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Every repository you've added, and the sources built " +
                "into the app. Tap one to see its sources.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        if (isM3Store &&
            designState.exploreStoreView ==
                app.xreader.data.prefs.M3ListView.CARD_GRID
        ) {
            // The card-grid form: the same content — warnings
            // and the pack span the full width (their actions
            // need the room), repositories stand two to a row.
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid
                    .GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout
                    .PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
            ) {
                item(
                    span = {
                        androidx.compose.foundation.lazy.grid
                            .GridItemSpan(maxLineSpan)
                    }
                ) {
                    PrimaryPill(
                        text = "Add repository",
                        onClick = onAddRepo,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                androidx.compose.foundation.lazy.grid.items(
                    state.repos.filter { it.id in state.changedRepos },
                    key = { "changed-${it.id}" },
                    span = {
                        androidx.compose.foundation.lazy.grid
                            .GridItemSpan(maxLineSpan)
                    }
                ) { repo ->
                    ChangedRepoCard(
                        repo = repo,
                        onAccept = { viewModel.acceptChangedIndex(repo) },
                        onDismiss = {
                            viewModel.dismissChangedIndex(repo)
                        },
                        modifier = Modifier.animateItem(
                            placementSpec = listPlacementSpec()
                        )
                    )
                }
                item(key = "repo-builtin") {
                    GlassCard(
                        onClick = onOpenBuiltIn,
                        modifier = Modifier.animateItem(
                            placementSpec = listPlacementSpec()
                        )
                    ) {
                        Text(
                            "Built-in sources",
                            style = MaterialTheme.typography.titleMedium,
                            color = primaryTextColor()
                        )
                        Text(
                            "$builtInCount sources · Ships with " +
                                "the app — always up to date",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                }
                if (packViewModel != null) {
                    item(
                        key = "repo-pack",
                        span = {
                            androidx.compose.foundation.lazy.grid
                                .GridItemSpan(maxLineSpan)
                        }
                    ) {
                        ProviderPackCard(packViewModel)
                    }
                }
                if (orderedRepos.isEmpty()) {
                    item(
                        span = {
                            androidx.compose.foundation.lazy.grid
                                .GridItemSpan(maxLineSpan)
                        }
                    ) {
                        KitEmptyState(
                            icon = Icons.Filled.Storefront,
                            message = "No repositories added yet. " +
                                "Add one and its sources will " +
                                "appear here."
                        )
                    }
                }
                androidx.compose.foundation.lazy.grid.items(
                    orderedRepos,
                    key = { "repo-${it.id}" }
                ) { repo ->
                    RepoCardGrid(
                        repo = repo,
                        sourceCount = sourceCount(repo),
                        pinned = repo.id in state.pinnedRepoIds,
                        busy = state.busy,
                        onOpen = { onOpenRepo(repo.id) },
                        onTogglePin = {
                            viewModel.setRepoPinned(
                                repo.id,
                                repo.id !in state.pinnedRepoIds
                            )
                        },
                        onRefresh = { viewModel.refreshRepo(repo) },
                        onRemove = { pendingRepoRemoval = repo },
                        modifier = Modifier.animateItem(
                            placementSpec = listPlacementSpec()
                        )
                    )
                }
                item(
                    span = {
                        androidx.compose.foundation.lazy.grid
                            .GridItemSpan(maxLineSpan)
                    }
                ) {
                    StoreSafetyLine()
                }
            }
        } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout
                .PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
        ) {
            item {
                PrimaryPill(
                    text = "Add repository",
                    onClick = onAddRepo,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Changed-index warnings (§5.5 Banner) ────────────
            items(
                state.repos.filter { it.id in state.changedRepos },
                key = { "changed-${it.id}" }
            ) { repo ->
                GlassCard(
                    Modifier.animateItem(
                        placementSpec = listPlacementSpec()
                    )
                ) {
                    Text(
                        "“${repo.name}” changed its list",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "The repository's list no longer matches " +
                            "the fingerprint from when you added " +
                            "it. That can be a normal update — or " +
                            "a sign the repository was taken over. " +
                            "Only accept if you trust it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        PrimaryPill(
                            text = "Accept the new list",
                            onClick = {
                                viewModel.acceptChangedIndex(repo)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        GhostPill(
                            text = "Not now",
                            onClick = {
                                viewModel.dismissChangedIndex(repo)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── Built-in sources ────────────────────────────────
            item(key = "repo-builtin") {
                GlassCard(
                    onClick = onOpenBuiltIn,
                    modifier = Modifier.animateItem(
                        placementSpec = listPlacementSpec()
                    )
                ) {
                    Text(
                        "Built-in sources",
                        style = MaterialTheme.typography.titleMedium,
                        color = primaryTextColor()
                    )
                    Text(
                        "$builtInCount sources · Ships with the app — " +
                            "always up to date",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                }
            }

            // ── Provider pack ───────────────────────────────────
            if (packViewModel != null) {
                item(key = "repo-pack") {
                    ProviderPackCard(packViewModel)
                }
            }

            if (orderedRepos.isEmpty()) {
                item {
                    KitEmptyState(
                        icon = Icons.Filled.Storefront,
                        message = "No repositories added yet. Add " +
                            "one and its sources will appear here."
                    )
                }
            }
            items(orderedRepos, key = { "repo-${it.id}" }) { repo ->
                val isLinkRepo =
                    repo.indexUrl == RepoManager.LINK_REPO_URL
                GlassCard(
                    onClick = { onOpenRepo(repo.id) },
                    modifier = Modifier.animateItem(
                        placementSpec = listPlacementSpec()
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                repo.name,
                                style = MaterialTheme.typography
                                    .titleMedium,
                                color = primaryTextColor()
                            )
                            Text(
                                // v1.3.3 (scope S7e): no
                                // "Not checked yet" jargon — a
                                // repo that was never refreshed
                                // simply shows its count.
                                listOfNotNull(
                                    "${sourceCount(repo)} sources",
                                    checkedLine(repo.lastFetchedAt)
                                        .takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography
                                    .bodySmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!isLinkRepo) {
                                Text(
                                    repo.indexUrl,
                                    style = MaterialTheme.typography
                                        .bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                viewModel.setRepoPinned(
                                    repo.id,
                                    repo.id !in state.pinnedRepoIds
                                )
                            }
                        ) {
                            Text(
                                if (repo.id in state.pinnedRepoIds) {
                                    "📌 Pinned"
                                } else {
                                    "Pin"
                                }
                            )
                        }
                    }
                    if (!isLinkRepo) {
                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = { viewModel.refreshRepo(repo) },
                                enabled = !state.busy
                            ) { Text("Check for updates") }
                            TextButton(
                                onClick = { pendingRepoRemoval = repo }
                            ) { Text("Remove") }
                        }
                    }
                }
            }

            // ── The safety line stands at the foot ──────────────
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Sources can only open the sites you allow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant
                    )
                }
            }
        }
        }
    }

    pendingRepoRemoval?.let { repo ->
        AlertDialog(
            onDismissRequest = { pendingRepoRemoval = null },
            title = { Text("Remove “${repo.name}”?") },
            text = {
                Text(
                    "Its sources will be uninstalled. Books you " +
                        "already downloaded stay in your library."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRepoRemoval = null
                    viewModel.removeRepo(repo)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRepoRemoval = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Code 120 completion: the changed-index warning card (§5.5
 * banner), shared by the store's list and card-grid forms —
 * the same words and the same two doors in both.
 */
@Composable
private fun ChangedRepoCard(
    repo: ExtensionRepo,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier) {
        Text(
            "“${repo.name}” changed its list",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "The repository's list no longer matches " +
                "the fingerprint from when you added " +
                "it. That can be a normal update — or " +
                "a sign the repository was taken over. " +
                "Only accept if you trust it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryPill(
                text = "Accept the new list",
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth()
            )
            GhostPill(
                text = "Not now",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Code 120 completion: one repository as a card-grid cell —
 * the list card's facts stacked (name, count · checked line,
 * address) with its doors (Pin / Check for updates / Remove)
 * on a wrapping row, so no action is lost in the grid form.
 * The link repository keeps its shipped exemption: no address
 * line and no Check/Remove doors.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RepoCardGrid(
    repo: ExtensionRepo,
    sourceCount: Int,
    pinned: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLinkRepo = repo.indexUrl == RepoManager.LINK_REPO_URL
    GlassCard(onClick = onOpen, modifier = modifier) {
        Text(
            repo.name,
            style = MaterialTheme.typography.titleMedium,
            color = primaryTextColor(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            listOfNotNull(
                "$sourceCount sources",
                checkedLine(repo.lastFetchedAt)
                    .takeIf { it.isNotBlank() }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!isLinkRepo) {
            Text(
                repo.indexUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onTogglePin) {
                Text(if (pinned) "📌 Pinned" else "Pin")
            }
            if (!isLinkRepo) {
                TextButton(onClick = onRefresh, enabled = !busy) {
                    Text("Check for updates")
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

/** The store's safety line, shared by both forms. */
@Composable
private fun StoreSafetyLine() {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Filled.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            "Sources can only open the sites you allow.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "Checked 3 days ago" for a repo card — quiet, human, and
 * never a fabricated time. A repo never refreshed shows
 * nothing here (v1.3.3: no "Not checked yet" jargon). */
private fun checkedLine(lastFetchedAt: Long?): String {
    if (lastFetchedAt == null) return ""
    val ageMs = System.currentTimeMillis() - lastFetchedAt
    val minutes = ageMs / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return "Checked " + when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes minute(s) ago"
        hours < 24 -> "$hours hour(s) ago"
        days < 30 -> "$days day(s) ago"
        days < 365 -> "${days / 30} month(s) ago"
        else -> "over a year ago"
    }
}
