package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { FOLLOW_SYSTEM, LIGHT, DARK }

/**
 * How glass surfaces render (v1.0.1 — the control now tells the truth):
 * FROSTED = real backdrop blur (Haze/RenderEffect) where the device
 * can do it, TINTED = the original solid-tint glass look, SOLID =
 * fully opaque surfaces. The user's CHOICE is stored; the EFFECTIVE
 * style is resolved per device by [AppearanceValidation.effectiveGlassStyle].
 */
enum class GlassStyle { FROSTED, TINTED, SOLID }

/** Selection token prefix for a make-your-own scheme: the stored
 * colour-scheme value is `custom:<id>` and the full spec lives in
 * [AppearanceState.customSchemes] (v1.0.2, R04 data path). */
const val CUSTOM_SCHEME_PREFIX = "custom:"

/** Where cover-derived colours may appear (v1.0.2, R05). The reader
 * PAGE palette is never cover-derived in any scope — only reader
 * chrome can join the book page, and only when the user asks. */
enum class BookColoursScope { BOOK_PAGE_ONLY, BOOK_PAGE_AND_READER, OFF }

/** The user's visible performance choice (v1.0.2, R06). AUTOMATIC
 * lets the device tier decide; SMOOTH forces the cheap rendering
 * paths; BEST_LOOKING enables every safe effect the device can do.
 * The effective policy is resolved by PerformancePolicy. */
enum class PerformanceMode { AUTOMATIC, SMOOTH, BEST_LOOKING }

/**
 * A user-made colour scheme spec (v1.0.2, R04 make-your-own-theme):
 * seed colour (+ optional gradient end colour) and a format version.
 * The full [app.xreader.ui.theme.AppScheme] is DERIVED from this by
 * the generator — never stored — so the payload stays tiny and a
 * future generator improvement upgrades every saved theme for free.
 * Stored in DataStore as `id|name|seed|end|version` rows; the same
 * shape is the sync payload field (R02).
 */
data class CustomSchemeSpec(
    val id: String,
    val name: String,
    /** ARGB seed colour the scheme is generated from. */
    val seedArgb: Long,
    /** Optional second colour (cover-aura gradient end); null = seed only. */
    val endArgb: Long? = null,
    val version: Int = 1
)

/**
 * Appearance state (PLAN §2.2): theme mode plus the three global glass
 * controls — opacity, corner radius, elevation — that re-token the whole
 * app, and the reduce-motion switch. Defaults are the Stitch design's
 * values; the Appearance screen's sliders write through [AppearancePrefs]
 * (the single writer) and the theme reads this state.
 */
data class AppearanceState(
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val glassOpacity: Float = AppearanceValidation.DEFAULT_GLASS_OPACITY,
    val glassRadiusScale: Float = AppearanceValidation.DEFAULT_RADIUS_SCALE,
    val glassElevationScale: Float = AppearanceValidation.DEFAULT_ELEVATION_SCALE,
    val reduceMotion: Boolean = false,
    /** Name of the chosen app colour scheme (see AppSchemes), or
     * `custom:<id>` for a make-your-own scheme. */
    val colourScheme: String = "Ember",
    /** The user's glass CHOICE (the effective style may fall back). */
    val glassStyle: GlassStyle = GlassStyle.TINTED,
    /** True black (v1.0.2, R04): dark-mode surfaces collapse to pure
     * black for AMOLED. Off by default. */
    val trueBlack: Boolean = false,
    /** The user's own colour schemes (R04 make-your-own data path). */
    val customSchemes: List<CustomSchemeSpec> = emptyList(),
    /** Cover-colour scope (v1.0.2, R05). Default: book page only. */
    val bookColours: BookColoursScope = BookColoursScope.BOOK_PAGE_ONLY,
    /** Library-card cover glow (v1.0.2, R05): a separate opt-in,
     * default off, auto-disabled on the MINIMAL tier. */
    val coverGlow: Boolean = false,
    /** Performance choice (v1.0.2, R06). Default AUTOMATIC. */
    val performanceMode: PerformanceMode = PerformanceMode.AUTOMATIC,
    /** App text size (v1.0.2, R07): 80–130% in 5% steps. Reader text
     * size is a separate setting and is not touched by this. */
    val appTextSizePercent: Int = 100,
    /** "Move card with phone" (v1.0.2, R29): device-motion tilt for
     * the Me identity card. Default off; registered only while Me
     * is visible. */
    val meCardMotionTilt: Boolean = false
)

