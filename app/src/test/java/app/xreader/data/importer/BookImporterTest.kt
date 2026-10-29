package app.xreader.data.importer

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.db.BookFormat
import app.xreader.data.db.DownloadState
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.ParserRegistry
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookImporterTest {

    @get:Rule val temp = TemporaryFolder()

    private fun importer(storage: BookStorage, books: FakeBookDao, chapters: FakeChapterDao) =
        BookImporter(storage, ParserRegistry(null), books, chapters) { 42_000L }

    @Test fun txtImportLandsBookChaptersAndContent() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        val importer = importer(storage, books, chapters)
        val text = "Chapter 1\n\nThe story begins in a quiet village.\n\n" +
            "Chapter 2\n\nIt ends beyond the mountains.\n"
        val candidate = when (val r = importer.stage("my-novel.txt") { text.byteInputStream() }) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("stage failed: ${r.error}")
        }
        assertEquals(BookFormat.TXT, candidate.format)
        assertEquals(null, candidate.unsupportedReason)

        val book = when (val r = importer.importStaged(candidate)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("import failed: ${r.error}")
        }
        assertEquals("my-novel", book.title)
        assertEquals(2, book.totalChapters)
        val rows = chapters.forBook(book.id)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.downloadState == DownloadState.READY })
        val content = storage.readChapterContent(book.id, rows[0].id)
        assertTrue(content is AppResult.Ok)
        assertTrue((content as AppResult.Ok).value.plainText().contains("quiet village"))
    }

    @Test fun unknownFormatIsRefusedAtStaging() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val importer = importer(storage, FakeBookDao(), FakeChapterDao())
        val garbage = byteArrayOf(0, 1, 0, 2, 0, 3, 0, 0, 9)
        val candidate = when (val r = importer.stage("blob.bin") { garbage.inputStream() }) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("stage failed")
        }
        assertEquals(BookFormat.UNKNOWN, candidate.format)
        assertNotNull(candidate.unsupportedReason)
        val result = importer.importStaged(candidate)
        assertTrue(result is AppResult.Err)
    }

    @Test fun epubImportExtractsCover() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        val importer = importer(storage, books, chapters)
        // Minimal EPUB built the same way as the parser fixture.
        val epubBytes = buildMiniEpub()
        val candidate = when (val r = importer.stage("fixture.epub") { epubBytes.inputStream() }) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("stage failed: ${r.error}")
        }
        assertEquals(BookFormat.EPUB, candidate.format)
        val book = when (val r = importer.importStaged(candidate)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("import failed: ${r.error}")
        }
        assertEquals("Mini Fixture", book.title)
        assertNotNull(book.coverPath)
        assertTrue(storage.resolve(book.coverPath!!).isFile)
    }

    private fun buildMiniEpub(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip".toByteArray())
            entry(
                "META-INF/container.xml",
                ("<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>")
                    .toByteArray()
            )
            entry(
                "content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Mini Fixture</dc:title><dc:language>en</dc:language></metadata>
                <manifest>
                <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                <item id="cov" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                </manifest>
                <spine><itemref idref="c1"/></spine></package>""".toByteArray()
            )
            entry(
                "c1.xhtml",
                ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" +
                    "<h1>Start</h1><p>A tiny book with real words.</p></body></html>").toByteArray()
            )
            entry("cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2))
        }
        return out.toByteArray()
    }
}

class BigTxtStreamingTest {

    @get:Rule val temp = TemporaryFolder()

    /** ~25 MB generated novel: the parser must stream it (counts are
     * asserted; the suite machine would feel a whole-file load far
     * less than the 2 GB tablet would, so this pins the chapter/word
     * accounting on a big input instead). */
    @Test fun largeTxtParsesWithCorrectCounts() {
        val file = File(temp.root, "big.txt")
        val chapterCount = 250
        val paragraph = "The road wound through pine forest and cold rivers toward the northern pass. ".repeat(30)
        file.bufferedWriter().use { writer ->
            for (chapter in 1..chapterCount) {
                writer.write("Chapter $chapter\n\n")
                repeat(40) { writer.write(paragraph + "\n\n") }
            }
        }
        assertTrue(file.length() > 20_000_000)
        val work = File(temp.root, "work").apply { mkdirs() }
        val parsed = when (val r = app.xreader.data.parse.TxtParser().parse(file, work) {}) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("parse failed: ${r.error}")
        }
        assertEquals(chapterCount, parsed.chapters.size)
        assertEquals("Chapter 250", parsed.chapters.last().title)
        val totalWords = parsed.chapters.sumOf { it.wordCount }
        assertTrue("words: $totalWords", totalWords > 2_000_000)
    }
}
