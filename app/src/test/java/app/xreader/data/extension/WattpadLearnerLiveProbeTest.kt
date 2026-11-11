package app.xreader.data.extension

import app.xreader.data.LiveSmoke
import app.xreader.data.net.OkHttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.rules.TemporaryFolder

/**
 * v1.5.2 (owner ruling 2026-11-03 ~17:29): the owed LEARNER-road
 * check for Wattpad. Wattpad does not ship as a built-in (its
 * API-recipe chapter-text leg is unproven — both candidate text
 * endpoints 404, build-logs/V152-B4-WATTPAD-PROBE.md); the owner
 * asked whether the paste-a-link learner proves it on its own.
 * This probe runs the REAL LinkProber over the live story URL
 * and RECORDS the outcome verbatim (class, missing legs, stage
 * lines, test-drive numbers) — it asserts only that the probe
 * completed; the verdict lives in the build report, from this
 * output. The hidden-browser escalation is exercised as far as
 * this machine allows: the forced-render retry runs through the
 * production flag, but no real RenderedPageFetcher exists on the
 * JVM (WebView is Android-only), so when the plain outcome asks
 * for rendering, that leg is recorded NOT EXERCISABLE HERE —
 * never silently claimed either way.
 */
@Category(LiveSmoke::class)
class WattpadLearnerLiveProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        LiveNet.installEgressProxyFromEnv()
        LiveNet.warmUp("https://www.wattpad.com/")
    }

    private fun describe(outcome: LinkProber.Outcome): String = when (outcome) {
        is LinkProber.Outcome.Ready -> buildString {
            append("READY config=").append(outcome.draft.config.id)
            append(" chapters=").append(outcome.draft.testDrive.chapterCount)
            append(" textLen=").append(outcome.draft.testDrive.chapterTextLength)
            append(" searchVerified=").append(outcome.draft.testDrive.searchVerified)
            append(" stages=").append(
                outcome.stageLines.joinToString(" | ") { it.render() }
            )
        }
        is LinkProber.Outcome.NotProven -> buildString {
            append("NOTPROVEN host=").append(outcome.host)
            append(" missingLegs=").append(outcome.missingLegs.joinToString(","))
            append(" renderWorthTrying=").append(outcome.renderWorthTrying)
            append(" stages=").append(
                outcome.stageLines.joinToString(" | ") { it.render() }
            )
        }
        is LinkProber.Outcome.LoginRequired ->
            "LOGINREQUIRED host=${outcome.host} siteCheck=${outcome.siteCheck}"
        is LinkProber.Outcome.Failed -> buildString {
            append("FAILED renderWorthTrying=").append(outcome.renderWorthTrying)
            append(" message=").append(outcome.message)
            append(" stages=").append(
                outcome.stageLines.joinToString(" | ") { it.render() }
            )
        }
    }

    @Test
    fun `wattpad story paste through the learner - verdict recorded`() = runBlocking {
        val transport = OkHttpTransport()
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()),
            testSessionStore(),
            transport
        )
        val prober = LinkProber(factory, testSessionStore())
        val storyUrl = "https://www.wattpad.com/story/415664012-mystery-of-love-lenamiu"
        val outcome = prober.probe(storyUrl)
        println("WATTPAD-LEARNER story: ${describe(outcome)}")
        val renderWorthTrying = when (outcome) {
            is LinkProber.Outcome.NotProven -> outcome.renderWorthTrying
            is LinkProber.Outcome.Failed -> outcome.renderWorthTrying
            else -> false
        }
        if (renderWorthTrying) {
            // The production escalation flag, exercised as far as
            // the JVM allows: with no real rendered fetcher wired
            // (Android WebView only), contexts stay static, so a
            // Ready here would be a STATIC-road proof and anything
            // else leaves the rendered leg device-owed.
            val rendered = prober.probe(storyUrl, forceRender = true)
            println("WATTPAD-LEARNER forced-render retry: ${describe(rendered)}")
            println(
                "WATTPAD-LEARNER note: no real RenderedPageFetcher " +
                    "exists on this machine (WebView is Android-only); " +
                    "the rendered escalation leg itself is NOT " +
                    "exercisable here."
            )
        }
        assertTrue("the probe completed with an outcome", true)
        Unit
    }
}
