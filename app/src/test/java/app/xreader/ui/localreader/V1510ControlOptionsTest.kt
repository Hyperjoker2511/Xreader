package app.xreader.ui.localreader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.10 (G2) gate: control options — three PRESET tap
 * layouts (not a grid editor), the Smart scroll lock's pure
 * decision, and the volume-key census (both readers run the
 * shared comfort host, which owns the keys for both).
 */
class V1510ControlOptionsTest {

    // ── Tap presets ─────────────────────────────────────────

    @Test
    fun `moon default is the shipped thirds rule`() {
        assertEquals(TapZoneAction.TURN_PREV, tapActionFor(TapLayoutPreset.MOON_DEFAULT, 0.1f, 0.5f))
        assertEquals(TapZoneAction.TOGGLE_CHROME, tapActionFor(TapLayoutPreset.MOON_DEFAULT, 0.5f, 0.5f))
        assertEquals(TapZoneAction.TURN_NEXT, tapActionFor(TapLayoutPreset.MOON_DEFAULT, 0.9f, 0.1f))
        // Y never matters in the default layout.
        assertEquals(TapZoneAction.TURN_PREV, tapActionFor(TapLayoutPreset.MOON_DEFAULT, 0.1f, 0.05f))
    }

    @Test
    fun `edges only turns at the rim and idles between`() {
        assertEquals(TapZoneAction.TURN_PREV, tapActionFor(TapLayoutPreset.EDGES_ONLY, 0.1f, 0.5f))
        assertEquals(TapZoneAction.TURN_NEXT, tapActionFor(TapLayoutPreset.EDGES_ONLY, 0.95f, 0.5f))
        assertEquals(TapZoneAction.TOGGLE_CHROME, tapActionFor(TapLayoutPreset.EDGES_ONLY, 0.5f, 0.5f))
        assertEquals(TapZoneAction.NONE, tapActionFor(TapLayoutPreset.EDGES_ONLY, 0.3f, 0.5f))
        assertEquals(TapZoneAction.NONE, tapActionFor(TapLayoutPreset.EDGES_ONLY, 0.7f, 0.9f))
    }

    @Test
    fun `nine zone grid maps every cell`() {
        val p = TapLayoutPreset.NINE_ZONE
        // Top row: prev · idle · next.
        assertEquals(TapZoneAction.TURN_PREV, tapActionFor(p, 0.16f, 0.16f))
        assertEquals(TapZoneAction.NONE, tapActionFor(p, 0.5f, 0.16f))
        assertEquals(TapZoneAction.TURN_NEXT, tapActionFor(p, 0.83f, 0.16f))
        // Middle row: prev · chrome · next.
        assertEquals(TapZoneAction.TURN_PREV, tapActionFor(p, 0.16f, 0.5f))
        assertEquals(TapZoneAction.TOGGLE_CHROME, tapActionFor(p, 0.5f, 0.5f))
        assertEquals(TapZoneAction.TURN_NEXT, tapActionFor(p, 0.83f, 0.5f))
        // Bottom row: prev · idle · next.
        assertEquals(TapZoneAction.TURN_PREV, tapActionFor(p, 0.16f, 0.83f))
        assertEquals(TapZoneAction.NONE, tapActionFor(p, 0.5f, 0.83f))
        assertEquals(TapZoneAction.TURN_NEXT, tapActionFor(p, 0.83f, 0.83f))
    }

    @Test
    fun `out of range fractions clamp instead of crashing`() {
        assertEquals(
            TapZoneAction.TURN_PREV,
            tapActionFor(TapLayoutPreset.NINE_ZONE, -0.5f, 2f)
        )
        assertEquals(
            TapZoneAction.TURN_NEXT,
            tapActionFor(TapLayoutPreset.MOON_DEFAULT, 2f, 0f)
        )
    }

    // ── Smart scroll lock ───────────────────────────────────

    @Test
    fun `smart lock eats horizontal drift on vertical drags`() {
        // The lock's contract: during a predominantly VERTICAL
        // drag, the stray horizontal component is consumed so
        // the column never drifts sideways mid-read.
        assertTrue(smartScrollLockConsumesHorizontal(5f, 30f, lockEnabled = true))
        assertTrue(smartScrollLockConsumesHorizontal(-5f, 30f, lockEnabled = true))
        // A predominantly horizontal drag is not the lock's
        // business.
        assertFalse(smartScrollLockConsumesHorizontal(30f, 5f, lockEnabled = true))
        // Equal components are not predominantly vertical.
        assertFalse(smartScrollLockConsumesHorizontal(10f, 10f, lockEnabled = true))
        // Pure vertical: no horizontal component to consume.
        assertFalse(smartScrollLockConsumesHorizontal(0f, 40f, lockEnabled = true))
        // Lock off: nothing consumed.
        assertFalse(smartScrollLockConsumesHorizontal(5f, 30f, lockEnabled = false))
    }

    // ── Volume keys: both readers (audit result) ────────────

    private fun sourceFile(name: String): File {
        val candidates = listOf(
            File("src/main/java/app/xreader/ui/localreader/$name"),
            File("app/src/main/java/app/xreader/ui/localreader/$name")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("source file not found: $name")
    }

    @Test
    fun `volume key turning is served to both readers by the comfort host`() {
        // The audit: ReaderComforts' LocalReaderComfortHost
        // owns the volume keys, and BOTH readers wrap their
        // content in it (EPUB in EpubBookReader, PDF inside
        // PdfDocumentReader's Scaffold content) — no G2 build
        // was needed here; this census keeps it true.
        val epubText = sourceFile("EpubBookReader.kt").readText()
        assertTrue(epubText.contains("LocalReaderComfortHost("))
        assertTrue(epubText.contains("onVolumeKeyTurn"))

        val pdfText = sourceFile("PdfDocumentReader.kt").readText()
        assertTrue(pdfText.contains("LocalReaderComfortHost("))
        assertTrue(pdfText.contains("onVolumeKeyTurn"))

        val hostText = sourceFile("ReaderComforts.kt").readText()
        assertTrue(hostText.contains("volumeKeyTurn"))

        // Both formats carry the setting.
        val prefsText = sourceFile("LocalReaderPrefs.kt").readText()
        assertTrue(prefsText.contains("volumeKeyTurn"))
    }
}
