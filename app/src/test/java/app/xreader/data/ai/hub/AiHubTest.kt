package app.xreader.data.ai.hub

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.security.KeyVault
import app.xreader.core.security.SecretCipher
import app.xreader.data.FakeTransport
import app.xreader.data.ai.GeminiClient
import app.xreader.data.ai.GeminiModels
import app.xreader.data.prefs.AiPrefs
import app.xreader.data.prefs.AiProviderPrefs
import app.xreader.data.prefs.EnginePick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub end-to-end (v1.0.3), on the JVM with an in-memory
 * DataStore, a fake cipher and a scripted transport:
 *  - Automatic routes to a configured provider and NAMES it;
 *  - Automatic steps past a failing provider to the next one;
 *  - a Specific pick that fails returns ITS failure — the other
 *    provider is never even called (no silent switch);
 *  - a Specific pick of a removed provider is a plain error;
 *  - hasKey follows the pick, not just any saved key.
 */
class AiHubTest {

    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(0x5A) + plain
        override fun decrypt(data: ByteArray): ByteArray {
            require(data.isNotEmpty() && data[0] == 0x5A.toByte()) { "bad tag" }
            return data.copyOfRange(1, data.size)
        }
    }

    private class FakeStore : DataStore<Preferences> {
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

    private class Rig(
        val transport: FakeTransport,
        val vault: KeyVault,
        val aiPrefs: AiPrefs,
        val hub: AiHub
    )

    private fun rig(
        handler: suspend (app.xreader.data.net.HttpTransport.Request) ->
            AppResult<app.xreader.data.net.HttpTransport.Response>
    ): Rig {
        val transport = FakeTransport(handler)
        val vault = KeyVault(FakeStore(), FakeCipher())
        val settingsStore = FakeStore()
        val aiPrefs = AiPrefs(settingsStore)
        val providerPrefs = AiProviderPrefs(settingsStore)
        val hub = AiHub(
            transport = transport,
            keyVault = vault,
            aiPrefs = aiPrefs,
            providerPrefs = providerPrefs,
            geminiFactory = { pickerOverride ->
                GeminiClient(
                    transport = transport,
                    keyProvider = { vault.get(KeyVault.GEMINI_KEY) },
                    pickerProvider = { pickerOverride ?: GeminiModels.AUTO }
                )
            }
        )
        return Rig(transport, vault, aiPrefs, hub)
    }

    private fun geminiBody(text: String): String =
        """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""

    private fun openAiBody(text: String): String =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}"""

    @Test
    fun `automatic answers from the configured provider and names it`() = runBlocking {
        val rig = rig { request ->
            when {
                request.url.contains("api.openai.com") ->
                    FakeTransport.ok(openAiBody("from openai"))
                else -> AppResult.Ok(FakeTransport.response(500, "unused"))
            }
        }
        rig.vault.put(KeyVault.OPENAI_KEY, "sk-test")
        val result = rig.hub.client.generateText("hello")
        assertTrue(result is AppResult.Ok)
        val ok = (result as AppResult.Ok).value
        assertEquals("from openai", ok.text)
        assertTrue(ok.modelUsed.startsWith("OpenAI · "))
    }

    @Test
    fun `automatic steps past a failing provider to the next`() = runBlocking {
        val rig = rig { request ->
            when {
                request.url.contains("generativelanguage") ->
                    AppResult.Ok(FakeTransport.response(
                        500, """{"error":{"message":"boom"}}"""
                    ))
                request.url.contains("api.openai.com") ->
                    FakeTransport.ok(openAiBody("second answer"))
                else -> AppResult.Ok(FakeTransport.response(404, "nope"))
            }
        }
        rig.vault.put(KeyVault.GEMINI_KEY, "gemini-key")
        rig.vault.put(KeyVault.OPENAI_KEY, "sk-test")
        val result = rig.hub.client.generateText("hello")
        assertTrue(result is AppResult.Ok)
        assertEquals("second answer", (result as AppResult.Ok).value.text)
    }

    @Test
    fun `specific pick failure is returned and the other provider is never called`() =
        runBlocking {
            val rig = rig { request ->
                when {
                    request.url.contains("api.openai.com") ->
                        AppResult.Ok(FakeTransport.response(
                            401, """{"error":{"message":"bad key"}}"""
                        ))
                    else -> FakeTransport.ok(geminiBody("gemini answer"))
                }
            }
            rig.vault.put(KeyVault.GEMINI_KEY, "gemini-key")
            rig.vault.put(KeyVault.OPENAI_KEY, "sk-wrong")
            rig.aiPrefs.setEnginePick(
                EnginePick.Specific(AiProviders.OPENAI_ID, "gpt-5-mini")
            )
            val result = rig.hub.client.generateText("hello")
            assertTrue(result is AppResult.Err)
            assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
            assertTrue(
                "a silent switch would have called Gemini",
                rig.transport.requests.none {
                    it.url.contains("generativelanguage")
                }
            )
        }

    @Test
    fun `specific pick of a removed provider is a plain error, not a substitute`() =
        runBlocking {
            val rig = rig { FakeTransport.ok(geminiBody("gemini answer")) }
            rig.vault.put(KeyVault.GEMINI_KEY, "gemini-key")
            rig.aiPrefs.setEnginePick(
                EnginePick.Specific("custom-gone", "any-model")
            )
            val result = rig.hub.client.generateText("hello")
            assertTrue(result is AppResult.Err)
            val error = (result as AppResult.Err).error
            assertTrue(error is AppError.BadFile)
            assertTrue(
                (error as AppError.BadFile).reason.contains("isn't there anymore")
            )
            assertTrue(rig.transport.requests.isEmpty())
        }

    @Test
    fun `hasKey follows the pick`() = runBlocking {
        val rig = rig { FakeTransport.ok("{}") }
        rig.vault.put(KeyVault.GEMINI_KEY, "gemini-key")
        // Automatic: a configured provider exists → usable.
        assertTrue(rig.hub.client.hasKey())
        // Specific pick of a provider with NO key → not usable,
        // even though another provider has one.
        rig.aiPrefs.setEnginePick(
            EnginePick.Specific(AiProviders.OPENAI_ID, "gpt-5-mini")
        )
        assertTrue(!rig.hub.client.hasKey())
    }

    @Test
    fun `no providers at all is a plain no-key error`() = runBlocking {
        val rig = rig { FakeTransport.ok("{}") }
        val result = rig.hub.client.generateText("hello")
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
        assertTrue(rig.transport.requests.isEmpty())
    }
}
