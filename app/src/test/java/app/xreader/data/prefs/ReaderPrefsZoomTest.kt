package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 (WS2) pins for the manga-zoom flags in [ReaderPrefs]:
 * an unset store reads Mihon's defaults (double-tap on, zoom
 * out allowed), an explicit update round-trips through the
 * DataStore, and reset restores the defaults. Without these the
 * settings switches could render one value while the reader
 * used another.
 */
class ReaderPrefsZoomTest {

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

    @Test
    fun unsetStoreReadsZoomDefaults() = runTest {
        val prefs = ReaderPrefs(FakeDataStore())
        val config = prefs.config.first()
        assertTrue(config.doubleTapToZoom)
        assertFalse(config.disableZoomOut)
    }

    @Test
    fun zoomFlagsRoundTripThroughTheStore() = runTest {
        val prefs = ReaderPrefs(FakeDataStore())
        prefs.update {
            it.copy(doubleTapToZoom = false, disableZoomOut = true)
        }
        val stored = prefs.config.first()
        assertFalse(stored.doubleTapToZoom)
        assertTrue(stored.disableZoomOut)

        prefs.update {
            it.copy(doubleTapToZoom = true, disableZoomOut = false)
        }
        val restored = prefs.config.first()
        assertTrue(restored.doubleTapToZoom)
        assertFalse(restored.disableZoomOut)
    }

    @Test
    fun resetToDefaultsRestoresZoomDefaults() = runTest {
        val prefs = ReaderPrefs(FakeDataStore())
        prefs.update {
            it.copy(doubleTapToZoom = false, disableZoomOut = true)
        }
        prefs.resetToDefaults()
        val config = prefs.config.first()
        assertTrue(config.doubleTapToZoom)
        assertFalse(config.disableZoomOut)
    }
}
