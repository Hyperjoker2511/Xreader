package app.xreader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.xreader.di.AppContainer
import app.xreader.domain.reader.TermPair
import app.xreader.ui.common.KitEmptyState
import app.xreader.ui.common.RowItem
import app.xreader.ui.common.ScreenHeader
import app.xreader.ui.more.TermsEditor
import app.xreader.ui.theme.LocalUiMetrics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Patch 11 (Part B): "Terms for this book" as its own full
 * PAGE — the owner's anti-clutter word ("It should have
 * separate page — if we replace more it gets cluttered"):
 * the reader settings sheet keeps only a door row; the pairs
 * themselves live here, in the same page pattern as the
 * global Terms screen (header, back, one calm editor column),
 * so the list can grow without crowding anything.
 *
 * The editor is the shared [TermsEditor], bound to THIS
 * book's pairs through TermsPrefs' per-book API — the same
 * store the reader sheet's inline editor used, so nothing
 * about the data changed, only its home. Saving re-derives
 * the reader's display instantly (the pairs flow is live).
 *
 * Part C rides at the bottom: a "Terms for all books" door
 * to the global list's page — the global editor's only home
 * is now inside the reader flow, next to this book's list.
 */
class BookTermsViewModel(
    private val container: AppContainer,
    private val bookId: Long
) : ViewModel() {

    val pairs: StateFlow<List<TermPair>> =
        container.termsPrefs.bookPairs(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var bookTitle by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            bookTitle = container.database.bookDao().getById(bookId)?.title ?: ""
        }
    }

    fun setPairs(pairs: List<TermPair>) {
        viewModelScope.launch { container.termsPrefs.setBookPairs(bookId, pairs) }
    }

    companion object {
        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BookTermsViewModel(container, bookId) as T
            }
    }
}

@Composable
fun BookTermsScreen(
    viewModel: BookTermsViewModel,
    onBack: () -> Unit,
    onOpenGlobalTerms: () -> Unit
) {
    val pairs by viewModel.pairs.collectAsStateWithLifecycle()
    // Code 121 W8 (R13): the Material 3 per-book terms page —
    // the shared M3TermsContent at this book's scope.
    if (app.xreader.ui.theme.LocalDesignState.current.design !=
        app.xreader.data.prefs.AppDesign.NOCTURNE
    ) {
        app.xreader.ui.more.M3TermsContent(
            title = viewModel.bookTitle.ifBlank { "This book" },
            scopeLine = "Applies to this book only",
            pairs = pairs,
            onChange = { viewModel.setPairs(it) },
            onBack = onBack,
            globalDoor = onOpenGlobalTerms
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = LocalUiMetrics.current.topGap, bottom = 16.dp)
            .padding(bottom = 72.dp)
    ) {
        ScreenHeader(
            title = "Terms for this book",
            eyebrow = viewModel.bookTitle.ifBlank { "This book" }
        )
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            "Names or words in THIS book that you want shown " +
                "differently. They apply to this book only, and " +
                "only to what you see — the book's own text is " +
                "never changed. Deleting a pair brings the " +
                "original wording back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        if (pairs.isEmpty()) {
            KitEmptyState(
                icon = Icons.Filled.FindReplace,
                message = "No terms for this book yet. Long-press " +
                    "a word while reading and choose Replace, or " +
                    "add one here."
            )
            Spacer(Modifier.height(12.dp))
        }
        TermsEditor(
            pairs = pairs,
            onChange = { viewModel.setPairs(it) }
        )
        Spacer(Modifier.height(24.dp))
        RowItem(
            icon = Icons.Filled.FindReplace,
            label = "Terms for all books",
            value = "Every book",
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenGlobalTerms
        )
        Text(
            "Pairs that apply in every book. This book's own " +
                "pairs win when both name the same word.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
