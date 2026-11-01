package app.xreader.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.extension.player.PlayerFailureWords
import app.xreader.data.extension.player.SourcePrefRow
import app.xreader.data.extension.player.SourcePreferenceHost
import app.xreader.di.AppContainer
import eu.kanade.tachiyomi.source.ConfigurableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SourcePreferencesUiState(
    val sourceName: String = "",
    val loading: Boolean = true,
    val rows: List<SourcePrefRow> = emptyList(),
    /** The source isn't a player source (or is gone): the
     * screen shows this plain line instead of a settings list
     * that could never work. */
    val unavailableMessage: String? = null,
    /** The source is a player source with no settings of its
     * own (not a ConfigurableSource): the screen shows the
     * honest line, not an empty form. */
    val noSettings: Boolean = false,
    val errorMessage: String? = null
)

/**
 * K5: one player source's own settings. Resolution mirrors
 * the catalogue's (same service door, same handle), then the
 * source's preference screen is built headlessly by
 * [SourcePreferenceHost] over the player's namespaced context
 * and rendered as rows; edits run back through the host on IO
 * and the state takes the host's fresh snapshot, so what the
 * screen shows is always what actually persisted (a source's
 * change-listener veto included).
 *
 * These settings are the extension's own store by
 * construction — they apply to this source only, never app-
 * wide; there is no second tier to reconcile (the two-tier
 * rule's per-book tier is what a source's own store IS).
 */
class SourcePreferencesViewModel(
    private val container: AppContainer,
    private val extensionId: Long,
    private val sourceId: String
) : ViewModel() {

    private val _state = MutableStateFlow(SourcePreferencesUiState())
    val state: StateFlow<SourcePreferencesUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var host: SourcePreferenceHost? = null

    init {
        viewModelScope.launch {
            val found = container.extensionService
                .source(extensionId, sourceId)
            when (found) {
                is AppResult.Err -> _state.value = _state.value.copy(
                    loading = false,
                    unavailableMessage = ErrorText.displayText(found.error)
                )
                is AppResult.Ok -> {
                    val handle = found.value
                    _state.value = _state.value.copy(
                        sourceName = handle.source.name
                    )
                    val adapter = container.extensionService
                        .mihonAdapterOf(handle)
                    val mihon = adapter?.mihon
                    when {
                        adapter == null || mihon == null ->
                            _state.value = _state.value.copy(
                                loading = false,
                                unavailableMessage =
                                    "This source's settings live on " +
                                        "its own page."
                            )
                        mihon !is ConfigurableSource ->
                            _state.value = _state.value.copy(
                                loading = false,
                                noSettings = true
                            )
                        else -> {
                            val built = SourcePreferenceHost(
                                context = container.playerApplication,
                                packageName = adapter.packageName,
                                source = mihon
                            )
                            host = built
                            runHost { built.build() }
                        }
                    }
                }
            }
        }
    }

    fun setChecked(row: SourcePrefRow, value: Boolean) {
        val current = host ?: return
        runHost { current.setChecked(row.id, value) }
    }

    fun setChoice(row: SourcePrefRow, value: String) {
        val current = host ?: return
        runHost { current.setChoice(row.id, value) }
    }

    fun setText(row: SourcePrefRow, value: String) {
        val current = host ?: return
        runHost { current.setText(row.id, value) }
    }

    /** Toggle one value of a MULTI_CHOICE row: the new set is
     * computed from the row's CURRENT snapshot (the screen
     * passes the freshest row it has). */
    fun toggleMultiValue(row: SourcePrefRow, value: String) {
        val current = host ?: return
        val next = if (value in row.selectedValues) {
            row.selectedValues - value
        } else {
            row.selectedValues + value
        }
        runHost { current.setMultiChoice(row.id, next) }
    }

    fun click(row: SourcePrefRow) {
        val current = host ?: return
        runHost { current.click(row.id) }
    }

    /** Run one host call on IO and take its snapshot as the
     * new state. A source that throws mid-edit (its listener
     * crashed) earns a plain-words note, not a dead screen —
     * the rows stay at their last good snapshot. */
    private fun runHost(call: () -> List<SourcePrefRow>) {
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { call() }
                _state.value = _state.value.copy(
                    loading = false,
                    rows = rows,
                    errorMessage = null
                )
            } catch (t: Throwable) {
                val words = failureWords(t)
                val hadRows = _state.value.rows.isNotEmpty()
                _state.value = _state.value.copy(
                    loading = false,
                    // The first build failing leaves an empty
                    // screen: the words belong ON it. A later
                    // edit failing keeps the last good rows and
                    // the words arrive as a transient note.
                    errorMessage = if (hadRows) {
                        _state.value.errorMessage
                    } else {
                        words
                    }
                )
                if (hadRows) _messages.tryEmit(words)
            }
        }
    }

    /** Plain words for a settings failure: the player's known
     * gaps (missing class, script engine, OkHttp version)
     * keep [PlayerFailureWords]' wording; anything else is a
     * settings failure, said plainly. */
    private fun failureWords(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }
        val isGap = chain.any { e ->
            PlayerFailureWords.missingClassOf(e) != null ||
                (e is UnsupportedOperationException &&
                    e.message?.contains("JavaScriptEngine") == true)
        }
        return if (isGap) {
            PlayerFailureWords.describe(t).reason
        } else {
            "This source's settings couldn't be opened."
        }
    }

    companion object {
        fun factory(
            container: AppContainer,
            extensionId: Long,
            sourceId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SourcePreferencesViewModel(
                    container, extensionId, sourceId
                ) as T
        }
    }
}
