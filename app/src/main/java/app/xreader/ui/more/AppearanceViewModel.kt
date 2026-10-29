package app.xreader.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.data.prefs.AppearancePrefs
import app.xreader.data.prefs.AppearanceState
import app.xreader.data.prefs.GlassStyle
import app.xreader.data.prefs.ThemeMode
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Appearance (PLAN §3): the glass sliders write through [AppearancePrefs]
 * (the single writer) and the whole app re-tokens live, because
 * MainActivity feeds this same state into the theme.
 */
class AppearanceViewModel(private val prefs: AppearancePrefs) : ViewModel() {

    val state: StateFlow<AppearanceState> = prefs.state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppearanceState()
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setGlassOpacity(value: Float) {
        viewModelScope.launch { prefs.setGlassOpacity(value) }
    }

    fun setGlassRadiusScale(value: Float) {
        viewModelScope.launch { prefs.setGlassRadiusScale(value) }
    }

    fun setGlassElevationScale(value: Float) {
        viewModelScope.launch { prefs.setGlassElevationScale(value) }
    }

    fun setReduceMotion(value: Boolean) {
        viewModelScope.launch { prefs.setReduceMotion(value) }
    }

    fun setColourScheme(name: String) {
        viewModelScope.launch { prefs.setColourScheme(name) }
    }

    fun setGlassStyle(style: GlassStyle) {
        viewModelScope.launch { prefs.setGlassStyle(style) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppearanceViewModel(container.appearancePrefs) as T
            }
    }
}
