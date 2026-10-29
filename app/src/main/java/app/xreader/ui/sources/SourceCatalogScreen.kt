package app.xreader.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.core.motion.listPlacementSpec
import app.xreader.core.motion.pressSquish
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage

/**
 * A source's catalogue (PLAN §5): Popular / Latest / Search with
 * paging. Covers load straight from the source's site; tapping a book
 * adds it to the library (details + chapter list) and opens it.
 */
@Composable
fun SourceCatalogScreen(
    viewModel: SourceCatalogViewModel,
    extensionId: Long,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onLogin: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.openedBook.collect { bookId -> onOpenBook(bookId) }
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
            Column(Modifier.weight(1f)) {
                Text(
                    state.sourceName.ifBlank { "Source" },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onLogin) { Text("Log in") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogMode.entries.forEach { mode ->
                ModeChip(
                    label = when (mode) {
                        CatalogMode.POPULAR -> "Popular"
                        CatalogMode.LATEST -> "Latest"
                        CatalogMode.SEARCH -> "Search"
                    },
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) }
                )
            }
        }
        if (state.mode == CatalogMode.SEARCH) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { viewModel.setQuery(it) },
                    label = { Text("Search this source") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                XreaderPrimaryButton(
                    text = "Search",
                    onClick = { viewModel.submitSearch() },
                    enabled = state.query.isNotBlank()
                )
            }
        }
        if (state.loading && state.books.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.errorMessage != null && state.books.isEmpty() -> {
                EmptyState(
                    title = "This source didn't answer",
                    message = state.errorMessage!!,
                    actionLabel = "Try again",
                    onAction = { viewModel.retry() },
                    modifier = Modifier.weight(1f)
                )
            }
            !state.loading && state.books.isEmpty() -> {
                EmptyState(
                    title = "Nothing here yet",
                    message = if (state.mode == CatalogMode.SEARCH) {
                        "No matches — try a different title."
                    } else {
                        "This page of the catalogue is empty."
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            else -> {
                // v1.0.2 (R13/item 16): cover-first grammar — the
                // same visual language as Library: a grid of covers
                // with designed placeholders, not a text list.
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid
                        .GridCells.Adaptive(minSize = 118.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(
                        state.books, key = { "book-${it.id}" }
                    ) { book ->
                        Column(
                            Modifier
                                .animateItem(
                                    placementSpec = listPlacementSpec()
                                )
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.openBook(book) }
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(12.dp))
                            ) {
                                if (!book.coverUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = book.coverUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    app.xreader.ui.library.PlaceholderCover(
                                        title = book.title,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                if (state.openingBookId == book.id) {
                                    CircularProgressIndicator(
                                        Modifier
                                            .size(26.dp)
                                            .align(Alignment.Center)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                book.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!book.author.isNullOrBlank()) {
                                Text(
                                    book.author!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (state.hasNext) {
                        item(
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            TextButton(
                                onClick = { viewModel.loadMore() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (state.loading) "Loading…" else "Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .pressSquish(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
