package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model cascade (PLAN §7, ReTranslate lesson): lite-first order,
 * stepping ONLY on model-404, session memory of the model that
 * works, picker first when set, and auth/quota failures surfacing
 * immediately without burning the rest of the cascade.
 */
class GeminiClientTest {

    private fun textBody(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}"""
    }

    private fun model404Body(model: String): String =
        """{"error":{"code":404,"message":"This model models/$model is no longer """ +
            """available to new users. Please update your code to use a newer model.",""" +
            """"status":"NOT_FOUND"}}"""

    private fun client(
        transport: FakeTransport,
        picker: String = GeminiModels.AUTO
    ) = GeminiClient(
        transport = transport,
        keyProvider = { "test-key" },
        pickerProvider = { picker }
    )

    @Test
    fun `cascade steps to the next model only on a model 404`() = runBlocking {
        val transport = FakeTransport { request ->
            when {
                request.url.contains("gemini-3.5-flash-lite") ->
                    AppResult.Ok(
                        FakeTransport.response(404, model404Body("gemini-3.5-flash-lite"))
                    )
                else -> FakeTransport.ok(textBody("hello"))
            }
        }
        val result = client(transport).generateText("Say hello")
        assertTrue(result is AppResult.Ok)
        val ok = (result as AppResult.Ok).value
        assertEquals("hello", ok.text)
        assertEquals("gemini-3.1-flash-lite", ok.modelUsed)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0].url.contains("gemini-3.5-flash-lite"))
        assertTrue(transport.requests[1].url.contains("gemini-3.1-flash-lite"))
    }

    @Test
    fun `working model is remembered for the session`() = runBlocking {
        val transport = FakeTransport { request ->
            when {
                request.url.contains("gemini-3.5-flash-lite") ->
                    AppResult.Ok(
                        FakeTransport.response(404, model404Body("gemini-3.5-flash-lite"))
                    )
                else -> FakeTransport.ok(textBody("hi"))
            }
        }
        val gemini = client(transport)
        gemini.generateText("one")
        transport.requests.clear()
        val second = gemini.generateText("two")
        assertTrue(second is AppResult.Ok)
        assertEquals(1, transport.requests.size)
        assertTrue(
            "second call starts at the remembered model",
            transport.requests[0].url.contains("gemini-3.1-flash-lite")
        )
    }

    @Test
    fun `auth failure does not step the cascade`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(
                FakeTransport.response(
                    400,
                    """{"error":{"code":400,"message":"API key not valid. """ +
                        """Please pass a valid API key.","status":"INVALID_ARGUMENT",""" +
                        """"details":[{"reason":"API_KEY_INVALID"}]}}"""
                )
            )
        }
        val result = client(transport).generateText("hello")
        assertTrue(result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue("expected AuthFailed, got $error", error is AppError.AuthFailed)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `quota failure surfaces without stepping`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(
                FakeTransport.response(
                    429,
                    """{"error":{"code":429,"message":"Quota exceeded.","status":"RESOURCE_EXHAUSTED"}}"""
                )
            )
        }
        val result = client(transport).generateText("hello")
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.RateLimited)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `a zero limit 429 says the key has no painting allowance`() {
        // v1.5.40 (honest error words): Google gives free keys
        // a quota limit of 0 for image models — that is not
        // "too many requests", and waiting can never heal it.
        val body = """{"error":{"code":429,"message":"Quota exceeded for """ +
            """quota metric 'Generate Content API requests per minute' """ +
            """and limit 'GenerateContent request limit' of service """ +
            """'generativelanguage.googleapis.com'.","status":""" +
            """"RESOURCE_EXHAUSTED","details":[{"@type":""" +
            """"type.googleapis.com/google.rpc.QuotaFailure",""" +
            """"violations":[{"quotaMetric":"generativelanguage.""" +
            """googleapis.com/generate_content_free_tier_requests",""" +
            """"quotaValue":"0"}]}]}}"""
        val gemini = client(FakeTransport { FakeTransport.ok("{}") })
        val error = gemini.httpError(429, body)
        assertTrue(error is AppError.RateLimited)
        val words = app.xreader.core.error.ErrorText.of(error).message
        assertTrue(
            "the words must name the missing allowance, were: $words",
            words.contains("no painting allowance")
        )
        assertTrue(words.contains("billing"))
        // Google's raw quota text does not ride into the detail.
        assertFalse(
            error.detail.orEmpty().contains("Quota exceeded for quota metric")
        )

        // A plain 429 (a real rate limit) keeps its own words.
        val busy = gemini.httpError(
            429,
            """{"error":{"code":429,"message":"Quota exceeded.",""" +
                """"status":"RESOURCE_EXHAUSTED"}}"""
        )
        val busyWords = app.xreader.core.error.ErrorText.of(busy).message
        assertTrue(busyWords.contains("Too many requests"))
    }

    @Test
    fun `a 404 that is not about the model does not step`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(
                FakeTransport.response(
                    404,
                    """{"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND"}}"""
                )
            )
        }
        val result = client(transport).generateText("hello")
        assertTrue(result is AppResult.Err)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `picker choice is tried first`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok(textBody("ok")) }
        val result = client(transport, picker = "gemini-3.8-flash").generateText("hi")
        assertTrue(result is AppResult.Ok)
        assertEquals("gemini-3.8-flash", (result as AppResult.Ok).value.modelUsed)
        assertTrue(transport.requests[0].url.contains("gemini-3.8-flash"))
    }

    @Test
    fun `no key fails before any request`() = runBlocking {
        val transport = FakeTransport { FakeTransport.ok(textBody("never")) }
        val gemini = GeminiClient(transport, keyProvider = { null })
        val result = gemini.generateText("hi")
        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error is AppError.AuthFailed)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun `error detail carries the http code and googles message`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Ok(
                FakeTransport.response(404, model404Body("gemini-3.5-flash-lite"))
            )
        }
        // Every cascade model 404s with the same body → the final
        // error still names the HTTP code and Google's words.
        val result = client(transport).testKey()
        assertTrue(result is AppResult.Err)
        val detail = (result as AppResult.Err).error.detail ?: ""
        assertTrue(detail.contains("404"))
        assertTrue(detail.contains("no longer available"))
    }

    @Test
    fun `attempt order puts picker then session then cascade`() {
        assertEquals(
            listOf(
                "gemini-3.8-flash", "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite", "gemini-3.6-flash"
            ),
            GeminiModels.attemptOrder("gemini-3.8-flash", null)
        )
        assertEquals(
            "gemini-3.1-flash-lite",
            GeminiModels.attemptOrder(null, "gemini-3.1-flash-lite").first()
        )
        assertEquals(
            GeminiModels.CASCADE,
            GeminiModels.attemptOrder(GeminiModels.AUTO, null)
        )
        assertEquals(
            GeminiModels.CASCADE,
            GeminiModels.attemptOrder("gemini-3.5-flash", null)
        )
    }
}
