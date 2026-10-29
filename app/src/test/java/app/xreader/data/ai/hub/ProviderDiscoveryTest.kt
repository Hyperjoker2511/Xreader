package app.xreader.data.ai.hub

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-a-provider test-drive (v1.0.3): protocol detection by wire
 * shape only (no site names), NeedsKey as a first-class outcome, a
 * refused key reported as refused, and — structurally — the probe
 * holds no storage at all, so a failed check cannot add anything.
 */
class ProviderDiscoveryTest {

    private val openAiModels = """
        {"object":"list","data":[
          {"id":"free/model-a","object":"model","owned_by":"gateway"},
          {"id":"paid-model-b","object":"model","owned_by":"gateway"}
        ]}
    """.trimIndent()

    private val anthropicModels = """
        {"data":[
          {"id":"claude-x-1","type":"model","display_name":"Claude X 1"}
        ],"has_more":false}
    """.trimIndent()

    @Test
    fun `openai-shaped gateway confirms with its models and free badges`() = runBlocking {
        val transport = FakeTransport { request ->
            if (request.url == "https://gateway.example/v1/models" &&
                request.headers["Authorization"] == "Bearer key-1"
            ) {
                FakeTransport.ok(openAiModels)
            } else {
                FakeTransport.response(404, "nope")
            }
        }
        val probe = ProviderDiscovery(transport).probe("gateway.example", "key-1")
        assertTrue(probe is ProviderDiscovery.Probe.Confirmed)
        val confirmed = probe as ProviderDiscovery.Probe.Confirmed
        assertEquals(AiProtocol.OPENAI_COMPATIBLE, confirmed.protocol)
        assertEquals("https://gateway.example/v1", confirmed.baseUrl)
        assertEquals(2, confirmed.models.size)
        assertEquals(true, confirmed.keyValidated)
        assertEquals(
            listOf("free/model-a"),
            confirmed.models.filter { it.listedFree }.map { it.id }
        )
    }

    @Test
    fun `401 without a key is needs-key, not failure`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.response(401, """{"error":{"message":"missing key"}}""")
        }
        val probe = ProviderDiscovery(transport).probe("https://gateway.example", null)
        assertTrue(probe is ProviderDiscovery.Probe.NeedsKey)
    }

    @Test
    fun `401 with a key is a plain refusal and adds nothing`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.response(401, """{"error":{"message":"bad key"}}""")
        }
        val probe = ProviderDiscovery(transport).probe("https://gateway.example", "wrong")
        assertTrue(probe is ProviderDiscovery.Probe.NotProvider)
        assertTrue(
            (probe as ProviderDiscovery.Probe.NotProvider).reason
                .contains("refused that key")
        )
    }

    @Test
    fun `anthropic-shaped gateway confirms on the x-api-key dialect`() = runBlocking {
        val transport = FakeTransport { request ->
            when {
                request.url == "https://claude-gateway.example/v1/models" &&
                    request.headers.containsKey("x-api-key") ->
                    FakeTransport.ok(anthropicModels)
                else -> FakeTransport.response(404, "nope")
            }
        }
        val probe = ProviderDiscovery(transport)
            .probe("https://claude-gateway.example", "sk-ant-test")
        assertTrue(probe is ProviderDiscovery.Probe.Confirmed)
        val confirmed = probe as ProviderDiscovery.Probe.Confirmed
        assertEquals(AiProtocol.ANTHROPIC, confirmed.protocol)
        assertEquals(listOf("claude-x-1"), confirmed.models.map { it.id })
    }

    @Test
    fun `openrouter-style api v1 root is found`() = runBlocking {
        val transport = FakeTransport { request ->
            if (request.url == "https://openrouter.example/api/v1/models") {
                FakeTransport.ok(openAiModels)
            } else {
                FakeTransport.response(404, "nope")
            }
        }
        val probe = ProviderDiscovery(transport).probe("openrouter.example", "sk-or")
        assertTrue(probe is ProviderDiscovery.Probe.Confirmed)
        assertEquals(
            "https://openrouter.example/api/v1",
            (probe as ProviderDiscovery.Probe.Confirmed).baseUrl
        )
    }

    @Test
    fun `a plain website is not a provider`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.ok("<html><body>Just a blog</body></html>")
        }
        val probe = ProviderDiscovery(transport).probe("https://blog.example", "key")
        assertTrue(probe is ProviderDiscovery.Probe.NotProvider)
    }

    @Test
    fun `network failure is not a provider either`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Err(app.xreader.core.error.AppError.NoNetwork)
        }
        val probe = ProviderDiscovery(transport).probe("https://down.example", null)
        assertTrue(probe is ProviderDiscovery.Probe.NotProvider)
    }

    @Test
    fun `http transport type is honoured`() {
        // Compile-time pin: the probe speaks only HttpTransport.
        val transport: HttpTransport = FakeTransport { FakeTransport.ok("{}") }
        ProviderDiscovery(transport)
    }
}
