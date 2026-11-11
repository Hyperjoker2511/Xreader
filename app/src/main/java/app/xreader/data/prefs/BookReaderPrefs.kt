package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-book reader overrides (v1.0.2, R18's "This book / All books"
 * scope): a book may carry its OWN palette and text size — the two
 * things a reader genuinely varies per book (a scanned comic vs a
 * novel) — without touching the global reader settings. Everything
 * else stays global. Clearing an override returns the book to the
 * global look; the override store never syncs (it is device-local
 * presentation state).
 */
data class BookReaderOverride(
    val paletteName: String? = null,
    val fontSizeSp: Float? = null,
    /** Manga programme (slice M2): this series' own image-
     * reading mode, set from inside its reader (option-scope
     * rule tier 2). Null = the series follows the global
     * default in ReaderConfig.imageMode. */
    val imageMode: app.xreader.domain.reader.ImageReaderMode? = null,
    /** v1.5.0 (Part C): this series' own tap-zone layout
     * (Komikku parity: mode + orientation are the donor's
     * per-series flags; Xreader adds the tap layout and the
     * crop switch the in-reader sheet offers). Null = the
     * global default. */
    val mangaTapLayout: app.xreader.domain.reader.MangaTapLayout? = null,
    /** v1.5.0 (Part C): this series' own orientation. */
    val mangaOrientation: app.xreader.domain.reader.MangaOrientation? = null,
    /** v1.5.0 (Part C): this series' own border-crop switch
     * (applies to whichever mode family the series reads in). */
    val mangaCropBorders: Boolean? = null
) {
    val isEmpty: Boolean
        get() = paletteName == null && fontSizeSp == null && imageMode == null &&
            mangaTapLayout == null && mangaOrientation == null &&
            mangaCropBorders == null

    /** True when any MANGA override is set (the in-reader
     * sheet's "reset this series' settings" row keys on it). */
    val hasMangaOverrides: Boolean
        get() = imageMode != null || mangaTapLayout != null ||
            mangaOrientation != null || mangaCropBorders != null
}

/**
 * v1.5.8 (E3/I5): the pure exclusion rule both read paths share —
 * a chapter is filtered out exactly when its scanlator is in the
 * book's exclusion set. A null scanlator is never filtered (the
 * source credited nobody; hiding it would hide honest chapters).
 */
object ScanlatorFilter {
    fun isExcluded(scanlator: String?, excluded: Set<String>): Boolean =
        scanlator != null && scanlator in excluded

    fun <T> apply(chapters: List<T>, scanlatorOf: (T) -> String?, excluded: Set<String>): List<T> =
        if (excluded.isEmpty()) chapters
        else chapters.filter { !isExcluded(scanlatorOf(it), excluded) }
}

class BookReaderPrefs(private val store: DataStore<Preferences>) {

    fun overrides(bookId: Long): Flow<BookReaderOverride> = store.data.map { prefs ->
        BookReaderOverride(
            paletteName = prefs[paletteKey(bookId)],
            fontSizeSp = prefs[fontKey(bookId)],
            imageMode = prefs[imageModeKey(bookId)]?.let { stored ->
                app.xreader.domain.reader.ImageReaderMode.entries
                    .firstOrNull { it.name == stored }
            },
            mangaTapLayout = prefs[mangaTapLayoutKey(bookId)]?.let { stored ->
                app.xreader.domain.reader.MangaTapLayout.entries
                    .firstOrNull { it.name == stored }
            },
            mangaOrientation = prefs[mangaOrientationKey(bookId)]?.let { stored ->
                app.xreader.domain.reader.MangaOrientation.entries
                    .firstOrNull { it.name == stored }
            },
            mangaCropBorders = prefs[mangaCropKey(bookId)]
        )
    }

    suspend fun setPalette(bookId: Long, paletteName: String?) {
        store.edit { prefs ->
            if (paletteName == null) prefs.remove(paletteKey(bookId))
            else prefs[paletteKey(bookId)] = paletteName
        }
    }

    suspend fun setFontSize(bookId: Long, fontSizeSp: Float?) {
        store.edit { prefs ->
            if (fontSizeSp == null) prefs.remove(fontKey(bookId))
            else prefs[fontKey(bookId)] = fontSizeSp
        }
    }

