package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.5 (E4) end-to-end pins over the trimmed live-capture
 * fixtures: the novelight fast lane through the real engine
 * (E3 complete lists, A3 locked rows), the honest test-drive
 * verdicts (A1), the lying search (A5), the JS-shell refusal
 * (A9) and the conditional AI copy (A10).
 */
class V105ProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun prober(transport: app.xreader.data.FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    // ── A11 + E3 + A3: the fast lane through the real engine ────

    @Test
    fun `novelight lane reads the complete deduped chapter list`() {
        val entry = KnownSites.entryFor(NovelightSite.HOST)!!
        val config = KnownSites.fullConfig(
            entry, "link-test", "Novelight", NovelightSite.BASE
        )!!
        val context = testContext(
            NovelightSite.transport(), tmp.newFolder(),
            ExtensionPermissions(domains = listOf(NovelightSite.HOST))
        )
        val source = DeclarativeSource(config, context)
        val book = SourceBook(id = NovelightSite.BOOK_URL, title = "")
        val (details, chapters) = source.detailsAndChapters(book)
        assertEquals("Lord of Talents", details.title)
        // The page shows 3 + 5 rows over two blocks; the complete
        // list is the 5 unique chapters, in reading order (E3).
        assertEquals(5, chapters.size)
        assertTrue(chapters.first().id.endsWith("/101"))
        assertTrue(chapters.last().id.endsWith("/105"))
        // The two newest chapters are lock-marked (A3).
        assertEquals(
            setOf(
                "${NovelightSite.BASE}/book/chapter/104",
                "${NovelightSite.BASE}/book/chapter/105"
            ),
            source.lockedChapterIds(book)
        )
        // The book page itself carries no cover (documented
        // family limit) — covers bind from the catalogue cards.
        assertNull(details.coverUrl)
        val popular = source.popular(1)
        assertEquals(2, popular.books.size)
        assertTrue(popular.books.first().coverUrl!!.endsWith("lord.webp"))
    }

    // Stage 1.5: the lane's ajax text mode closes the one gap
    // Stage 1 had to leave open — the drive now passes EVERY
    // step for novelight (list, locked-aware text sampled from
    // an unlocked chapter over the ajax endpoint, proven search).
    @Test
    fun `novelight drive passes every step with the ajax text mode`() = runBlocking {
        val entry = KnownSites.entryFor(NovelightSite.HOST)!!
        val config = KnownSites.fullConfig(
            entry, "link-test", "Novelight", NovelightSite.BASE
        )!!
        val result = prober(NovelightSite.transport())
            .testDrive(config, NovelightSite.BOOK_URL)
        assertTrue("expected Ok, got $result", result is AppResult.Ok)
        val drive = (result as AppResult.Ok).value
        assertEquals("Lord of Talents", drive.bookTitle)
        assertEquals(5, drive.chapterCount)
        assertTrue(
            "text sampled from an unlocked chapter, got ${drive.chapterTextLength}",
            drive.chapterTextLength >= LinkInference.MIN_READABLE_CHARS
        )
        assertTrue(drive.chapterTextPreview.contains("lord of talents"))
        assertTrue("search must prove it filters", drive.searchVerified)
    }

    @Test
    fun `novelight probe installs the fast lane once the drive passes`() = runBlocking {
        val outcome = prober(NovelightSite.transport())
            .probe(NovelightSite.BOOK_URL)
        assertTrue("expected Ready, got $outcome", outcome is LinkProber.Outcome.Ready)
        val draft = (outcome as LinkProber.Outcome.Ready).draft
        assertEquals("Lord of Talents", draft.testDrive.bookTitle)
        assertEquals(5, draft.testDrive.chapterCount)
        assertTrue(draft.testDrive.searchVerified)
        assertTrue(!draft.config.searchNeedsSetup)
        // The saved rules carry the ajax text mode.
        assertEquals(
            "${NovelightSite.BASE}/book/ajax/read-chapter/{id}",
            draft.config.chapter.ajax?.urlTemplate
        )
    }

    @Test
    fun `novelight probe from the site root reaches the same passing drive`() =
        runBlocking {
            val outcome = prober(NovelightSite.transport())
                .probe(NovelightSite.BASE)
            assertTrue(
                "expected Ready, got $outcome",
                outcome is LinkProber.Outcome.Ready
            )
            val draft = (outcome as LinkProber.Outcome.Ready).draft
            assertEquals("Lord of Talents", draft.testDrive.bookTitle)
            assertEquals(5, draft.testDrive.chapterCount)
        }

    // ── A5: the lying search ────────────────────────────────────

    @Test
    fun `a search that returns the catalogue for everything is not verified`() =
        runBlocking {
            val outcome = prober(LyingSearchSite.transport())
                .probe(LyingSearchSite.BOOK_URL)
            assertTrue("expected Ready, got $outcome", outcome is LinkProber.Outcome.Ready)
            val draft = (outcome as LinkProber.Outcome.Ready).draft
            assertEquals("Quiet Star", draft.testDrive.bookTitle)
            assertEquals(3, draft.testDrive.chapterCount)
            assertTrue(!draft.testDrive.searchVerified)
            assertTrue(draft.config.searchNeedsSetup)
            assertTrue(draft.testDrive.searchFailure!!.contains("doesn't filter"))
        }

    // ── A9: JS shell refusal ────────────────────────────────────

    @Test
    fun `a javascript shell is refused plainly and ai copy stays honest`() = runBlocking {
        val outcome = prober(JsShellSite.transport())
            .probe("${JsShellSite.BASE}/book/anything")
        assertTrue("expected Failed, got $outcome", outcome is LinkProber.Outcome.Failed)
        val message = (outcome as LinkProber.Outcome.Failed).message
        assertTrue(message.contains("JavaScript"))
        assertTrue(message.contains("no source was added"))
    }

    // ── A10: failure copy mentions AI only when the button shows ─

    @Test
    fun `failure copy points at providers and keys when no ai button will render`() =
        runBlocking {
            val outcome = prober(MoonSite.transport())
                .probe("https://blog.example/post", aiAvailable = false)
            assertTrue(outcome is LinkProber.Outcome.Failed)
            val message = (outcome as LinkProber.Outcome.Failed).message
            assertTrue(message.contains("Providers & keys"))
            assertTrue(!message.contains("AI detector below"))
        }

    @Test
    fun `failure copy mentions the ai detector when it will render`() = runBlocking {
        val outcome = prober(MoonSite.transport())
            .probe("https://blog.example/post", aiAvailable = true)
        assertTrue(outcome is LinkProber.Outcome.Failed)
        val message = (outcome as LinkProber.Outcome.Failed).message
        assertTrue(message.contains("AI detector below"))
    }
}
