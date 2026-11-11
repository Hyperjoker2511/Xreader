package app.xreader.ui.localreader

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.5.9 (Finding 4 §8): every quick-row tap has an effect.
 * The REAL [EpubQuickRow] is composed with recording handlers
 * (the production [epubQuickActions] model binds them, in
 * Moon+'s order); tapping each of the six actions must invoke
 * exactly its handler. The Day/night case additionally runs
 * the production toggle transform behind a settings state —
 * the same transform `EpubBookReader`'s handler submits
 * through `updateEpubSettings` — and asserts the settings
 * state actually flips DARK → Paper light.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w420dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubQuickRowTapEffectTest {

    @Test
    fun `each quick action tap invokes its matching handler`() {
        val calls = mutableListOf<String>()
        val handlers = EpubChromeHandlers(
            onToggleDayNight = { calls += "dayNight" },
            onContents = { calls += "contents" },
            onBookmarks = { calls += "bookmarks" },
            onSearch = { calls += "search" },
            onSettings = { calls += "settings" },
            onReadAloud = { calls += "readAloud" }
        )
        val handle = V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(width = 400.dp, height = 120.dp)) {
                    EpubQuickRow(
                        handlers = handlers, isDark = true, ttsPlaying = false
                    )
                }
            }
        }
        for (label in listOf(
            "Day / night", "Contents", "Bookmarks",
            "Search", "Book settings", "Read aloud"
        )) {
            assertTrue(
                "tap on $label lands",
                V159ComposeHarness.clickByLabel(handle, label)
            )
        }
        assertEquals(
            listOf(
                "dayNight", "contents", "bookmarks",
                "search", "settings", "readAloud"
            ),
            calls
        )
    }

    @Test
    fun `day night tap flips the settings state`() {
        var settings by mutableStateOf(EpubBookSettings())
        assertEquals(EpubThemeChoice.DARK, settings.theme)
        val handlers = EpubChromeHandlers(
            onToggleDayNight = {
                // The production handler's transform, verbatim:
                // updateEpubSettings { it.toggleDayNight() }.
                settings = settings.toggleDayNight()
            },
            onContents = {}, onBookmarks = {}, onSearch = {},
            onSettings = {}, onReadAloud = {}
        )
        val handle = V159ComposeHarness.compose {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(width = 400.dp, height = 120.dp)) {
                    EpubQuickRow(
                        handlers = handlers,
                        isDark = settings.theme == EpubThemeChoice.DARK,
                        ttsPlaying = false
                    )
                }
            }
        }
        assertTrue(V159ComposeHarness.clickByLabel(handle, "Day / night"))
        assertEquals(
            "day/night flipped DARK to the last light preset (Paper)",
            EpubThemePreset.PAPER, settings.currentPreset()
        )
        assertTrue(V159ComposeHarness.clickByLabel(handle, "Day / night"))
        assertEquals(
            "and back to the last dark preset (AMOLED)",
            EpubThemePreset.AMOLED, settings.currentPreset()
        )
    }
}