    suspend fun setImageMode(
        bookId: Long,
        mode: app.xreader.domain.reader.ImageReaderMode?
    ) {
        store.edit { prefs ->
            if (mode == null) prefs.remove(imageModeKey(bookId))
            else prefs[imageModeKey(bookId)] = mode.name
        }
    }

    /** v1.5.0 (Part C): the in-reader manga sheet's writes —
     * one setter per per-series field (null clears back to
     * the global default). */
    suspend fun setMangaTapLayout(
        bookId: Long,
        layout: app.xreader.domain.reader.MangaTapLayout?
    ) {
        store.edit { prefs ->
            if (layout == null) prefs.remove(mangaTapLayoutKey(bookId))
            else prefs[mangaTapLayoutKey(bookId)] = layout.name
        }
    }

    suspend fun setMangaOrientation(
        bookId: Long,
        orientation: app.xreader.domain.reader.MangaOrientation?
    ) {
        store.edit { prefs ->
            if (orientation == null) prefs.remove(mangaOrientationKey(bookId))
            else prefs[mangaOrientationKey(bookId)] = orientation.name
        }
    }

    suspend fun setMangaCropBorders(bookId: Long, crop: Boolean?) {
        store.edit { prefs ->
            if (crop == null) prefs.remove(mangaCropKey(bookId))
            else prefs[mangaCropKey(bookId)] = crop
        }
    }

    /** v1.5.0 (Part C): clear ONLY the manga overrides (the
     * in-reader sheet's reset — the donor's ResetViewerFlags
     * shape); palette/text-size overrides are untouched. */
    suspend fun clearManga(bookId: Long) {
        store.edit { prefs ->
            prefs.remove(imageModeKey(bookId))
            prefs.remove(mangaTapLayoutKey(bookId))
            prefs.remove(mangaOrientationKey(bookId))
            prefs.remove(mangaCropKey(bookId))
        }
    }

    /**
     * v1.5.8 (E3): this book's excluded scanlators — the donor's
     * per-book chapter filter (ScanlatorFilterDialog). Chapters
     * whose scanlator is in this set are hidden from the book
     * page's chapter list and skipped by the manga reader's
     * chapter-change selection (I5). Empty set = nothing hidden.
     */
    fun excludedScanlators(bookId: Long): Flow<Set<String>> =
        store.data.map { prefs ->
            prefs[excludedScanlatorsKey(bookId)] ?: emptySet()
        }

    suspend fun setExcludedScanlators(bookId: Long, excluded: Set<String>) {
        store.edit { prefs ->
            if (excluded.isEmpty()) {
                prefs.remove(excludedScanlatorsKey(bookId))
            } else {
                prefs[excludedScanlatorsKey(bookId)] = excluded
            }
        }
    }

    suspend fun clear(bookId: Long) {
        store.edit { prefs ->
            prefs.remove(paletteKey(bookId))
            prefs.remove(fontKey(bookId))
            prefs.remove(imageModeKey(bookId))
            prefs.remove(mangaTapLayoutKey(bookId))
            prefs.remove(mangaOrientationKey(bookId))
            prefs.remove(mangaCropKey(bookId))
            prefs.remove(excludedScanlatorsKey(bookId))
        }
    }

    private fun paletteKey(bookId: Long) =
        stringPreferencesKey("bookreader.$bookId.palette")

    private fun fontKey(bookId: Long) =
        floatPreferencesKey("bookreader.$bookId.font_size")

    private fun imageModeKey(bookId: Long) =
        stringPreferencesKey("bookreader.$bookId.image_mode")

    private fun mangaTapLayoutKey(bookId: Long) =
        stringPreferencesKey("bookreader.$bookId.manga_tap_layout")

    private fun mangaOrientationKey(bookId: Long) =
        stringPreferencesKey("bookreader.$bookId.manga_orientation")

    private fun mangaCropKey(bookId: Long) =
        androidx.datastore.preferences.core.booleanPreferencesKey(
            "bookreader.$bookId.manga_crop"
        )

    private fun excludedScanlatorsKey(bookId: Long) =
        stringSetPreferencesKey("bookreader.$bookId.excluded_scanlators")
}
