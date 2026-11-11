package app.xreader.komikku

import app.xreader.komikku.presentation.scrubberPresentation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (I1): the vertical scrubber is the donor's rail —
 * slim rail, clean pill thumb, current label above + total
 * below OUTSIDE the track, and NO full-height band background
 * anywhere in the shipped composable.
 */
class V158ScrubberPresentationTest {

    @Test
    fun `labels and thumb fraction follow the page`() {
        val first = scrubberPresentation(currentPage = 0, totalPages = 19)
        assertEquals("1", first.currentLabel)
        assertEquals("19", first.totalLabel)
        assertEquals(0f, first.thumbFraction, 0.001f)

        val mid = scrubberPresentation(currentPage = 9, totalPages = 19)
        assertEquals("10", mid.currentLabel)
        assertEquals(0.5f, mid.thumbFraction, 0.001f)

        val last = scrubberPresentation(currentPage = 18, totalPages = 19)
        assertEquals(1f, last.thumbFraction, 0.001f)
    }

    @Test
    fun `the shipped vertical navigator has rail pill and external labels, no band`() {
        val source = sourceFile(
            "app/xreader/komikku/presentation/ReaderChrome.kt"
        ).readText()
        val start = source.indexOf("fun ChapterNavigatorVert")
        val end = source.indexOf("fun ReaderBottomBar")
        assertTrue("navigator located", start >= 0 && end > start)
        val section = source.substring(start, end)
        assertTrue(
            "no band-background Surface remains on the vertical navigator",
            !section.contains("Surface(")
        )
        assertTrue(section.contains("ScrubberPillThumb"))
        assertTrue(section.contains("scrubberPresentation"))
        assertTrue(section.contains("presentation.currentLabel"))
        assertTrue(section.contains("presentation.totalLabel"))
    }

    private fun sourceFile(suffix: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "app/src/main/java/$suffix")
            if (candidate.isFile) return candidate
            val direct = File(dir, "src/main/java/$suffix")
            if (direct.isFile) return direct
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate $suffix from ${System.getProperty("user.dir")}")
    }
}
