@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.xreader.data

import app.xreader.data.net.OkHttpTransport
import app.xreader.data.net.HttpTransport
import app.xreader.ui.sources.StreamingSearch
import java.net.ServerSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2 (§10 item 4 machine legs): the search envelope — 0.9× /
 * 1.1× budget legs, the distinct footnotes, and call.cancel()
 * evidence at the OkHttp layer (Failure C's mechanism).
 *
 * Round-15 closure: the two classification legs run on VIRTUAL
 * time — the coordinator's dispatcher and clock are the test
 * scheduler's (StandardTestDispatcher + testScheduler.currentTime
 * injected through the same two parameters GlobalSearch injects
 * the real ones through), and each boundary is asserted at
 * T−1 / T / T+1 steps. The previous shape ran these legs on the
 * wall clock with ~30 ms margins (270 ms answer vs a 300 ms
 * slice): under a loaded gate machine the answer itself could
 * overrun the slice and flip its own classification — the
 * round-1 flake's mechanism. No wall-clock margin remains in
 * any classification assertion in this class; the only real-time
 * leg left is the socket test, whose bound is 5 s against a
 * mechanism that settles in milliseconds.
 */
class V152EnvelopeTest {

    @Test
    fun `legs at 0 9x answer, 1 1x times out as didnt answer`() = runTest {
        val testScope = this
        val results = mutableMapOf<String, Result<String>>()
        var footnotes: StreamingSearch.Footnotes<String>? = null
        val job = backgroundScope.launch {
            StreamingSearch.run(
                items = listOf("quick", "slow"),
                parallelism = 2,
                perItemTimeoutMs = 300,
                legDispatcher = StandardTestDispatcher(testScheduler),
                nowMs = { testScope.testScheduler.currentTime },
                search = { item ->
                    if (item == "quick") delay(270) else delay(330)
                    "hits-$item"
                },
                onResult = { item, result -> results[item] = result },
                onProgress = { _, _ -> },
                onFootnotes = { footnotes = it }
            )
        }
        runCurrent()
        // T−1 for the quick leg (269): nothing has settled.
        advanceTimeBy(269)
        runCurrent()
        assertTrue("settled early: $results", results.isEmpty())
        // T (270): the 0.9× leg answers; the 1.1× leg has not.
        advanceTimeBy(1)
        runCurrent()
        assertEquals("hits-quick", results["quick"]!!.getOrNull())
        assertNull(results["slow"])
        assertNull(footnotes)
        // T−1 for the slice (299): the slow leg is still inside it.
        advanceTimeBy(29)
        runCurrent()
        assertNull(results["slow"])
        // T (300): the slice closes — the slow leg is "didn't
        // answer", exactly, and the run completes.
        advanceTimeBy(1)
        runCurrent()
        assertTrue(results["slow"]!!.isFailure)
        assertEquals(listOf("slow"), footnotes!!.didntAnswer)
        assertEquals(emptyList<String>(), footnotes!!.notReached)
        job.cancel()
    }

    @Test
    fun `a source never asked because the aggregate closed is not reached`() =
        runTest {
            val testScope = this
            val results = mutableMapOf<String, Result<String>>()
            var footnotes: StreamingSearch.Footnotes<String>? = null
            val job = backgroundScope.launch {
                StreamingSearch.run(
                    items = listOf("a", "b", "c"),
                    parallelism = 1,
                    perItemTimeoutMs = 200,
                    aggregateDeadlineMs = 250,
                    legDispatcher = StandardTestDispatcher(testScheduler),
                    nowMs = { testScope.testScheduler.currentTime },
                    search = { item -> delay(5_000); "hits-$item" },
                    onResult = { item, result -> results[item] = result },
                    onProgress = { _, _ -> },
                    onFootnotes = { footnotes = it }
                )
            }
            runCurrent()
            // T−1 (199): a is still inside its slice.
            advanceTimeBy(199)
            runCurrent()
            assertTrue("settled early: $results", results.isEmpty())
            // T (200): a's slice closes — a is "didn't answer";
            // b starts at this exact step (asked at leg start:
            // b is inside the deterministic asked limit, so its
            // classification was decided before it ran).
            advanceTimeBy(1)
            runCurrent()
            assertEquals(setOf("a"), results.keys)
            assertNull(footnotes)
            // T−1 (249): b is inside its remaining aggregate (50 ms).
            advanceTimeBy(49)
            runCurrent()
            assertEquals(setOf("a"), results.keys)
            assertNull(footnotes)
            // T (250): the aggregate closes on b — both asked legs
            // are "didn't answer"; c was never asked and is
            // "not reached". The two footnotes are distinct.
            advanceTimeBy(1)
            runCurrent()
            assertEquals(setOf("a", "b"), footnotes!!.didntAnswer.toSet())
            assertEquals(listOf("c"), footnotes!!.notReached)
            assertTrue(results["c"] == null)
            job.cancel()
        }

    @Test
    fun `cancelling the coroutine cancels the OkHttp call`() = runBlocking {
        // A server that accepts and never answers: without
        // invokeOnCancellation { call.cancel() } the blocking
        // execute() would hold the leg until the 300 s call
        // timeout (Failure C). With it, cancellation lands at the
        // socket within moments.
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val accepter = Thread {
            runCatching {
                val socket = server.accept()
                Thread.sleep(30_000)
                socket.close()
            }
        }.apply { isDaemon = true; start() }
        try {
            val transport = OkHttpTransport()
            val deferred = async {
                transport.execute(
                    HttpTransport.Request(
                        url = "http://127.0.0.1:${server.localPort}/hang"
                    )
                )
            }
            delay(400)
            val started = System.currentTimeMillis()
            deferred.cancel()
            val outcome = withTimeoutOrNull(5_000) {
                try {
                    deferred.await()
                    "completed"
                } catch (e: CancellationException) {
                    "cancelled"
                }
            }
            val elapsed = System.currentTimeMillis() - started
            assertNotNull("call must settle promptly after cancel", outcome)
            assertEquals("cancelled", outcome)
            assertTrue("elapsed ${elapsed}ms", elapsed < 5_000)
        } finally {
            runCatching { server.close() }
        }
    }
}
