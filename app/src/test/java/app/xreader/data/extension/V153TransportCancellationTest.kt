package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.net.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.5.3 (finding #6, spec item 18): cancellation must reach the
 * body phase. A drip-feeding body parks the client's blocking read;
 * when the caller's coroutine is cancelled (the fetcher's 75 s
 * budget expiring is the production case), the read must abort
 * promptly — the client closes the body stream, which makes the
 * parked read throw at once — instead of holding the call open
 * until OkHttp's 300 s call timeout.
 */
class V153TransportCancellationTest {

    /**
     * A body stream that models a drip-feed: [read] parks until
     * [close] releases it, then throws like a real socket stream
     * does when closed mid-read. Nothing ever arrives and EOF
     * never comes on its own.
     */
    private class DripFeedStream : InputStream() {
        val readEntered = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)

        @Volatile
        var closeCalls = 0
            private set

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            readEntered.countDown()
            // Parked: only close() (or the 120 s test-safety net,
            // far beyond every budget asserted here) releases us.
            releaseLatch.await(120, TimeUnit.SECONDS)
            throw IOException("stream closed")
        }

        override fun read(): Int {
            readEntered.countDown()
            releaseLatch.await(120, TimeUnit.SECONDS)
            throw IOException("stream closed")
        }

        override fun close() {
            closeCalls++
            releaseLatch.countDown()
        }
    }

    private fun client(transport: FakeTransport): ExtensionHttpClient =
        ExtensionHttpClient(
            transport = transport,
            sessionStore = testSessionStore(),
            sessionKey = "test-session",
            label = "Test Source",
            whitelist = {
                ExtensionPermissions(domains = listOf("novels.example"))
            },
            rateLimiter = RateLimiter(minIntervalMs = 0)
        )

    @Test
    fun `cancelling the call aborts a parked body read promptly`() = runBlocking {
        val stream = DripFeedStream()
        val transport = FakeTransport {
            AppResult.Ok(
                HttpTransport.Response(
                    code = 200,
                    headers = emptyMap(),
                    body = stream,
                    isPartialContent = false
                )
            )
        }
        // The call runs on an independent scope: on the pre-fix
        // code it never completes after cancellation, and the
        // test must fail on its assertions, not hang the suite.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val call = scope.async {
                client(transport).get("https://novels.example/book/1")
            }
            assertTrue(
                "the body read must be entered",
                stream.readEntered.await(5, TimeUnit.SECONDS)
            )
            val cancelAt = System.nanoTime()
            call.cancel()
            var completed = false
            try {
                // Null return = the timeout had to fire because
                // await() never returned (the parked read ignored
                // the cancellation). A CancellationException means
                // the cancelled fetch surfaced promptly.
                completed = withTimeoutOrNull(ABORT_BUDGET_MS) {
                    call.await()
                } != null
            } catch (expected: CancellationException) {
                completed = true
            }
            val elapsedMs = (System.nanoTime() - cancelAt) / 1_000_000
            println("V153-WS4: abort elapsed after cancel = ${elapsedMs} ms")
            assertTrue(
                "the cancelled call must complete promptly after cancel " +
                    "(still parked after $elapsedMs ms)",
                completed
            )
            assertTrue(
                "closing the body stream must be what releases the read",
                stream.closeCalls > 0
            )
            assertTrue(
                "abort must be orders of magnitude below the 300 s " +
                    "call timeout (was $elapsedMs ms)",
                elapsedMs < ABORT_BUDGET_MS
            )
        } finally {
            // Release the parked read thread even on the failing
            // (pre-fix) path so the suite is never held hostage.
            stream.close()
            scope.cancel()
        }
    }

    @Test
    fun `ordinary body read still returns the body`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.ok("<html>chapter text</html>")
        }
        val result = client(transport).get("https://novels.example/book/1")
        assertTrue(result is AppResult.Ok)
        assertEquals(
            "<html>chapter text</html>",
            (result as AppResult.Ok).value.body
        )
    }

    private companion object {
        /** The promptness bar: 5 s — vs the 300 s call timeout
         * the pre-fix read would have run to. */
        const val ABORT_BUDGET_MS = 5_000L
    }
}
