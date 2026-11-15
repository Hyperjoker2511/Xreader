package app.xreader.data.reader

import app.xreader.data.prefs.PerfTier
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPrefetchDepthTest {

    @Test
    fun `depth follows the performance tier`() {
        assertEquals(1, TtsPrefetchDepth.forTier(PerfTier.MINIMAL))
        assertEquals(2, TtsPrefetchDepth.forTier(PerfTier.STANDARD))
        assertEquals(3, TtsPrefetchDepth.forTier(PerfTier.FULL))
    }
}
