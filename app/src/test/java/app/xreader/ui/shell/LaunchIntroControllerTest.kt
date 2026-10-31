package app.xreader.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2.0 (WS6): pins the launch-intro contract — the animated
 * launcher-mark overlay that plays over cold-start WITHOUT ever
 * delaying first content. Each test fails if the corresponding
 * controller logic is removed:
 *  - fresh cold start shows;
 *  - once per process (no replay on rotation / second composition);
 *  - dismisses the moment the Library-ready signal fires;
 *  - force-dismisses at the hard cap when ready never fires
 *    (fake clock — time is injected into the controller);
 *  - never shows under reduced motion;
 *  - never flashes when the Library beat the intro to screen.
 */
class LaunchIntroControllerTest {

    @Test
    fun `shows on a fresh cold start`() {
        var now = 0L
        val controller = LaunchIntroController(nowMillis = { now })
        assertTrue(
            controller.shouldShow(alreadyShown = false, reducedMotion = false)
        )
        assertTrue(
            controller.begin(alreadyShown = false, reducedMotion = false)
        )
        assertTrue(controller.isShowing)
        assertEquals(LaunchIntroController.Phase.SHOWING, controller.phase)
    }

    @Test
    fun `does not show when this process already showed it`() {
        var now = 0L
        val controller = LaunchIntroController(nowMillis = { now })
        assertFalse(
            controller.shouldShow(alreadyShown = true, reducedMotion = false)
        )
        assertFalse(
            controller.begin(alreadyShown = true, reducedMotion = false)
        )
        assertFalse(controller.isShowing)
    }

    @Test
    fun `process gate claims exactly once`() {
        // The process-lifetime once-flag (rotation creates a new
        // Activity but the same process, so the gate must already
        // be claimed when the intro is composed again).
        val gate = LaunchIntroOnceGate()
        assertFalse(gate.hasClaimed())
        assertTrue(gate.tryClaim())
        assertTrue(gate.hasClaimed())
        assertFalse(gate.tryClaim())
        assertFalse(gate.tryClaim())
    }

    @Test
    fun `does not replay after dismissal - a second begin is refused`() {
        var now = 0L
        val controller = LaunchIntroController(nowMillis = { now })
        assertTrue(
            controller.begin(alreadyShown = false, reducedMotion = false)
        )
        assertTrue(controller.onLibraryReady())
        assertFalse(controller.isShowing)
        // Second composition / rotation in the same process.
        assertFalse(
            controller.begin(alreadyShown = true, reducedMotion = false)
        )
        assertFalse(controller.isShowing)
        assertEquals(LaunchIntroController.Phase.DONE, controller.phase)
    }

    @Test
    fun `dismisses when the library ready signal fires`() {
        var now = 0L
        val controller = LaunchIntroController(nowMillis = { now })
        assertTrue(
            controller.begin(alreadyShown = false, reducedMotion = false)
        )
        now = 120L // well inside the cap — dismissal is the signal's doing
        assertTrue(controller.onLibraryReady())
        assertFalse(controller.isShowing)
        // A late second signal is a no-op, never a resurrection.
        assertFalse(controller.onLibraryReady())
        assertFalse(controller.isShowing)
    }

    @Test
    fun `force-dismisses at the cap when ready never fires`() {
        var now = 5_000L
        val controller = LaunchIntroController(nowMillis = { now })
        assertTrue(
            controller.begin(alreadyShown = false, reducedMotion = false)
        )
        now = 5_000L + LaunchIntroController.CAP_MILLIS - 1L
        assertFalse("one ms before the cap it must still be showing",
            controller.onTick())
        assertTrue(controller.isShowing)
        now = 5_000L + LaunchIntroController.CAP_MILLIS
        assertTrue("at the cap the tick force-dismisses", controller.onTick())
        assertFalse(controller.isShowing)
        // Further ticks stay dismissed.
        now += 10_000L
        assertFalse(controller.onTick())
        assertFalse(controller.isShowing)
    }

    @Test
    fun `cap is at most one second`() {
        assertTrue(LaunchIntroController.CAP_MILLIS <= 1_000L)
        assertEquals(1_000L, LaunchIntroController.CAP_MILLIS)
    }

    @Test
    fun `never shows under reduced motion`() {
        var now = 0L
        val controller = LaunchIntroController(nowMillis = { now })
        assertFalse(
            controller.shouldShow(alreadyShown = false, reducedMotion = true)
        )
        assertFalse(
            controller.begin(alreadyShown = false, reducedMotion = true)
        )
        assertFalse(controller.isShowing)
        // Reduced motion arriving mid-show drops it immediately.
        val midShow = LaunchIntroController(nowMillis = { now })
        assertTrue(
            midShow.begin(alreadyShown = false, reducedMotion = false)
        )
        assertTrue(midShow.dismiss())
        assertFalse(midShow.isShowing)
    }

    @Test
    fun `does not flash when the library is already ready`() {
        var now = 0L
        val controller = LaunchIntroController(nowMillis = { now })
        assertFalse(
            controller.shouldShow(
                alreadyShown = false, reducedMotion = false, libraryReady = true
            )
        )
        assertFalse(
            controller.begin(
                alreadyShown = false, reducedMotion = false, libraryReady = true
            )
        )
        assertFalse(controller.isShowing)
    }

    @Test
    fun `ready before the cap wins - later ticks never resurrect or re-dismiss`() {
        var now = 0L
        val controller = LaunchIntroController(nowMillis = { now })
        assertTrue(
            controller.begin(alreadyShown = false, reducedMotion = false)
        )
        now = 300L
        assertTrue(controller.onLibraryReady())
        now = 300L + LaunchIntroController.CAP_MILLIS + 1L
        assertFalse(controller.onTick())
        assertFalse(controller.isShowing)
        assertEquals(LaunchIntroController.Phase.DONE, controller.phase)
    }
}
