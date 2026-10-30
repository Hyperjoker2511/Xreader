package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.prefs.SourcePrefs
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.1 pins: the six-fix round from the v1.0.6 draw-check.
 *
 * P1 — add-from-link refused EVERY input on the device,
 * including addresses the same helpers accepted inside Repair.
 * The accepted cause (Gemini round, reviewed): invisible
 * Unicode riding in the pasted string, invisible to trim() and
 * fatal to the URI parser. normaliseUrl now sanitises,
 * extracts, and only then validates — and a refusal records
 * the input's fingerprint in the diagnostic log.
 *
 * P3 — Repair's fresh-sample picker judged candidates with
 * the stored config's own chapter rules, so a config whose
 * chapter rules were broken rejected every candidate and the
 * picker fell back to the stored all-locked sample. Judging
 * is config-free now; these tests pin that a BROKEN stored
 * config still yields the right sample. (The Stage A picker
 * tests pin the same scenarios with a working config and are
 * unchanged.)
 *
 * P4 — the test-drive's lock read failed open: a thrown lock
 * read became "nothing is locked", and boilerplate length
 * alone could pass as proven chapter text. The read is
 * tri-state now, and a lock-unchecked candidate passes only
 * through the prose gate, carrying its limit.
 *
 * P6 — Copy details gains the session's recent problems, and
 * stays byte-identical when there are none.
 */
