package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.XreaderPrimaryButton

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
    onOpenStore: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var repoName by remember { mutableStateOf("") }
    var repoUrl by remember { mutableStateOf("") }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Door 1: a repository, form and all ──────────────
            item {
                GlassCard {
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
                    XreaderPrimaryButton(
                        text = "Add repository",
                        onClick = {
                            viewModel.addRepo(repoName, repoUrl)
                            repoName = ""
                            repoUrl = ""
                        },
                        enabled = repoUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Door 2: a site link ─────────────────────────────
            item {
                GlassCard {
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
                    XreaderPrimaryButton(
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
                    XreaderPrimaryButton(
                        text = "Install a site as an app",
                        onClick = onAddFromLink,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Door 3: a Legado file ───────────────────────────
            item {
                GlassCard {
                    Text(
                        "Import a Legado file",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Legado (阅读) book sources convert into Xreader " +
                            "sources after a test read.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    XreaderPrimaryButton(
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

            // ── The store door ──────────────────────────────────
            item {
                GlassCard(onClick = onOpenStore, bouncePress = true) {
                    Text("Source store", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Browse the repositories you've added and install " +
                            "their sources.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                                    TextButton(
                                        onClick = {
                                            viewModel.installFamilySource(site)
                                        },
                                        enabled = !state.busy
                                    ) { Text("Install ready source") }
                                } else {
                                    TextButton(
                                        onClick = { viewModel.createSourceFromSite(site) },
                                        enabled = !state.busy
                                    ) { Text("Create Xreader source") }
                                }
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
