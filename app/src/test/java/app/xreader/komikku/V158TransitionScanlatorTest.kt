package app.xreader.komikku

import app.xreader.komikku.model.ReaderChapter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (I2): transition cards (and the reader dialogs) carry
 * the chapter's scanlator now that E1 gives the model one —
 * the two omission comments are deleted, not worked around.
 */
class V158TransitionScanlatorTest {

    @Test
    fun `a chapter with a scanlator renders it on the card line`() {
        val chapter = ReaderChapter(
            chapterId = 1, title = "Chapter 1", scanlator = "Gengar"
        )
        assertEquals("Gengar", ScanlatorLine.scanlatorSegment(chapter.scanlator))
    }

    @Test
    fun `a chapter without one renders no empty segment`() {
        val chapter = ReaderChapter(chapterId = 1, title = "Chapter 1")
        assertNull(ScanlatorLine.scanlatorSegment(chapter.scanlator))
    }

    @Test
    fun `the omission comments are gone from the shipped files`() {
        val cards = sourceFile(
            "app/xreader/komikku/presentation/ChapterTransitionCards.kt"
        ).readText()
        val dialogs = sourceFile(
            "app/xreader/komikku/presentation/ReaderDialogs.kt"
        ).readText()
        assertTrue(cards.contains("chapter.scanlator"))
        assertTrue(!cards.contains("omitted rather than fabricated"))
        assertTrue(!dialogs.contains("no scanlator field exists"))
        assertTrue(dialogs.contains("chapter.scanlator"))
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
