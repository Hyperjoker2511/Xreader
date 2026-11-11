package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.prefs.PerfTier
import app.xreader.extensionapi.HostResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (D1): the render clock's three proofs.
 *  - [RenderProgress] — the PURE progress→deadline decision,
 *    exercised with recorded sample sequences (the WebView
 *    fetcher is device-bound; this function is the pinch
 *    surface its poll loop obeys).
 *  - The declared budget reaches fetchRendered's budget
 *    parameter through the escalating fetcher, and the
 *    outer fetch budget rises in step with the ceiling.
 *  - [RenderProbeGuard] — a JS probe that never answers
 *    degrades to null promptly instead of parking the loop.
 */
class V158RenderProgressExtensionTest {

    private fun input(
        committed: Boolean,
        lastActivityMs: Long,
        nowMs: Long,
        base: Long = 30_000,
        ceiling: Long = 60_000,
        declared: Long? = null,
        startedMs: Long = 0
    ) = RenderProgressInput(
        committed = committed,
        readyState = if (committed) "interactive" else "loading",
        lastActivityMs = lastActivityMs,
        nowMs = nowMs,
        startedMs = startedMs,
        baseBudgetMs = base,
        ceilingMs = ceiling,
        declaredBudgetMs = declared
    )

    @Test
    fun progressingPageExtendsToTheCeilingAndNoFurther() {
        // Committed at ~40 s, still progressing at 45 s: the
        // deadline extends — bounded by the 60 s ceiling.
        val extended = RenderProgress.evaluate(
            input(committed = true, lastActivityMs = 44_500, nowMs = 45_000)
        )
        assertEquals(
            RenderDeadlineDecision.Extend(60_000), extended
        )
        // At the ceiling the verdict falls even mid-progress.
        val verdict = RenderProgress.evaluate(
            input(committed = true, lastActivityMs = 59_500, nowMs = 60_000)
        )
        assertEquals(RenderDeadlineDecision.Verdict, verdict)
    }

    @Test
    fun noCommitNoActivityVerdictsAtTheBaseBudget() {
        val extended = RenderProgress.evaluate(
            input(committed = false, lastActivityMs = 0, nowMs = 29_999)
        )
        assertEquals(RenderDeadlineDecision.Extend(30_000), extended)
        val verdict = RenderProgress.evaluate(
            input(committed = false, lastActivityMs = 0, nowMs = 30_000)
        )
        assertEquals(RenderDeadlineDecision.Verdict, verdict)
    }

    @Test
    fun stalledActivityVerdictsOneBaseBudgetAfterTheLastActivity() {
        // Progress until 20 s, then silence: the verdict
        // falls at last-activity + base (50 s), not before.
        val extended = RenderProgress.evaluate(
            input(committed = true, lastActivityMs = 20_000, nowMs = 49_999)
        )
        assertEquals(RenderDeadlineDecision.Extend(50_000), extended)
        val verdict = RenderProgress.evaluate(
            input(committed = true, lastActivityMs = 20_000, nowMs = 50_000)
        )
        assertEquals(RenderDeadlineDecision.Verdict, verdict)
    }

    @Test
    fun declaredBudgetReplacesTheBase() {
        val extended = RenderProgress.evaluate(
            input(
                committed = false, lastActivityMs = 0, nowMs = 59_999,
                declared = 60_000
            )
        )
        assertEquals(RenderDeadlineDecision.Extend(60_000), extended)
        val verdict = RenderProgress.evaluate(
            input(
                committed = false, lastActivityMs = 0, nowMs = 60_000,
                declared = 60_000
            )
        )
        assertEquals(RenderDeadlineDecision.Verdict, verdict)
    }
}

class V158DeclaredRenderBudgetTest {

    /** Records the budget argument of every render. */
    private class RecordingRenderer : RenderedPageFetcher {
        val budgets = mutableListOf<Long?>()

        override suspend fun fetchRendered(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions,
            waitForSelector: String?
        ): AppResult<HostResponse> = fetchRendered(
            url, sessionKey, permissions, waitForSelector, null
        )

