package app.xreader.data.engine

import android.app.Application
import app.xreader.data.prefs.PerfTier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * v1.5.10 (H1 — PDF dwell prefetch to +6). Extends the
 * V159PdfFlipSequencePerf harness shape: there is NO dwell
 * timer in the tree — prefetch enqueues the whole window on
 * SETTLE — so this drives the paged reader's own settle
 * pattern (publish the settled page, render it P0, enqueue
 * the tier's full-res window at P2 under the settle's
 * generation) over the owner's real page size (396×612 pt,
 * [V159PdfSessionFixtures]) and asserts the widened window's
 * contract:
 *
 *  - FULL/STANDARD prefetch +6/−1, MINIMAL +2/−1 (the plan's
 *    numbers; the Gemini return's +4 was rejected).
 *  - A scripted 10-page linear run has ZERO non-cached
 *    renders after page 2: every arrival is a cache swap.
 *  - The staleness window IS the prefetch window: a P2
 *    request at distance +5 (stale under the old +2) is
 *    valid, +8 is dropped, and prefetched neighbours are
 *    NOT re-rendered when the reader arrives on them.
 *  - A jump still pays exactly one preview + one base render.
 *  - Budget: the widened window's resident set (settled +
 *    forward + backward base renders at the harness width)
 *    fits the tier's unchanged memory budget — the LruCache
 *    cap itself is pinned by V159PdfTierBudgetsTest and the
 *    budgets/eviction order are untouched by H1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V1510PdfDwellPrefetchTest {

    @get:Rule val temp = TemporaryFolder()

    private companion object {
        /** The harness render width (540 px, as in the v1.5.9
         * flip tests) at the owner's 396×612 pt aspect. */
        const val WIDTH = 540
        val PAGE_BYTES: Long =
            WIDTH.toLong() * (WIDTH.toFloat() * 612 / 396).toInt() * 4
    }

    /**
     * The paged reader's settle pattern (PdfDocumentReader's
     * paged prefetch effect, reduced to its session calls):
     * publish the settled page, render it at P0, then enqueue
     * the full-res window at P2 under the settle generation,
     * ±1 last as the reader does. Awaits the whole window so
     * each settle is complete before the next begins.
     */
    private suspend fun settle(
        session: PdfSession,
        provider: V159PdfSessionFixtures.CountingProvider,
        page: Int
    ) = coroutineScope {
        session.publishVisiblePage(page)
        assertNotNull(
            "settled page $page renders",
            session.renderPage(page, WIDTH, PdfPageFilter.NONE, 1f, priority = true)
        )
        val generation = session.currentGeneration()
        val jobs = ArrayList<kotlinx.coroutines.Deferred<*>>()
        for (delta in session.prefetchForward downTo 1) {
            if (delta == 1) continue
            val target = page + delta
            if (target in 0 until session.sectionCount) {
                jobs += async {
                    session.renderPage(
                        target, WIDTH, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P2, generation = generation
                    )
                }
            }
        }
        for (delta in session.prefetchBackward downTo 1) {
            if (delta == 1) continue
            val target = page - delta
            if (target in 0 until session.sectionCount) {
                jobs += async {
                    session.renderPage(
                        target, WIDTH, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P2, generation = generation
                    )
                }
            }
        }
        for (target in listOf(page - 1, page + 1)) {
            if (target in 0 until session.sectionCount) {
                jobs += async {
                    session.renderPage(
                        target, WIDTH, PdfPageFilter.NONE, 1f,
                        slot = PdfRenderSlot.P2, generation = generation
                    )
                }
            }
        }
        jobs.awaitAll()
        Unit
    }

    @Test
    fun `window arithmetic per tier - plan numbers`() {
        assertEquals(6 to 1, PdfSession.prefetchWindowFor(PerfTier.FULL))
        assertEquals(6 to 1, PdfSession.prefetchWindowFor(PerfTier.STANDARD))
        assertEquals(2 to 1, PdfSession.prefetchWindowFor(PerfTier.MINIMAL))
        // Budgets unchanged (48/48/20 MB) — H1 widens the
        // window, never the budget.
        assertEquals(48L * 1024 * 1024, PdfSession.memoryCapBytesFor(PerfTier.FULL))
        assertEquals(48L * 1024 * 1024, PdfSession.memoryCapBytesFor(PerfTier.STANDARD))
        assertEquals(20L * 1024 * 1024, PdfSession.memoryCapBytesFor(PerfTier.MINIMAL))
        // The widened window's full resident set fits its tier
        // budget at the owner's page size: settled + forward +
        // backward base renders.
        assertTrue(
            "FULL resident set ${(1 + 6 + 1) * PAGE_BYTES} <= 48 MB",
            (1 + 6 + 1) * PAGE_BYTES <= PdfSession.memoryCapBytesFor(PerfTier.FULL)
        )
        assertTrue(
            "MINIMAL resident set ${(1 + 2 + 1) * PAGE_BYTES} <= 20 MB",
            (1 + 2 + 1) * PAGE_BYTES <= PdfSession.memoryCapBytesFor(PerfTier.MINIMAL)
        )
    }

    @Test
    fun `linear run of ten pages - zero non-cached renders after page two`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider()
        val session = V159PdfSessionFixtures.openSession(temp.root, 1510, provider)
        try {
            assertEquals(6, session.prefetchForward)
            assertEquals(1, session.prefetchBackward)
            for (page in 0..9) {
                // Arrival check BEFORE this settle renders
                // anything: from page 1 on (and in particular
                // after page 2, the map's line) the page must
                // already be a cache swap — a previous settle's
                // dwell window covered it.
                if (page >= 1) {
                    assertTrue(
                        "page $page already base-cached on arrival",
                        session.isBaseCached(page, WIDTH)
                    )
                    assertEquals(
                        "page $page rendered exactly once (by prefetch)",
                        1, provider.rendersOf(page)
                    )
                }
                settle(session, provider, page)
                assertEquals(
                    "page $page still rendered exactly once after its settle",
                    1, provider.rendersOf(page)
                )
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun `staleness window widened identically - prefetched neighbours not re-rendered`() =
        runBlocking {
            val provider = V159PdfSessionFixtures.CountingProvider()
            val session = V159PdfSessionFixtures.openSession(temp.root, 1511, provider)
            try {
                settle(session, provider, 0)
                // The whole +6 window landed during page 0's dwell.
                for (page in 1..6) {
                    assertTrue("page $page prefetched", session.isBaseCached(page, WIDTH))
                    assertEquals(1, provider.rendersOf(page))
                }
                // Distance +5 at a fresh width (uncached key):
                // inside the widened staleness window, so the P2
                // ticket is valid and renders. Under the old +2
                // window this was dropped as stale.
                val inWindow = session.renderPage(
                    5, 300, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P2
                )
                assertNotNull("distance +5 P2 ticket survives the widened window", inWindow)
                assertEquals(2, provider.rendersOf(5)) // base 540 + this 300
                // Distance +8: outside even the widened window —
                // dropped, no render ever starts.
                val stale = session.renderPage(
                    8, 300, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P2
                )
                assertNull("distance +8 P2 ticket is dropped as stale", stale)
                assertEquals(0, provider.rendersOf(8))
                // Walk forward onto the prefetched neighbours:
                // arrival is a swap, never a re-render.
                for (page in 1..3) {
                    settle(session, provider, page)
                    assertEquals(
                        "page $page not re-rendered on arrival",
                        1, provider.rendersOf(page)
                    )
                }
            } finally {
                session.close()
            }
        }

    @Test
    fun `a jump pays exactly one preview and one base render`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider()
        val session = V159PdfSessionFixtures.openSession(temp.root, 1512, provider)
        try {
            settle(session, provider, 0)
            val startsBefore = provider.renderStarts.size
            // The jump road (A-2): land on page 9 cold — preview
            // first, then the base render, nothing else.
            session.publishVisiblePage(9)
            assertNotNull(session.renderPreview(9, WIDTH))
            assertNotNull(
                session.renderPage(9, WIDTH, PdfPageFilter.NONE, 1f, priority = true)
            )
            assertEquals(
                "exactly two renders for the jump, both page 9 (preview + base)",
                listOf(9, 9),
                provider.renderStarts.drop(startsBefore)
            )
            assertEquals(2, provider.rendersOf(9))
            assertEquals(0, provider.rendersOf(10))
            assertEquals(0, provider.rendersOf(11))
        } finally {
            session.close()
        }
    }

    @Test
    fun `MINIMAL tier walks plus two only`() = runBlocking {
        val provider = V159PdfSessionFixtures.CountingProvider()
        val session = V159PdfSessionFixtures.openSession(
            temp.root, 1513, provider, tier = PerfTier.MINIMAL
        )
        try {
            assertEquals(2, session.prefetchForward)
            assertEquals(1, session.prefetchBackward)
            settle(session, provider, 0)
            assertTrue(session.isBaseCached(1, WIDTH))
            assertTrue(session.isBaseCached(2, WIDTH))
            assertFalse(
                "page 3 is beyond MINIMAL's +2 dwell window",
                session.isBaseCached(3, WIDTH)
            )
            assertEquals(0, provider.rendersOf(3))
            // And its staleness window is +2 too: distance +3
            // at P2 is dropped.
            val stale = session.renderPage(
                3, 300, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P2
            )
            assertNull(stale)
            assertEquals(0, provider.rendersOf(3))
        } finally {
            session.close()
        }
    }
}
