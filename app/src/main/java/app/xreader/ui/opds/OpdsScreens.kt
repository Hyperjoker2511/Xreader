package app.xreader.ui.opds

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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.listPlacementSpec
import app.xreader.data.db.OpdsServer
import app.xreader.data.opds.OpdsEntry
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage

/**
 * Browse (PLAN §4): your OPDS library servers (Komga / Kavita /
 * Calibre-Web) live here; extension sources join this tab in Phase 3.
 * The screen has two modes: the server list (with an add form) and a
 * server's catalog feed.
 */
@Composable
fun BrowseScreen(
    viewModel: OpdsViewModel,
    onOpenServer: (OpdsServer) -> Unit
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    var showAddForm by remember { mutableStateOf(false) }

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
                if (!showAddForm) {
                    XreaderPrimaryButton(
                        text = "+ Add a server",
                        onClick = { showAddForm = true }
                    )
                }
            }
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showAddForm) {
                item(key = "add-form") {
                    AddServerForm(
                        onAdd = { name, url, user, pass ->
                            viewModel.addServer(name, url, user, pass)
                            showAddForm = false
                        },
                        onCancel = { showAddForm = false }
                    )
                }
            }
            if (servers.isEmpty() && !showAddForm) {
                item(key = "empty") {
                    EmptyState(
                        title = "No sources yet",
                        message = "Add a library server (Komga, Kavita or Calibre-Web) " +
                            "to browse and download your books. Extension sources arrive " +
                            "in the next phase.",
                        actionLabel = "Add a server",
                        onAction = { showAddForm = true }
                    )
                }
            }
            items(servers, key = { "server-${it.id}" }) { server ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(placementSpec = listPlacementSpec()),
                    onClick = { onOpenServer(server) }
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                server.baseUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { viewModel.removeServer(server) }) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove server")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddServerForm(
    onAdd: (name: String, url: String, user: String, pass: String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add a library server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (e.g. Home Komga)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Catalog address (…/opds)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Username (if it asks)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "The password is stored encrypted on this device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XreaderPrimaryButton(
                    text = "Add server",
                    onClick = { onAdd(name, url, user, pass) },
                    enabled = url.isNotBlank()
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** One server's catalog: categories drill in, books download. */
@Composable
fun OpdsCatalogScreen(
    viewModel: OpdsViewModel,
    serverName: String,
    onBack: () -> Unit
) {
    val state by viewModel.browse.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (!viewModel.goBack()) onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text(
                    state.feed?.title?.ifBlank { serverName } ?: serverName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1
                )
                Text(
                    serverName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.loading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }

            state.errorMessage != null -> EmptyState(
                title = "The catalog didn't load",
                message = state.errorMessage ?: "",
                modifier = Modifier.weight(1f)
            )

            state.feed != null -> LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.feed!!.entries, key = { "entry-${it.id}" }) { entry ->
                    OpdsEntryRow(
                        entry = entry,
                        onOpen = { viewModel.openEntry(entry) },
                        onDownload = { viewModel.downloadBook(entry) },
                        modifier = Modifier.animateItem(placementSpec = listPlacementSpec())
                    )
                }
                if (state.feed!!.nextPageUrl != null) {
                    item(key = "next-page") {
                        XreaderPrimaryButton(
                            text = "More…",
                            onClick = { viewModel.openNextPage() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpdsEntryRow(
    entry: OpdsEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = if (entry.isBook) null else onOpen
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (entry.thumbnailUrl != null) {
                AsyncImage(
                    model = entry.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 40.dp, height = 56.dp)
                )
            } else if (!entry.isBook) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                entry.author?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entry.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            if (entry.isBook) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download ${entry.title}")
                }
            }
        }
    }
}
