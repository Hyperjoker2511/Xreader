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
 *  2. the glass topology: v1.0.4 (D1) made the mesh backdrop the
 *     ONE haze source, a sibling layer behind the content, because
 *     an effect node inside a source's subtree was Haze's R01
 *     nested-effect landmine — the history this pin stands on.
 *     The shell now runs TWO layered sources (Patch 3): Haze 1.5.1
 *     is zIndex-aware — its effect node tracks an ancestor source
 *     and its own guard names correct zIndex values as the remedy —
 *     so what this gate pins is the layering contract itself:
 *     MeshBackdrop is the base source at zIndex 0f, the NavHost
 *     carries the page source at zIndex 1f, and both stay gated
 *     on glass.frosted. Whether the layering runs clean on
 *     hardware is settled by device proof; this static gate pins
 *     the contract, not the runtime;
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
    fun `the shell layers its haze sources backdrop zero then navhost one`() {
        val scaffold = source("ui/shell/MainScaffold.kt")
        assertTrue("MeshBackdrop missing", scaffold.contains("fun MeshBackdrop"))
        val sourceCount = scaffold.split("hazeSource(").size - 1
        assertTrue(
            "expected exactly two hazeSource calls (backdrop layer + NavHost layer), " +
                "found $sourceCount — a source added or removed outside the pinned " +
                "two-layer topology re-opens the nested-effect question",
            sourceCount == 2
        )
        val backdropStart = scaffold.indexOf("fun MeshBackdrop")
        val baseAt = scaffold.indexOf("hazeSource(")
        val pageAt = scaffold.indexOf("hazeSource(", baseAt + 1)
        assertTrue(
            "the first hazeSource must live in MeshBackdrop",
            backdropStart >= 0 && baseAt > backdropStart
        )
        assertTrue(
            "the second hazeSource (the NavHost page layer) must come after the first",
            pageAt > baseAt
        )
        val baseCall = scaffold.substring(baseAt, scaffold.indexOf(")", baseAt) + 1)
        assertTrue(
            "the MeshBackdrop source must pass zIndex = 0f (the base layer), " +
                "found: $baseCall",
            baseCall.contains("zIndex = 0f")
        )
        val pageCall = scaffold.substring(pageAt, scaffold.indexOf(")", pageAt) + 1)
        assertTrue(
            "the NavHost source must pass zIndex = 1f (the page layer), " +
                "found: $pageCall",
            pageCall.contains("zIndex = 1f")
        )
        // Both call sites stay gated on glass.frosted: the gate text
        // sits immediately before each call (`if (haze != null &&
        // glass.frosted) it.hazeSource(...)`).
        val baseGate = scaffold.substring((baseAt - 60).coerceAtLeast(0), baseAt)
        assertTrue(
            "the MeshBackdrop source must stay gated on glass.frosted",
            baseGate.contains("glass.frosted")
        )
        val pageGate = scaffold.substring((pageAt - 60).coerceAtLeast(0), pageAt)
        assertTrue(
            "the NavHost source must stay gated on glass.frosted",
            pageGate.contains("glass.frosted")
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
