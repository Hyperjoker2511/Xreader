package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import app.xreader.data.db.ExtensionRepo
import app.xreader.data.extension.ForeignSite
import app.xreader.data.extension.IndexPackage
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SearchCapsule
import app.xreader.ui.theme.primaryTextColor

/**
 * v1.3.2 (scope E): ONE repository's own screen — only its
 * sources, the FULL list reachable (no "…and N more" dead end),
 * an in-list search and a language filter, rows of
 * name + language + domain + one Add button that becomes
 * "Added ✓", and a plain count line. Novel-led per the repo's
 * nature: a mixed repo (Keiyoushi) leads with its novel shelf
 * and keeps the rest behind a "show all" toggle; a novels-only
 * repo (LNReader) simply lists everything.
 *
 * [builtIn] renders the Built-in sources shelf instead of a
 * repository (whose [repo] row may legitimately be absent while
 * state loads, so null never means built-in).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RepoSourcesScreen(
    viewModel: SourcesViewModel,
    repo: ExtensionRepo?,
    onBack: () -> Unit,
    onOpenExtension: (Long) -> Unit = {},
    builtIn: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val foreign = repo?.let { state.foreignRepos[it.id] }
    var query by remember(repo?.id, builtIn) { mutableStateOf("") }
    var language by remember(repo?.id, builtIn) { mutableStateOf<String?>(null) }
    var novelsOnly by remember(repo?.id, builtIn) { mutableStateOf(true) }
    var pendingInstall by remember { mutableStateOf<IndexPackage?>(null) }

    // ── Resolve the rows for this repo's nature ─────────────────
    val installedNames = remember(state.extensions) {
        state.extensions.map {
            app.xreader.data.extension.WorkMatcher
                .normaliseTitle(it.displayName)
        }.toSet()
    }
    val installedByPkg = remember(state.extensions) {
        state.extensions.associateBy { it.packageId }
    }
    data class Entry(
        val id: String,
        val row: RepoSourceFilter.Row,
        val site: ForeignSite? = null,
        val pkg: IndexPackage? = null,
        val extensionId: Long? = null,
        val added: Boolean = false
    )
    val entries: List<Entry> = when {
        builtIn -> state.extensions
            .filter { it.packageId.startsWith("bundled.") }
            .map { ext ->
                Entry(
                    id = "ext-${ext.id}",
                    row = RepoSourceFilter.Row(
                        name = ext.displayName,
                        lang = ext.languages.split(",")
                            .firstOrNull()?.trim() ?: "",
                        domain = "",
                        novel = true
                    ),
                    extensionId = ext.id,
                    added = true
                )
            }
        foreign != null -> foreign.sites.map { site ->
            Entry(
                id = "site-${site.pluginId ?: site.baseUrl}",
                row = RepoSourceFilter.Row(
                    name = site.name,
                    lang = site.lang,
                    domain = RepoSourceFilter.hostOf(site.baseUrl),
                    novel = RepoSourceFilter.isNovelSite(
                        site, foreign.ecosystemName
                    )
                ),
                site = site,
                added = app.xreader.data.extension.WorkMatcher
                    .normaliseTitle(site.name) in installedNames
            )
        }
        repo != null && state.catalogues[repo.id] != null ->
            (state.catalogues[repo.id] ?: emptyList()).map { pkg ->
                Entry(
                    id = "pkg-${pkg.pkg}",
                    row = RepoSourceFilter.Row(
                        name = pkg.name,
                        lang = pkg.languages.joinToString(", "),
                        domain = pkg.sources.firstOrNull()
                            ?.let { RepoSourceFilter.hostOf(it.baseUrl) }
                            ?: "",
                        novel = pkg.name.contains(
                            "novel", ignoreCase = true
                        )
                    ),
                    pkg = pkg,
                    added = pkg.pkg in installedByPkg
                )
            }
        repo != null -> state.extensions
            .filter { it.repoId == repo.id }
            .map { ext ->
                Entry(
                    id = "ext-${ext.id}",
                    row = RepoSourceFilter.Row(
                        name = ext.displayName,
                        lang = ext.languages.split(",")
                            .firstOrNull()?.trim() ?: "",
                        domain = "",
                        novel = true
                    ),
                    extensionId = ext.id,
                    added = true
                )
            }
        else -> emptyList()
    }
    val allRows = entries.map { it.row }
    val showNovelToggle = !builtIn &&
        RepoSourceFilter.hasNonNovelRows(allRows)
    val visibleEntries = entries
        .filter { entry ->
            RepoSourceFilter.matches(
                entry.row, query, language,
                novelsOnly = showNovelToggle && novelsOnly
            )
        }
        .sortedWith { a, b ->
            RepoSourceFilter.ORDER.compare(a.row, b.row)
        }
    val visible = visibleEntries.map { it.row }

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
                if (builtIn) "Built-in sources" else repo?.name ?: "",
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
            when {
                builtIn ->
                    "${entries.size} sources · built into Xreader, " +
                        "always up to date"
                visible.size == entries.size ->
                    "${entries.size} sources"
                else ->
                    "${entries.size} sources · showing ${visible.size}"
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
                    placeholder = "Search this repository…",
                    modifier = Modifier.fillMaxWidth()
                )
                val langs = RepoSourceFilter.languages(allRows)
                if (langs.size > 1 || showNovelToggle) {
                    Spacer(Modifier.height(8.dp))
                    // v1.3.3 (V132-CHECK 8, scope S7d): the
                    // language CHIP WALL (~35 chips on a big
                    // repo) collapses into ONE compact picker;
                    // Novels-only stays a single toggle beside
                    // it, and the search field above leads.
                    var languageMenuOpen by remember {
                        mutableStateOf(false)
                    }
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                        verticalAlignment =
                            androidx.compose.ui.Alignment
                                .CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (showNovelToggle) {
                            NocturneChip(
                                label = "Novels only",
                                selected = novelsOnly,
                                onClick = { novelsOnly = !novelsOnly }
                            )
                        }
                        if (langs.size > 1) {
                            androidx.compose.foundation.layout.Box {
                                NocturneChip(
                                    label = "Language: " +
                                        (language?.uppercase()
                                            ?: "All") + " ▾",
                                    selected = language != null,
                                    onClick = { languageMenuOpen = true }
                                )
                                androidx.compose.material3
                                    .DropdownMenu(
                                        expanded = languageMenuOpen,
                                        onDismissRequest = {
                                            languageMenuOpen = false
                                        }
                                    ) {
                                        androidx.compose.material3
                                            .DropdownMenuItem(
                                                text = {
                                                    Text("All languages")
                                                },
                                                onClick = {
                                                    language = null
                                                    languageMenuOpen =
                                                        false
                                                }
                                            )
                                        langs.forEach { lang ->
                                            androidx.compose.material3
                                                .DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            lang.uppercase()
                                                        )
                                                    },
                                                    onClick = {
                                                        language = lang
                                                        languageMenuOpen =
                                                            false
                                                    }
                                                )
                                        }
                                    }
                            }
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                item {
                    KitEmptyState(
                        icon = Icons.Filled.Storefront,
                        message = if (repo != null && foreign == null) {
                            "No source list is loaded for this " +
                                "repository yet — check for updates " +
                                "and its sources will appear here."
                        } else {
                            "Nothing here yet."
                        },
                        actionText = if (
                            repo != null && foreign == null
                        ) {
                            "Check for updates"
                        } else {
                            null
                        },
                        onAction = if (repo != null && foreign == null) {
                            { viewModel.refreshRepo(repo) }
                        } else {
                            null
                        }
                    )
                }
            }
            items(visibleEntries, key = { "repo-row-${it.id}" }) { entry ->
                GlassCard(
                    onClick = {
                        entry.extensionId?.let { onOpenExtension(it) }
                    },
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
                                    entry.row.name.firstOrNull()
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
                                entry.row.name,
                                style = MaterialTheme.typography
                                    .bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                listOfNotNull(
                                    entry.row.lang
                                        .takeIf { it.isNotBlank() }
                                        ?.uppercase(),
                                    entry.row.domain
                                        .takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography
                                    .bodySmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        when {
                            entry.added && entry.pkg != null -> Column(
                                horizontalAlignment =
                                    Alignment.End
                            ) {
                                Text(
                                    "Added ✓",
                                    style = MaterialTheme.typography
                                        .labelLarge,
                                    color = MaterialTheme.colorScheme
                                        .secondary
                                )
                                TextButton(
                                    onClick = {
                                        installedByPkg[entry.pkg.pkg]
                                            ?.let {
                                                viewModel
                                                    .uninstallExtension(it)
                                            }
                                    },
                                    enabled = !state.busy
                                ) { Text("Uninstall") }
                            }
                            entry.added -> Text(
                                "Added ✓",
                                style = MaterialTheme.typography
                                    .labelLarge,
                                color = MaterialTheme.colorScheme
                                    .secondary
                            )
                            entry.pkg != null -> PrimaryPill(
                                text = "Add",
                                onClick = {
                                    pendingInstall = entry.pkg
                                },
                                enabled = !state.busy
                            )
                            entry.site != null -> PrimaryPill(
                                text = "Add",
                                onClick = {
                                    val site = entry.site
                                    if (app.xreader.data.extension
                                            .FamilyTemplates.hasTemplate(
                                                site.family
                                            )
                                    ) {
                                        // The family's reading
                                        // logic is known — the
                                        // source installs ready.
                                        viewModel
                                            .installFamilySource(site)
                                    } else {
                                        viewModel
                                            .createSourceFromSite(site)
                                    }
                                },
                                enabled = !state.busy
                            )
                        }
                    }
                }
            }
            if (entries.isNotEmpty() && visibleEntries.isEmpty()) {
                item {
                    KitEmptyState(
                        icon = Icons.Filled.Storefront,
                        message = "Nothing in this repository " +
                            "matches that search."
                    )
                }
            }
        }
    }

    // ── Native install consent (same words as the store's) ────
    if (repo != null) {
        pendingInstall?.let { pkg ->
            val permissions = pkg.installPermissions()
            AlertDialog(
                onDismissRequest = { pendingInstall = null },
                title = { Text("Add “${pkg.name}”?") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            permissions.consentSummary(
                                pkg.kindEnum?.label ?: "source"
                            )
                        )
                        Text(
                            "Version ${pkg.versionName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
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
                    TextButton(onClick = { pendingInstall = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
