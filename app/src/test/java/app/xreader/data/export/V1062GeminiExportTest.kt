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
import app.xreader.domain.content.MediaClip
import app.xreader.domain.content.MediaOverlay
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (Gemini fix round, G2): the EPUB exporter streams —
 * it no longer materialises the whole book's contents before
 * writing. These pins hold the output contract under streaming:
 * a many-chapter book round-trips intact, and an image+overlay
 * chapter still produces its asset entries, its SMIL, and the
 * OPF media-overlay declaration.
 */
class V1062GeminiExportTest {

    @get:Rule val temp = TemporaryFolder()

    private suspend fun storedChapter(
        storage: BookStorage,
        chapters: FakeChapterDao,
        bookId: Long,
        index: Int,
        content: ChapterContent
    ): Long {
        val chapterId = chapters.insert(
            Chapter(
                bookId = bookId, index = index,
                title = "Chapter ${index + 1}", contentPath = "pending",
                updatedAt = 1
            )
        )
        val relative = storage.writeChapterContent(bookId, chapterId, content)
        assertTrue(relative is AppResult.Ok)
        chapters.setContent(
            chapterId, (relative as AppResult.Ok).value,
            content.wordCount, DownloadState.READY
        )
        return chapterId
    }

    @Test fun manyChapterBookExportsAndRoundTrips() = runTest {
        val storage = BookStorage(File(temp.root, "files"))
        val chapters = FakeChapterDao()
        val bookId = 21L
        val book = Book(
            id = bookId, title = "Long Haul", addedAt = 1, updatedAt = 1
        )
        val count = 60
        repeat(count) { index ->
            storedChapter(
                storage, chapters, bookId, index,
                ChapterContent(
                    listOf(
                        ChapterBlock.Heading(1, "Chapter ${index + 1}"),
                        ChapterBlock.Paragraph.plain(
                            "Prose of chapter ${index + 1}, streaming by."
                        )
                    )
                )
            )
        }
        val outFile = File(temp.root, "long.epub")
        val result = outFile.outputStream().use {
            BookExporter(storage, chapters).exportEpub(book, it)
        }
        assertTrue(
            "export failed: ${(result as? AppResult.Err)?.error}",
            result is AppResult.Ok
        )
        val work = File(temp.root, "reimport").apply { mkdirs() }
        val parsed = when (val r = EpubParser().parse(outFile, work) {}) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("re-parse failed: ${r.error}")
        }
        assertEquals(count, parsed.chapters.size)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val lastText = json.decodeChapter(
            File(parsed.chaptersDir, parsed.chapters.last().contentFileName)
                .readText()
        ).plainText()
        assertTrue(lastText.contains("Prose of chapter $count"))
    }

    @Test fun imageAndOverlayChapterKeepsAssetsSmilAndOpfOverlay() = runTest {
        val storage = BookStorage(File(temp.root, "files2"))
        val chapters = FakeChapterDao()
        val bookId = 22L
        val book = Book(
            id = bookId, title = "Narrated", addedAt = 1, updatedAt = 1
        )
        // Assets live in the content dir under the img- prefix.
        val contentDir = storage.contentDir(bookId)
        File(contentDir, "img-map.png").writeBytes(byteArrayOf(9, 8, 7))
        File(contentDir, "img-ch1.mp3").writeBytes(byteArrayOf(6, 5, 4))
        storedChapter(
            storage, chapters, bookId, 0,
            ChapterContent(
                blocks = listOf(
                    ChapterBlock.Paragraph.plain("Read along.", id = "p1"),
                    ChapterBlock.Image("map.png", "Map")
                ),
                mediaOverlay = MediaOverlay(
                    audioFileName = "ch1.mp3",
                    clips = listOf(MediaClip("p1", 0.0, 2.5))
                )
            )
        )
        val outFile = File(temp.root, "narrated.epub")
        val result = outFile.outputStream().use {
            BookExporter(storage, chapters).exportEpub(book, it)
        }
        assertTrue(
            "export failed: ${(result as? AppResult.Err)?.error}",
            result is AppResult.Ok
        )
        ZipFile(outFile).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue("asset image missing: $names", "assets/map.png" in names)
            assertTrue("asset audio missing: $names", "assets/ch1.mp3" in names)
            assertTrue("SMIL missing: $names", "chapter-1.smil" in names)
            val opf = zip.getInputStream(zip.getEntry("content.opf"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue(opf.contains("media-overlay=\"smil1\""))
        }
    }
}
