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
 * v1.5.8 (G9 + H7): the ONE auto-scroll engine — against fake
 * surfaces on a virtual clock, it emits advance commands
 * monotonically at the set rate, stop halts them, and the
 * end of the book stops the engine by itself. The PDF-surface
 * case drives the same engine with page-advance semantics
 * (H7).
 *
 * G9 spike verdict (recorded in the build report): the
 * scroll-mode surface ships — timed small locator steps over
 * the navigator's `go`, the same road search/bookmark jumps
 * already travel in scroll mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class V158AutoScrollTest {

    @Test
    fun `engine advances monotonically at the set rate and stop halts`() = runTest {
        val engine = AutoScrollEngine()
        var advances = 0
        val surface = AutoScrollEngine.Surface { advances++; true }
        engine.start(this, surface, intervalMs = 1_000L)
        runCurrent()
        assertEquals(0, advances)
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(1, advances)
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(3, advances)
        engine.stop()
        assertFalse(engine.running)
        advanceTimeBy(5_000L); runCurrent()
        assertEquals("no commands after stop", 3, advances)
    }

    @Test
    fun `end of book stops the engine`() = runTest {
        val engine = AutoScrollEngine()
        var advances = 0
        val surface = AutoScrollEngine.Surface {
            advances++
            advances < 2 // second command reports the end
        }
        engine.start(this, surface, intervalMs = 500L)
        advanceTimeBy(500L); runCurrent()
        advanceTimeBy(500L); runCurrent()
        assertEquals(2, advances)
        advanceTimeBy(3_000L); runCurrent()
        assertEquals("engine settled at the end", 2, advances)
        assertFalse(engine.running)
    }

    @Test
    fun `pdf surface case - page advance from the current page to the end`() = runTest {
        // H7's paged surface semantics over the same engine:
        // advance = go to the next page; false at the last page.
        val pageCount = 8
        var currentPage = 5
        val visited = mutableListOf<Int>()
        val pdfSurface = AutoScrollEngine.Surface {
            val next = currentPage + 1
            if (next < pageCount) {
                currentPage = next
                visited += next
                true
            } else {
                false
            }
        }
        val engine = AutoScrollEngine()
        engine.start(
            this, pdfSurface,
            intervalMs = AutoScrollEngine.intervalFor(
                AutoScrollEngine.PAGE_INTERVAL_MS, speed = 2.0
            )
        )
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(listOf(6), visited)
        advanceTimeBy(2_000L); runCurrent()
        assertEquals(listOf(6, 7), visited)
        advanceTimeBy(6_000L); runCurrent()
        assertEquals("no page past the end", listOf(6, 7), visited)
        assertFalse(engine.running)
    }

    @Test
    fun `scroll-mode locator steps move monotonically within the resource`() = runTest {
        // G9's scroll surface semantics: each tick steps the
        // progression by progressionStep(speed), monotonically,
        // capped at the resource end.
        var progression = 0.40
        val steps = mutableListOf<Double>()
        val scrollSurface = AutoScrollEngine.Surface {
            val next = progression + AutoScrollEngine.progressionStep(1.0)
            if (next >= 1.0) {
                false
            } else {
                progression = next
                steps += next
                true
            }
        }
        val engine = AutoScrollEngine()
        engine.start(
            this, scrollSurface,
            intervalMs = AutoScrollEngine.intervalFor(
                AutoScrollEngine.STEP_INTERVAL_MS, speed = 1.0
            )
        )
        advanceTimeBy(1_000L); runCurrent()
        // v1.5.9 RECONCILIATION (F-7b): the shipped cadence pins
        // moved — interval 100 ms → 120 ms and step 0.0015 →
        // 0.002 at speed 1.0 (fewer, larger steps, same rate).
        // One second at 120 ms/step is eight steps, not ten.
        assertEquals(8, steps.size)
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        assertTrue(steps.all { it in 0.4..1.0 })
        engine.stop()
    }

    @Test
    fun `speed scales the interval and the step`() {
        assertEquals(4_000L, AutoScrollEngine.intervalFor(4_000L, 1.0))
        assertEquals(2_000L, AutoScrollEngine.intervalFor(4_000L, 2.0))
        assertEquals(8_000L, AutoScrollEngine.intervalFor(4_000L, 0.5))
        assertTrue(
            AutoScrollEngine.progressionStep(2.0) >
                AutoScrollEngine.progressionStep(1.0)
        )
        // v1.5.9 (F-7b): the retuned base cadence, pinned.
        assertEquals(120L, AutoScrollEngine.STEP_INTERVAL_MS)
        assertEquals(0.002, AutoScrollEngine.progressionStep(1.0), 0.0)
    }
}