        override suspend fun fetchRendered(
            url: String,
            sessionKey: String,
            permissions: ExtensionPermissions,
            waitForSelector: String?,
            renderBudgetMs: Long?
        ): AppResult<HostResponse> {
            budgets += renderBudgetMs
            return AppResult.Ok(
                HostResponse(
                    200,
                    "<html><body><p>real catalogue, fully built</p></body></html>",
                    finalUrl = url
                )
            )
        }
    }

    private class ShellStatic : PageFetcher {
        override suspend fun get(
            url: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> = AppResult.Ok(
            HostResponse(
                200,
                "<html><body><div id=\"root\"></div>" +
                    "<script src=\"/app.js\"></script></body></html>",
                finalUrl = url
            )
        )

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>
        ): AppResult<HostResponse> = get(url, headers)
    }

    private fun escalating(
        renderer: RecordingRenderer,
        declared: Long?
    ) = EscalatingPageFetcher(
        staticFetcher = ShellStatic(),
        renderedFetcher = renderer,
        sessionKey = "v158-d1",
        purpose = RenderPurpose.SOURCE,
        needsRendering = { false },
        permissions = {
            ExtensionPermissions(domains = listOf("x.example"))
        },
        rateLimiter = RateLimiter(minIntervalMs = 0),
        gate = RenderGate(
            masterEnabled = { true },
            tier = { PerfTier.FULL },
            userDriven = { true },
            backgroundWork = { false }
        ),
        renderBudgetMs = { declared },
        shellHosts = ShellHostMemory()
    )

    @Test
    fun declaredBudgetReachesTheFetcher() = runBlocking {
        val renderer = RecordingRenderer()
        val result = escalating(renderer, 60_000)
            .get("https://x.example/popular")
        assertTrue(result is AppResult.Ok)
        assertEquals(listOf(60_000L), renderer.budgets)
    }

    @Test
    fun noDeclaredBudgetPassesNull() = runBlocking {
        val renderer = RecordingRenderer()
        val result = escalating(renderer, null)
            .get("https://x.example/popular")
        assertTrue(result is AppResult.Ok)
        assertEquals(listOf<Long?>(null), renderer.budgets)
    }

    @Test
    fun fetchBudgetRisesInStepWithTheCeiling() {
        // Ceiling 60 s (FULL/STANDARD) + 45 s transport room.
        assertEquals(105_000L, RenderSettle.fetchBudgetMs(PerfTier.FULL))
        assertEquals(105_000L, RenderSettle.fetchBudgetMs(PerfTier.STANDARD))
        // Ceiling 90 s (MINIMAL) + 45 s.
        assertEquals(135_000L, RenderSettle.fetchBudgetMs(PerfTier.MINIMAL))
        assertEquals(60_000L, RenderSettle.ceilingMs(PerfTier.FULL))
        assertEquals(90_000L, RenderSettle.ceilingMs(PerfTier.MINIMAL))
    }
}

class V158ProbeTimeoutGuardTest {

    @Test
    fun wedgedProbeDegradesToNullPromptly() = runBlocking {
        val started = System.currentTimeMillis()
        val answer = RenderProbeGuard.guarded<String>(timeoutMs = 150) {
            suspendCancellableCoroutine { /* never resumes */ }
        }
        val elapsed = System.currentTimeMillis() - started
        assertNull(answer)
        assertTrue("guard took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun answeringProbeReturnsItsValue() = runBlocking {
        val fast = RenderProbeGuard.guarded(timeoutMs = 2_000) { "sample" }
        assertEquals("sample", fast)
        val slow = RenderProbeGuard.guarded(timeoutMs = 2_000) {
            delay(50)
            "late sample"
        }
        assertEquals("late sample", slow)
        // The production default is a small multiple of the
        // poll interval.
        assertEquals(
            RenderSettle.POLL_INTERVAL_MS * 3,
            RenderProbeGuard.PROBE_TIMEOUT_MS
        )
    }
}
