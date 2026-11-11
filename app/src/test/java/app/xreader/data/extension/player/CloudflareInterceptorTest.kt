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
 *
 * v1.5.4 (WS-1 item 6): the classes are split — the donor-detected
 * JS/captcha class is auto-solved in-process (an injectable solver
 * stands in for the WebView here) and replayed once; any other
 * Cloudflare-served error page (the wall class) fails FAST into
 * the same honest IOException — no bridge wait anywhere on the
 * load path. The bridge's user solve is no longer consulted by
 * the interceptor at all.
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

            // v1.5.4: the solve is the in-process auto-solve —
            // the bridge (and its user wait) is never consulted.
            var solverCalls = 0
            val result = CloudflareInterceptor(jar, bridge({ false })) {
                solverCalls++
                true
            }.intercept(chain)

            assertSame(ok, result)
            assertEquals(1, solverCalls)
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
            CloudflareInterceptor(jar, bridge({ false })) { false }
                .intercept(chain)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("asurascans.com"))
            assertTrue(expected.message!!.contains("check"))
        }
        assertEquals(1, chain.proceedCount)
    }

    @Test
    fun `cloudflare wall page fails fast with no solve attempt`() {
        // The genuinely interactive / block class: a
        // Cloudflare-served error page WITHOUT the donor's
        // challenge elements (the real BrightNovel wall's
        // shape). The load fails fast into the honest state —
        // solver never invoked, nothing replayed, and no wait
        // (the bridge here would take 5 s to time out if the
        // interceptor still consulted it; the elapsed bound
        // proves it does not).
        val wall = response(
            403, "cloudflare",
            "<html><body><div id=\"cf-error-details\">" +
                "Sorry, you have been blocked</div></body></html>"
        )
        val chain = FakeChain(request, listOf(wall))
        var solverCalls = 0
        val started = System.nanoTime()
        try {
            CloudflareInterceptor(jar, bridge({ false }, timeoutMillis = 5_000)) {
                solverCalls++
                true
            }.intercept(chain)
            fail("expected IOException")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("check"))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, solverCalls)
        assertEquals(1, chain.proceedCount)
        assertTrue("wall must fail fast, took ${elapsedMs}ms", elapsedMs < 4_000)
    }

    @Test
    fun `solved replay preserves the original POST`() {
        // The Madara case (item 6a): a challenged POST to
        // admin-ajax is replayed as the SAME request — method
        // and body intact — exactly once.
        val post = Request.Builder()
            .url("https://asurascans.com/wp-admin/admin-ajax.php")
            .post(
                okhttp3.FormBody.Builder()
                    .add("action", "manga_get_chapters")
                    .add("manga", "42")
                    .build()
            )
            .build()
        val challenge = response(403, "cloudflare", challengePage)
            .newBuilder().request(post).build()
        val ok = response(200, "cloudflare", "<html>chapters</html>")
            .newBuilder().request(post).build()
        val chain = FakeChain(post, listOf(challenge, ok))

        val result = CloudflareInterceptor(jar, bridge({ false })) { true }
            .intercept(chain)

        assertSame(ok, result)
        assertEquals(2, chain.proceedCount)
        val replayed = chain.proceededRequests[1]
        assertEquals("POST", replayed.method)
        val buffer = okio.Buffer()
        replayed.body!!.writeTo(buffer)
        assertTrue(buffer.readUtf8().contains("action=manga_get_chapters"))
    }

    private class FakeChain(
        private val req: Request,
        responses: List<Response>
    ) : Interceptor.Chain {
        private val queue = ArrayDeque(responses)

        var proceedCount = 0
            private set

        val proceededRequests = mutableListOf<Request>()

        override fun request(): Request = req

        override fun proceed(request: Request): Response {
            proceedCount++
            proceededRequests += request
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
