package app.xreader.ui.sources

import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import app.xreader.core.motion.pressBounce
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.extension.ExtensionKind
import app.xreader.data.extension.IndexPackage
import app.xreader.data.extension.RepoManager
import app.xreader.ui.common.GlassCard
import app.xreader.ui.theme.errorTextColor
import app.xreader.ui.theme.primaryTextColor

/**
 * v1.0.5 (C3/D23): the Source store — repositories as a pushed
 * screen of their own (the Mihon pattern: repo management lives
 * one tap deep, Browse stays a list of YOUR sources). Search,
 * the Installed/Updates/Language filter chips, pinned +
 * recently-used ordering, install rows and the changed-list
 * warnings all moved here unchanged from the old Browse scroll;
 * the safety line stands at the store's foot.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SourceStoreScreen(
    viewModel: SourcesViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingInstall by remember { mutableStateOf<Pair<ExtensionRepo, IndexPackage>?>(null) }
    var pendingRepoRemoval by remember { mutableStateOf<ExtensionRepo?>(null) }
    var pendingUninstall by remember { mutableStateOf<InstalledExtension?>(null) }
    var repoQuery by remember { mutableStateOf("") }
    // v1.0.4 (D11): one shared press source for the filter chips.
    val repoChipInteraction = remember { MutableInteractionSource() }
    var repoFilter by remember { mutableStateOf("ALL") }
    var repoLanguage by remember { mutableStateOf<String?>(null) }

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
            Text("Source store", style = MaterialTheme.typography.headlineSmall)
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Repositories you've added, and the sources they offer. " +
                "Pinned and recently used repositories come first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Changed-index warnings ──────────────────────────
            items(state.repos.filter { it.id in state.changedRepos }, key = { "changed-${it.id}" }) { repo ->
                GlassCard(Modifier.animateItem(placementSpec = listPlacementSpec())) {
                    Text(
                        "“${repo.name}” changed its list",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "The repository's list no longer matches the fingerprint from when " +
                            "you added it. That can be a normal update — or a sign the " +
                            "repository was taken over. Only accept if you trust it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.acceptChangedIndex(repo) }) {
                            Text("Accept the new list")
                        }
                        TextButton(onClick = { viewModel.dismissChangedIndex(repo) }) {
                            Text("Not now")
                        }
                    }
                }
            }

            // ── Search + filters (v1.0.2 R14 storefront) ────────
            item {
                OutlinedTextField(
                    value = repoQuery,
                    onValueChange = { repoQuery = it },
                    label = { Text("Search repositories and sources") },
                    placeholder = {
                        Text("Name, site address or language…")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "ALL" to "All",
                        "INSTALLED" to "Installed",
                        "UPDATES" to "Updates"
                    ).forEach { (key, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = repoFilter == key,
                            onClick = { repoFilter = key },
                            label = { Text(label) },
                            interactionSource = repoChipInteraction,
                            modifier = Modifier.pressBounce(repoChipInteraction)
                        )
                    }
                    val languages = state.catalogues.values.flatten()
                        .flatMap { it.languages }.distinct().sorted()
                    languages.forEach { lang ->
                        androidx.compose.material3.FilterChip(
                            selected = repoFilter == "LANGUAGE" &&
                                repoLanguage == lang,
                            onClick = {
                                repoFilter = "LANGUAGE"
                                repoLanguage = lang
                            },
                            label = { Text(lang.uppercase()) },
                            interactionSource = repoChipInteraction,
                            modifier = Modifier.pressBounce(repoChipInteraction)
                        )
                    }
                }
            }
            val installedByPkg = state.extensions.associateBy { it.packageId }
            val orderedRepos = state.repos.sortedWith(
                compareBy<ExtensionRepo>(
                    { if (it.id in state.pinnedRepoIds) 0 else 1 },
                    {
                        val idx = state.recentRepoIds.indexOf(it.id)
                        if (idx < 0) Int.MAX_VALUE else idx
                    },
                    { it.name.lowercase() }
                )
            )
            fun pkgMatches(repo: ExtensionRepo, pkg: IndexPackage): Boolean {
                val q = repoQuery.trim().lowercase()
                val queryHit = q.isEmpty() ||
                    repo.name.lowercase().contains(q) ||
                    repo.indexUrl.lowercase().contains(q) ||
                    pkg.name.lowercase().contains(q) ||
                    pkg.languages.any { it.lowercase().contains(q) } ||
                    pkg.sources.any {
                        it.baseUrl.lowercase().contains(q) ||
                            it.name.lowercase().contains(q)
                    }
                if (!queryHit) return false
                return when (repoFilter) {
                    "INSTALLED" -> pkg.pkg in installedByPkg
                    "UPDATES" -> state.updates.containsKey(pkg.pkg)
                    "LANGUAGE" -> repoLanguage == null ||
                        repoLanguage in pkg.languages
                    else -> true
                }
            }
            val visibleRepos = orderedRepos.mapNotNull { repo ->
                val catalogue = (state.catalogues[repo.id] ?: emptyList())
                    .filter { pkgMatches(repo, it) }
                val filtering = repoQuery.isNotBlank() || repoFilter != "ALL"
                if (filtering && catalogue.isEmpty() &&
                    (state.catalogues[repo.id] ?: emptyList()).isNotEmpty()
                ) {
                    null
                } else {
                    repo to catalogue
                }
            }
            if (state.repos.isEmpty()) {
                item {
                    Text(
                        "No repositories yet. Add one from “Add a source” " +
                            "and its sources will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(visibleRepos, key = { "repo-${it.first.id}" }) { (repo, catalogue) ->
                RepoCard(
                    repo = repo,
                    catalogue = catalogue,
                    installedByPkg = installedByPkg,
                    updates = state.updates,
                    pinned = repo.id in state.pinnedRepoIds,
                    busy = state.busy,
                    onRefresh = { viewModel.refreshRepo(repo) },
                    onRemove = { pendingRepoRemoval = repo },
                    onInstall = { pkg -> pendingInstall = repo to pkg },
                    onUpdatePkg = { pkg ->
                        state.updates[pkg.pkg]?.let { viewModel.applyUpdate(it) }
                    },
                    onUninstall = { ext -> pendingUninstall = ext },
                    onTogglePin = {
                        viewModel.setRepoPinned(repo.id, repo.id !in state.pinnedRepoIds)
                    },
                    modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                )
            }
            if (visibleRepos.isEmpty() && state.repos.isNotEmpty()) {
                item {
                    Text(
                        "Nothing matches that search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── The safety line stands at the store's foot ──────
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
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
        }
    }

    // ── Install consent ─────────────────────────────────────────
    pendingInstall?.let { (repo, pkg) ->
        val permissions = pkg.installPermissions()
        AlertDialog(
            onDismissRequest = { pendingInstall = null },
            title = { Text("Add “${pkg.name}”?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(permissions.consentSummary(pkg.kindEnum?.label ?: "source"))
                    if (pkg.kindEnum == ExtensionKind.DEX) {
                        Text(
                            "This kind runs REAL app code inside Xreader — the strongest " +
                                "kind there is. Only install it from a repository you fully " +
                                "trust, and it will still ask for a separate opt-in before " +
                                "it can run.",
                            color = errorTextColor()
                        )
                    }
                    Text(
                        "Version ${pkg.versionName} • Fingerprint " +
                            fingerprintPreview(pkg),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingInstall = null
                    viewModel.install(repo, pkg)
                }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { pendingInstall = null }) { Text("Cancel") }
            }
        )
    }

    pendingRepoRemoval?.let { repo ->
        AlertDialog(
            onDismissRequest = { pendingRepoRemoval = null },
            title = { Text("Remove “${repo.name}”?") },
            text = {
                Text("Its sources will be uninstalled. Books you already downloaded stay in your library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRepoRemoval = null
                    viewModel.removeRepo(repo)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRepoRemoval = null }) { Text("Cancel") }
            }
        )
    }

    pendingUninstall?.let { ext ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("Uninstall “${ext.displayName}”?") },
            text = {
                Text(
                    "The source leaves your list. Books you already " +
                        "downloaded stay in your library."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUninstall = null
                    viewModel.uninstallExtension(ext)
                }) { Text("Uninstall") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("Cancel") }
            }
        )
    }
}

private fun fingerprintPreview(pkg: IndexPackage): String {
    val fp = if (pkg.kindEnum == ExtensionKind.DECLARATIVE) {
        app.xreader.data.extension.DeclarativeFingerprints.of(pkg.sources)
    } else {
        pkg.sha256
    }
    return if (fp.length > 12) fp.take(12) + "…" else fp
}

/**
 * v1.0.2 (R14): a repository as a small app store — pin, name and
 * address, then one row per source with chips (version · languages ·
 * kind), a status, and ONE action: Install / Update / Uninstall.
 * (Re-homed to the Source store screen in v1.0.5, unchanged.)
 */
