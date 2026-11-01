package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.3.4 (T3): error classification v2.
 *
 *  - a 503 with a JSON body (NovelBuddy's outage shape,
 *    live-captured 2026-11-01) is TEMPORARILY_UNAVAILABLE —
 *    it never renders, never escalates, never becomes an
 *    empty success;
 *  - 429 is its own flavour ("too many requests") with the
 *    Retry-After carried; 503 is "temporarily unavailable";
 *  - DNS is its own plain-words state, and NO user-facing
 *    string anywhere carries a raw transport code;
 *  - the shared park fails fast: after a 429 with a
 *    Retry-After, the next request never reaches the
 *    transport.
 */
class V134ClassificationTest {

    @Before
    fun reset() {
        PageShapeLog.clearForTests()
    }

    private class StaticFetcher(
        private val answer: () -> AppResult<HostResponse>
    ) : PageFetcher {
        var calls = 0
        override suspend fun get(
            url: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> {
            calls++
            return answer()
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> = answer()
    }

    private fun escalating(
        static: PageFetcher,
        rendered: FakeRenderedFetcher,
        key: String
    ) = EscalatingPageFetcher(
        staticFetcher = static,
        renderedFetcher = rendered,
        sessionKey = key,
        purpose = RenderPurpose.SOURCE,
        needsRendering = { false },
        permissions = {
            ExtensionPermissions(domains = listOf("novels.example"))
        },
        rateLimiter = RateLimiter(minIntervalMs = 0),
        gate = RenderGate(
            masterEnabled = { true },
            tier = { PerfTier.FULL },
            userDriven = { true },
            backgroundWork = { false }
        )
    )

    @Test
    fun `novelbuddy 503 shape is availability, never a render, never empty`() =
        runBlocking {
            val body = "{\"code\":\"SERVICE_UNAVAILABLE\"," +
                "\"message\":\"Service temporarily unavailable\"," +
                "\"retryAfter\":5}"
            val static = StaticFetcher {
                AppResult.Ok(
                    HostResponse(
                        503, body,
                        mapOf("Retry-After" to "5"),
                        "https://api.novels.example/titles/search"
                    )
                )
            }
            val rendered = FakeRenderedFetcher()
            val fetcher = escalating(static, rendered, "v134-nb503")
            val result = fetcher.get("https://novels.example/popular")
            assertTrue(result is AppResult.Err)
            val error = (result as AppResult.Err).error
            assertTrue(error is AppError.RateLimited)
            assertEquals(5L, (error as AppError.RateLimited).retryAfterSeconds)
            assertTrue("the browser view was never spent", rendered.calls.isEmpty())
            val classified = FetchClassification.classify(null, error)
            assertEquals(
                FetchClassification.Kind.TEMPORARILY_UNAVAILABLE,
                classified.kind
            )
            assertFalse(classified.rateLimited)
        }

    @Test
    fun `a 429 classifies as rate limited with its own copy`() = runBlocking {
        val static = StaticFetcher {
            AppResult.Ok(
                HostResponse(
                    429, "slow down",
                    mapOf("Retry-After" to "120"),
                    "https://novels.example/popular"
                )
            )
        }
        val fetcher = escalating(static, FakeRenderedFetcher(), "v134-429")
        val result = fetcher.get("https://novels.example/popular")
        val error = (result as AppResult.Err).error
        val classified = FetchClassification.classify(null, error)
        assertEquals(
            FetchClassification.Kind.TEMPORARILY_UNAVAILABLE, classified.kind
        )
        assertTrue(classified.rateLimited)
        val words = ErrorText.of(error).message
        assertTrue(words.contains("Too many requests"))
        assertTrue(words.contains("2 minutes"))
    }

    @Test
    fun `503 copy says temporarily unavailable, not too many requests`() {
        val error = AppError.RateLimited(
            "source", retryAfterSeconds = 30,
            detail = "the site is temporarily unavailable (HTTP 503)"
        )
        val words = ErrorText.of(error).message
        assertTrue(words.contains("temporarily unavailable"))
        assertFalse(words.contains("Too many requests"))
        assertTrue(words.contains("30 seconds"))
    }

    @Test
    fun `a challenge-shaped 503 still escalates to the render`() = runBlocking {
        val challenge = "<html><head><title>Attention Required! | " +
            "Cloudflare</title></head><body>challenge-form</body></html>"
        val static = StaticFetcher {
            AppResult.Ok(
                HostResponse(503, challenge, finalUrl = "https://novels.example/x")
            )
        }
        val rendered = FakeRenderedFetcher(
            pages = mapOf(
                "https://novels.example/popular" to
                    "<html><body><p>real books</p></body></html>"
            )
        )
        val fetcher = escalating(static, rendered, "v134-cf503")
        val result = fetcher.get("https://novels.example/popular")
        assertTrue("the render rescued the challenge 503", result is AppResult.Ok)
        assertEquals(1, rendered.calls.size)
    }

    @Test
    fun `an availability park inside the fetcher is terminal`() = runBlocking {
        val static = StaticFetcher {
            AppResult.Err(
                AppError.RateLimited(
                    "source", retryAfterSeconds = 20,
                    detail = "the site is temporarily unavailable (HTTP 429)"
                )
            )
        }
        val rendered = FakeRenderedFetcher()
        val fetcher = escalating(static, rendered, "v134-parked")
        val result = fetcher.get("https://novels.example/popular")
        assertTrue(result is AppResult.Err)
        assertTrue(rendered.calls.isEmpty())
    }

    @Test
    fun `the client park fails fast and names its origin`() = runBlocking {
        var transportCalls = 0
        val transport = FakeTransport {
            transportCalls++
            AppResult.Ok(
                FakeTransport.response(
                    429, "slow down", mapOf("Retry-After" to "120")
                )
            )
        }
        val client = ExtensionHttpClient(
            transport = transport,
            sessionStore = testSessionStore(),
            sessionKey = "v134-client-park",
            label = "Test",
            whitelist = {
                ExtensionPermissions(domains = listOf("novels.example"))
            },
            rateLimiter = RateLimiter(minIntervalMs = 0),
            cooldowns = AvailabilityCooldowns()
        )
        val first = client.get("https://novels.example/popular")
        assertTrue(first is AppResult.Ok)
        assertEquals(429, (first as AppResult.Ok).value.status)
        val second = client.get("https://novels.example/popular")
        assertTrue(second is AppResult.Err)
        val error = (second as AppResult.Err).error
        assertTrue(error is AppError.RateLimited)
        assertEquals(
            "the parked request never reached the transport",
            1, transportCalls
        )
        val classified = FetchClassification.classify(null, error)
        assertEquals(
            FetchClassification.Kind.TEMPORARILY_UNAVAILABLE, classified.kind
        )
        assertTrue("the 429 origin survives the park", classified.rateLimited)
    }

    @Test
    fun `dns failure is its own plain words state`() = runBlocking {
        val transport = FakeTransport {
            AppResult.Err(AppError.NoNetwork)
        }
        val client = ExtensionHttpClient(
            transport = transport,
            sessionStore = testSessionStore(),
            sessionKey = "v134-dns",
            label = "Test",
            whitelist = {
                ExtensionPermissions(domains = listOf("novels.example"))
            },
            rateLimiter = RateLimiter(minIntervalMs = 0),
            cooldowns = AvailabilityCooldowns()
        )
        val result = client.get("https://novels.example/popular")
        assertTrue(result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertEquals(
            FetchClassification.Kind.DNS_FAILURE,
            FetchClassification.classify(null, error).kind
        )
        assertTrue(
            error.detail.orEmpty().contains(FetchClassification.DNS_MARKER)
        )
    }

    @Test
    fun `webview error words never carry a raw code`() {
        for (code in listOf(-1, -2, -6, -7, -8, -9, -11, -15, null)) {
            val phrase = WebViewErrorWords.phrase(code)
            assertFalse(
                "code $code leaked into: $phrase",
                phrase.contains("(error") || phrase.contains("-2)")
            )
        }
        assertTrue(
            WebViewErrorWords.phrase(-2)
                .contains(FetchClassification.DNS_MARKER)
        )
    }

    @Test
    fun `no user facing string carries a raw transport code`() {
        val errors = listOf(
            AppError.RateLimited("s", 5, "the site is temporarily unavailable (HTTP 503)"),
            AppError.RateLimited("s", 5, "the site is temporarily unavailable (HTTP 429)"),
            AppError.ExtensionFailed(
                "NovelFire",
                "couldn't find the site's address — the name didn't resolve"
            ),
            AppError.ProviderDown(
                "browser view", retryable = true,
                detail = WebViewErrorWords.phrase(-2)
            )
        )
        val rawCode = Regex("\\(error -?\\d+\\)|error -\\d+")
        for (error in errors) {
            val shown = ErrorText.displayText(error)
            assertFalse(
                "raw code in user copy: $shown",
                rawCode.containsMatchIn(shown)
            )
        }
    }

    @Test
    fun `classifier maps the session lifecycle markers`() {
        val signInConfig = DeclarativeConfig(
            id = "c1", name = "C1", lang = "en",
            baseUrl = "https://novels.example",
            search = null, popular = null, latest = null,
            details = DetailsSpec(
                chapters = ChapterListSpec(list = "a", name = "@text", link = "@href")
            ),
            chapter = ChapterSpec(content = "div"),
            signInNeeded = true
        )
        assertEquals(
            FetchClassification.Kind.SESSION_CHECK,
            FetchClassification.classifyDetail(
                signInConfig,
                "${FetchClassification.MINT_CHALLENGE_MARKER}: the site wants its check"
            ).kind
        )
        assertEquals(
            FetchClassification.Kind.SIGN_IN,
            FetchClassification.classifyDetail(
                signInConfig,
                "${FetchClassification.SESSION_DEAD_MARKER}: sign in again"
            ).kind
        )
        assertEquals(
            FetchClassification.Kind.LOCKED,
            FetchClassification.classifyDetail(
                null,
                "the chapter stayed locked even after the reading pass " +
                    "was renewed (${FetchClassification.STAYED_LOCKED_MARKER})"
            ).kind
        )
        assertEquals(
            FetchClassification.Kind.SIGN_IN,
            FetchClassification.classifyDetail(
                signInConfig, DeclarativeSource.AJAX_TEXT_EMPTY_MESSAGE
            ).kind
        )
        assertEquals(
            FetchClassification.Kind.LOCKED,
            FetchClassification.classifyDetail(
                null, DeclarativeSource.AJAX_TEXT_EMPTY_MESSAGE
            ).kind
        )
    }
}
