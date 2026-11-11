package app.xreader.data.extension

import app.xreader.extensionapi.HostHttp
import app.xreader.extensionapi.HostResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v1.5.8 (C3 / F7 F3): an adopted (harvested) token's
 * expiry anchors at its OBSERVATION instant — the
 * harvest's own timestamp plus the spec's default
 * lifetime — never at adoption time. A harvest observed
 * at T and adopted at T+240 s carries ≈60 s of life, not
 * a fresh 300 s; pre-fix the adoption re-anchored the
 * lifetime and the app presented a token the site had
 * already retired as valid.
 */
class V158AdoptedTokenExpiryTest {

    private val spec = OperationTokenSpec(
        mintUrl = "https://fenrir.example/api/read-token",
        mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}",
        checkUrlTemplate = "https://fenrir.example/series/{seg-3}/{seg-1}"
    )

    private class UnusedHttp : HostHttp {
        var calls = 0
        override fun get(
            url: String, headers: Map<String, String>
        ): HostResponse {
            calls++
            return HostResponse(500, "must not be used")
        }

        override fun post(
            url: String, body: String, contentType: String,
            headers: Map<String, String>
        ): HostResponse {
            calls++
            return HostResponse(500, "must not be used")
        }
    }

    private class NullProvider :
        ReadTokenCoordinator.WebViewMintProvider {
        override suspend fun harvestToken(
            parkUrl: String
        ): TokenHarvestResult? = null

        override suspend fun mintInContext(
            spec: OperationTokenSpec,
            mintChapterId: String?,
            parkUrl: String
        ): InContextFetchResult? = null
    }

    @Test
    fun `adopted token expiry anchors at observation not adoption`() =
        runBlocking {
            FenrirHarvestSink.clearForTests()
            val store = testSessionStore()
            // The harvest is observed NOW (the sink stamps
            // the real clock); its body states NO expires_at,
            // so the spec's 300 s default lifetime applies —
            // counted from the observation.
            val observedAt = System.currentTimeMillis()
            FenrirHarvestSink.recordForHost(
                "fenrir.example",
                TokenHarvestResult("harvested-token", 200, "{}")
            )
            // Adoption happens 240 s after observation: the
            // coordinator's clock is pinned there.
            val coordinator = ReadTokenCoordinator(
                spec, store = store, storeKey = "v158-c3",
                nowMs = { observedAt + 240_000L }
            )
            val http = UnusedHttp()
            val outcome = coordinator.token(
                http, "scope-c3", "7",
                webViewProvider = NullProvider(),
                recentSolve = true,
                parkUrl = "https://fenrir.example/series/x/7"
            )
            assertTrue(
                "the harvest must be adopted, got $outcome",
                outcome is ReadTokenCoordinator.MintOutcome.Ready
            )
            assertEquals(0, http.calls)
            val stored = store.loadReadToken("v158-c3", "scope-c3")
            assertNotNull("the adoption must persist", stored)
            val expected = observedAt + 300_000L
            assertTrue(
                "expiry must anchor at observation + 300 s " +
                    "(expected ≈$expected, was ${stored!!.expiresAtMs}; " +
                    "adoption-anchored would be ≈${observedAt + 540_000L})",
                abs(stored.expiresAtMs - expected) < 5_000L
            )
            FenrirHarvestSink.clearForTests()
        }
}
