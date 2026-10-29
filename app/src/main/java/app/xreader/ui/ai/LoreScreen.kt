package app.xreader.ui.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.listPlacementSpec
import app.xreader.data.db.LoreArt
import app.xreader.data.db.LoreEntity
import app.xreader.data.db.LoreType
import app.xreader.data.db.TerminologyEntry
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.StatusPill
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

private fun LoreType.label(): String = when (this) {
    LoreType.CHARACTER -> "Character"
    LoreType.FACTION -> "Faction"
    LoreType.LOCATION -> "Place"
    LoreType.ITEM -> "Item"
    LoreType.TERM -> "Term"
}

/**
 * LoreKeeper screen (screen #20): the book's knowledge base with
 * tabs for the entity list, the relationship map, the translation
 * glossary, and generated art. Everything here is editable — the
 * reader's corrections lock and outrank the AI's (§7).
 */
@Composable
fun LoreScreen(
    viewModel: LoreViewModel,
    artFile: (String) -> File,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<LoreEntity?>(null) }
    var addingEntity by remember { mutableStateOf(false) }
    var editingTerm by remember { mutableStateOf<TerminologyEntry?>(null) }
    var addingTerm by remember { mutableStateOf(false) }

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
            Column(Modifier.padding(start = 4.dp)) {
                Text("Lore & glossary", style = MaterialTheme.typography.headlineSmall)
                Text(
                    state.book?.title ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            EngineChip(label = engineLabel)
        }
        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.messageIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
            listOf("Lore", "Map", "Glossary", "Art").forEachIndexed { index, label ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when (tab) {
            0 -> LoreTab(
                state = state,
                onBuild = { viewModel.buildLore() },
                onEdit = { editing = it },
                onAdd = { addingEntity = true },
                onArt = { viewModel.generateArt(it) },
                onDelete = { viewModel.deleteEntity(it) }
            )
            1 -> MapTab(state = state)
            2 -> GlossaryTab(
                state = state,
                onEdit = { editingTerm = it },
                onAdd = { addingTerm = true },
                onDelete = { viewModel.deleteTerm(it) }
            )
            else -> ArtTab(
                state = state,
                artFile = artFile,
                onDelete = { viewModel.deleteArt(it) }
            )
        }
    }

    editing?.let { entity ->
        EntityEditor(
            entity = entity,
            onDismiss = { editing = null },
            onSave = { name, description ->
                viewModel.saveEntity(entity, name, description)
                editing = null
            }
        )
    }
    if (addingEntity) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(LoreType.CHARACTER) }
        AlertDialog(
            onDismissRequest = { addingEntity = false },
            title = { Text("Add to the lore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LoreType.entries.forEach { candidate ->
                            TextButton(onClick = { type = candidate }) {
                                Text(
                                    candidate.label(),
                                    color = if (type == candidate) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("What the book says") },
                        minLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addEntity(name, type, description)
                        addingEntity = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { addingEntity = false }) { Text("Cancel") }
            }
        )
    }
    if (editingTerm != null || addingTerm) {
        TermEditor(
            existing = editingTerm,
            onDismiss = {
                editingTerm = null
                addingTerm = false
            },
            onSave = { source, translation, locked ->
                viewModel.saveTerm(editingTerm, source, translation, locked)
                editingTerm = null
                addingTerm = false
            }
        )
    }
}

