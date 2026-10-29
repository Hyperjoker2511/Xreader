package app.xreader.domain.reader

import app.xreader.domain.content.MediaClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlaySyncTest {

    private val sync = OverlaySync(
        listOf(
            MediaClip("p3", beginSec = 20.0, endSec = 30.0),
            MediaClip("p1", beginSec = 0.0, endSec = 9.5),
            MediaClip("p2", beginSec = 9.5, endSec = 18.0)
        )
    )

    @Test
    fun clipsAreSortedAndDurationIsLastEnd() {
        assertEquals(listOf("p1", "p2", "p3"), sync.clips.map { it.textId })
        assertEquals(30.0, sync.durationSec, 0.001)
    }

    @Test
    fun activeClipFollowsTheTimingModel() {
        assertEquals("p1", sync.textIdAt(0.0))
        assertEquals("p1", sync.textIdAt(9.4))
        assertEquals("p2", sync.textIdAt(9.5))
        assertEquals("p2", sync.textIdAt(17.9))
        // Gap 18.0–20.0: the previous clip stays lit (read-aloud rule).
        assertEquals("p2", sync.textIdAt(19.0))
        assertEquals("p3", sync.textIdAt(20.0))
        assertEquals("p3", sync.textIdAt(500.0)) // past the end: last clip
    }

    @Test
    fun beforeFirstClipLightsNothing() {
        val late = OverlaySync(listOf(MediaClip("x", 5.0, 8.0)))
        assertEquals(-1, late.clipIndexAt(2.0))
        assertNull(late.textIdAt(2.0))
        assertEquals("x", late.textIdAt(5.0))
    }

    @Test
    fun skipTargetsWalkTheClipList() {
        assertEquals("p2", sync.nextClip(3.0)?.textId)
        assertEquals("p3", sync.nextClip(12.0)?.textId)
        assertNull(sync.nextClip(25.0))
        assertEquals("p1", sync.previousClip(12.0)?.textId)
        assertNull(sync.previousClip(3.0))
    }

    @Test
    fun clipLookupByTextId() {
        assertEquals(9.5, sync.clipForTextId("p2")?.beginSec ?: -1.0, 0.001)
        assertNull(sync.clipForTextId("missing"))
    }

    @Test
    fun emptyOverlayIsInert() {
        val empty = OverlaySync(emptyList())
        assertEquals(-1, empty.clipIndexAt(10.0))
        assertNull(empty.textIdAt(10.0))
        assertEquals(0.0, empty.durationSec, 0.001)
    }
}
