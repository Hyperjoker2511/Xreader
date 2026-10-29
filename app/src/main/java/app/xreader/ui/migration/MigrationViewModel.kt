package app.xreader.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.migration.MigratedLibrary
import app.xreader.data.migration.MigrationParsers
import app.xreader.data.migration.MigrationService
import app.xreader.di.AppContainer
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface MigrationUiState {
    data object Idle : MigrationUiState
    data object Reading : MigrationUiState
    data class Preview(
        val library: MigratedLibrary,
        val preview: MigrationService.Preview
    ) : MigrationUiState

    data object Importing : MigrationUiState
    data class Done(val lines: List<String>, val unmatchedTitles: List<String>) :
        MigrationUiState

    data class Failed(val message: String) : MigrationUiState
}

/**
 * Migration Studio (screen #19, PLAN §8): pick a backup → preview
 * what it holds and what will match → import → a plain-words report
 * of exactly what arrived, what was already here, and what still
 * needs a source. Nothing is written before the Import tap.
 */
class MigrationViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow<MigrationUiState>(MigrationUiState.Idle)
    val state: StateFlow<MigrationUiState> = _state

    fun pickFile(openStream: () -> InputStream?) {
        _state.value = MigrationUiState.Reading
        viewModelScope.launch {
            val bytes = runCatching {
                openStream()?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                _state.value = MigrationUiState.Failed(
                    "That file couldn't be opened — try picking it again."
                )
                return@launch
            }
            when (val parsed = MigrationParsers.parse(bytes)) {
                is AppResult.Err -> _state.value =
                    MigrationUiState.Failed(ErrorText.displayText(parsed.error))
                is AppResult.Ok -> {
                    val preview = container.migrationService.preview(parsed.value)
                    _state.value = MigrationUiState.Preview(parsed.value, preview)
                }
            }
        }
    }

    fun confirmImport() {
        val current = _state.value as? MigrationUiState.Preview ?: return
        _state.value = MigrationUiState.Importing
        viewModelScope.launch {
            when (val result = container.migrationService.apply(current.library)) {
                is AppResult.Err -> _state.value =
                    MigrationUiState.Failed(ErrorText.displayText(result.error))
                is AppResult.Ok -> {
                    // Imported read-state is EXP-bearing history:
                    // settle so achievements/profile agree.
                    runCatching { container.expService.settle() }
                    _state.value = MigrationUiState.Done(
                        lines = result.value.lines(),
                        unmatchedTitles = result.value.unmatchedTitles
                    )
                }
            }
        }
    }

    fun reset() {
        _state.value = MigrationUiState.Idle
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MigrationViewModel(container) as T
            }
    }
}
