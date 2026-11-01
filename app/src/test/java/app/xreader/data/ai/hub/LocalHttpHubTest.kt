package app.xreader.data.ai.hub

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.net.LocalHttpPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.5 (P0): the hub clients obey [LocalHttpPolicy] — a
 * provider key is never sent across public plain http, and the
 * refusal lands BEFORE any request leaves the app. Local and
 * LAN addresses (the normal Ollama shapes) are unaffected.
 */
class LocalHttpHubTest {

    private fun assertPlainHttpRefusal(error: AppError) {
        assertTrue(error is AppError.BadFile)
        assertEquals(
            LocalHttpPolicy.REFUSAL_MESSAGE,
            (error as AppError.BadFile).reason
        )
    }

    @Test
    fun `ollama on a public http address is refused`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("") }
        val client = OllamaClient(
            transport = transport,
            baseUrlProvider = { "http://203.0.113.9:11434" },
            modelProvider = { "llama3.1" }
        )
        val result = client.generateText("Say hi")
        assertTrue(result is AppResult.Err)
        assertPlainHttpRefusal((result as AppResult.Err).error)
        val models = client.listModels()
        assertTrue(models is AppResult.Err)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `ollama stream on a public http address fails with the refusal`() =
        runBlocking {
            val transport = FakeTransport { FakeTransport.ok("") }
            val client = OllamaClient(
                transport = transport,
                baseUrlProvider = { "http://ollama.example.com:11434" },
                modelProvider = { "llama3.1" }
            )
            val events = client.generateTextStream("Say hi").toList()
            val failure = events.single()
                as app.xreader.data.ai.GeminiClient.StreamEvent.Failed
            assertPlainHttpRefusal(failure.error)
            assertTrue(transport.requests.isEmpty())
        }

    @Test
    fun `openai compatible on a public http address is refused`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("") }
        val client = OpenAiCompatibleClient(
            transport = transport,
            serviceId = "Gateway",
            baseUrl = "http://api.example.com/v1",
            keyProvider = { "sk-test" },
            modelProvider = { "gpt-test" }
        )
        val result = client.generateText("Say hi")
        assertTrue(result is AppResult.Err)
        assertPlainHttpRefusal((result as AppResult.Err).error)
        val models = client.listModels("sk-test")
        assertTrue(models is AppResult.Err)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `anthropic on a public http address is refused`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("") }
        val client = AnthropicClient(
            transport = transport,
            serviceId = "Gateway",
            baseUrl = "http://api.example.com",
            keyProvider = { "sk-test" },
            modelProvider = { "claude-test" }
        )
        val result = client.generateText("Say hi")
        assertTrue(result is AppResult.Err)
        assertPlainHttpRefusal((result as AppResult.Err).error)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `discovery refuses a public http site without probing it`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("") }
        val probe = ProviderDiscovery(transport)
            .probe("http://api.example.com", "sk-test")
        assertTrue(probe is ProviderDiscovery.Probe.NotProvider)
        assertTrue(
            (probe as ProviderDiscovery.Probe.NotProvider).reason
                .contains(LocalHttpPolicy.REFUSAL_MESSAGE)
        )
        assertTrue(transport.requests.isEmpty())
    }
}
