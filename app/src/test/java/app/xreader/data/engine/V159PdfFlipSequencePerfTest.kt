package app.xreader.data.engine

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (Finding 3 §8.1 — the headline test). The shipped
 * pipeline fails this by construction: one FIFO mutex with a
 * PNG encode inside it, no memory cache, no priority, no
 * staleness. Fixture pages are the owner's real size
 * (396×612 pt) via [V159PdfSessionFixtures].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V159PdfFlipSequencePerfTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * (a) Priority: with a low-priority render RUNNING (page 5)
     * and another QUEUED (page 6), a priority request for the
     * visible page (4) is granted next — it completes before
     * the queued prefetch, behind only the render already in
     * flight (priority is queue order, not preemption).
     */
    @Test
    fun `visible page jumps the queue ahead of queued prefetch`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider(renderDelayMs = 120)
        val session = V159PdfSessionFixtures.openSession(temp.root, 201, provider)
        try {
            session.publishVisiblePage(4)
            val low1 = async { session.renderPage(5, 540, PdfPageFilter.NONE, 2f) }
            // Wait until page 5's render is actually running.
            withTimeout(10_000) {
                while (!provider.renderStarts.contains(5)) delay(10)
            }
            val low2 = async {
                session.renderPage(6, 540, PdfPageFilter.NONE, 2f, priority = false)
            }
            // Let page 6 settle into the queue behind page 5.
            delay(50)
            val visible = async {
                session.renderPage(4, 540, PdfPageFilter.NONE, 2f, priority = true)
            }
            assertNotNull(visible.await())
            assertNotNull(low1.await())
            assertNotNull(low2.await())
            assertEquals(
                "priority render starts before the queued prefetch",
                listOf(5, 4, 6), provider.renderStarts.toList()
            )
            assertEquals(
                "and completes before it too",
                listOf(5, 4, 6), provider.renderEnds.toList()
            )
        } finally {
            session.close()
        }
    }

    /**
     * (b)+(c)+(d): the scripted flip sequence 1→2→3→8→1 (pages
     * 0→1→2→7→0) with the pager's prefetch pattern (next page
     * at low priority), inside a wall-time budget derived from
     * a single measured render; the revisit of page 1 is served
     * from the MEMORY cache (its disk artefact deleted, provider
     * render count still 1); after settling, a low-priority
     * request outside ±2 of the visible page is dropped without
     * a render ever starting.
     */
    @Test
    fun `flip sequence budget, memory-cache revisit, staleness drop`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider(renderDelayMs = 40)
        val session = V159PdfSessionFixtures.openSession(temp.root, 202, provider)
        try {
            // The budget's unit: one cold visible render through
            // the full session path (render + encode + tiers).
            session.publishVisiblePage(0)
            val singleStart = System.nanoTime()
            assertNotNull(session.renderPage(0, 540, PdfPageFilter.NONE, 2f, priority = true))
            val singleMs = (System.nanoTime() - singleStart) / 1_000_000
            assertEquals(1, provider.rendersOf(0))

            val sequenceStart = System.nanoTime()
            for (target in listOf(1, 2, 7)) {
                session.publishVisiblePage(target)
                // The pager's prefetch of the next page, low
                // priority, overlapping the flip.
                val prefetch = async {
                    session.renderPage(
                        target + 1, 540, PdfPageFilter.NONE, 2f, priority = false
                    )
                }
                val page = session.renderPage(
                    target, 540, PdfPageFilter.NONE, 2f, priority = true
                )
                assertNotNull("visible page $target delivered", page)
                prefetch.await()
            }
            // Revisit page 1 (index 0) as the visible page: with
            // its disk artefact deleted, only the memory tier
            // can serve it without a second provider render.
            session.publishVisiblePage(0)
            V159PdfSessionFixtures.cacheDirOf(temp.root, 202)
                .listFiles()?.filter { it.name.endsWith(".jpg") }
                ?.forEach { it.delete() }
            val revisit = session.renderPage(
                0, 540, PdfPageFilter.NONE, 2f, priority = true
            )
            assertNotNull(revisit)
            assertEquals(
                "page 1 revisit served from memory — provider rendered it once",
                1, provider.rendersOf(0)
            )
            val sequenceMs = (System.nanoTime() - sequenceStart) / 1_000_000
            // Budget (Finding 3 §8.1b): ≤ 5 × single-render +
            // 2 × decode, plus the three prefetches the pager
            // pattern legitimately adds — decode is bounded by
            // a single render in this harness, so the whole
            // sequence must land within 10 single-renders. The
            // shipped serial FIFO+PNG pipeline pays a full
            // encode per page INSIDE the queue and re-decodes
            // the revisit; this is the shape that separates it.
            val budgetMs = 10 * singleMs + 500
            println(
                "V159 FLIP: single=${singleMs} ms sequence=${sequenceMs} ms " +
                    "budget=${budgetMs} ms starts=${provider.renderStarts}"
            )
            assertTrue(
                "sequence ${sequenceMs} ms within budget ${budgetMs} ms",
                sequenceMs <= budgetMs
            )

            // (d) Staleness after settle: visible is page 8
            // (index 7); a low-priority request for page 5
            // (index 4, distance 3) is dropped — no render
            // starts, null comes back.
            session.publishVisiblePage(7)
            val startsBefore = provider.renderStarts.size
            val stale = session.renderPage(
                4, 540, PdfPageFilter.NONE, 2f, priority = false
            )
            assertNull("stale low-priority request is dropped", stale)
            assertEquals("no render started for the stale page", 0, provider.rendersOf(4))
            assertEquals(
                "no render at all started after the settle",
                startsBefore, provider.renderStarts.size
            )
        } finally {
            session.close()
        }
    }
}
