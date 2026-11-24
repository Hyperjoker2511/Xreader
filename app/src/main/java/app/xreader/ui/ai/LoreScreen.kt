package app.xreader.ui.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding
import app.xreader.core.motion.listPlacementSpec
import app.xreader.data.db.LoreArt
import app.xreader.data.db.LoreEntity
import app.xreader.data.db.LoreType
import app.xreader.data.db.TerminologyEntry
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.NocturneChip
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.common.QuickActionTile
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LoreScreen(
    viewModel: LoreViewModel,
    artFile: (String) -> File,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engineLabel by viewModel.engineLabel.collectAsStateWithLifecycle()
    // Code 121 W8 (R11, D-121-W7-03 retired): the Material 3
    // form of this page — a single column per the scratch
    // spec. Nocturne keeps the tabbed form below, untouched.
    if (app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        M3LoreContent(
            state = state,
            engineLabel = engineLabel,
            artFile = artFile,
            onBack = onBack,
            onBuild = { viewModel.buildLore() },
            onSaveEntity = { entity, name, description ->
                viewModel.saveEntity(entity, name, description)
            },
            onAddEntity = { name, type, description ->
                viewModel.addEntity(name, type, description)
            },
            onDeleteEntity = { viewModel.deleteEntity(it) },
            onArt = { viewModel.generateArt(it) },
            onDeleteArt = { viewModel.deleteArt(it) }
        )
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<LoreEntity?>(null) }
    var addingEntity by remember { mutableStateOf(false) }
    var editingTerm by remember { mutableStateOf<TerminologyEntry?>(null) }
    var addingTerm by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text("Lore & glossary", style = MaterialTheme.typography.headlineMedium)
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Lore", "Map", "Glossary", "Art").forEachIndexed { index, label ->
                NocturneChip(
                    label = label,
                    modifier = Modifier.weight(1f),
                    selected = tab == index,
                    dotColor = null,
                    onClick = { tab = index }
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
                    // v1.0.5 (B3): a FlowRow — all five lore types
                    // as text buttons in one fixed Row crushed
                    // inside this dialog on narrow screens.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LoreType.entries.forEach { candidate ->
                            NocturneChip(
                                label = candidate.label(),
                                selected = type == candidate,
                                dotColor = null,
                                onClick = { type = candidate }
                            )
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
    // Code 120 completion (R11): the M3 lore view knob — card
    // grid (the design default) or list, both below. Under
    // Nocturne the shipped card column after this branch
    // renders exactly as before and the knob is never read.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    if (designState.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        when (designState.loreView) {
            app.xreader.data.prefs.M3ListView.CARD_GRID ->
                LoreGridTab(state, onBuild, onEdit, onAdd, onArt, onDelete)
            app.xreader.data.prefs.M3ListView.LIST ->
                LoreListTab(state, onBuild, onEdit, onAdd, onArt, onDelete)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
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
                    PrimaryPill(
                        text = when {
                            state.building -> state.buildProgress.ifBlank { "Reading…" }
                            else -> "Build lore from my chapters"
                        },
                        onClick = onBuild,
                        enabled = state.hasKey && !state.building,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryPill(
                        text = "Add by hand",
                        enabled = true,
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
                KitEmptyState(
                    icon = Icons.Filled.AutoStories,
                    message = "No lore yet. Once chapters are read or downloaded, " +
                        "LoreKeeper remembers who everyone is — names, factions, " +
                        "places, terms."
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
                        NocturneChip(
                            label = entity.type.label(),
                            selected = false,
                            dotColor = MaterialTheme.colorScheme.tertiary,
                            onClick = null
                        )
                        if (entity.userLocked) {
                            NocturneChip(
                                label = "Your edit",
                                selected = false,
                                dotColor = MaterialTheme.colorScheme.secondary,
                                onClick = null
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QuickActionTile(
                        icon = Icons.Filled.Brush,
                        label = if (state.artBusyFor == entity.id) {
                            "Painting…"
                        } else {
                            "Generate art"
                        },
                        modifier = Modifier.weight(1f),
                        active = state.artBusyFor == entity.id,
                        onClick = {
                            if (state.artBusyFor == null) onArt(entity)
                        }
                    )
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
    }
}

@Composable
private fun MapTab(state: LoreUiState) {
    val entities = state.entities
    if (entities.size < 2 || state.relations.isEmpty()) {
        KitEmptyState(
            icon = Icons.Filled.Share,
            message = "No relationships yet. When LoreKeeper finds how people " +
                "and factions connect, the map draws itself here."
        )
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val nodeColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val textColor = MaterialTheme.colorScheme.onSurface
    val textStyle = MaterialTheme.typography.labelSmall
    Column(Modifier.fillMaxSize()) {
        GlassCard(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
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
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Pinch to zoom, drag to pan. Who connects to whom:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
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
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
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
                PrimaryPill(
                    text = "Add a term",
                    enabled = true,
                    onClick = onAdd,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (state.terms.isEmpty()) {
            item(key = "empty") {
                KitEmptyState(
                    icon = Icons.Filled.Translate,
                    message = "No glossary terms yet. Add the names and terms " +
                        "that must always translate the same way — sect names, " +
                        "techniques, titles."
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
                        NocturneChip(
                            label = "Locked",
                            selected = false,
                            dotColor = MaterialTheme.colorScheme.secondary,
                            onClick = null
                        )
                    }
                }
                Row {
                    TextButton(onClick = { onDelete(term) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun ArtTab(
    state: LoreUiState,
    artFile: (String) -> File,
    onDelete: (LoreArt) -> Unit
) {
    if (state.art.isEmpty()) {
        KitEmptyState(
            icon = Icons.Filled.Image,
            message = "No art yet. Generate art from any lore entry and it " +
                "will live here — painted from what the book itself says " +
                "about them."
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = bottomContentPadding(LocalUiMetrics.current, dockVisible = false))
    ) {
        items(state.art, key = { "art-${it.id}" }) { art ->
            Column {
                AsyncImage(
                    model = artFile(art.imagePath),
                    contentDescription = art.passageQuote,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(24.dp))
                )
                if (art.passageQuote.isNotBlank()) {
                    Text(
                        "“${art.passageQuote}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 3
                    )
                }
                TextButton(onClick = { onDelete(art) }) { Text("Remove") }
            }
        }
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
                    NocturneSwitch(
                        checked = locked,
                        onCheckedChange = { locked = it },
                        enabled = true
                    )
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

// ────────────────────────────────────────────────────────────────
// Code 120 completion (R11) — the lore tab's Material 3 forms:
// the card grid (the design default) and the list. Both carry
// the shipped actions and facts; Nocturne never reaches them.
// ────────────────────────────────────────────────────────────────

/** The lore tab's action block — the explainer, the Build /
 * Add pills and the build spinner — shared by the two M3
 * forms. (The Nocturne column keeps its own inline copy.) */
@Composable
private fun LoreActionsBlock(
    state: LoreUiState,
    onBuild: () -> Unit,
    onAdd: () -> Unit
) {
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
            PrimaryPill(
                text = when {
                    state.building -> state.buildProgress.ifBlank { "Reading…" }
                    else -> "Build lore from my chapters"
                },
                onClick = onBuild,
                enabled = state.hasKey && !state.building,
                modifier = Modifier.weight(1f)
            )
            PrimaryPill(
                text = "Add by hand",
                enabled = true,
                onClick = onAdd,
                modifier = Modifier.weight(1f)
            )
        }
        if (state.building) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun LoreEmptyState() {
    KitEmptyState(
        icon = Icons.Filled.AutoStories,
        message = "No lore yet. Once chapters are read or downloaded, " +
            "LoreKeeper remembers who everyone is — names, factions, " +
            "places, terms."
    )
}

/** The remove confirm, identical words in every form. */
@Composable
private fun LoreRemoveDialog(
    entity: LoreEntity,
    onDismiss: () -> Unit,
    onDelete: (LoreEntity) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove “${entity.name}”?") },
        text = {
            Text("It will also be removed from your other device on the next sync.")
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDelete(entity)
                }
            ) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep") }
        }
    )
}

/** R11 card grid: entities two to a row as compact cards —
 * name, type, description, first-seen line, and the Generate
 * art / Remove doors. Tap opens the editor, as shipped. */
@Composable
private fun LoreGridTab(
    state: LoreUiState,
    onBuild: () -> Unit,
    onEdit: (LoreEntity) -> Unit,
    onAdd: () -> Unit,
    onArt: (LoreEntity) -> Unit,
    onDelete: (LoreEntity) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            bottom = bottomContentPadding(
                LocalUiMetrics.current, dockVisible = false
            )
        )
    ) {
        item(
            key = "actions",
            span = {
                androidx.compose.foundation.lazy.grid
                    .GridItemSpan(maxLineSpan)
            }
        ) {
            LoreActionsBlock(state, onBuild, onAdd)
        }
        if (state.entities.isEmpty()) {
            item(
                key = "empty",
                span = {
                    androidx.compose.foundation.lazy.grid
                        .GridItemSpan(maxLineSpan)
                }
            ) {
                LoreEmptyState()
            }
        }
        items(state.entities, key = { "lore-${it.id}" }) { entity ->
            var confirmDelete by remember { mutableStateOf(false) }
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onEdit(entity) }
            ) {
                Text(
                    entity.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style
                        .TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    NocturneChip(
                        label = entity.type.label(),
                        selected = false,
                        dotColor = MaterialTheme.colorScheme.tertiary,
                        onClick = null
                    )
                    if (entity.userLocked) {
                        NocturneChip(
                            label = "Your edit",
                            selected = false,
                            dotColor = MaterialTheme.colorScheme.secondary,
                            onClick = null
                        )
                    }
                }
                if (entity.description.isNotBlank()) {
                    Text(
                        entity.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style
                            .TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Text(
                    "First seen in chapter ${entity.firstChapterIndex + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                QuickActionTile(
                    icon = Icons.Filled.Brush,
                    label = if (state.artBusyFor == entity.id) {
                        "Painting…"
                    } else {
                        "Generate art"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    active = state.artBusyFor == entity.id,
                    onClick = {
                        if (state.artBusyFor == null) onArt(entity)
                    }
                )
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Remove")
                }
            }
            if (confirmDelete) {
                LoreRemoveDialog(
                    entity = entity,
                    onDismiss = { confirmDelete = false },
                    onDelete = onDelete
                )
            }
        }
    }
}

/** R11 list: entities as compact rows — name + type +
 * one-line description, with the art and remove doors as
 * icon buttons on the row. Tap opens the editor. */
@Composable
private fun LoreListTab(
    state: LoreUiState,
    onBuild: () -> Unit,
    onEdit: (LoreEntity) -> Unit,
    onAdd: () -> Unit,
    onArt: (LoreEntity) -> Unit,
    onDelete: (LoreEntity) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            bottom = bottomContentPadding(
                LocalUiMetrics.current, dockVisible = false
            )
        )
    ) {
        item(key = "actions") {
            LoreActionsBlock(state, onBuild, onAdd)
        }
        if (state.entities.isEmpty()) {
            item(key = "empty") {
                LoreEmptyState()
            }
        }
        items(state.entities, key = { "lore-${it.id}" }) { entity ->
            var confirmDelete by remember { mutableStateOf(false) }
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(placementSpec = listPlacementSpec()),
                onClick = { onEdit(entity) },
                contentPadding = PaddingValues(
                    horizontal = 14.dp, vertical = 10.dp
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entity.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style
                                .TextOverflow.Ellipsis
                        )
                        Text(
                            listOfNotNull(
                                entity.type.label(),
                                entity.description
                                    .takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style
                                .TextOverflow.Ellipsis
                        )
                    }
                    if (state.artBusyFor == entity.id) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(
                            onClick = {
                                if (state.artBusyFor == null) {
                                    onArt(entity)
                                }
                            }
                        ) {
                            Icon(
                                Icons.Filled.Brush,
                                contentDescription = "Generate art"
                            )
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove"
                        )
                    }
                }
            }
            if (confirmDelete) {
                LoreRemoveDialog(
                    entity = entity,
                    onDismiss = { confirmDelete = false },
                    onDelete = onDelete
                )
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────
// Code 121 W8 (R11): the Material 3 Lore page — medium top app
// bar (book title) over ONE column: the generated-art strip
// (poster cards), characters as list items with shape-cropped
// avatar markers, lore entries as filled cards carrying their
// provenance assist chip (AI · provider · AUTO/manual — the
// engine label is the live hub label; AUTO/manual is the
// entry's own userLocked flag). All actions are the shipped
// LoreViewModel's. The "Ask about this book" extended FAB is
// NOT drawn: R12's page needs a live reader back-stack entry
// and no route from Lore exists — see D-121-W8-01 in the
// build log. Nocturne never reaches this composable.
// ────────────────────────────────────────────────────────────────

@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun M3LoreContent(
    state: LoreUiState,
    engineLabel: String,
    artFile: (String) -> File,
    onBack: () -> Unit,
    onBuild: () -> Unit,
    onSaveEntity: (LoreEntity, String, String) -> Unit,
    onAddEntity: (String, LoreType, String) -> Unit,
    onDeleteEntity: (LoreEntity) -> Unit,
    onArt: (LoreEntity) -> Unit,
    onDeleteArt: (LoreArt) -> Unit
) {
    var editing by remember { mutableStateOf<LoreEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var selectedArt by remember { mutableStateOf<LoreArt?>(null) }
    val characters = state.entities.filter { it.type == LoreType.CHARACTER }
    val entries = state.entities.filter { it.type != LoreType.CHARACTER }
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.MediumTopAppBar(
                title = {
                    Text(
                        state.book?.title ?: "Lore",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style
                            .TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "m3-lore-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.AssistChip(
                            onClick = {},
                            label = { Text(engineLabel) }
                        )
                        if (state.building) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                state.buildProgress.ifBlank {
                                    "Building lore…"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            androidx.compose.material3.Button(
                                onClick = onBuild
                            ) { Text("Build lore") }
                            androidx.compose.material3.OutlinedButton(
                                onClick = { adding = true }
                            ) { Text("Add to lore") }
                        }
                    }
                    state.message?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.messageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (!state.hasKey) {
                        Text(
                            "Set up an AI provider in Providers & keys " +
                                "to build this book's lore.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                }
            }
            if (state.art.isNotEmpty()) {
                item(key = "m3-lore-art-head") {
                    Text(
                        "Art",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                item(key = "m3-lore-art-strip") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.art, key = { "m3-art-${it.id}" }) { art ->
                            val entityName = art.entityId?.let { id ->
                                state.entities.firstOrNull { it.id == id }
                                    ?.name
                            }
                            // The strip's poster card (R11): a
                            // filled card, 2:3 art over its title —
                            // M3PosterCard's grid form fills its
                            // cell, so the strip builds the same
                            // shape at a fixed width.
                            androidx.compose.material3.Card(
                                onClick = { selectedArt = art },
                                colors = androidx.compose.material3
                                    .CardDefaults.cardColors(
                                        containerColor =
                                            MaterialTheme.colorScheme
                                                .surfaceContainerLow
                                    ),
                                modifier = Modifier.width(140.dp)
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    AsyncImage(
                                        model = artFile(art.imagePath),
                                        contentDescription =
                                            art.passageQuote,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(2f / 3f)
                                    )
                                    Text(
                                        entityName ?: "Passage art",
                                        style = MaterialTheme.typography
                                            .titleSmall,
                                        maxLines = 2,
                                        modifier = Modifier.padding(
                                            horizontal = 10.dp,
                                            vertical = 8.dp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (characters.isNotEmpty()) {
                item(key = "m3-lore-characters-head") {
                    Text(
                        "Characters",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(characters, key = { "m3-char-${it.id}" }) { entity ->
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(entity.name) },
                        supportingContent = {
                            Text(
                                entity.description
                                    .lineSequence().firstOrNull()
                                    ?.takeIf { it.isNotBlank() }
                                    ?: entity.type.label(),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style
                                    .TextOverflow.Ellipsis
                            )
                        },
                        leadingContent = {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(
                                        androidx.compose.material3
                                            .MaterialShapes.Cookie9Sided
                                            .toShape()
                                    )
                                    .background(
                                        MaterialTheme.colorScheme
                                            .secondaryContainer
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    entity.name.trim()
                                        .firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography
                                        .titleMedium,
                                    color = MaterialTheme.colorScheme
                                        .onSecondaryContainer
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            editing = entity
                        }
                    )
                }
            }
            if (entries.isNotEmpty()) {
                item(key = "m3-lore-entries-head") {
                    Text(
                        "Lore",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(entries, key = { "m3-entry-${it.id}" }) { entity ->
                    androidx.compose.material3.Card(
                        colors = androidx.compose.material3.CardDefaults
                            .cardColors(
                                containerColor = MaterialTheme.colorScheme
                                    .surfaceContainer
                            ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                entity.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                entity.type.label(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme
                                    .onSurfaceVariant
                            )
                            if (entity.description.isNotBlank()) {
                                Text(
                                    entity.description,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            androidx.compose.material3.AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "$engineLabel · " +
                                            if (entity.userLocked) {
                                                "Manual"
                                            } else {
                                                "Auto"
                                            }
                                    )
                                }
                            )
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(4.dp)
                            ) {
                                TextButton(onClick = { editing = entity }) {
                                    Text("Edit")
                                }
                                TextButton(
                                    onClick = { onArt(entity) },
                                    enabled = state.artBusyFor == null
                                ) {
                                    Text(
                                        if (state.artBusyFor == entity.id) {
                                            "Painting…"
                                        } else {
                                            "Illustrate"
                                        }
                                    )
                                }
                                TextButton(
                                    onClick = { onDeleteEntity(entity) }
                                ) {
                                    Text(
                                        "Delete",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (state.entities.isEmpty() && state.art.isEmpty()) {
                item(key = "m3-lore-empty") {
                    app.xreader.ui.common.M3EmptyState(
                        headline = "No lore yet",
                        body = "Build the lore and this page fills with " +
                            "the book's characters, places and art — " +
                            "painted from what the book itself says.",
                        actionLabel = "Build lore",
                        onAction = onBuild
                    )
                }
            }
        }
    }
    editing?.let { entity ->
        EntityEditor(
            entity = entity,
            onDismiss = { editing = null },
            onSave = { name, description ->
                onSaveEntity(entity, name, description)
                editing = null
            }
        )
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(LoreType.CHARACTER) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add to the lore") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LoreType.entries.forEach { candidate ->
                            androidx.compose.material3.FilterChip(
                                selected = type == candidate,
                                onClick = { type = candidate },
                                label = { Text(candidate.label()) }
                            )
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
                        onAddEntity(name, type, description)
                        adding = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text("Cancel") }
            }
        )
    }
    selectedArt?.let { art ->
        AlertDialog(
            onDismissRequest = { selectedArt = null },
            title = { Text("Art") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model = artFile(art.imagePath),
                        contentDescription = art.passageQuote,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                    )
                    if (art.passageQuote.isNotBlank()) {
                        Text(
                            "“${art.passageQuote}”",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme
                                .onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteArt(art)
                        selectedArt = null
                    }
                ) {
                    Text(
                        "Delete",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedArt = null }) {
                    Text("Close")
                }
            }
        )
    }
}
