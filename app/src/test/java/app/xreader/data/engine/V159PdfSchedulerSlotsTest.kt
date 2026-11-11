package app.xreader.data.engine

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (sift A-1): the 3-slot scheduler contract — P0 settled
 * before P1 `targetPage` before P2 prefetch; the pending P2
 * set served LIFO; and the staleness re-check before
 * `openPage` (an in-flight native render is never cancelled —
 * drop-before-openPage is the only seam).
 *
 * v1.5.11 (step 1, D1): P2 staleness is DISTANCE-based, not
 * generation-based — a queued prefetch is stale only when
 * its page has fallen outside the prefetch window around
 * the live settled/target page (see V1511PdfTurnSequenceTest,
 * whose first test mirrors this file's first scenario). The
 * generation-conflation expectation below is re-derived
 * accordingly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V159PdfSchedulerSlotsTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `P1 target page jumps queued P2 and the distance-valid queued prefetch still renders`() =
        runBlocking {
            val provider = V159PdfSessionFixtures.CountingProvider(renderDelayMs = 120)
            val session = V159PdfSessionFixtures.openSession(temp.root, 211, provider)
            try {
                session.publishPagerState(settledPage = 4, targetPage = 4)
                val running = async {
                    session.renderPage(5, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P2)
                }
                withTimeout(10_000) {
                    while (!provider.renderStarts.contains(5)) delay(10)
                }
                val queuedPrefetch = async {
                    session.renderPage(6, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P2)
                }
                // Deterministic: page 6 IS in the queue before
                // the turn begins (no fixed sleep).
                withTimeout(10_000) {
                    while (session.pendingRenderCount() < 1) delay(5)
                }
                // The turn begins: targetPage becomes 7 — the
                // publication bumps the generation. Pre-v1.5.11
                // that bump alone conflated the queued page 6
                // stale; v1.5.11 (D1) judges P2 by distance
                // only, and page 6 is inside the window from
                // both anchors (settled 4: +2; target 7: −1),
                // so it must RENDER — after the P1 it jumped.
                session.publishPagerState(settledPage = 4, targetPage = 7)
                val target = async {
                    session.renderPage(7, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P1)
                }
                assertNotNull(target.await())
                assertNotNull(running.await())
                assertNotNull(
                    "the queued P2 is distance-valid and renders",
                    queuedPrefetch.await()
                )
                assertEquals(1, provider.rendersOf(6))
                assertEquals(
                    "P1 jumps the queued P2, then the P2 lands",
                    listOf(5, 7, 6), provider.renderStarts.toList()
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `pending P2 set is served LIFO - most recent direction first`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider(renderDelayMs = 120)
        val session = V159PdfSessionFixtures.openSession(temp.root, 212, provider)
        try {
            // Settled on page 8 of the 12-page fixture: +1 = 9
            // and +2 = 10 both exist (a settled page 10 would
            // put +2 off the end of the book).
            session.publishPagerState(settledPage = 8, targetPage = 8)
            val settled = async {
                session.renderPage(8, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0)
            }
            withTimeout(10_000) {
                while (!provider.renderStarts.contains(8)) delay(10)
            }
            val gen = session.currentGeneration()
            val plus1 = async {
                session.renderPage(
                    9, 540, PdfPageFilter.NONE, 1f,
                    slot = PdfRenderSlot.P2, generation = gen
                )
            }
            // Deterministic enqueue order: +1 is queued before
            // +2 is even launched (no fixed sleep).
            withTimeout(10_000) {
                while (session.pendingRenderCount() < 1) delay(5)
            }
            val plus2 = async {
                session.renderPage(
                    10, 540, PdfPageFilter.NONE, 1f,
                    slot = PdfRenderSlot.P2, generation = gen
                )
            }
            assertNotNull("settled page renders", settled.await())
            assertNotNull(
                "+1 must not be dropped (starts=${provider.renderStarts}, " +
                    "gen $gen vs ${session.currentGeneration()})",
                plus1.await()
            )
            assertNotNull("+2 must not be dropped", plus2.await())
            assertEquals(
                "the later-enqueued prefetch renders first (LIFO)",
                listOf(8, 10, 9), provider.renderStarts.toList()
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `a P0 ticket for a page the pager left is dropped before openPage`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider(renderDelayMs = 150)
        val session = V159PdfSessionFixtures.openSession(temp.root, 213, provider)
        try {
            session.publishPagerState(settledPage = 4, targetPage = 4)
            val running = async {
                session.renderPage(4, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0)
            }
            withTimeout(10_000) {
                while (!provider.renderStarts.contains(4)) delay(10)
            }
            // A second request for page 4 at a different width
            // (a different ticket/key) queues behind it…
            val queued = async {
                session.renderPage(4, 300, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0)
            }
            delay(50)
            // …and the pager settles far away before it grants.
            session.publishPagerState(settledPage = 9, targetPage = 9)
            assertNotNull(running.await())
            assertNull("dropped before openPage — never rendered", queued.await())
            assertEquals(
                "page 4 rendered once (the live request only)",
                1, provider.rendersOf(4)
            )
        } finally {
            session.close()
        }
    }
}