@Composable
private fun RepoCard(
    repo: ExtensionRepo,
    catalogue: List<IndexPackage>,
    installedByPkg: Map<String, InstalledExtension>,
    updates: Map<String, RepoManager.UpdateCandidate>,
    pinned: Boolean,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onInstall: (IndexPackage) -> Unit,
    onUpdatePkg: (IndexPackage) -> Unit,
    onUninstall: (InstalledExtension) -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLinkRepo = repo.indexUrl == RepoManager.LINK_REPO_URL
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(repo.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isLinkRepo) "Sources you added from links" else repo.indexUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onTogglePin) {
                Text(if (pinned) "📌 Pinned" else "Pin")
            }
        }
        if (!isLinkRepo) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Check for updates") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
        if (catalogue.isEmpty() && !isLinkRepo) {
            Text(
                "Tap “Check for updates” to load its source list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        catalogue.forEach { pkg ->
            val installedExt = installedByPkg[pkg.pkg]
                ?.takeIf { it.repoId == repo.id || isLinkRepo }
            val hasUpdate = updates.containsKey(pkg.pkg)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            pkg.name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.titleSmall,
                            color = primaryTextColor()
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(pkg.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "v${pkg.versionName} · " +
                            pkg.languages.joinToString(", ") { it.uppercase() } +
                            " · ${pkg.kindEnum?.label ?: pkg.kind}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (installedExt != null) {
                        Text(
                            if (hasUpdate) "Update available" else "Installed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasUpdate) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                    }
                }
                when {
                    installedExt != null && hasUpdate ->
                        TextButton(
                            onClick = { onUpdatePkg(pkg) },
                            enabled = !busy
                        ) { Text("Update") }
                    installedExt != null ->
                        TextButton(
                            onClick = { onUninstall(installedExt) },
                            enabled = !busy
                        ) { Text("Uninstall") }
                    else ->
                        TextButton(
                            onClick = { onInstall(pkg) },
                            enabled = !busy
                        ) { Text("Install") }
                }
            }
        }
    }
}
