package app.xreader.data.extension

import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.5.1 regression pins for the device failure the whole
 * 671-test v1.0.5 gate could not see.
 *
 * The fault: the probe gated the known-sites fast lane on
 * [LinkInference.analyseChapterPage] returning null — but that
 * analysis fires on ANY page carrying a ≥300-char low-link
 * prose block, and a normal book page's synopsis is exactly
 * that. Novelight's real book pages carry a long synopsis, so
 * on the device the verified config was silently skipped, the
 * guessing proposers failed the drive (the chapter text lives
 * behind the ajax endpoint only the lane config knows), and
 * Repair rolled back to the broken legacy rules — 0 chapters.
 * The CI fixtures were trimmed captures WITHOUT the synopsis
 * block, so the gate never tripped in tests.
 *
 * These tests pin the fixed contract: the lane is
 * unconditional for recognised domains, and a total failure
 * reports the proposer stages it actually evaluated.
 */
class V1051LaneGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    @Test
    fun `premise - the long synopsis trips the chapter page analysis`() {
        // The gate's condition on the page shape the live site
        // actually serves: non-null, so the v1.0.5 lane was
        // skipped for exactly this page.
        val live = Jsoup.parse(
            NovelightSite.BOOK_PAGE_LONG_SYNOPSIS, NovelightSite.BOOK_URL
        )
        assertNotNull(
            LinkInference.analyseChapterPage(live, NovelightSite.BOOK_URL)
        )
        // …while the trimmed v1.0.5 fixture page never trips
        // it — the whole reason CI stayed green.
        val trimmed = Jsoup.parse(
            NovelightSite.BOOK_PAGE, NovelightSite.BOOK_URL
        )
        assertNull(
            LinkInference.analyseChapterPage(trimmed, NovelightSite.BOOK_URL)
        )
    }

    @Test
    fun `the fast lane installs even when the book page carries a long synopsis`() =
        runBlocking {
            val outcome = prober(NovelightSite.transportLongSynopsis())
                .probe(NovelightSite.BOOK_URL)
            assertTrue(
                "expected Ready, got $outcome",
                outcome is LinkProber.Outcome.Ready
            )
            val draft = (outcome as LinkProber.Outcome.Ready).draft
            assertEquals("Lord of Talents", draft.testDrive.bookTitle)
            assertEquals(5, draft.testDrive.chapterCount)
            // The winning config IS the curated lane config —
            // proven by its ajax text mode, which only the lane
            // carries.
            assertEquals(
                "${NovelightSite.BASE}/book/ajax/read-chapter/{id}",
                draft.config.chapter.ajax?.urlTemplate
            )
        }

    @Test
    fun `repair mode installs the fast lane over the long synopsis too`() =
        runBlocking {
            // The exact path that failed on the device: Repair
            // drives every proposer and keeps the best passing
            // one — the lane must be among them, synopsis or not.
            val outcome = prober(NovelightSite.transportLongSynopsis())
                .probe(
                    NovelightSite.BOOK_URL,
                    aiAvailable = false, tryAllProposers = true
                )
            assertTrue(
                "expected Ready, got $outcome",
                outcome is LinkProber.Outcome.Ready
            )
            val draft = (outcome as LinkProber.Outcome.Ready).draft
            assertEquals(5, draft.testDrive.chapterCount)
            assertEquals(
                "${NovelightSite.BASE}/book/ajax/read-chapter/{id}",
                draft.config.chapter.ajax?.urlTemplate
            )
        }

    @Test
    fun `a total failure reports the proposer stages it evaluated`() = runBlocking {
        // The sparse blog page: no known site (no lane), the
        // book-page inference runs and finds nothing, the
        // heuristic proposer runs and finds nothing. The
        // failure must name exactly those two stages.
        val outcome = prober(MoonSite.transport())
            .probe("https://blog.example/post", aiAvailable = false)
        assertTrue(
            "expected Failed, got $outcome",
            outcome is LinkProber.Outcome.Failed
        )
        val failed = outcome as LinkProber.Outcome.Failed
        assertEquals(
            listOf("inference", "heuristics"),
            failed.proposersTried
        )
    }
}
