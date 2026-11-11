package app.xreader.data.extension

import app.xreader.core.error.CrashGuard
import app.xreader.data.prefs.PerfTier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
 *
 * v1.5.10 (A2 containment): the two collectors below sit on a
 * supervisor scope carrying CrashGuard's containment handler, and
 * each emission is applied defensively — a flow that throws (a
 * stored value that fails to decode upstream, a mapping that NPEs
 * on a legacy row) is reported as a CONTAINED failure through
 * [containedFailureSink] and can neither kill the process nor
 * cancel its sibling collector. Production's sink (wired in
 * `AppContainer`) writes CrashGuard's contained note + a
 * DiagnosticLog line with the top frames; the default sink here
 * writes the DiagnosticLog line only (this class has no filesDir).
 */
class RenderRuntime(
    masterEnabledFlow: Flow<Boolean>,
    tierFlow: Flow<PerfTier>,
    private val containedFailureSink: (site: String, throwable: Throwable) -> Unit =
        { site, throwable ->
            DiagnosticLog.record(
                tag = "contained",
                message = "Contained failure at $site: " +
                    "${throwable.javaClass.name} — the app kept running.",
                detail = CrashGuard.framesText(throwable)
            )
        }
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

    private fun reportContained(site: String, throwable: Throwable) {
        if (throwable is CancellationException) return
        runCatching { containedFailureSink(site, throwable) }
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CrashGuard.containmentHandler(SITE_SCOPE) { site, _, throwable ->
                reportContained(site, throwable)
            }
    )

    init {
        scope.launch {
            masterEnabledFlow
                .catch { error -> reportContained(SITE_MASTER, error) }
                .collect { value ->
                    runCatching { masterEnabledNow = value }
                        .onFailure { reportContained(SITE_MASTER, it) }
                }
        }
        scope.launch {
            tierFlow
                .catch { error -> reportContained(SITE_TIER, error) }
                .collect { value ->
                    runCatching { tierNow = value }
                        .onFailure { reportContained(SITE_TIER, it) }
                }
        }
    }

    val currentTier: PerfTier get() = tierNow

    val inBackgroundWork: Boolean get() = backgroundDepth.get() > 0

    fun gate(): RenderGate = RenderGate(
        masterEnabled = { masterEnabledNow },
        tier = { tierNow },
        userDriven = { appInForeground },
        backgroundWork = { inBackgroundWork }
    )

    companion object {
        /** Containment site labels (A2 census): the scope backstop
         * and each collector, named in contained-failure notes. */
        const val SITE_SCOPE = "render-runtime"
        const val SITE_MASTER = "render-runtime:master-enabled"
        const val SITE_TIER = "render-runtime:tier"
    }

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
