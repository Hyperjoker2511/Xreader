package app.xreader.ui.localreader

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (F-2, Finding 4 P1): the reading surface's size is
 * CONSTANT across chrome toggles. The production overlay host
 * ([EpubChromeOverlayHost]) is composed with a size-recording
 * body: with the chrome visible and with it hidden, the body
 * measures identically — the shipped Scaffold layout resized
 * the Readium WebView by the bars' animated height on every
 * centre-tap. Also pins the F-6 placeholder predicate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubBodySizeStabilityTest {

    @Test
    fun `body size is identical with chrome visible and hidden`() {
        var controlsVisible by mutableStateOf(true)
        var bodySize = IntSize.Zero
        val handle = V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(width = 400.dp, height = 700.dp)) {
                    EpubChromeOverlayHost(
                        controlsVisible = controlsVisible,
                        topBar = {
                            Box(Modifier.size(width = 400.dp, height = 52.dp))
                        },
                        bottomChrome = {
                            Box(Modifier.size(width = 400.dp, height = 180.dp))
                        },
                        statusLine = { Text("84% · 20:16") }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .onSizeChanged { bodySize = it }
                        )
                    }
                }
            }
        }
        assertTrue("body laid out", bodySize.width > 0 && bodySize.height > 0)
        val withChrome = bodySize

        handle.activity.runOnUiThread { controlsVisible = false }
        V159ComposeHarness.idle()
        assertEquals(
            "body size must not move when the chrome hides",
            withChrome, bodySize
        )

        handle.activity.runOnUiThread { controlsVisible = true }
        V159ComposeHarness.idle()
        assertEquals(
            "body size must not move when the chrome returns",
            withChrome, bodySize
        )
    }

    @Test
    fun `opening placeholder shows exactly until the first locator lands`() {
        assertTrue(epubOpeningPlaceholderVisible(firstLocatorLanded = false))
        assertFalse(epubOpeningPlaceholderVisible(firstLocatorLanded = true))
    }
}
