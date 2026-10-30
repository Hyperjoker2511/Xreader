package app.xreader.ui.more

import kotlin.math.roundToInt
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.xreader.data.library.LibraryLayout
import app.xreader.data.library.LibraryState
import app.xreader.data.library.NavBarStyle
import app.xreader.data.library.StartScreen
import app.xreader.data.library.TabLabelStyle
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import app.xreader.data.prefs.AppearanceState
import app.xreader.data.prefs.CUSTOM_SCHEME_PREFIX
import app.xreader.data.prefs.CustomSchemeSpec
import app.xreader.data.prefs.GlassStyle
import app.xreader.ui.theme.CustomSchemeGenerator
import app.xreader.di.AppContainer
import app.xreader.domain.reader.PagedDirection
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.TapZoneLayout
import app.xreader.ui.common.GlassCard
import app.xreader.ui.theme.ReaderPalettes
import app.xreader.ui.theme.maxContrast
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Customise (v1.0.1): the ONE home for every look-and-feel control —
 * colour theme, glass, library layout, navigation, and the reader's
 * look. The same DataStore state the rest of the app reads; the old
 * Appearance copies were removed (one home per setting).
 */
class CustomiseViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val appearance: AppearanceState = AppearanceState(),
        val library: LibraryState = LibraryState(),
        val reader: ReaderConfig = ReaderConfig(),
        val bestSourcePriority: app.xreader.data.extension.BestSourcePriority =
            app.xreader.data.extension.BestSourcePriority.DOWNLOADED_FIRST,
        /** v1.0.4 (D10/D13): the download switches' home — the same
         * DownloadPrefs the download engine reads. */
        val downloads: app.xreader.data.download.DownloadSettings =
            app.xreader.data.download.DownloadSettings(),
        /** v1.0.6 (W2.4): the browser-view master switch. */
        val browserViewEnabled: Boolean = true
    )

    val state: StateFlow<UiState> = combine(
        container.appearancePrefs.state,
        container.libraryPrefs.state,
        container.readerPrefs.config,
        container.sourcePrefs.bestSourcePriority,
        container.downloadPrefs.settings,
        container.sourcePrefs.browserViewEnabled
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        UiState(
            appearance = values[0] as AppearanceState,
            library = values[1] as LibraryState,
            reader = values[2] as ReaderConfig,
            bestSourcePriority = values[3]
                as app.xreader.data.extension.BestSourcePriority,
            downloads = values[4]
                as app.xreader.data.download.DownloadSettings,
            browserViewEnabled = values[5] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Storage sizes load off the main thread and refresh after a
     * cache clear; kept out of [state] so a slow disk walk never
     * stalls the settings state. */
    val storage =
        kotlinx.coroutines.flow.MutableStateFlow<app.xreader.data.files.StorageBreakdown?>(null)

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch(container.ioDispatcher) {
            storage.value = runCatching {
                app.xreader.data.files.StorageMeter.measure(container.bookStorage.filesDir)
            }.getOrNull()
        }
    }

    /** Clear ONLY rebuildable cache (covers + temp). Downloaded
     * chapters, book files, progress and settings are never touched
     * here (R07/G16 — different words, different confirmations). */
    fun clearCaches() {
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                container.bookStorage.coversDir().listFiles()?.forEach { it.deleteRecursively() }
                container.bookStorage.downloadsTempDir().listFiles()
                    ?.forEach { it.deleteRecursively() }
            }
            refreshStorage()
        }
    }

    fun setColourScheme(name: String) {
        viewModelScope.launch { container.appearancePrefs.setColourScheme(name) }
    }

    fun setThemeMode(mode: app.xreader.data.prefs.ThemeMode) {
        viewModelScope.launch { container.appearancePrefs.setThemeMode(mode) }
    }

    fun setReduceMotion(value: Boolean) {
        viewModelScope.launch { container.appearancePrefs.setReduceMotion(value) }
    }

    // ── R05 / R06 / R07 controls ─────────────────────────────────

    fun setBookColours(scope: app.xreader.data.prefs.BookColoursScope) {
        viewModelScope.launch { container.appearancePrefs.setBookColours(scope) }
    }

    fun setCoverGlow(value: Boolean) {
        viewModelScope.launch { container.appearancePrefs.setCoverGlow(value) }
    }

    fun setPerformanceMode(mode: app.xreader.data.prefs.PerformanceMode) {
        viewModelScope.launch { container.appearancePrefs.setPerformanceMode(mode) }
    }

    fun setAppTextSize(percent: Int) {
        viewModelScope.launch { container.appearancePrefs.setAppTextSizePercent(percent) }
    }

    fun setMeCardMotionTilt(value: Boolean) {
        viewModelScope.launch { container.appearancePrefs.setMeCardMotionTilt(value) }
    }

    fun setBestSourcePriority(priority: app.xreader.data.extension.BestSourcePriority) {
        viewModelScope.launch { container.sourcePrefs.setBestSourcePriority(priority) }
    }

    /** v1.0.6 (W2.4): the browser-view master switch. */
    fun setBrowserViewEnabled(value: Boolean) {
        viewModelScope.launch { container.sourcePrefs.setBrowserViewEnabled(value) }
    }

    // ── v1.0.4 (D10/D13): the download switches, moved here from
    // the Library and Downloads screens by the user's ruling. Both
    // write the SAME prefs the engine already reads — nothing about
    // download behaviour changes, only where the switches live. ──

    fun setAutoDownloadTracked(value: Boolean) {
        viewModelScope.launch { container.libraryPrefs.setAutoDownloadTracked(value) }
    }

    fun setWifiOnlyPrefetch(value: Boolean) {
        viewModelScope.launch { container.downloadPrefs.setWifiOnlyPrefetch(value) }
    }

    /** Reset customisation (R07): look-and-feel back to defaults.
     * Books, sources, repos, keys, shelves, tags, custom schemes and
     * progress are untouched — the screen says so before it runs. */
    fun resetCustomisation() {
        viewModelScope.launch {
            container.appearancePrefs.resetCustomisation()
            container.libraryPrefs.resetViewToDefaults()
            container.readerPrefs.resetToDefaults()
        }
    }

    fun setTrueBlack(value: Boolean) {
        viewModelScope.launch { container.appearancePrefs.setTrueBlack(value) }
    }

    /** Make-your-own (R04): create a custom scheme from a seed
     * colour (+ optional end colour) and select it straight away.
     * The three-scheme cap is enforced by AppearanceValidation;
     * the editor disables Create at the cap and says why. */
    fun addCustomScheme(seedArgb: Long, name: String, endArgb: Long? = null) {
        viewModelScope.launch {
            val current = state.value.appearance.customSchemes
            val spec = CustomSchemeSpec(
                id = "c${System.currentTimeMillis()}",
                name = name.ifBlank { "My theme" },
                seedArgb = seedArgb,
                endArgb = endArgb
            )
            val next = app.xreader.data.prefs.AppearanceValidation
                .addCustomScheme(current, spec) ?: return@launch
            container.appearancePrefs.setCustomSchemes(next)
            container.appearancePrefs.setColourScheme(CUSTOM_SCHEME_PREFIX + spec.id)
        }
    }

    fun renameCustomScheme(id: String, name: String) {
        viewModelScope.launch {
            val current = state.value.appearance.customSchemes
            container.appearancePrefs.setCustomSchemes(
                current.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }
            )
        }
    }

    fun updateCustomScheme(id: String, seedArgb: Long, endArgb: Long?) {
        viewModelScope.launch {
            val current = state.value.appearance.customSchemes
            container.appearancePrefs.setCustomSchemes(
                current.map {
                    if (it.id == id) it.copy(seedArgb = seedArgb, endArgb = endArgb) else it
                }
            )
        }
    }

    fun removeCustomScheme(id: String) {
        viewModelScope.launch {
            val appearance = state.value.appearance
            container.appearancePrefs.setCustomSchemes(
                appearance.customSchemes.filterNot { it.id == id }
            )
            // R04: deleting the ACTIVE custom scheme returns to
            // the fresh-install default (not a silent fall to Ember).
            if (appearance.colourScheme == CUSTOM_SCHEME_PREFIX + id) {
                container.appearancePrefs.setColourScheme(
                    app.xreader.data.prefs.AppearanceValidation.FRESH_INSTALL_SCHEME
                )
            }
        }
    }

    fun setGlassStyle(style: GlassStyle) {
        viewModelScope.launch { container.appearancePrefs.setGlassStyle(style) }
    }

    fun setGlassOpacity(value: Float) {
        viewModelScope.launch { container.appearancePrefs.setGlassOpacity(value) }
    }

    fun setGlassRadiusScale(value: Float) {
        viewModelScope.launch { container.appearancePrefs.setGlassRadiusScale(value) }
    }

    fun setGlassElevationScale(value: Float) {
        viewModelScope.launch { container.appearancePrefs.setGlassElevationScale(value) }
    }

    fun setLayout(value: LibraryLayout) {
        viewModelScope.launch { container.libraryPrefs.setLayout(value) }
    }

    fun setGridColumns(value: Int) {
        viewModelScope.launch { container.libraryPrefs.setGridColumns(value) }
    }

    fun setShowContinueReading(value: Boolean) {
        viewModelScope.launch { container.libraryPrefs.setShowContinueReading(value) }
    }

    fun setShowBadges(value: Boolean) {
        viewModelScope.launch { container.libraryPrefs.setShowBadges(value) }
    }

    fun setNavBarStyle(value: NavBarStyle) {
        viewModelScope.launch { container.libraryPrefs.setNavBarStyle(value) }
    }

    fun setTabLabelStyle(value: TabLabelStyle) {
        viewModelScope.launch { container.libraryPrefs.setTabLabelStyle(value) }
    }

    fun setStartScreen(value: StartScreen) {
        viewModelScope.launch { container.libraryPrefs.setStartScreen(value) }
    }

    fun updateReader(transform: (ReaderConfig) -> ReaderConfig) {
        viewModelScope.launch { container.readerPrefs.update(transform) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CustomiseViewModel(container) as T
            }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CustomiseScreen(viewModel: CustomiseViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val appearance = state.appearance
    val library = state.library
    val reader = state.reader
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Customise", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            "How Xreader looks and feels. Everything here applies straight away.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        CustomiseSection("Colour theme") {
            Text(
                "The colours the whole app wears.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val darkPreview = appearance.themeMode == app.xreader.data.prefs.ThemeMode.DARK ||
                (appearance.themeMode == app.xreader.data.prefs.ThemeMode.FOLLOW_SYSTEM &&
                    androidx.compose.foundation.isSystemInDarkTheme())
            // v1.0.6.2 (F8b): exactly three complete themes —
            // the full scheme list stays resolvable for installs
            // that already picked another, but the picker is
            // these three (the user's 1–3 options rule).
            app.xreader.ui.theme.AppColourThemes.forEach { scheme ->
                val colors = scheme.colors(darkPreview)
                val selected = appearance.colourScheme == scheme.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) colors.primary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { viewModel.setColourScheme(scheme.name) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.primary)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(scheme.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text(
                            "Sample text in this theme",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.primary)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (selected) "In use" else "Button",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onPrimary
                        )
                    }
                }
            }
            // Theme mode (moved here from the old Appearance door —
            // R07: one Customise home, "Appearance" no longer exists
            // as a separate destination).
            // v1.0.5 (B3): every chip/swatch row on this screen is a
            // FlowRow — fixed Rows crushed long labels ("Book page
            // + reader controls") on narrow screens; wrapping rows
            // can never crush.
            Text("Theme mode", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = appearance.themeMode ==
                        app.xreader.data.prefs.ThemeMode.FOLLOW_SYSTEM,
                    onClick = {
                        viewModel.setThemeMode(app.xreader.data.prefs.ThemeMode.FOLLOW_SYSTEM)
                    },
                    label = { Text("System") }
                )
                FilterChip(
                    selected = appearance.themeMode == app.xreader.data.prefs.ThemeMode.LIGHT,
                    onClick = {
                        viewModel.setThemeMode(app.xreader.data.prefs.ThemeMode.LIGHT)
                    },
                    label = { Text("Light") }
                )
                FilterChip(
                    selected = appearance.themeMode == app.xreader.data.prefs.ThemeMode.DARK,
                    onClick = {
                        viewModel.setThemeMode(app.xreader.data.prefs.ThemeMode.DARK)
                    },
                    label = { Text("Dark") }
                )
            }
            // Wallpaper colours (R04, Material You): API 31+ only —
            // the row simply doesn't exist where the platform can't
            // provide it.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val selected = appearance.colourScheme == "Wallpaper"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { viewModel.setColourScheme("Wallpaper") }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Wallpaper colours", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "The app takes its colours from your wallpaper. " +
                                "Xreader still checks every colour pair for readability.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (selected) {
                        Text(
                            "In use",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            // True black (R04): dark surfaces become pure black.
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = appearance.trueBlack) {
                        viewModel.setTrueBlack(!appearance.trueBlack)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("True black (AMOLED)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "In dark mode, backgrounds become pure black — " +
                            "kinder to AMOLED screens and batteries. " +
                            "It also turns the frosted-glass glow off: " +
                            "with nothing behind the glass, every screen " +
                            "— Library included — rests on plain black.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = appearance.trueBlack,
                    onCheckedChange = { viewModel.setTrueBlack(it) }
                )
            }
            // Make your own (R04 foundation): pick a colour, the app
            // builds a full readable theme from it. The full editor
            // arrives with the Customise round (R07); removal is here
            // from day one (removability law).
            Text(
                "Make your own — tap a colour and Xreader builds a theme from it.",
                style = MaterialTheme.typography.titleSmall
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(
                    0xFF7C5CD6L to "My violet", 0xFF2E7CF6L to "My blue",
                    0xFF12A594L to "My teal", 0xFF1FA36BL to "My green",
                    0xFFE0A800L to "My gold", 0xFFF76B15L to "My orange",
                    0xFFE5484DL to "My red", 0xFFD6409FL to "My pink"
                ).forEach { (seed, name) ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(seed.toInt()))
                            .clickable { viewModel.addCustomScheme(seed, name) }
                    )
                }
            }
            appearance.customSchemes.forEach { spec ->
                val generated = CustomSchemeGenerator.generate(spec)
                val colors = generated.colors(darkPreview)
                val selected = appearance.colourScheme == CUSTOM_SCHEME_PREFIX + spec.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) colors.primary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            viewModel.setColourScheme(CUSTOM_SCHEME_PREFIX + spec.id)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.primary)
                    )
                    Text(
                        spec.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.removeCustomScheme(spec.id) }) {
                        Text("Remove")
                    }
                }
            }
        }

        CustomiseSection("Glass & surfaces") {
            GlassStyle.entries.forEach { style ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = appearance.glassStyle == style,
                            role = Role.RadioButton,
                            onClick = { viewModel.setGlassStyle(style) }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = appearance.glassStyle == style,
                        onClick = { viewModel.setGlassStyle(style) }
                    )
                    Column {
                        Text(
                            when (style) {
                                GlassStyle.FROSTED -> "Frosted glass"
                                GlassStyle.TINTED -> "Tinted glass"
                                GlassStyle.SOLID -> "Solid"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            when (style) {
                                GlassStyle.FROSTED -> "Bars and cards blur whatever scrolls behind them."
                                GlassStyle.TINTED -> "Translucent tinted surfaces, no blur. Kind to weaker devices."
                                GlassStyle.SOLID -> "Fully opaque surfaces. Nothing shows through."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            val context = LocalContext.current
            val totalRamMb = remember(context) {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                val info = android.app.ActivityManager.MemoryInfo()
                manager.getMemoryInfo(info)
                info.totalMem / (1024L * 1024L)
            }
            app.xreader.data.prefs.AppearanceValidation.glassFallbackNote(
                appearance.glassStyle, android.os.Build.VERSION.SDK_INT, totalRamMb
            )?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "These three sliders restyle the tinted and solid surfaces, live.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CustomiseSlider(
                label = "Opacity",
                value = appearance.glassOpacity,
                range = app.xreader.data.prefs.AppearanceValidation.GLASS_OPACITY_RANGE,
                onChange = viewModel::setGlassOpacity
            )
            CustomiseSlider(
                label = "Corner roundness",
                value = appearance.glassRadiusScale,
                range = app.xreader.data.prefs.AppearanceValidation.RADIUS_SCALE_RANGE,
                onChange = viewModel::setGlassRadiusScale
            )
            CustomiseSlider(
                label = "Shadow depth",
                value = appearance.glassElevationScale,
                range = app.xreader.data.prefs.AppearanceValidation.ELEVATION_SCALE_RANGE,
                onChange = viewModel::setGlassElevationScale
            )
        }

        CustomiseSection("Library layout") {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = library.layout == LibraryLayout.GRID,
                    onClick = { viewModel.setLayout(LibraryLayout.GRID) },
                    label = { Text("Grid") }
                )
                FilterChip(
                    selected = library.layout == LibraryLayout.COMFORTABLE_LIST,
                    onClick = { viewModel.setLayout(LibraryLayout.COMFORTABLE_LIST) },
                    label = { Text("Comfortable list") }
                )
                FilterChip(
                    selected = library.layout == LibraryLayout.COMPACT_LIST,
                    onClick = { viewModel.setLayout(LibraryLayout.COMPACT_LIST) },
                    label = { Text("Compact list") }
                )
            }
            if (library.layout == LibraryLayout.GRID) {
                Text("Columns across", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(2, 3, 4).forEach { columns ->
                        FilterChip(
                            selected = library.gridColumns == columns,
                            onClick = { viewModel.setGridColumns(columns) },
                            label = { Text("$columns") }
                        )
                    }
                }
            }
            CustomiseSwitch(
                label = "Continue Reading card",
                description = "The big card at the top of the library that takes you back into your book.",
                checked = library.showContinueReading,
                onChange = viewModel::setShowContinueReading
            )
            CustomiseSwitch(
                label = "Cover badges",
                description = "New-chapter counts and progress on book covers.",
                checked = library.showBadges,
                onChange = viewModel::setShowBadges
            )
        }

        CustomiseSection("Navigation") {
            Text("Bottom bar style", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = library.navBarStyle == NavBarStyle.FLOATING_PILL,
                    onClick = { viewModel.setNavBarStyle(NavBarStyle.FLOATING_PILL) },
                    label = { Text("Floating pill") }
                )
                FilterChip(
                    selected = library.navBarStyle == NavBarStyle.DOCKED_BAR,
                    onClick = { viewModel.setNavBarStyle(NavBarStyle.DOCKED_BAR) },
                    label = { Text("Docked bar") }
                )
            }
            Text("Tab labels", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = library.tabLabelStyle == TabLabelStyle.ALWAYS,
                    onClick = { viewModel.setTabLabelStyle(TabLabelStyle.ALWAYS) },
                    label = { Text("Always") }
                )
                FilterChip(
                    selected = library.tabLabelStyle == TabLabelStyle.SELECTED_ONLY,
                    onClick = { viewModel.setTabLabelStyle(TabLabelStyle.SELECTED_ONLY) },
                    label = { Text("Selected only") }
                )
                FilterChip(
                    selected = library.tabLabelStyle == TabLabelStyle.ICONS_ONLY,
                    onClick = { viewModel.setTabLabelStyle(TabLabelStyle.ICONS_ONLY) },
                    label = { Text("Icons only") }
                )
            }
            Text("Start screen", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = library.startScreen == StartScreen.LIBRARY,
                    onClick = { viewModel.setStartScreen(StartScreen.LIBRARY) },
                    label = { Text("Library") }
                )
                FilterChip(
                    selected = library.startScreen == StartScreen.CONTINUE_READING,
                    onClick = { viewModel.setStartScreen(StartScreen.CONTINUE_READING) },
                    label = { Text("Continue reading") }
                )
                FilterChip(
                    selected = library.startScreen == StartScreen.DOWNLOADS,
                    onClick = { viewModel.setStartScreen(StartScreen.DOWNLOADS) },
                    label = { Text("Downloads") }
                )
            }
        }

        CustomiseSection("Reader") {
            Text(
                "Page themes — the preview is a real page in those colours.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ReaderPalettes) { palette ->
                    val effective = palette.maxContrast(reader.highContrastText)
                    val selected = reader.paletteName == palette.name
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            Modifier
                                .size(width = 76.dp, height = 96.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(effective.background)
                                .border(
                                    if (selected) 3.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    viewModel.updateReader { it.copy(paletteName = palette.name) }
                                }
                                .padding(7.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                "The gate opened at dawn.",
                                color = effective.text,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 3
                            )
                            Text(
                                "Chapter 12",
                                color = effective.muted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                        Text(
                            palette.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            CustomiseSwitch(
                label = "High contrast text",
                description = "Makes the words as bright (or dark) as the page allows.",
                checked = reader.highContrastText,
                onChange = { v -> viewModel.updateReader { it.copy(highContrastText = v) } }
            )
            CustomiseSwitch(
                label = "Auto-scroll / auto page-turn",
                description = "Pages move on their own while you read. Any touch pauses it.",
                checked = reader.autoScrollEnabled,
                onChange = { v -> viewModel.updateReader { it.copy(autoScrollEnabled = v) } }
            )
            Text("Tap zones", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = reader.tapZoneLayout == TapZoneLayout.STANDARD,
                    onClick = { viewModel.updateReader { it.copy(tapZoneLayout = TapZoneLayout.STANDARD) } },
                    label = { Text("Standard") }
                )
                FilterChip(
                    selected = reader.tapZoneLayout == TapZoneLayout.REVERSED,
                    onClick = { viewModel.updateReader { it.copy(tapZoneLayout = TapZoneLayout.REVERSED) } },
                    label = { Text("Reversed") }
                )
                FilterChip(
                    selected = reader.tapZoneLayout == TapZoneLayout.NINE_ZONE,
                    onClick = { viewModel.updateReader { it.copy(tapZoneLayout = TapZoneLayout.NINE_ZONE) } },
                    label = { Text("Nine-zone") }
                )
                FilterChip(
                    selected = reader.tapZoneLayout == TapZoneLayout.OFF,
                    onClick = { viewModel.updateReader { it.copy(tapZoneLayout = TapZoneLayout.OFF) } },
                    label = { Text("Off") }
                )
            }
            Text("Page order", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = reader.pagedDirection == PagedDirection.LTR,
                    onClick = { viewModel.updateReader { it.copy(pagedDirection = PagedDirection.LTR) } },
                    label = { Text("Left to right") }
                )
                FilterChip(
                    selected = reader.pagedDirection == PagedDirection.RTL,
                    onClick = { viewModel.updateReader { it.copy(pagedDirection = PagedDirection.RTL) } },
                    label = { Text("Right to left") }
                )
            }
        }

        // ── Your themes: the full make-your-own editor (R04/R07) ──
        CustomiseSection("Your themes") {
            Text(
                "Themes you made yourself, from one colour. Up to three.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (appearance.customSchemes.isEmpty()) {
                Text(
                    "None yet — pick a colour under Colour theme to create one.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            appearance.customSchemes.forEach { spec ->
                CustomThemeEditorRow(
                    spec = spec,
                    darkPreview = appearance.themeMode ==
                        app.xreader.data.prefs.ThemeMode.DARK,
                    onRename = { viewModel.renameCustomScheme(spec.id, it) },
                    onUpdate = { seed, end ->
                        viewModel.updateCustomScheme(spec.id, seed, end)
                    },
                    onRemove = { viewModel.removeCustomScheme(spec.id) }
                )
            }
            if (appearance.customSchemes.size >=
                app.xreader.data.prefs.AppearanceValidation.MAX_CUSTOM_SCHEMES
            ) {
                Text(
                    "That's the three-theme limit — remove one to make another.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── Book colours (R05) ───────────────────────────────────
        CustomiseSection("Book colours") {
            Text(
                "Each book's cover can lend its colours to the book page. " +
                    "The reading page itself never changes colour this way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = appearance.bookColours ==
                        app.xreader.data.prefs.BookColoursScope.BOOK_PAGE_ONLY,
                    onClick = {
                        viewModel.setBookColours(
                            app.xreader.data.prefs.BookColoursScope.BOOK_PAGE_ONLY
                        )
                    },
                    label = { Text("Book page only") }
                )
                FilterChip(
                    selected = appearance.bookColours ==
                        app.xreader.data.prefs.BookColoursScope.BOOK_PAGE_AND_READER,
                    onClick = {
                        viewModel.setBookColours(
                            app.xreader.data.prefs.BookColoursScope.BOOK_PAGE_AND_READER
                        )
                    },
                    label = { Text("Book page + reader controls") }
                )
                FilterChip(
                    selected = appearance.bookColours ==
                        app.xreader.data.prefs.BookColoursScope.OFF,
                    onClick = {
                        viewModel.setBookColours(app.xreader.data.prefs.BookColoursScope.OFF)
                    },
                    label = { Text("Off") }
                )
            }
            CustomiseSwitch(
                label = "Cover glow in Library",
                description = "A soft bloom of each cover's colour behind its card. " +
                    "Off unless you turn it on; light devices may skip it.",
                checked = appearance.coverGlow,
                onChange = viewModel::setCoverGlow
            )
        }

        // ── Performance (R06) ────────────────────────────────────
        CustomiseSection("Performance") {
            Text(
                "How hard Xreader pushes effects on this device. " +
                    "Automatic is right for almost everyone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = appearance.performanceMode ==
                        app.xreader.data.prefs.PerformanceMode.AUTOMATIC,
                    onClick = {
                        viewModel.setPerformanceMode(
                            app.xreader.data.prefs.PerformanceMode.AUTOMATIC
                        )
                    },
                    label = { Text("Automatic") }
                )
                FilterChip(
                    selected = appearance.performanceMode ==
                        app.xreader.data.prefs.PerformanceMode.SMOOTH,
                    onClick = {
                        viewModel.setPerformanceMode(app.xreader.data.prefs.PerformanceMode.SMOOTH)
                    },
                    label = { Text("Smooth") }
                )
                FilterChip(
                    selected = appearance.performanceMode ==
                        app.xreader.data.prefs.PerformanceMode.BEST_LOOKING,
                    onClick = {
                        viewModel.setPerformanceMode(
                            app.xreader.data.prefs.PerformanceMode.BEST_LOOKING
                        )
                    },
                    label = { Text("Best looking") }
                )
            }
            Text(
                when (appearance.performanceMode) {
                    app.xreader.data.prefs.PerformanceMode.AUTOMATIC ->
                        "The device decides: smaller devices get the lighter effects."
                    app.xreader.data.prefs.PerformanceMode.SMOOTH ->
                        "Static tints, no live blur, no particles — the gentlest ride."
                    app.xreader.data.prefs.PerformanceMode.BEST_LOOKING ->
                        "Every safe effect this device can do, including cover glow."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Sources & repairs (R11 preference) ───────────────────
        CustomiseSection("Sources & repairs") {
            // v1.0.6 (W2.4): the one master switch for the browser
            // view — default on; off restores static-only reading.
            CustomiseSwitch(
                label = "Browser view for sites that need JavaScript",
                description = "Some sites build their pages with JavaScript, so there's " +
                    "nothing to read until the page builds itself. With this on, " +
                    "Xreader reads those sites with its built-in browser view — you " +
                    "never see a browser; pages may just take a few seconds longer. " +
                    "Turn it off and those sites can't be added or read.",
                checked = state.browserViewEnabled,
                onChange = { viewModel.setBrowserViewEnabled(it) }
            )
            Text(
                "When a book exists on several sources, “Best source” picks by:",
                style = MaterialTheme.typography.bodyMedium
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.bestSourcePriority ==
                        app.xreader.data.extension.BestSourcePriority.DOWNLOADED_FIRST,
                    onClick = {
                        viewModel.setBestSourcePriority(
                            app.xreader.data.extension.BestSourcePriority.DOWNLOADED_FIRST
                        )
                    },
                    label = { Text("Already downloaded") }
                )
                FilterChip(
                    selected = state.bestSourcePriority ==
                        app.xreader.data.extension.BestSourcePriority.MOST_CHAPTERS,
                    onClick = {
                        viewModel.setBestSourcePriority(
                            app.xreader.data.extension.BestSourcePriority.MOST_CHAPTERS
                        )
                    },
                    label = { Text("Most chapters") }
                )
                FilterChip(
                    selected = state.bestSourcePriority ==
                        app.xreader.data.extension.BestSourcePriority.NEWEST_CHAPTER,
                    onClick = {
                        viewModel.setBestSourcePriority(
                            app.xreader.data.extension.BestSourcePriority.NEWEST_CHAPTER
                        )
                    },
                    label = { Text("Newest chapter") }
                )
                FilterChip(
                    selected = state.bestSourcePriority ==
                        app.xreader.data.extension.BestSourcePriority.FASTEST,
                    onClick = {
                        viewModel.setBestSourcePriority(
                            app.xreader.data.extension.BestSourcePriority.FASTEST
                        )
                    },
                    label = { Text("Fastest to load") }
                )
            }
            Text(
                "It never switches a book in your Library on its own — " +
                    "you always see the match first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Downloads (v1.0.4, D10/D13) ─────────────────────────
        // The user's ruling: ALL download switches live together in
        // Settings. The auto-download switch came from the Library,
        // the Wi-Fi-only switch from the Downloads screen; both
        // keep their full labels and their exact prefs.
        CustomiseSection("Downloads") {
            CustomiseSwitch(
                label = "Auto-download new chapters for tracked books (Wi-Fi)",
                description = "Books you track fetch their new chapters on their own, " +
                    "so they're ready to read offline.",
                checked = state.library.autoDownloadTracked,
                onChange = viewModel::setAutoDownloadTracked
            )
            CustomiseSwitch(
                label = "Wi-Fi only downloads",
                description = "Pause the queue when on mobile data",
                checked = state.downloads.wifiOnlyPrefetch,
                onChange = viewModel::setWifiOnlyPrefetch
            )
        }

        // ── Storage (R07/G16) ────────────────────────────────────
        CustomiseSection("Storage") {
            val storage by viewModel.storage.collectAsStateWithLifecycle()
            val sizes = storage
            if (sizes == null) {
                Text(
                    "Working out sizes…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                StorageRow(
                    "Downloaded chapters",
                    app.xreader.data.files.StorageMeter.formatBytes(sizes.downloadedTextBytes)
                )
                StorageRow(
                    "Book files",
                    app.xreader.data.files.StorageMeter.formatBytes(sizes.bookFilesBytes)
                )
                StorageRow(
                    "Cover cache (safe to clear)",
                    app.xreader.data.files.StorageMeter.formatBytes(sizes.coverCacheBytes)
                )
                StorageRow(
                    "Temporary files (safe to clear)",
                    app.xreader.data.files.StorageMeter.formatBytes(sizes.tempBytes)
                )
                StorageRow(
                    "Other app data",
                    app.xreader.data.files.StorageMeter.formatBytes(sizes.otherBytes)
                )
                StorageRow(
                    "Total",
                    app.xreader.data.files.StorageMeter.formatBytes(sizes.totalBytes)
                )
                var confirmClear by remember { mutableStateOf(false) }
                TextButton(onClick = { confirmClear = true }) {
                    Text("Clear caches")
                }
                Text(
                    "Clearing caches removes covers and half-finished downloads only. " +
                        "Books, downloaded chapters and your place in them stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (confirmClear) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmClear = false },
                        title = { Text("Clear caches?") },
                        text = {
                            Text(
                                "Cover images and temporary download files will be removed " +
                                    "and fetched again when needed. Nothing you are reading " +
                                    "is deleted."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmClear = false
                                viewModel.clearCaches()
                            }) { Text("Clear caches") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
                        }
                    )
                }
            }
        }

        // ── Accessibility & motion (R07/R29) ─────────────────────
        CustomiseSection("Accessibility & motion") {
            Text("App text size", style = MaterialTheme.typography.bodyLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = { viewModel.setAppTextSize(appearance.appTextSizePercent - 5) },
                    enabled = appearance.appTextSizePercent > 80
                ) { Text("Smaller") }
                Text(
                    "${appearance.appTextSizePercent}%",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(
                    onClick = { viewModel.setAppTextSize(appearance.appTextSizePercent + 5) },
                    enabled = appearance.appTextSizePercent < 130
                ) { Text("Bigger") }
            }
            Text(
                "Sizes the app's own words. The reader's text size is separate, in the reader.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CustomiseSwitch(
                label = "Reduce motion",
                description = "Calms the app's animations down to simple fades.",
                checked = appearance.reduceMotion,
                onChange = viewModel::setReduceMotion
            )
            CustomiseSwitch(
                label = "Move Me card with phone",
                description = "Tilting your phone tilts the card on the Me tab. Off by default.",
                checked = appearance.meCardMotionTilt,
                onChange = viewModel::setMeCardMotionTilt
            )
        }

        // ── Advanced (R07): reset lives here, behind a confirm ───
        CustomiseSection("Advanced") {
            var confirmReset by remember { mutableStateOf(false) }
            TextButton(onClick = { confirmReset = true }) {
                Text("Reset customisation")
            }
            Text(
                "Colours, glass, layout and reader defaults go back to how they started. " +
                    "Your books, sources, shelves, tags and progress are not touched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (confirmReset) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmReset = false },
                    title = { Text("Reset customisation?") },
                    text = {
                        Text(
                            "Only how Xreader looks and feels. Nothing you are reading, " +
                                "and nothing you organised, is removed."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmReset = false
                            viewModel.resetCustomisation()
                        }) { Text("Reset") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}

@Composable
private fun StorageRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The full editor row for one make-your-own theme (R04/R07):
 * preview in its real generated colours, rename, re-pick seed and
 * end colours, the honest "we deepened it" note, and Remove. */
@Composable
private fun CustomThemeEditorRow(
    spec: CustomSchemeSpec,
    darkPreview: Boolean,
    onRename: (String) -> Unit,
    onUpdate: (seedArgb: Long, endArgb: Long?) -> Unit,
    onRemove: () -> Unit
) {
    val generated = CustomSchemeGenerator.generate(spec)
    val colors = generated.colors(darkPreview)
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(spec.name) { mutableStateOf(spec.name) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.primary)
            )
            if (editingName) {
                androidx.compose.material3.OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
                TextButton(onClick = {
                    onRename(nameDraft)
                    editingName = false
                }) { Text("Save") }
            } else {
                Text(
                    spec.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
                TextButton(onClick = { editingName = true }) { Text("Rename") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
        Text(
            "Sample words in your theme",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        CustomSchemeGenerator.adjustmentNote(spec)?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "Main colour",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurface
        )
        SeedSwatchRow(
            selected = spec.seedArgb,
            onPick = { seed -> if (seed != null) onUpdate(seed, spec.endArgb) }
        )
        Text(
            "Second colour (optional)",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurface
        )
        SeedSwatchRow(
            selected = spec.endArgb,
            onPick = { onUpdate(spec.seedArgb, it) },
            allowNone = true
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SeedSwatchRow(
    selected: Long?,
    onPick: (Long?) -> Unit,
    allowNone: Boolean = false
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (allowNone) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(50))
                    .border(
                        if (selected == null) 2.dp else 1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(50)
                    )
                    .clickable { onPick(null) },
                contentAlignment = Alignment.Center
            ) {
                Text("–", style = MaterialTheme.typography.labelSmall)
            }
        }
        listOf(
            0xFF7C5CD6L, 0xFF2E7CF6L, 0xFF12A594L, 0xFF1FA36BL,
            0xFFE0A800L, 0xFFF76B15L, 0xFFE5484DL, 0xFFD6409FL,
            0xFFFFD60AL, 0xFF8AC926L, 0xFF6A4C93L, 0xFF1982C4L
        ).forEach { seed ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(seed.toInt()))
                    .border(
                        if (selected == seed) 2.dp else 0.dp,
                        MaterialTheme.colorScheme.onSurface,
                        RoundedCornerShape(50)
                    )
                    .clickable { onPick(seed) }
            )
        }
    }
}

@Composable
private fun CustomiseSection(title: String, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CustomiseSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CustomiseSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Text(
            "$label — ${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
