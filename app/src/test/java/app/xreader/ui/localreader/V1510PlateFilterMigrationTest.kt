package app.xreader.ui.localreader

import android.app.Application
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.fixtures.FakePreferencesDataStore
import app.xreader.fixtures.LegacyShelf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.5.10 (E1, build-map Group E): the one-time plate-filter
 * migration. A stored DARKEN from the pre-1.5.9 shipped
 * default is CLEARED once (marker
 * `localreader.epub.plateFilterVersion` absent → cleared +
 * marker written); every other stored value is byte-identical
 * afterwards; a DARKEN stored AFTER the marker is a genuine
 * per-book choice and survives. The LegacyShelf leg drives
 * the L-FIX shelf's stored-DARKEN EPUB book (BOOK_EPUB_D3)
 * through the real first-access seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1510PlateFilterMigrationTest {

    private fun plateKey(bookId: Long) =
        stringPreferencesKey("localreader.$bookId.epub.plate_filter")

    private suspend fun FakePreferencesDataStore.raw(name: String): Any? =
        snapshot().asMap().entries
            .firstOrNull { it.key.name == name }?.value

    @Test
    fun `darken cleared once, other values byte-identical, marker written`() =
        runTest {
            val store = FakePreferencesDataStore()
            store.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    this[plateKey(11L)] = "INVERT"
                    this[plateKey(12L)] = "ORIGINAL"
                    this[plateKey(14L)] = "DARKEN"
                    this[plateKey(15L)] = "DARKEN"
                    // book 13: plate_filter absent entirely.
                    this[doublePreferencesKey(
                        "localreader.14.epub.font_size"
                    )] = 1.3
                }.toPreferences()
            }
            val prefs = LocalReaderPrefs(store)

            // First access (any book) runs the migration.
            val settings14 = prefs.epubSettings(14L).first()
            assertEquals(
                "a cleared DARKEN falls back to the fresh default",
                EpubPlateFilter.ORIGINAL, settings14.plateFilter
            )
            assertNull(store.raw("localreader.14.epub.plate_filter"))
            assertNull(store.raw("localreader.15.epub.plate_filter"))
            // Non-DARKEN values are byte-identical, untouched.
            assertEquals("INVERT", store.raw("localreader.11.epub.plate_filter"))
            assertEquals("ORIGINAL", store.raw("localreader.12.epub.plate_filter"))
            assertEquals(1.3, store.raw("localreader.14.epub.font_size"))
            // The marker is written.
            assertEquals(
                LocalReaderPrefs.PLATE_FILTER_VERSION_VALUE,
                store.raw("localreader.epub.plateFilterVersion")
            )
            // Reads agree with the raw store.
            assertEquals(
                EpubPlateFilter.INVERT,
                prefs.epubSettings(11L).first().plateFilter
            )
            assertEquals(
                EpubPlateFilter.ORIGINAL,
                prefs.epubSettings(13L).first().plateFilter
            )
        }

    @Test
    fun `second access is a no-op and post-marker darken survives`() =
        runTest {
            val store = FakePreferencesDataStore()
            store.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    this[plateKey(21L)] = "DARKEN"
                }.toPreferences()
            }
            LocalReaderPrefs(store).epubSettings(21L).first()
            assertNull(store.raw("localreader.21.epub.plate_filter"))

            // A deliberate DARKEN stored AFTER the marker…
            store.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    this[plateKey(21L)] = "DARKEN"
                }.toPreferences()
            }
            // …survives a fresh instance's first access (the
            // marker, not the instance flag, governs).
            val again = LocalReaderPrefs(store).epubSettings(21L).first()
            assertEquals(EpubPlateFilter.DARKEN, again.plateFilter)
            assertEquals("DARKEN", store.raw("localreader.21.epub.plate_filter"))
        }

    @Test
    fun `legacy shelf stored-darken book migrates on first access`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val shelf = LegacyShelf.build(context)
            try {
                // Raw pre-migration state, asserted BEFORE any
                // production first access can run (the fixture's
                // own instruction).
                val before = shelf.settingsStore.snapshot()
                assertEquals(
                    "DARKEN",
                    before.asMap().entries.firstOrNull {
                        it.key.name ==
                            "localreader.${LegacyShelf.BOOK_EPUB_D3}.epub.plate_filter"
                    }?.value
                )
                assertFalse(
                    "no plate-filter marker in the legacy state",
                    before.asMap().keys.any {
                        it.name == "localreader.epub.plateFilterVersion"
                    }
                )
                val settings = LocalReaderPrefs(shelf.settingsStore)
                    .epubSettings(LegacyShelf.BOOK_EPUB_D3).first()
                assertEquals(EpubPlateFilter.ORIGINAL, settings.plateFilter)
                val after = shelf.settingsStore.snapshot()
                assertNull(
                    after.asMap().entries.firstOrNull {
                        it.key.name ==
                            "localreader.${LegacyShelf.BOOK_EPUB_D3}.epub.plate_filter"
                    }
                )
                assertEquals(
                    LocalReaderPrefs.PLATE_FILTER_VERSION_VALUE,
                    after.asMap().entries.firstOrNull {
                        it.key.name == "localreader.epub.plateFilterVersion"
                    }?.value
                )
            } finally {
                shelf.db.close()
            }
        }
}
