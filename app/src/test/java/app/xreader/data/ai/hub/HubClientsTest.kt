package app.xreader.data.ai.hub

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.ai.GeminiClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The protocol clients (v1.0.3): request shapes (URL, auth header,
 * body), reply parsing, and the shared error vocabulary — pinned
 * against a scripted transport, exactly like GeminiClientTest.
 */
class HubClientsTest {

    private fun openAiClient(
        transport: FakeTransport,
        key: String? = "sk-test",
        model: String? = "free/model-a"
    ) = OpenAiCompatibleClient(
        transport = transport,
        serviceId = "APInex",
        baseUrl = "https://api.apinex.bond/v1",
        keyProvider = { key },
        modelProvider = { model }
    )

    @Test
    fun `openai client posts to chat completions with bearer key and parses text`() =
        runBlocking {
            val transport = FakeTransport {
                FakeTransport.ok(
                    """{"choices":[{"message":{"role":"assistant","content":"hello there"}}]}"""
                )
            }
            val result = openAiClient(transport).generateText(
                "Say hello", jsonMode = true
            )
            assertTrue(result is AppResult.Ok)
            val ok = (result as AppResult.Ok).value
            assertEquals("hello there", ok.text)
            assertEquals("free/model-a", ok.modelUsed)
            val request = transport.requests.single()
            assertEquals(
                "https://api.apinex.bond/v1/chat/completions", request.url
            )
            assertEquals("Bearer sk-test", request.headers["Authorization"])
            assertTrue(request.body!!.contains("\"model\":\"free/model-a\""))
            assertTrue(request.body!!.contains("\"response_format\""))
        }

    @Test
    fun `openai client maps 401 to auth failed and 429 to rate limited`() = runBlocking {
        val unauthorized = FakeTransport {
            AppResult.Ok(FakeTransport.response(401, """{"error":{"message":"bad key"}}"""))
        }
        val authResult = openAiClient(unauthorized).generateText("hi")
        assertTrue(authResult is AppResult.Err)
        assertTrue((authResult as AppResult.Err).error is AppError.AuthFailed)

        val limited = FakeTransport {
            AppResult.Ok(FakeTransport.response(429, """{"error":{"message":"slow down"}}"""))
        }
        val rateResult = openAiClient(limited).generateText("hi")
        assertTrue((rateResult as AppResult.Err).error is AppError.RateLimited)
    }

    @Test
    fun `openai client without a key fails before any request`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok("{}") }
        val result = openAiClient(transport, key = null).generateText("hi")
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `openai client streams deltas and done`() = runBlocking {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Once \"}}]}\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"upon\"}}]}\n")
            append("data: [DONE]\n")
        }
        val transport = FakeTransport { FakeTransport.ok(sse) }
        val events = openAiClient(transport).generateTextStream("Tell").toList()
        val deltas = events.filterIsInstance<GeminiClient.StreamEvent.Delta>()
        assertEquals(listOf("Once ", "upon"), deltas.map { it.text })
        assertTrue(events.last() is GeminiClient.StreamEvent.Done)
    }

    @Test
    fun `anthropic client uses messages endpoint without doubling v1`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.ok(
                """{"content":[{"type":"text","text":"claude says hi"}]}"""
            )
        }
        val client = AnthropicClient(
            transport = transport,
            serviceId = "Claude",
            baseUrl = "https://api.anthropic.com",
            keyProvider = { "sk-ant" },
            modelProvider = { "claude-haiku-4-5" }
        )
        val result = client.generateText("Say hi")
        assertTrue(result is AppResult.Ok)
        assertEquals("claude says hi", (result as AppResult.Ok).value.text)
        val request = transport.requests.single()
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant", request.headers["x-api-key"])
        assertTrue(request.body!!.contains("\"max_tokens\""))

        val gatewayTransport = FakeTransport {
            FakeTransport.ok("""{"content":[{"type":"text","text":"ok"}]}""")
        }
        val gateway = AnthropicClient(
            transport = gatewayTransport,
            serviceId = "Gateway",
            baseUrl = "https://gateway.example/v1",
            keyProvider = { "k" },
            modelProvider = { "m" }
        )
        gateway.generateText("hi")
        assertEquals(
            "https://gateway.example/v1/messages",
            gatewayTransport.requests.single().url
        )
    }

    @Test
    fun `anthropic stream reads text deltas only`() = runBlocking {
        val sse = buildString {
            append("event: content_block_delta\n")
            append(
                "data: {\"type\":\"content_block_delta\",\"delta\":" +
                    "{\"type\":\"text_delta\",\"text\":\"abc\"}}\n"
            )
            append(
                "data: {\"type\":\"content_block_delta\",\"delta\":" +
                    "{\"type\":\"thinking_delta\",\"thinking\":\"secret\"}}\n"
            )
        }
        val transport = FakeTransport { FakeTransport.ok(sse) }
        val client = AnthropicClient(
            transport = transport,
            serviceId = "Claude",
            baseUrl = "https://api.anthropic.com",
            keyProvider = { "sk-ant" },
            modelProvider = { "claude-haiku-4-5" }
        )
        val events = client.generateTextStream("hi").toList()
        val deltas = events.filterIsInstance<GeminiClient.StreamEvent.Delta>()
        assertEquals(listOf("abc"), deltas.map { it.text })
    }

    @Test
    fun `ollama client posts to api chat and parses message content`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.ok(
                """{"message":{"role":"assistant","content":"local hello"}}"""
            )
        }
        val client = OllamaClient(
            transport = transport,
            baseUrlProvider = { "http://192.168.1.10:11434" },
            modelProvider = { "llama3.1" }
        )
        val result = client.generateText("Say hi", jsonMode = true)
        assertTrue(result is AppResult.Ok)
        assertEquals("local hello", (result as AppResult.Ok).value.text)
        val request = transport.requests.single()
        assertEquals("http://192.168.1.10:11434/api/chat", request.url)
        assertTrue(request.body!!.contains("\"format\":\"json\""))
        assertTrue(client.hasKey())
    }
}
