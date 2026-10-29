package app.xreader.domain.reader

/** Bundled ambient loops for focus sessions (PLAN §6). */
enum class Soundscape { NONE, RAIN, FIREPLACE, FOREST }

enum class FocusState { IDLE, RUNNING, PAUSED, DONE }

/**
 * The focus (Pomodoro) engine (PLAN §6): 25/50/custom-minute sessions
 * with pause/resume, an uninterrupted-completion flag (Phase 6's EXP
 * focus bonus consumes exactly this), and a chosen soundscape. Pure —
 * the injected clock drives everything; the UI ticks it.
 */
class FocusTimer(private val clock: () -> Long) {

    var state: FocusState = FocusState.IDLE
        private set
    var durationMinutes: Int = DEFAULT_MINUTES
        private set
    var soundscape: Soundscape = Soundscape.NONE
        private set
    /** True only when a session ran to DONE without ever pausing. */
    var completedUninterrupted: Boolean = false
        private set

    private var accumulatedMs: Long = 0L
    private var runningSinceMs: Long? = null
    private var everPaused: Boolean = false

    fun start(minutes: Int, soundscape: Soundscape = Soundscape.NONE) {
        durationMinutes = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        this.soundscape = soundscape
        accumulatedMs = 0L
        runningSinceMs = clock()
        everPaused = false
        completedUninterrupted = false
        state = FocusState.RUNNING
    }

    fun pause() {
        if (state != FocusState.RUNNING) return
        accumulatedMs = elapsedMs(clock())
        runningSinceMs = null
        everPaused = true
        state = FocusState.PAUSED
    }

    fun resume() {
        if (state != FocusState.PAUSED) return
        runningSinceMs = clock()
        state = FocusState.RUNNING
    }

    /** Ends the session early; returns focused minutes so far. */
    fun stop(): Int {
        val minutes = (elapsedMs(clock()) / 60_000L).toInt()
        state = FocusState.IDLE
        runningSinceMs = null
        accumulatedMs = 0L
        return minutes
    }

    fun elapsedMs(now: Long = clock()): Long {
        val running = runningSinceMs?.let { now - it } ?: 0L
        return accumulatedMs + maxOf(0L, running)
    }

    fun remainingMs(now: Long = clock()): Long =
        maxOf(0L, durationMinutes * 60_000L - elapsedMs(now))

    /** 0..1 of the session elapsed. */
    fun fraction(now: Long = clock()): Float {
        val total = durationMinutes * 60_000L
        if (total <= 0L) return 0f
        return (elapsedMs(now).toFloat() / total).coerceIn(0f, 1f)
    }

    /**
     * Advances the machine; returns true exactly once — the tick on
     * which a RUNNING session reaches its duration (state → DONE,
     * [completedUninterrupted] settled).
     */
    fun tick(now: Long = clock()): Boolean {
        if (state != FocusState.RUNNING) return false
        if (elapsedMs(now) < durationMinutes * 60_000L) return false
        accumulatedMs = durationMinutes * 60_000L
        runningSinceMs = null
        completedUninterrupted = !everPaused
        state = FocusState.DONE
        return true
    }

    fun acknowledgeDone() {
        if (state == FocusState.DONE) {
            state = FocusState.IDLE
            accumulatedMs = 0L
        }
    }

    companion object {
        const val DEFAULT_MINUTES = 25
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 240
        val PRESETS = listOf(25, 50)
    }
}
