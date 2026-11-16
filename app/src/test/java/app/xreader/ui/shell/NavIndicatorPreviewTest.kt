package app.xreader.ui.shell

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import app.xreader.data.library.NavBarStyle
import app.xreader.data.library.NavIndicatorStyle
import app.xreader.data.prefs.PerfTier
import app.xreader.ui.localreader.V159ComposeHarness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Preview dispatch (nav engine round, SPEC-NAV-ENGINE-2026-11-16
 * §5): the Customise miniature dock must render EVERY style —
 * all sixteen dispatch through the real IndicatorHost and the
 * fake slots without crashing — and the two layout styles
 * show their settled states in the slots themselves:
 * EXPANDING's selected slot carries a real "Library" label
 * inside its pill, FLASHY's selected slot stands its label
 * where the icon was, and shape styles draw no label text at
 * all (their "Library" exists only as the icon's
 * contentDescription — which is why this test reads the
 * semantics Text property, never findByText).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    sdk = [35],
    qualifiers = "w420dp-h900dp"
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavIndicatorPreviewTest {

    @Test
    fun `every style composes in the preview`() {
        NavIndicatorStyle.entries.forEach { style ->
            val handle = composePreview(style)
            assertTrue(
                "$style preview composed nothing",
                handle.nodes().isNotEmpty()
            )
        }
    }

    @Test
    fun `expanding and flashy show the selected label as real text`() {
        listOf(NavIndicatorStyle.EXPANDING, NavIndicatorStyle.FLASHY)
            .forEach { style ->
                val texts = realTexts(composePreview(style))
                assertTrue(
                    "$style selected slot must show the Library label " +
                        "as text, saw $texts",
                    texts.contains("Library")
                )
            }
    }

    @Test
    fun `shape styles draw no label text in the preview`() {
        listOf(
            NavIndicatorStyle.GOOEY, NavIndicatorStyle.WORM,
            NavIndicatorStyle.LENS, NavIndicatorStyle.PLAIN,
            NavIndicatorStyle.SLIDE, NavIndicatorStyle.SEGMENTED
        ).forEach { style ->
            val texts = realTexts(composePreview(style))
            assertFalse(
                "$style must not draw label text, saw $texts",
                texts.contains("Library")
            )
        }
    }

    private fun composePreview(
        style: NavIndicatorStyle
    ): V159ComposeHarness.Handle =
        V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NavIndicatorPreview(
                    style = style,
                    colour = Color(0xFF8B5CF6),
                    onColour = Color.White,
                    barStyle = NavBarStyle.FLOATING_PILL,
                    perfTier = PerfTier.FULL,
                    reduceMotion = false
                )
            }
        }

    /** The real drawn texts under [handle] — semantics Text
     * values only, never contentDescriptions. */
    private fun realTexts(handle: V159ComposeHarness.Handle): List<String> =
        handle.nodes().flatMap { node ->
            node.config
                .getOrElse(SemanticsProperties.Text) { emptyList() }
                .map { it.text }
        }
}
