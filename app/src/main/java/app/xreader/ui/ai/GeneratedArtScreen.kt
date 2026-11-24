package app.xreader.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.db.LoreArt
import app.xreader.komikku.presentation.savePageToDevice
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.bottomContentPadding
import app.xreader.ui.theme.effectiveBottomPadding
import coil.compose.AsyncImage
import java.io.File

/**
 * The Generated art gallery (Patch 10, Addendum 2): every picture
 * Illustrate has painted, across every book, newest first. A tap
 * opens the full view — the book it belongs to, what it was made
 * from, Save to phone, and Delete through the one shared delete
 * path.
 */
@Composable
fun GeneratedArtScreen(
    viewModel: GeneratedArtViewModel,
    artFile: (String) -> File,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = state.selected
    BackHandler(enabled = selected != null) { viewModel.clearSelection() }
    // Code 120 completion (R33): the M3 gallery-columns knob —
    // Auto is breakpoint-driven (an adaptive min cell size),
    // 2 / 3 pin the count. Under Nocturne the shipped fixed
    // two columns stand and the knob is never read.
    val designState = app.xreader.ui.theme.LocalDesignState.current
    val artColumns = if (
        designState.design !=
            app.xreader.data.prefs.AppDesign.NOCTURNE
    ) {
        when (designState.galleryColumns) {
            app.xreader.data.prefs.M3GalleryColumns.AUTO ->
                GridCells.Adaptive(minSize = 150.dp)
            app.xreader.data.prefs.M3GalleryColumns.TWO ->
                GridCells.Fixed(2)
            app.xreader.data.prefs.M3GalleryColumns.THREE ->
                GridCells.Fixed(3)
        }
    } else {
        GridCells.Fixed(2)
    }

    // Code 121 W8 (R33, D-121-W7-03 retired): the Material 3
    // gallery — poster-card grid, per-card menu, provenance
    // chip. Nocturne keeps the form below, untouched.
    if (designState.design !=
        app.xreader.data.prefs.AppDesign.NOCTURNE
    ) {
        M3GeneratedArtContent(
            state = state,
            viewModel = viewModel,
            artFile = artFile,
            artColumns = artColumns,
            onBack = onBack
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(
                start = 20.dp, end = 20.dp,
                top = LocalUiMetrics.current.topGap, bottom = 16.dp
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { if (selected != null) viewModel.clearSelection() else onBack() }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                if (selected != null) "Picture" else "Generated art",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.height(8.dp))
        }
        when {
            selected != null -> ArtFullView(
                art = selected,
                bookTitle = state.bookTitles[selected.bookId] ?: "",
                madeFrom = state.selectedMadeFrom,
                artFile = artFile,
                viewModel = viewModel
            )
            state.art.isEmpty() -> KitEmptyState(
                icon = Icons.Filled.Image,
                message = "No generated art yet. Open a book's Lore page " +
                    "and tap Illustrate."
            )
            else -> LazyVerticalGrid(
                columns = artColumns,
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(
                    bottom = effectiveBottomPadding(
                        bottomContentPadding(
                            LocalUiMetrics.current, dockVisible = false
                        ),
                        LocalDockClearance.current
                    )
                )
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
                                .clickable { viewModel.select(art) }
                        )
                        val title = state.bookTitles[art.bookId]
                        if (!title.isNullOrBlank()) {
                            Text(
                                title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtFullView(
    art: LoreArt,
    bookTitle: String,
    madeFrom: String,
    artFile: (String) -> File,
    viewModel: GeneratedArtViewModel
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        AsyncImage(
            model = artFile(art.imagePath),
            contentDescription = art.passageQuote,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
        )
        Spacer(Modifier.height(16.dp))
        if (bookTitle.isNotBlank()) {
            Text(
                bookTitle,
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(4.dp))
        }
        if (madeFrom.isNotBlank()) {
            Text(
                if (art.entityId != null) {
                    "Painted from the lore entry: $madeFrom"
                } else {
                    "Painted from this passage: “$madeFrom”"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
        PrimaryPill(
            text = "Save to phone",
            onClick = {
                val file = artFile(art.imagePath)
                viewModel.onSaveResult(file.exists() && savePageToDevice(context, file))
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = { viewModel.deleteSelected() }) {
                Text("Delete picture")
            }
        }
        Spacer(
            Modifier.height(
                effectiveBottomPadding(88.dp, LocalDockClearance.current)
            )
        )
    }
}

// ────────────────────────────────────────────────────────────────
// Code 121 W8 (R33): the Material 3 Generated art gallery —
// the poster-card grid exception (P3): filled poster cards,
// newest first (the VM's order), each with a provenance chip
// and a menu (Set as cover · Share · Delete). The provenance
// chip carries the book — LoreArt records no provider, so no
// provider is claimed (D-121-W8-03). Tapping a card opens the
// M3 full view with the same doors plus Save to phone.
// ────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3GeneratedArtContent(
    state: GeneratedArtUiState,
    viewModel: GeneratedArtViewModel,
    artFile: (String) -> File,
    artColumns: GridCells,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    fun shareArt(art: LoreArt) {
        val file = artFile(art.imagePath)
        if (!file.exists()) {
            viewModel.onSaveResult(false)
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "app.xreader.fileprovider", file
        )
        val intent = android.content.Intent(
            android.content.Intent.ACTION_SEND
        ).apply {
            type = "image/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "Share picture")
        )
    }
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Text(
                        if (state.selected != null) {
                            "Picture"
                        } else {
                            "Generated art"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.selected != null) {
                                viewModel.clearSelection()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            val selected = state.selected
            when {
                selected != null -> M3ArtFullView(
                    art = selected,
                    bookTitle = state.bookTitles[selected.bookId] ?: "",
                    madeFrom = state.selectedMadeFrom,
                    artFile = artFile,
                    onSave = {
                        val file = artFile(selected.imagePath)
                        viewModel.onSaveResult(
                            file.exists() && savePageToDevice(context, file)
                        )
                    },
                    onSetAsCover = { viewModel.setAsCover(selected) },
                    onShare = { shareArt(selected) },
                    onDelete = { viewModel.deleteSelected() }
                )
                state.art.isEmpty() -> app.xreader.ui.common.M3EmptyState(
                    headline = "No generated art yet",
                    body = "Open a book's Lore page and tap Illustrate — " +
                        "the pictures it paints gather here."
                )
                else -> LazyVerticalGrid(
                    columns = artColumns,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(
                        top = 8.dp, bottom = 32.dp
                    )
                ) {
                    items(state.art, key = { "m3-art-${it.id}" }) { art ->
                        var menuOpen by remember {
                            mutableStateOf(false)
                        }
                        Column {
                            app.xreader.ui.common.M3PosterCard(
                                title = state.bookTitles[art.bookId]
                                    ?: "Generated art",
                                author = null,
                                coverModel = artFile(art.imagePath),
                                metaLine = art.passageQuote
                                    .takeIf { it.isNotBlank() },
                                onClick = { viewModel.select(art) }
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            state.bookTitles[art.bookId]
                                                ?: "Book"
                                        )
                                    }
                                )
                                Spacer(Modifier.weight(1f))
                                Box {
                                    IconButton(
                                        onClick = { menuOpen = true }
                                    ) {
                                        Icon(
                                            androidx.compose.material.icons
                                                .Icons.Filled.MoreVert,
                                            contentDescription =
                                                "Picture actions"
                                        )
                                    }
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = {
                                            menuOpen = false
                                        }
                                    ) {
                                        androidx.compose.material3
                                            .DropdownMenuItem(
                                                text = {
                                                    Text("Set as cover")
                                                },
                                                onClick = {
                                                    menuOpen = false
                                                    viewModel.setAsCover(art)
                                                }
                                            )
                                        androidx.compose.material3
                                            .DropdownMenuItem(
                                                text = { Text("Share") },
                                                onClick = {
                                                    menuOpen = false
                                                    shareArt(art)
                                                }
                                            )
                                        androidx.compose.material3
                                            .DropdownMenuItem(
                                                text = { Text("Delete") },
                                                onClick = {
                                                    menuOpen = false
                                                    viewModel.deleteArt(art)
                                                }
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun M3ArtFullView(
    art: LoreArt,
    bookTitle: String,
    madeFrom: String,
    artFile: (String) -> File,
    onSave: () -> Unit,
    onSetAsCover: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        AsyncImage(
            model = artFile(art.imagePath),
            contentDescription = art.passageQuote,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        )
        Spacer(Modifier.height(16.dp))
        if (bookTitle.isNotBlank()) {
            Text(bookTitle, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
        }
        if (madeFrom.isNotBlank()) {
            Text(
                if (art.entityId != null) {
                    "Painted from the lore entry: $madeFrom"
                } else {
                    "Painted from this passage: “$madeFrom”"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
        androidx.compose.material3.Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save to phone") }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            androidx.compose.material3.OutlinedButton(
                onClick = onSetAsCover,
                modifier = Modifier.weight(1f)
            ) { Text("Set as cover") }
            androidx.compose.material3.OutlinedButton(
                onClick = onShare,
                modifier = Modifier.weight(1f)
            ) { Text("Share") }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = onDelete) {
                Text(
                    "Delete picture",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
