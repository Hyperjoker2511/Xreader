package app.xreader.data.engine

import android.app.Application
import app.xreader.data.prefs.PerfTier
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (sift A-4 AMENDED): budgets by tier via
 * `PerformancePolicy.tierForRam` — memory FULL 48 / STANDARD
 * 48 / MINIMAL 20 MB (not the flat 48 everywhere, not 64);
 * the preview ring and the 96 MB disk cap are tier-invariant.
 *
 * v1.5.10 (H1, plan + build map): the dwell-prefetch window
 * widened BY DESIGN — full-res +6/−1 on FULL/STANDARD, +2/−1
 * on MINIMAL (budgets, eviction order and the preview ring
 * unchanged). The window assertions below assert the H1
 * values; re-derived at the v1.5.10 gate under I6b with this
 * citation (the v1.5.9 +2/+1 values were the stale part).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V159PdfTierBudgetsTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `memory budgets and prefetch windows follow the tier`() {
        assertEquals(48L * 1024 * 1024, PdfSession.memoryCapBytesFor(PerfTier.FULL))
        assertEquals(48L * 1024 * 1024, PdfSession.memoryCapBytesFor(PerfTier.STANDARD))
        assertEquals(20L * 1024 * 1024, PdfSession.memoryCapBytesFor(PerfTier.MINIMAL))
        assertEquals(6 to 1, PdfSession.prefetchWindowFor(PerfTier.FULL))
        assertEquals(6 to 1, PdfSession.prefetchWindowFor(PerfTier.STANDARD))
        assertEquals(2 to 1, PdfSession.prefetchWindowFor(PerfTier.MINIMAL))
        assertEquals(4, PdfSession.PREVIEW_RING_FORWARD)
        assertEquals(2, PdfSession.PREVIEW_RING_BACKWARD)
        assertEquals(96L * 1024 * 1024, PdfSession.CACHE_CAP_BYTES)
    }

    @Test
    fun `a MINIMAL session exposes the MINIMAL window, a FULL session the full one`() {
        val minimalProvider = V159PdfSessionFixtures.CountingProvider()
        val minimal = V159PdfSessionFixtures.openSession(
            temp.root, 216, minimalProvider, tier = PerfTier.MINIMAL
        )
        try {
            assertEquals(2, minimal.prefetchForward)
            assertEquals(1, minimal.prefetchBackward)
        } finally {
            minimal.close()
        }
        val fullProvider = V159PdfSessionFixtures.CountingProvider()
        val full = V159PdfSessionFixtures.openSession(
            temp.root, 217, fullProvider, tier = PerfTier.FULL
        )
        try {
            assertEquals(6, full.prefetchForward)
            assertEquals(1, full.prefetchBackward)
        } finally {
            full.close()
        }
    }
}
