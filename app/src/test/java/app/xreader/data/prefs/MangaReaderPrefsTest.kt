package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.domain.reader.ImageReaderMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manga programme (slice M2) pins for the reading mode's two
 * storage tiers (the option-scope rule): the global default
 * lives in [ReaderPrefs] (ReaderConfig.imageMode, Webtoon
 * when unset), and a series' own mode lives in
 * [BookReaderPrefs] as an override — isolated per book,
 * cleared with the book's other overrides, and never written
 * by the global path.
 */
class MangaReaderPrefsTest {

    private class FakeDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    // ── Global tier (ReaderPrefs) ───────────────────────────

    @Test
    fun globalDefaultIsWebtoonOnAnUnsetStore() = runTest {
        val prefs = ReaderPrefs(FakeDataStore())
        assertEquals(ImageReaderMode.WEBTOON, prefs.config.first().imageMode)
    }

    @Test
    fun globalModeRoundTripsThroughTheStore() = runTest {
        val prefs = ReaderPrefs(FakeDataStore())
        prefs.update { it.copy(imageMode = ImageReaderMode.PAGED) }
        assertEquals(ImageReaderMode.PAGED, prefs.config.first().imageMode)
        prefs.update { it.copy(imageMode = ImageReaderMode.WEBTOON) }
        assertEquals(ImageReaderMode.WEBTOON, prefs.config.first().imageMode)
    }

    @Test
    fun globalResetRestoresWebtoon() = runTest {
        val prefs = ReaderPrefs(FakeDataStore())
        prefs.update { it.copy(imageMode = ImageReaderMode.PAGED) }
        prefs.resetToDefaults()
        assertEquals(ImageReaderMode.WEBTOON, prefs.config.first().imageMode)
    }

    // ── Per-series tier (BookReaderPrefs) ───────────────────

    @Test
    fun seriesOverrideRoundTrips() = runTest {
        val prefs = BookReaderPrefs(FakeDataStore())
        assertNull(prefs.overrides(7L).first().imageMode)
        prefs.setImageMode(7L, ImageReaderMode.PAGED)
        assertEquals(ImageReaderMode.PAGED, prefs.overrides(7L).first().imageMode)
        prefs.setImageMode(7L, null)
        assertNull(prefs.overrides(7L).first().imageMode)
    }

    @Test
    fun seriesOverridesAreIsolatedByBook() = runTest {
        val prefs = BookReaderPrefs(FakeDataStore())
        prefs.setImageMode(7L, ImageReaderMode.PAGED)
        assertNull(prefs.overrides(8L).first().imageMode)
        assertEquals(ImageReaderMode.PAGED, prefs.overrides(7L).first().imageMode)
    }

    @Test
    fun seriesOverrideCountsAndClearsWithTheOtherOverrides() = runTest {
        val prefs = BookReaderPrefs(FakeDataStore())
        assertTrue(prefs.overrides(7L).first().isEmpty)
        prefs.setImageMode(7L, ImageReaderMode.PAGED)
        assertFalse(prefs.overrides(7L).first().isEmpty)
        prefs.clear(7L)
        assertTrue(prefs.overrides(7L).first().isEmpty)
    }

    @Test
    fun seriesOverrideLeavesPaletteAndFontAlone() = runTest {
        val prefs = BookReaderPrefs(FakeDataStore())
        prefs.setPalette(7L, "Sepia")
        prefs.setImageMode(7L, ImageReaderMode.PAGED)
        val stored = prefs.overrides(7L).first()
        assertEquals("Sepia", stored.paletteName)
        assertEquals(ImageReaderMode.PAGED, stored.imageMode)
    }
}
