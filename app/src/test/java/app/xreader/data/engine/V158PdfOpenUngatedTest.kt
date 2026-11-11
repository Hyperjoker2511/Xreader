package app.xreader.data.engine

import android.app.Application
import app.xreader.data.parse.PdfPageBounds
import app.xreader.data.parse.PdfStripResult
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.8 (F4): the PDF open is UNGATED from the full-document
 * strip. The session seam [PdfStripSession] is what
 * [PdfSession.open] constructs and starts before returning
 * Ready: with the strip artificially stalled behind a latch,
 * the session exists and reports Pending (reading proceeds —
 * PdfSession never awaits it); when the latch releases, the
 * strip lands AND persists; a second session over the same
 * store reads the persisted strip with ZERO stripper
 * invocations (the wait is paid once, ever).
 *
 * v1.5.10 (I6c) — the loader-invocation race, named and closed:
 * [PdfStripSession.start] launches the strip on the caller's
 * scope and returns immediately, so the loader runs on the
 * scope's dispatcher thread at an unbounded later time. The
 * first test below originally asserted the loader's invocation
 * count synchronously after start() — no happens-before edge
 * orders that read after the launched coroutine's first step,
 * so under load it read 0 (archived failures 2026-11-05:
 * "expected:<1> but was:<0>" at the invocation assert, green
 * on rerun). The fix is in this test only: the loader signals
 * its own entry on a deferred the test awaits (with a
 * timeout), and the counter is atomic — the assertion now
 * reads a state the test has PROVEN was reached, while the
 * gate latch still holds the strip stalled behind it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V158PdfOpenUngatedTest {

    @get:Rule val temp = TemporaryFolder()

    private val fixtureResult = PdfStripResult(
        texts = listOf("page one text", "page two text"),
        bounds = listOf(PdfPageBounds(72f, 80f, 500f, 700f), null)
    )

    @Test
    fun `stalled strip does not gate the session, then lands and persists`() = runBlocking {
        val dir = temp.newFolder()
        val store = PdfStripStore(dir)
        val gate = CompletableDeferred<Unit>()
        val invocations = java.util.concurrent.atomic.AtomicInteger(0)
        // I6c: the loader's own entry signal — the happens-before
        // edge the invocation assertion needs (see class header).
        val loaderEntered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = PdfStripSession(
                store = store,
                fingerprint = "fp-1",
                loader = {
                    invocations.incrementAndGet()
                    loaderEntered.complete(Unit)
                    gate.await()
                    fixtureResult
                },
                scope = scope
            )
            session.start()
            // Wait until the launched strip has PROVENLY entered
            // the loader; it is then parked on the gate latch, so
            // every assertion below reads a settled state.
            withTimeout(10_000) { loaderEntered.await() }
            // The strip is running but stalled: the session is
            // already usable (this is the state PdfSession.open
            // returns Ready into) and reports Pending honestly.
            assertTrue(session.state.value is PdfStripState.Pending)
            assertEquals(null, session.snapshot())
            assertEquals(1, invocations.get())

            gate.complete(Unit)
            val landed = withTimeout(10_000) {
                var value = session.state.value
                while (value !is PdfStripState.Ready) {
                    kotlinx.coroutines.delay(20)
                    value = session.state.value
                }
                value
            }
            assertEquals(fixtureResult.texts, landed.result.texts)
            assertEquals("page two text", session.textAt(1))
            // Persisted under the fingerprint.
            assertEquals(
                fixtureResult.texts,
                PdfStripStore(dir).read("fp-1")?.texts
            )
            assertEquals(
                fixtureResult.bounds,
                PdfStripStore(dir).read("fp-1")?.bounds
            )
            // A different fingerprint is a miss (invalidation).
            assertEquals(null, PdfStripStore(dir).read("fp-OTHER"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `second open reads the persisted strip without stripping`() = runBlocking {
        val dir = temp.newFolder()
        PdfStripStore(dir).write("fp-1", fixtureResult)
        var invocations = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = PdfStripSession(
                store = PdfStripStore(dir),
                fingerprint = "fp-1",
                loader = {
                    invocations++
                    fixtureResult
                },
                scope = scope
            )
            session.start()
            withTimeout(10_000) {
                while (session.state.value !is PdfStripState.Ready) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertEquals(0, invocations)
            assertEquals("page one text", session.textAt(0))
            assertEquals(fixtureResult.bounds[0], session.boundsAt(0))
            assertEquals(null, session.boundsAt(1))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed strip settles unavailable, never pending forever`() = runBlocking {
        val dir = temp.newFolder()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = PdfStripSession(
                store = PdfStripStore(dir),
                fingerprint = "fp-x",
                loader = { throw IllegalStateException("no text layer") },
                scope = scope
            )
            session.start()
            withTimeout(10_000) {
                while (session.state.value is PdfStripState.Pending) {
                    kotlinx.coroutines.delay(20)
                }
            }
            assertTrue(session.state.value is PdfStripState.Unavailable)
            assertEquals("", session.textAt(0))
        } finally {
            scope.cancel()
        }
    }
}
