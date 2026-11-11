package app.xreader.ui.sources

import app.xreader.data.extension.DiagnosticLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v1.5.8 (C4 / F7 F4): the Done-tap solve-quality record.
 * Saving a session at Done writes ONE DiagnosticLog line
 * stating whether the captured jar actually holds a
 * clearance cookie for the source's host — the fact that
 * separates "a capture ran" from "a clearance was earned"
 * in every future Fenrir report. No behaviour change rides
 * on the line; this test pins the line itself.
 */
class V158SolveQualityRecordTest {

    @Before
    fun setUp() {
        DiagnosticLog.clear()
    }

    @Test
    fun `done tap without a clearance cookie records NOT held`() {
        recordSolveQuality(
            "ext-fenrir", emptyMap(), "fenrirealm.com"
        )
        val lines = DiagnosticLog.entries()
            .filter { it.tag == "solve-quality" }
        assertEquals(1, lines.size)
        assertTrue(
            lines.single().message.contains("NOT held at Done-tap")
        )
        assertTrue(
            lines.single().message.contains("fenrirealm.com")
        )
        assertEquals("ext-fenrir", lines.single().detail)
    }

    @Test
    fun `done tap with a clearance cookie records HELD`() {
        recordSolveQuality(
            "ext-fenrir",
            mapOf("fenrirealm.com" to mapOf("cf_clearance" to "abc")),
            "fenrirealm.com"
        )
        val lines = DiagnosticLog.entries()
            .filter { it.tag == "solve-quality" }
        assertEquals(1, lines.size)
        assertTrue(
            lines.single().message.contains("HELD at Done-tap")
        )
    }

    @Test
    fun `clearance under any captured host counts as held`() {
        recordSolveQuality(
            "ext-fenrir",
            mapOf("www.fenrirealm.com" to mapOf("cf_clearance" to "abc")),
            "fenrirealm.com"
        )
        val lines = DiagnosticLog.entries()
            .filter { it.tag == "solve-quality" }
        assertEquals(1, lines.size)
        assertTrue(
            lines.single().message.contains("HELD at Done-tap")
        )
    }
}
