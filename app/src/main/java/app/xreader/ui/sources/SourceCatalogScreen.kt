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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.setValue
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SourceCatalogScreen(
    viewModel: SourceCatalogViewModel,
    extensionId: Long,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onLogin: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.0.6.2 (F8a): search opens from an icon, like the Library —
    // the field itself only takes room while searching.
    var searchOpen by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

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
            IconButton(
                onClick = {
                    viewModel.setMode(CatalogMode.SEARCH)
                    searchOpen = true
                }
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Search this source"
                )
            }
            TextButton(onClick = onLogin) { Text("Log in") }
        }
        Spacer(Modifier.height(8.dp))
        // v1.0.5 (B3): a FlowRow — the three mode chips wrap
        // instead of ever squeezing on narrow screens.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CatalogMode.entries.forEach { mode ->
                ModeChip(
                    label = when (mode) {
                        CatalogMode.POPULAR -> "Popular"
                        CatalogMode.LATEST -> "Latest"
                        CatalogMode.SEARCH -> "Search"
                    },
                    selected = state.mode == mode,
                    onClick = {
                        viewModel.setMode(mode)
                        if (mode == CatalogMode.SEARCH) searchOpen = true
                    }
                )
            }
        }
        if (state.mode == CatalogMode.SEARCH && searchOpen) {
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
                        // v1.0.6.2 (S1): the same shared poster
                        // card as the Library — one card everywhere.
                        app.xreader.ui.common.BookPosterCard(
                            title = book.title,
                            author = book.author?.takeIf { it.isNotBlank() },
                            coverModel = book.coverUrl
                                ?.takeIf { it.isNotBlank() },
                            metaLine = null,
                            busy = state.openingBookId == book.id,
                            onClick = { viewModel.openBook(book) },
                            modifier = Modifier.animateItem(
                                placementSpec = listPlacementSpec()
                            )
                        )
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
                    if (!state.hasNext && state.page > 1 && state.books.isNotEmpty()) {
                        // v1.0.6.2 (F2): the honest end of the
                        // catalogue — shown once paging actually
                        // walked past page 1 and a page added
                        // nothing new.
                        item(
                            span = {
                                androidx.compose.foundation.lazy.grid
                                    .GridItemSpan(maxLineSpan)
                            }
                        ) {
                            Text(
                                "That's everything here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
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
