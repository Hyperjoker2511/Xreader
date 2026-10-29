package app.xreader.data.ai.hub

import app.xreader.data.prefs.AiProviderData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-provider document codec (v1.0.3): custom providers, model
 * lists and choices round-trip through the one JSON document
 * [app.xreader.data.prefs.AiProviderPrefs] stores — and an empty or
 * legacy document decodes to the empty state, never a crash.
 */
class AiProviderPrefsCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `provider data round trips`() {
        val data = AiProviderData(
            customs = listOf(
                CustomProvider(
                    id = "custom-123",
                    name = "Openrouter.ai",
                    baseUrl = "https://openrouter.ai/api/v1",
                    protocol = AiProtocol.OPENAI_COMPATIBLE
                ),
                CustomProvider(
                    id = "custom-456",
                    name = "Claude-gateway.example",
                    baseUrl = "https://claude-gateway.example/v1",
                    protocol = AiProtocol.ANTHROPIC
                )
            ),
            models = mapOf("custom-123" to listOf("a/b", "c/d:free")),
            freeModels = mapOf("custom-123" to listOf("c/d:free")),
            chosenModels = mapOf("custom-123" to "c/d:free")
        )
        val encoded = json.encodeToString(AiProviderData.serializer(), data)
        val decoded = json.decodeFromString<AiProviderData>(encoded)
        assertEquals(data, decoded)
        // The registry resolves the decoded customs into defs whose
        // keys live under the per-provider vault names.
        val def = AiProviders.defFor("custom-123", decoded.customs)
        assertEquals("provider_key_custom-123", def?.keyVaultName)
        assertEquals(AiProtocol.ANTHROPIC, decoded.customs[1].protocol)
    }

    @Test
    fun `empty document decodes to the empty state`() {
        val decoded = json.decodeFromString<AiProviderData>("{}")
        assertEquals(AiProviderData(), decoded)
        assertTrue(decoded.customs.isEmpty())
    }
}
