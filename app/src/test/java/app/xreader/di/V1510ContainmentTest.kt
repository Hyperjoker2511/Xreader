package app.xreader.di

import app.xreader.core.error.CrashGuard
import app.xreader.data.extension.DiagnosticLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.10 (A2) verification, at the level that is provable on the
 * JVM:
 *  (i) the containment handler + contained-note writer seam —
 *      driven through a scope built EXACTLY as AppContainer builds
 *      appScope (SupervisorJob + IO/Default + the CrashGuard
 *      handler), with a recorder composed from the same production
 *      primitives AppContainer.recordContainedFailure composes
 *      (CrashGuard.writeContainedNote + DiagnosticLog.record with
 *      CrashGuard.framesText). AppContainer's literal scope needs
 *      an Android Context and is census-verified, not driven —
 *      see the lane report.
 *  (iii) scope-level: a failing child neither cancels its sibling
 *      nor poisons the scope for later launches.
 */
class V1510ContainmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private data class Recorded(val site: String, val thread: String, val error: Throwable)

    private val recorded = CopyOnWriteArrayList<Recorded>()
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        DiagnosticLog.clear()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        DiagnosticLog.clear()
    }

    /** The recorder composition AppContainer uses in production,
     * with the Context-bound note writer swapped for its File-based
     * production twin over a temp filesDir. */
    private fun productionShapedRecorder(filesDir: File) =
        CrashGuard.ContainedFailureRecorder { site, threadName, throwable ->
            CrashGuard.writeContainedNote(filesDir, threadName, site, throwable)
            DiagnosticLog.record(
                tag = "contained",
                message = "Contained failure at $site: " +
                    "${throwable.javaClass.name} — the app kept running.",
                detail = CrashGuard.framesText(throwable)
            )
            recorded += Recorded(site, threadName, throwable)
        }

    /** AppContainer.appScope's construction, replicated shape for
     * shape (its dispatcher is IO; Default is used here so the
     * test never touches Android's IO pool assumptions — the
     * containment semantics under test are dispatcher-independent). */
    private fun appScopeShaped(filesDir: File): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CrashGuard.containmentHandler("appScope", productionShapedRecorder(filesDir))
        ).also { scopes += it }

    private fun handBuiltNpe(): NullPointerException {
        val t = NullPointerException("must-never-appear")
        t.stackTrace = arrayOf(
            StackTraceElement("com.example.alpha.Foo", "bar", "Foo.kt", 31337),
            StackTraceElement("com.example.beta.Baz", "qux", "Baz.kt", 42424),
            StackTraceElement("com.example.gamma.Quux", "zap", "Quux.kt", 55555)
        )
        return t
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue("condition not met within ${timeoutMs}ms", cond())
    }

    @Test
    fun `failing child is contained - recorded with marker, frames in note and log, no crash marker`() {
        val filesDir = tmp.newFolder("files")
        val scope = appScopeShaped(filesDir)
        val siblingDone = CountDownLatch(1)

        scope.launch { throw handBuiltNpe() }
        scope.launch { siblingDone.countDown() }

        // Sibling survived the failure (supervisor semantics).
        assertTrue(siblingDone.await(5, TimeUnit.SECONDS))
        // The failure reached the recorder as a contained failure.
        awaitTrue { recorded.size == 1 }
        assertEquals("appScope", recorded[0].site)
        assertTrue(recorded[0].error is NullPointerException)

        // Note artefact: written, carries the marker + frames, and
        // the crash marker was NOT written.
        val notesDir = File(filesDir, "crash-notes")
        awaitTrue { (notesDir.listFiles()?.size ?: 0) == 1 }
        val noteText = notesDir.listFiles()!!.single().readText()
        assertTrue(noteText.contains(CrashGuard.CONTAINED_MARKER))
        assertTrue(noteText.contains("com.example.alpha.Foo.bar"))
        assertTrue(noteText.contains("Message: must-never-appear"))
        assertFalse(File(filesDir, CrashGuard.MARKER_FILE).exists())

        // DiagnosticLog artefact: one contained entry whose detail
        // carries A1's frames.
        val entries = DiagnosticLog.entries().filter { it.tag == "contained" }
        assertEquals(1, entries.size)
        assertTrue(entries[0].detail.contains("com.example.alpha.Foo.bar"))
        assertTrue(entries[0].message.contains("NullPointerException"))
    }

    @Test
    fun `scope stays usable after a contained failure`() {
        val filesDir = tmp.newFolder("files2")
        val scope = appScopeShaped(filesDir)
        scope.launch { throw handBuiltNpe() }
        awaitTrue { recorded.size == 1 }
        val after = CountDownLatch(1)
        scope.launch { after.countDown() }
        assertTrue(after.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `cancellation is never recorded as a contained failure`() {
        val filesDir = tmp.newFolder("files3")
        val scope = appScopeShaped(filesDir)
        val job = scope.launch { throw CancellationException("normal cancel") }
        awaitTrue { job.isCompleted }
        Thread.sleep(200)
        assertTrue(recorded.isEmpty())
        assertTrue(DiagnosticLog.entries().isEmpty())
    }

    @Test
    fun `handler ignores cancellation thrown directly`() {
        val filesDir = tmp.newFolder("files4")
        val handler = CrashGuard.containmentHandler(
            "direct", productionShapedRecorder(filesDir)
        )
        handler.handleException(
            kotlin.coroutines.EmptyCoroutineContext, CancellationException("x")
        )
        assertTrue(recorded.isEmpty())
    }
}
