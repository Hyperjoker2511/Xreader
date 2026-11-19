package app.xreader.ui.localreader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.7 Patch 3 (Item 6): the prepare record's encoding —
 * strict decode (anything malformed reads as NO record: the
 * book simply prepares again), the round trip, and the
 * per-book store.
 */
class Patch3PrepareRecordTest {

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

    private val record = EpubPrepareRecord(
        fingerprint = "a".repeat(64),
        loaderVersion = 1,
        sectionMap = mapOf(0 to 501L, 2 to 503L, 5 to 506L),
        notePending = true
    )

    @Test
    fun `encode decode round-trips`() {
        assertEquals(record, decodeEpubPrepareRecord(encodeEpubPrepareRecord(record)))
    }

    @Test
    fun `empty map and no note round-trip`() {
        val bare = EpubPrepareRecord(fingerprint = "b".repeat(64), loaderVersion = 1)
        assertEquals(bare, decodeEpubPrepareRecord(encodeEpubPrepareRecord(bare)))
    }

    @Test
    fun `decode rejects malformed input`() {
        assertNull(decodeEpubPrepareRecord(null))
        assertNull(decodeEpubPrepareRecord(""))
        assertNull(decodeEpubPrepareRecord("garbage"))
        // Wrong part count.
        assertNull(decodeEpubPrepareRecord("1|abc|0"))
        assertNull(decodeEpubPrepareRecord("1|abc|0|0:5|extra"))
        // Bad version.
        assertNull(decodeEpubPrepareRecord("x|abc|0|"))
        assertNull(decodeEpubPrepareRecord("0|abc|0|"))
        assertNull(decodeEpubPrepareRecord("-1|abc|0|"))
        // Blank fingerprint.
        assertNull(decodeEpubPrepareRecord("1||0|"))
        // Bad note flag.
        assertNull(decodeEpubPrepareRecord("1|abc|2|"))
        assertNull(decodeEpubPrepareRecord("1|abc|yes|"))
        // Bad map entries.
        assertNull(decodeEpubPrepareRecord("1|abc|0|0"))
        assertNull(decodeEpubPrepareRecord("1|abc|0|0:x"))
        assertNull(decodeEpubPrepareRecord("1|abc|0|x:5"))
        assertNull(decodeEpubPrepareRecord("1|abc|0|-1:5"))
        assertNull(decodeEpubPrepareRecord("1|abc|0|0:0"))
        assertNull(decodeEpubPrepareRecord("1|abc|0|0:5,"))
    }

    @Test
    fun `store round-trips the record per book`() = runTest {
        val prefs = LocalReaderPrefs(FakeDataStore())
        assertNull(prefs.epubPrepareRecord(7L))
        prefs.saveEpubPrepareRecord(7L, record)
        assertEquals(record, prefs.epubPrepareRecord(7L))
        // Another book's record never leaks across.
        assertNull(prefs.epubPrepareRecord(8L))
        // Clearing the note flag rewrites in place.
        prefs.saveEpubPrepareRecord(7L, record.copy(notePending = false))
        assertEquals(
            record.copy(notePending = false),
            prefs.epubPrepareRecord(7L)
        )
    }
}
