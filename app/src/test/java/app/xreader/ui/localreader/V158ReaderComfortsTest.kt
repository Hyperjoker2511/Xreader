package app.xreader.ui.localreader

import android.content.pm.ActivityInfo
import android.view.KeyEvent
import app.xreader.ui.reader.VolumeKeyTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (G8): the shared comforts — brightness-swipe mapping,
 * volume-key routing through the tree's EXISTING registry
 * (VolumeKeyTurn, fed by MainActivity.dispatchKeyEvent), and
 * the orientation mapping. The host registers/unregisters the
 * handler exactly as the novel reader does; with no handler
 * registered (the setting off), volume keys keep their normal
 * behaviour.
 */
class V158ReaderComfortsTest {

    @Test
    fun `brightness swipe maps drag distance to window brightness`() {
        // Dragging up half the strip from 0.5 → full brightness.
        assertEquals(1f, brightnessForDrag(0.5f, -500f, 1000f), 0.001f)
        // Dragging down a quarter → 0.25.
        assertEquals(0.25f, brightnessForDrag(0.5f, 250f, 1000f), 0.001f)
        // Clamped: never fully black, never above 1.
        assertEquals(0.01f, brightnessForDrag(0.2f, 900f, 1000f), 0.001f)
        assertEquals(1f, brightnessForDrag(0.9f, -900f, 1000f), 0.001f)
        // A zero-height surface changes nothing.
        assertEquals(0.4f, brightnessForDrag(0.4f, -100f, 0f), 0.001f)
    }

    @Test
    fun `volume keys route to page turns only while a handler is registered`() {
        VolumeKeyTurn.handler = null
        // Setting OFF: no handler — the key is NOT consumed.
        assertFalse(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, isDown = true))
        assertFalse(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_UP, isDown = true))

        val turns = mutableListOf<Int>()
        VolumeKeyTurn.handler = { direction -> turns += direction }
        try {
            // Setting ON: down = next (+1), up = previous (−1).
            assertTrue(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, isDown = true))
            assertTrue(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_UP, isDown = true))
            assertEquals(listOf(1, -1), turns)
            // Key-up events and other keys are not consumed.
            assertFalse(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, isDown = false))
            assertFalse(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_POWER, isDown = true))
            assertEquals(listOf(1, -1), turns)
        } finally {
            VolumeKeyTurn.handler = null
        }
    }

    @Test
    fun `orientation lock maps to the host's requested orientation`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientationConstantFor(ReaderOrientation.FREE)
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            orientationConstantFor(ReaderOrientation.PORTRAIT)
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            orientationConstantFor(ReaderOrientation.LANDSCAPE)
        )
    }
}
