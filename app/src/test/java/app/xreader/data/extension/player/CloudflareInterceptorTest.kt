package app.xreader.data.extension.player

import app.xreader.data.extension.SessionStore
import app.xreader.data.extension.testVault
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * K2: the Cloudflare interceptor — the donor's detection (403/503 from
 * a Cloudflare server with the challenge elements in the body), the
 * donor's flow (drop stale clearance, solve, re-proceed), and our
 * substitution (the solve is the sanctioned-screen bridge, not a
 * hidden WebView). Failures surface as IOException.
 */
class CloudflareInterceptorTest {

    private val vault = testVault()
    private val store = SessionStore(vault)
    private val jar = SessionStoreCookieJar(store, vault)

    private val request: Request =
        Request.Builder().url("https://asurascans.com/browse").build()

    private fun bridge(
        clearance: () -> Boolean,
        timeoutMillis: Long = 5_000
    ) = PlayerChallengeBridge(
        sessionSavedEvents = MutableSharedFlow(extraBufferCapacity = 4),
        hasClearance = { clearance() },
        timeoutMillis = timeoutMillis
    )

    private fun response(code: Int, server: String?, bodyText: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("m")
            .apply { if (server != null) header("Server", server) }
            .body(bodyText.toResponseBody("text/html".toMediaType()))
            .build()

    private val challengePage =
        "<html><body><div id=\"challenge-error-title\">" +
            "Attention Required!</div></body></html>"

    @Test
    fun `ordinary responses pass through untouched`() {
        val ok = response(200, "cloudflare", "<html>fine</html>")
        val chain = FakeChain(request, listOf(ok))
        val result = CloudflareInterceptor(jar, bridge({ false })).intercept(chain)
        assertSame(ok, result)
        assertEquals(1, chain.proceedCount)
    }

    @Test
    fun `403 from a non-cloudflare server passes through`() {
        val forbidden = response(403, "nginx", challengePage)
        val chain = FakeChain(request, listOf(forbidden))
        val result = CloudflareInterceptor(jar, bridge({ false })).intercept(chain)
        assertSame(forbidden, result)
        assertEquals(1, chain.proceedCount)
    }

    @Test
    fun `cloudflare 403 without challenge elements passes through`() {
        // A geo-block / plain denial is not a solvable challenge.
        val blocked = response(403, "cloudflare", "<html>denied</html>")
        val chain = FakeChain(request, listOf(blocked))
        val result = CloudflareInterceptor(jar, bridge({ false })).intercept(chain)
        assertSame(blocked, result)
        assertEquals(1, chain.proceedCount)
    }

    @Test
    fun `challenge solved re-proceeds and drops the stale clearance first`() =
        runBlocking {
            store.mergeCookies(
                SessionStoreCookieJar.PLAYER_SESSION_KEY,
                "asurascans.com",
                mapOf("cf_clearance" to "stale", "session" to "s-1")
            )
            val challenge = response(403, "cloudflare", challengePage)
            val ok = response(200, "cloudflare", "<html>real page</html>")
            val chain = FakeChain(request, listOf(challenge, ok))

            val result = CloudflareInterceptor(jar, bridge({ true })).intercept(chain)

            assertSame(ok, result)
            assertEquals(2, chain.proceedCount)
            // The donor removes the old cf_clearance before solving;
            // ours is gone from the store, the session cookie remains.
            val cookies = jar.loadForRequest(request.url)
            assertTrue(cookies.none { it.name == "cf_clearance" })
            assertTrue(cookies.any { it.name == "session" })
        }

    @Test
    fun `challenge unsolved throws IOException`() {
        val challenge = response(503, "cloudflare", challengePage)
        val chain = FakeChain(request, listOf(challenge))
        try {
            CloudflareInterceptor(jar, bridge({ false }, timeoutMillis = 300))
                .intercept(chain)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("asurascans.com"))
        }
        assertEquals(1, chain.proceedCount)
    }

    private class FakeChain(
        private val req: Request,
        responses: List<Response>
    ) : Interceptor.Chain {
        private val queue = ArrayDeque(responses)

        var proceedCount = 0
            private set

        override fun request(): Request = req

        override fun proceed(request: Request): Response {
            proceedCount++
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
