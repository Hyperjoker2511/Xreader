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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.SearchCapsule
// Code 121 (W4): the scratch-M3 (R27) branch's components.
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import app.xreader.ui.theme.primaryTextColor
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding

/**
 * The Keiyoushi shelf (manga programme M3; player K3;
 * donor-parity rebuild v1.4.3). ONE row per extension
 * PACKAGE — Komikku's unit — grouped the donor's way:
 * Updates pending (with Update all) · Installed · Available
 * by language. Search is name-first over the WHOLE shelf
 * ([KeiyoushiIndex.searchShelf]); a domain can only surface
 * a row after every name match.
 *
 * States are the player's truth: Ready only when the
 * extension is installed AND loaded; every other row shows
 * its real state — Install / Downloading / Needs your OK /
 * Update available / the failure's plain-words reason —
 * with the action that belongs to it. Installing runs the
 * consent sheet first (the honest DEX wording, the
 * fingerprint, the signer); nothing loads before the user
 * agrees to the exact bytes.
 *
 * v1.4.3: progress is PER ROW (spinner + Cancel on the busy
 * row only) — the pre-1.4.3 shelf disabled every row behind
 * one global busy flag, so a single stuck install faded
 * the whole list forever. Opening the shelf also runs the
 * player's update detection: an update is something the
 * app notices, not something the user must remember to
 * ask for. The 18+ chip at the top-right drives the same
 * ContentVisibility preference as Customise.
 */
