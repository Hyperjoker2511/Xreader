package app.xreader.data.engine

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.12 gate — FRONT 3, PDF half: the Moon+ video
 * whole-path sequence (owner steering item 3, 2026-11-06).
 *
 * The reference video's first half is the standard, filmed
 * on the owner's own 227-page Grieving Soul: instant open at
 * the cover → rapid turns with zero blank frames → fluid
 * pinch zoom out/in, page never lost → a far jump that lands
 * once, directly → reading continues immediately. This test
 * exercises the SAME actions in the SAME ORDER as ONE
 * continuous sequence against the production session:
 *
 *   open → 20 consecutive rapid turns → zoom in and back out
 *   → a far jump → more turns after the jump.
 *
 * Bar (owner): no turn waits on a fresh render (every
 * turned-to page renders, exactly once); zoom never strands
 * the pager (the base page survives the zoom cycle in the
 * memory tier); the far jump lands ONCE directly (no
 * intermediate page is rendered on the way); post-jump turns
 * work immediately.
 *
 * Like the born-stale gate, pager state is published
 * ASYNCHRONOUSLY throughout (a delayed launch standing in
 * for the reader's snapshotFlow collector) — never
 * synchronously before the render request, the v1.5.11
 * gate's blind spot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V1512PdfGateSequenceTest {

    @get:Rule val temp = TemporaryFolder()

    /** The collector's shape: publication arrives LATE, from
     * another coroutine, exactly like production. */
    private fun CoroutineScope.publishLater(
        session: PdfSession,
        settled: Int,
        target: Int,
        delayMs: Long = 60
    ) = launch {
        delay(delayMs)
        session.publishPagerState(settledPage = settled, targetPage = target)
    }

    @Test
    fun `moon-plus sequence - open, twenty rapid turns, zoom cycle, far jump, post-jump turns`() =
        runBlocking {
            val provider = V159PdfSessionFixtures.CountingProvider(
                pageCount = 40,
                renderDelayMs = 40
            )
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15126, provider)
            try {
                // ── 1. OPEN: the cover renders even though its
                // publication is still in flight (the
                // settledPage<0 first-paint road).
                val cover = async {
                    session.renderPage(
                        0, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                    )
                }
                val coverPublication = publishLater(session, 0, 0, delayMs = 50)
                assertNotNull(
                    "open: the cover renders at open",
                    withTimeout(15_000) { cover.await() }
                )
                coverPublication.join()
                assertEquals("cover rendered exactly once", 1, provider.rendersOf(0))

                // ── 2. TWENTY RAPID TURNS: each request is
                // issued the moment the turn happens, with its
                // publication trailing behind it. No turn may
                // come back blank (born-stale) and no page may
                // render twice.
                for (page in 1..20) {
                    val request = async {
                        session.renderPage(
                            page, 540, PdfPageFilter.NONE, 1f,
                            slot = PdfRenderSlot.P0
                        )
                    }
                    val publication = publishLater(session, page, page)
                    assertNotNull(
                        "turn $page: no blank frame — the page renders",
                        withTimeout(15_000) { request.await() }
                    )
                    publication.join()
                }
                for (page in 1..20) {
                    assertEquals(
                        "turn $page rendered exactly once",
                        1, provider.rendersOf(page)
                    )
                }

                // ── 3. ZOOM IN AND BACK OUT on the settled
                // page: the refine clip renders at reading
                // resolution; zooming back out evicts the
                // refinement and the BASE page is still there —
                // the page is never lost to the zoom cycle.
                val clip = session.renderRefinedClip(
                    20, PdfPageClip(0f, 0f, 0.5f, 0.5f), 1080
                )
                assertNotNull("zoom-in: the refined clip renders", clip)
                assertTrue(
                    "zoom-in went through the clip road",
                    provider.clipCalls.contains(20)
                )
                session.evictRefinements(20)
                assertTrue(
                    "zoom-out: the base page survives — never lost",
                    session.isBaseCached(20, 540)
                )

                // ── 4. FAR JUMP (slider / chapter-list style):
                // 20 → 35 in one step. The landing page renders
                // exactly once and NO intermediate page is
                // rendered on the way — the jump does not walk.
                val jump = async {
                    session.renderPage(
                        35, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                    )
                }
                val jumpPublication = publishLater(session, 35, 35)
                assertNotNull(
                    "far jump: the landing page renders",
                    withTimeout(15_000) { jump.await() }
                )
                jumpPublication.join()
                assertEquals(
                    "far jump lands ONCE",
                    1, provider.rendersOf(35)
                )
                for (skipped in 21..34) {
                    assertEquals(
                        "far jump never renders intermediate page $skipped",
                        0, provider.rendersOf(skipped)
                    )
                }

                // ── 5. POST-JUMP TURNS work immediately:
                // forward twice, then back onto the jump
                // landing — served from the memory tier, not
                // re-rendered.
                for (page in 36..37) {
                    val request = async {
                        session.renderPage(
                            page, 540, PdfPageFilter.NONE, 1f,
                            slot = PdfRenderSlot.P0
                        )
                    }
                    val publication = publishLater(session, page, page)
                    assertNotNull(
                        "post-jump turn $page renders immediately",
                        withTimeout(15_000) { request.await() }
                    )
                    publication.join()
                    assertEquals(1, provider.rendersOf(page))
                }
                val back = async {
                    session.renderPage(
                        35, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                    )
                }
                val backPublication = publishLater(session, 35, 35)
                assertNotNull(
                    "turning back onto the jump landing renders",
                    withTimeout(15_000) { back.await() }
                )
                backPublication.join()
                assertEquals(
                    "the landing page was already there — no fresh render",
                    1, provider.rendersOf(35)
                )

                // The observable publication ends where the
                // reader ended: settled on the last turn.
                assertEquals(35, session.pagerPublication.value.settledPage)
                assertEquals(35, session.pagerPublication.value.targetPage)
            } finally {
                session.close()
            }
        }
}
