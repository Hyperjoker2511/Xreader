package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (second-opinion 2B, delta 2): the escalation ROUTING
 * journey, end to end through production pieces — a real
 * [HostContextFactory] holding BOTH a rendered fetcher and a
 * [RenderRuntime] builds the real [EscalatingPageFetcher], and
 * the fake transport serves a block-shaped static failure.
 *
 * Proven routing: static-fail → render attempt → render fails →
 * bridge answers (fetchViaBridge scripted on the fake). And
 * with RenderHealth already struck for the session key, the
 * render leg is skipped entirely: static-fail → bridge-answer.
 * Routing only — cookie synchronisation stays device-owed and
 * is deliberately NOT faked here.
 */
class V152EscalationRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A rendered fetcher with BOTH roads scripted and every
     * call recorded in order. */
    private class ScriptedRenderer(
        private val bridgeBody: String
    ) : RenderedPageFetcher {
        val calls = mutableListOf<String>()

        override suspend fun fetchRendered(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions,
            waitForSelector: String?
        ): AppResult<HostResponse> {
            calls += "render"
            return AppResult.Err(
                AppError.ExtensionFailed("scripted", "the render failed")
            )
        }

        override suspend fun fetchViaBridge(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions
        ): AppResult<HostResponse>? {
            calls += "bridge"
            return AppResult.Ok(HostResponse(200, bridgeBody, finalUrl = url))
        }
    }

    @Before
    fun setUp() {
        RenderHealth.reset()
    }

    private suspend fun runtimeReady(): RenderRuntime {
        val runtime = RenderRuntime(
            MutableStateFlow(true), MutableStateFlow(PerfTier.FULL)
        )
        runtime.appInForeground = true
        var waitedMs = 0L
        while (!runtime.gate().masterEnabled() && waitedMs < 2_000L) {
            delay(10)
            waitedMs += 10
        }
        assertTrue(runtime.gate().masterEnabled())
        return runtime
    }

    @Test
    fun `static block, render fails, bridge answers - in that order`() =
        runBlocking {
            val renderer = ScriptedRenderer(bridgeBody = "BRIDGE-ANSWER")
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                FakeTransport { AppResult.Ok(FakeTransport.response(403, "blocked")) },
                renderedFetcher = renderer,
                renderRuntime = runtimeReady()
            )
            val key = "esc-route-a"
            val perms = ExtensionPermissions(listOf("esc.example"))
            val client = factory.clientFor(key, "esc", perms)
            val fetcher = factory.escalatingFetcherOrNull(
                client, key, RenderPurpose.SOURCE,
                permissions = { perms },
                rateLimiter = RateLimiter(minIntervalMs = 0)
            )
            assertTrue("factory must build the escalating fetcher", fetcher != null)
            val result = fetcher!!.get("https://esc.example/book/1")
            val ok = result as? AppResult.Ok
                ?: throw AssertionError("expected the bridge answer, got $result")
            assertEquals("BRIDGE-ANSWER", ok.value.body)
            assertEquals(
                "routing order must be render-then-bridge",
                listOf("render", "bridge"), renderer.calls
            )
            Unit
        }

    @Test
    fun `render health struck - the render leg is skipped and the bridge answers directly`() =
        runBlocking {
            val renderer = ScriptedRenderer(bridgeBody = "BRIDGE-DIRECT")
            val factory = HostContextFactory(
                ExtensionStore(tmp.newFolder()),
                testSessionStore(),
                FakeTransport { AppResult.Ok(FakeTransport.response(403, "blocked")) },
                renderedFetcher = renderer,
                renderRuntime = runtimeReady()
            )
            val key = "esc-route-b"
            // The strike: a recent render failure puts this
            // source on the bridge road for the cooldown.
            RenderHealth.recordFailure(key)
            assertTrue(!RenderHealth.renderAllowed(key))
            val perms = ExtensionPermissions(listOf("esc.example"))
            val client = factory.clientFor(key, "esc", perms)
            val fetcher = factory.escalatingFetcherOrNull(
                client, key, RenderPurpose.SOURCE,
                permissions = { perms },
                rateLimiter = RateLimiter(minIntervalMs = 0)
            )!!
            val result = fetcher.get("https://esc.example/book/2")
            val ok = result as? AppResult.Ok
                ?: throw AssertionError("expected the bridge answer, got $result")
            assertEquals("BRIDGE-DIRECT", ok.value.body)
            assertEquals(
                "no render may be attempted while health is struck",
                listOf("bridge"), renderer.calls
            )
            Unit
        }

    @Test
    fun `healthy static page never touches render or bridge`() = runBlocking {
        val renderer = ScriptedRenderer(bridgeBody = "UNUSED")
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()),
            testSessionStore(),
            FakeTransport {
                AppResult.Ok(
                    FakeTransport.response(
                        200,
                        "<html><body><article><p>A plain book page.</p>" +
                            "</article></body></html>"
                    )
                )
            },
            renderedFetcher = renderer,
            renderRuntime = runtimeReady()
        )
        val key = "esc-route-c"
        val perms = ExtensionPermissions(listOf("esc.example"))
        val client = factory.clientFor(key, "esc", perms)
        val fetcher = factory.escalatingFetcherOrNull(
            client, key, RenderPurpose.SOURCE,
            permissions = { perms },
            rateLimiter = RateLimiter(minIntervalMs = 0)
        )!!
        val result = fetcher.get("https://esc.example/book/3")
        assertTrue("static page must answer directly: $result", result is AppResult.Ok)
        assertEquals(
            "no escalation for a healthy page",
            emptyList<String>(), renderer.calls
        )
        Unit
    }
}
