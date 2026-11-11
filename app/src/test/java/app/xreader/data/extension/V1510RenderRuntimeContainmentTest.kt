package app.xreader.data.extension

import app.xreader.data.prefs.PerfTier
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.10 (A2 verification ii): RenderRuntime's two flow collectors
 * driven with fakes — one collector's flow throws (the throwing
 * mapping / legacy-row shape), and the sibling collector keeps
 * applying values; the failure lands in the contained-failure sink
 * with its site label, and values emitted before the throw were
 * applied (skip-and-continue at the seam, never a process death).
 */
class V1510RenderRuntimeContainmentTest {

    private data class Recorded(val site: String, val error: Throwable)

    private fun handBuiltNpe(): NullPointerException {
        val t = NullPointerException("row-secret")
        t.stackTrace = arrayOf(
            StackTraceElement("com.example.rows.LegacyRow", "map", "LegacyRow.kt", 77777)
        )
        return t
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue("condition not met within ${timeoutMs}ms", cond())
    }

    @Test
    fun `throwing master flow is contained and the tier sibling keeps applying`() {
        val recorded = CopyOnWriteArrayList<Recorded>()
        // Emits one good value, then the mapping throws — the
        // legacy-row collector shape from Finding 7's R1.
        val masterFlow: Flow<Boolean> = flow {
            emit(false)
            throw handBuiltNpe()
        }
        val tierFlow = MutableStateFlow(PerfTier.FULL)
        val runtime = RenderRuntime(
            masterEnabledFlow = masterFlow,
            tierFlow = tierFlow,
            containedFailureSink = { site, error -> recorded += Recorded(site, error) }
        )

        // The good emission landed before the throw.
        awaitTrue { !runtime.gate().masterEnabled() }
        // The failure was contained and labelled with its site.
        awaitTrue { recorded.size == 1 }
        assertEquals(RenderRuntime.SITE_MASTER, recorded[0].site)
        assertTrue(recorded[0].error is NullPointerException)

        // The sibling collector is alive: a later tier emission
        // still applies.
        tierFlow.value = PerfTier.MINIMAL
        awaitTrue { runtime.currentTier == PerfTier.MINIMAL }
    }

    @Test
    fun `throwing tier flow is contained and the master sibling keeps applying`() {
        val recorded = CopyOnWriteArrayList<Recorded>()
        val masterFlow = MutableStateFlow(true)
        val tierFlow: Flow<PerfTier> = flow {
            emit(PerfTier.STANDARD)
            delay(50)
            throw handBuiltNpe()
        }
        val runtime = RenderRuntime(
            masterEnabledFlow = masterFlow,
            tierFlow = tierFlow,
            containedFailureSink = { site, error -> recorded += Recorded(site, error) }
        )

        awaitTrue { runtime.currentTier == PerfTier.STANDARD }
        awaitTrue { recorded.size == 1 }
        assertEquals(RenderRuntime.SITE_TIER, recorded[0].site)

        masterFlow.value = false
        awaitTrue { !runtime.gate().masterEnabled() }
    }

    @Test
    fun `healthy flows apply with no contained failures`() {
        val recorded = CopyOnWriteArrayList<Recorded>()
        val runtime = RenderRuntime(
            masterEnabledFlow = MutableStateFlow(false),
            tierFlow = MutableStateFlow(PerfTier.MINIMAL),
            containedFailureSink = { site, error -> recorded += Recorded(site, error) }
        )
        awaitTrue { !runtime.gate().masterEnabled() && runtime.currentTier == PerfTier.MINIMAL }
        Thread.sleep(200)
        assertTrue(recorded.isEmpty())
    }
}
