package app.xreader.data.parse

import app.xreader.core.error.AppResult
import app.xreader.data.db.BookFormat
import app.xreader.domain.content.ChapterBlock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-18 (round-12 check 2): the local/downloaded branch's page
 * detection follows the donor DirectoryPageLoader semantics —
 * NATURAL order (page2 before page10) and CONTENT-BASED image
 * detection (ImageUtil.isImage: extension OR content), not a
 * plain name sort and not an extension-only filter.
 */
class ImageSniffTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
    private val gif = "GIF89a".toByteArray()
    private val webp = "RIFF....WEBP".toByteArray()
    private val text = "just some text".toByteArray()

    @Test
    fun contentDetectionByMagic() {
        assertTrue(ImageSniff.isImageContent(jpeg))
        assertTrue(ImageSniff.isImageContent(png))
        assertTrue(ImageSniff.isImageContent(gif))
        assertTrue(ImageSniff.isImageContent(webp))
        assertFalse(ImageSniff.isImageContent(text))
        assertFalse(ImageSniff.isImageContent(byteArrayOf(1, 2)))
    }

    @Test
    fun naturalSortPutsPage2BeforePage10() {
        val names = listOf("page10.jpg", "page2.jpg", "page1.jpg", "Page3.jpg")
        assertEquals(
            listOf("page1.jpg", "page2.jpg", "Page3.jpg", "page10.jpg"),
            names.sortedWith(NaturalSort)
        )
    }

    /** ImageBookBuilder over fake archive entries: an
     * extension-less PNG and a misnamed text file prove the
     * content rule; the order proves the natural sort. */
    @Test
    fun builderKeepsContentImagesInNaturalOrder() {
        val workDir = createTempDir("imagebook")
        try {
            val payloads = mapOf(
                "10.jpg" to png,      // image by extension AND content
                "2" to png,           // image by CONTENT only (no extension)
                "1.jpg" to jpeg,      // image
                "3.jpg" to text,      // NOT an image despite the extension? —
                                        // donor rule is extension OR content,
                                        // so this one is KEPT by extension.
                "notes.txt" to text,  // neither — dropped
                "ComicInfo.xml" to "<ComicInfo/>".toByteArray() // dropped
            )
            val result = ImageBookBuilder.build(
                workDir = workDir,
                format = BookFormat.CBZ,
                comicInfoXml = null,
                extractPage = { name, dest ->
                    val bytes = payloads[name] ?: return@build false
                    dest.writeBytes(bytes)
                    true
                },
                pageNames = payloads.keys.toList()
            )
            assertTrue(result is AppResult.Ok)
            val book = (result as AppResult.Ok).value
            assertEquals(1, book.chapters.size)
            // Read the staged chapter's blocks back from the
            // chapters dir the builder wrote.
            val chapterFile = File(book.chaptersDir, book.chapters[0].contentFileName)
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val content = json.decodeFromString(
                app.xreader.domain.content.ChapterContent.serializer(),
                chapterFile.readText()
            )
            val imageNames = content.blocks
                .filterIsInstance<ChapterBlock.Image>()
                .map { it.fileName }
            // Natural order of the KEPT pages: 1.jpg, 2, 3.jpg, 10.jpg
            assertEquals(listOf("1.jpg", "2", "3.jpg", "10.jpg"), imageNames)
        } finally {
            workDir.deleteRecursively()
        }
    }

    @Test
    fun builderRejectsArchiveWithNoImages() {
        val workDir = createTempDir("imagebook-empty")
        try {
            val result = ImageBookBuilder.build(
                workDir = workDir,
                format = BookFormat.CBZ,
                comicInfoXml = null,
                extractPage = { _, dest ->
                    dest.writeBytes(text)
                    true
                },
                pageNames = listOf("a.txt", "b.xml")
            )
            assertTrue(result is AppResult.Err)
        } finally {
            workDir.deleteRecursively()
        }
    }
}
