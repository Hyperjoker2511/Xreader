package app.xreader.ui.localreader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (G5 + H8): the permanent slim status line — one
 * component, two payloads. EPUB state (chapter 3 of 49, 1.4%)
 * renders the line's fields; the PDF case renders outline
 * chapter + 14/227. The line renders in the chrome-HIDDEN
 * state: both readers compose it OUTSIDE the chrome
 * AnimatedVisibility (structural assertion — the point of the
 * item).
 */
class V158StatusLineTest {

    @Test
    fun `epub payload renders chapter, position in book, percent, battery, clock`() {
        val content = epubStatusLineContent(
            progress = EpubProgress(
                fraction = 0.014f,
                chapterTitle = "Prologue: A Short-Lived Dream",
                sectionIndex = 2,
                sectionCount = 49
            ),
            batteryPercent = 20,
            clockText = "10:51"
        )
        val rendered = content.render()
        assertTrue(rendered.contains("20%"))
        assertTrue(rendered.contains("10:51"))
        assertTrue(rendered.contains("Prologue: A Short-Lived Dream"))
        assertTrue(rendered.contains("3/49"))
        assertTrue(rendered.contains("1%"))
    }

    @Test
    fun `pdf payload renders outline chapter and file position`() {
        val content = pdfStatusLineContent(
            chapterTitle = "Color Illustrations",
            page = 13,
            pageCount = 227,
            batteryPercent = 20,
            clockText = "10:51"
        )
        val rendered = content.render()
        assertTrue(rendered.contains("Color Illustrations"))
        assertTrue(rendered.contains("14/227"))
        assertTrue(rendered.contains("20%"))
        assertTrue(rendered.contains("10:51"))
    }

    @Test
    fun `clock ticks on minute boundaries`() {
        // 10:51:30.000 → 30 s to the next minute.
        val base = 10 * 3600_000L + 51 * 60_000L + 30_000L
        assertEquals(30_000L, msToNextMinute(base))
        assertTrue(statusClockText(base).matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun `both readers compose the line outside the chrome gate`() {
        for (name in listOf("EpubBookReader.kt", "PdfDocumentReader.kt")) {
            val source = sourceFile(name).readText()
            // The CALL site (header comments name the component
            // in prose; the composed call carries the lambda).
            val lineIndex = source.indexOf("LiveReaderStatusLine {")
            assertTrue("$name must compose the status line", lineIndex >= 0)
            // The status line call must not sit inside a
            // top/bottom-bar AnimatedVisibility block: in both
            // files it appears in the Scaffold CONTENT (after
            // the bottomBar slot closes), not in a bar slot.
            val bottomBarEnd = source.indexOf(") { padding ->")
            assertTrue(
                "$name status line must be in the content, not a bar slot",
                lineIndex > bottomBarEnd
            )
        }
    }

    private fun sourceFile(name: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(
                dir,
                "app/src/main/java/app/xreader/ui/localreader/$name"
            )
            if (candidate.isFile) return candidate
            val direct = File(dir, "src/main/java/app/xreader/ui/localreader/$name")
            if (direct.isFile) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate $name from ${System.getProperty("user.dir")}")
    }
}
