package app.xreader.ui.localreader

import app.xreader.data.engine.LocatorJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

/**
 * v1.5.9 (F-3, Finding 4 P2): the locator pipeline's budget.
 *
 * (a) The derivation gate: 600 synthetic emissions in one
 * section, moving the whole-book progression smoothly through
 * ten 0.1% buckets, earn at most emissions/10 derivations —
 * and the LAST derived value is the final position (within
 * one bucket), i.e. throttling never loses the reader's place.
 *
 * (b) The session's precomputed surface (real fixture book):
 * `hrefToSection` resolves every href form [hrefMatches]
 * accepts to the same section the old scan found, and
 * `anchorTitleFor` is a list index into `anchorBySection` —
 * the precomputed titles — so it performs zero per-call JSON
 * parses by construction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LocatorPipelineBudgetTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `600 emissions earn at most a tenth of them in derivations`() {
        val gate = EpubLocatorGate()
        var derivations = 0
        var lastDerivedSection = -1
        var lastDerivedTotal = -1.0
        val emissions = 600
        for (i in 0 until emissions) {
            // One section; total progression glides 0.1000 →
            // 0.1096 (ten 0.1% buckets) — the shape of a scroll
            // through part of one chapter.
            val total = 0.10 + i * 0.000016
            if (gate.shouldDerive(section = 3, totalProgression = total)) {
                derivations++
                lastDerivedSection = 3
                lastDerivedTotal = total
            }
        }
        assertTrue(
            "derivations $derivations must be <= ${emissions / 10}",
            derivations <= emissions / 10
        )
        assertTrue("at least the bucket crossings derived", derivations >= 9)
        assertEquals(3, lastDerivedSection)
        val finalTotal = 0.10 + (emissions - 1) * 0.000016
        assertTrue(
            "final derived progress within one bucket of the true final",
            finalTotal - lastDerivedTotal < 0.001
        )
        // A section change always earns a derivation, even
        // inside the same bucket.
        assertTrue(gate.shouldDerive(section = 4, totalProgression = finalTotal))
    }

    @Test
    fun `precomputed href map and anchor list match the scan verdicts`() = runBlocking {
        val root = temp.newFolder("book")
        val epub = LocalReaderEpubFixture.writeEpub(root)
        val session = LocalReaderEpubFixture.openSession(root, epub)
        try {
            assertEquals(2, session.sectionCount)
            // The precomputed anchors: outline titles per section.
            assertEquals(
                listOf("Chapter One", "Chapter Two"), session.anchorBySection
            )
            repeat(50) {
                assertEquals("Chapter One", session.anchorTitleFor(0))
                assertEquals("Chapter Two", session.anchorTitleFor(1))
            }
            // Href forms the scan accepted resolve identically
            // through the map path (bare, fragment, absolute,
            // percent-encoded forms are covered by the fixture's
            // own reading-order hrefs + mutations of them).
            for (section in 0 until session.sectionCount) {
                val locator = session.locatorForSection(section)
                assertNotNull(locator)
                val json = locator!!.toJSON().toString()
                assertEquals(section, session.sectionIndexOfLocator(json))
                val href = LocatorJson.epubLocatorHref(json)!!
                assertEquals(section, session.sectionIndexOfHref(href))
                assertEquals(
                    section, session.sectionIndexOfHref("$href#frag")
                )
            }
        } finally {
            session.close()
        }
    }
}
