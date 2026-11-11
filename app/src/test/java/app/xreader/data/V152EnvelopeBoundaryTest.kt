@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.xreader.data

import app.xreader.ui.sources.StreamingSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2 (second-opinion delta 2): the §4 envelope boundary,
 * proven DETERMINISTICALLY under virtual time — this class is
 * the flake's closure evidence (the real-time envelope test's
 * repeat runs are not). StreamingSearch takes its leg
 * dispatcher and its clock from the test's scheduler
 * (production defaults unchanged), so every boundary is
 * asserted at T−1 ms, T and T+1 ms with zero wall-clock input:
 * a leg's classification cannot flip under load, because no
 * load exists in virtual time.
 */
class V152EnvelopeBoundaryTest {

    private class Recorder {
        val settled = LinkedHashMap<String, Result<String>>()
        var footnotes: StreamingSearch.Footnotes<String>? = null
    }

    @Test
    fun `per-item slice boundary - stall is did-not-answer exactly at T`() =
        runTest {
            val testScope = this
            val rec = Recorder()
            val job = backgroundScope.launch {
                StreamingSearch.run(
                    items = listOf("stall", "quick"),
                    parallelism = 1,
                    perItemTimeoutMs = 1_000L,
                    legDispatcher = StandardTestDispatcher(testScheduler),
                    nowMs = { testScope.testScheduler.currentTime },
                    search = { item ->
                        if (item == "stall") {
                            delay(60_000L)
                            "never"
                        } else {
                            "ok"
                        }
                    },
                    onResult = { item, result -> rec.settled[item] = result },
                    onProgress = { _, _ -> },
                    onFootnotes = { rec.footnotes = it }
                )
            }
            runCurrent()
            // T−1 ms: the stall leg is 1 ms short of its slice —
            // nothing has settled and no footnote exists.
            advanceTimeBy(999L)
            runCurrent()
            assertTrue("settled early: ${rec.settled}", rec.settled.isEmpty())
            assertNull(rec.footnotes)
            // T: the slice closes — the stall is "didn't answer",
            // the queued leg runs and settles, the run completes.
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(setOf("stall", "quick"), rec.settled.keys)
            assertTrue(
                rec.settled["stall"]!!.exceptionOrNull()
                    is app.xreader.ui.sources.SearchSourceTimeoutException
            )
            assertEquals(listOf("stall"), rec.footnotes!!.didntAnswer)
            assertEquals(emptyList<String>(), rec.footnotes!!.notReached)
            // T+1 ms: the verdict is stable.
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("stall"), rec.footnotes!!.didntAnswer)
            job.cancel()
        }

    @Test
    fun `aggregate sliver boundary - a leg started with 51 ms left is did-not-answer at the bound`() =
        runTest {
            val testScope = this
            // The historical flake's exact shape: leg b starts
            // with ~50 ms of aggregate left. Whether it counts
            // as asked was decided when it STARTED (it is inside
            // the deterministic asked limit), and its timeout —
            // the remaining aggregate — classifies it.
            val rec = Recorder()
            val job = backgroundScope.launch {
                StreamingSearch.run(
                    items = listOf("a", "b"),
                    parallelism = 1,
                    perItemTimeoutMs = 1_000L,
                    aggregateDeadlineMs = 1_050L,
                    legDispatcher = StandardTestDispatcher(testScheduler),
                    nowMs = { testScope.testScheduler.currentTime },
                    search = { item ->
                        if (item == "a") {
                            delay(999L)
                            "A"
                        } else {
                            delay(60_000L)
                            "never"
                        }
                    },
                    onResult = { item, result -> rec.settled[item] = result },
                    onProgress = { _, _ -> },
                    onFootnotes = { rec.footnotes = it }
                )
            }
            runCurrent()
            // a settles at 999; b starts with 51 ms of aggregate.
            advanceTimeBy(999L)
            runCurrent()
            assertEquals(setOf("a"), rec.settled.keys)
            assertNull(rec.footnotes)
            // T−1 (1049): b is still inside its 51 ms.
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(setOf("a"), rec.settled.keys)
            assertNull(rec.footnotes)
            // T (1050): b is "didn't answer"; the run completes
            // exactly on the bound.
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(setOf("a", "b"), rec.settled.keys)
            assertEquals(listOf("b"), rec.footnotes!!.didntAnswer)
            assertEquals(emptyList<String>(), rec.footnotes!!.notReached)
            // T+1: stable.
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(listOf("b"), rec.footnotes!!.didntAnswer)
            job.cancel()
        }

    @Test
    fun `aggregate cap boundary - legs beyond the asked limit are not reached exactly at T`() =
        runTest {
            val testScope = this
            // 40 stallers at the production slice (20 s): the
            // waves compose 160 s but the aggregate caps at
            // 120 s, so the deterministic asked limit is
            // 6 waves × 5 = 30 — legs 31..40 are "not reached",
            // decided up front, not by timing.
            val items = (0 until 40).map { "s$it" }
            val rec = Recorder()
            val job = backgroundScope.launch {
                StreamingSearch.run(
                    items = items,
                    parallelism = 5,
                    perItemTimeoutMs = 20_000L,
                    legDispatcher = StandardTestDispatcher(testScheduler),
                    nowMs = { testScope.testScheduler.currentTime },
                    search = {
                        delay(600_000L)
                        "never"
                    },
                    onResult = { item, result -> rec.settled[item] = result },
                    onProgress = { _, _ -> },
                    onFootnotes = { rec.footnotes = it }
                )
            }
            runCurrent()
            // T−1 (119_999): the last asked wave is still inside
            // its slice — no footnotes yet.
            advanceTimeBy(119_999L)
            runCurrent()
            assertNull(rec.footnotes)
            // T (120_000): the run completes on the bound; the
            // two footnotes are exact and distinct.
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(
                (0 until 30).map { "s$it" }.toSet(),
                rec.footnotes!!.didntAnswer.toSet()
            )
            assertEquals(
                (30 until 40).map { "s$it" },
                rec.footnotes!!.notReached
            )
            // T+1: stable.
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(30, rec.footnotes!!.didntAnswer.size)
            assertEquals(10, rec.footnotes!!.notReached.size)
            job.cancel()
        }
}
