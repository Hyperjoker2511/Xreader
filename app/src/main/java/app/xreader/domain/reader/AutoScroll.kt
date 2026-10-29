package app.xreader.domain.reader

/**
 * Auto-scroll / auto page-turn driver logic (v1.0.1) — pure and
 * unit-tested; the composables only feed it ticks and touches.
 *
 * Behaviour contract (from the user's ask):
 *  - scroll mode: the page drifts at a steady speed;
 *  - paged mode: a page turns every [pageTurnIntervalMs];
 *  - ANY touch pauses it; the same on-screen control resumes it;
 *  - it stops dead on chapter change, exit, or backgrounding —
 *    those are just [onLifecycleStop] from the UI, and a stopped
 *    driver never restarts itself.
 */
class AutoScrollController(
    enabled: Boolean = false,
    var speed: Float = 1f
) {
    var enabled: Boolean = enabled
        private set
    enum class RunState { OFF, RUNNING, PAUSED_BY_TOUCH, STOPPED }

    var runState: RunState = if (enabled) RunState.RUNNING else RunState.OFF
        private set

    /** The reader sheet's switch / the reader menu's play-pause. */
    fun setEnabled(on: Boolean) {
        enabled = on
        runState = if (on) RunState.RUNNING else RunState.OFF
    }

    /** Any touch on the reading surface pauses (never toggles off —
     * the user's setting survives; only motion pauses). */
    fun onTouch() {
        if (runState == RunState.RUNNING) runState = RunState.PAUSED_BY_TOUCH
    }

    /** The same control the user paused with resumes. */
    fun resume() {
        if (runState == RunState.PAUSED_BY_TOUCH) runState = RunState.RUNNING
    }

    fun togglePauseResume() {
        when (runState) {
            RunState.RUNNING -> runState = RunState.PAUSED_BY_TOUCH
            RunState.PAUSED_BY_TOUCH -> runState = RunState.RUNNING
            else -> Unit
        }
    }

    /** Chapter change / reader exit / app background: full stop.
     * Re-entering the reader re-arms from [enabled] via [rearm]. */
    fun onLifecycleStop() {
        if (runState == RunState.RUNNING || runState == RunState.PAUSED_BY_TOUCH) {
            runState = RunState.STOPPED
        }
    }

    fun rearm() {
        if (runState == RunState.STOPPED) {
            runState = if (enabled) RunState.RUNNING else RunState.OFF
        }
    }

    val isAdvancing: Boolean get() = runState == RunState.RUNNING

    companion object {
        /** Scroll velocity in px/s for the speed multiplier: a calm
         * 40 px/s at 1× (roughly one line every second and a half at
         * body size), scaling linearly. */
        fun velocityPxPerSecond(speed: Float): Float =
            40f * speed.coerceIn(
                ReaderValidation.AUTO_SCROLL_SPEED_RANGE.start,
                ReaderValidation.AUTO_SCROLL_SPEED_RANGE.endInclusive
            )

        /** Paged mode: seconds per page at 1× — a steady 20 s page,
         * faster/slower with the same speed slider. */
        fun pageTurnIntervalMs(speed: Float): Long {
            val s = speed.coerceIn(
                ReaderValidation.AUTO_SCROLL_SPEED_RANGE.start,
                ReaderValidation.AUTO_SCROLL_SPEED_RANGE.endInclusive
            )
            return (20_000f / s).toLong().coerceIn(3_000L, 80_000L)
        }
    }
}
