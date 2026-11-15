package app.xreader.data.reader

import app.xreader.data.prefs.PerfTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPrefetchDepthTest {

    @Test
    fun `depth follows the performance tier`() {
        assertEquals(1, TtsPrefetchDepth.forTier(PerfTier.MINIMAL))
        assertEquals(2, TtsPrefetchDepth.forTier(PerfTier.STANDARD))
        assertEquals(3, TtsPrefetchDepth.forTier(PerfTier.FULL))
    }

    /** 2 GB perf pass (F5): the chapter-ahead fill is offered
     * on every tier except MINIMAL. */
    @Test
    fun `chapter ahead follows the performance tier`() {
        assertFalse(TtsChapterAhead.forTier(PerfTier.MINIMAL))
        assertTrue(TtsChapterAhead.forTier(PerfTier.STANDARD))
        assertTrue(TtsChapterAhead.forTier(PerfTier.FULL))
    }
}
