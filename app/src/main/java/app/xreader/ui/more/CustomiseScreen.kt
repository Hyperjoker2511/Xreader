package app.xreader.ui.more

import kotlin.math.roundToInt
import android.content.Context
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.xreader.core.motion.stackEnter
import app.xreader.core.motion.stackExit
import app.xreader.core.motion.stackPopEnter
import app.xreader.core.motion.stackPopExit
import app.xreader.data.library.CoverSize
import app.xreader.data.library.LibraryLayout
import app.xreader.data.library.LibraryState
import app.xreader.data.library.NavBarStyle
import app.xreader.data.library.StartScreen
import app.xreader.data.library.TabLabelStyle
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.xreader.data.prefs.AppearanceState
import app.xreader.data.prefs.AppearanceValidation
import app.xreader.data.prefs.CUSTOM_SCHEME_PREFIX
import app.xreader.data.prefs.CustomSchemeSpec
import app.xreader.data.prefs.GlassStyle
import app.xreader.ui.theme.CustomSchemeGenerator
import app.xreader.di.AppContainer
import app.xreader.domain.reader.ImageReaderMode
import app.xreader.domain.reader.PagedDirection
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.TapZoneLayout
import app.xreader.ui.common.GlassCard
import app.xreader.ui.common.NocturneSwitch
import app.xreader.ui.common.ProgressPillBar
import app.xreader.ui.common.SectionHeader
import app.xreader.ui.common.StatusPill
import app.xreader.ui.theme.LocalAppSize
import app.xreader.ui.theme.LocalDockClearance
import app.xreader.ui.theme.LocalUiMetrics
import app.xreader.ui.theme.effectiveBottomPadding
import app.xreader.ui.theme.ReaderPalettes
import app.xreader.ui.theme.UiTier
import app.xreader.ui.theme.maxContrast
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uy.kohesive.injekt.api.get

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
        val browserViewEnabled: Boolean = true,
        /** Manga programme (M1): the ONE content preference —
         * which source ratings are listed (Mihon model). */
        val contentVisibility: app.xreader.data.extension.ContentVisibility =
            app.xreader.data.extension.ContentVisibility.DEFAULT,
        /** Keiyoushi player (K6): the player's kill switch —
         * off means no Keiyoushi extension loads at all. */
        val keiyoushiPlayerEnabled: Boolean = true,
        /** v1.7 Patch 1 (item 5): DNS-over-HTTPS — source
         * lookups through Cloudflare (1.1.1.1) instead of
         * the device's resolver. Default OFF. */
        val dohEnabled: Boolean = false,
        /** v1.7 OE-4 (owner's CBZ ruling): where a landed
         * local CBZ/CBR opens — true = the manga reader
         * (Komikku, the default), false = the book reader.
         * One global choice; no per-book override. */
        val cbzOpensInKomikku: Boolean = true,
        /** Code 120: the whole-app design state (the Design
         * door's page + the Material 3 knobs). A separate prefs
         * owner — Nocturne's fields above never read it. */
        val design: app.xreader.data.prefs.DesignState =
            app.xreader.data.prefs.DesignState()
    )

    /** FIX-18: the Komikku manga reader's GLOBAL defaults,
     * resolved from Injekt as the donor's settings do. */
    val komikkuGlobalPrefs: app.xreader.komikku.setting.ReaderPreferences
        get() = uy.kohesive.injekt.Injekt.get()

    val state: StateFlow<UiState> = combine(
        container.appearancePrefs.state,
        container.libraryPrefs.state,
        container.readerPrefs.config,
        container.sourcePrefs.bestSourcePriority,
        container.downloadPrefs.settings,
        container.sourcePrefs.browserViewEnabled,
        container.sourcePrefs.contentVisibility,
        container.sourcePrefs.keiyoushiPlayerEnabled,
        container.sourcePrefs.dohEnabled,
        container.readerPrefs.cbzOpensInKomikku,
        container.designPrefs.state
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
            browserViewEnabled = values[5] as Boolean,
            contentVisibility = values[6]
                as app.xreader.data.extension.ContentVisibility,
            keiyoushiPlayerEnabled = values[7] as Boolean,
            dohEnabled = values[8] as Boolean,
            cbzOpensInKomikku = values[9] as Boolean,
            design = values[10] as app.xreader.data.prefs.DesignState
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Storage sizes load off the main thread and refresh after a
     * cache clear; kept out of [state] so a slow disk walk never
     * stalls the settings state. */
    val storage =
        kotlinx.coroutines.flow.MutableStateFlow<app.xreader.data.files.StorageBreakdown?>(null)

    /** v1.5.2 fix (FIX-6c, §12.3): Clean cache's plain-words result —
     * failures surface HERE, on the storage screen, never silently. */
    val cacheClearNote =
        kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Patch 12: the listening cache's size (spec §C.4) — it
     * lives in cacheDir, outside the filesDir the StorageMeter
     * walks, so it is measured directly through the cache
     * object. Null while measuring. */
    val voiceCacheBytes =
        kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** Patch 12: the listening cache's ONLY eraser besides its
     * own LRU — the generic Clear caches never touches it
     * (AppContainer.cleanCache leaves it alone by design). */
    fun clearVoiceCache() {
        viewModelScope.launch(container.ioDispatcher) {
            runCatching { container.ttsAudioCache.clear() }
            cacheClearNote.value = "Listening cache cleared."
            refreshStorage()
        }
    }

    /** v1.5.6 (§3.5 R3): Clear Legacy Data's result line. The data
     * op is MANUAL ONLY — this tap is its only caller. */
    val legacyClearNote =
        kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun clearLegacyData() {
        viewModelScope.launch(container.ioDispatcher) {
            val cleared = runCatching {
                container.localBookDeletion.clearLegacyData()
            }.getOrNull()
            legacyClearNote.value = when {
                cleared == null -> "Legacy data couldn't be cleared."
                cleared == 0 -> "No legacy data to clear."
                cleared == 1 -> "Legacy data cleared for 1 book."
                else -> "Legacy data cleared for $cleared books."
            }
            refreshStorage()
        }
    }

    init {
        refreshStorage()
    }

    fun refreshStorage() {
        viewModelScope.launch(container.ioDispatcher) {
            // Patch 12: the listening cache is measured on the
            // same pass (independently — a meter failure must not
            // hide it).
            voiceCacheBytes.value = runCatching {
                container.ttsAudioCache.sizeBytes()
            }.getOrNull()
            storage.value = runCatching {
                val meter = app.xreader.data.files.StorageMeter
                    .measure(container.bookStorage.filesDir)
                // v1.5.2 fix (Track B, §12.9/§12.11): the meter's
                // content/ total mixes USER downloads with
                // AUTOMATIC read cache — the exhibited 29.2 MB
                // showed as "Downloaded chapters". Split it by the
                // chapter rows' origin (the ONE predicate's own
                // facts): USER bodies are downloads; AUTOMATIC
                // bodies — and content no chapter row owns
                // (partials/orphans, temp-class) — are cache.
                var userBytes = 0L
                var autoBytes = 0L
                for (book in container.database.bookDao().allBooks()) {
                    for (chapter in container.database.chapterDao().forBook(book.id)) {
                        val path = chapter.contentPath ?: continue
                        val bytes = runCatching {
                            container.bookStorage.resolve(path).length()
                        }.getOrDefault(0L)
                        when (chapter.bodyOrigin) {
                            app.xreader.data.download.BodyOrigin152.USER ->
                                userBytes += bytes
                            app.xreader.data.download.BodyOrigin152.AUTOMATIC ->
                                autoBytes += bytes
                        }
                    }
                }
                val unowned = (meter.downloadedTextBytes - userBytes - autoBytes)
                    .coerceAtLeast(0L)
                meter.copy(
                    downloadedTextBytes = userBytes,
                    cacheBodyBytes = autoBytes + unowned
                )
            }.getOrNull()
        }
    }

    /** Clear ONLY rebuildable cache (covers + temp). Downloaded
     * chapters, book files, progress and settings are never touched
     * here (R07/G16 — different words, different confirmations). */
    fun clearCaches() {
        viewModelScope.launch(container.ioDispatcher) {
            // v1.5.2 fix (FIX-6c): THE shared Clean cache (§12.3) —
            // same implementation as the Downloads screen: read
            // chapter bodies + image cache + temp clear; stored
            // covers and lore-art (per-book assets) survive.
            val result = container.cleanCache()
            cacheClearNote.value = if (result.failures.isEmpty()) {
                "Cache cleared."
            } else {
                "Some cache couldn't be cleared: " +
                    result.failures.joinToString("; ")
            }
            refreshStorage()
        }
    }

    fun setColourScheme(name: String) {
        viewModelScope.launch { container.appearancePrefs.setColourScheme(name) }
    }

    // ── Code 120: the design switch + the Material 3 knobs ──
    // Each writes DesignPrefs only; Nocturne's state is never
    // touched by a Material 3 knob, nor the reverse.

    fun setDesign(value: app.xreader.data.prefs.AppDesign) {
        viewModelScope.launch { container.designPrefs.setDesign(value) }
    }

    fun setM3ColourSource(value: app.xreader.data.prefs.M3ColourSource) {
        viewModelScope.launch { container.designPrefs.setColourSource(value) }
    }

    fun setM3Seed(argb: Long) {
        viewModelScope.launch { container.designPrefs.setSeed(argb) }
    }

    fun setM3ColourStyle(value: app.xreader.data.prefs.M3ColourStyle) {
        viewModelScope.launch { container.designPrefs.setColourStyle(value) }
    }

    fun setM3ThemeMode(value: app.xreader.data.prefs.M3ThemeMode) {
        viewModelScope.launch { container.designPrefs.setThemeMode(value) }
    }

    fun setM3BookPageCoverColour(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setBookPageCoverColour(value) }
    }

    fun setM3TabLabels(value: app.xreader.data.prefs.M3TabLabels) {
        viewModelScope.launch { container.designPrefs.setTabLabels(value) }
    }

    fun setM3NavForm(value: app.xreader.data.prefs.M3NavForm) {
        viewModelScope.launch { container.designPrefs.setNavForm(value) }
    }

    fun setM3LibraryContinueRail(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setLibraryContinueRail(value) }
    }

    fun setM3LibraryProgressOnCovers(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setLibraryProgressOnCovers(value) }
    }

    fun setM3LibraryFilterRow(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setLibraryFilterRow(value) }
    }

    fun setM3BookHeader(value: app.xreader.data.prefs.M3BookHeader) {
        viewModelScope.launch { container.designPrefs.setBookHeader(value) }
    }

    fun setM3BookAction(value: app.xreader.data.prefs.M3BookAction) {
        viewModelScope.launch { container.designPrefs.setBookAction(value) }
    }

    fun setM3HistoryGrouping(value: app.xreader.data.prefs.M3HistoryGrouping) {
        viewModelScope.launch { container.designPrefs.setHistoryGrouping(value) }
    }

    fun setM3HistoryDensity(value: app.xreader.data.prefs.M3DensityChoice) {
        viewModelScope.launch { container.designPrefs.setHistoryDensity(value) }
    }

    fun setM3DownloadsSort(value: app.xreader.data.prefs.M3DownloadsSort) {
        viewModelScope.launch { container.designPrefs.setDownloadsSort(value) }
    }

    fun setM3DownloadsChapterSize(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setDownloadsChapterSize(value) }
    }

    // Code 121 W8: the reader-chrome knob's Customise door is
    // RETIRED (§8 Q3 — the floating toolbar is the one M3 form;
    // DesignPrefs keeps the stored value, unread by M3 surfaces).

    fun setM3LibraryShelfDensity(value: app.xreader.data.prefs.M3DensityChoice) {
        viewModelScope.launch { container.designPrefs.setLibraryShelfDensity(value) }
    }

    fun setM3LibraryDefaultTab(value: app.xreader.data.prefs.M3LibraryTab) {
        viewModelScope.launch { container.designPrefs.setLibraryDefaultTab(value) }
    }

    fun setM3ReaderPositionLine(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setReaderPositionLine(value) }
    }

    fun setM3MangaPageChip(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setMangaPageChip(value) }
    }

    fun setM3SelectionColourRow(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setSelectionColourRow(value) }
    }

    fun setM3ContentsTab(value: app.xreader.data.prefs.M3ContentsTab) {
        viewModelScope.launch { container.designPrefs.setContentsTab(value) }
    }

    fun setM3ExploreSide(value: app.xreader.data.prefs.M3ExploreSide) {
        viewModelScope.launch { container.designPrefs.setExploreSide(value) }
    }

    fun setM3ExploreGrouping(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setExploreGrouping(value) }
    }

    fun setM3ExploreDensity(value: app.xreader.data.prefs.M3DensityChoice) {
        viewModelScope.launch { container.designPrefs.setExploreDensity(value) }
    }

    fun setM3ExploreStoreView(value: app.xreader.data.prefs.M3ListView) {
        viewModelScope.launch { container.designPrefs.setExploreStoreView(value) }
    }

    fun setM3ExploreRepoSort(value: app.xreader.data.prefs.M3RepoSort) {
        viewModelScope.launch { container.designPrefs.setExploreRepoSort(value) }
    }

    fun setM3ProfileHeaderCard(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setProfileHeaderCard(value) }
    }

    fun setM3ProfileDoorsList(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setProfileDoorsList(value) }
    }

    fun setM3VocabSort(value: app.xreader.data.prefs.M3VocabSort) {
        viewModelScope.launch { container.designPrefs.setVocabSort(value) }
    }

    fun setM3LoreView(value: app.xreader.data.prefs.M3ListView) {
        viewModelScope.launch { container.designPrefs.setLoreView(value) }
    }

    fun setM3GalleryColumns(value: app.xreader.data.prefs.M3GalleryColumns) {
        viewModelScope.launch { container.designPrefs.setGalleryColumns(value) }
    }

    fun setM3MotionChoice(value: app.xreader.data.prefs.M3MotionChoice) {
        viewModelScope.launch { container.designPrefs.setMotionChoice(value) }
    }

    fun setM3AmoledDark(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setM3AmoledDark(value) }
    }

    fun setLaunchIntro(value: Boolean) {
        viewModelScope.launch { container.designPrefs.setLaunchIntro(value) }
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

    /** v1.6.2: the App size — one number for the whole screen
     * (replaces the retired R07 text-size stepper). */
    fun setAppSize(value: Float) {
        viewModelScope.launch { container.appearancePrefs.setAppSize(value) }
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

    /** Manga programme (M1): the content preference. */
    fun setContentVisibility(
        value: app.xreader.data.extension.ContentVisibility
    ) {
        viewModelScope.launch { container.sourcePrefs.setContentVisibility(value) }
    }

    /** Keiyoushi player (K6): the kill switch. The DataStore
     * write is the whole action here — the container's
     * collector pushes the change into the player manager,
     * which empties (or refills) the live registry. */
    fun setKeiyoushiPlayerEnabled(value: Boolean) {
        viewModelScope.launch {
            container.sourcePrefs.setKeiyoushiPlayerEnabled(value)
        }
    }

    /** v1.7 Patch 1 (item 5): the DNS-over-HTTPS toggle. */
    fun setDohEnabled(value: Boolean) {
        viewModelScope.launch { container.sourcePrefs.setDohEnabled(value) }
    }

    /** v1.7 OE-4: the "CBZ opens in" choice (ReaderPrefs). */
    fun setCbzOpensInKomikku(value: Boolean) {
        viewModelScope.launch {
            container.readerPrefs.setCbzOpensInKomikku(value)
        }
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

    /** v1.3.1 (item 58): the auto-clean cap preset (MB; 0 = Off). */
    fun setAutoCleanCapMb(value: Int) {
        viewModelScope.launch { container.downloadPrefs.setAutoCleanCapMb(value) }
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

    /** v1.0.7 (AnymeX port): recook a saved scheme in a flavour. */
    fun setCustomSchemeFlavour(
        id: String,
        flavour: app.xreader.data.prefs.SchemeFlavour
    ) {
        viewModelScope.launch {
            val current = state.value.appearance.customSchemes
            container.appearancePrefs.setCustomSchemes(
                current.map {
                    if (it.id == id) it.copy(flavour = flavour) else it
                }
            )
        }
    }

    fun setBloom(value: Boolean) {
        viewModelScope.launch { container.appearancePrefs.setBloom(value) }
    }

    fun setGrain(value: Boolean) {
        viewModelScope.launch { container.appearancePrefs.setGrain(value) }
    }

    fun setGrainIntensity(value: Float) {
        viewModelScope.launch {
            container.appearancePrefs.setGrainIntensity(value)
        }
    }

    /** v1.0.7 Liquid background: copy the picked picture into
     * filesDir/backgrounds (downscaled to at most 1080 px wide —
     * the 2 GB tablet rule), then point the preference at it.
     * A failed copy changes nothing and says nothing. */
    fun setBackgroundImage(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch(container.ioDispatcher) {
            val name = runCatching {
                val dir = java.io.File(
                    container.bookStorage.filesDir, "backgrounds"
                ).apply { mkdirs() }
                val target = java.io.File(dir, "background.jpg")
                val decoded = context.applicationContext.contentResolver
                    .openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    } ?: return@runCatching null
                val scale = minOf(
                    1f, 1080f / decoded.width.coerceAtLeast(1)
                )
                val out = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    decoded
                }
                target.outputStream().use { stream ->
                    out.compress(
                        android.graphics.Bitmap.CompressFormat.JPEG,
                        85, stream
                    )
                }
                target.name
            }.getOrNull()
            if (name != null) {
                container.appearancePrefs.setBackgroundImage(name)
            }
        }
    }

    fun clearBackgroundImage() {
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                java.io.File(
                    container.bookStorage.filesDir,
                    "backgrounds/background.jpg"
                ).delete()
            }
            container.appearancePrefs.setBackgroundImage(null)
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

    fun clearGridColumns() {
        viewModelScope.launch { container.libraryPrefs.clearGridColumns() }
    }

    /** v1.6.2: the Library's novel cover size. */
    fun setCoverSize(value: app.xreader.data.library.CoverSize) {
        viewModelScope.launch { container.libraryPrefs.setCoverSize(value) }
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

    /** Nav-bar styles (v1.6.2): the dock indicator's style. */
    fun setNavIndicatorStyle(value: app.xreader.data.library.NavIndicatorStyle) {
        viewModelScope.launch {
            container.libraryPrefs.setNavIndicatorStyle(value)
        }
    }

    /** Nav-bar styles (v1.6.2): back to the theme's colour. */
    fun setNavIndicatorColourTheme() {
        viewModelScope.launch {
            container.libraryPrefs.setNavIndicatorColourMode(
                app.xreader.data.library.NavIndicatorColourMode.THEME
            )
        }
    }

    /** Nav-bar styles (v1.6.2): an exact colour from the wheel
     * (the prefs write flips the mode to CUSTOM with it). */
    fun setNavIndicatorColour(argb: Long) {
        viewModelScope.launch {
            container.libraryPrefs.setNavIndicatorColour(argb)
        }
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
    // Code 121 (R30): under Material 3, Customise is that
    // design's own settings-list page (DesignSection.kt's
    // M3CustomisePage) — design-scoped groups only, no
    // Nocturne doors inside M3 (trace T3). The Nocturne hub
    // below is untouched and renders exactly as shipped when
    // Nocturne is the active design.
    if (state.design.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    ) {
        M3CustomisePage(
            state = state,
            viewModel = viewModel,
            onBack = onBack
        )
        return
    }
    // v1.0.7: the liquid-background picture picker.
    val screenContext = LocalContext.current
    val backgroundPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.setBackgroundImage(screenContext, uri)
    }
    // Orion remake (v1.6.2, SPEC-CUSTOMISE-ORION-2026-11-16):
    // the Patch 7 accordion is gone — Customise is a HUB of
    // doors (the Me dashboard's card language) with one PAGE
    // per door, inside this same destination. [page] is the
    // open page's index into the door order below, or null
    // for the hub. Composition state (rememberSaveable), so
    // a rotation keeps the reader's place.
    var page by rememberSaveable { mutableStateOf<Int?>(null) }
    // System back from a page returns to the hub first; from
    // the hub it leaves Customise exactly as before.
    BackHandler(enabled = page != null) { page = null }
    val hubScroll = rememberScrollState()
    val pageScroll = rememberScrollState()
    // A page always opens at its top; the hub keeps its own
    // scroll place behind it.
    LaunchedEffect(page) {
        if (page != null) pageScroll.scrollTo(0)
    }
    // v1.1.0 (§5.16): the live preview tile's names — a mini
    // book tile + text sample drawn in the CURRENT Material
    // roles inside a real GlassCard (on the hub, below), so
    // it mutates with the scheme and glass controls the
    // moment they change.
    val schemeDisplayName = if (
        appearance.colourScheme.startsWith(CUSTOM_SCHEME_PREFIX)
    ) {
        appearance.customSchemes.firstOrNull {
            CUSTOM_SCHEME_PREFIX + it.id == appearance.colourScheme
        }?.name ?: "Your theme"
    } else {
        appearance.colourScheme
    }
    val glassDisplayName = when (appearance.glassStyle) {
        GlassStyle.LIQUID -> "Liquid glass"
        GlassStyle.FROSTED -> "Frosted glass"
        GlassStyle.TINTED -> "Tinted glass"
        GlassStyle.SOLID -> "Solid"
    }
    val glassShortName = when (appearance.glassStyle) {
        GlassStyle.LIQUID -> "Liquid"
        GlassStyle.FROSTED -> "Frosted"
        GlassStyle.TINTED -> "Tinted"
        GlassStyle.SOLID -> "Solid"
    }
    val layoutStatus = when (library.layout) {
        LibraryLayout.GRID -> "Grid"
        LibraryLayout.COMFORTABLE_LIST -> "Comfortable list"
        LibraryLayout.COMPACT_LIST -> "Compact list"
        LibraryLayout.BENTO -> "Bento"
    }
    val navigationStatus =
        "${navIndicatorStyleName(library.navIndicatorStyle)} · " +
            when (library.navIndicatorColourMode) {
                app.xreader.data.library.NavIndicatorColourMode.THEME -> "Theme"
                app.xreader.data.library.NavIndicatorColourMode.CUSTOM -> "Custom"
            }
    val bookColoursStatus = when (appearance.bookColours) {
        app.xreader.data.prefs.BookColoursScope.BOOK_PAGE_ONLY ->
            "Book page only"
        app.xreader.data.prefs.BookColoursScope.BOOK_PAGE_AND_READER ->
            "Book page + reader"
        app.xreader.data.prefs.BookColoursScope.OFF -> "Off"
    }
    val performanceStatus = when (appearance.performanceMode) {
        app.xreader.data.prefs.PerformanceMode.AUTOMATIC -> "Automatic"
        app.xreader.data.prefs.PerformanceMode.SMOOTH -> "Smooth"
        app.xreader.data.prefs.PerformanceMode.BEST_LOOKING -> "Best looking"
    }
    // The fifteen doors, in the order the sections have
    // always stood — doors[i] opens pageContents[i]. A door's
    // status line comes ONLY from state this body already
    // reads (the Orion dashboard's honest-absence rule):
    // where no live value reaches this screen — the Manga
    // reader's Komikku defaults, Storage's sizes, Advanced's
    // lone reset — the door carries no line.
    val doors = listOf(
        // Code 120 (design §2.11): the Design door stands at
        // the top — it holds the whole-app design switch and,
        // under Material 3, that design's knobs grouped by
        // their map sections.
        CustomiseDoor(
            icon = Icons.Filled.DesignServices,
            title = "Design",
            status = if (state.design.design ==
                app.xreader.data.prefs.AppDesign.MATERIAL3
            ) {
                "Material 3"
            } else {
                "Nocturne"
            }
        ),
        CustomiseDoor(
            icon = Icons.Filled.Palette,
            title = "Colour theme",
            status = "$schemeDisplayName · $glassShortName"
        ),
        CustomiseDoor(
            icon = Icons.Filled.FormatSize,
            title = "App size",
            status = "${appSizePresetName(appearance.appSize)} · " +
                AppearanceValidation.formatAppSize(appearance.appSize)
        ),
        CustomiseDoor(
            icon = Icons.Filled.AutoAwesome,
            title = "Glass & surfaces",
            status = glassDisplayName
        ),
        CustomiseDoor(
            icon = Icons.Filled.List,
            title = "Library layout",
            status = layoutStatus
        ),
        CustomiseDoor(
            icon = Icons.Filled.Explore,
            title = "Navigation",
            status = navigationStatus
        ),
        CustomiseDoor(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = "Reader",
            status = reader.paletteName
        ),
        CustomiseDoor(
            icon = Icons.Filled.AutoStories,
            title = "Manga reader",
            status = null
        ),
        CustomiseDoor(
            icon = Icons.Filled.Brush,
            title = "Your themes",
            status = if (appearance.customSchemes.isEmpty()) {
                "None yet"
            } else {
                "${appearance.customSchemes.size} saved"
            }
        ),
        CustomiseDoor(
            icon = Icons.Filled.Book,
            title = "Book colours",
            status = bookColoursStatus
        ),
        CustomiseDoor(
            icon = Icons.Filled.FastForward,
            title = "Performance",
            status = performanceStatus
        ),
        CustomiseDoor(
            icon = Icons.Filled.Build,
            title = "Sources & repairs",
            status = state.contentVisibility.label
        ),
        CustomiseDoor(
            icon = Icons.Filled.Download,
            title = "Downloads",
            status = if (library.autoDownloadTracked) {
                "Auto-download on"
            } else {
                "Auto-download off"
            }
        ),
        CustomiseDoor(
            icon = Icons.Filled.FolderOpen,
            title = "Storage",
            status = null
        ),
        CustomiseDoor(
            icon = Icons.Filled.DirectionsRun,
            title = "Accessibility & motion",
            status = if (appearance.reduceMotion) {
                "Reduce motion on"
            } else {
                "Reduce motion off"
            }
        ),
        CustomiseDoor(
            icon = Icons.Filled.Tune,
            title = "Advanced",
            status = null
        )
    )
    // Each door's page body: the section's existing content,
    // verbatim — the controls move, they are not rewritten.
    val pageContents = listOf<@Composable () -> Unit>(
        {
            // Code 120: the Design page (DesignSection.kt) —
            // the switch + the Material 3 knobs.
            DesignPageContent(state = state.design, viewModel = viewModel)
        },
        {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(scheme.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                            // v1.1.0 (§2.4/§5.16): Nocturne violet
                            // is the app default — badged in the
                            // picker. (The picker list itself comes
                            // from AppColourThemes, Nocturne first.)
                            if (scheme.name == "Nocturne violet") {
                                StatusPill(text = "App default", color = colors.primary)
                            }
                        }
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
            // v1.1.0 (§3.2): the pin's honest line — picking a
            // scheme here is the deliberate act that clears the
            // book page's preserved colours.
            Text(
                "Changing this also changes the book page's colours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                NocturneSwitch(
                    checked = appearance.trueBlack,
                    onCheckedChange = { viewModel.setTrueBlack(it) },
                    modifier = Modifier,
                    enabled = true
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
            // v1.0.7 (AnymeX port): the seed grid is AnymeX's 17
            // Material colours — one tap makes a theme from that
            // colour (the old 8 house chips retire into it; any
            // theme already made from them is untouched).
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                app.xreader.ui.theme.SeedPresets.forEach { (name, seed) ->
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(seed.toInt()))
                            .clickable { viewModel.addCustomScheme(seed, name) }
                    )
                }
            }
            if (appearance.customSchemes.size >=
                app.xreader.data.prefs.AppearanceValidation.MAX_CUSTOM_SCHEMES
            ) {
                Text(
                    "You've saved ${appearance.customSchemes.size} of your own " +
                        "themes — remove one to add more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            // Code 121 (§5.2 — the ONLY Nocturne-side change in
            // this build): "Pick any colour", beside the preset
            // schemes. It opens the S37 picker in Nocturne's
            // frame (ColourBar.kt's ColourPickerDialog); the
            // picked colour becomes a CustomSchemeSpec whose
            // seed is the colour, generated by the SAME
            // CustomSchemeGenerator the presets' custom themes
            // use — seed hue drives the grounds, the accent
            // family (primary/secondary/tertiary + the text
            // tokens the indicator, selection tint and glow
            // read) derives from it, and the AMOLED tiers are
            // untouched (true-black still applies on top, as
            // for every scheme). The result is saved as the
            // "Custom" entry beside the presets — re-pickable
            // and re-editable (a second pick updates the same
            // entry); presets and the default accent are
            // unchanged.
            var pickAnyColourOpen by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(16.dp)
                    )
                    .clickable { pickAnyColourOpen = true }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            androidx.compose.ui.graphics.Brush
                                .sweepGradient(
                                    listOf(
                                        Color(0xFFF44336),
                                        Color(0xFFFF9800),
                                        Color(0xFFFFEB3B),
                                        Color(0xFF4CAF50),
                                        Color(0xFF2196F3),
                                        Color(0xFF9C27B0),
                                        Color(0xFFF44336)
                                    )
                                )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                MaterialTheme.colorScheme.surface
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pick any colour",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Any colour at all — Xreader builds a " +
                            "Nocturne theme from it and saves it " +
                            "as “Custom”, beside the themes above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (pickAnyColourOpen) {
                val customEntry = appearance.customSchemes
                    .firstOrNull { it.name == "Custom" }
                app.xreader.ui.common.ColourPickerDialog(
                    title = "Pick any colour",
                    initialArgb = customEntry?.seedArgb
                        ?: appearance.customSchemes
                            .firstOrNull {
                                appearance.colourScheme ==
                                    CUSTOM_SCHEME_PREFIX + it.id
                            }?.seedArgb
                        ?: 0xFF8B5CF6L,
                    recentColours = appearance.customSchemes
                        .map { it.seedArgb },
                    onDismiss = { pickAnyColourOpen = false },
                    onPick = { argb ->
                        pickAnyColourOpen = false
                        if (customEntry != null) {
                            viewModel.updateCustomScheme(
                                customEntry.id, argb, null
                            )
                            viewModel.setColourScheme(
                                CUSTOM_SCHEME_PREFIX + customEntry.id
                            )
                        } else {
                            viewModel.addCustomScheme(argb, "Custom")
                        }
                    }
                )
            }
            // v1.0.7 (AnymeX port): the SELECTED custom scheme's
            // flavour — the same seed, recooked five ways.
            val selectedCustom = appearance.customSchemes.firstOrNull {
                appearance.colourScheme == CUSTOM_SCHEME_PREFIX + it.id
            }
            if (selectedCustom != null) {
                Text(
                    "Flavour of “${selectedCustom.name}” — the same colour, cooked differently.",
                    style = MaterialTheme.typography.titleSmall
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    app.xreader.data.prefs.SchemeFlavour.entries.forEach { flavour ->
                        FilterChip(
                            selected = selectedCustom.flavour == flavour,
                            onClick = {
                                viewModel.setCustomSchemeFlavour(
                                    selectedCustom.id, flavour
                                )
                            },
                            label = {
                                Text(
                                    when (flavour) {
                                        app.xreader.data.prefs.SchemeFlavour.TONAL ->
                                            "Tonal"
                                        app.xreader.data.prefs.SchemeFlavour.VIBRANT ->
                                            "Vibrant"
                                        app.xreader.data.prefs.SchemeFlavour.SOFT ->
                                            "Soft"
                                        app.xreader.data.prefs.SchemeFlavour.EXPRESSIVE ->
                                            "Expressive"
                                        app.xreader.data.prefs.SchemeFlavour.MONO ->
                                            "Mono"
                                    }
                                )
                            }
                        )
                    }
                }
            }
        },

        // ── App size (v1.6.2): one number for the whole screen.
        // Right after Colour theme, near the top of the index,
        // where a wrong value can't hide (the old text-size
        // stepper sat buried in Accessibility & motion and only
        // ever scaled the words). ─────────────────────────────
        {
            Text(
                "How big everything is on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // v1.0.5 (B3): chip rows are FlowRows — wrapping
            // rows can never crush a label on a narrow screen.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = appearance.appSize == AppearanceValidation.APP_SIZE_SMALL,
                    onClick = { viewModel.setAppSize(AppearanceValidation.APP_SIZE_SMALL) },
                    label = { Text("Small") }
                )
                FilterChip(
                    selected = appearance.appSize == AppearanceValidation.APP_SIZE_NORMAL,
                    onClick = { viewModel.setAppSize(AppearanceValidation.APP_SIZE_NORMAL) },
                    label = { Text("Normal") }
                )
                FilterChip(
                    selected = appearance.appSize == AppearanceValidation.APP_SIZE_LARGE,
                    onClick = { viewModel.setAppSize(AppearanceValidation.APP_SIZE_LARGE) },
                    label = { Text("Large") }
                )
            }
            Column {
                Text(
                    "Exact size — ${AppearanceValidation.formatAppSize(appearance.appSize)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = appearance.appSize,
                    onValueChange = { viewModel.setAppSize(it) },
                    valueRange = AppearanceValidation.APP_SIZE_RANGE,
                    steps = 11
                )
            }
            Text(
                "Small on a small phone, Normal on a big one. This changes the whole " +
                    "screen — cards, text and dock — not just the words.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },

        {
            // v1.0.7 (AnymeX port): bloom, grain, liquid background.
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = appearance.bloom) {
                        viewModel.setBloom(!appearance.bloom)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bloom", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "A soft glow in your theme's own colours behind the glass. " +
                            "True black turns it off — black stays black.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NocturneSwitch(
                    checked = appearance.bloom,
                    onCheckedChange = { viewModel.setBloom(it) },
                    modifier = Modifier,
                    enabled = true
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = appearance.grain) {
                        viewModel.setGrain(!appearance.grain)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Film grain", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "A fine film-grain texture over the app. It rests on " +
                            "weaker devices and never covers your reading pages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NocturneSwitch(
                    checked = appearance.grain,
                    onCheckedChange = { viewModel.setGrain(it) },
                    modifier = Modifier,
                    enabled = true
                )
            }
            if (appearance.grain) {
                CustomiseSlider(
                    label = "Grain strength",
                    value = appearance.grainIntensity,
                    range = app.xreader.data.prefs.AppearanceValidation
                        .GRAIN_INTENSITY_RANGE,
                    onChange = { viewModel.setGrainIntensity(it) }
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Background picture", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your own picture behind the glass, on every main screen. " +
                        "It's copied into the app (shrunk to fit) — the original stays yours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { backgroundPicker.launch("image/*") }) {
                        Text(
                            if (appearance.backgroundImage != null) {
                                "Change picture"
                            } else {
                                "Choose a picture"
                            }
                        )
                    }
                    if (appearance.backgroundImage != null) {
                        TextButton(onClick = { viewModel.clearBackgroundImage() }) {
                            Text("Remove")
                        }
                    }
                }
            }
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
                                GlassStyle.LIQUID -> "Liquid glass"
                                GlassStyle.FROSTED -> "Frosted glass"
                                GlassStyle.TINTED -> "Tinted glass"
                                GlassStyle.SOLID -> "Solid"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            when (style) {
                                GlassStyle.LIQUID -> "Liquid glass — surfaces bend the light behind them. Needs a strong device."
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
        },

        {
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
                // Adaptive metrics (Patch 5): the chips show what
                // the Library actually renders. In AUTO (no stored
                // choice) the count is resolved for the phone in
                // hand, so the Auto chip carries that count and
                // shows selected, and no number chip is picked —
                // the stored placeholder is NOT presented as the
                // user's choice. Tapping a number stores the
                // explicit value and ends auto; tapping Auto
                // removes the stored key and hands the count back
                // to the resolution. v1.6.2: the NOVELS grid's
                // Auto count now resolves from the Cover size
                // below (its target width fitted to the screen),
                // so the chip carries the cover-resolved count —
                // the number the novels grid renders. (The Manga
                // grid's Auto count still resolves from the width
                // tier row.)
                val autoColumns = app.xreader.data.library.CoverSizeLogic.resolveColumns(
                    availableWidthDp =
                        LocalConfiguration.current.screenWidthDp - 40,
                    size = library.coverSize,
                    appSize = LocalAppSize.current
                )
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = library.gridColumnsAuto,
                        onClick = { viewModel.clearGridColumns() },
                        label = { Text("Auto ($autoColumns)") }
                    )
                    listOf(2, 3, 4).forEach { columns ->
                        FilterChip(
                            selected = !library.gridColumnsAuto &&
                                library.gridColumns == columns,
                            onClick = { viewModel.setGridColumns(columns) },
                            label = { Text("$columns") }
                        )
                    }
                }
                // Cover size (v1.6.2, the owner's word: "Library
                // covers also customizable sizes"). The novels
                // grid fits as many covers across as the phone
                // allows at the picked width; Follow app size is
                // the default (Small app → small covers). A
                // column number picked above wins over this.
                Text("Novel cover size", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = library.coverSize == CoverSize.FOLLOW_APP,
                        onClick = { viewModel.setCoverSize(CoverSize.FOLLOW_APP) },
                        label = { Text("Follow app size") }
                    )
                    FilterChip(
                        selected = library.coverSize == CoverSize.SMALL,
                        onClick = { viewModel.setCoverSize(CoverSize.SMALL) },
                        label = { Text("Small") }
                    )
                    FilterChip(
                        selected = library.coverSize == CoverSize.NORMAL,
                        onClick = { viewModel.setCoverSize(CoverSize.NORMAL) },
                        label = { Text("Normal") }
                    )
                    FilterChip(
                        selected = library.coverSize == CoverSize.LARGE,
                        onClick = { viewModel.setCoverSize(CoverSize.LARGE) },
                        label = { Text("Large") }
                    )
                }
                Text(
                    "How wide novel covers are — the grid fits as many across as " +
                        "the phone allows. A column number picked above wins over this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        },

        {
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
            // Nav-bar styles (v1.6.2): the indicator's style
            // and colour, with the live miniature dock at the
            // top — the section proves itself here, before any
            // phone-proof round. The preview resolves its tier
            // exactly as the dock does (same PerformancePolicy
            // call), so a MINIMAL phone previews MINIMAL travel.
            val navContext = LocalContext.current
            val navRamMb = remember(navContext) {
                val manager = navContext.getSystemService(Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                val info = android.app.ActivityManager.MemoryInfo()
                manager.getMemoryInfo(info)
                info.totalMem / (1024L * 1024L)
            }
            val navPerfTier = app.xreader.data.prefs.PerformancePolicy.resolve(
                appearance.performanceMode, navRamMb, appearance.coverGlow
            ).tier
            val indicatorColour = if (
                library.navIndicatorColourMode ==
                app.xreader.data.library.NavIndicatorColourMode.CUSTOM
            ) {
                Color(library.navIndicatorColourArgb.toInt())
            } else {
                MaterialTheme.colorScheme.primary
            }
            val onIndicatorColour = Color(
                app.xreader.ui.shell.NavIndicatorLogic.onIndicatorArgb(
                    indicatorColour.toArgb().toLong() and 0xFFFFFFFFL
                ).toInt()
            )
            Text("Indicator preview", style = MaterialTheme.typography.bodyLarge)
            app.xreader.ui.shell.NavIndicatorPreview(
                style = library.navIndicatorStyle,
                colour = indicatorColour,
                onColour = onIndicatorColour,
                barStyle = library.navBarStyle,
                perfTier = navPerfTier,
                reduceMotion = appearance.reduceMotion
            )
            Text("Indicator style", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.GOOEY,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.GOOEY
                        )
                    },
                    label = { Text("Gooey") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.WORM,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.WORM
                        )
                    },
                    label = { Text("Worm") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.LENS,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.LENS
                        )
                    },
                    label = { Text("Liquid lens") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.SLIDE,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.SLIDE
                        )
                    },
                    label = { Text("Slide") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.BUBBLE,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.BUBBLE
                        )
                    },
                    label = { Text("Bubble") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.NOTCH,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.NOTCH
                        )
                    },
                    label = { Text("Notch") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.EXPANDING,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.EXPANDING
                        )
                    },
                    label = { Text("Expanding") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.SEGMENTED,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.SEGMENTED
                        )
                    },
                    label = { Text("Segmented") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.SQUIRCLE,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.SQUIRCLE
                        )
                    },
                    label = { Text("Square") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.RING,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.RING
                        )
                    },
                    label = { Text("Ring") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.DOT,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.DOT
                        )
                    },
                    label = { Text("Dot") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.TOPLINE,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.TOPLINE
                        )
                    },
                    label = { Text("Top line") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.GLOW,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.GLOW
                        )
                    },
                    label = { Text("Glow") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.MINIMAL,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.MINIMAL
                        )
                    },
                    label = { Text("Minimal") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.FLASHY,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.FLASHY
                        )
                    },
                    label = { Text("Flashy") }
                )
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.PLAIN,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.PLAIN
                        )
                    },
                    label = { Text("Plain") }
                )
                // WAVE (travel-choreography round,
                // SPEC-NAV-TRAVEL-WAVE-2026-11-16 §C): the
                // seventeenth style, appended last like its
                // enum value — the bar's top edge dips and the
                // selected icon rides in a circle cradled in
                // the dip.
                FilterChip(
                    selected = library.navIndicatorStyle ==
                        app.xreader.data.library.NavIndicatorStyle.WAVE,
                    onClick = {
                        viewModel.setNavIndicatorStyle(
                            app.xreader.data.library.NavIndicatorStyle.WAVE
                        )
                    },
                    label = { Text("Wave") }
                )
            }
            Text("Indicator colour", style = MaterialTheme.typography.bodyLarge)
            var indicatorWheelOpen by remember { mutableStateOf(false) }
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = library.navIndicatorColourMode ==
                        app.xreader.data.library.NavIndicatorColourMode.THEME,
                    onClick = { viewModel.setNavIndicatorColourTheme() },
                    label = { Text("Theme") }
                )
                FilterChip(
                    selected = library.navIndicatorColourMode ==
                        app.xreader.data.library.NavIndicatorColourMode.CUSTOM,
                    onClick = { indicatorWheelOpen = true },
                    label = { Text("Custom…") },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(
                                    Color(
                                        library.navIndicatorColourArgb.toInt()
                                    )
                                )
                        )
                    }
                )
            }
            if (indicatorWheelOpen) {
                app.xreader.ui.common.ColourWheelPickerDialog(
                    title = "Indicator colour",
                    initialArgb = library.navIndicatorColourArgb,
                    onDismiss = { indicatorWheelOpen = false },
                    onPick = { argb ->
                        viewModel.setNavIndicatorColour(argb)
                        indicatorWheelOpen = false
                    }
                )
            }
            Text(
                "The marker that travels under the tabs. With Reduce motion " +
                    "on, every style rests as Plain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Expanding and Flashy show the name only on the tab you " +
                    "are on — Expanding puts it inside the coloured pill, " +
                    "Flashy swaps the icon for the name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },

        {
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
            // Manga programme (M2b) + v1.5.0 (Part C — the
            // Komikku surface): the GLOBAL manga settings
            // (option-scope rule tier 1) — ReaderConfig via
            // ReaderPrefs, the same flow this screen already
            // collects as `reader`. A series' own choices,
            // made inside its reader, still win for that
            // series only.
            Text("Manga reading:", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (option in ImageReaderMode.entries) {
                    FilterChip(
                        selected = reader.imageMode == option,
                        onClick = {
                            viewModel.updateReader {
                                it.copy(imageMode = option)
                            }
                        },
                        label = { Text(option.label) }
                    )
                }
            }
            Text(
                "How manga and comics open. You can change the " +
                    "mode, tap zones, direction and borders for " +
                    "a single series while reading that series.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // v1.7 OE-4 (owner's CBZ ruling): the ONE global
            // choice for comic files stored on this device —
            // no per-book override. A comic that can't open in
            // the manga reader opens in the book reader by
            // itself, whichever side is picked here.
            Text("CBZ opens in", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.cbzOpensInKomikku,
                    onClick = { viewModel.setCbzOpensInKomikku(true) },
                    label = { Text("Komikku") }
                )
                FilterChip(
                    selected = !state.cbzOpensInKomikku,
                    onClick = { viewModel.setCbzOpensInKomikku(false) },
                    label = { Text("Book reader") }
                )
            }
            Text(
                "For CBZ and CBR files on this device. If a " +
                    "comic can't open in the manga reader, it " +
                    "opens in the book reader instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // D13 (Moon+ copy): the footnotes default for
            // every book — Moon+'s own switch words. A book
            // can carry its own choice, made while reading
            // it; this is the default books without one use.
            Text("Footnotes", style = MaterialTheme.typography.bodyLarge)
            CustomiseSwitch(
                label = "Show footnotes directly in chapter text",
                description = "EPUB footnotes appear in a small " +
                    "box at the end of the paragraph that refers " +
                    "to them. Changing this re-prepares each " +
                    "EPUB book once, the next time it opens.",
                checked = reader.footnotesInText,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(footnotesInText = value)
                    }
                }
            )
            Text("Manga tap zones", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (option in app.xreader.domain.reader.MangaTapLayout.entries) {
                    FilterChip(
                        selected = reader.mangaTapLayout == option,
                        onClick = {
                            viewModel.updateReader {
                                it.copy(mangaTapLayout = option)
                            }
                        },
                        label = { Text(option.label) }
                    )
                }
            }
            Text("Flip tap zones", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (option in app.xreader.domain.reader.TapInvert.entries) {
                    FilterChip(
                        selected = reader.mangaTapInvert == option,
                        onClick = {
                            viewModel.updateReader {
                                it.copy(mangaTapInvert = option)
                            }
                        },
                        label = { Text(option.label) }
                    )
                }
            }
            CustomiseSwitch(
                label = "Smaller tap zones",
                description = "The turn areas shrink to the outer quarters of the screen.",
                checked = reader.mangaSmallerTapZones,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(mangaSmallerTapZones = value)
                    }
                }
            )
            CustomiseSwitch(
                label = "Webtoon double-tap zoom",
                description = "In scrolling mode, double-tap a strip to zoom in; double-tap again to fit.",
                checked = reader.webtoonDoubleTapZoom,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(webtoonDoubleTapZoom = value)
                    }
                }
            )
            CustomiseSwitch(
                label = "Webtoon pinch zoom",
                description = "Two fingers spread or pinch to zoom the scrolling strip.",
                checked = reader.webtoonPinchZoom,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(webtoonPinchZoom = value)
                    }
                }
            )
            CustomiseSwitch(
                label = "Keep webtoon at reading size or larger",
                description = "Pinching can zoom in but never out past the fitted width.",
                checked = reader.webtoonDisableZoomOut,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(webtoonDisableZoomOut = value)
                    }
                }
            )
            CustomiseSwitch(
                label = "Crop borders — paged",
                description = "Trim the plain white/black margins off page-by-page pages.",
                checked = reader.cropBordersPaged,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(cropBordersPaged = value)
                    }
                }
            )
            CustomiseSwitch(
                label = "Crop borders — webtoon",
                description = "Trim the plain white/black margins off scrolling strips.",
                checked = reader.cropBordersWebtoon,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(cropBordersWebtoon = value)
                    }
                }
            )
            CustomiseSwitch(
                label = "Greyscale pages",
                description = "Read every manga page in black and white.",
                checked = reader.mangaGrayscale,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(mangaGrayscale = value)
                    }
                }
            )
            CustomiseSwitch(
                label = "Invert page colours",
                description = "Black becomes white and white becomes black — kind on the eyes at night.",
                checked = reader.mangaInvertColors,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(mangaInvertColors = value)
                    }
                }
            )
            CustomiseSwitch(
                label = "Colour tint over pages",
                description = "A soft colour wash over every page (sepia by default).",
                checked = reader.mangaColorFilter,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(mangaColorFilter = value)
                    }
                }
            )
            if (reader.mangaColorFilter) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tints = listOf(
                        "Sepia" to 0x33FFCC99L,
                        "Warm" to 0x26FFB37EL,
                        "Cool" to 0x2699CCFFL,
                        "Dim" to 0x40000000L
                    )
                    for ((name, argb) in tints) {
                        FilterChip(
                            selected = reader.mangaColorFilterArgb == argb,
                            onClick = {
                                viewModel.updateReader {
                                    it.copy(mangaColorFilterArgb = argb)
                                }
                            },
                            label = { Text(name) }
                        )
                    }
                }
            }
            CustomiseSlider(
                label = "Webtoon side padding: ${reader.webtoonSidePadding}%",
                value = reader.webtoonSidePadding.toFloat(),
                range = 0f..25f,
                onChange = { value ->
                    viewModel.updateReader {
                        it.copy(webtoonSidePadding = value.toInt())
                    }
                }
            )
            Text("Page fit", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (option in app.xreader.domain.reader.MangaScaleType.entries) {
                    FilterChip(
                        selected = reader.mangaScaleType == option,
                        onClick = {
                            viewModel.updateReader {
                                it.copy(mangaScaleType = option)
                            }
                        },
                        label = { Text(option.label) }
                    )
                }
            }
            Text("Manga screen direction", style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (option in app.xreader.domain.reader.MangaOrientation.entries) {
                    FilterChip(
                        selected = reader.mangaOrientation == option,
                        onClick = {
                            viewModel.updateReader {
                                it.copy(mangaOrientation = option)
                            }
                        },
                        label = { Text(option.label) }
                    )
                }
            }
        },

        // ── FIX-18: the Komikku manga reader's global defaults ──
        // (option-scope tier 1: Settings = the default for ALL
        // books; a series' own in-reader choices win for it only).
        // The legacy ReaderConfig manga rows above remain for the
        // text reader's image chapters; the manga reader itself
        // reads these Komikku preferences.
        {
            app.xreader.komikku.presentation.settings
                .KomikkuGlobalDefaultsSection(prefs = viewModel.komikkuGlobalPrefs)
        },

        // ── Your themes: the full make-your-own editor (R04/R07) ──
        {
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
        },

        // ── Book colours (R05) ───────────────────────────────────
        {
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
        },

        // ── Performance (R06) ────────────────────────────────────
        {
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
        },

        // ── Sources & repairs (R11 preference) ───────────────────
        {
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
            // Manga programme (M1, Mihon model): the ONE
            // content preference — sources carry a rating
            // (Safe / Mixed / 18+); this decides which ratings
            // are listed in Explore, search and repositories.
            Text(
                "Content shown from sources:",
                style = MaterialTheme.typography.bodyMedium
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (option in app.xreader.data.extension
                    .ContentVisibility.entries) {
                    FilterChip(
                        selected = state.contentVisibility == option,
                        onClick = { viewModel.setContentVisibility(option) },
                        label = { Text(option.label) }
                    )
                }
            }
            Text(
                state.contentVisibility.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Keiyoushi player (K6): the kill switch, beside
            // the content preference — one switch for the
            // whole manga-extension player.
            CustomiseSwitch(
                label = "Keiyoushi manga sources",
                description = "Manga sources from the Keiyoushi " +
                    "repository run as extensions inside Xreader. " +
                    "Turn this off and none of them load: they " +
                    "disappear from Explore and search, and books " +
                    "from them show their source as turned off. " +
                    "They stay installed — turn it back on and " +
                    "they load again.",
                checked = state.keiyoushiPlayerEnabled,
                onChange = { viewModel.setKeiyoushiPlayerEnabled(it) }
            )
            // v1.7 Patch 1 (item 5): DNS-over-HTTPS — one
            // toggle routing source lookups around ISP
            // blocking. Default off; on means Cloudflare
            // (1.1.1.1) answers the lookups instead of the
            // device's own resolver.
            CustomiseSwitch(
                label = "Use DNS-over-HTTPS for sources",
                description = "Some networks block or tamper with plain DNS lookups, so " +
                    "sources stop loading even though the connection is fine. With this " +
                    "on, Xreader asks Cloudflare (1.1.1.1) for source addresses over " +
                    "an encrypted lookup instead of your network's resolver. Turn it " +
                    "off and lookups go back to the device's own resolver, exactly " +
                    "as before.",
                checked = state.dohEnabled,
                onChange = { viewModel.setDohEnabled(it) }
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
        },

        // ── Downloads (v1.0.4, D10/D13) ─────────────────────────
        // The user's ruling: ALL download switches live together in
        // Settings. The auto-download switch came from the Library,
        // the Wi-Fi-only switch from the Downloads screen; both
        // keep their full labels and their exact prefs.
        {
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
            // v1.3.1 (item 58): Auto-clean cache — the image/
            // cover cache trims itself oldest-first past the
            // cap (Coil's disk-cache bound), so recent covers
            // survive and downloads are never touched. Applies
            // from the next app start.
            Text(
                "Auto-clean cache",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "When the image cache grows past the limit, the " +
                    "oldest covers are removed first. Downloaded " +
                    "chapters are never touched. Applies from the " +
                    "next app start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    200 to "200 MB",
                    500 to "500 MB",
                    1024 to "1 GB",
                    0 to "Off"
                ).forEach { (capMb, label) ->
                    FilterChip(
                        selected = state.downloads.autoCleanCapMb == capMb,
                        onClick = { viewModel.setAutoCleanCapMb(capMb) },
                        label = { Text(label) }
                    )
                }
            }
        },

        // ── Storage (R07/G16) ────────────────────────────────────
        {
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
                    "Reading cache (safe to clear)",
                    app.xreader.data.files.StorageMeter.formatBytes(sizes.cacheBodyBytes)
                )
                StorageRow(
                    "Book files",
                    app.xreader.data.files.StorageMeter.formatBytes(sizes.bookFilesBytes)
                )
                StorageRow(
                    // §12.1: stored covers are per-book assets —
                    // they SURVIVE Clean cache; never label them
                    // as clearable cache.
                    "Stored covers",
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
                // Patch 12 (§C.4): the listening cache lives in
                // cacheDir, outside the meter's filesDir walk —
                // its own row, measured through the cache object.
                val voiceBytes by viewModel.voiceCacheBytes
                    .collectAsStateWithLifecycle()
                StorageRow(
                    "Listening cache",
                    app.xreader.data.files.StorageMeter.formatBytes(voiceBytes ?: 0L)
                )
                var confirmClear by remember { mutableStateOf(false) }
                var confirmVoiceClear by remember { mutableStateOf(false) }
                TextButton(onClick = { confirmClear = true }) {
                    Text("Clear caches")
                }
                Text(
                    "Clearing caches removes read-chapter cache, the image " +
                        "cache and temporary files. Stored covers, downloaded " +
                        "chapters and your place in them stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val clearNote by viewModel.cacheClearNote.collectAsStateWithLifecycle()
                if (clearNote != null) {
                    Text(
                        clearNote!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Patch 12 (§C.4): the listening cache's own
                // clear — separate words, separate confirmation;
                // Clear caches above never touches it.
                TextButton(onClick = { confirmVoiceClear = true }) {
                    Text("Clear listening cache")
                }
                Text(
                    "Removes sentences the online voice fetched ahead. " +
                        "Books, downloads and your places stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (confirmVoiceClear) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmVoiceClear = false },
                        title = { Text("Clear listening cache?") },
                        text = {
                            Text(
                                "Sentences the online voice fetched ahead " +
                                    "will be removed. Books, downloads and " +
                                    "your places stay."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmVoiceClear = false
                                viewModel.clearVoiceCache()
                            }) { Text("Clear listening cache") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmVoiceClear = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
                // v1.5.6 (§3.5 R3): Clear Legacy Data — manual
                // only, never automatic. Converted chapter data of
                // books that now read from their file goes; the
                // books, places and bookmarks are kept.
                var confirmLegacy by remember { mutableStateOf(false) }
                TextButton(onClick = { confirmLegacy = true }) {
                    Text("Clear Legacy Data")
                }
                Text(
                    "Removes the converted chapter data left behind by " +
                        "older imports for books that now read straight " +
                        "from their file. The books themselves, your " +
                        "places in them and your bookmarks are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val legacyNote by viewModel.legacyClearNote
                    .collectAsStateWithLifecycle()
                if (legacyNote != null) {
                    Text(
                        legacyNote!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (confirmLegacy) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmLegacy = false },
                        title = { Text("Clear Legacy Data?") },
                        text = {
                            Text(
                                "Converted chapter data of books that now " +
                                    "read from their file will be deleted. " +
                                    "Books, reading places and bookmarks " +
                                    "are kept."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmLegacy = false
                                viewModel.clearLegacyData()
                            }) { Text("Clear Legacy Data") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmLegacy = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
                if (confirmClear) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmClear = false },
                        title = { Text("Clear caches?") },
                        text = {
                            Text(
                                "Read-chapter cache, image cache and temporary files " +
                                    "will be removed and fetched again when needed. " +
                                    "Stored covers and downloads stay."
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
        },

        // ── Accessibility & motion (R29; R07's App text size
        // moved to the App size section in v1.6.2 — it scales
        // the whole screen now, not just the words) ───────────
        {
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
        },

        // ── Advanced (R07): reset lives here, behind a confirm ───
        {
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
    )

    // Hub and pages swap on the app's stack transitions
    // (MotionSpecs shared-axis): forward into a page, pop
    // back to the hub. No new animation system.
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState != null) {
                stackEnter(appearance.reduceMotion).togetherWith(
                    stackExit(appearance.reduceMotion)
                )
            } else {
                stackPopEnter(appearance.reduceMotion).togetherWith(
                    stackPopExit(appearance.reduceMotion)
                )
            }.using(SizeTransform(clip = true))
        },
        label = "Customise hub and pages"
    ) { currentPage ->
        if (currentPage == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(
                        start = 24.dp, end = 24.dp,
                        top = LocalUiMetrics.current.topGap, bottom = 16.dp
                    )
                    .verticalScroll(hubScroll),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                    Text(
                        "Customise",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Text(
                    "How Xreader looks and feels. Everything here applies straight away.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The pinned live preview (v1.1.0 §5.16): a mini
                // book tile + text sample in the CURRENT Material
                // roles inside a real GlassCard — the same sample
                // line the Reader palette previews already use;
                // the pill bar's fill is the preview's own sample
                // fraction, not reading data.
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(
                            title = "Preview",
                            modifier = Modifier.fillMaxWidth(),
                            eyebrow = "Live"
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(width = 48.dp, height = 68.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    "A sample book",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "The gate opened at dawn.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                                ProgressPillBar(
                                    fraction = 0.42f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        Text(
                            "Now using: $schemeDisplayName · $glassDisplayName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // The doors: the Me dashboard's grid — chunked
                // rows of door cards, one column on the COMPACT
                // tier (Patch 7's rule, read through UiMetrics).
                val metrics = LocalUiMetrics.current
                if (metrics.tier == UiTier.COMPACT) {
                    doors.forEachIndexed { index, door ->
                        CustomiseDoorCard(
                            door = door,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { page = index }
                        )
                    }
                } else {
                    doors.withIndex().chunked(2).forEach { rowDoors ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowDoors.forEach { (index, door) ->
                                CustomiseDoorCard(
                                    door = door,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    onClick = { page = index }
                                )
                            }
                        }
                    }
                }
                // v1.1.0: bottom clearance — 72dp here + the
                // column's 16dp vertical padding = the standard
                // 88dp (dock + dead strip).
                Spacer(
                    Modifier.height(
                        effectiveBottomPadding(72.dp, LocalDockClearance.current)
                    )
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(
                        start = 24.dp, end = 24.dp,
                        top = LocalUiMetrics.current.topGap, bottom = 16.dp
                    )
                    .verticalScroll(pageScroll),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page = null }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to all settings"
                        )
                    }
                    Text(
                        doors[currentPage].title,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                // The page body: the section's own content in a
                // plain column at the rhythm the accordion's
                // inner column gave it (10.dp) — ONE surface, no
                // card around the rows. key() gives each page
                // its own remember slots (dialog and wheel
                // state), as the fifteen separate section call
                // sites did before.
                key(currentPage) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pageContents[currentPage]()
                    }
                }
                // v1.1.0: bottom clearance, as on the hub.
                Spacer(
                    Modifier.height(
                        effectiveBottomPadding(72.dp, LocalDockClearance.current)
                    )
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

/** One Customise door's facts (Orion remake): the hub grid
 * and the compact single column render the same fifteen
 * doors from this one list — icon, plain title, and the ONE
 * live status line (null where no honest value reaches the
 * screen body). */
private data class CustomiseDoor(
    val icon: ImageVector,
    val title: String,
    val status: String?
)

/**
 * One hub door card (Orion remake): the Me dashboard's door
 * language (Patch 7, MeDoorCard) — a big tappable [GlassCard],
 * the door's icon in its 42.dp badge, the plain-word title,
 * and the ONE live status line where the door has one. The
 * card stays the one tap target, with the same bounce press
 * the Me doors use.
 */
@Composable
private fun CustomiseDoorCard(
    door: CustomiseDoor,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // The provided (App-size-scaled) metrics row, like the Me
    // dashboard — the door's min height is one of the sizes
    // the App size exists to scale.
    val metrics = LocalUiMetrics.current
    GlassCard(
        onClick = onClick,
        modifier = modifier.heightIn(min = metrics.cardMinHeight),
        bouncePress = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    door.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                door.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (door.status != null) {
                Text(
                    door.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The App size door's preset name — the chips' own words. */
private fun appSizePresetName(value: Float): String = when (value) {
    AppearanceValidation.APP_SIZE_SMALL -> "Small"
    AppearanceValidation.APP_SIZE_NORMAL -> "Normal"
    AppearanceValidation.APP_SIZE_LARGE -> "Large"
    else -> "Custom"
}

/** The Navigation door's style name — the chips' own words. */
private fun navIndicatorStyleName(
    style: app.xreader.data.library.NavIndicatorStyle
): String = when (style) {
    app.xreader.data.library.NavIndicatorStyle.GOOEY -> "Gooey"
    app.xreader.data.library.NavIndicatorStyle.WORM -> "Worm"
    app.xreader.data.library.NavIndicatorStyle.LENS -> "Liquid lens"
    app.xreader.data.library.NavIndicatorStyle.SLIDE -> "Slide"
    app.xreader.data.library.NavIndicatorStyle.BUBBLE -> "Bubble"
    app.xreader.data.library.NavIndicatorStyle.NOTCH -> "Notch"
    app.xreader.data.library.NavIndicatorStyle.EXPANDING -> "Expanding"
    app.xreader.data.library.NavIndicatorStyle.SEGMENTED -> "Segmented"
    app.xreader.data.library.NavIndicatorStyle.SQUIRCLE -> "Square"
    app.xreader.data.library.NavIndicatorStyle.RING -> "Ring"
    app.xreader.data.library.NavIndicatorStyle.DOT -> "Dot"
    app.xreader.data.library.NavIndicatorStyle.TOPLINE -> "Top line"
    app.xreader.data.library.NavIndicatorStyle.GLOW -> "Glow"
    app.xreader.data.library.NavIndicatorStyle.MINIMAL -> "Minimal"
    app.xreader.data.library.NavIndicatorStyle.FLASHY -> "Flashy"
    app.xreader.data.library.NavIndicatorStyle.PLAIN -> "Plain"
    app.xreader.data.library.NavIndicatorStyle.WAVE -> "Wave"
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
        // v1.1.0 (§4/§5.16): the kit switch is the app-wide switch
        // styling — every Customise toggle goes through it now.
        NocturneSwitch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier,
            enabled = true
        )
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
