package app.xreader.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
                columns = GridCells.Fixed(2),
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
