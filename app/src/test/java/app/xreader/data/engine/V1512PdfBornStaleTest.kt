package app.xreader.data.engine

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * v1.5.12 gate — FRONT 3, PDF half, P1 (contract §3.3+§3.4):
 * the BORN-STALE race, reproduced the way production actually
 * races — NOT the v1.5.11 gate's shape.
 *
 * The conviction (forensics Lane 3, Q2+Q5): a page cell
 * derives its slot from LIVE pager state in composition, but
 * `PdfSession` judges the ticket's validity against its own
 * COPIED settled/target state, published by a separate
 * `snapshotFlow` collector with no ordering guarantee against
 * the cell effect. If the turned-to page's P0 request is
 * judged before the publication catches up, it is dropped as
 * stale — and the one-shot cell never re-asked. The v1.5.11
 * turn-sequence test could not see this: it published
 * SYNCHRONOUSLY before every renderPage.
 *
 * These tests therefore publish LATE and ASYNCHRONOUSLY (a
 * delayed coroutine, like the collector): the request is
 * issued against the STALE publication, the publication
 * catches up while the request is in flight, and the page
 * must still render — via the session's non-terminal retry
 * under the coherently re-derived slot, surfaced through
 * [PdfSession.pagerPublication], the same flow the production
 * cell keys its effect on. The P2 control proves the fix did
 * not repeal distance staleness: a prefetch the reader turned
 * away from is still dropped.
 *
 * P4 is pinned at the end: composition's size reads are
 * peek-only (the provider is never touched), and the async
 * fill lands the size exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V1512PdfBornStaleTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `turned-to page P0 renders when the publication lags the request`() =
        runBlocking {
            val provider =
                V159PdfSessionFixtures.CountingProvider(renderDelayMs = 120)
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15121, provider)
            try {
                // Settle on the cover and render it — the one
                // page the shipped code always managed (the
                // settledPage<0 exception covers first paint).
                session.publishPagerState(settledPage = 0, targetPage = 0)
                assertNotNull(
                    session.renderPage(
                        0, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                    )
                )
                // Occupy the single render slot with a slow
                // prefetch, so the turned-to page's ticket is
                // judged while the publication is still stale.
                val slowPrefetch = async {
                    session.renderPage(
                        3, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P2
                    )
                }
                withTimeout(10_000) {
                    while (!provider.renderStarts.contains(3)) delay(5)
                }
                // THE RACE: the cell for page 1 already
                // composed from live pager state (the user
                // turned; the pager says settled 1) and issues
                // its P0 request NOW — but the session's
                // publication still says settled 0. Pre-fix,
                // acquire() dropped this ticket as stale and
                // renderPage returned null PERMANENTLY (the
                // cell's one-shot effect never re-ran).
                val turned = async {
                    session.renderPage(
                        1, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                    )
                }
                // The publication catches up ASYNCHRONOUSLY —
                // a delayed launch standing in for the reader's
                // snapshotFlow collector, exactly the production
                // ordering (or lack of it).
                val publication = launch {
                    delay(250)
                    session.publishPagerState(settledPage = 1, targetPage = 1)
                }
                val bitmap = withTimeout(15_000) { turned.await() }
                assertNotNull(
                    "the turned-to page must render once the " +
                        "publication catches up — a born-stale " +
                        "drop is not terminal any more",
                    bitmap
                )
                assertEquals(1, provider.rendersOf(1))
                assertEquals(
                    "the observable publication is the caught-up one",
                    PdfSession.PagerPublication(1, -1, 1),
                    session.pagerPublication.value
                )
                publication.join()
                assertNotNull(slowPrefetch.await())
            } finally {
                session.close()
            }
        }

    @Test
    fun `in-flight target P1 renders when only the target publication lags`() =
        runBlocking {
            val provider =
                V159PdfSessionFixtures.CountingProvider(renderDelayMs = 120)
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15122, provider)
            try {
                session.publishPagerState(settledPage = 0, targetPage = 0)
                assertNotNull(
                    session.renderPage(
                        0, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                    )
                )
                val slowPrefetch = async {
                    session.renderPage(
                        4, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P2
                    )
                }
                withTimeout(10_000) {
                    while (!provider.renderStarts.contains(4)) delay(5)
                }
                // The pager is mid-turn: live target is 1, the
                // cell beyond the viewport composes as P1 — the
                // session's publication still has target 0, so
                // the P1 ticket is born stale by the same race.
                val target = async {
                    session.renderPage(
                        1, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P1
                    )
                }
                val publication = launch {
                    delay(250)
                    session.publishPagerState(settledPage = 0, targetPage = 1)
                }
                assertNotNull(
                    "the in-flight target renders when the " +
                        "target publication catches up",
                    withTimeout(15_000) { target.await() }
                )
                assertEquals(1, provider.rendersOf(1))
                publication.join()
                assertNotNull(slowPrefetch.await())
            } finally {
                session.close()
            }
        }

    @Test
    fun `prefetch turned away from is still dropped - staleness was narrowed not repealed`() =
        runBlocking {
            val provider =
                V159PdfSessionFixtures.CountingProvider(renderDelayMs = 150)
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15123, provider)
            try {
                session.publishPagerState(settledPage = 8, targetPage = 8)
                val settled = async {
                    session.renderPage(
                        8, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                    )
                }
                withTimeout(10_000) {
                    while (!provider.renderStarts.contains(8)) delay(5)
                }
                val farPrefetch = async {
                    session.renderPage(
                        10, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P2
                    )
                }
                withTimeout(10_000) {
                    while (session.pendingRenderCount() < 1) delay(5)
                }
                // The reader jumps AWAY: page 10 falls outside
                // the window from settled 1. P2 drops stay
                // terminal (no retry budget for prefetch), so
                // the page is never rendered.
                session.publishPagerState(settledPage = 1, targetPage = 1)
                assertNotNull(settled.await())
                assertNull(
                    "a P2 prefetch outside the window is dropped",
                    farPrefetch.await()
                )
                assertEquals(0, provider.rendersOf(10))
            } finally {
                session.close()
            }
        }

    @Test
    fun `repeated turns with lagging publications all land`() =
        runBlocking {
            // The soak form of the race: five consecutive
            // turns, each request issued BEFORE its settle is
            // published (publication trails one turn behind,
            // published asynchronously). Every turned-to page
            // must render exactly once.
            val provider =
                V159PdfSessionFixtures.CountingProvider(renderDelayMs = 60)
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15124, provider)
            try {
                session.publishPagerState(settledPage = 0, targetPage = 0)
                assertNotNull(
                    session.renderPage(
                        0, 540, PdfPageFilter.NONE, 1f, slot = PdfRenderSlot.P0
                    )
                )
                for (page in 1..5) {
                    val request = async {
                        session.renderPage(
                            page, 540, PdfPageFilter.NONE, 1f,
                            slot = PdfRenderSlot.P0
                        )
                    }
                    val publication = launch {
                        delay(120)
                        session.publishPagerState(
                            settledPage = page, targetPage = page
                        )
                    }
                    assertNotNull(
                        "turn to page $page lands despite the lag",
                        withTimeout(15_000) { request.await() }
                    )
                    publication.join()
                }
                for (page in 1..5) {
                    assertEquals(
                        "page $page rendered exactly once",
                        1, provider.rendersOf(page)
                    )
                }
            } finally {
                session.close()
            }
        }

    @Test
    fun `size peek never touches the provider and the async fill lands once`() =
        runBlocking {
            val provider = V159PdfSessionFixtures.CountingProvider()
            val session =
                V159PdfSessionFixtures.openSession(temp.root, 15125, provider)
            try {
                // The size table is lazy: nothing has fetched
                // any size yet (no publication happened).
                assertEquals(0, provider.sizeCalls.get())
                // Composition's read: peek only — a miss returns
                // null and the provider is NOT called (P4: a
                // composition-path get() could run a
                // lock-contending openPage on the main thread).
                assertNull(session.pagePointsSizePeek(3))
                assertEquals(
                    "peek must not fetch",
                    0, provider.sizeCalls.get()
                )
                // The off-composition fill: asynchronous, lands
                // the size, bumps the version cells key on.
                val versionBefore = session.sizeTableVersion.value
                session.requestPageSizeAsync(3)
                withTimeout(10_000) {
                    while (session.pagePointsSizePeek(3) == null) delay(10)
                }
                assertEquals(
                    V159PdfSessionFixtures.PAGE_W_PT to
                        V159PdfSessionFixtures.PAGE_H_PT,
                    session.pagePointsSizePeek(3)
                )
                assertEquals(1, provider.sizeCalls.get())
                assertTrue(
                    "the fill bumped the size-table version",
                    session.sizeTableVersion.value > versionBefore
                )
                // A second request is a no-op (already cached).
                session.requestPageSizeAsync(3)
                delay(150)
                assertEquals(1, provider.sizeCalls.get())
            } finally {
                session.close()
            }
        }
}
