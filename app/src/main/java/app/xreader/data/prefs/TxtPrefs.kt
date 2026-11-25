package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** MR-6 (SPEC-MR6-TXT-2026-11-25): the Simplified↔Traditional
 * render mode (Item 8). OFF is the default, as in the spec. */
enum class ScriptMode {
    OFF,
    TO_TRADITIONAL,
    TO_SIMPLIFIED;

    companion object {
        fun fromStored(value: String?): ScriptMode =
            entries.firstOrNull { it.name == value } ?: OFF
    }
}

/**
 * MR-6: the one store for the TXT flagship settings, on the shared
 * settings DataStore beside [ReaderPrefs] (the spec's Item 1/5
 * "builder's call" — the keys belong apart from the reader config
 * because the parsers read them synchronously at import time).
 *
 * Holds: the per-language chapter-regex overrides + max title
 * length + trailing-char blacklist (Item 1), the `txt_toc_reg`
 * mode switch (Item 4), the per-file encoding records keyed by
 * `Book.filePath` (Item 5), the Simplified↔Traditional mode
 * (Item 8) and the two render-time trim switches (Item 9).
 *
 * [snapshot] is the synchronous view the parser path reads; it is
 * kept current by a collector the container starts at app scope.
 */
class TxtPrefs(private val store: DataStore<Preferences>) {

    data class State(
        val regexOverrides: Map<TxtLanguage, String> = emptyMap(),
        val maxTitleLength: Int = DEFAULT_MAX_TITLE_LENGTH,
        val titleBlacklist: String = DEFAULT_TITLE_BLACKLIST,
        val tocRegMode: Boolean = false,
        val encodingOverrides: Map<String, String> = emptyMap(),
        val detectedEncodings: Map<String, String> = emptyMap(),
        val scriptMode: ScriptMode = ScriptMode.OFF,
        // Item 9's defaults are ON, deliberately: the spec's own
        // rule is "defaulting to current behaviour so existing
        // books do not change shape on update", and Xreader's
        // shipped shape is the trimmed one (import used to bake
        // the trims in). Moon+'s switches default OFF because
        // Moon+ stores raw text; Xreader now stores the
        // as-printed structure and trims at render, so ON is the
        // value that keeps every book looking as it does today.
        val trimBlankLines: Boolean = true,
        val trimPageTopBlanks: Boolean = true
    ) {
        fun regexOverride(language: TxtLanguage): String? =
            regexOverrides[language]?.takeIf { it.isNotBlank() }

        fun encodingOverrideFor(filePath: String): String? =
            encodingOverrides[filePath]

        fun detectedEncodingFor(filePath: String): String? =
            detectedEncodings[filePath]
    }

    /** The TXT language a regex rule belongs to (Moon+'s
     * `getTxtRegRule` choice: CN when Chinese or Japanese, RU when
     * Russian, else EN). */
    enum class TxtLanguage { EN, CN, RU }

    val state: Flow<State> = store.data.map { prefs -> prefs.toState() }

    @Volatile
    var snapshot: State = State()
        internal set

    private fun Preferences.toState(): State = State(
        regexOverrides = TxtLanguage.entries.mapNotNull { language ->
            val key = when (language) {
                TxtLanguage.EN -> KEY_REGEX_EN
                TxtLanguage.CN -> KEY_REGEX_CN
                TxtLanguage.RU -> KEY_REGEX_RU
            }
            this[key]?.takeIf { it.isNotBlank() }?.let { language to it }
        }.toMap(),
        maxTitleLength = (this[KEY_MAX_TITLE_LENGTH] ?: DEFAULT_MAX_TITLE_LENGTH)
            .coerceIn(10, 500),
        titleBlacklist = this[KEY_TITLE_BLACKLIST] ?: DEFAULT_TITLE_BLACKLIST,
        tocRegMode = this[KEY_TOC_REG] ?: false,
        encodingOverrides = decodePathMap(this[KEY_ENCODING_OVERRIDES]),
        detectedEncodings = decodePathMap(this[KEY_DETECTED_ENCODINGS]),
        scriptMode = ScriptMode.fromStored(this[KEY_SCRIPT_MODE]),
        trimBlankLines = this[KEY_TRIM_BLANK] ?: true,
        trimPageTopBlanks = this[KEY_TRIM_TOP] ?: true
    )

