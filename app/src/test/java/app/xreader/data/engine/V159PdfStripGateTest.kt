package app.xreader.data.engine

import android.app.Application
import app.xreader.data.parse.PdfPageBounds
import app.xreader.data.parse.PdfStripResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (sift A-5): the strip PAUSES behind the session gate
 * — while the pager is scrolling or any P0/P1 ticket is
 * outstanding the strip does not run; it resumes when the
 * flip sequence settles. No MIN_PRIORITY tricks, no fixed
 * per-page delay: the gate is the mechanism. (A persisted
 * strip is served without the gate — see the F4 tests.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V159PdfStripGateTest {

    @get:Rule val temp = TemporaryFolder()

    private val fixtureResult = PdfStripResult(
        texts = listOf("page one text"),
        bounds = listOf(PdfPageBounds(72f, 80f, 500f, 700f))
    )

    @Test
    fun `strip holds behind a closed gate and resumes when it opens`() = runBlocking {
        val dir = temp.newFolder()
        val gate = MutableStateFlow(false)
        var invocations = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = PdfStripSession(
                store = PdfStripStore(dir),
                fingerprint = "gate-1",
                loader = {
                    invocations++
                    fixtureResult
                },
                scope = scope,
                gate = gate
            )
            session.start()
            delay(400)
            assertEquals("the strip never started behind the gate", 0, invocations)
            assertTrue(session.state.value is PdfStripState.Pending)

            gate.value = true
            withTimeout(10_000) {
                while (session.state.value !is PdfStripState.Ready) delay(20)
            }
            assertEquals(1, invocations)
            assertEquals("page one text", session.textAt(0))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `session gate closes while scrolling or a P0 ticket is outstanding`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider(renderDelayMs = 200)
        val session = V159PdfSessionFixtures.openSession(temp.root, 218, provider)
        try {
            assertTrue("gate open at rest", session.stripGate.value)
            session.publishPagerState(settledPage = 2, targetPage = 2, scrolling = true)
            assertFalse("pager scrolling closes the gate", session.stripGate.value)
            session.publishPagerState(settledPage = 2, targetPage = 2, scrolling = false)
            assertTrue("settle reopens the gate", session.stripGate.value)

            val render = async {
                session.renderPage(2, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0)
            }
            // The P0 ticket is outstanding while it renders.
            withTimeout(10_000) {
                while (session.stripGate.value) delay(10)
            }
            assertFalse("P0 outstanding closes the gate", session.stripGate.value)
            render.await()
            withTimeout(10_000) {
                while (!session.stripGate.value) delay(10)
            }
            assertTrue("gate reopens when the flip sequence settles", session.stripGate.value)
        } finally {
            session.close()
        }
    }
}
