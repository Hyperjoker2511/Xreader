package app.xreader.data.export

import app.xreader.core.error.AppResult
import app.xreader.data.FakeChapterDao
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.EpubParser
import app.xreader.data.parse.decodeChapter
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Export → re-import round trip: the EPUB Xreader writes must be a
 * valid EPUB Xreader (and anything else) can read back with the same
 * chapter structure and text.
 */
class BookExporterTest {

    @get:Rule val temp = TemporaryFolder()

    @Test fun epubRoundTrip() = runTest {
        val storage = BookStorage(File(temp.root, "files"))
        val chapters = FakeChapterDao()
        val bookId = 7L
        val book = Book(
            id = bookId, title = "Round Trip Novel", author = "Test Writer",
            description = "Exported and re-imported.", addedAt = 1, updatedAt = 1
        )
        val texts = listOf(
            "Chapter 1" to "The export begins with a journey west.",
            "Chapter 2" to "It ends where the rivers meet the sea."
        )
        texts.forEachIndexed { index, (title, prose) ->
            val chapterId = chapters.insert(
                Chapter(
                    bookId = bookId, index = index, title = title,
                    contentPath = "pending", updatedAt = 1
                )
            )
            val content = ChapterContent(
                listOf(
                    ChapterBlock.Heading(1, title),
                    ChapterBlock.Paragraph.plain(prose)
                )
            )
            val relative = storage.writeChapterContent(bookId, chapterId, content)
            assertTrue(relative is AppResult.Ok)
            chapters.setContent(
                chapterId, (relative as AppResult.Ok).value,
                content.wordCount, DownloadState.READY
            )
        }
        val outFile = File(temp.root, "exported.epub")
        val exporter = BookExporter(storage, chapters)
        val result = outFile.outputStream().use { exporter.exportEpub(book, it) }
        assertTrue("export failed: ${(result as? AppResult.Err)?.error}", result is AppResult.Ok)

        // Re-parse the export with our own EPUB parser.
        val work = File(temp.root, "reimport").apply { mkdirs() }
        val parsed = when (val r = EpubParser().parse(outFile, work) {}) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("re-parse failed: ${r.error}")
        }
        assertEquals("Round Trip Novel", parsed.title)
        assertEquals("Test Writer", parsed.author)
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter 1", parsed.chapters[0].title)
        assertEquals("Chapter 2", parsed.chapters[1].title)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val firstText = json.decodeChapter(
            File(parsed.chaptersDir, parsed.chapters[0].contentFileName).readText()
        ).plainText()
        assertTrue(firstText.contains("journey west"))
    }

    @Test fun cbzExportForImageBookAndRefusalForText() = runTest {
        val storage = BookStorage(File(temp.root, "files2"))
        val chapters = FakeChapterDao()
        val bookId = 9L
        val chapterId = chapters.insert(
            Chapter(bookId = bookId, index = 0, title = "Comic", contentPath = "p", updatedAt = 1)
        )
        // Two fake page images in the content dir (img- prefix layout).
        val contentDir = storage.contentDir(bookId)
        File(contentDir, "img-page1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(contentDir, "img-page2.jpg").writeBytes(byteArrayOf(4, 5, 6))
        val content = ChapterContent(
            listOf(ChapterBlock.Image("page1.jpg"), ChapterBlock.Image("page2.jpg"))
        )
        storage.writeChapterContent(bookId, chapterId, content)
        chapters.setContent(chapterId, "content/$bookId/$chapterId.json", 0, DownloadState.READY)

        val exporter = BookExporter(storage, chapters)
        val comic = Book(id = bookId, title = "Comic", addedAt = 1, updatedAt = 1)
        val outFile = File(temp.root, "comic.cbz")
        val result = outFile.outputStream().use { exporter.exportCbz(comic, it) }
        assertTrue(result is AppResult.Ok)
        java.util.zip.ZipFile(outFile).use { zip ->
            assertEquals(2, zip.entries().toList().size)
        }

        val textBook = Book(id = 99, title = "Text", addedAt = 1, updatedAt = 1)
        val refused = exporter.exportCbz(textBook, java.io.ByteArrayOutputStream())
        assertTrue(refused is AppResult.Err)
    }
}
