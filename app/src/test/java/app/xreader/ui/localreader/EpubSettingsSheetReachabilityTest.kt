package app.xreader.ui.localreader

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (F-1, Finding 4 §8): the settings sheet's tail is
 * REACHABLE. The REAL [EpubSettingsSheet] is composed in a
 * 700 dp-bounded host (the ModalBottomSheet's effective
 * viewport class on the M31); the four controls the shipped
 * non-scrolling Column made physically unreachable —
 * "Volume-key page turns", "Keep screen on", "Orientation",
 * "Reset this book's settings" — become displayed after
 * scrolling to them. On the shipped code the sheet had no
 * scrollable node at all and these texts sat outside every
 * reachable place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubSettingsSheetReachabilityTest {

    @Test
    fun `sheet tail controls become displayed after scroll in a 700dp host`() {
        val handle = V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(width = 400.dp, height = 700.dp)) {
                    EpubSettingsSheet(
                        settings = EpubBookSettings(),
                        updateSettings = {}
                    )
                }
            }
        }
        // The sheet's head is displayed without scrolling.
        val head = V159ComposeHarness.findByText(handle, "Book settings")
        assertNotNull("sheet composed", head)
        assertTrue(
            "sheet head displayed",
            V159ComposeHarness.isDisplayed(handle, head!!)
        )
        // A scrollable node must exist — the shipped sheet had none.
        val scrollable = handle.nodes().any { node ->
            node.config.getOrNull(
                androidx.compose.ui.semantics.SemanticsActions.ScrollBy
            ) != null
        }
        assertTrue("the sheet root scrolls (F-1)", scrollable)

        for (label in listOf(
            "Volume-key page turns",
            "Keep screen on",
            "Orientation",
            "Reset this book's settings"
        )) {
            assertNotNull(
                "$label is composed",
                V159ComposeHarness.findByText(handle, label)
            )
            assertTrue(
                "$label becomes displayed after scroll",
                V159ComposeHarness.scrollUntilDisplayed(handle, label)
            )
        }
    }
}
