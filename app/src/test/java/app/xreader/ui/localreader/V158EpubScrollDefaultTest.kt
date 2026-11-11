package app.xreader.ui.localreader

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.8 (G1): EPUB opens as a scrolling book — and the ONE-TIME
 * UPGRADE RULE is load-bearing. Cases exactly per plan:
 * (i) fresh prefs → submitted EpubPreferences.scroll == true
 *     (asserted against the REAL preferences the reader builds
 *     through a real Readium session);
 * (ii) a stored `false` written by the OLD build's snapshot
 *     path (the shipped saveEpub wrote the key on ANY settings
 *     change) with the default-version marker ABSENT → first
 *     1.5.8 access clears it, writes the marker, scroll == true;
 * (iii) a `false` stored WITH the marker present (a genuine
 *     1.5.8 choice) → still false (the persistence fence).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V158EpubScrollDefaultTest {

    @get:Rule val temp = TemporaryFolder()

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

        fun seed(values: Map<String, Any>) {
            val editable = emptyPreferences().toMutablePreferences()
            for ((name, value) in values) {
                when (value) {
                    is Boolean -> editable[booleanPreferencesKey(name)] = value
                    is Int -> editable[intPreferencesKey(name)] = value
                    is String -> editable[stringPreferencesKey(name)] = value
                    is Double -> editable[doublePreferencesKey(name)] = value
                    is Long -> editable[longPreferencesKey(name)] = value
                    is Float -> editable[floatPreferencesKey(name)] = value
                }
            }
            state.value = editable.toPreferences()
        }

        fun rawValue(name: String): Any? = state.value.asMap()
            .entries.firstOrNull { it.key.name == name }?.value
    }

    @Test
    fun `i - fresh prefs submit scroll true`() = runBlocking {
        val root = temp.newFolder("files")
        val session = LocalReaderEpubFixture.openSession(
            root, LocalReaderEpubFixture.writeEpub(root)
        )
        try {
            val settings = LocalReaderPrefs(FakeDataStore())
                .epubSettings(bookId = 7L).first()
            assertTrue(settings.scroll)
            val submitted = settings.toEpubPreferences(session)
            assertEquals(true, submitted.scroll)
        } finally {
            session.close()
        }
    }

    @Test
    fun `ii - pre-158 snapshot false without marker is cleared once`() = runTest {
        val store = FakeDataStore()
        // Model the shipped v1.5.7 saveEpub output: a settings
        // snapshot that wrote scroll=false (the default then)
        // alongside an unrelated change — marker ABSENT.
        store.seed(
            mapOf(
                "localreader.7.epub.scroll" to false,
                "localreader.7.epub.font_size" to 1.2,
                "localreader.9.epub.scroll" to false
            )
        )
        val prefs = LocalReaderPrefs(store)
        val settings = prefs.epubSettings(bookId = 7L).first()
        assertTrue("cleared snapshot must read as the new default", settings.scroll)
        // ALL stored scroll values were cleared, not just this book's.
        assertEquals(null, store.rawValue("localreader.7.epub.scroll"))
        assertEquals(null, store.rawValue("localreader.9.epub.scroll"))
        // Unrelated keys untouched; the marker is written.
        assertEquals(1.2, store.rawValue("localreader.7.epub.font_size"))
        assertEquals(
            LocalReaderPrefs.SCROLL_DEFAULT_VERSION_VALUE,
            store.rawValue("localreader.epub.scrollDefaultVersion")
        )
        // A genuine choice made AFTER the upgrade persists.
        prefs.saveEpub(7L, settings.copy(scroll = false))
        val reread = LocalReaderPrefs(store).epubSettings(7L).first()
        assertFalse(reread.scroll)
    }

    @Test
    fun `iii - false stored with the marker present is a genuine choice`() = runTest {
        val store = FakeDataStore()
        store.seed(
            mapOf(
                "localreader.epub.scrollDefaultVersion" to
                    LocalReaderPrefs.SCROLL_DEFAULT_VERSION_VALUE,
                "localreader.7.epub.scroll" to false
            )
        )
        val prefs = LocalReaderPrefs(store)
        assertFalse(prefs.epubSettings(bookId = 7L).first().scroll)
        // And it survives a fresh prefs instance (no re-clear).
        assertFalse(LocalReaderPrefs(store).epubSettings(7L).first().scroll)
    }
}
