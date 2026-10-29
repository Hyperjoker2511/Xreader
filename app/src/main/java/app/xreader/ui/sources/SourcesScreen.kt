package app.xreader.ui.sources
import app.xreader.ui.theme.primaryTextColor
import app.xreader.ui.theme.errorTextColor

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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.db.InstalledExtension
import app.xreader.data.db.TrustState
import app.xreader.data.extension.ExtensionKind
import app.xreader.data.extension.IndexPackage
import app.xreader.data.extension.RepoManager
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.StatusPill
import app.xreader.ui.common.XreaderPrimaryButton

/**
 * The Sources surface (Stitch "Sources" screen; PLAN §5 + Amendment 7):
 * installed sources with live status, repositories with their
 * catalogues, "Add a repository" and — right beside it — "Add from
 * link". OPDS library servers live one tap deeper (they moved off this
 * tab's top level when sources arrived).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    onOpenSource: (extensionId: Long, sourceId: String) -> Unit,
    onOpenExtension: (extensionId: Long) -> Unit,
    onOpenOpdsServers: () -> Unit,
    onAddFromLink: () -> Unit,
    /** v1.0.1 declutter: global search lives on its own screen. */
    onSearchAll: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRepoForm by remember { mutableStateOf(false) }
    var repoName by remember { mutableStateOf("") }
    var repoUrl by remember { mutableStateOf("") }
    var pendingInstall by remember { mutableStateOf<Pair<ExtensionRepo, IndexPackage>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val legadoFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText()
            }.getOrNull()
            if (text != null) viewModel.importLegadoText(text)
        }
    }
    var pendingRepoRemoval by remember { mutableStateOf<ExtensionRepo?>(null) }
    // ── v1.0.2 (R14): storefront search + filters ────────────────
    var repoQuery by remember { mutableStateOf("") }
    var repoFilter by remember { mutableStateOf("ALL") }
    var repoLanguage by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<InstalledExtension?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        ScreenHeader(
            title = "Browse",
            eyebrow = "Sources",
            action = {
                TextButton(
                    onClick = { viewModel.refreshAll() },
                    enabled = !state.busy
                ) { Text("Check for updates") }
            }
        )
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Search everything: ONE-LINE PILL (v1.0.2, item 20
            // — the v1.0.1 card read as content, not as search) ──
            item {
                Surface(
                    onClick = onSearchAll,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                        .copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("🔍", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Search all your sources",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Add a source ────────────────────────────────────────
            item {
                GlassCard {
                    Text("Add a source", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Add a repository of sources, or paste a novel site's link and " +
                            "Xreader will set it up for you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        XreaderPrimaryButton(
                            text = "Add a repository",
                            onClick = { showRepoForm = !showRepoForm },
                            modifier = Modifier.weight(1f)
                        )
                        XreaderPrimaryButton(
                            text = "Add from link",
                            onClick = onAddFromLink,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            legadoFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }
                    ) {
                        Text("Import a Legado source file (.json)")
                    }
                    Text(
                        "Legado (阅读) book sources convert into Xreader sources after a test read.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (showRepoForm) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = repoName,
                            onValueChange = { repoName = it },
                            label = { Text("Name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = repoUrl,
                            onValueChange = { repoUrl = it },
                            label = { Text("Repository list address") },
                            placeholder = { Text("https://…/index.json") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        XreaderPrimaryButton(
                            text = "Add repository",
                            onClick = {
                                viewModel.addRepo(repoName, repoUrl)
                                repoName = ""
                                repoUrl = ""
                                showRepoForm = false
                            },
                            enabled = repoUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── Foreign repo explanation card (v1.0.1) ─────────────
            state.foreignRepo?.let { foreign ->
                item {
                    GlassCard {
                        Text(
                            when (foreign.ecosystemName) {
                                "Legado" -> "This is a Legado source file"
                                "another reader app" ->
                                    "This looks like another reader app's repository"
                                else -> "This is a ${foreign.ecosystemName} repository"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Xreader can't install extensions made for other apps, " +
                                "but it can build its own source for the same sites. " +
                                "Pick a site below and Xreader will test-read it first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        foreign.sites.take(40).forEach { site ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(site.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        listOfNotNull(
                                            site.lang.takeIf { it.isNotBlank() },
                                            site.baseUrl
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.createSourceFromSite(site) },
                                    enabled = !state.busy
                                ) { Text("Create Xreader source") }
                            }
                        }
                        if (foreign.sites.size > 40) {
                            Text(
                                "…and ${foreign.sites.size - 40} more sites in that list.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { viewModel.dismissForeignRepo() }) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // ── Legado import report (v1.0.1) ──────────────────────
            if (state.legadoRows.isNotEmpty()) {
                item {
                    GlassCard {
                        Text(
                            "Legado sources",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        state.legadoRows.forEach { row ->
                            Text(
                                (if (row.converted) "✓ " else "• ") + row.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                row.outcome,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            // ── Changed-index warnings ──────────────────────────────
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

            // ── Installed sources ───────────────────────────────────
            item {
                Text(
                    "Installed sources",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (state.extensions.isEmpty()) {
                item {
                    EmptyState(
                        title = "No sources yet",
                        message = "Install one from a repository below, or add a site " +
                            "you read with “Add from link”.",
                        actionLabel = "Add from link",
                        onAction = onAddFromLink
                    )
                }
            }
            items(state.extensions, key = { "ext-${it.id}" }) { ext ->
                InstalledSourceCard(
                    ext = ext,
                    hasUpdate = state.updates[ext.packageId] != null,
                    hasPending = state.pendings.containsKey(ext.packageId),
                    healthLine = ext.sourceIds.split(",")
                        .firstOrNull()?.trim()
                        ?.let { firstSource ->
                            state.health[
                                app.xreader.data.extension.SourceHealthRecord
                                    .key(ext.id, firstSource)
                            ]?.summaryLine()
                        },
                    onOpen = {
                        val firstSource = ext.sourceIds.split(",").firstOrNull()?.trim()
                        if (firstSource.isNullOrBlank() || ext.sourceIds.contains(",")) {
                            onOpenExtension(ext.id)
                        } else {
                            onOpenSource(ext.id, firstSource)
                        }
                    },
                    onDetails = { onOpenExtension(ext.id) },
                    onUpdate = { state.updates[ext.packageId]?.let { viewModel.applyUpdate(it) } },
                    modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                )
            }

            // ── Repositories (v1.0.2 R14 STOREFRONT: search across
            // name/domain/language, Installed/Updates/Language
            // filters, pinned + recently-used first) ─────────────
            item {
                Text(
                    "Repositories",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
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
                            label = { Text(label) }
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
                            label = { Text(lang.uppercase()) }
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

            // ── OPDS + trust note ───────────────────────────────────
            item {
                GlassCard(onClick = onOpenOpdsServers) {
                    Text("Library servers (OPDS)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Komga, Kavita and Calibre-Web servers you added.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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

    // ── Install consent ─────────────────────────────────────────────
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

@Composable
private fun InstalledSourceCard(
    ext: InstalledExtension,
    hasUpdate: Boolean,
    hasPending: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    /** v1.0.2 (R13): the last test-drive verdict, when checked. */
    healthLine: String? = null
) {
    val (statusText, statusColor) = when {
        ext.trustState == TrustState.CHANGED_REQUIRES_RETRUST ->
            "Needs re-trust" to MaterialTheme.colorScheme.error
        hasPending -> "Update waiting" to MaterialTheme.colorScheme.error
        !ext.enabled -> "Off" to MaterialTheme.colorScheme.onSurfaceVariant
        hasUpdate -> "Update available" to MaterialTheme.colorScheme.primary
        else -> "Online" to MaterialTheme.colorScheme.secondary
    }
    GlassCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                modifier = Modifier.size(44.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        ext.displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = primaryTextColor()
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    ext.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${ext.languages.split(",").firstOrNull()?.uppercase() ?: ""} • v${ext.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (healthLine != null) {
                    Text(
                        healthLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StatusPill(text = statusText, color = statusColor)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasUpdate) {
                XreaderPrimaryButton(text = "Update", onClick = onUpdate)
            }
            XreaderPrimaryButton(
                text = "Open",
                onClick = onOpen,
                enabled = ext.enabled && ext.trustState == TrustState.TRUSTED
            )
            TextButton(onClick = onDetails) { Text("Details") }
        }
    }
}

/**
 * v1.0.2 (R14): a repository as a small app store — pin, name and
 * address, then one row per source with chips (version · languages ·
 * kind), a status, and ONE action: Install / Update / Uninstall.
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
