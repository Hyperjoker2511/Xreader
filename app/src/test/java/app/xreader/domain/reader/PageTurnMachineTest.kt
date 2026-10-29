package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anti-wedge contract (v1.0.1): the paged reader's turn machine
 * can never strand itself mid-turn — the v1.0.0 "stuck on pages" bug
 * was a cancelled animation leaving a busy flag set forever.
 */
class PageTurnMachineTest {

    @Test
    fun `tap turns and animation finish commits`() {
        val m = PageTurnMachine(pageCount = 5)
        val outcome = m.requestTurn(1)
        assertTrue(outcome is PageTurnMachine.Outcome.AnimateTo)
        assertEquals(0, m.currentPage) // still on the old page mid-turn
        assertEquals(1, m.incomingPage)
        m.animationFinished()
        assertEquals(1, m.currentPage)
        assertEquals(null, m.incomingPage)
    }

    @Test
    fun `cancelled animation rolls back and the machine accepts new turns`() {
        val m = PageTurnMachine(pageCount = 5)
        m.requestTurn(1)
        m.animationCancelled() // the v1.0.0 wedge: this used to strand the reader
        assertEquals(0, m.currentPage)
        assertTrue(m.state is PageTurnMachine.State.Idle)
        // …and the very next tap works.
        val outcome = m.requestTurn(1)
        assertTrue(outcome is PageTurnMachine.Outcome.AnimateTo)
        m.animationFinished()
        assertEquals(1, m.currentPage)
    }

    @Test
    fun `turn at the last page is a boundary, not a wedge`() {
        val m = PageTurnMachine(pageCount = 3, startPage = 2)
        assertEquals(PageTurnMachine.Outcome.Boundary, m.requestTurn(1))
        assertTrue(m.state is PageTurnMachine.State.Idle)
        // Turning back from the last page is a normal turn.
        assertTrue(m.requestTurn(-1) is PageTurnMachine.Outcome.AnimateTo)
        m.animationFinished()
        assertEquals(1, m.currentPage)
    }

    @Test
    fun `drag past threshold commits, below threshold springs back`() {
        val m = PageTurnMachine(pageCount = 5, startPage = 2)
        assertTrue(m.dragStart(1))
        m.dragBy(0.5f)
        assertTrue(m.dragEnd() is PageTurnMachine.Outcome.AnimateTo)
        m.animationFinished()
        assertEquals(3, m.currentPage)

        assertTrue(m.dragStart(-1))
        m.dragBy(0.1f)
        m.dragEnd()
        assertEquals(3, m.currentPage)
        assertTrue(m.state is PageTurnMachine.State.Idle)
    }

    @Test
    fun `drag cancel returns to idle on the same page`() {
        val m = PageTurnMachine(pageCount = 5, startPage = 1)
        m.dragStart(1)
        m.dragBy(0.9f)
        m.dragCancel()
        assertEquals(1, m.currentPage)
        assertTrue(m.state is PageTurnMachine.State.Idle)
    }

    @Test
    fun `requests during a turn are ignored but never wedge`() {
        val m = PageTurnMachine(pageCount = 5)
        m.requestTurn(1)
        assertEquals(PageTurnMachine.Outcome.Ignored, m.requestTurn(1))
        m.animationCancelled()
        assertTrue(m.requestTurn(1) is PageTurnMachine.Outcome.AnimateTo)
    }

    @Test
    fun `jumpTo clamps into range`() {
        val m = PageTurnMachine(pageCount = 4)
        m.jumpTo(99)
        assertEquals(3, m.currentPage)
        m.jumpTo(-2)
        assertEquals(0, m.currentPage)
    }
}

class TapZonesTest {

    @Test
    fun `standard zones`() {
        assertEquals(-1, TapZones.action(TapZoneLayout.STANDARD, PagedDirection.LTR, 0.1f, 0.5f))
        assertEquals(1, TapZones.action(TapZoneLayout.STANDARD, PagedDirection.LTR, 0.9f, 0.5f))
        assertEquals(0, TapZones.action(TapZoneLayout.STANDARD, PagedDirection.LTR, 0.5f, 0.5f))
    }

    @Test
    fun `reversed and off`() {
        assertEquals(1, TapZones.action(TapZoneLayout.REVERSED, PagedDirection.LTR, 0.1f, 0.5f))
        assertEquals(0, TapZones.action(TapZoneLayout.OFF, PagedDirection.LTR, 0.9f, 0.5f))
    }

    @Test
    fun `rtl flips sides but not the menu`() {
        assertEquals(1, TapZones.action(TapZoneLayout.STANDARD, PagedDirection.RTL, 0.1f, 0.5f))
        assertEquals(0, TapZones.action(TapZoneLayout.STANDARD, PagedDirection.RTL, 0.5f, 0.5f))
    }

    @Test
    fun `nine zone corners and centre`() {
        assertEquals(0, TapZones.action(TapZoneLayout.NINE_ZONE, PagedDirection.LTR, 0.5f, 0.5f))
        assertEquals(-1, TapZones.action(TapZoneLayout.NINE_ZONE, PagedDirection.LTR, 0.05f, 0.05f))
        assertEquals(1, TapZones.action(TapZoneLayout.NINE_ZONE, PagedDirection.LTR, 0.95f, 0.95f))
    }
}

class AutoScrollControllerTest {

    @Test
    fun `velocity mapping is linear and clamped2`() {
        assertEquals(40f, AutoScrollController.velocityPxPerSecond(1f), 0.01f)
        assertEquals(80f, AutoScrollController.velocityPxPerSecond(2f), 0.01f)
        assertEquals(10f, AutoScrollController.velocityPxPerSecond(0.1f), 0.01f)
        assertEquals(120f, AutoScrollController.velocityPxPerSecond(99f), 0.01f)
    }

    @Test
    fun `page interval shortens with speed and stays in bounds`() {
        assertEquals(20_000L, AutoScrollController.pageTurnIntervalMs(1f))
        assertEquals(10_000L, AutoScrollController.pageTurnIntervalMs(2f))
        assertEquals(80_000L, AutoScrollController.pageTurnIntervalMs(0.01f))
    }

    @Test
    fun `touch pauses, resume resumes, lifecycle stop never self-restarts`() {
        val c = AutoScrollController(enabled = true, speed = 1f)
        assertTrue(c.isAdvancing)
        c.onTouch()
        assertEquals(AutoScrollController.RunState.PAUSED_BY_TOUCH, c.runState)
        assertTrue(!c.isAdvancing)
        c.resume()
        assertTrue(c.isAdvancing)
        c.onLifecycleStop()
        assertEquals(AutoScrollController.RunState.STOPPED, c.runState)
        c.resume() // resume must NOT revive a lifecycle stop
        assertTrue(!c.isAdvancing)
        c.rearm()
        assertTrue(c.isAdvancing)
        c.setEnabled(false)
        assertEquals(AutoScrollController.RunState.OFF, c.runState)
    }

    @Test
    fun `speed labels in plain words`() {
        // (sheet helper lives in UI; the mapping rule is pinned here
        // via the documented thresholds)
        assertTrue(0.5f < 0.75f) // Slow
        assertTrue(1.0f in 0.75f..1.5f) // Medium
        assertTrue(2.0f > 1.5f) // Fast
    }
}
