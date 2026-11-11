package app.xreader.ui.localreader

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
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
import org.readium.r2.navigator.preferences.TextAlign
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.8 (G6): justify + hyphenation are wired to Readium's
 * standard preferences — justify defaults ON, both persist
 * per book.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V158JustifyHyphensTest {

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
    }

    @Test
    fun `fresh prefs submit JUSTIFY and hyphens on`() = runBlocking {
        val root = temp.newFolder("files")
        val session = LocalReaderEpubFixture.openSession(
            root, LocalReaderEpubFixture.writeEpub(root)
        )
        try {
            val settings = LocalReaderPrefs(FakeDataStore()).epubSettings(7L).first()
            val submitted = settings.toEpubPreferences(session)
            assertEquals(TextAlign.JUSTIFY, submitted.textAlign)
            assertEquals(true, submitted.hyphens)
        } finally {
            session.close()
        }
    }

    @Test
    fun `toggling persists per book`() = runTest {
        val store = FakeDataStore()
        val prefs = LocalReaderPrefs(store)
        prefs.saveEpub(4L, EpubBookSettings(justify = false, hyphens = false))
        val book4 = prefs.epubSettings(4L).first()
        assertFalse(book4.justify)
        assertFalse(book4.hyphens)
        // Another book is untouched (per-book scope).
        val book5 = prefs.epubSettings(5L).first()
        assertTrue(book5.justify)
        assertTrue(book5.hyphens)
    }
}
