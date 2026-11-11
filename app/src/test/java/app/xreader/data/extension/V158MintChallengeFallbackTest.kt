package app.xreader.data.extension

import app.xreader.extensionapi.HostHttp
import app.xreader.extensionapi.HostResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (C2 / F7 F2): a challenge CLASSIFICATION from the
 * in-context mint is not terminal — one context's verdict
 * must not convict the credential while the other context
 * is untried. The mint falls through to ONE OkHttp mint
 * before ChallengeRequired can return. Fences: a transport
 * failure / null in-context result keeps today's
 * fall-through, and a challenge on BOTH roads still ends
 * in ChallengeRequired (no third attempt).
 */
class V158MintChallengeFallbackTest {

    private val spec = OperationTokenSpec(
        mintUrl = "https://fenrir.example/api/read-token",
        mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}",
        checkUrlTemplate = "https://fenrir.example/series/{seg-3}/{seg-1}"
    )

    private class RecordingHttp(
        private val mintAnswer: HostResponse
    ) : HostHttp {
        var calls = 0
        override fun get(
            url: String, headers: Map<String, String>
        ): HostResponse {
            calls++
            return HostResponse(200, "")
        }

        override fun post(
            url: String, body: String, contentType: String,
            headers: Map<String, String>
        ): HostResponse {
            calls++
            return mintAnswer
        }
    }

    private class FakeProvider(
        var mint: InContextFetchResult? = null
    ) : ReadTokenCoordinator.WebViewMintProvider {
        var mintCalls = 0
        override suspend fun harvestToken(
            parkUrl: String
        ): TokenHarvestResult? = null

        override suspend fun mintInContext(
            spec: OperationTokenSpec,
            mintChapterId: String?,
            parkUrl: String
        ): InContextFetchResult? {
            mintCalls++
            return mint
        }
    }

    private val challengeBody =
        "<html><body><div class=\"cf-turnstile\"></div>" +
            "Just a moment…</body></html>"

    @Test
    fun `in-context challenge falls through to one okhttp mint`() {
        FenrirHarvestSink.clearForTests()
        val http = RecordingHttp(
            HostResponse(200, "{\"token\":\"ok-token\"}")
        )
        val provider = FakeProvider(
            mint = InContextFetchResult(403, challengeBody)
        )
        val coordinator = ReadTokenCoordinator(spec, store = null)
        val outcome = coordinator.token(
            http, "scope-c2-a", "42",
            webViewProvider = provider,
            recentSolve = true,
            parkUrl = "https://fenrir.example/series/x/42"
        )
        assertTrue(
            "the OkHttp mint's token must win after the " +
                "in-context challenge, got $outcome",
            outcome is ReadTokenCoordinator.MintOutcome.Ready
        )
        assertEquals(
            "ok-token",
            (outcome as ReadTokenCoordinator.MintOutcome.Ready).token
        )
        assertEquals(1, provider.mintCalls)
        assertEquals(
            "exactly ONE OkHttp mint follows the fall-through",
            1, http.calls
        )
    }

    @Test
    fun `challenge on both roads still ends challenge-required`() {
        FenrirHarvestSink.clearForTests()
        val http = RecordingHttp(HostResponse(403, challengeBody))
        val provider = FakeProvider(
            mint = InContextFetchResult(403, challengeBody)
        )
        val coordinator = ReadTokenCoordinator(spec, store = null)
        val outcome = coordinator.token(
            http, "scope-c2-b", "42",
            webViewProvider = provider,
            recentSolve = true,
            parkUrl = "https://fenrir.example/series/x/42"
        )
        assertTrue(
            "both roads challenged → ChallengeRequired, got $outcome",
            outcome is ReadTokenCoordinator.MintOutcome.ChallengeRequired
        )
        assertEquals(1, http.calls)
    }

    @Test
    fun `null in-context result keeps the plain okhttp road`() {
        FenrirHarvestSink.clearForTests()
        val http = RecordingHttp(
            HostResponse(200, "{\"token\":\"ok-token-2\"}")
        )
        val provider = FakeProvider(mint = null)
        val coordinator = ReadTokenCoordinator(spec, store = null)
        val outcome = coordinator.token(
            http, "scope-c2-c", "42",
            webViewProvider = provider,
            recentSolve = true,
            parkUrl = "https://fenrir.example/series/x/42"
        )
        assertTrue(outcome is ReadTokenCoordinator.MintOutcome.Ready)
        assertEquals(
            "ok-token-2",
            (outcome as ReadTokenCoordinator.MintOutcome.Ready).token
        )
        assertEquals(1, http.calls)
    }
}
