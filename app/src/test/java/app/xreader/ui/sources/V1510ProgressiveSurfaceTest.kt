package app.xreader.ui.sources

import app.xreader.data.extension.ChapterWaitRegistry
import app.xreader.data.extension.SourceBookSync
import app.xreader.extensionapi.SourceBook
import app.xreader.fixtures.HangCatalogueSource
import app.xreader.ui.book.ChapterReadGuard
import app.xreader.ui.book.ChaptersVerdict
import app.xreader.ui.book.ReadCtaState
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.10 (H3) gate: progressive surfaces — first frame
 * renders stored rows; a pending fetch rides ALONGSIDE,
 * never INSTEAD; no false empty/failed verdict while a
 * fetch is merely pending. Two arms:
 *  - catalogue: catalogueSurfaceMode's table, driven with
 *    the I4 hang fixture in flight (a catalogue fetch that
 *    never resolves changes nothing about the verdict for
 *    stored rows);
 *  - book page (B1's state machine is its instance): stored
 *    chapters keep the page READY while a wait stands, and
 *    a pending wait over an empty stored list is the
 *    FINDING / honest-slow state, never an unavailability
 *    verdict.
 */
class V1510ProgressiveSurfaceTest {

    private val storedBooks = listOf(
        SourceBook(id = "b1", title = "Stored One"),
        SourceBook(id = "b2", title = "Stored Two")
    )

    @Test
    fun `catalogue mode table - pending never replaces stored rows`() {
        assertEquals(
            CatalogueSurfaceMode.CONTENT_UPDATING,
            catalogueSurfaceMode(
                CatalogUiState(books = storedBooks, loading = true)
            )
        )
        assertEquals(
            CatalogueSurfaceMode.CONTENT,
            catalogueSurfaceMode(
                CatalogUiState(books = storedBooks, loading = false)
            )
        )
        assertEquals(
            CatalogueSurfaceMode.LOADING_EMPTY,
            catalogueSurfaceMode(
                CatalogUiState(books = emptyList(), loading = true)
            )
        )
        assertEquals(
            CatalogueSurfaceMode.ERROR_EMPTY,
            catalogueSurfaceMode(
                CatalogUiState(
                    books = emptyList(), loading = false,
                    errorMessage = "didn't answer"
                )
            )
        )
        assertEquals(
            CatalogueSurfaceMode.EMPTY,
            catalogueSurfaceMode(
                CatalogUiState(
                    books = emptyList(), loading = false,
                    loadCompleted = true
                )
            )
        )
        // A fetch error arriving while stored rows stand must
        // not blank them either: content wins over the error.
        assertEquals(
            CatalogueSurfaceMode.CONTENT,
            catalogueSurfaceMode(
                CatalogUiState(
                    books = storedBooks, loading = false,
                    errorMessage = "refresh failed"
                )
            )
        )
    }

    @Test
    fun `hang fixture in flight - stored rows verdict is updating, never failed`() =
        runTest {
            val catalogue = HangCatalogueSource()
            val walk = async { catalogue.fetchCatalogue(1) }
            advanceTimeBy(120_000)
            assertTrue(walk.isActive)
            assertTrue(catalogue.gate.isAlive)
            // The surface state while the fetch hangs: stored
            // rows + loading — the progressive verdict.
            val state = CatalogUiState(
                books = storedBooks, loading = walk.isActive
            )
            assertEquals(
                CatalogueSurfaceMode.CONTENT_UPDATING,
                catalogueSurfaceMode(state)
            )
            walk.cancel()
        }

    @Test
    fun `book page - stored chapters render while a wait stands`() {
        // Stored chapters present: the page is READY (content
        // on screen) regardless of any pending refresh.
        assertEquals(
            ReadCtaState.READY,
            ChapterReadGuard.ctaState(
                isExtensionBook = true,
                chapterCount = 12,
                pageLoading = false,
                chaptersLoading = true
            )
        )
        assertNull(
            ChapterReadGuard.blockedReason(
                isExtensionBook = true,
                chapterCount = 12,
                pageLoading = false,
                chaptersLoading = true
            )
        )
    }

    @Test
    fun `book page - pending wait is finding, stalled wait is honest-slow, neither is a failure verdict`() =
        runBlocking {
            var now = 1_000L
            val registry = ChapterWaitRegistry { now }
            registry.begin(3L)
            // Pending, under the bound: FINDING_CHAPTERS.
            assertEquals(
                ReadCtaState.FINDING_CHAPTERS,
                ChapterReadGuard.ctaState(
                    isExtensionBook = true,
                    chapterCount = 0,
                    pageLoading = false,
                    chaptersLoading = true,
                    chaptersStalled = registry.isStalled(3L)
                )
            )
            // Past the bound: still FINDING (the slow pill
            // arm), never CHAPTERS_UNAVAILABLE, and no blocked
            // reason is produced for a merely-pending wait.
            now += SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS
            assertTrue(registry.isStalled(3L))
            assertEquals(
                ReadCtaState.FINDING_CHAPTERS,
                ChapterReadGuard.ctaState(
                    isExtensionBook = true,
                    chapterCount = 0,
                    pageLoading = false,
                    chaptersLoading = false,
                    verdict = ChaptersVerdict.EMPTY_SOURCE,
                    chaptersStalled = true
                )
            )
            assertNull(
                ChapterReadGuard.blockedReason(
                    isExtensionBook = true,
                    chapterCount = 0,
                    pageLoading = false,
                    chaptersLoading = false,
                    verdict = ChaptersVerdict.EMPTY_SOURCE,
                    chaptersStalled = true
                )
            )
        }
}
