package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.ai.GeminiModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which AI features exist (PLAN §7 — per-feature "AI off" toggles so
 * a user can keep summaries but silence lore, and quota burn stays
 * under his control). The Gemini key itself NEVER lives here — it is
 * in the KeyVault; this owner holds choices only. */
enum class AiFeature(val label: String) {
    SUMMARIZER("Chapter summaries"),
    LORE_KEEPER("LoreKeeper — characters & terms"),
    LORE_CHAT("Lore Chat"),
    CONCEPT_ART("Concept art"),
    RAW_TRANSLATION("RAW translation"),
    SELECTOR_AI("AI source detector (from link)"),

    /** v1.0.3 (AI hub): model-proposed tags on the book page. */
    TAG_SUGGESTIONS("AI tag suggestions"),

    /** v1.0.3 (AI hub): AI help spotting the same book during a
     * "Bring my library" import — suggestions only, always confirmed
     * by the user before anything is treated as a match. */
    LIBRARY_MATCH("AI book matching (Bring my library)")
}

/**
 * Which engine AI features run on (v1.0.3, the picking rule):
 * [Automatic] lets the hub route across every configured provider;
 * [Specific] pins ONE provider + model, and that pick is used as
 * picked — a failure is reported, never silently re-routed.
 */
sealed interface EnginePick {
    data object Automatic : EnginePick
    data class Specific(val providerId: String, val model: String) : EnginePick
}

data class AiSettings(
    /** [GeminiModels.AUTO] or one of [GeminiModels.CASCADE]. */
    val modelChoice: String = GeminiModels.AUTO,
    val features: Map<AiFeature, Boolean> = AiFeature.entries.associateWith { true },
    /** v1.0.3: the engine pick (see [EnginePick]). */
    val enginePick: EnginePick = EnginePick.Automatic,
    /** v1.0.3: Ollama (a model server on the user's own computer —
     * no key; the address + model name are plain settings). */
    val ollamaBaseUrl: String = DEFAULT_OLLAMA_URL,
    val ollamaModel: String = ""
) {
    fun enabled(feature: AiFeature): Boolean = features[feature] ?: true

    companion object {
        const val DEFAULT_OLLAMA_URL = "http://localhost:11434"
    }
}

/** The single writer/reader of AI choices (PLAN §1.2 rule). Stored
 * values are validated on read: an unknown model string falls back
 * to Automatic rather than breaking every AI call. */
class AiPrefs(private val store: DataStore<Preferences>) {

    val settings: Flow<AiSettings> = store.data.map { prefs ->
        AiSettings(
            modelChoice = prefs[KEY_MODEL]?.takeIf {
                it == GeminiModels.AUTO || GeminiModels.isKnownModel(it)
            } ?: GeminiModels.AUTO,
            features = AiFeature.entries.associateWith { feature ->
                prefs[featureKey(feature)] ?: true
            },
            enginePick = decodePick(
                prefs[KEY_ENGINE_PROVIDER], prefs[KEY_ENGINE_MODEL]
            ),
            ollamaBaseUrl = prefs[KEY_OLLAMA_URL]
                ?.takeIf { it.isNotBlank() } ?: AiSettings.DEFAULT_OLLAMA_URL,
            ollamaModel = prefs[KEY_OLLAMA_MODEL] ?: ""
        )
    }

    suspend fun setModelChoice(choice: String) {
        store.edit {
            it[KEY_MODEL] = if (choice == GeminiModels.AUTO || GeminiModels.isKnownModel(choice)) {
                choice
            } else {
                GeminiModels.AUTO
            }
        }
    }

    /** The engine pick is stored as two plain strings; a blank
     * provider means Automatic. The provider id is NOT validated
     * here — a user-added provider's id is only known to the hub,
     * which resolves (and plainly reports) a stale pick. */
    suspend fun setEnginePick(pick: EnginePick) {
        store.edit { prefs ->
            when (pick) {
                EnginePick.Automatic -> {
                    prefs.remove(KEY_ENGINE_PROVIDER)
                    prefs.remove(KEY_ENGINE_MODEL)
                }
                is EnginePick.Specific -> {
                    prefs[KEY_ENGINE_PROVIDER] = pick.providerId
                    prefs[KEY_ENGINE_MODEL] = pick.model
                }
            }
        }
    }

    suspend fun setOllama(baseUrl: String, model: String) {
        store.edit { prefs ->
            prefs[KEY_OLLAMA_URL] = baseUrl.trim().ifBlank {
                AiSettings.DEFAULT_OLLAMA_URL
            }
            prefs[KEY_OLLAMA_MODEL] = model.trim()
        }
    }

    suspend fun setFeatureEnabled(feature: AiFeature, enabled: Boolean) {
        store.edit { it[featureKey(feature)] = enabled }
    }

    private fun featureKey(feature: AiFeature) =
        booleanPreferencesKey("ai.feature.${feature.name.lowercase()}")

    private companion object {
        val KEY_MODEL = stringPreferencesKey("ai.model_choice")
        val KEY_ENGINE_PROVIDER = stringPreferencesKey("ai.engine_provider")
        val KEY_ENGINE_MODEL = stringPreferencesKey("ai.engine_model")
        val KEY_OLLAMA_URL = stringPreferencesKey("ai.ollama_url")
        val KEY_OLLAMA_MODEL = stringPreferencesKey("ai.ollama_model")

        fun decodePick(provider: String?, model: String?): EnginePick {
            val id = provider?.takeIf { it.isNotBlank() }
                ?: return EnginePick.Automatic
            return EnginePick.Specific(id, model ?: "")
        }
    }
}
