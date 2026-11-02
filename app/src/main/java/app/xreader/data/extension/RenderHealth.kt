package app.xreader.data.extension

/**
 * v1.5.1 (items 2 + 5): the render tier's per-source health
 * memory — the containment the v1.5.0 field freeze demanded.
 *
 * One hidden-WebView render at a time already holds (the
 * fetcher's own mutex); what was missing was any memory
 * ACROSS fetches: a source whose render never finished
 * (BrightNovel's catalogue) was re-rendered from scratch on
 * every open, its timed-out page kept loading in the shared
 * view, and repeated opens piled render attempts onto a
 * 6 GB phone until the whole app wedged. The rules here:
 *
 *  - ONE failed render puts the source on a strike: for
 *    [COOLDOWN_MS] its fetches use the WebView fetch bridge
 *    (the saved session, the site's own network stack)
 *    instead of re-rendering (item 5's one-strike rule).
 *  - Attempts are capped per source per window
 *    ([MAX_ATTEMPTS_PER_WINDOW] in [ATTEMPT_WINDOW_MS]) so
 *    even successful-but-slow renders can't pile up when a
 *    user hammers a source.
 *  - A successful render clears the strike.
 *
 * In-memory by design: a fresh process owes each source one
 * honest render attempt; the strike governs the session
 * that actually suffered the failure. Pure and clock-fed so
 * the whole table is unit-testable.
 */
object RenderHealth {

    /** How long a struck source stays on the bridge road. */
    const val COOLDOWN_MS: Long = 5 * 60_000L

    /** The attempt-cap window. */
    const val ATTEMPT_WINDOW_MS: Long = 10 * 60_000L

    /** Render attempts allowed per source inside the window. */
    const val MAX_ATTEMPTS_PER_WINDOW: Int = 3

    private class SourceState {
        var lastFailureAtMs: Long = 0L
        val attemptTimesMs = ArrayDeque<Long>()
    }

    private val states = HashMap<String, SourceState>()

    private fun state(key: String): SourceState =
        states.getOrPut(key) { SourceState() }

    private fun prune(state: SourceState, nowMs: Long) {
        while (true) {
            val first = state.attemptTimesMs.firstOrNull() ?: return
            if (nowMs - first < ATTEMPT_WINDOW_MS) return
            state.attemptTimesMs.removeFirst()
        }
    }

    /** May this source render right now? False while a
     * failure strike is inside its cooldown, or while the
     * attempt cap for the window is spent. */
    @Synchronized
    fun renderAllowed(
        key: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val state = states[key] ?: return true
        if (state.lastFailureAtMs > 0L &&
            nowMs - state.lastFailureAtMs < COOLDOWN_MS
        ) {
            return false
        }
        prune(state, nowMs)
        return state.attemptTimesMs.size < MAX_ATTEMPTS_PER_WINDOW
    }

    /** Record one render attempt (success or failure). */
    @Synchronized
    fun noteAttempt(
        key: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val state = state(key)
        prune(state, nowMs)
        state.attemptTimesMs.addLast(nowMs)
    }

    /** A render failed: the strike starts now. */
    @Synchronized
    fun recordFailure(
        key: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        state(key).lastFailureAtMs = nowMs
    }

    /** A render answered: the strike clears. */
    @Synchronized
    fun recordSuccess(key: String) {
        states[key]?.lastFailureAtMs = 0L
    }

    /** Test seam: forget everything. */
    @Synchronized
    fun reset() {
        states.clear()
    }
}
