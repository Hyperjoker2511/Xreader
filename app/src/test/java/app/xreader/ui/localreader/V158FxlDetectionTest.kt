package app.xreader.ui.localreader

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.8 (G10): fixed-layout detection from the publication's
 * own metadata (rendition:layout = pre-paginated) at open —
 * labelled fixed-layout; a reflowable book is untouched. The
 * paginated default applies only when the reader has never
 * stored a scroll choice for the book (the "no stored choice
 * → paginated" half of the rule lives on the same
 * [LocalReaderPrefs.hasStoredEpubScroll] contract G1's
 * upgrade maintains).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V158FxlDetectionTest {

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
    fun `pre-paginated publication is detected as fixed layout`() = runBlocking {
        val root = temp.newFolder("files")
        val session = LocalReaderEpubFixture.openSession(
            root, LocalReaderEpubFixture.writeEpub(root, fixedLayout = true)
        )
        try {
            assertTrue(session.isFixedLayout)
        } finally {
            session.close()
        }
    }

    @Test
    fun `reflowable publication is not fixed layout`() = runBlocking {
        val root = temp.newFolder("files")
        val session = LocalReaderEpubFixture.openSession(
            root, LocalReaderEpubFixture.writeEpub(root, fixedLayout = false)
        )
        try {
            assertFalse(session.isFixedLayout)
        } finally {
            session.close()
        }
    }

    @Test
    fun `stored scroll choice suppresses the paginated default`() = runTest {
        val prefs = LocalReaderPrefs(FakeDataStore())
        assertFalse(prefs.hasStoredEpubScroll(9L))
        prefs.saveEpub(9L, EpubBookSettings(scroll = true))
        assertTrue(prefs.hasStoredEpubScroll(9L))
    }
}
