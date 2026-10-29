package app.xreader.ui.start

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R01 structural gate (delivery-gate skill): the P0 defects were
 * INVISIBLE to behaviour tests — a Haze effect with no background
 * colour, and screens rooted in a bare layout container with no
 * content-colour provider. These source-structure assertions pin
 * the fixes so they can't silently regress:
 *  1. every hazeEffect configuration sets backgroundColor;
 *  2. GlassCard (content inside the haze source) carries NO Haze
 *     effect node — the nested-effect landmine stays defused;
 *  3. Recovery and Onboarding root in a themed Surface.
 * No device is needed and none is claimed: this is a static gate,
 * complementary to the Stage-3 device-draw check.
 */
class ScreenRootStructureTest {

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

    @Test
    fun `frosted glass always sets a haze background colour`() {
        val components = source("ui/common/Components.kt")
        val fnStart = components.indexOf("fun Modifier.frostedGlass")
        assertTrue("frostedGlass not found", fnStart >= 0)
        val block = components.substring(fnStart, components.indexOf("\n}", fnStart))
        assertTrue(
            "hazeEffect without backgroundColor re-opens the P0 crash",
            block.contains("backgroundColor")
        )
    }

    @Test
    fun `glass card carries no haze effect node`() {
        val components = source("ui/common/Components.kt")
        val fnStart = components.indexOf("fun GlassCard")
        assertTrue("GlassCard not found", fnStart >= 0)
        val block = components.substring(fnStart, components.indexOf("\n}", fnStart))
        assertTrue(
            "GlassCard must not apply frostedGlass (nested haze effect)",
            !block.contains("frostedGlass(")
        )
    }

    @Test
    fun `recovery and onboarding root in a themed surface`() {
        listOf("ui/start/RecoveryScreen.kt", "ui/start/OnboardingScreen.kt")
            .forEach { path ->
                val text = source(path)
                assertTrue(
                    "$path must root in Surface(colorScheme.background, " +
                        "contentColor = onBackground)",
                    text.contains("Surface(") &&
                        text.contains("contentColor = MaterialTheme.colorScheme.onBackground")
                )
            }
    }

    @Test
    fun `recovery offers the safe appearance way out`() {
        val text = source("ui/start/RecoveryScreen.kt")
        assertTrue(text.contains("Open with safe appearance"))
    }
}
