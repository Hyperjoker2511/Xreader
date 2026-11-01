package app.xreader.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.data.extension.PackCheckOutcome
import app.xreader.data.extension.PackInfo
import app.xreader.di.AppContainer
import app.xreader.ui.common.GhostPill
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.PrimaryPill
import app.xreader.ui.theme.primaryTextColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * v1.3.5 (Cycle 1, provider packs): the pack channel's small
 * UI life — a card on the Repositories screen carrying the
 * pack's plain-words state ("Updated by pack · <date>"), the
 * manual "Check for pack updates" action and one-tap
 * rollback to the previous pack. The automatic check runs
 * throttled from the shell's startup; failures there are
 * silent, exactly as they are here (the message line only
 * ever reports what the user asked for).
 */
data class PackUiState(
    val info: PackInfo? = null,
    val busy: Boolean = false,
    val message: String? = null
)

class ProviderPackViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val _state = MutableStateFlow(PackUiState())
    val state: StateFlow<PackUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(
            info = container.providerPackManager.info()
        )
    }

    fun checkNow() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val message = when (
                val outcome = container.providerPackManager
                    .checkForUpdates(manual = true)
            ) {
                is PackCheckOutcome.Updated ->
                    "Pack updated — ${outcome.report.added.size} new, " +
                        "${outcome.report.updated.size} refreshed."
                is PackCheckOutcome.UpToDate ->
                    "Your pack is already up to date."
                is PackCheckOutcome.SkippedThrottled ->
                    "Just checked — you're up to date."
                is PackCheckOutcome.Failed ->
                    "Couldn't check just now — your sources keep working as they are."
            }
            _state.value = PackUiState(
                info = container.providerPackManager.info(),
                busy = false,
                message = message
            )
        }
    }

    fun rollback() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val message = when (
                val result = container.providerPackManager.rollback()
            ) {
                is AppResult.Ok ->
                    "Back on pack v${result.value.version}."
                is AppResult.Err ->
                    "There's no earlier pack to go back to."
            }
            _state.value = PackUiState(
                info = container.providerPackManager.info(),
                busy = false,
                message = message
            )
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ProviderPackViewModel(container) as T
            }
    }
}

@Composable
fun ProviderPackCard(viewModel: ProviderPackViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val info = state.info
    GlassCard {
        Text(
            "Provider pack",
            style = MaterialTheme.typography.titleMedium,
            color = primaryTextColor()
        )
        Text(
            when {
                info == null ->
                    "New sources arrive between app updates — " +
                        "nothing has been delivered by pack yet."
                info.sourceIds.isEmpty() ->
                    "Pack v${info.version} · ${info.date} — " +
                        "no pack sources yet."
                else ->
                    "${info.sourceIds.size} sources · " +
                        "Updated by pack · ${info.date}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.message?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = primaryTextColor()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PrimaryPill(
                text = if (state.busy) "Checking…" else "Check for pack updates",
                enabled = !state.busy,
                onClick = { viewModel.checkNow() },
                modifier = Modifier.weight(1f)
            )
            if (info?.canRollback == true) {
                GhostPill(
                    text = "Roll back",
                    enabled = !state.busy,
                    onClick = { viewModel.rollback() },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (info?.canRollback == true) {
            Text(
                "Roll back returns every pack source to pack " +
                    "v${info.previousVersion}, exactly as it was.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
