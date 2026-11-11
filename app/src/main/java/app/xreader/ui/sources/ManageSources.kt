package app.xreader.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.di.AppContainer
import app.xreader.data.db.ExtensionKind
import app.xreader.data.db.InstalledExtension
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.SourceProvenance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * v1.5.2 (FIX-15): the source-management screen's state.
 *
 * Owner directive (2026-11-03): with the full source set
 * landing, sources must not clutter — ONE dedicated screen
 * lists EVERY source (built-in, pack, link-added, repo),
 * each with its §8.6 provenance label, its language and its
 * kind, a SHOW/HIDE toggle, and visible labelled filters.
 *
 * The toggle rides the EXISTING §8.1 enabled/disabled flag
 * (InstalledExtension.enabled, written through
 * RepoManager.setEnabled — the same writer the extension
 * detail screen uses). There is deliberately NO second
 * flag: the §8 update pass preserves this one, Explore
 * browses shown sources only, and the global-search fan-out
 * skips disabled rows (see GlobalSearchViewModel).
 */

/** Where a source came from, as a filter bucket. The label
 * the user reads comes from [SourceProvenance]; the bucket
 * derives from the same packageId/kind facts. Keiyoushi
 * extensions bucket as REPO (their label still reads
 * "Keiyoushi extension"). */
enum class ManagedOrigin { SHIPPED, PACK, LINK, REPO }

/** The four language buckets the filter offers. A row with
 * no code mapping to any of them is "unknown" — handled
 * honestly as its own filter choice, never dressed up with
 * a fabricated language. */
enum class ManagedLang { EN, JP, CN, KR }

enum class KindFilter { ALL, NOVELS, MANGA }
enum class LanguageFilter { ALL, EN, JP, CN, KR, UNKNOWN }
enum class OriginFilter { ALL, SHIPPED, PACK, LINK, REPO }
enum class ShowingFilter { ALL, SHOWN, HIDDEN }

data class ManageFilters(
    val name: String = "",
    val kind: KindFilter = KindFilter.ALL,
    val language: LanguageFilter = LanguageFilter.ALL,
    val origin: OriginFilter = OriginFilter.ALL,
    val showing: ShowingFilter = ShowingFilter.ALL
)

/** One management row: the installed-extension record plus
 * its derived display facts. */
data class ManagedSourceRow(
    val extension: InstalledExtension,
    val provenanceLabel: String,
    val origin: ManagedOrigin,
    val languageLabel: String,
    val languages: Set<ManagedLang>,
    val kinds: Set<MediaKind>,
    val kindLabel: String
) {
    val shown: Boolean get() = extension.enabled
}

/** The pure derivations + filter predicate — kept free of
 * Android/Compose so the journey tests can pin them
 * directly. */
object ManageSourcesModel {

    fun originOf(ext: InstalledExtension): ManagedOrigin = when {
        ext.kind == ExtensionKind.KEIYOUSHI -> ManagedOrigin.REPO
        ext.packageId.startsWith("link.") ||
            ext.packageId.startsWith("live.") -> ManagedOrigin.LINK
        ext.packageId.startsWith("packs.") -> ManagedOrigin.PACK
        ext.packageId.startsWith("bundled.") -> ManagedOrigin.SHIPPED
        else -> ManagedOrigin.REPO
    }

    /** (label, buckets) for an extension's recorded language
     * codes (comma-separated, as installed). Blank record =
     * "Language unknown" with no buckets; codes outside the
     * big four show as recorded (e.g. "id") rather than being
     * silently re-labelled. */
    fun languagesOf(ext: InstalledExtension): Pair<String, Set<ManagedLang>> {
        val codes = ext.languages.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        if (codes.isEmpty()) return "Language unknown" to emptySet()
        val buckets = mutableSetOf<ManagedLang>()
        val names = codes.map { code ->
            when {
                code == "en" -> {
                    buckets += ManagedLang.EN; "English"
                }
                code.startsWith("ja") -> {
                    buckets += ManagedLang.JP; "Japanese"
                }
                code.startsWith("zh") -> {
                    buckets += ManagedLang.CN; "Chinese"
                }
                code.startsWith("ko") -> {
                    buckets += ManagedLang.KR; "Korean"
                }
                else -> code
            }
        }
        return names.distinct().joinToString(", ") to buckets
    }

    fun kindLabel(kinds: Set<MediaKind>): String = when {
        kinds.isEmpty() -> "Kind unknown"
        kinds == setOf(MediaKind.TEXT) -> "Novels"
        kinds == setOf(MediaKind.MANGA) -> "Manga"
        else -> "Novels + Manga"
    }

