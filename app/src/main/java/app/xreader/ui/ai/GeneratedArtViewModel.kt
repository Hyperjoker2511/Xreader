package app.xreader.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.data.db.LoreArt
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GeneratedArtUiState(
    /** Every picture from every book, newest first (the DAO's
     * observeAllArt order — this VM never re-sorts). */
    val art: List<LoreArt> = emptyList(),
    val bookTitles: Map<Long, String> = emptyMap(),
    /** The picture open in the full view, if any. Derived from
     * the live list by id, so a deletion closes it on its own. */
    val selected: LoreArt? = null,
    /** What the selected picture was made from: the lore entry's
     * name when it was painted from one, else its passage quote. */
    val selectedMadeFrom: String = "",
    val message: String? = null
)

/**
 * The Generated art gallery (Patch 10, Addendum 2): all of the
 * app's painted pictures in one place. Deleting goes through the
 * ONE shared art delete path ([app.xreader.data.ai.LoreArtStore])
 * — the same one the Lore page uses.
 */
class GeneratedArtViewModel(private val container: AppContainer) : ViewModel() {

    private data class LocalState(
        val selectedId: Long? = null,
        val madeFrom: String = "",
        val message: String? = null
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<GeneratedArtUiState> = combine(
        container.database.loreArtDao().observeAllArt(),
        container.database.bookDao().observeAllBooks(),
        local
    ) { art, books, localState ->
        val selected = localState.selectedId?.let { id ->
            art.firstOrNull { it.id == id }
        }
        GeneratedArtUiState(
            art = art,
            bookTitles = books.associate { it.id to it.title },
            selected = selected,
            selectedMadeFrom = if (selected != null) {
                localState.madeFrom
            } else {
                ""
            },
            message = localState.message
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GeneratedArtUiState()
    )

    fun select(art: LoreArt) {
        local.value = local.value.copy(
            selectedId = art.id,
            madeFrom = art.passageQuote,
            message = null
        )
        val entityId = art.entityId ?: return
        viewModelScope.launch {
            val name = container.database.loreEntityDao().forBook(art.bookId)
                .firstOrNull { it.id == entityId }?.name
            if (name != null && local.value.selectedId == art.id) {
                local.value = local.value.copy(madeFrom = name)
            }
        }
    }

    fun clearSelection() {
        local.value = local.value.copy(selectedId = null, madeFrom = "")
    }

    /** The shared delete path — row and file together, exactly
     * as the Lore page's Remove does it. */
    fun deleteSelected() {
        val art = state.value.selected ?: return
        viewModelScope.launch {
            container.loreArtStore.delete(art)
            local.value = local.value.copy(
                selectedId = null,
                madeFrom = "",
                message = "Picture deleted."
            )
        }
    }

    fun onSaveResult(saved: Boolean) {
        local.value = local.value.copy(
            message = if (saved) {
                "Saved to your pictures."
            } else {
                "Couldn't save that picture to the phone."
            }
        )
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GeneratedArtViewModel(container) as T
            }
    }
}
