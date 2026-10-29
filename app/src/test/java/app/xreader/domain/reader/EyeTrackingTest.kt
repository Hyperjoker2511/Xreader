package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeTrackingTest {

    // -- Controller: fails closed -------------------------------------------

    @Test
    fun happyPathReachesActive() {
        val controller = EyeTrackingController()
        assertEquals(EyeState.OFF, controller.state)
        controller.toggle(true)
        assertEquals(EyeState.WAITING_PERMISSION, controller.state)
        controller.onPermissionResult(true)
        assertEquals(EyeState.STARTING, controller.state)
        controller.onDetectorReady()
        assertEquals(EyeState.ACTIVE, controller.state)
        controller.toggle(false)
        assertEquals(EyeState.OFF, controller.state)
    }

    @Test
    fun permissionDeniedFailsClosedWithPlainMessage() {
        val controller = EyeTrackingController()
        controller.toggle(true)
        controller.onPermissionResult(false)
        assertEquals(EyeState.PERMISSION_DENIED, controller.state)
        assertTrue(controller.message!!.contains("camera"))
        assertTrue(!controller.isOn)
    }

    @Test
    fun unavailableDetectorFailsClosed() {
        val controller = EyeTrackingController()
        controller.toggle(true)
        controller.onPermissionResult(true)
        controller.onUnavailable("This device has no front camera.")
        assertEquals(EyeState.UNAVAILABLE, controller.state)
        assertEquals("This device has no front camera.", controller.message)
        assertTrue(!controller.isOn)
    }

    @Test
    fun midFlightFailureFailsClosedAndCanRetry() {
        val controller = EyeTrackingController()
        controller.toggle(true)
        controller.onPermissionResult(true)
        controller.onDetectorReady()
        controller.onFailure("The camera stopped responding.")
        assertEquals(EyeState.FAILED, controller.state)
        controller.toggle(true) // retry is allowed from FAILED
        assertEquals(EyeState.WAITING_PERMISSION, controller.state)
    }

    @Test
    fun strayEventsCannotActivate() {
        val controller = EyeTrackingController()
        controller.onPermissionResult(true) // never asked
        assertEquals(EyeState.OFF, controller.state)
        controller.onDetectorReady()
        assertEquals(EyeState.OFF, controller.state)
    }

    // -- Detector: gestures ---------------------------------------------------

    private fun blink(detector: EyeTurnDetector, startMs: Long, durationMs: Long = 200L) {
        detector.onEvent(FaceEvent.Eyes(0.05f, 0.05f, startMs))
        detector.onEvent(FaceEvent.Eyes(0.95f, 0.95f, startMs + durationMs))
    }

    @Test
    fun singleBlinkDoesNotTurn() {
        val detector = EyeTurnDetector()
        blink(detector, 0L)
        assertNull(detector.onEvent(FaceEvent.Eyes(0.95f, 0.95f, 5_000L)))
    }

    @Test
    fun doubleBlinkTurnsOnceThenCooldownSwallows() {
        val detector = EyeTurnDetector()
        blink(detector, 0L) // first blink ends at 200
        detector.onEvent(FaceEvent.Eyes(0.05f, 0.05f, 600L))
        val command = detector.onEvent(FaceEvent.Eyes(0.95f, 0.95f, 800L))
        assertEquals(TurnCommand.NEXT, command)
        // Immediate further blinks are inside the cooldown.
        blink(detector, 1_000L)
        assertNull(detector.onEvent(FaceEvent.Eyes(0.95f, 0.95f, 1_400L)))
    }

    @Test
    fun slowBlinksDoNotCountAsDouble() {
        val detector = EyeTurnDetector()
        blink(detector, 0L)
        blink(detector, 3_000L) // > 1.4 s later: a fresh first blink
        assertNull(detector.onEvent(FaceEvent.Eyes(0.95f, 0.95f, 3_400L)))
    }

    @Test
    fun longEyeClosureIsNotABlink() {
        val detector = EyeTurnDetector()
        detector.onEvent(FaceEvent.Eyes(0.05f, 0.05f, 0L))
        detector.onEvent(FaceEvent.Eyes(0.95f, 0.95f, 900L)) // 900 ms shut
        blink(detector, 1_200L)
        // Only ONE valid blink followed the long closure: no turn.
        assertNull(detector.onEvent(FaceEvent.Eyes(0.95f, 0.95f, 2_000L)))
    }

    @Test
    fun nodTurns() {
        val detector = EyeTurnDetector()
        assertNull(detector.onEvent(FaceEvent.Pitch(22f, 0L)))
        assertEquals(
            TurnCommand.NEXT,
            detector.onEvent(FaceEvent.Pitch(2f, 700L))
        )
    }

    @Test
    fun slowHeadLoweringIsNotANodButDwellFires() {
        val detector = EyeTurnDetector()
        detector.onEvent(FaceEvent.Pitch(22f, 0L))
        // Still down at 1.6 s: the look-down dwell fires instead.
        assertEquals(
            TurnCommand.NEXT,
            detector.onEvent(FaceEvent.Pitch(24f, 1_700L))
        )
    }

    @Test
    fun missingEyeProbabilitiesAreIgnored() {
        val detector = EyeTurnDetector()
        assertNull(detector.onEvent(FaceEvent.Eyes(null, null, 0L)))
        assertNull(detector.onEvent(FaceEvent.Eyes(0.05f, null, 100L)))
    }
}