/**
 * Pure validation for stored appearance values (PLAN §1.5: stored values
 * are validated on read with safe defaults — a corrupt or out-of-range
 * preference can never produce a broken layout). Unit-tested on the JVM.
 */
object AppearanceValidation {
    const val DEFAULT_GLASS_OPACITY = 1.0f
    const val DEFAULT_RADIUS_SCALE = 1.0f
    const val DEFAULT_ELEVATION_SCALE = 1.0f

    /** Glass surfaces may go translucent, never invisible. */
    val GLASS_OPACITY_RANGE = 0.55f..1.0f
    val RADIUS_SCALE_RANGE = 0.5f..1.5f
    val ELEVATION_SCALE_RANGE = 0.0f..2.0f

    fun clampGlassOpacity(value: Float): Float =
        if (value.isNaN()) DEFAULT_GLASS_OPACITY
        else value.coerceIn(GLASS_OPACITY_RANGE.start, GLASS_OPACITY_RANGE.endInclusive)

    fun clampRadiusScale(value: Float): Float =
        if (value.isNaN()) DEFAULT_RADIUS_SCALE
        else value.coerceIn(RADIUS_SCALE_RANGE.start, RADIUS_SCALE_RANGE.endInclusive)

    fun clampElevationScale(value: Float): Float =
        if (value.isNaN()) DEFAULT_ELEVATION_SCALE
        else value.coerceIn(ELEVATION_SCALE_RANGE.start, ELEVATION_SCALE_RANGE.endInclusive)

    fun parseThemeMode(raw: String?): ThemeMode =
        runCatching { ThemeMode.valueOf(raw ?: "") }.getOrDefault(ThemeMode.FOLLOW_SYSTEM)

    fun parseGlassStyle(raw: String?): GlassStyle =
        runCatching { GlassStyle.valueOf(raw ?: "") }.getOrDefault(GlassStyle.TINTED)

    fun parseBookColours(raw: String?): BookColoursScope =
        runCatching { BookColoursScope.valueOf(raw ?: "") }
            .getOrDefault(BookColoursScope.BOOK_PAGE_ONLY)

    fun parsePerformanceMode(raw: String?): PerformanceMode =
        runCatching { PerformanceMode.valueOf(raw ?: "") }
            .getOrDefault(PerformanceMode.AUTOMATIC)

    /** App text size bounds (R07): 80–130%, snapped to 5% steps. */
    val APP_TEXT_SIZE_RANGE = 80..130

    fun clampAppTextSize(value: Int): Int {
        val snapped = (value / 5) * 5 + if (value % 5 >= 3) 5 else 0
        return snapped.coerceIn(APP_TEXT_SIZE_RANGE.first, APP_TEXT_SIZE_RANGE.last)
    }

    /** The colour scheme names the picker offers (kept in sync with
     * AppSchemes in the theme module; unknown names fall back to Ember).
     * v1.0.2 adds the R04 vibrant presets. "Wallpaper" is the Material
     * You dynamic preset (R04): the name validates everywhere, but the
     * theme only resolves it on API 31+ and the picker hides it below
     * that — an older device holding the value falls back to Ember. */
    val COLOUR_SCHEMES = listOf(
        "Ember", "Violet night", "Ocean", "Forest", "Rose", "Mono",
        "Nebula violet", "Emerald", "Sunset", "Wallpaper"
    )

    /** The fresh-install default scheme (R04 + the user's autonomy
     * decision): a brand-new install starts on Violet Night. */
    const val FRESH_INSTALL_SCHEME = "Violet night"

    /** The upgrade-preserving default: an install that already has
     * data keeps the look it had (Ember unless the user chose). */
    const val ESTABLISHED_INSTALL_SCHEME = "Ember"

    /** R04: at most three named make-your-own schemes. */
    const val MAX_CUSTOM_SCHEMES = 3

