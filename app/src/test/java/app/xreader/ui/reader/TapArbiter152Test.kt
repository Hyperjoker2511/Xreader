package app.xreader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.2 (FIX-4, §6.1 / §10 item 7): the manga tap arbiter's
 * classification rules, driven exactly as the reader's
 * Initial-pass adapter drives them — with the page consumer
 * (tap-zone handler) attached, a double tap must fire zoom
 * ONLY, never also a tap-zone action.
 */
class TapArbiter152Test {

    private fun arbiter() = TapArbiter152(
        slopPx = 24f,
        doubleTapTimeoutMs = 300L,
        longPressTimeoutMs = 500L
    )

    /** A recording pair of consumers, as wired in the reader. */
    private class Consumers {
        val singles = mutableListOf<Pair<Float, Float>>()
        val doubles = mutableListOf<Pair<Float, Float>>()
        fun dispatch(actions: List<TapArbiter152.Action>) {
            for (action in actions) {
                when (action) {
                    is TapArbiter152.Action.SingleTap ->
                        singles += action.x to action.y
                    is TapArbiter152.Action.DoubleTap ->
                        doubles += action.x to action.y
                }
            }
        }
    }

    @Test
    fun `single tap fires once, only after the double-tap window closes`() {
        val arbiter = arbiter()
        val consumers = Consumers()
        arbiter.onDown(100f, 200f, 1_000L, pressedCount = 1)
        val feed = arbiter.onUp(100f, 200f, 1_060L)
        assertTrue("a classified tap's up is consumed", feed.consumeUp)
        consumers.dispatch(feed.actions)
        assertTrue("nothing fires before the window closes", consumers.singles.isEmpty())
        assertTrue(consumers.doubles.isEmpty())
        // The window closes at firstUp + 300.
        consumers.dispatch(arbiter.onTimeout(1_359L))
        assertTrue(consumers.singles.isEmpty())
        consumers.dispatch(arbiter.onTimeout(1_360L))
        assertEquals(listOf(100f to 200f), consumers.singles)
        assertTrue(consumers.doubles.isEmpty())
        // And never twice.
        consumers.dispatch(arbiter.onTimeout(2_000L))
        assertEquals(1, consumers.singles.size)
    }

    @Test
    fun `double tap fires zoom only - the tap-zone action never fires`() {
        val arbiter = arbiter()
        val consumers = Consumers()
        arbiter.onDown(400f, 300f, 0L, pressedCount = 1)
        consumers.dispatch(arbiter.onUp(400f, 300f, 60L).actions)
        arbiter.onDown(405f, 302f, 200L, pressedCount = 1)
        val feed = arbiter.onUp(405f, 302f, 260L)
        assertTrue(feed.consumeUp)
        consumers.dispatch(feed.actions)
        assertEquals(listOf(405f to 302f), consumers.doubles)
        // The pending single is dead: no tap-zone action, ever.
        consumers.dispatch(arbiter.onTimeout(10_000L))
        assertTrue("double tap must not also fire a tap-zone action", consumers.singles.isEmpty())
    }

    @Test
    fun `second tap at the exact window edge still counts as double`() {
        val arbiter = arbiter()
        val consumers = Consumers()
        arbiter.onDown(100f, 100f, 0L, pressedCount = 1)
        arbiter.onUp(100f, 100f, 50L)
        // firstUp = 50, window = 300 → a down at 350 is inside.
        arbiter.onDown(100f, 100f, 350L, pressedCount = 1)
        consumers.dispatch(arbiter.onUp(100f, 100f, 400L).actions)
        assertEquals(1, consumers.doubles.size)
        assertTrue(consumers.singles.isEmpty())
    }

    @Test
    fun `a far second tap flushes the pending single and starts fresh`() {
        val arbiter = arbiter()
        val consumers = Consumers()
        arbiter.onDown(10f, 10f, 0L, pressedCount = 1)
        arbiter.onUp(10f, 10f, 50L)
        // Second press far away (other tap zone): the first tap
        // resolves as a single immediately.
        val flushed = arbiter.onDown(900f, 10f, 120L, pressedCount = 1)
        consumers.dispatch(flushed)
        assertEquals(listOf(10f to 10f), consumers.singles)
        val feed = arbiter.onUp(900f, 10f, 180L)
        consumers.dispatch(feed.actions)
        consumers.dispatch(arbiter.onTimeout(480L))
        assertEquals(
            listOf(10f to 10f, 900f to 10f), consumers.singles
        )
        assertTrue(consumers.doubles.isEmpty())
    }

    @Test
    fun `a drag is not a tap`() {
        val arbiter = arbiter()
        val consumers = Consumers()
        arbiter.onDown(100f, 100f, 0L, pressedCount = 1)
        arbiter.onMove(100f, 220f) // 120 px > slop → scroll
        val feed = arbiter.onUp(100f, 220f, 120L)
        assertFalse(feed.consumeUp)
        consumers.dispatch(feed.actions)
        consumers.dispatch(arbiter.onTimeout(10_000L))
        assertTrue(consumers.singles.isEmpty())
        assertTrue(consumers.doubles.isEmpty())
    }

    @Test
    fun `a long press is not a tap`() {
        val arbiter = arbiter()
        val consumers = Consumers()
        arbiter.onDown(100f, 100f, 0L, pressedCount = 1)
        val feed = arbiter.onUp(100f, 100f, 600L) // ≥ long-press 500
        assertFalse(feed.consumeUp)
        consumers.dispatch(feed.actions)
        consumers.dispatch(arbiter.onTimeout(10_000L))
        assertTrue(consumers.singles.isEmpty())
    }

    @Test
    fun `multi-touch cancels classification (pinch is never a tap)`() {
        val arbiter = arbiter()
        val consumers = Consumers()
        arbiter.onDown(100f, 100f, 0L, pressedCount = 1)
        arbiter.onUp(100f, 100f, 50L) // pending single
        arbiter.onDown(300f, 300f, 100L, pressedCount = 2) // pinch starts
        consumers.dispatch(arbiter.onTimeout(10_000L))
        assertTrue(consumers.singles.isEmpty())
        assertTrue(consumers.doubles.isEmpty())
    }

    @Test
    fun `a second gesture that drags cancels both taps`() {
        val arbiter = arbiter()
        val consumers = Consumers()
        arbiter.onDown(100f, 100f, 0L, pressedCount = 1)
        arbiter.onUp(100f, 100f, 50L)
        arbiter.onDown(102f, 100f, 150L, pressedCount = 1)
        arbiter.onMove(102f, 300f) // becomes a scroll
        val feed = arbiter.onUp(102f, 300f, 300L)
        consumers.dispatch(feed.actions)
        consumers.dispatch(arbiter.onTimeout(10_000L))
        assertTrue(consumers.singles.isEmpty())
        assertTrue(consumers.doubles.isEmpty())
    }
}
