package app.xreader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.xreader.data.ai.hub.CustomProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * User-added AI providers and per-provider model memory (v1.0.3, AI
 * hub). Lives in the settings DataStore next to [AiPrefs] — choices
 * and public model lists only: **keys never live here** (they are in
 * the KeyVault, one entry per provider id).
 *
 * Everything is one JSON document so a provider, its known models
 * and the user's model choice are written atomically — the provider
 * list can never show a provider whose models were lost, or the
 * reverse.
 */
@Serializable
data class AiProviderData(
    val customs: List<CustomProvider> = emptyList(),
    /** providerId → model ids the provider itself listed. */
    val models: Map<String, List<String>> = emptyMap(),
    /** providerId → the subset of [models] the provider lists as free. */
    val freeModels: Map<String, List<String>> = emptyMap(),
    /** providerId → the model the user chose for Automatic routing. */
    val chosenModels: Map<String, String> = emptyMap()
)

class AiProviderPrefs(private val store: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    val data: Flow<AiProviderData> = store.data.map { prefs ->
        prefs[KEY_DATA]?.let { raw ->
            runCatching { json.decodeFromString<AiProviderData>(raw) }
                .getOrNull()
        } ?: AiProviderData()
    }

    suspend fun addCustom(provider: CustomProvider) {
        update { current ->
            current.copy(
                customs = current.customs.filter { it.id != provider.id } + provider
            )
        }
    }

    /** Removes the provider AND every trace of its models/choices.
     * (The caller removes the KeyVault key — vault and prefs stay
     * separate owners, as everywhere else in the app.) */
    suspend fun removeCustom(providerId: String) {
        update { current ->
            current.copy(
                customs = current.customs.filter { it.id != providerId },
                models = current.models - providerId,
                freeModels = current.freeModels - providerId,
                chosenModels = current.chosenModels - providerId
            )
        }
    }

    /** Remembers a provider's own model list (from its /models
     * answer), and which of those it lists as free. */
    suspend fun setModels(
        providerId: String,
        models: List<String>,
        freeModels: List<String>
    ) {
        update { current ->
            current.copy(
                models = current.models + (providerId to models),
                freeModels = current.freeModels + (providerId to freeModels)
            )
        }
    }

    suspend fun setChosenModel(providerId: String, model: String) {
        update { current ->
            current.copy(
                chosenModels = current.chosenModels + (providerId to model)
            )
        }
    }

    private suspend fun update(transform: (AiProviderData) -> AiProviderData) {
        store.edit { prefs ->
            val current = prefs[KEY_DATA]?.let { raw ->
                runCatching { json.decodeFromString<AiProviderData>(raw) }
                    .getOrNull()
            } ?: AiProviderData()
            prefs[KEY_DATA] = json.encodeToString(
                AiProviderData.serializer(), transform(current)
            )
        }
    }

    private companion object {
        val KEY_DATA = stringPreferencesKey("ai.providers.v1")
    }
}