@Composable
fun KeiyoushiRepoScreen(
    viewModel: SourcesViewModel,
    repo: ExtensionRepo,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Code 120 completion (M1): the M3 store-view knob — this
    // shelf as a card grid under Material 3, group structure
    // and per-row actions unchanged. Under Nocturne (and at
    // the List view) the shipped column renders as before.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val isM3Shelf = designState.design !=
        app.xreader.data.prefs.AppDesign.NOCTURNE
    var query by remember(repo.id) { mutableStateOf("") }
    var language by remember(repo.id) { mutableStateOf<String?>(null) }

    // Update detection on shelf open (by repo identity —
    // this screen only ever renders for the Keiyoushi repo).
    LaunchedEffect(repo.id) { viewModel.checkKeiyoushiUpdates() }

    val rows = state.keiyoushiRows
    val languageFiltered = remember(rows, language) {
        if (language == null) {
            rows
        } else {
            rows.filter { row ->
                row.langs.any { it.equals(language, ignoreCase = true) }
            }
        }
    }
    val searching = query.isNotBlank()
    val searchResults = remember(languageFiltered, query) {
        if (searching) {
            KeiyoushiIndex.searchShelf(languageFiltered, query)
        } else {
            emptyList()
        }
    }
    val updates = remember(languageFiltered) {
        languageFiltered.filter {
            KeiyoushiIndex.shelfGroupOf(it) ==
                KeiyoushiIndex.ShelfGroup.UPDATES
        }
    }
    val installed = remember(languageFiltered) {
        languageFiltered.filter {
            KeiyoushiIndex.shelfGroupOf(it) ==
                KeiyoushiIndex.ShelfGroup.INSTALLED
        }
    }
    val availableByLang = remember(languageFiltered) {
        languageFiltered
            .filter {
                KeiyoushiIndex.shelfGroupOf(it) ==
                    KeiyoushiIndex.ShelfGroup.AVAILABLE
            }
            .groupBy { it.primaryLang }
            .toSortedMap()
    }
    val readyCount = remember(rows) {
        rows.count { it.state == KeiyoushiIndex.State.READY }
    }
    val allLangs = remember(rows) {
        rows.flatMap { it.langs }.distinct().sorted()
    }

    // ── Code 121 (W4, scratch R27): the Keiyoushi shelf in ──
    // Material 3 — the R17 store grid scoped to this repository:
    // repo header with Refresh, a search field filtering the
    // shelf, language filter chips, the shelf's own sections
    // (Updates pending · Installed · Available by language) as
    // outlined cards with Install/Installed toggles. The install
    // consent dialog is the shipped one, unchanged. Under
    // Nocturne the shipped shelf below renders byte-identically.
    if (isM3Shelf) {
        Column(Modifier.fillMaxSize()) {
            MediumTopAppBar(
                title = {
                    Text(
                        repo.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshRepo(repo) },
                        enabled = !state.busy
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                    IconButton(
                        onClick = { viewModel.toggleAdultVisibility() }
                    ) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = "Adult content: " +
                                state.contentVisibility.label
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
                    if (rows.isEmpty()) {
                        "The manga catalogue"
                    } else {
                        "${rows.size} manga sources · $readyCount ready"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.busy) {
                    LinearWavyProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search this catalogue") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                if (allLangs.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = language == null,
                            onClick = { language = null },
                            label = { Text("All languages") }
                        )
                        allLangs.forEach { lang ->
                            FilterChip(
                                selected = language == lang,
                                onClick = { language = lang },
                                label = {
                                    Text(
                                        if (lang == "all") {
                                            "All languages (multi)"
                                        } else {
                                            lang.uppercase()
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (rows.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                verticalArrangement =
                                    Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "No source list is loaded for " +
                                        "this repository yet — check " +
                                        "for updates and its sources " +
                                        "will appear here.",
                                    style = MaterialTheme
                                        .typography.bodyMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        viewModel.refreshRepo(repo)
                                    }
                                ) { Text("Check for updates") }
                            }
                        }
                    } else if (searching) {
                        if (searchResults.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Text(
                                    "Nothing in this catalogue matches " +
                                        "that search.",
                                    style = MaterialTheme
                                        .typography.bodyMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                            }
                        } else {
                            items(
                                searchResults,
                                key = { "m3-keiyoushi-${it.packageName}" }
                            ) { row ->
                                M3KeiyoushiCard(row, viewModel, state)
                            }
                        }
                    } else {
                        if (updates.isNotEmpty()) {
                            item(
                                key = "m3-shelf-updates-head",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                Row(
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    M3GroupHeader(
                                        "Updates pending",
                                        Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            viewModel.updateAllKeiyoushi()
                                        }
                                    ) { Text("Update all") }
                                }
                            }
                            items(
                                updates,
                                key = { "m3-keiyoushi-${it.packageName}" }
                            ) { row ->
                                M3KeiyoushiCard(row, viewModel, state)
                            }
                        }
                        if (installed.isNotEmpty()) {
                            item(
                                key = "m3-shelf-installed-head",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                M3GroupHeader("Installed")
                            }
                            items(
                                installed,
                                key = { "m3-keiyoushi-${it.packageName}" }
                            ) { row ->
                                M3KeiyoushiCard(row, viewModel, state)
                            }
                        }
                        availableByLang.forEach { (lang, langRows) ->
                            item(
                                key = "m3-shelf-lang-$lang",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                M3GroupHeader(
                                    if (lang == "all") {
                                        "All languages (multi)"
                                    } else {
                                        lang.uppercase()
                                    }
                                )
                            }
                            items(
                                langRows,
                                key = { "m3-keiyoushi-${it.packageName}" }
                            ) { row ->
                                M3KeiyoushiCard(row, viewModel, state)
                            }
                        }
                    }
                }
            }
        }
        // The K3 install consent sheet — the same dialog the
        // shipped shelf shows (see below); nothing is loaded
        // until it is confirmed.
        state.keiyoushiConsent?.let { prompt ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { viewModel.dismissKeiyoushiConsent() },
                title = { Text("Install “${prompt.displayName}”?") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                (prompt.signerSha256?.take(16) ?: "") +
                                "…",
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
                    TextButton(
                        onClick = { viewModel.confirmKeiyoushiConsent() }
                    ) { Text("Install") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissKeiyoushiConsent() }
                    ) { Text("Cancel") }
                }
            )
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
                repo.name,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            AdultVisibilityToggle(
                visibility = state.contentVisibility,
                onToggle = { viewModel.toggleAdultVisibility() }
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
            } else {
                "${rows.size} manga sources · $readyCount ready"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        if (isM3Shelf &&
            designState.exploreStoreView ==
                app.xreader.data.prefs.M3ListView.CARD_GRID
        ) {
            // The card-grid form: search spans the top, the
            // shelf's groups keep their headers (full span),
            // rows stand two to a row as compact cards with
            // the same states and actions.
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid
                    .GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(
                    bottom = bottomContentPadding(
                        LocalUiMetrics.current, dockVisible = false
                    )
                )
            ) {
                item(
                    span = {
                        androidx.compose.foundation.lazy.grid
                            .GridItemSpan(maxLineSpan)
                    }
                ) {
                    SearchCapsule(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search this catalogue…",
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (allLangs.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        var gridLanguageMenuOpen by remember {
                            mutableStateOf(false)
                        }
                        Box {
                            NocturneChip(
                                label = "Language: " +
                                    (language?.uppercase()
                                        ?: "All") + " ▾",
                                selected = language != null,
                                onClick = {
                                    gridLanguageMenuOpen = true
                                }
                            )
                            DropdownMenu(
                                expanded = gridLanguageMenuOpen,
                                onDismissRequest = {
                                    gridLanguageMenuOpen = false
                                }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All languages") },
                                    onClick = {
                                        language = null
                                        gridLanguageMenuOpen = false
                                    }
                                )
                                allLangs.forEach { lang ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (lang == "all") {
                                                    "All languages (multi)"
                                                } else {
                                                    lang.uppercase()
                                                }
                                            )
                                        },
                                        onClick = {
                                            language = lang
                                            gridLanguageMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                if (rows.isEmpty()) {
                    item(
                        span = {
                            androidx.compose.foundation.lazy.grid
                                .GridItemSpan(maxLineSpan)
                        }
                    ) {
                        KitEmptyState(
                            icon = Icons.Filled.Storefront,
                            message = "No source list is loaded " +
                                "for this repository yet — check " +
                                "for updates and its sources will " +
                                "appear here.",
                            actionText = "Check for updates",
                            onAction = { viewModel.refreshRepo(repo) }
                        )
                    }
                } else if (searching) {
                    if (searchResults.isEmpty()) {
                        item(
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            KitEmptyState(
                                icon = Icons.Filled.Storefront,
                                message = "Nothing in this catalogue " +
                                    "matches that search."
                            )
                        }
                    } else {
                        items(
                            searchResults,
                            key = { "keiyoushi-pkg-${it.packageName}" }
                        ) { row ->
                            KeiyoushiShelfGridCard(row, viewModel, state)
                        }
                    }
                } else {
                    if (updates.isNotEmpty()) {
                        item(
                            key = "shelf-updates-head",
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            Row(
                                verticalAlignment =
                                    Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Updates pending",
                                    style = MaterialTheme.typography
                                        .titleSmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        viewModel.updateAllKeiyoushi()
                                    }
                                ) { Text("Update all") }
                            }
                        }
                        items(
                            updates,
                            key = { "keiyoushi-pkg-${it.packageName}" }
                        ) { row ->
                            KeiyoushiShelfGridCard(row, viewModel, state)
                        }
                    }
                    if (installed.isNotEmpty()) {
                        item(
                            key = "shelf-installed-head",
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            Text(
                                "Installed",
                                style = MaterialTheme.typography
                                    .titleSmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                        items(
                            installed,
                            key = { "keiyoushi-pkg-${it.packageName}" }
                        ) { row ->
                            KeiyoushiShelfGridCard(row, viewModel, state)
                        }
                    }
                    for ((lang, langRows) in availableByLang) {
                        item(
                            key = "shelf-avail-head-$lang",
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            Text(
                                if (lang.isBlank()) {
                                    "Available"
                                } else if (lang == "all") {
                                    "Available · All languages"
                                } else {
                                    "Available · ${lang.uppercase()}"
                                },
                                style = MaterialTheme.typography
                                    .titleSmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                        items(
                            langRows,
                            key = { "keiyoushi-pkg-${it.packageName}" }
                        ) { row ->
                            KeiyoushiShelfGridCard(row, viewModel, state)
                        }
                    }
                    if (languageFiltered.isEmpty()) {
                        item(
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            KitEmptyState(
                                icon = Icons.Filled.Storefront,
                                message = "Nothing in this catalogue " +
                                    "matches that filter."
                            )
                        }
                    }
                }
            }
        } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
        ) {
            item {
                SearchCapsule(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search this catalogue…",
                    modifier = Modifier.fillMaxWidth()
                )
                if (allLangs.size > 1) {
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
                        // Code 128 (audit E2): the menu wears
                        // the face's menu dress — the
                        // SelectionBar menu's Nocturne form
                        // (16dp capsule shape,
                        // surfaceContainerHigh, 1dp
                        // outlineVariant border), not the bare
                        // stock menu.
                        DropdownMenu(
                            expanded = languageMenuOpen,
                            onDismissRequest = { languageMenuOpen = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme
                                .surfaceContainerHigh,
                            border = androidx.compose.foundation
                                .BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme
                                        .outlineVariant
                                )
                        ) {
                            DropdownMenuItem(
                                text = { Text("All languages") },
                                onClick = {
                                    language = null
                                    languageMenuOpen = false
                                }
                            )
                            allLangs.forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (lang == "all") {
                                                "All languages (multi)"
                                            } else {
                                                lang.uppercase()
                                            }
                                        )
                                    },
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
            } else if (searching) {
                if (searchResults.isEmpty()) {
                    item {
                        KitEmptyState(
                            icon = Icons.Filled.Storefront,
                            message = "Nothing in this catalogue " +
                                "matches that search."
                        )
                    }
                } else {
                    items(
                        searchResults,
                        key = { "keiyoushi-pkg-${it.packageName}" }
                    ) { row ->
                        KeiyoushiShelfRow(row, viewModel, state)
                    }
                }
            } else {
                if (updates.isNotEmpty()) {
                    item(key = "shelf-updates-head") {
                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Updates pending",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { viewModel.updateAllKeiyoushi() }
                            ) { Text("Update all") }
                        }
                    }
                    items(
                        updates,
                        key = { "keiyoushi-pkg-${it.packageName}" }
                    ) { row ->
                        KeiyoushiShelfRow(row, viewModel, state)
                    }
                }
                if (installed.isNotEmpty()) {
                    item(key = "shelf-installed-head") {
                        Text(
                            "Installed",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                    items(
                        installed,
                        key = { "keiyoushi-pkg-${it.packageName}" }
                    ) { row ->
                        KeiyoushiShelfRow(row, viewModel, state)
                    }
                }
                for ((lang, langRows) in availableByLang) {
                    item(key = "shelf-avail-head-$lang") {
                        Text(
                            if (lang.isBlank()) {
                                "Available"
                            } else if (lang == "all") {
                                "Available · All languages"
                            } else {
                                "Available · ${lang.uppercase()}"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                    items(
                        langRows,
                        key = { "keiyoushi-pkg-${it.packageName}" }
                    ) { row ->
                        KeiyoushiShelfRow(row, viewModel, state)
                    }
                }
                if (languageFiltered.isEmpty()) {
                    item {
                        KitEmptyState(
                            icon = Icons.Filled.Storefront,
                            message = "Nothing in this catalogue " +
                                "matches that filter."
                        )
                    }
                }
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

/**
 * Code 120 completion: one shelf package as a card-grid cell
 * — the shelf row's facts stacked (letter tile + name, the
 * meta line, the failure's reason, the update signer line)
 * with the state's label and its own action on the bottom
 * row. Busy still means this row's spinner + Cancel only.
 */
@Composable
private fun KeiyoushiShelfGridCard(
    row: KeiyoushiIndex.PackageRow,
    viewModel: SourcesViewModel,
    state: SourcesUiState
) {
    val busy = row.packageName in state.keiyoushiBusyPackages
    GlassCard(
        contentPadding = PaddingValues(
            horizontal = 12.dp, vertical = 10.dp
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary
                    .copy(alpha = 0.18f),
                modifier = Modifier.size(38.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    Text(
                        row.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        color = primaryTextColor()
                    )
                }
            }
            Text(
                row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            listOfNotNull(
                row.langs
                    .joinToString(", ") { it.uppercase() }
                    .takeIf { it.isNotBlank() },
                row.domains.firstOrNull(),
                if (row.sourceCount > 1) {
                    "${row.sourceCount} sources"
                } else {
                    null
                },
                row.badge
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (row.detail != null) {
            Text(
                row.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (row.state == KeiyoushiIndex.State.UPDATE_AVAILABLE) {
            val signer =
                state.keiyoushiInstalledSigners[row.packageName]
            Text(
                if (signer != null) {
                    "Signed by the Keiyoushi key " +
                        signer.take(16) + "…"
                } else {
                    "Signed by the Keiyoushi key"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    TextButton(
                        onClick = {
                            viewModel.cancelKeiyoushiInstall(
                                row.packageName
                            )
                        }
                    ) { Text("Cancel") }
                }
            } else {
                val showLabel =
                    row.action == KeiyoushiIndex.RowAction.NONE ||
                        row.state == KeiyoushiIndex.State.READY ||
                        row.state ==
                        KeiyoushiIndex.State.UPDATE_AVAILABLE
                if (showLabel) {
                    Text(
                        row.state.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (
                            row.state == KeiyoushiIndex.State.READY
                        ) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                when (row.action) {
                    KeiyoushiIndex.RowAction.INSTALL ->
                        PrimaryPill(
                            text = "Install",
                            onClick = {
                                viewModel.installKeiyoushi(
                                    row.packageName
                                )
                            }
                        )
                    KeiyoushiIndex.RowAction.REVIEW ->
                        PrimaryPill(
                            text = "Review",
                            onClick = {
                                viewModel.reviewKeiyoushiConsent(
                                    row.packageName
                                )
                            }
                        )
                    KeiyoushiIndex.RowAction.UPDATE ->
                        PrimaryPill(
                            text = "Update",
                            onClick = {
                                viewModel.installKeiyoushi(
                                    row.packageName
                                )
                            }
                        )
                    KeiyoushiIndex.RowAction.REMOVE ->
                        TextButton(
                            onClick = {
                                viewModel.uninstallKeiyoushi(
                                    row.packageName
                                )
                            }
                        ) { Text("Remove") }
                    KeiyoushiIndex.RowAction.NONE -> Unit
                }
            }
        }
    }
}

/**
 * One shelf row: letter tile, name, the quiet meta line
 * (language(s) · first host · source count · rating badge),
 * the failure's plain-words reason when the state carries
 * one, and the state's own action. A busy row shows its
 * spinner + Cancel; no other row is ever disabled by it.
 * The one verb for a not-installed row is "Install".
 */
@Composable
private fun KeiyoushiShelfRow(
    row: KeiyoushiIndex.PackageRow,
    viewModel: SourcesViewModel,
    state: SourcesUiState
) {
    val busy = row.packageName in state.keiyoushiBusyPackages
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
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    Text(
                        row.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleSmall,
                        color = primaryTextColor()
                    )
                }
            }
            Column(
                Modifier.weight(1f).padding(horizontal = 10.dp)
            ) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        row.langs
                            .joinToString(", ") { it.uppercase() }
                            .takeIf { it.isNotBlank() },
                        row.domains.firstOrNull(),
                        if (row.sourceCount > 1) {
                            "${row.sourceCount} sources"
                        } else {
                            null
                        },
                        row.badge
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.detail != null) {
                    Text(
                        row.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // v1.5.39 (item 4): the quiet signer line on
                // an update row — the key that signed the
                // copy already installed, shown BEFORE the
                // tap, in the consent sheet's own words. The
                // staged update is still verified against the
                // catalogue key in the player; an update
                // whose signer differs from this one falls
                // back to the full consent sheet (the two-tap
                // apply lives in SourcesViewModel
                // .installKeiyoushi).
                if (row.state ==
                    KeiyoushiIndex.State.UPDATE_AVAILABLE
                ) {
                    val signer =
                        state.keiyoushiInstalledSigners[
                            row.packageName
                        ]
                    Text(
                        if (signer != null) {
                            "Signed by the Keiyoushi key " +
                                signer.take(16) + "…"
                        } else {
                            "Signed by the Keiyoushi key"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme
                            .onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        TextButton(
                            onClick = {
                                viewModel.cancelKeiyoushiInstall(
                                    row.packageName
                                )
                            }
                        ) { Text("Cancel") }
                    }
                } else {
                    val showLabel =
                        row.action == KeiyoushiIndex.RowAction.NONE ||
                            row.state == KeiyoushiIndex.State.READY ||
                            row.state ==
                            KeiyoushiIndex.State.UPDATE_AVAILABLE
                    if (showLabel) {
                        Text(
                            row.state.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (
                                row.state == KeiyoushiIndex.State.READY
                            ) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            },
                            maxLines = 2
                        )
                    }
                    when (row.action) {
                        KeiyoushiIndex.RowAction.INSTALL ->
                            PrimaryPill(
                                text = "Install",
                                onClick = {
                                    viewModel.installKeiyoushi(
                                        row.packageName
                                    )
                                }
                            )
                        KeiyoushiIndex.RowAction.REVIEW ->
                            PrimaryPill(
                                text = "Review",
                                onClick = {
                                    viewModel.reviewKeiyoushiConsent(
                                        row.packageName
                                    )
                                }
                            )
                        KeiyoushiIndex.RowAction.UPDATE ->
                            PrimaryPill(
                                text = "Update",
                                onClick = {
                                    viewModel.installKeiyoushi(
                                        row.packageName
                                    )
                                }
                            )
                        KeiyoushiIndex.RowAction.REMOVE ->
                            TextButton(
                                onClick = {
                                    viewModel.uninstallKeiyoushi(
                                        row.packageName
                                    )
                                }
                            ) { Text("Remove") }
                        KeiyoushiIndex.RowAction.NONE -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Code 121 (W4, scratch R27): one Keiyoushi package as an
 * outlined store card — the shelf row's facts (initial avatar,
 * name, language · domain · count line, the failure's reason,
 * the update signer line) with the state's label and its own
 * action: the Install/Installed toggle family of R17. Busy
 * still means this row's spinner + Cancel only.
 */
@Composable
private fun M3KeiyoushiCard(
    row: KeiyoushiIndex.PackageRow,
    viewModel: SourcesViewModel,
    state: SourcesUiState
) {
    val busy = row.packageName in state.keiyoushiBusyPackages
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            M3InitialAvatar(row.name)
            Text(
                row.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                listOfNotNull(
                    row.langs
                        .joinToString(", ") { it.uppercase() }
                        .takeIf { it.isNotBlank() },
                    row.domains.firstOrNull(),
                    if (row.sourceCount > 1) {
                        "${row.sourceCount} sources"
                    } else {
                        null
                    },
                    row.badge
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (row.detail != null) {
                Text(
                    row.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (row.state == KeiyoushiIndex.State.UPDATE_AVAILABLE) {
                val signer = state.keiyoushiInstalledSigners[row.packageName]
                Text(
                    if (signer != null) {
                        "Signed by the Keiyoushi key " +
                            signer.take(16) + "…"
                    } else {
                        "Signed by the Keiyoushi key"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    TextButton(
                        onClick = {
                            viewModel.cancelKeiyoushiInstall(row.packageName)
                        }
                    ) { Text("Cancel") }
                }
            } else {
                val showLabel =
                    row.action == KeiyoushiIndex.RowAction.NONE ||
                        row.state == KeiyoushiIndex.State.READY ||
                        row.state == KeiyoushiIndex.State.UPDATE_AVAILABLE
                if (showLabel) {
                    Text(
                        row.state.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (
                            row.state == KeiyoushiIndex.State.READY
                        ) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2
                    )
                }
                when (row.action) {
                    KeiyoushiIndex.RowAction.INSTALL ->
                        Button(
                            onClick = {
                                viewModel.installKeiyoushi(row.packageName)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Install") }
                    KeiyoushiIndex.RowAction.REVIEW ->
                        Button(
                            onClick = {
                                viewModel.reviewKeiyoushiConsent(
                                    row.packageName
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Review") }
                    KeiyoushiIndex.RowAction.UPDATE ->
                        Button(
                            onClick = {
                                viewModel.installKeiyoushi(row.packageName)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Update") }
                    KeiyoushiIndex.RowAction.REMOVE -> {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Installed",
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                        TextButton(
                            onClick = {
                                viewModel.uninstallKeiyoushi(
                                    row.packageName
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Remove") }
                    }
                    KeiyoushiIndex.RowAction.NONE -> Unit
                }
            }
        }
    }
}
