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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.QuickActionTile
// Code 121 (W4): the scratch-M3 (R16) branch's components.
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import app.xreader.ui.theme.NocturneOverline
import app.xreader.ui.theme.primaryTextColor
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding

/**
 * v1.0.5 (C3/D23): "Add a source" is its OWN screen now — the
 * three doors (a repository, a site link, a Legado file) with
 * their forms HERE, instead of a form expanding inside the
 * Browse scroll. The foreign-repository explanation and the
 * Legado import report live here too: they are both answers to
 * something the reader just tried to add.
 */
@Composable
fun AddSourceScreen(
    viewModel: SourcesViewModel,
    onBack: () -> Unit,
    onAddFromLink: () -> Unit,
    onOpenStore: () -> Unit,
    /** v1.3.2 (scope E): the persisted foreign repo's own
     * screen — the full site list lives there now. */
    onOpenRepo: (Long) -> Unit = {},
    /** Code 122 (R16, audit item #8): the Built-in sources
     * door (→R19) — the shell wires this to the repoBuiltIn
     * route, the same callback the Source store receives. */
    onOpenBuiltIn: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var repoName by remember { mutableStateOf("") }
    var repoUrl by remember { mutableStateOf("") }
    // v1.3.1 (item 59): the form clears ONLY once the add has
    // actually succeeded — a failure leaves the address in
    // place for correction.
    androidx.compose.runtime.LaunchedEffect(
        state.repoOutcome, state.repoOutcomeIsError
    ) {
        if (state.repoOutcome != null && !state.repoOutcomeIsError) {
            repoName = ""
            repoUrl = ""
        }
    }
    // v1.3.3 (scope S7c): a successful add OPENS the new
    // repo's own page — the user's rule, for native and
    // foreign adds alike. Each id navigates once.
    val navigatedRepoIds = remember { mutableSetOf<Long>() }
    androidx.compose.runtime.LaunchedEffect(
        state.lastAddedRepoId, state.lastForeignRepoId
    ) {
        val target = state.lastAddedRepoId ?: state.lastForeignRepoId
        if (target != null && navigatedRepoIds.add(target)) {
            onOpenRepo(target)
        }
    }
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

    // ── Code 121 (W4, scratch R16): the Material 3 Add source ──
    // A doors page: door rows for each way in, with the
    // repository form under its door. Code 122 (audit item
    // #8): the fourth door, Built-in sources (→R19), is drawn
    // too, on the new onOpenBuiltIn callback (D-121-W4-5).
    // Under Nocturne the shipped screen renders byte-identically.
    if (isM3Design()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Add a source") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout
                    .PaddingValues(bottom = 24.dp)
            ) {
                item(key = "m3-door-repo-head") {
                    ListItem(
                        headlineContent = { Text("Add a repository") },
                        supportingContent = {
                            Text(
                                "One address that lists sources other " +
                                    "people publish. Add it, then " +
                                    "install its sources from the " +
                                    "Source store."
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = null
                            )
                        }
                    )
                }
                item(key = "m3-repo-form") {
                    Column(
                        Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = repoName,
                            onValueChange = { repoName = it },
                            label = { Text("Name (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = repoUrl,
                            onValueChange = { repoUrl = it },
                            label = { Text("Repository list address") },
                            placeholder = { Text("https://…/index.json") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                viewModel.addRepo(repoName, repoUrl)
                            },
                            enabled = repoUrl.isNotBlank() && !state.busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (state.busy) {
                                    state.repoStage ?: "Working…"
                                } else {
                                    "Add repository"
                                }
                            )
                        }
                        if (state.busy && state.repoStage != null) {
                            LinearWavyProgressIndicator(
                                Modifier.fillMaxWidth()
                            )
                            Text(
                                state.repoStage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                            Text(
                                "A big list on a slow connection can " +
                                    "take a few minutes — this line " +
                                    "keeps you posted while it works.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                        state.repoOutcome?.let { outcome ->
                            Text(
                                outcome,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.repoOutcomeIsError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            TextButton(
                                onClick = { viewModel.dismissRepoOutcome() }
                            ) { Text("Dismiss") }
                        }
                    }
                }
                item(key = "m3-door-link") {
                    ListItem(
                        headlineContent = { Text("Add from link") },
                        supportingContent = {
                            Text(
                                "Paste a novel site's book link and " +
                                    "Xreader works out how to read " +
                                    "it — proven with a test read " +
                                    "before anything is saved."
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.AddLink,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable {
                            onAddFromLink()
                        }
                    )
                }
                item(key = "m3-door-live") {
                    ListItem(
                        headlineContent = {
                            Text("Install a site as an app")
                        },
                        supportingContent = {
                            Text(
                                "Any novel site can live on your " +
                                    "home screen like an app, while " +
                                    "everything inside opens in " +
                                    "Xreader's own screens. No test " +
                                    "read — it's read fresh each time."
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.AddLink,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable {
                            onAddFromLink()
                        }
                    )
                }
                item(key = "m3-door-legado") {
                    ListItem(
                        headlineContent = {
                            Text("Import a Legado file")
                        },
                        supportingContent = {
                            Text(
                                "Legado (阅读) book sources (v2 / v3) " +
                                    "convert into Xreader sources " +
                                    "after a test read."
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable {
                            legadoFileLauncher.launch(
                                arrayOf(
                                    "application/json", "text/plain", "*/*"
                                )
                            )
                        }
                    )
                }
                item(key = "m3-door-store") {
                    ListItem(
                        headlineContent = { Text("Browse the store") },
                        supportingContent = {
                            Text("Repositories and ready-made sources")
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Storefront,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable { onOpenStore() }
                    )
                }
                item(key = "m3-door-builtin") {
                    ListItem(
                        headlineContent = { Text("Built-in sources") },
                        supportingContent = {
                            Text(
                                "The sources that ship with Xreader — " +
                                    "turn any of them back on, or open one."
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.Storage,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.clickable { onOpenBuiltIn() }
                    )
                }
                state.foreignRepo?.let { foreign ->
                    item(key = "m3-foreign") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme
                                    .colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    when (foreign.ecosystemName) {
                                        "Legado" ->
                                            "This is a Legado source file"
                                        "another reader app" ->
                                            "This looks like another " +
                                                "reader app's repository"
                                        else ->
                                            "This is a " +
                                                "${foreign.ecosystemName} " +
                                                "repository"
                                    },
                                    style = MaterialTheme
                                        .typography.titleMedium
                                )
                                Text(
                                    when {
                                        foreign.stubIndex ->
                                            "This repository's list has " +
                                                "moved to a newer format — " +
                                                "what's published at this " +
                                                "link now is just a note " +
                                                "telling older apps to " +
                                                "update, and Xreader " +
                                                "already looked for the " +
                                                "real list next to it. " +
                                                "The sites in that note " +
                                                "aren't real sources."
                                        foreign.pluginIndex ->
                                            "Its sources live inside a " +
                                                "single plugin file made " +
                                                "for " +
                                                (if (foreign.ecosystemName ==
                                                    "another reader app"
                                                ) {
                                                    "another reader app"
                                                } else {
                                                    foreign.ecosystemName
                                                }) +
                                                ". Xreader can't run " +
                                                "another app's plugin " +
                                                "file, and the index " +
                                                "doesn't list its sites " +
                                                "separately — so there's " +
                                                "nothing here it can " +
                                                "install. If there's a " +
                                                "site you read there, add " +
                                                "it with Add from link " +
                                                "instead: Xreader will " +
                                                "test-read it and build " +
                                                "its own source."
                                        else ->
                                            "Xreader can't install " +
                                                "extensions made for " +
                                                "other apps, but it can " +
                                                "build its own source " +
                                                "for the same sites. " +
                                                "Pick a site below and " +
                                                "Xreader will test-read " +
                                                "it first."
                                    },
                                    style = MaterialTheme
                                        .typography.bodyMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant
                                )
                                foreign.sites.take(40).forEach { site ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                site.name,
                                                style = MaterialTheme
                                                    .typography.bodyLarge
                                            )
                                            Text(
                                                listOfNotNull(
                                                    site.lang.takeIf {
                                                        it.isNotBlank()
                                                    },
                                                    site.family?.let {
                                                        "LNReader family · $it"
                                                    },
                                                    site.baseUrl
                                                ).joinToString(" · "),
                                                style = MaterialTheme
                                                    .typography.bodySmall,
                                                color = MaterialTheme
                                                    .colorScheme
                                                    .onSurfaceVariant
                                            )
                                        }
                                        if (app.xreader.data.extension
                                                .FamilyTemplates
                                                .hasTemplate(site.family)
                                        ) {
                                            Button(
                                                onClick = {
                                                    viewModel
                                                        .installFamilySource(
                                                            site
                                                        )
                                                },
                                                enabled = !state.busy
                                            ) { Text("Install ready source") }
                                        } else {
                                            TextButton(
                                                onClick = {
                                                    viewModel
                                                        .createSourceFromSite(
                                                            site
                                                        )
                                                },
                                                enabled = !state.busy
                                            ) { Text("Create Xreader source") }
                                        }
                                    }
                                }
                                if (foreign.sites.size > 40) {
                                    Text(
                                        "Showing the first 40 of " +
                                            "${foreign.sites.size} sites.",
                                        style = MaterialTheme
                                            .typography.bodySmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                }
                                if (state.lastForeignRepoId != null) {
                                    Button(
                                        onClick = {
                                            onOpenRepo(
                                                state.lastForeignRepoId
                                                    ?: 0L
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            "See all " +
                                                "${foreign.sites.size} sites"
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.dismissForeignRepo()
                                    }
                                ) { Text("Dismiss") }
                            }
                        }
                    }
                }
                if (state.legadoRows.isNotEmpty()) {
                    item(key = "m3-legado-report") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme
                                    .colorScheme.surfaceContainerLow
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "Legado sources",
                                    style = MaterialTheme
                                        .typography.titleMedium
                                )
                                state.legadoRows.forEach { row ->
                                    Text(
                                        (if (row.converted) "✓ " else "• ") +
                                            row.name,
                                        style = MaterialTheme
                                            .typography.bodyLarge
                                    )
                                    Text(
                                        row.outcome,
                                        style = MaterialTheme
                                            .typography.bodySmall,
                                        color = MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                    )
                                }
                            }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Add a source", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Three ways in: a repository of ready-made sources, a " +
                "novel site's link that Xreader sets up for you, or a " +
                "Legado source file.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
        ) {
            // ── Door 1: a repository, form and all ──────────────
            item {
                GlassCard {
                    DoorOverline("Door 1")
                    Text(
                        "Add a repository",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A repository is one address that lists sources " +
                            "other people publish. Add it, then install " +
                            "its sources from the Source store.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                    PrimaryPill(
                        // v1.3.1 (item 59): while the add runs,
                        // the button carries the stage and stays
                        // put — never a silent, cleared form.
                        text = if (state.busy) {
                            state.repoStage ?: "Working…"
                        } else {
                            "Add repository"
                        },
                        onClick = { viewModel.addRepo(repoName, repoUrl) },
                        enabled = repoUrl.isNotBlank() && !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (state.busy && state.repoStage != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {
                            androidx.compose.material3
                                .CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp)
                                )
                            Text(
                                state.repoStage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                        }
                        Text(
                            "A big list on a slow connection can take " +
                                "a few minutes — this line keeps you " +
                                "posted while it works.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                    state.repoOutcome?.let { outcome ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            outcome,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.repoOutcomeIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        GhostPill(
                            text = "Dismiss",
                            onClick = { viewModel.dismissRepoOutcome() }
                        )
                    }
                }
            }

            // ── Door 2: a site link ─────────────────────────────
            item {
                GlassCard {
                    DoorOverline("Door 2")
                    Text(
                        "Add from link",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Paste a novel site's book link and Xreader works " +
                            "out how to read it — proven with a test read " +
                            "before anything is saved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryPill(
                        text = "Add from link",
                        onClick = onAddFromLink,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Door 2.5: install a site as an app (v1.0.9.1 —
            // the Live Source door, in plain words, at the top
            // level: in v1.0.9 it hid behind a text button
            // inside Add from link and the user couldn't find
            // the feature at all) ────────────────────────────
            item {
                GlassCard {
                    DoorOverline("Door 2.5")
                    Text(
                        "Install a site as an app",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Any novel site can live on your home screen " +
                            "like an app — its own icon, its own window — " +
                            "while everything inside opens in Xreader's " +
                            "own screens, never as a web page. No test " +
                            "read, nothing to go stale: it's read fresh " +
                            "from the site each time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryPill(
                        text = "Install a site as an app",
                        onClick = onAddFromLink,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Door 3: a Legado file ───────────────────────────
            item {
                GlassCard {
                    DoorOverline("Door 3")
                    Text(
                        "Import a Legado file",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    NocturneChip(
                        label = "Supports v2 / v3",
                        selected = false
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Legado (阅读) book sources convert into Xreader " +
                            "sources after a test read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryPill(
                        text = "Choose a Legado file (.json)",
                        onClick = {
                            legadoFileLauncher.launch(
                                arrayOf("application/json", "text/plain", "*/*")
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── The store door (§5.7: a QuickActionTile door) ───
            item {
                QuickActionTile(
                    icon = Icons.Filled.Storefront,
                    label = "Source store",
                    modifier = Modifier.fillMaxWidth(),
                    active = false,
                    onClick = onOpenStore
                )
            }

            // ── Foreign repo explanation (v1.0.1, re-homed) ─────
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
                            // v1.0.9: a stub index is only an
                            // upgrade note — the walk already
                            // looked for the real list beside it
                            // and found nothing better.
                            when {
                                foreign.stubIndex ->
                                    "This repository's list has moved to a newer format — " +
                                        "what's published at this link now is just a note " +
                                        "telling older apps to update, and Xreader already " +
                                        "looked for the real list next to it. The sites in " +
                                        "that note aren't real sources."
                                // v1.0.8.3: a plugin index lists no
                                // sites — its sources are code inside
                                // one plugin file. Say that plainly
                                // instead of inviting a pick from a
                                // list that isn't there.
                                foreign.pluginIndex ->
                                    "Its sources live inside a single plugin file made for " +
                                        (if (foreign.ecosystemName == "another reader app") {
                                            "another reader app"
                                        } else {
                                            foreign.ecosystemName
                                        }) +
                                        ". Xreader can't run another app's plugin file, and " +
                                        "the index doesn't list its sites separately — so " +
                                        "there's nothing here it can install. If there's a " +
                                        "site you read there, add it with Add from link " +
                                        "instead: Xreader will test-read it and build its " +
                                        "own source."
                                else ->
                                    "Xreader can't install extensions made for other apps, " +
                                        "but it can build its own source for the same sites. " +
                                        "Pick a site below and Xreader will test-read it first."
                            },
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
                                            site.family?.let { "LNReader family · $it" },
                                            site.baseUrl
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (app.xreader.data.extension.FamilyTemplates
                                        .hasTemplate(site.family)
                                ) {
                                    // v1.0.9: the family's reading
                                    // logic is already known — the
                                    // source installs ready-made.
                                    PrimaryPill(
                                        text = "Install ready source",
                                        onClick = {
                                            viewModel.installFamilySource(site)
                                        },
                                        enabled = !state.busy
                                    )
                                } else {
                                    GhostPill(
                                        text = "Create Xreader source",
                                        onClick = { viewModel.createSourceFromSite(site) },
                                        enabled = !state.busy
                                    )
                                }
                            }
                        }
                        if (foreign.sites.size > 40) {
                            Text(
                                "Showing the first 40 of ${foreign.sites.size} sites.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // v1.3.2 (scope E): the dead-end count
                        // line is a DOOR now — the repo was saved
                        // and its own screen carries the FULL
                        // list, searchable.
                        if (state.lastForeignRepoId != null) {
                            Spacer(Modifier.height(8.dp))
                            PrimaryPill(
                                text = "See all ${foreign.sites.size} sites",
                                onClick = {
                                    onOpenRepo(
                                        state.lastForeignRepoId ?: 0L
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        GhostPill(
                            text = "Dismiss",
                            onClick = { viewModel.dismissForeignRepo() }
                        )
                    }
                }
            }

            // ── Legado import report (v1.0.1, re-homed) ─────────
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
        }
    }
}

/** §5.7: the numbered overline above each door card's title. */
@Composable
private fun DoorOverline(text: String) {
    Text(
        text.uppercase(),
        style = NocturneOverline,
        color = primaryTextColor()
    )
    Spacer(Modifier.height(4.dp))
}
