package app.xreader.data.extension.player

import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.testVault
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.5.12 (Front 1, S5): the Cloudflare interceptor's
 * post-solve harvest is BOUNDED. The v1.5.11 awaited shape
 * had no timeout of its own — a harvest that never returns
 * would park the OkHttp dispatcher thread until the player
 * client's 120 s call timeout. Here a never-returning
 * harvest (the injected seam stands in for
 * PlayerWebViewSync) cannot hold the interceptor past its
 * bound: the replay proceeds without the fresh harvest,
 * exactly the honest degradation the fix prescribes.
 */
class V1512HarvestTimeoutTest {

    private val vault = testVault()
    private val store = SessionStore(vault)
    private val jar = SessionStoreCookieJar(store, vault)

    private val request: Request =
        Request.Builder().url("https://asurascans.com/browse").build()

    private fun bridge() = PlayerChallengeBridge(
        sessionSavedEvents = MutableSharedFlow(extraBufferCapacity = 4),
        hasClearance = { false },
        timeoutMillis = 5_000
    )

    private fun response(code: Int, bodyText: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("m")
            .header("Server", "cloudflare")
            .body(bodyText.toResponseBody("text/html".toMediaType()))
            .build()

    private val challengePage =
        "<html><body><div id=\"challenge-error-title\">" +
            "Attention Required!</div></body></html>"

    @Test
    fun `a harvest that never returns cannot park the interceptor beyond the bound`() {
        val challenge = response(403, challengePage)
        val ok = response(200, "<html>real page</html>")
        val chain = FakeChain(request, listOf(challenge, ok))
        val harvestStarted = AtomicBoolean(false)
        val interceptor = CloudflareInterceptor(
            jar, bridge(),
            autoSolve = { true },
            harvest = {
                harvestStarted.set(true)
                // The hung harvest: never returns inside any
                // bound the test will wait for.
                delay(60_000)
            },
            harvestTimeoutMs = 300
        )
        val started = System.nanoTime()
        val result = interceptor.intercept(chain)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the harvest must have been attempted", harvestStarted.get())
        assertSame(
            "the replay must proceed without the fresh harvest",
            ok, result
        )
        assertEquals(2, chain.proceedCount)
        assertTrue(
            "the interceptor must be released near the 300 ms bound, " +
                "not the 120 s call timeout (took ${elapsedMs}ms)",
            elapsedMs < 5_000
        )
    }

    @Test
    fun `a prompt harvest still lands before the replay`() {
        val order = mutableListOf<String>()
        val challenge = response(403, challengePage)
        val ok = response(200, "<html>real page</html>")
        val chain = object : FakeChain(request, listOf(challenge, ok)) {
            override fun onProceed() {
                order += "proceed"
            }
        }
        val interceptor = CloudflareInterceptor(
            jar, bridge(),
            autoSolve = { true },
            harvest = { order += "harvest" },
            harvestTimeoutMs = 5_000
        )
        val result = interceptor.intercept(chain)
        assertSame(ok, result)
        assertEquals(
            "harvest first, replay second — the v1.5.11 ordering is kept",
            listOf("proceed", "harvest", "proceed"), order
        )
    }

    @Test
    fun `the shipped bound sits well under the call timeout`() {
        assertTrue(
            "the production harvest bound must be well under 120 s",
            CloudflareInterceptor.HARVEST_TIMEOUT_MS in 1_000..30_000
        )
    }

    private open class FakeChain(
        private val req: Request,
        responses: List<Response>
    ) : Interceptor.Chain {
        private val queue = ArrayDeque(responses)

        var proceedCount = 0
            private set

        open fun onProceed() = Unit

        override fun request(): Request = req

        override fun proceed(request: Request): Response {
            proceedCount++
            onProceed()
            return queue.removeFirst()
        }

        override fun connection(): Connection? = null

        override fun call(): Call =
            throw UnsupportedOperationException("no call in the fake chain")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