@Composable
private fun LoreTab(
    state: LoreUiState,
    onBuild: () -> Unit,
    onEdit: (LoreEntity) -> Unit,
    onAdd: () -> Unit,
    onArt: (LoreEntity) -> Unit,
    onDelete: (LoreEntity) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.hasKey) {
                    Text(
                        "LoreKeeper reads chapters with your AI engine and remembers the " +
                            "people, places and terms. Add your free key in More → " +
                            "Providers & keys to switch it on — you can also add " +
                            "entries by hand below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    XreaderPrimaryButton(
                        text = when {
                            state.building -> state.buildProgress.ifBlank { "Reading…" }
                            else -> "Build lore from my chapters"
                        },
                        onClick = onBuild,
                        enabled = state.hasKey && !state.building,
                        modifier = Modifier.weight(1f)
                    )
                    XreaderPrimaryButton(
                        text = "Add by hand",
                        onClick = onAdd,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (state.building) {
                    CircularProgressIndicator()
                }
            }
        }
        if (state.entities.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "No lore yet",
                    message = "Once chapters are read or downloaded, LoreKeeper " +
                        "remembers who everyone is — names, factions, places, terms."
                )
            }
        }
        items(state.entities, key = { "lore-${it.id}" }) { entity ->
            var confirmDelete by remember { mutableStateOf(false) }
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(placementSpec = listPlacementSpec()),
                onClick = { onEdit(entity) }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(entity.name, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(
                            text = entity.type.label(),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        if (entity.userLocked) {
                            StatusPill(
                                text = "Your edit",
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
                if (entity.description.isNotBlank()) {
                    Text(
                        entity.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                val aliases = app.xreader.data.ai.LoreMerger.decodeAliases(entity.aliasesJson)
                if (aliases.isNotEmpty()) {
                    Text(
                        "Also: ${aliases.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    "First seen in chapter ${entity.firstChapterIndex + 1} — tap to edit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onArt(entity) },
                        enabled = state.artBusyFor == null
                    ) {
                        Text(
                            if (state.artBusyFor == entity.id) {
                                "Painting…"
                            } else {
                                "Generate art"
                            }
                        )
                    }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Remove")
                    }
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Remove “${entity.name}”?") },
                        text = {
                            Text("It will also be removed from your other device on the next sync.")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmDelete = false
                                    onDelete(entity)
                                }
                            ) { Text("Remove") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) {
                                Text("Keep")
                            }
                        }
                    )
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun MapTab(state: LoreUiState) {
    val entities = state.entities
    if (entities.size < 2 || state.relations.isEmpty()) {
        EmptyState(
            title = "No relationships yet",
            message = "When LoreKeeper finds how people and factions connect, " +
                "the map draws itself here."
        )
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val nodeColor = MaterialTheme.colorScheme.tertiary
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val textStyle = MaterialTheme.typography.labelSmall
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTransformGestures { _, gesturePan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 3f)
                        pan += gesturePan
                    }
                }
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                    }
            ) {
                val centre = Offset(size.width / 2, size.height / 2)
                val radius = minOf(size.width, size.height) / 2 - 70f
                val positions = entities.mapIndexed { index, entity ->
                    val angle = (2 * Math.PI * index / entities.size) - Math.PI / 2
                    entity.id to Offset(
                        centre.x + (radius * cos(angle)).toFloat(),
                        centre.y + (radius * sin(angle)).toFloat()
                    )
                }.toMap()
                state.relations.forEach { relation ->
                    val from = positions[relation.fromEntityId]
                    val to = positions[relation.toEntityId]
                    if (from != null && to != null) {
                        drawLine(lineColor, from, to, strokeWidth = 2f)
                    }
                }
                positions.forEach { (id, point) ->
                    val entity = entities.first { it.id == id }
                    drawCircle(nodeColor, radius = 10f, center = point)
                    val layout = measurer.measure(entity.name.take(18), textStyle)
                    drawText(
                        layout,
                        color = textColor,
                        topLeft = Offset(
                            point.x - layout.size.width / 2,
                            point.y + 14f
                        )
                    )
                }
            }
        }
        Text(
            "Pinch to zoom, drag to pan. Who connects to whom:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.relations, key = { "rel-${it.id}" }) { relation ->
                val from = entities.firstOrNull { it.id == relation.fromEntityId }?.name ?: "?"
                val to = entities.firstOrNull { it.id == relation.toEntityId }?.name ?: "?"
                Text(
                    "$from — ${relation.relationLabel} → $to",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun GlossaryTab(
    state: LoreUiState,
    onEdit: (TerminologyEntry) -> Unit,
    onAdd: () -> Unit,
    onDelete: (TerminologyEntry) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "intro") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The glossary steers RAW translation: these are the spellings " +
                        "the translator must use for this book. Locked entries are " +
                        "orders; unlocked ones are strong suggestions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                XreaderPrimaryButton(
                    text = "Add a term",
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (state.terms.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "No glossary terms yet",
                    message = "Add the names and terms that must always translate " +
                        "the same way — sect names, techniques, titles."
                )
            }
        }
        items(state.terms, key = { "term-${it.id}" }) { term ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(placementSpec = listPlacementSpec()),
                onClick = { onEdit(term) }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${term.sourceTerm} → ${term.chosenTranslation}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (term.locked) {
                        StatusPill(
                            text = "Locked",
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Row {
                    TextButton(onClick = { onDelete(term) }) { Text("Remove") }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ArtTab(
    state: LoreUiState,
    artFile: (String) -> File,
    onDelete: (LoreArt) -> Unit
) {
    if (state.art.isEmpty()) {
        EmptyState(
            title = "No art yet",
            message = "Generate art from any lore entry and it will live here — " +
                "painted from what the book itself says about them."
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(state.art, key = { "art-${it.id}" }) { art ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(placementSpec = listPlacementSpec())
            ) {
                AsyncImage(
                    model = artFile(art.imagePath),
                    contentDescription = art.passageQuote,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
                if (art.passageQuote.isNotBlank()) {
                    Text(
                        "“${art.passageQuote}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Row {
                    TextButton(onClick = { onDelete(art) }) { Text("Remove") }
                }
            }
        }
        item(key = "pad") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EntityEditor(
    entity: LoreEntity,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(entity.id) { mutableStateOf(entity.name) }
    var description by remember(entity.id) { mutableStateOf(entity.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit lore") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("What the book says") },
                    minLines = 3
                )
                Text(
                    "Your saved version is locked — automatic lore updates will " +
                        "never overwrite it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun TermEditor(
    existing: TerminologyEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit
) {
    var source by remember(existing?.id) { mutableStateOf(existing?.sourceTerm ?: "") }
    var translation by remember(existing?.id) {
        mutableStateOf(existing?.chosenTranslation ?: "")
    }
    var locked by remember(existing?.id) { mutableStateOf(existing?.locked ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a term" else "Edit term") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Original term") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text("Always translate as") },
                    singleLine = true
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Locked (an order, not a hint)")
                    Switch(checked = locked, onCheckedChange = { locked = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(source, translation, locked) },
                enabled = source.isNotBlank() && translation.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
