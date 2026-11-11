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
 * v1.5.11 gate — the PDF TURN SEQUENCE, end to end through
 * the real [PdfSession] harness (the V159 fixtures'
 * CountingProvider over a real 12-page document): the
 * v1.5.10 conviction was that prefetching only ever filled
 * while the reader sat still. The scheduler judged a queued
 * P2 (prefetch) ticket by its ENQUEUE-TIME GENERATION, and
 * the pager bumps that generation on EVERY settle/target
 * change — so the first turn conflated the whole queued
 * prefetch window stale before `openPage`, and pages the
 * reader was turning TOWARDS were never ready.
 *
 * v1.5.11 (step 1, D1) judges P2 by DISTANCE ONLY: a
 * prefetch is stale only when its page has fallen outside
 * the prefetch window around the LIVE settled/target page,
 * re-checked at grant time. These tests publish real
 * consecutive pager-state turns over queued prefetches and
 * assert the pages still land — plus the control that the
 * distance guard itself still drops a prefetch the reader
 * turned AWAY from (the fix is a narrower staleness rule,
 * not no staleness rule).
 *
 * The first test is the EXACT re-derived scenario of the
 * old failing assertion (V159PdfSchedulerSlotsTest's
 * generation-conflation case, same setup): settle 4, P2
 * page 5 running, P2 page 6 queued, then the pager targets
 * 7. The pre-fix code returned null for page 6; page 6 is
 * distance-valid from settled 4 (+2, inside +6) AND from
 * target 7 (−1, inside −1..+6), so it must RENDER — after
 * the P1 target it jumped, per the slot contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V1511PdfTurnSequenceTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `queued P2 page 6 renders after settle 4 target 7 - the re-derived scenario`() =
        runBlocking {
            val provider =
                V159PdfSessionFixtures.CountingProvider(renderDelayMs = 120)
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15111, provider)
            try {
                session.publishPagerState(settledPage = 4, targetPage = 4)
                val running = async {
                    session.renderPage(
                        5, 540, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P2
                    )
                }
                withTimeout(10_000) {
                    while (!provider.renderStarts.contains(5)) delay(10)
                }
                val queuedPrefetch = async {
                    session.renderPage(
                        6, 540, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P2
                    )
                }
                // Deterministic: page 6 IS queued before the
                // turn begins (no fixed sleep).
                withTimeout(10_000) {
                    while (session.pendingRenderCount() < 1) delay(5)
                }
                // The turn begins: targetPage becomes 7 — the
                // publication bumps the generation. Pre-fix,
                // that bump alone killed the queued page 6.
                session.publishPagerState(settledPage = 4, targetPage = 7)
                val target = async {
                    session.renderPage(
                        7, 540, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P1
                    )
                }
                assertNotNull("the P1 target renders", target.await())
                assertNotNull("the running prefetch renders", running.await())
                assertNotNull(
                    "page 6 is distance-valid from settled 4 (+2)" +
                        " and target 7 (-1): the generation bump" +
                        " must not conflate it",
                    queuedPrefetch.await()
                )
                assertEquals(1, provider.rendersOf(6))
                assertEquals(
                    "P1 still jumps the queued P2, then the P2 lands",
                    listOf(5, 7, 6), provider.renderStarts.toList()
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `prefetch window enqueued at one settle still lands after consecutive turns`() =
        runBlocking {
            val provider =
                V159PdfSessionFixtures.CountingProvider(renderDelayMs = 150)
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15112, provider)
            try {
                // Settle on page 1; its own render holds the
                // slot while the prefetch window queues behind
                // it (deterministically, via the pending count).
                session.publishPagerState(settledPage = 1, targetPage = 1)
                val settled = async {
                    session.renderPage(
                        1, 540, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P0
                    )
                }
                withTimeout(10_000) {
                    while (!provider.renderStarts.contains(1)) delay(10)
                }
                val gen = session.currentGeneration()
                val ahead2 = async {
                    session.renderPage(
                        4, 540, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P2, generation = gen
                    )
                }
                withTimeout(10_000) {
                    while (session.pendingRenderCount() < 1) delay(5)
                }
                val ahead3 = async {
                    session.renderPage(
                        5, 540, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P2, generation = gen
                    )
                }
                withTimeout(10_000) {
                    while (session.pendingRenderCount() < 2) delay(5)
                }
                // CONSECUTIVE TURNS, one page at a time: each
                // publication bumps the generation the tickets
                // were enqueued under. Pages 4 and 5 stay
                // inside the window the whole way (page 4 ends
                // AS the settled page, page 5 one ahead).
                session.publishPagerState(settledPage = 2, targetPage = 2)
                session.publishPagerState(settledPage = 3, targetPage = 3)
                session.publishPagerState(settledPage = 4, targetPage = 4)

                assertNotNull("the settled page renders", settled.await())
                assertNotNull(
                    "prefetch +3 from the old settle must survive" +
                        " three generation bumps and land",
                    ahead2.await()
                )
                assertNotNull(
                    "prefetch +4 from the old settle must survive" +
                        " three generation bumps and land",
                    ahead3.await()
                )
                assertEquals(1, provider.rendersOf(4))
                assertEquals(1, provider.rendersOf(5))
                assertEquals(
                    "the queued P2 set is still served LIFO",
                    listOf(1, 5, 4), provider.renderStarts.toList()
                )
            } finally {
                session.close()
            }
        }

    @Test
    fun `prefetch outside the window after a jump away is still dropped`() =
        runBlocking {
            // The control: distance staleness still guards.
            // Page 10 is a legitimate +2 prefetch from settled
            // 8 when queued — but the reader then jumps BACK
            // to page 1, putting it +9 away, outside the
            // +6 window from both the settled and target page.
            // It must be dropped before openPage, exactly as
            // a stale ticket always was.
            val provider =
                V159PdfSessionFixtures.CountingProvider(renderDelayMs = 150)
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15113, provider)
            try {
                session.publishPagerState(settledPage = 8, targetPage = 8)
                val settled = async {
                    session.renderPage(
                        8, 540, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P0
                    )
                }
                withTimeout(10_000) {
                    while (!provider.renderStarts.contains(8)) delay(10)
                }
                val gen = session.currentGeneration()
                val farPrefetch = async {
                    session.renderPage(
                        10, 540, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P2, generation = gen
                    )
                }
                withTimeout(10_000) {
                    while (session.pendingRenderCount() < 1) delay(5)
                }
                session.publishPagerState(settledPage = 1, targetPage = 1)
                assertNotNull("the settled page renders", settled.await())
                assertNull(
                    "a prefetch the reader turned away from is" +
                        " dropped before openPage",
                    farPrefetch.await()
                )
                assertEquals(0, provider.rendersOf(10))
                assertEquals(
                    listOf(8), provider.renderStarts.toList()
                )
            } finally {
                session.close()
            }
        }
}
