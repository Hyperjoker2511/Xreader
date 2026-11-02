package app.xreader.ui.common

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.1 UI-audit rider (UI-AUDIT.md F1/F2/F7/F8) — source-structure
 * pins in the ScreenRootStructureTest tradition: these four defects
 * are invisible to behaviour tests (a hardcoded colour, a hit area,
 * a hardcoded enabled flag, a press effect on a disabled row), so
 * the gate asserts the fixed shapes in source. Device rendering of
 * the ring/chips/rows stays device-owed.
 */
class V151UiAuditTest {

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

    private fun block(src: String, startMarker: String): String {
        val start = src.indexOf(startMarker)
        assertTrue("marker not found: $startMarker", start >= 0)
        return src.substring(start, src.indexOf("\n}", start))
    }

    @Test
    fun `F1 level ring sweep is scheme driven with no hardcoded cyan`() {
        val ring = block(source("ui/profile/ProfileScreen.kt"), "private fun LevelRing")
        assertTrue(
            "ring sweep must use the colour scheme (primary/tertiary)",
            ring.contains("MaterialTheme.colorScheme.primary") &&
                ring.contains("MaterialTheme.colorScheme.tertiary")
        )
        assertFalse(
            "the hardcoded VibrantCyan/ElectricPurple palette must be gone from the ring",
            ring.contains("VibrantCyan") || ring.contains("ElectricPurple")
        )
    }

    @Test
    fun `F2 clickable nocturne chip reserves a minimum interactive size`() {
        val chip = block(source("ui/common/NocturneKit.kt"), "fun NocturneChip")
        assertTrue(
            "the clickable chip must expand its hit area to the M3 minimum (48dp >= 40dp floor)",
            chip.contains("minimumInteractiveComponentSize")
        )
    }

    @Test
    fun `F7 add shelf pill is enabled only with a non blank name`() {
        val library = source("ui/library/LibraryScreen.kt")
        val start = library.indexOf("text = \"Add shelf\"")
        assertTrue("Add shelf pill not found", start >= 0)
        val pill = library.substring(start, minOf(start + 1600, library.length))
        assertTrue(
            "Add shelf must wire enabled to the name, not hardcode enabled = true",
            pill.contains("enabled = newName.isNotBlank()")
        )
    }

    @Test
    fun `F8 disabled sheet action row has no click and no bounce`() {
        val row = block(source("ui/common/Components.kt"), "fun GlassSheetActionRow")
        assertTrue(
            "a disabled row must pass no click at all (plain, interaction-free surface)",
            row.contains("onClick = if (enabled) onClick else null")
        )
        assertTrue(
            "a disabled row must not bounce on press",
            row.contains("bouncePress = enabled")
        )
    }
}
