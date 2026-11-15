package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Which voice reads chapters aloud (Patch 12). [DEVICE] is the
 * default and never leaves the phone. [EDGE] is Microsoft's Edge
 * read-aloud voices — free and keyless, but chapter text is sent
 * to Microsoft to be read, so it is strictly opt-in. (An offline
 * downloaded pack is designed in PATCH-SPEC-EDGE-TTS §D.4 for a
 * future patch; it deliberately has NO value here — a half-wired
 * surface never ships.)
 */
enum class VoiceEngine(val label: String) {
    DEVICE("Device voice"),
    EDGE("Online voice")
}

data class VoiceSettings(
    val engine: VoiceEngine = VoiceEngine.DEVICE
)

/** The single writer/reader of the voice choice, in the AiPrefs
 * pattern: the enum is stored by name and validated on read — an
 * unknown stored value falls back to the device voice rather
 * than breaking read-aloud. */
class VoicePrefs(private val store: DataStore<Preferences>) {

    val settings: Flow<VoiceSettings> = store.data.map { prefs ->
        VoiceSettings(
            engine = prefs[KEY_ENGINE]?.let { stored ->
                VoiceEngine.entries.firstOrNull { it.name == stored }
            } ?: VoiceEngine.DEVICE
        )
    }

    suspend fun setEngine(engine: VoiceEngine) {
        store.edit { it[KEY_ENGINE] = engine.name }
    }

    private companion object {
        val KEY_ENGINE = stringPreferencesKey("voice.engine")
    }
}
