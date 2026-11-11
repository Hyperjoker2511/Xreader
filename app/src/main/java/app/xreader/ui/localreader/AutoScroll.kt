package app.xreader.ui.localreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v1.5.8 (G9 + H7): ONE auto-scroll engine for both local
 * readers. Moon+ lists five rolling variants; they collapse to
 * speed choices here (DECIDED — the capability is the parity
 * item, not the variant count).
 *
 * The engine is deliberately surface-blind: a [Surface] receives
 * one advance command per tick; returning false means "the book
 * is finished" and the engine stops itself. Surfaces:
 *  - EPUB scroll mode: timed small locator steps (progression
 *    increments within the current resource — the same `go`
 *    road search/bookmark jumps already travel, so a scroll-mode
 *    view moves to each step);
 *  - EPUB paginated mode: a timer over page-advance;
 *  - PDF (H7): page-advance in paged mode, smooth scroll steps
 *    in continuous mode — same engine, same speed setting.
 *
 * Tick/rate/stop logic lives here and nowhere else; the test
 * drives it against a fake surface on a virtual clock.
 */
class AutoScrollEngine {

    /** One advanceable reading surface. [advance] returns false
     * when there is nowhere further to go (end of book). */
    fun interface Surface {
        suspend fun advance(): Boolean
    }

    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /**
     * Starts emitting advance commands to [surface] every
     * [intervalMs] (already speed-adjusted by the caller via
     * [intervalFor]). Stops on [stop], on end-of-book, or when
     * the owning scope dies.
     */
    fun start(
        scope: CoroutineScope,
        surface: Surface,
        intervalMs: Long
    ) {
        stop()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs.coerceAtLeast(16L))
                if (!isActive) break
                val moved = runCatching { surface.advance() }.getOrDefault(false)
                if (!moved) break
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** EPUB paginated / PDF paged: one page per interval. */
        const val PAGE_INTERVAL_MS = 4_000L

        /** EPUB scroll / PDF continuous: one small step per
         * interval — small steps at a short interval read as a
         * smooth roll. v1.5.9 (F-7b): retuned 100 ms → 120 ms
         * (with the larger progression step below, the same
         * rate shape at ~17% fewer navigations). */
        const val STEP_INTERVAL_MS = 120L

        /** The interval for [baseMs] at [speed] (1.0 = base). */
        fun intervalFor(baseMs: Long, speed: Double): Long =
            (baseMs / speed.coerceIn(0.25, 4.0)).toLong().coerceAtLeast(16L)

        /** The progression step one scroll-mode tick advances
         * within the current resource at [speed]. v1.5.9
         * (F-7b): 0.0015 → 0.002 at speed 1.0 — fewer, larger
         * steps, the same overall rate. */
        fun progressionStep(speed: Double): Double =
            0.002 * speed.coerceIn(0.25, 4.0)
    }
}

/**
 * The start/stop state a reader's quick row binds to: wraps an
 * [AutoScrollEngine] with the surface the ACTIVE body registers
 * (the EPUB fragment's owner registers the locator-step or
 * page-advance surface; the PDF bodies register theirs). A body
 * that unregisters mid-run stops the engine — auto-scroll never
 * outlives the surface it drives.
 */
class AutoScrollController {
    val engine = AutoScrollEngine()

    @Volatile var surface: AutoScrollEngine.Surface? = null
        private set

    @Volatile var intervalMs: Long = AutoScrollEngine.STEP_INTERVAL_MS
        private set

    @Volatile private var scope: CoroutineScope? = null

    /** Registers the ACTIVE body's surface. If the engine was
     * running (a mode/speed change re-registers), it restarts
     * on the new surface so the change is live. */
    fun register(
        scope: CoroutineScope,
        surface: AutoScrollEngine.Surface,
        intervalMs: Long
    ) {
        val wasRunning = engine.running
        this.scope = scope
        this.surface = surface
        this.intervalMs = intervalMs
        if (wasRunning) engine.start(scope, surface, intervalMs)
    }

    fun unregister(surface: AutoScrollEngine.Surface) {
        if (this.surface === surface) {
            this.surface = null
            engine.stop()
        }
    }

    fun start() {
        val current = surface ?: return
        val owner = scope ?: return
        engine.start(owner, current, intervalMs)
    }

    fun stop() = engine.stop()
}