    suspend fun setRegexOverride(language: TxtLanguage, rule: String?) {
        val key = when (language) {
            TxtLanguage.EN -> KEY_REGEX_EN
            TxtLanguage.CN -> KEY_REGEX_CN
            TxtLanguage.RU -> KEY_REGEX_RU
        }
        store.edit { prefs ->
            if (rule.isNullOrBlank()) prefs.remove(key) else prefs[key] = rule
        }
    }

    suspend fun setMaxTitleLength(value: Int) {
        store.edit { prefs -> prefs[KEY_MAX_TITLE_LENGTH] = value.coerceIn(10, 500) }
    }

    suspend fun setTitleBlacklist(value: String) {
        store.edit { prefs -> prefs[KEY_TITLE_BLACKLIST] = value }
    }

    suspend fun setTocRegMode(value: Boolean) {
        store.edit { prefs -> prefs[KEY_TOC_REG] = value }
    }

    suspend fun setEncodingOverride(filePath: String, charsetName: String?) {
        store.edit { prefs ->
            val map = decodePathMap(prefs[KEY_ENCODING_OVERRIDES]).toMutableMap()
            if (charsetName.isNullOrBlank()) map.remove(filePath) else map[filePath] = charsetName
            prefs[KEY_ENCODING_OVERRIDES] = encodePathMap(map)
        }
    }

    suspend fun recordDetectedEncoding(filePath: String, charsetName: String) {
        store.edit { prefs ->
            val map = decodePathMap(prefs[KEY_DETECTED_ENCODINGS]).toMutableMap()
            if (map[filePath] != charsetName) {
                map[filePath] = charsetName
                prefs[KEY_DETECTED_ENCODINGS] = encodePathMap(map)
            }
        }
    }

    suspend fun setScriptMode(mode: ScriptMode) {
        store.edit { prefs -> prefs[KEY_SCRIPT_MODE] = mode.name }
    }

    suspend fun setTrimBlankLines(value: Boolean) {
        store.edit { prefs -> prefs[KEY_TRIM_BLANK] = value }
    }

    suspend fun setTrimPageTopBlanks(value: Boolean) {
        store.edit { prefs -> prefs[KEY_TRIM_TOP] = value }
    }

    companion object {
        const val DEFAULT_MAX_TITLE_LENGTH = 80
        const val DEFAULT_TITLE_BLACKLIST = ",.;:，。；：、"

        private val KEY_REGEX_EN = stringPreferencesKey("txt_regex_en")
        private val KEY_REGEX_CN = stringPreferencesKey("txt_regex_cn")
        private val KEY_REGEX_RU = stringPreferencesKey("txt_regex_ru")
        private val KEY_MAX_TITLE_LENGTH = intPreferencesKey("txt_max_title_length")
        private val KEY_TITLE_BLACKLIST = stringPreferencesKey("txt_title_blacklist")
        private val KEY_TOC_REG = booleanPreferencesKey("txt_toc_reg")
        private val KEY_ENCODING_OVERRIDES = stringPreferencesKey("txt_encoding_overrides")
        private val KEY_DETECTED_ENCODINGS = stringPreferencesKey("txt_detected_encodings")
        private val KEY_SCRIPT_MODE = stringPreferencesKey("txt_script_mode")
        private val KEY_TRIM_BLANK = booleanPreferencesKey("txt_trim_blank_lines")
        private val KEY_TRIM_TOP = booleanPreferencesKey("txt_trim_page_top_blanks")

        /** Path maps ride in one string pref: one `path<TAB>value`
         * per line. Paths are app-relative and never contain tabs
         * or newlines. */
        internal fun encodePathMap(map: Map<String, String>): String =
            map.entries.joinToString("\n") { (k, v) -> "$k\t$v" }

        internal fun decodePathMap(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null
                else line.substring(0, tab) to line.substring(tab + 1)
            }.toMap()
        }
    }
}

/** Convenience alias so the parse layer does not depend on the
 * prefs class for the language enum alone. */
typealias TxtLanguage = TxtPrefs.TxtLanguage
