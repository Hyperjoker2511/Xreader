package app.xreader.ui.localreader

import android.view.KeyEvent
import app.xreader.data.engine.PdfPageFilter
import app.xreader.ui.reader.VolumeKeyTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.9 (Finding 3 §8.6): per-control tap → effect, at state
 * level (the V158EpubChromeShapeTest handler-identity pattern).
 *
 *  - U-2's moved actions keep their v1.5.8 handlers (identity).
 *  - The Night chip ≡ the sheet's filter row: both controls
 *    perform the SAME settings transform ([withFilter]), which
 *    is the single transform both call sites invoke.
 *  - Auto-scroll register/unregister pairing on the shared
 *    controller the PDF bodies use.
 *  - Volume keys: ±1 emission through the production
 *    [VolumeKeyTurn] registry with a handler registered (the
 *    per-book pref on — the comfort host registers exactly
 *    then), silence with none (pref off).
 */
class V159PdfChromeHandlersTest {

    // ── U-2 handler identity ─────────────────────────────────

    @Test
    fun `moved quick-row actions keep their v158 handlers`() {
        val bookmarkHere: () -> Unit = {}
        val bookmarks: () -> Unit = {}
        val textView: () -> Unit = {}
        val display: () -> Unit = {}
        val actions = pdfQuickActions(
            PdfChromeHandlers(bookmarkHere, bookmarks, textView, display)
        )
        assertEquals(
            listOf("bookmarkHere", "bookmarks", "textView", "display"),
            actions.map { it.id }
        )
        assertEquals(
            listOf("Bookmark", "Bookmarks", "Text view", "Page display"),
            actions.map { it.label }
        )
        assertSame(bookmarkHere, actions[0].onClick)
        assertSame(bookmarks, actions[1].onClick)
        assertSame(textView, actions[2].onClick)
        assertSame(display, actions[3].onClick)
    }

    // ── Night chip ≡ sheet filter row ────────────────────────

    @Test
    fun `night chip and sheet filter row perform the one filter transform`() {
        val base = PdfDocSettings(
            zoom = 1.5f,
            scrollModel = PdfScrollModel.CONTINUOUS,
            filter = PdfPageFilter.NONE,
            fitMode = PdfFitMode.FIT_WIDTH,
            spread = true,
            volumeKeyTurn = true,
            keepScreenOn = true,
            autoScrollSpeed = 2.0
        )
        // The bottom chip's exact transform chain.
        val chipResult = base.withFilter(base.filter.toggledNight())
        // The sheet chip for "Invert B&W" performs withFilter too.
        val sheetResult = base.withFilter(PdfPageFilter.INVERT_BLACK_AND_WHITE)
        assertEquals(sheetResult, chipResult)
        // Only the filter field moved — the transform the two
        // controls share touches nothing else.
        assertEquals(base.copy(filter = PdfPageFilter.INVERT_BLACK_AND_WHITE), chipResult)
        // And back: from the night preset the chip lands on NONE,
        // the same pref the sheet's "As printed" chip writes.
        val backAgain = chipResult.withFilter(chipResult.filter.toggledNight())
        assertEquals(base.withFilter(PdfPageFilter.NONE), backAgain)
    }

    // ── Auto-scroll register/unregister pairing ──────────────

    @Test
    fun `auto-scroll register and unregister pair by surface identity`() {
        val controller = AutoScrollController()
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val surface = AutoScrollEngine.Surface { true }
            val other = AutoScrollEngine.Surface { true }
            controller.register(scope, surface, intervalMs = 60_000)
            assertSame(surface, controller.surface)
            // Unregistering a DIFFERENT surface must not detach
            // the active one (the pairing is by identity).
            controller.unregister(other)
            assertSame(surface, controller.surface)
            controller.start()
            assertTrue(controller.engine.running)
            // The owning body's dispose unregisters ITS surface:
            // the surface detaches and the engine stops — the
            // run never outlives the surface it drives.
            controller.unregister(surface)
            assertNull(controller.surface)
            assertFalse(controller.engine.running)
            // Starting with nothing registered is a no-op.
            controller.start()
            assertFalse(controller.engine.running)
        } finally {
            scope.cancel()
        }
    }

    // ── Volume keys: ±1 with the pref on, silence with it off ─

    @Test
    fun `volume keys emit plus-minus one only while a handler is registered`() {
        val previous = VolumeKeyTurn.handler
        try {
            // Pref OFF: the comfort host registers nothing —
            // keys are not consumed and nothing is emitted.
            VolumeKeyTurn.handler = null
            assertFalse(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, true))
            assertFalse(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_UP, true))

            // Pref ON: the host's registration forwards to the
            // view model's turn handler.
            val emitted = mutableListOf<Int>()
            VolumeKeyTurn.handler = { direction -> emitted.add(direction) }
            assertTrue(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, true))
            assertTrue(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_UP, true))
            assertEquals(listOf(1, -1), emitted)
            // Key-up events and unrelated keys emit nothing.
            assertFalse(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_VOLUME_DOWN, false))
            assertFalse(VolumeKeyTurn.onKey(KeyEvent.KEYCODE_BACK, true))
            assertEquals(listOf(1, -1), emitted)
        } finally {
            VolumeKeyTurn.handler = previous
        }
    }
}
