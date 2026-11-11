package app.xreader.core.error

import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * v1.5.11 (ANR forensics — build-logs/V1511-RESEARCH-ANR-FORENSICS.md
 * §1.5, §3): the in-process stall watchdog, ANR-WatchDog pattern.
 * An ANR throws nothing, so CrashGuard's exception handler can
 * never see one; this is the layer that captures the main
 * thread's stack AT the moment of a stall, on every device —
 * and the ONLY layer Android 9 (API 28) has, where
 * ApplicationExitInfo does not exist.
 *
 * Shape: a dedicated daemon thread heartbeats the main looper —
 * it posts a tick [Runnable] to the main thread, then sleeps
 * [TIMEOUT_MS] in [STEP_MS] steps (stepped so a stop request is
 * honoured promptly and the thread never parks in one long
 * sleep). A tick that has not run after the timeout is a FIRST
 * MISS, never a conviction: the dog may itself have been starved
 * by CPU saturation or a stop-the-world GC pause, which looks
 * identical to a blocked main. Conviction needs a SECOND sample
 * [RESAMPLE_MS] later with the tick STILL unrun (two-sample
 * conviction). Only then is a note recorded — through
 * [CrashGuard]'s stall entry point — carrying both main-thread
 * samples (the pair separates a hard block from a composition
 * spin; research §2) plus every other thread's state and top
 * frames, so a lock holder is convicted in the same note.
 *
 * Cost while healthy: one posted runnable per timeout cycle on
 * main, and stepped sleeps on the dog's own thread — nothing on
 * any hot path. Silence while a debugger is attached (the
 * canonical setIgnoreDebugger default): a paused main under a
 * debugger is not a stall.
 *
 * The loop's seams (poster, main thread, recorder, debugger
 * probe, clock) are constructor-injected so the conviction logic
 * is JVM-drivable; [start] wires the production Android shell.
 */
class StallWatchdog(
    private val postToMain: (Runnable) -> Unit,
    private val mainThread: Thread,
    private val recorder: (StallCapture) -> Unit,
    private val isDebuggerAttached: () -> Boolean = { false },
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val timeoutMs: Long = TIMEOUT_MS,
    private val resampleMs: Long = RESAMPLE_MS,
    private val stepMs: Long = STEP_MS
) {

    /** One convicted stall, in exactly the shape
     * [CrashGuard.formatStallNote] records: frames are
     * class + method only (the store's privacy class). */
    data class StallCapture(
        val blockedMs: Long,
        val mainThreadName: String,
        val mainState: String,
        val mainSample1: List<String>,
        val mainSample2: List<String>,
        val otherThreads: List<String>
    )

    /** Incremented BY the tick runnable, ON the main thread;
     * the dog only ever reads it. */
    @Volatile
    private var tick: Long = 0L

    private val tickRunnable = Runnable { tick++ }

    @Volatile
    private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            val postedTick = tick
            val postedAt = clock()
            postToMain(tickRunnable)
            sleepStepped(timeoutMs)
            if (!running) break
            if (tick != postedTick) continue // healthy cycle
            // FIRST MISS — a suspicion, not a conviction. Take
            // sample 1 and give the main thread the resample
            // window to prove the dog was merely starved.
            val sample1 = mainFrames()
            val state1 = mainThread.state
            sleepStepped(resampleMs)
            if (!running) break
            if (tick != postedTick) continue // recovered: no conviction
            // SECOND MISS with the tick still unrun: convicted.
            val sample2 = mainFrames()
            if (!isDebuggerAttached()) {
                runCatching {
                    recorder(
                        StallCapture(
                            blockedMs = clock() - postedAt,
                            mainThreadName = mainThread.name,
                            mainState = state1.name,
                            mainSample1 = sample1,
                            mainSample2 = sample2,
                            otherThreads = otherThreadLines()
                        )
                    )
                }
            }
            // One note per stall EPISODE: wait for the main
            // thread to answer a tick again before re-arming, so
            // a 25 s freeze (the MangaFire proof leg) yields one
            // note, not five.
            waitForRecovery(postedTick)
        }
    }

    /** Sleep [totalMs] in [stepMs] steps, returning early when
     * stopped or interrupted. */
    private fun sleepStepped(totalMs: Long) {
        var remaining = totalMs
        while (running && remaining > 0) {
            val step = minOf(stepMs, remaining)
            try {
                Thread.sleep(step)
            } catch (e: InterruptedException) {
                return
            }
            remaining -= step
        }
    }

    /** Park (stepped) until the main thread runs a tick again. */
    private fun waitForRecovery(postedTick: Long) {
        while (running && tick == postedTick) {
            try {
                Thread.sleep(stepMs)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /** The main thread's full stack as class + method lines. */
    private fun mainFrames(): List<String> =
        mainThread.stackTrace.map { "${it.className}.${it.methodName}" }

    /** Every OTHER thread (main and the dog excluded): name,
     * state, and its top [OTHER_THREAD_FRAMES] frames as
     * class + method — enough to name a lock holder without
     * bloating the note. */
    private fun otherThreadLines(): List<String> {
        val traces = runCatching { Thread.getAllStackTraces() }
            .getOrDefault(emptyMap())
        return traces.entries
            .filter { (t, _) -> t !== mainThread && t.name != THREAD_NAME }
            .sortedBy { (t, _) -> t.name }
            .map { (t, frames) ->
                val top = frames.take(OTHER_THREAD_FRAMES)
                    .joinToString(", ") { "${it.className}.${it.methodName}" }
                if (top.isEmpty()) {
                    "${t.name} [${t.state}]"
                } else {
                    "${t.name} [${t.state}]: $top"
                }
            }
    }

    companion object {
        const val THREAD_NAME = "stall-watchdog"

        /** The system's own input-dispatch ANR threshold
         * (research §1.5) — a shorter fuse would convict stalls
         * the system itself tolerates. */
        const val TIMEOUT_MS = 5_000L

        /** The sample-pair spacing (research §2: ~1 s apart). */
        const val RESAMPLE_MS = 1_000L

        /** Stepped-sleep granularity of the heartbeat loop. */
        const val STEP_MS = 250L

        /** Top frames recorded per non-main thread. */
        const val OTHER_THREAD_FRAMES = 3

        @Volatile
        private var instance: StallWatchdog? = null

        /** Production wiring, installed once from
         * [app.xreader.XreaderApp.onCreate]: ticks ride the
         * main looper's Handler, convictions land in
         * [CrashGuard]'s store. Idempotent — a second call is
         * a no-op. */
        fun start(context: Context) {
            if (instance != null) return
            val appContext = context.applicationContext
            val mainLooper = Looper.getMainLooper()
            val handler = Handler(mainLooper)
            val watchdog = StallWatchdog(
                postToMain = { runnable -> handler.post(runnable) },
                mainThread = mainLooper.thread,
                recorder = { capture ->
                    CrashGuard.recordStall(
                        context = appContext,
                        blockedMs = capture.blockedMs,
                        mainThreadName = capture.mainThreadName,
                        mainState = capture.mainState,
                        mainSample1 = capture.mainSample1,
                        mainSample2 = capture.mainSample2,
                        otherThreads = capture.otherThreads
                    )
                },
                isDebuggerAttached = {
                    Debug.isDebuggerConnected() || Debug.waitingForDebugger()
                }
            )
            instance = watchdog
            watchdog.start()
        }
    }
}
