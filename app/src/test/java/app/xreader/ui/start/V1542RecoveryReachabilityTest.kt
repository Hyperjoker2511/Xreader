package app.xreader.ui.start

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.42 hotfix pins (Defect 2): the recovery screen's column
 * never scrolled, so a long crash note pushed "Continue to
 * your library" off the bottom of small screens — and since
 * the note is cleared only from that button (or the
 * safe-appearance row below it), the screen returned on every
 * launch and locked the user out.
 *
 * Source-structure pins, same census style as
 * [ScreenRootStructureTest]: the crash-note card is the ONE
 * bounded, flexible region (weight) with the note text
 * scrolling inside it, and the actions live below/outside
 * that region so they are always on screen. No device is
 * needed and none is claimed — this is a static gate.
 */
class V1542RecoveryReachabilityTest {

    private fun source(relative: String): String {
        val roots = listOf(
            File("src/main/java/app/xreader"),
            File("app/src/main/java/app/xreader"),
            File(System.getProperty("user.dir") ?: ".")
                .resolve("src/main/java/app/xreader")
        )
        val file = roots.map { it.resolve(relative) }.firstOrNull { it.exists() }
            ?: throw AssertionError("source file not found from any known root: $relative")
        return file.readText()
    }

    private fun noteRegion(text: String): String {
        val noteStart = text.indexOf("if (!note.isNullOrBlank())")
        assertTrue("note card block not found", noteStart >= 0)
        val pillAt = text.indexOf("PrimaryPill(")
        assertTrue("PrimaryPill not found", pillAt > noteStart)
        return text.substring(noteStart, pillAt)
    }

    @Test
    fun `the crash note is the bounded region and scrolls inside its card`() {
        val region = noteRegion(source("ui/start/RecoveryScreen.kt"))
        assertTrue(
            "the note card must take the column's flexible space " +
                "(weight), or a long note pushes the actions off screen",
            region.contains("weight(1f)")
        )
        assertTrue(
            "the note text must scroll inside the card",
            region.contains("verticalScroll(rememberScrollState())")
        )
        assertTrue(
            "the scrolling region must be the note text itself",
            region.contains("note.trim()")
        )
    }

    @Test
    fun `the actions sit outside and below the note region`() {
        val text = source("ui/start/RecoveryScreen.kt")
        val pillAt = text.indexOf("PrimaryPill(")
        assertTrue(pillAt >= 0)
        val actions = text.substring(pillAt)
        assertTrue(
            "Continue must be an action below the note region",
            actions.contains("Continue to your library")
        )
        assertTrue(
            "the safe-appearance escape must stay below the note region",
            actions.contains("Open with safe appearance")
        )
        assertFalse(
            "nothing scrollable may wrap the actions themselves",
            actions.contains("verticalScroll")
        )
    }

    @Test
    fun `recovery still composes zero glass machinery`() {
        val text = source("ui/start/RecoveryScreen.kt")
        assertFalse(text.contains("GlassCard("))
        assertFalse(text.contains("frostedGlass("))
        assertFalse(text.contains("hazeSource("))
        assertFalse(text.contains("hazeEffect("))
    }
}
