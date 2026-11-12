package app.xreader.ui.localreader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (G5 + H8): the permanent slim status line — one
 * component, two payloads. EPUB state (chapter 3 of 49, 1.4%)
 * renders the line's fields; the PDF case renders outline
 * chapter + 14/227. The EPUB line renders in the
 * chrome-HIDDEN state, outside the chrome AnimatedVisibility.
 * The PDF reader's current v1.5.35 structure is different by
 * design: its status facts are the first row inside the
 * bottom chrome capsule, present only while that chrome is
 * open, and no permanent line sits over the page.
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
    fun `both readers compose the line in their current chrome structure`() {
        val epub = sourceFile("EpubBookReader.kt").readText()
        val epubLineIndex = epub.indexOf("LiveReaderStatusLine {")
        assertTrue("EpubBookReader.kt must compose the status line", epubLineIndex >= 0)
        // The EPUB status line call must not sit inside a
        // top/bottom-bar AnimatedVisibility block: it appears
        // in the Scaffold CONTENT (after the bottomBar slot
        // closes), not in a bar slot.
        val epubBottomBarEnd = epub.indexOf(") { padding ->")
        assertTrue(
            "EpubBookReader.kt status line must be in the content, not a bar slot",
            epubLineIndex > epubBottomBarEnd
        )

        val pdf = sourceFile("PdfDocumentReader.kt").readText()
        val pdfLineIndex = pdf.indexOf("LiveReaderStatusLine(bare = true) {")
        assertTrue("PdfDocumentReader.kt must compose the bare status line", pdfLineIndex >= 0)
        assertTrue(
            "PdfDocumentReader.kt no longer uses the removed Scaffold content shape",
            !pdf.contains(") { padding ->")
        )
        // The line is PdfBottomChrome's first status row.
        val bottomChromeStart = pdf.indexOf("private fun PdfBottomChrome(")
        assertTrue(
            "PdfDocumentReader.kt status line must live inside PdfBottomChrome",
            bottomChromeStart >= 0 && pdfLineIndex > bottomChromeStart
        )
        val insideMarkerIndex = pdf.indexOf(
            "// v1.5.35 (item 4): the status facts, INSIDE"
        )
        assertTrue(
            "PdfDocumentReader.kt status line must follow the v1.5.35 inside-bar marker",
            insideMarkerIndex >= 0 && pdfLineIndex > insideMarkerIndex
        )
        // …and that bottom chrome is itself invoked only
        // inside the controlsVisible AnimatedVisibility.
        val snackbarIndex = pdf.indexOf("SnackbarHost(snackbar)")
        val bottomGateIndex = pdf.indexOf("AnimatedVisibility(", snackbarIndex)
        val bottomChromeCall = pdf.indexOf("PdfBottomChrome(", bottomGateIndex)
        assertTrue(
            "PdfDocumentReader.kt bottom chrome must be inside the chrome gate",
            snackbarIndex >= 0 && bottomGateIndex > snackbarIndex &&
                bottomChromeCall > bottomGateIndex
        )
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