    /**
     * Add or replace a custom scheme under the [MAX_CUSTOM_SCHEMES]
     * cap. Returns the new list, or null when [spec] is a NEW scheme
     * and the cap is already reached (replacing an existing id is
     * always allowed). Pure — the writer and the editor UI share it.
     */
    fun addCustomScheme(
        existing: List<CustomSchemeSpec>,
        spec: CustomSchemeSpec
    ): List<CustomSchemeSpec>? {
        val isNew = existing.none { it.id == spec.id }
        if (isNew && existing.size >= MAX_CUSTOM_SCHEMES) return null
        return if (isNew) existing + spec
        else existing.map { if (it.id == spec.id) spec else it }
    }

    /**
     * A stored selection is valid when it names a preset or a
     * `custom:<id>` whose spec is actually stored ([customIds]);
     * anything else falls back to Ember (PLAN §1.5 validation).
     */
    fun parseColourScheme(raw: String?, customIds: Set<String> = emptySet()): String = when {
        raw in COLOUR_SCHEMES -> raw!!
        raw != null && raw.startsWith(CUSTOM_SCHEME_PREFIX) &&
            raw.removePrefix(CUSTOM_SCHEME_PREFIX) in customIds -> raw
        else -> "Ember"
    }

    /** Serialise custom scheme specs for DataStore (one row per line:
     * `id|name|seedArgb|endArgbOrBlank|version`). Names are stripped
     * of the separators so the format can never corrupt. */
    fun encodeCustomSchemes(schemes: List<CustomSchemeSpec>): String =
        schemes.joinToString("\n") { spec ->
            val safeName = spec.name.replace('|', ' ').replace('\n', ' ').trim()
            listOf(
                spec.id, safeName, spec.seedArgb.toString(),
                spec.endArgb?.toString() ?: "", spec.version.toString()
            ).joinToString("|")
        }

    /** Parse stored custom scheme rows; malformed rows are skipped,
     * never fatal (PLAN §1.5). */
    fun decodeCustomSchemes(raw: String?): List<CustomSchemeSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 5) return@mapNotNull null
            val id = parts[0].trim()
            val seed = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null
            CustomSchemeSpec(
                id = id,
                name = parts[1].ifBlank { "My theme" },
                seedArgb = seed,
                endArgb = parts[3].toLongOrNull(),
                version = parts[4].toIntOrNull() ?: 1
            )
        }
    }

    /**
     * Resolve the EFFECTIVE glass style for a device. Real blur needs
     * RenderEffect (API 31+) and more than the low-RAM tier (>2 GB);
     * below either bar FROSTED honestly falls back to TINTED. Pure and
     * unit-tested — the Appearance screen shows the same resolution in
     * plain words.
     */
    fun effectiveGlassStyle(choice: GlassStyle, sdkInt: Int, totalRamMb: Long): GlassStyle =
        when (choice) {
            GlassStyle.FROSTED ->
                if (sdkInt >= 31 && totalRamMb > 2048) GlassStyle.FROSTED else GlassStyle.TINTED
            GlassStyle.TINTED -> GlassStyle.TINTED
            GlassStyle.SOLID -> GlassStyle.SOLID
        }

    /** Plain-words note when the choice can't be honoured here. */
    fun glassFallbackNote(choice: GlassStyle, sdkInt: Int, totalRamMb: Long): String? =
        if (choice == GlassStyle.FROSTED &&
            effectiveGlassStyle(choice, sdkInt, totalRamMb) != GlassStyle.FROSTED
        ) "Frosted glass needs a stronger device — using tinted glass here."
        else null

    /** Sanitises a whole state read from storage. */
    fun validate(state: AppearanceState): AppearanceState = state.copy(
        glassOpacity = clampGlassOpacity(state.glassOpacity),
        glassRadiusScale = clampRadiusScale(state.glassRadiusScale),
        glassElevationScale = clampElevationScale(state.glassElevationScale)
    )
}

/** The single writer/reader of appearance preferences (PLAN §1.2: one
 * canonical writer per stored value).
 *
 * [establishedInstall] (v1.0.2, R04) is the upgrade signal: true when
 * the app already had data before this process first ran (the caller
 * checks the Room database file's existence BEFORE Room can create
 * it). It drives ONLY the one-time default-scheme priming in
 * [primeDefaultScheme] — an upgrader is never silently re-skinned,
 * and a fresh install gets the Violet Night default the plan names. */
