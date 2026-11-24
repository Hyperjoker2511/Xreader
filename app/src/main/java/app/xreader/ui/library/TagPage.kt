package app.xreader.ui.library

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.xreader.data.db.Book
import app.xreader.di.AppContainer
import app.xreader.ui.common.EmptyState
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.common.XreaderPrimaryButton
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.xreader.ui.theme.LocalUiMetrics

/**
 * A tag's page (v1.0.2, R22): every library book carrying the tag,
 * with counts, plus the Follow switch — followed tags power the
 * Library's "For you" picks and are a shelf of their own. Source
 * copies are found through Browse/Global search (the plan keeps
 * tag pages library-first and honest about it).
 */
data class TagPageUiState(
    val displayName: String = "",
    val books: List<Book> = emptyList(),
    val followed: Boolean = false,
    val loading: Boolean = true
)

class TagPageViewModel(
    private val container: AppContainer,
    private val slug: String
) : ViewModel() {

    val state: StateFlow<TagPageUiState> = combine(
        container.database.bookDao().observeAllBooks(),
        container.database.tagDao().observeAllBookTags(),
        container.database.tagDao().observeFollows()
    ) { books, bookTags, follows ->
        val memberIds = bookTags
            .filter { it.slug == slug && !it.suppressed }
            .map { it.bookId }
            .toSet()
        TagPageUiState(
            displayName = app.xreader.data.library.TagRegistry.displayFor(slug),
            books = books.filter { it.id in memberIds && it.mergedIntoId == null },
            followed = follows.any { it.slug == slug },
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagPageUiState())

    fun setFollowed(follow: Boolean) {
        viewModelScope.launch {
            val dao = container.database.tagDao()
            if (dao.tag(slug) == null) {
                dao.insertTag(
                    app.xreader.data.db.Tag(
                        slug = slug,
                        displayName = app.xreader.data.library.TagRegistry
                            .displayFor(slug),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            if (follow) {
                dao.follow(
                    app.xreader.data.db.TagFollow(
                        slug = slug, followedAt = System.currentTimeMillis()
                    )
                )
            } else {
                dao.unfollow(slug)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, slug: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TagPageViewModel(container, slug) as T
            }
    }
}

@Composable
fun TagPageScreen(
    viewModel: TagPageViewModel,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Code 121 (R9): the Material 3 tag page — a shelf, not a
    // new screen type: medium flexible bar (tag + book count)
    // over the R1 shelf card list filtered to the tag. The
    // Nocturne composition below is unchanged.
    if (app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        M3TagContent(
            viewModel = viewModel,
            coverFile = coverFile,
            onBack = onBack,
            onOpenBook = onOpenBook
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
    ) {
        ScreenHeader(
            title = state.displayName,
            eyebrow = "Tag",
            action = {
                XreaderPrimaryButton(
                    text = if (state.followed) "Following ✓" else "Follow tag",
                    onClick = { viewModel.setFollowed(!state.followed) }
                )
            }
        )
        TextButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(8.dp))
        Text(
            "${state.books.size} book(s) in your library with this tag",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        if (!state.loading && state.books.isEmpty()) {
            EmptyState(
                title = "No books with this tag yet",
                message = "When a book carries this tag — from its source, " +
                    "the synopsis, or your own edit — it will be listed here.",
                modifier = Modifier.weight(1f)
            )
            return@Column
        }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.books, key = { "tagbook-${it.id}" }) { book ->
                GlassCard(Modifier.fillMaxWidth(), onClick = { onOpenBook(book.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val cover = coverFile(book.coverPath)
                        if (cover != null && cover.exists()) {
                            AsyncImage(
                                model = cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 44.dp, height = 62.dp)
                            )
                        } else {
                            PlaceholderCover(
                                title = book.title,
                                modifier = Modifier.size(width = 44.dp, height = 62.dp)
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                book.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            Text(
                                listOfNotNull(
                                    book.author.takeIf { it.isNotBlank() },
                                    "${book.totalChapters} chapters"
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Code 121 (R9): the Material 3 tag page ─────────────────
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)
@Composable
private fun M3TagContent(
    viewModel: TagPageViewModel,
    coverFile: (String?) -> File?,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults
        .exitUntilCollapsedScrollBehavior()
    androidx.compose.material3.Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.MediumFlexibleTopAppBar(
                title = {
                    Text(
                        state.displayName,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                subtitle = {
                    Text(
                        if (state.books.size == 1) {
                            "1 book"
                        } else {
                            "${state.books.size} books"
                        }
                    )
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    androidx.compose.material3.ToggleButton(
                        checked = state.followed,
                        onCheckedChange = { viewModel.setFollowed(it) }
                    ) {
                        Text(if (state.followed) "Following" else "Follow tag")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        if (!state.loading && state.books.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "No books with this tag yet",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "When a book carries this tag — from its source, " +
                        "the synopsis, or your own edit — it will be " +
                        "listed here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp
            )
        ) {
            items(state.books, key = { "m3-tagbook-${it.id}" }) { book ->
                app.xreader.ui.common.M3BookCard(
                    title = book.title,
                    author = book.author.takeIf { it.isNotBlank() },
                    coverModel = coverFile(book.coverPath)
                        ?.takeIf { it.exists() }
                        ?: book.coverUrl?.takeIf { it.isNotBlank() },
                    supportingLine = if (book.totalChapters > 0) {
                        "${book.totalChapters} chapters"
                    } else {
                        null
                    },
                    onClick = { onOpenBook(book.id) }
                )
            }
        }
    }
}