    fun rowOf(
        ext: InstalledExtension,
        kinds: Set<MediaKind>
    ): ManagedSourceRow {
        val (languageLabel, buckets) = languagesOf(ext)
        return ManagedSourceRow(
            extension = ext,
            provenanceLabel = SourceProvenance.label(ext),
            origin = originOf(ext),
            languageLabel = languageLabel,
            languages = buckets,
            kinds = kinds,
            kindLabel = kindLabel(kinds)
        )
    }

    fun matches(row: ManagedSourceRow, filters: ManageFilters): Boolean {
        val name = filters.name.trim()
        if (name.isNotEmpty() &&
            !row.extension.displayName.contains(name, ignoreCase = true)
        ) {
            return false
        }
        when (filters.kind) {
            KindFilter.ALL -> Unit
            KindFilter.NOVELS ->
                if (MediaKind.TEXT !in row.kinds) return false
            KindFilter.MANGA ->
                if (MediaKind.MANGA !in row.kinds) return false
        }
        when (filters.language) {
            LanguageFilter.ALL -> Unit
            LanguageFilter.UNKNOWN ->
                if (row.languages.isNotEmpty()) return false
            LanguageFilter.EN ->
                if (ManagedLang.EN !in row.languages) return false
            LanguageFilter.JP ->
                if (ManagedLang.JP !in row.languages) return false
            LanguageFilter.CN ->
                if (ManagedLang.CN !in row.languages) return false
            LanguageFilter.KR ->
                if (ManagedLang.KR !in row.languages) return false
        }
        when (filters.origin) {
            OriginFilter.ALL -> Unit
            OriginFilter.SHIPPED ->
                if (row.origin != ManagedOrigin.SHIPPED) return false
            OriginFilter.PACK ->
                if (row.origin != ManagedOrigin.PACK) return false
            OriginFilter.LINK ->
                if (row.origin != ManagedOrigin.LINK) return false
            OriginFilter.REPO ->
                if (row.origin != ManagedOrigin.REPO) return false
        }
        when (filters.showing) {
            ShowingFilter.ALL -> Unit
            ShowingFilter.SHOWN -> if (!row.shown) return false
            ShowingFilter.HIDDEN -> if (row.shown) return false
        }
        return true
    }
}

class ManageSourcesViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val kindsByExtension =
        MutableStateFlow<Map<Long, Set<MediaKind>>>(emptyMap())

    private val _filters = MutableStateFlow(ManageFilters())
    val filters: StateFlow<ManageFilters> = _filters

    /** Every installed source as a management row (shown AND
     * hidden — management is the one place hidden sources
     * stay visible). */
    val allRows: StateFlow<List<ManagedSourceRow>> = combine(
        container.database.installedExtensionDao().observeAll(),
        kindsByExtension
    ) { exts, kinds ->
        exts.map { ext ->
            ManageSourcesModel.rowOf(ext, kinds[ext.id].orEmpty())
        }
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    /** [allRows] after the visible filters. */
    val rows: StateFlow<List<ManagedSourceRow>> = combine(
        allRows, _filters
    ) { all, active ->
        all.filter { ManageSourcesModel.matches(it, active) }
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    init {
        viewModelScope.launch {
            runCatching {
                kindsByExtension.value =
                    container.extensionService.allSources()
                        .groupBy { it.extension.id }
                        .mapValues { (_, handles) ->
                            handles.map { handle ->
                                container.extensionService
                                    .mediaKindOf(handle)
                            }.toSet()
                        }
            }
        }
    }

    fun setName(name: String) {
        _filters.value = _filters.value.copy(name = name)
    }

    fun setKind(kind: KindFilter) {
        _filters.value = _filters.value.copy(kind = kind)
    }

    fun setLanguage(language: LanguageFilter) {
        _filters.value = _filters.value.copy(language = language)
    }

    fun setOrigin(origin: OriginFilter) {
        _filters.value = _filters.value.copy(origin = origin)
    }

    fun setShowing(showing: ShowingFilter) {
        _filters.value = _filters.value.copy(showing = showing)
    }

    /** THE toggle wiring (FIX-15): writes the EXISTING §8.1
     * enabled flag through the same RepoManager writer the
     * extension detail screen uses — no second flag. */
    fun setEnabled(row: ManagedSourceRow, enabled: Boolean) {
        viewModelScope.launch {
            container.repoManager.setEnabled(row.extension, enabled)
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>
                ): T = ManageSourcesViewModel(container) as T
            }
    }
}
