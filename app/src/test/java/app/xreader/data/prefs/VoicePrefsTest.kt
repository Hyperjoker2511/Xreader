package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * Patch 12 voice-choice storage pins: the global choice defaults
 * to the device voice, round-trips, and validates on read; the
 * per-book override round-trips through BookReaderPrefs and
 * counts toward the override's emptiness honestly.
 */
class VoicePrefsTest {

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

        fun seed(prefs: Preferences) {
            state.value = prefs
        }
    }

    @Test
    fun `global choice defaults to the device voice`() = runTest {
        val prefs = VoicePrefs(FakeDataStore())
        assertEquals(VoiceEngine.DEVICE, prefs.settings.first().engine)
    }

    @Test
    fun `global choice round-trips`() = runTest {
        val prefs = VoicePrefs(FakeDataStore())
        prefs.setEngine(VoiceEngine.EDGE)
        assertEquals(VoiceEngine.EDGE, prefs.settings.first().engine)
        prefs.setEngine(VoiceEngine.DEVICE)
        assertEquals(VoiceEngine.DEVICE, prefs.settings.first().engine)
    }

    @Test
    fun `an unknown stored engine falls back to the device voice`() = runTest {
        val store = FakeDataStore()
        store.seed(
            mutablePreferencesOf(stringPreferencesKey("voice.engine") to "KOKORO")
        )
        val prefs = VoicePrefs(store)
        assertEquals(VoiceEngine.DEVICE, prefs.settings.first().engine)
    }

    @Test
    fun `per-book voice override round-trips and clears`() = runTest {
        val prefs = BookReaderPrefs(FakeDataStore())
        assertTrue(prefs.overrides(7L).first().isEmpty)

        prefs.setVoice(7L, VoiceEngine.EDGE, "ko-KR-InJoonNeural")
        val set = prefs.overrides(7L).first()
        assertEquals(VoiceEngine.EDGE, set.voiceEngine)
        assertEquals("ko-KR-InJoonNeural", set.edgeVoiceId)
        assertFalse(set.isEmpty)

        // Another book is untouched.
        assertTrue(prefs.overrides(8L).first().isEmpty)

        prefs.setVoice(7L, null, null)
        val cleared = prefs.overrides(7L).first()
        assertNull(cleared.voiceEngine)
        assertNull(cleared.edgeVoiceId)
        assertTrue(cleared.isEmpty)
    }

    @Test
    fun `an unknown stored per-book engine reads as no override`() = runTest {
        val store = FakeDataStore()
        store.seed(
            mutablePreferencesOf(stringPreferencesKey("voice_engine.7") to "FUTURE")
        )
        val prefs = BookReaderPrefs(store)
        assertNull(prefs.overrides(7L).first().voiceEngine)
    }
}
