package app.xreader.data.extension

import app.xreader.data.prefs.PerfTier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The live inputs [RenderPolicy] reads through, bundled as providers
 * so fetch sites never touch prefs or lifecycle state directly.
 */
data class RenderGate(
    val masterEnabled: () -> Boolean,
    val tier: () -> PerfTier,
    val userDriven: () -> Boolean,
    val backgroundWork: () -> Boolean
)

/**
 * v1.0.6 (W2/W5): app-lifetime holder for the browser-view runtime
 * state — the master switch and performance tier (mirrored from
 * prefs flows), whether a person is looking at the app (fed by the
 * activity lifecycle in `AppContainer`), and whether the caller is
 * background work (workers and the heal sweep wrap their runs in
 * [asBackgroundWork]).
 *
 * Pure JVM by design: the Android wiring lives in `AppContainer`,
 * so tests construct this with plain flows.
 */
class RenderRuntime(
    masterEnabledFlow: Flow<Boolean>,
    tierFlow: Flow<PerfTier>
) {

    /** W2.4: the Customise master switch; ON until prefs say otherwise. */
    @Volatile
    private var masterEnabledNow: Boolean = true

    @Volatile
    private var tierNow: PerfTier = PerfTier.FULL

    /** True while any activity is started — set by AppContainer's
     * lifecycle callbacks. Drives both the MINIMAL tap-only leash
     * (W5.1) and the browser-source download gate (Q4). */
    @Volatile
    var appInForeground: Boolean = false

    private val backgroundDepth = AtomicInteger(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            masterEnabledFlow.collect { masterEnabledNow = it }
        }
        scope.launch { tierFlow.collect { tierNow = it } }
    }

    val currentTier: PerfTier get() = tierNow

    val inBackgroundWork: Boolean get() = backgroundDepth.get() > 0

    fun gate(): RenderGate = RenderGate(
        masterEnabled = { masterEnabledNow },
        tier = { tierNow },
        userDriven = { appInForeground },
        backgroundWork = { inBackgroundWork }
    )

    /** Marks the wrapped block as background work: while it runs,
     * [RenderPolicy] answers static-only for every fetch (W5.2). */
    suspend fun <T> asBackgroundWork(block: suspend () -> T): T {
        backgroundDepth.incrementAndGet()
        return try {
            block()
        } finally {
            backgroundDepth.decrementAndGet()
        }
    }
}
