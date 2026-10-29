package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6 Stage A pins: the all-locked probe trap from the
 * v1.0.5.1 device draw-check, closed.
 *
 * The trap: Repair seeded the probe from the stored sample
 * book — "Urban Vagabond: Reload", whose 60 listed chapters
 * are ALL paid or locked. The drive listed the chapters
 * fine, then found no unlocked chapter to sample, judged
 * the (correct) candidate FAILED, and Repair saved nothing;
 * the tester afterwards condemned the legacy rules for the
 * probe's failure. These tests pin the three-part fix: the
 * drive passes-with-limits on an all-locked sample (and
 * ONLY on that explicit outcome), Repair picks a fresh
 * sample preferring a book with an unlocked chapter, and
 * an all-locked TEXT verdict reads WORKING_WITH_LIMITS.
 */
class V106StageATest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    @Test
    fun `the lane config passes with limits against an all-locked sample`() =
        runBlocking {
            val outcome = prober(NovelightSite.transportAllLocked())
                .probe(NovelightSite.BOOK_URL)
            assertTrue(
                "expected Ready, got $outcome",
                outcome is LinkProber.Outcome.Ready
            )
            val ready = outcome as LinkProber.Outcome.Ready
            val drive = ready.draft.testDrive
            assertEquals(5, drive.chapterCount)
            assertEquals(5, drive.lockedChapterCount)
            assertTrue(drive.allChaptersLocked)
            assertEquals(0, drive.chapterTextLength)
            // It IS the curated lane config (the ajax text
            // mode only the lane carries).
            assertEquals(
                "${NovelightSite.BASE}/book/ajax/read-chapter/{id}",
                ready.draft.config.chapter.ajax?.urlTemplate
            )
            // And the stage log names the limit honestly.
            assertTrue(
                "stage lines: ${ready.stageLines}",
                ready.stageLines.any {
                    it.proposer == "fast lane" && it.step == "chapter text"
                }
            )
        }

    @Test
    fun `repair mode also passes with limits against an all-locked sample`() =
        runBlocking {
            val outcome = prober(NovelightSite.transportAllLocked())
                .probe(
                    NovelightSite.BOOK_URL,
                    aiAvailable = false, tryAllProposers = true
                )
            assertTrue(
                "expected Ready, got $outcome",
                outcome is LinkProber.Outcome.Ready
            )
            val ready = outcome as LinkProber.Outcome.Ready
            assertTrue(ready.draft.testDrive.allChaptersLocked)
        }

    @Test
    fun `the shared drive passes an all-locked sample - the AI gate inherits it`() =
        runBlocking {
            // SelectorAi judges its proposals through exactly
            // this public test-drive; an all-locked sample
            // must come back Ok there too, or AI-built
            // configs would keep dying in the same trap.
            val result = prober(NovelightSite.transportAllLocked())
                .testDrive(laneConfig(), NovelightSite.BOOK_URL)
            assertTrue("expected Ok, got $result", result is AppResult.Ok)
            val drive = (result as AppResult.Ok).value
            assertTrue(drive.allChaptersLocked)
            assertEquals(5, drive.chapterCount)
        }

    @Test
    fun `the sample picker prefers a book with an unlocked chapter`() =
        runBlocking {
            // Catalogue book 1 is all-locked; book 2 has
            // unlocked chapters — the picker must skip to it.
            val picked = prober(NovelightSite.transportPicker())
                .freshSampleUrl(laneConfig())
            assertEquals(NovelightSite.BOOK2_URL, picked)
        }

    @Test
    fun `the sample picker falls back to the first book when every book is all-locked`() =
        runBlocking {
            // No book qualifies; the first book with a chapter
            // list is used and the drive's pass-with-limits
            // (above) is what saves the repair.
            val picked = prober(NovelightSite.transportAllLocked())
                .freshSampleUrl(laneConfig())
            assertEquals(NovelightSite.BOOK_URL, picked)
        }

    private fun coreOk(): List<CapabilityResult> =
        ContractVerdict.READING_CORE.map {
            CapabilityResult(it, ok = true, detail = "ok")
        }

    @Test
    fun `an all-locked text verdict works with limits`() {
        val results = coreOk().map {
            if (it.capability == SourceCapability.TEXT) {
                CapabilityResult(
                    it.capability, ok = false,
                    detail = "all 5 chapter(s) are marked paid or " +
                        "locked on this site — locked chapters " +
                        "can't be sampled",
                    limitOnly = true
                )
            } else {
                it
            }
        }
        assertEquals(
            SourceHealthState.WORKING_WITH_LIMITS,
            ContractVerdict.judge(results, checkedAtMillis = 5)
        )
    }

    @Test
    fun `an all-locked text verdict plus a real core failure still needs setup`() {
        val results = coreOk().map {
            when (it.capability) {
                SourceCapability.TEXT -> CapabilityResult(
                    it.capability, ok = false,
                    detail = "all 5 chapter(s) are marked paid or locked",
                    limitOnly = true
                )
                SourceCapability.CHAPTERS -> CapabilityResult(
                    it.capability, ok = false,
                    detail = "the book page listed 0 chapters"
                )
                else -> it
            }
        }
        assertEquals(
            SourceHealthState.NEEDS_SETUP,
            ContractVerdict.judge(results, checkedAtMillis = 5)
        )
    }

    @Test
    fun `problem details carry the repair record when one exists`() {
        val text = ProblemDetails.format(
            host = "novelight.net",
            failingStep = "Chapter lists",
            reason = "The book page listed 0 chapters.",
            appVersion = "1.0.6",
            repair = SourceRepairRecord(
                repairedAtMillis = 5,
                saved = false,
                note = "Repair couldn't read the site.",
                stageLines = listOf(
                    "fast lane — chapter text: all 60 chapters on " +
                        "the test book are paid or locked"
                )
            )
        )
        assertEquals(
            """
            Xreader problem details
            Site: novelight.net
            Failing step: Chapter lists
            Problem: The book page listed 0 chapters.
            Last repair: didn't change the rules.
            Repair note: Repair couldn't read the site.
            Repair log:
            - fast lane — chapter text: all 60 chapters on the test book are paid or locked
            App version: 1.0.6
            """.trimIndent(),
            text
        )
    }
}