class AppearancePrefs(
    private val store: DataStore<Preferences>,
    private val establishedInstall: Boolean = true
) {

    /**
     * One-time default-scheme priming (R04): if the user has never
     * chosen a colour scheme, write the install-appropriate default
     * explicitly — Violet Night for a fresh install, Ember for an
     * established one — so the choice is stable across process
     * restarts (after the first run every install "has data", so an
     * unprimed read would flip a fresh install back to Ember).
     * Idempotent; a stored user choice is never overwritten.
     */
    suspend fun primeDefaultScheme() {
        store.edit { prefs ->
            if (prefs[KEY_SCHEME_PRIMED] == true) return@edit
            prefs[KEY_SCHEME_PRIMED] = true
            if (prefs[KEY_COLOUR_SCHEME] == null) {
                prefs[KEY_COLOUR_SCHEME] = if (establishedInstall) {
                    AppearanceValidation.ESTABLISHED_INSTALL_SCHEME
                } else {
                    AppearanceValidation.FRESH_INSTALL_SCHEME
                }
            }
        }
    }

    val state: Flow<AppearanceState> = store.data.map { prefs ->
        val customs = AppearanceValidation.decodeCustomSchemes(prefs[KEY_CUSTOM_SCHEMES])
        AppearanceValidation.validate(
            AppearanceState(
                themeMode = AppearanceValidation.parseThemeMode(prefs[KEY_THEME_MODE]),
                glassOpacity = prefs[KEY_GLASS_OPACITY] ?: AppearanceValidation.DEFAULT_GLASS_OPACITY,
                glassRadiusScale = prefs[KEY_GLASS_RADIUS] ?: AppearanceValidation.DEFAULT_RADIUS_SCALE,
                glassElevationScale = prefs[KEY_GLASS_ELEVATION] ?: AppearanceValidation.DEFAULT_ELEVATION_SCALE,
                reduceMotion = prefs[KEY_REDUCE_MOTION] ?: false,
                colourScheme = AppearanceValidation.parseColourScheme(
                    prefs[KEY_COLOUR_SCHEME], customs.map { it.id }.toSet()
                ),
                glassStyle = AppearanceValidation.parseGlassStyle(prefs[KEY_GLASS_STYLE]),
                trueBlack = prefs[KEY_TRUE_BLACK] ?: false,
                customSchemes = customs,
                bookColours = AppearanceValidation.parseBookColours(prefs[KEY_BOOK_COLOURS]),
                coverGlow = prefs[KEY_COVER_GLOW] ?: false,
                performanceMode = AppearanceValidation.parsePerformanceMode(prefs[KEY_PERF_MODE]),
                appTextSizePercent = AppearanceValidation.clampAppTextSize(
                    prefs[KEY_TEXT_SIZE] ?: 100
                ),
                meCardMotionTilt = prefs[KEY_ME_MOTION_TILT] ?: false
            )
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) =
        store.edit { it[KEY_THEME_MODE] = mode.name }

    suspend fun setGlassOpacity(value: Float) =
        store.edit { it[KEY_GLASS_OPACITY] = AppearanceValidation.clampGlassOpacity(value) }

    suspend fun setGlassRadiusScale(value: Float) =
        store.edit { it[KEY_GLASS_RADIUS] = AppearanceValidation.clampRadiusScale(value) }

    suspend fun setGlassElevationScale(value: Float) =
        store.edit { it[KEY_GLASS_ELEVATION] = AppearanceValidation.clampElevationScale(value) }

    suspend fun setReduceMotion(value: Boolean) =
        store.edit { it[KEY_REDUCE_MOTION] = value }

    suspend fun setColourScheme(name: String) =
        store.edit { prefs ->
            val customs = AppearanceValidation.decodeCustomSchemes(prefs[KEY_CUSTOM_SCHEMES])
            prefs[KEY_COLOUR_SCHEME] = AppearanceValidation.parseColourScheme(
                name, customs.map { it.id }.toSet()
            )
        }

    suspend fun setGlassStyle(style: GlassStyle) =
        store.edit { it[KEY_GLASS_STYLE] = style.name }

    suspend fun setTrueBlack(value: Boolean) =
        store.edit { it[KEY_TRUE_BLACK] = value }

    /** Replace the stored custom schemes (add/edit/remove all go
     * through here — the single writer). If the current selection
     * pointed at a spec that no longer exists, it falls back to
     * Ember on the next read (parseColourScheme validation). */
    suspend fun setCustomSchemes(schemes: List<CustomSchemeSpec>) =
        store.edit { it[KEY_CUSTOM_SCHEMES] = AppearanceValidation.encodeCustomSchemes(schemes) }

    suspend fun setBookColours(scope: BookColoursScope) =
        store.edit { it[KEY_BOOK_COLOURS] = scope.name }

    suspend fun setCoverGlow(value: Boolean) =
        store.edit { it[KEY_COVER_GLOW] = value }

    suspend fun setPerformanceMode(mode: PerformanceMode) =
        store.edit { it[KEY_PERF_MODE] = mode.name }

    suspend fun setAppTextSizePercent(value: Int) =
        store.edit { it[KEY_TEXT_SIZE] = AppearanceValidation.clampAppTextSize(value) }

    suspend fun setMeCardMotionTilt(value: Boolean) =
        store.edit { it[KEY_ME_MOTION_TILT] = value }

    /**
     * Reset customisation (R07): appearance, motion, and glass return
     * to defaults. Books, sources, repos, keys, shelves, tags, custom
     * schemes and progress are NOT touched — this resets how the app
     * looks, never what the user has. The primed scheme default is
     * re-primed to the same install-appropriate default.
     */
    suspend fun resetCustomisation() {
        store.edit { prefs ->
            prefs.remove(KEY_THEME_MODE)
            prefs.remove(KEY_GLASS_OPACITY)
            prefs.remove(KEY_GLASS_RADIUS)
            prefs.remove(KEY_GLASS_ELEVATION)
            prefs.remove(KEY_REDUCE_MOTION)
            prefs.remove(KEY_COLOUR_SCHEME)
            prefs.remove(KEY_GLASS_STYLE)
            prefs.remove(KEY_TRUE_BLACK)
            prefs.remove(KEY_BOOK_COLOURS)
            prefs.remove(KEY_COVER_GLOW)
            prefs.remove(KEY_PERF_MODE)
            prefs.remove(KEY_TEXT_SIZE)
            prefs.remove(KEY_ME_MOTION_TILT)
            prefs.remove(KEY_SCHEME_PRIMED)
        }
        primeDefaultScheme()
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("appearance.theme_mode")
        val KEY_GLASS_OPACITY = floatPreferencesKey("appearance.glass_opacity")
        val KEY_GLASS_RADIUS = floatPreferencesKey("appearance.glass_radius_scale")
        val KEY_GLASS_ELEVATION = floatPreferencesKey("appearance.glass_elevation_scale")
        val KEY_REDUCE_MOTION = booleanPreferencesKey("appearance.reduce_motion")
        val KEY_COLOUR_SCHEME = stringPreferencesKey("appearance.colour_scheme")
        val KEY_GLASS_STYLE = stringPreferencesKey("appearance.glass_style")
        val KEY_TRUE_BLACK = booleanPreferencesKey("appearance.true_black")
        val KEY_CUSTOM_SCHEMES = stringPreferencesKey("appearance.custom_schemes")
        val KEY_SCHEME_PRIMED = booleanPreferencesKey("appearance.scheme_primed")
        val KEY_BOOK_COLOURS = stringPreferencesKey("appearance.book_colours")
        val KEY_COVER_GLOW = booleanPreferencesKey("appearance.cover_glow")
        val KEY_PERF_MODE = stringPreferencesKey("appearance.performance_mode")
        val KEY_TEXT_SIZE = intPreferencesKey("appearance.app_text_size")
        val KEY_ME_MOTION_TILT = booleanPreferencesKey("appearance.me_motion_tilt")
    }
}

/** First-run onboarding completion (PLAN §3) — its own tiny owner on the
 * same settings file. */
class OnboardingPrefs(private val store: DataStore<Preferences>) {

    val completed: Flow<Boolean> = store.data.map { it[KEY_DONE] ?: false }

    suspend fun markCompleted() {
        store.edit { it[KEY_DONE] = true }
    }

    private companion object {
        val KEY_DONE = booleanPreferencesKey("onboarding.completed")
    }
}
