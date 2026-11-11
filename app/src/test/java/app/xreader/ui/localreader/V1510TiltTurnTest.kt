package app.xreader.ui.localreader

import app.xreader.fixtures.FakePreferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.10 (F6) gate: tilt-to-turn — §6 Q2 → A: built, OFF by
 * default, Moon's reference constants (26° trigger, 10°
 * hysteresis, 950 ms cooldown). The math and the controller's
 * fire-once-per-excursion contract are pinned here; the
 * sensor registration lifecycle is pinned by census (it is
 * device behaviour, not JVM behaviour).
 */
class V1510TiltTurnTest {

    @Test
    fun `reference constants are the plan numbers`() {
        assertEquals(26f, TiltTurnMath.TRIGGER_DEGREES)
        assertEquals(10f, TiltTurnMath.NEUTRAL_HYSTERESIS_DEGREES)
        assertEquals(950L, TiltTurnMath.COOLDOWN_MS)
    }

    @Test
    fun `delta converts acceleration to degrees`() {
        // One g along Y ≈ 90° from flat.
        assertEquals(90f, TiltTurnMath.deltaDegrees(9.81f, 0f), 0.01f)
        assertEquals(0f, TiltTurnMath.deltaDegrees(2f, 2f), 0.001f)
        assertEquals(-45f, TiltTurnMath.deltaDegrees(0f, 4.905f), 0.01f)
    }

    @Test
    fun `evaluate fires past trigger when armed and cooled`() {
        val (firedNext, direction) = TiltTurnMath.evaluate(
            deltaDegrees = 30f, armed = true,
            lastTriggerMs = 0L, nowMs = 5_000L
        )
        assertTrue(firedNext.not())
        assertEquals(1, direction)
        val (firedPrev, directionPrev) = TiltTurnMath.evaluate(
            deltaDegrees = -30f, armed = true,
            lastTriggerMs = 0L, nowMs = 5_000L
        )
        assertTrue(firedPrev.not())
        assertEquals(-1, directionPrev)
    }

    @Test
    fun `evaluate respects cooldown and hysteresis`() {
        // Inside the cooldown: no fire.
        assertEquals(
            true to 0,
            TiltTurnMath.evaluate(30f, true, lastTriggerMs = 1_000L, nowMs = 1_500L)
        )
        // Below the trigger: no fire, stays armed.
        assertEquals(
            true to 0,
            TiltTurnMath.evaluate(20f, true, lastTriggerMs = 0L, nowMs = 9_000L)
        )
        // Disarmed until the device returns inside the band.
        assertEquals(
            false to 0,
            TiltTurnMath.evaluate(20f, false, lastTriggerMs = 0L, nowMs = 9_000L)
        )
        assertEquals(
            true to 0,
            TiltTurnMath.evaluate(5f, false, lastTriggerMs = 0L, nowMs = 9_000L)
        )
    }

    @Test
    fun `controller fires once per excursion`() {
        val controller = TiltTurnController()
        // First sample = baseline (device held at rest tilt,
        // Y ≈ 5 m/s² in the reading pose).
        assertEquals(0, controller.onSample(5f, nowMs = 0L))
        // Tilt forward: Y 8 → ≈ +27.5° past the 26° trigger.
        assertEquals(1, controller.onSample(8f, nowMs = 100L))
        // Held there: disarmed, no refire.
        assertEquals(0, controller.onSample(8f, nowMs = 300L))
        // Back inside the neutral band: re-arms silently.
        assertEquals(0, controller.onSample(5.2f, nowMs = 500L))
        // Tilt back past −26° after the cooldown: previous.
        assertEquals(-1, controller.onSample(2f, nowMs = 1_200L))
    }

    @Test
    fun `tilt is off by default in the store`() = runTest {
        val store = ReaderBarStore(FakePreferencesDataStore())
        assertFalse(store.tiltEnabled.first())
        store.saveTiltEnabled(true)
        assertTrue(store.tiltEnabled.first())
    }

    @Test
    fun `sensor lifecycle is registered and unregistered`() {
        val file = listOf(
            File("src/main/java/app/xreader/ui/localreader/TiltTurn.kt"),
            File("app/src/main/java/app/xreader/ui/localreader/TiltTurn.kt")
        ).first { it.exists() }
        val text = file.readText()
        assertTrue(text.contains("registerListener"))
        assertTrue(text.contains("unregisterListener"))
        assertTrue(text.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(text.contains("Lifecycle.Event.ON_PAUSE"))
        assertTrue(text.contains("SENSOR_DELAY_UI"))
    }
}
