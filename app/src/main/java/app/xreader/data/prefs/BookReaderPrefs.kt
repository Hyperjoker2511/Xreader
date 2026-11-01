package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
    val imageMode: app.xreader.domain.reader.ImageReaderMode? = null
) {
    val isEmpty: Boolean
        get() = paletteName == null && fontSizeSp == null && imageMode == null
}

class BookReaderPrefs(private val store: DataStore<Preferences>) {

    fun overrides(bookId: Long): Flow<BookReaderOverride> = store.data.map { prefs ->
        BookReaderOverride(
            paletteName = prefs[paletteKey(bookId)],
            fontSizeSp = prefs[fontKey(bookId)],
            imageMode = prefs[imageModeKey(bookId)]?.let { stored ->
                app.xreader.domain.reader.ImageReaderMode.entries
                    .firstOrNull { it.name == stored }
            }
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

    suspend fun clear(bookId: Long) {
        store.edit { prefs ->
            prefs.remove(paletteKey(bookId))
            prefs.remove(fontKey(bookId))
            prefs.remove(imageModeKey(bookId))
        }
    }

    private fun paletteKey(bookId: Long) =
        stringPreferencesKey("bookreader.$bookId.palette")

    private fun fontKey(bookId: Long) =
        floatPreferencesKey("bookreader.$bookId.font_size")

    private fun imageModeKey(bookId: Long) =
        stringPreferencesKey("bookreader.$bookId.image_mode")
}