class V1061FixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun resetLog() = DiagnosticLog.clear()

    @After
    fun clearLog() = DiagnosticLog.clear()

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    private fun laneConfig(): DeclarativeConfig {
        val entry = KnownSites.entryFor(NovelightSite.HOST)
        assertTrue("novelight must be a known site", entry != null)
        return KnownSites.fullConfig(
            entry = entry!!,
            id = "link-novelight-test",
            name = "Novelight",
            baseUrl = NovelightSite.BASE
        )!!
    }

    // ── P1: robust URL input ─────────────────────────────────

    @Test
    fun `a zero-width mark on a pasted address no longer refuses it`() {
        assertEquals(
            "https://novelight.net",
            LinkProber.normaliseUrl("https://novelight.net\u200B")
        )
        assertEquals(
            "https://novelight.net",
            LinkProber.normaliseUrl("novelight.net\u200E")
        )
        assertEquals(
            "https://novelight.net/book/lord-of-talents",
            LinkProber.normaliseUrl("\uFEFFhttps://novelight.net/book/lord-of-talents")
        )
    }

    @Test
    fun `an address wrapped in share text is extracted`() {
        assertEquals(
            "https://novelight.net/book/lord-of-talents",
            LinkProber.normaliseUrl(
                "Read this: https://novelight.net/book/lord-of-talents"
            )
        )
    }

    @Test
    fun `a bare domain is accepted with the scheme added`() {
        assertEquals(
            "https://novelight.net",
            LinkProber.normaliseUrl("novelight.net")
        )
        assertEquals(
            "https://novelight.net/book/quiet-river",
            LinkProber.normaliseUrl("novelight.net/book/quiet-river")
        )
    }

    @Test
    fun `an uppercase scheme is accepted`() {
        val url = LinkProber.normaliseUrl("HTTPS://Novelight.NET/book/x")
        assertNotNull(url)
        assertEquals("novelight.net", CookieParsing.hostOf(url!!))
    }

    @Test
    fun `clean inputs come out exactly as before`() {
        assertEquals(
            "https://novelight.net/book/lord-of-talents",
            LinkProber.normaliseUrl("https://novelight.net/book/lord-of-talents")
        )
        // The pre-existing pins (LinkInferenceTest) still hold.
        assertEquals(
            "https://moonread.example/novel/star-fall",
            LinkProber.normaliseUrl("moonread.example/novel/star-fall")
        )
        assertNull(LinkProber.normaliseUrl("not a url"))
        assertNull(LinkProber.normaliseUrl(""))
    }

    @Test
    fun `the diagnostics name the invisible code points`() {
        val detail = LinkProber.inputDiagnostics("https://novelight.net\u200B")
        assertTrue(detail, detail.startsWith("len=22"))
        assertTrue(detail, detail.contains("hidden=[U+200B]"))
        assertEquals("len=9", LinkProber.inputDiagnostics("not a url"))
    }

    @Test
    fun `a refused link records its fingerprint in the diagnostic log`() =
        runBlocking {
            val outcome = prober(NovelightSite.transport()).probe("not a url")
            assertTrue(
                "expected Failed, got $outcome",
                outcome is LinkProber.Outcome.Failed
            )
            val entries = DiagnosticLog.entries()
            assertEquals(1, entries.size)
            assertEquals("add from link", entries.single().tag)
            assertTrue(entries.single().detail.contains("len=9"))
        }

    // ── P3: config-free fresh sample ─────────────────────────

    /** The lane config with its chapter-list rule broken —
     * enumeration (popular) intact, chapter parsing dead. The
     * old picker judged candidates with these rules and so
     * rejected every book; the config-free judge must not. */
    private fun brokenLaneConfig(): DeclarativeConfig {
        val lane = laneConfig()
        return lane.copy(
            details = lane.details.copy(
                chapters = lane.details.chapters.copy(
                    list = ".rows-that-do-not-exist"
                )
            )
        )
    }

    @Test
    fun `a broken stored config still picks the book with an unlocked chapter`() =
        runBlocking {
            val picked = prober(NovelightSite.transportPicker())
                .freshSampleUrl(brokenLaneConfig())
            assertEquals(NovelightSite.BOOK2_URL, picked)
        }

    @Test
    fun `a broken stored config still falls back to the first listed book`() =
        runBlocking {
            val picked = prober(NovelightSite.transportAllLocked())
                .freshSampleUrl(brokenLaneConfig())
            assertEquals(NovelightSite.BOOK_URL, picked)
        }

    // ── P4: tri-state lock detection + prose gate ────────────

    private object LockFailSite {
        const val BASE = "https://lockfail.example"
        const val BOOK_URL = "$BASE/book/clockwork-garden"

        /** Six sentences, ~430 chars — genuine prose. */
        const val PROSE =
            "The mill had stood silent for eleven years before Mara " +
                "came back to the valley. She found the great wheel " +
                "choked with ivy and the gears green with moss. Her " +
                "grandfather's notebook lay where he had left it, " +
                "open to the last page. Wind the heart gear first, " +
                "it said, and mind the spring when it wakes. Mara " +
                "laughed out loud in the empty room, because the " +
                "warning felt like a greeting. Then she rolled up " +
                "her sleeves and began."

        /** Long, but not one sentence-ender in it — page
         * boilerplate of exactly the kind the old fail-open
         * lock read let pass as proven text. */
        const val BOILERPLATE =
            "Home Latest Updates Popular This Week New Releases " +
                "Top Rated Genres Action Adventure Comedy Drama " +
                "Fantasy Horror Mystery Romance Science Fiction " +
                "Slice Of Life Tags Alpha Beta Gamma Delta Authors " +
                "Ranking Daily Weekly Monthly Yearly Completed " +
                "Ongoing Hiatus Forum Discord Twitter Facebook " +
                "Instagram Youtube Privacy Policy Terms Of Service " +
                "Contact Us About Advertise Copyright Notice All " +
                "Rights Reserved Reading Settings Font Size Line " +
                "Height Night Mode"

        val BOOK_PAGE = """
            <html><head><title>The Clockwork Garden</title></head><body>
            <h1>The Clockwork Garden</h1>
            <ul id="chapter-list">
            <li><a href="/book/clockwork-garden/chapter-1">Chapter 1</a></li>
            <li><a href="/book/clockwork-garden/chapter-2">Chapter 2</a></li>
            <li><a href="/book/clockwork-garden/chapter-3">Chapter 3</a></li>
            </ul>
            </body></html>
        """.trimIndent()

        fun chapterPage(text: String) = """
            <html><head><title>Chapter</title></head><body>
            <div id="chapter-content"><p>$text</p></div>
            </body></html>
        """.trimIndent()

        fun config(): DeclarativeConfig = DeclarativeConfig(
            id = "link-lockfail-test",
            name = "Lockfail",
            baseUrl = BASE,
            details = DetailsSpec(
                title = "h1",
                chapters = ChapterListSpec(
                    list = "#chapter-list li",
                    name = "@text",
                    link = "a@href"
                )
            ),
            chapter = ChapterSpec(content = "#chapter-content"),
            sampleBookUrl = BOOK_URL
        )

        /** The book page serves ONCE (the drive's details
         * read); the lock read's own fetch of the same page is
         * refused with a 500, so lock detection fails while
         * everything else about the drive works. */
        fun transportFailingLockRead(text: String): FakeTransport {
            val bookFetches = AtomicInteger(0)
            return FakeTransport { request ->
                when {
                    request.url == BOOK_URL ->
                        if (bookFetches.incrementAndGet() == 1) {
                            FakeTransport.ok(BOOK_PAGE)
                        } else {
                            AppResult.Ok(
                                FakeTransport.response(500, "server error")
                            )
                        }
                    request.url.startsWith("$BASE/book/clockwork-garden/chapter-") ->
                        FakeTransport.ok(chapterPage(text))
                    else ->
                        AppResult.Ok(FakeTransport.response(404, "not found"))
                }
            }
        }

        /** The same site with a healthy lock read — the
         * control: no row carries a lock mark, so detection
         * succeeds with an empty locked set. */
        fun transportHealthy(text: String): FakeTransport =
            FakeTransport { request ->
                when {
                    request.url == BOOK_URL -> FakeTransport.ok(BOOK_PAGE)
                    request.url.startsWith("$BASE/book/clockwork-garden/chapter-") ->
                        FakeTransport.ok(chapterPage(text))
                    else ->
                        AppResult.Ok(FakeTransport.response(404, "not found"))
                }
            }
    }

    @Test
    fun `a failed lock read with real prose passes carrying the unchecked-locks limit`() =
        runBlocking {
            val result = prober(
                LockFailSite.transportFailingLockRead(LockFailSite.PROSE)
            ).testDrive(LockFailSite.config(), LockFailSite.BOOK_URL)
            assertTrue("expected Ok, got $result", result is AppResult.Ok)
            val drive = (result as AppResult.Ok).value
            assertTrue(drive.lockStateUnknown)
            assertFalse(drive.allChaptersLocked)
            assertTrue(drive.chapterTextLength >= 200)
        }

    @Test
    fun `a failed lock read with boilerplate text fails`() =
        runBlocking {
            val result = prober(
                LockFailSite.transportFailingLockRead(LockFailSite.BOILERPLATE)
            ).testDrive(LockFailSite.config(), LockFailSite.BOOK_URL)
            assertTrue("expected Err, got $result", result is AppResult.Err)
        }

    @Test
    fun `a healthy lock read passes without the limit`() =
        runBlocking {
            val result = prober(
                LockFailSite.transportHealthy(LockFailSite.PROSE)
            ).testDrive(LockFailSite.config(), LockFailSite.BOOK_URL)
            assertTrue("expected Ok, got $result", result is AppResult.Ok)
            val drive = (result as AppResult.Ok).value
            assertFalse(drive.lockStateUnknown)
            assertFalse(drive.allChaptersLocked)
        }

    // ── P6: problem details + the prefs clear ────────────────

    @Test
    fun `problem details are byte-identical when the log is empty`() {
        val text = ProblemDetails.format(
            host = "novelight.net",
            failingStep = "Chapter lists",
            reason = "The book page listed no chapters.",
            appVersion = "1.0.5",
            recentProblems = DiagnosticLog.entries()
        )
        assertEquals(
            """
            Xreader problem details
            Site: novelight.net
            Failing step: Chapter lists
            Problem: The book page listed no chapters.
            App version: 1.0.5
            """.trimIndent(),
            text
        )
    }

    @Test
    fun `problem details append the recent problems after the version line`() {
        DiagnosticLog.record(
            "add from link",
            "That doesn't look like a web address — check the link and try again.",
            "len=9"
        )
        val text = ProblemDetails.format(
            host = "novelight.net",
            failingStep = null,
            reason = "The site did not answer.",
            appVersion = "1.0.6.1",
            recentProblems = DiagnosticLog.entries()
        )
        assertTrue(text, text.contains("Recent problems (this session):"))
        assertTrue(text, text.contains("add from link"))
        assertTrue(text, text.contains("(len=9)"))
        assertTrue(
            text,
            text.indexOf("Recent problems (this session):") >
                text.indexOf("App version:")
        )
    }

    @Test
    fun `a cleared health record is gone from the stored map`() =
        runBlocking {
            val prefs = SourcePrefs(FakePreferencesStore())
            val key = SourceHealthRecord.key(7L, "src-1")
            prefs.setSourceHealth(
                key, SourceHealthRecord(SourceHealthState.BROKEN)
            )
            assertTrue(prefs.sourceHealth.first().containsKey(key))
            prefs.clearSourceHealth(key)
            assertFalse(prefs.sourceHealth.first().containsKey(key))
        }
}
