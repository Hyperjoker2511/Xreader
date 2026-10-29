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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
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
