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
 *  2. the v1.0.4 (D1) glass topology: the shell's mesh backdrop is
 *     the ONE haze source (a sibling layer behind the content), and
 *     GlassCard routes its blur through frostedGlass — an effect
 *     node is safe exactly because it never sits inside the
 *     source's subtree (the R01 nested-effect landmine stays
 *     defused by structure, and the NavHost must never carry the
 *     source again);
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
    fun `glass card blurs through the background-colour safe path`() {
        val components = source("ui/common/Components.kt")
        val fnStart = components.indexOf("fun GlassCard")
        assertTrue("GlassCard not found", fnStart >= 0)
        val block = components.substring(fnStart, components.indexOf("\n}", fnStart))
        assertTrue(
            "GlassCard must apply frostedGlass (the v1.0.4 real-blur path)",
            block.contains("frostedGlass(")
        )
    }

    @Test
    fun `the mesh backdrop is the only haze source in the shell`() {
        val scaffold = source("ui/shell/MainScaffold.kt")
        assertTrue("MeshBackdrop missing", scaffold.contains("fun MeshBackdrop"))
        val sourceCount = scaffold.split("hazeSource(").size - 1
        assertTrue(
            "expected exactly one hazeSource (the backdrop), found $sourceCount — " +
                "a second source on the NavHost re-opens the nested-effect topology",
            sourceCount == 1
        )
        val backdropStart = scaffold.indexOf("fun MeshBackdrop")
        val sourceAt = scaffold.indexOf("hazeSource(")
        assertTrue(
            "the one hazeSource must live in MeshBackdrop",
            backdropStart >= 0 && sourceAt > backdropStart
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
