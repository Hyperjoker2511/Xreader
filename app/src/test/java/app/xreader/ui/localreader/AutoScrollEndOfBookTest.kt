package app.xreader.ui.localreader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.9 (F-7a, Finding 4 A-2): scroll-mode auto-scroll stops
 * at the end of the book. The surface under test is built from
 * the PRODUCTION step shape ([epubScrollAdvance]) — mid-resource
 * steps move the progression and report true; the step that
 * crosses the resource end returns goForward's own verdict,
 * and when the book is finished that verdict is false, which
 * is what stops the engine. The shipped code discarded the
 * verdict and returned true unconditionally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoScrollEndOfBookTest {

    /** A fake book of two resources: stepping inside resource 0
     * moves its progression; crossing into resource 1 works
     * once; at the end of resource 1 goForward reports false. */
    private class FakeBook {
        var resource = 0
        var progression = 0.995
        val steppedTo = mutableListOf<Double>()
        var forwardCalls = 0

        fun goForward(): Boolean {
            forwardCalls++
            return if (resource == 0) {
                resource = 1
                progression = 0.0
                true
            } else {
                false // the end of the book
            }
        }

        fun surface() = AutoScrollEngine.Surface {
            epubScrollAdvance(
                currentProgression = progression,
                step = AutoScrollEngine.progressionStep(1.0),
                goForward = { goForward() },
                goToProgression = { next ->
                    progression = next
                    steppedTo += next
                }
            )
        }
    }

    @Test
    fun `mid-resource steps move the progression and keep going`() {
        val book = FakeBook().apply { progression = 0.40 }
        var moved = false
        kotlinx.coroutines.runBlocking {
            moved = book.surface().advance()
        }
        assertTrue(moved)
        assertEquals(listOf(0.40 + 0.002), book.steppedTo)
        assertEquals(0, book.forwardCalls)
    }

    @Test
    fun `scroll-mode engine stops at the end of the book`() = runTest {
        val book = FakeBook()
        val engine = AutoScrollEngine()
        engine.start(
            this, book.surface(),
            intervalMs = AutoScrollEngine.intervalFor(
                AutoScrollEngine.STEP_INTERVAL_MS, speed = 1.0
            )
        )
        // First tick (120 ms): at 0.999 the step crosses the
        // resource end, so it becomes a goForward — which moves
        // to resource 1 and reports true (the book continues).
        book.progression = 0.999
        advanceTimeBy(120L); runCurrent()
        assertTrue("crossing into the next resource continues", engine.running)
        assertEquals(1, book.resource)
        // Now at the last resource's end: the next crossing
        // step must report the end and stop the engine.
        book.progression = 0.9995
        advanceTimeBy(120L); runCurrent()
        assertFalse("engine stopped at the end of the book", engine.running)
        val callsAtStop = book.forwardCalls
        advanceTimeBy(5_000L); runCurrent()
        assertEquals("no further advances after the end", callsAtStop, book.forwardCalls)
    }

    @Test
    fun `paginated shape - a false goForward stops the engine`() = runTest {
        var calls = 0
        val surface = AutoScrollEngine.Surface {
            calls++
            calls < 2 // second page-turn reports the end
        }
        val engine = AutoScrollEngine()
        engine.start(this, surface, intervalMs = 500L)
        advanceTimeBy(500L); runCurrent()
        advanceTimeBy(500L); runCurrent()
        advanceTimeBy(3_000L); runCurrent()
        assertEquals(2, calls)
        assertFalse(engine.running)
    }
}
