package app.xreader.ui.localreader

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5.10 (D2, build-map Group D): B-2's inset is applied at
 * TERMINAL values only. The pure producer-side reducer is
 * driven over show/hide cycles with intermediate height
 * streams (the chrome's AnimatedVisibility measurements):
 * the applied sequence is exactly {0, settled} in some
 * order — never an intermediate height. Plus the wiring
 * census: EpubBody's inset comes from the reducer's state
 * only. B-2's own tests (V159EpubBottomInsetTest) pin the
 * preserved guarantee and re-run alongside.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V1510EpubInsetTerminalTest {

    /** Feeds [events] through the reducer from the initial
     * state, returning the applied inset after EVERY event. */
    private fun appliedSequence(
        events: List<EpubInsetEvent>
    ): List<Int> {
        var state = EpubInsetState()
        val applied = mutableListOf(state.appliedInsetPx)
        for (event in events) {
            state = epubInsetReducer(state, event)
            applied += state.appliedInsetPx
        }
        return applied
    }

    @Test
    fun `show cycle applies only the settled height, never intermediates`() {
        val applied = appliedSequence(
            listOf(
                EpubInsetEvent.VisibilityChanged(true),
                EpubInsetEvent.HeightMeasured(36),
                EpubInsetEvent.HeightMeasured(88),
                EpubInsetEvent.HeightMeasured(141),
                EpubInsetEvent.HeightMeasured(184),
                EpubInsetEvent.ChromeSettled
            )
        )
        assertEquals(
            "every pre-settle frame applies 0; the settle applies 184",
            listOf(0, 0, 0, 0, 0, 0, 184), applied
        )
    }

    @Test
    fun `hide applies zero immediately, mid-stream`() {
        val applied = appliedSequence(
            listOf(
                EpubInsetEvent.VisibilityChanged(true),
                EpubInsetEvent.HeightMeasured(184),
                EpubInsetEvent.ChromeSettled,
                EpubInsetEvent.VisibilityChanged(false),
                EpubInsetEvent.HeightMeasured(120),
                EpubInsetEvent.HeightMeasured(40)
            )
        )
        assertEquals(listOf(0, 0, 0, 184, 0, 0, 0), applied)
    }

    @Test
    fun `full show-hide-show cycle yields exactly zero and settled values`() {
        val events = listOf(
            EpubInsetEvent.VisibilityChanged(true),
            EpubInsetEvent.HeightMeasured(50),
            EpubInsetEvent.HeightMeasured(190),
            EpubInsetEvent.ChromeSettled,
            EpubInsetEvent.VisibilityChanged(false),
            EpubInsetEvent.HeightMeasured(0),
            EpubInsetEvent.VisibilityChanged(true),
            EpubInsetEvent.HeightMeasured(75),
            EpubInsetEvent.HeightMeasured(190),
            EpubInsetEvent.ChromeSettled
        )
        val applied = appliedSequence(events)
        assertEquals(
            listOf(0, 0, 0, 0, 190, 0, 0, 0, 0, 0, 190), applied
        )
        assertTrue(
            "the applied set over the whole cycle is exactly {0, settled}",
            applied.toSet() == setOf(0, 190)
        )
    }

    @Test
    fun `settle before any measurement then measurement applies at once`() {
        // First composition with chrome already visible: the
        // transition rests at Visible before onSizeChanged
        // reports — the post-settle measurement is terminal.
        val applied = appliedSequence(
            listOf(
                EpubInsetEvent.VisibilityChanged(true),
                EpubInsetEvent.ChromeSettled,
                EpubInsetEvent.HeightMeasured(184)
            )
        )
        assertEquals(listOf(0, 0, 0, 184), applied)
    }

    @Test
    fun `post-settle remeasurement applies, negative heights clamp`() {
        val applied = appliedSequence(
            listOf(
                EpubInsetEvent.VisibilityChanged(true),
                EpubInsetEvent.HeightMeasured(184),
                EpubInsetEvent.ChromeSettled,
                EpubInsetEvent.HeightMeasured(220),
                EpubInsetEvent.HeightMeasured(-8)
            )
        )
        assertEquals(listOf(0, 0, 0, 184, 220, 0), applied)
    }

    @Test
    fun `epub body inset comes from the reducer state only`() {
        val source = sourceFile("EpubBookReader.kt").readText()
        assertTrue(
            "EpubBody receives the reducer's applied output",
            source.contains("bottomInsetPx = insetState.appliedInsetPx")
        )
        assertTrue(
            "the reducer is the producer's state transition",
            source.contains("epubInsetReducer(insetState, event)")
        )
        assertTrue(
            "height measurements are dispatched as reducer events",
            source.contains("EpubInsetEvent.HeightMeasured(height)")
        )
        assertTrue(
            "the settle is dispatched as a reducer event",
            source.contains("EpubInsetEvent.ChromeSettled")
        )
        assertFalse(
            "no raw measured-height state feeds the inset any more",
            source.contains("var bottomChromeHeightPx")
        )
    }

    private fun sourceFile(name: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(
                dir,
                "app/src/main/java/app/xreader/ui/localreader/$name"
            )
            if (candidate.isFile) return candidate
            val direct = File(dir, "src/main/java/app/xreader/ui/localreader/$name")
            if (direct.isFile) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate $name from ${System.getProperty("user.dir")}")
    }
}
