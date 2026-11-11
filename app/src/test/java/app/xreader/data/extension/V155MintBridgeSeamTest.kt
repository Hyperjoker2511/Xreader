package app.xreader.data.extension

import android.app.Application
import app.xreader.extensionapi.HostHttp
import app.xreader.extensionapi.HostResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.5 (§3, reality check c — the JVM boundary stated
 * honestly): a true WebView round-trip is device-bound and
 * cannot run here, so the mint-in-WebView road is proven at
 * its KOTLIN SEAMS instead:
 *
 *  - the @JavascriptInterface bridge: a registered call is
 *    completed by a scripted onResult payload, and a harvest
 *    wait by a scripted onHarvest payload (nothing is ever
 *    read from an evaluateJavascript return — the bridge is
 *    the only channel, by construction);
 *  - the harvest parsing: token-bearing mint answers and
 *    storage-scan reports parse into adoptions; tokenless
 *    reports do not;
 *  - the hook + fetch scripts keep their invariants (strict
 *    ES6 — no ?. or ?? — Request-object inputs read via
 *    input.url, responses cloned before reading, storage
 *    scanned, credentials included);
 *  - the coordinator's mint ORDER over a fake provider:
 *    post-solve, the in-context road mints (sink → in-context)
 *    and OkHttp is never touched; with no recent solve, OkHttp
 *    mints first and the provider is never touched. The wire
 *    classifier is shared, so both roads yield the same Ready.
 *
 * v1.5.12 (Front 1, S2): the mint sequence is capped at ONE
 * Main-resident round-trip per chapter open — the v1.5.5
 * harvest VISIT (a second parked round-trip ahead of the
 * in-context mint) no longer runs inside the coordinator;
 * the sink adoption (fed by the solve flow's own harvest)
 * is the only harvest step left, and it costs no round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V155MintBridgeSeamTest {

    private val spec = OperationTokenSpec(
        mintUrl = "https://fenrir.example/api/read-token",
        mintBodyTemplate = "{\"chapter_id\":\"{chapter_id}\"}",
        checkUrlTemplate = "https://fenrir.example/series/{seg-3}/{seg-1}"
    )

    /** An OkHttp stand-in that must stay untouched on the
     * post-solve road (and answers on the pre-solve road). */
    private class RecordingHttp(
        private val mintAnswer: HostResponse
    ) : HostHttp {
        var calls = 0
        override fun get(
            url: String,
            headers: Map<String, String>
        ): HostResponse {
            calls++
            return HostResponse(200, "")
        }

        override fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>
        ): HostResponse {
            calls++
            return mintAnswer
        }
    }

    private class FakeProvider(
        var harvest: TokenHarvestResult? = null,
        var mint: InContextFetchResult? = null
    ) : ReadTokenCoordinator.WebViewMintProvider {
        var harvestCalls = 0
        var mintCalls = 0
        override suspend fun harvestToken(
            parkUrl: String
        ): TokenHarvestResult? {
            harvestCalls++
            return harvest
        }

        override suspend fun mintInContext(
            spec: OperationTokenSpec,
            mintChapterId: String?,
            parkUrl: String
        ): InContextFetchResult? {
            mintCalls++
            return mint
        }
    }

    @Test
    fun `bridge completes a registered fetch call from its payload`() =
        runBlocking {
            val bridge = InContextBridge.Bridge()
            val deferred = bridge.register("call-1")
            bridge.onResult(
                "call-1", "{\"status\":200,\"body\":\"{\\\"ok\\\":1}\"}"
            )
            val parsed = InContextBridge.parseFetchResult(deferred.await())
            assertNotNull(parsed)
            assertEquals(200, parsed!!.status)
            assertEquals("{\"ok\":1}", parsed.body)
        }

    @Test
    fun `bridge harvest completes the waiter and feeds the sink`() =
        runBlocking {
            FenrirHarvestSink.clearForTests()
            val bridge = InContextBridge.Bridge()
            val deferred = bridge.registerHarvest("fenrir.example")
            bridge.onHarvest(
                "{\"status\":200,\"body\":\"{}\",\"token\":\"sink-token\"}"
            )
            val parsed = InContextBridge.parseHarvest(deferred.await())
            assertEquals("sink-token", parsed?.token)
            assertEquals(
                "sink-token",
                FenrirHarvestSink.recentFor("fenrir.example")?.token
            )
            FenrirHarvestSink.clearForTests()
        }

    @Test
    fun `tokenless harvest reports do not parse as adoptions`() {
        assertEquals(
            null,
            InContextBridge.parseHarvest(
                "{\"status\":403,\"body\":\"check\",\"token\":\"\"}"
            )
        )
        assertEquals(
            "abc.def.ghi",
            InContextBridge.extractTokenFromMintBody(
                "{\"token\":\"abc.def.ghi\"}"
            )
        )
    }

    @Test
    fun `hook and fetch scripts keep their es6 invariants`() {
        val hook = InContextBridge.tokenHarvestHookScript()
        assertTrue(hook.contains("clone()"))
        assertTrue(hook.contains("input.url"))
        assertTrue(hook.contains("localStorage"))
        assertTrue(hook.contains("XMLHttpRequest"))
        assertFalse("hook must stay ES6", hook.contains("?."))
        assertFalse("hook must stay ES6", hook.contains("??"))
        val fetch = InContextBridge.fetchScript(
            "c1", "https://fenrir.example/api/x", "GET", null,
            mapOf("Accept" to "application/json")
        )
        assertTrue(fetch.contains("credentials: 'include'"))
        assertFalse("fetch script must stay ES6", fetch.contains("?."))
        assertFalse("fetch script must stay ES6", fetch.contains("??"))
    }

    @Test
    fun `post solve the mint runs in context and okhttp is untouched`() {
        FenrirHarvestSink.clearForTests()
        val http = RecordingHttp(HostResponse(500, "must not be used"))
        val provider = FakeProvider(
            harvest = null,
            mint = InContextFetchResult(
                200, "{\"token\":\"wv-token\",\"expires_at\":9999999999}"
            )
        )
        val coordinator = ReadTokenCoordinator(spec, store = null)
        val outcome = coordinator.token(
            http, "scope-a", "42",
            webViewProvider = provider,
            recentSolve = true,
            parkUrl = "https://fenrir.example/series/x/42"
        )
        assertTrue(outcome is ReadTokenCoordinator.MintOutcome.Ready)
        assertEquals(
            "wv-token",
            (outcome as ReadTokenCoordinator.MintOutcome.Ready).token
        )
        assertEquals(0, http.calls)
        // v1.5.12 (Front 1, S2): NO harvest visit — the
        // in-context mint is the sequence's ONE round-trip.
        assertEquals(0, provider.harvestCalls)
        assertEquals(1, provider.mintCalls)
        val diagnostic = coordinator.lastMintDiagnostic()
        assertEquals("webview", diagnostic?.road)
        assertEquals(200, diagnostic?.status)
    }

    @Test
    fun `post solve a harvested token is adopted with no new mint`() {
        FenrirHarvestSink.clearForTests()
        FenrirHarvestSink.record(
            "{\"status\":200,\"body\":\"{}\",\"token\":\"harvested-token\"}"
        )
        val http = RecordingHttp(HostResponse(500, "must not be used"))
        val provider = FakeProvider(harvest = null, mint = null)
        val coordinator = ReadTokenCoordinator(spec, store = null)
        val outcome = coordinator.token(
            http, "scope-b", "7",
            webViewProvider = provider,
            recentSolve = true,
            parkUrl = "https://fenrir.example/series/x/7"
        )
        assertTrue(outcome is ReadTokenCoordinator.MintOutcome.Ready)
        assertEquals(
            "harvested-token",
            (outcome as ReadTokenCoordinator.MintOutcome.Ready).token
        )
        assertEquals(0, http.calls)
        assertEquals(0, provider.mintCalls)
        assertEquals(
            "harvest", coordinator.lastMintDiagnostic()?.road
        )
        FenrirHarvestSink.clearForTests()
    }

    @Test
    fun `no recent solve mints via okhttp first`() {
        val http = RecordingHttp(
            HostResponse(200, "{\"token\":\"ok-token\"}")
        )
        val provider = FakeProvider(
            mint = InContextFetchResult(200, "{\"token\":\"wv\"}")
        )
        val coordinator = ReadTokenCoordinator(spec, store = null)
        val outcome = coordinator.token(
            http, "scope-c", "9",
            webViewProvider = provider,
            recentSolve = false,
            parkUrl = "https://fenrir.example/series/x/9"
        )
        assertTrue(outcome is ReadTokenCoordinator.MintOutcome.Ready)
        assertEquals(
            "ok-token",
            (outcome as ReadTokenCoordinator.MintOutcome.Ready).token
        )
        assertEquals(1, http.calls)
        assertEquals(0, provider.harvestCalls)
        assertEquals(0, provider.mintCalls)
        assertEquals("okhttp", coordinator.lastMintDiagnostic()?.road)
    }
}
