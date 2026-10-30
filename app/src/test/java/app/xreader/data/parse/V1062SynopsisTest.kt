package app.xreader.data.parse

import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.6.2 (F6a): the synopsis path — an EPUB WITH a description
 * parses with its synopsis populated, a PDF's Subject metadata maps
 * the same way, the metadata-only EPUB read powers heal-on-open,
 * and the heal's file picking only fires for local books whose
 * file is actually present.
 */
class V1062SynopsisTest : ParserTestBase() {

    @Test fun epubWithDescriptionParsesSynopsis() {
        val (staged, work) = stage("book.epub", epubWithDescription())
        val parsed = EpubParser().parse(staged, work) {}.unwrap()
        assertEquals("Raised by monsters, he fights to protect humanity.", parsed.description)
    }

    @Test fun epubReadDescriptionIsMetadataOnly() {
        val (staged, work) = stage("book.epub", epubWithDescription())
        assertEquals(
            "Raised by monsters, he fights to protect humanity.",
            EpubParser().readDescription(staged, work)
        )
    }

    private class SubjectExtractor(
        private val pages: List<String>,
        private val subject: String?
    ) : PdfTextExtractor {
        override fun extractPages(file: File): AppResult<List<String>> =
            AppResult.Ok(pages)

        override fun extractDescription(file: File): String? = subject
    }

    @Test fun pdfSubjectBecomesTheSynopsis() {
        val pages = listOf(
            "Chapter 1\nA long enough opening page of prose to pass " +
                "the scan check and keep the parser happy today.\n"
        )
        val (staged, work) = stage("book.pdf", byteArrayOf(1, 2))
        val parsed = PdfBookParser(
            SubjectExtractor(pages, "A quest across the nine seas.")
        ).parse(staged, work) {}.unwrap()
        assertEquals("A quest across the nine seas.", parsed.description)
    }

    @Test fun pdfWithoutSubjectHasNoSynopsis() {
        val pages = listOf(
            "Chapter 1\nA long enough opening page of prose to pass " +
                "the scan check and keep the parser happy today.\n"
        )
        val (staged, work) = stage("book.pdf", byteArrayOf(1, 2))
        val parsed = PdfBookParser(
            SubjectExtractor(pages, null)
        ).parse(staged, work) {}.unwrap()
        assertNull(parsed.description)
    }

    @Test fun healFindsTheRecordedFileAndReadsIt() {
        val filesDir = temp.newFolder("files")
        val storage = BookStorage(filesDir)
        val bookDir = storage.booksDir(7L)
        val epubFile = File(bookDir, "shadow.epub").apply {
            writeBytes(epubWithDescription())
        }
        val heal = LocalSynopsisHeal(storage, null)
        val book = Book(
            id = 7L,
            title = "Shadow Fixture",
            description = "",
            sourceType = SourceType.LOCAL_FILE,
            localFormat = BookFormat.EPUB,
            filePath = storage.bookFileRelative(7L, "shadow.epub"),
            addedAt = 1L,
            updatedAt = 1L
        )
        val candidate = heal.candidateFile(book)
        assertEquals(epubFile.absolutePath, candidate?.absolutePath)
        assertEquals(
            "Raised by monsters, he fights to protect humanity.",
            heal.readDescription(book, candidate!!)
        )
    }

    @Test fun healIgnoresSourceBooksAndMissingFiles() {
        val filesDir = temp.newFolder("files")
        val storage = BookStorage(filesDir)
        val heal = LocalSynopsisHeal(storage, null)
        val sourceBook = Book(
            id = 9L,
            title = "Site Book",
            description = "",
            sourceType = SourceType.EXTENSION,
            localFormat = BookFormat.EPUB,
            addedAt = 1L,
            updatedAt = 1L
        )
        assertNull(heal.candidateFile(sourceBook))
        val goneBook = sourceBook.copy(
            id = 10L,
            sourceType = SourceType.LOCAL_FILE,
            filePath = "books/10/missing.epub"
        )
        assertNull(heal.candidateFile(goneBook))
        assertTrue(storage.booksDir(10L).listFiles().isNullOrEmpty())
    }

    /** One-chapter EPUB carrying a dc:description. */
    private fun epubWithDescription(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>"""
            )
            entry(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">urn:test:f6a</dc:identifier>
                <dc:title>Shadow Fixture</dc:title>
                <dc:creator>Some Author</dc:creator>
                <dc:description>Raised by monsters, he fights to protect humanity.</dc:description>
                <dc:language>en</dc:language>
                </metadata>
                <manifest><item id="chap1" href="chap1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="chap1"/></spine>
                </package>"""
            )
            entry(
                "OEBPS/chap1.xhtml",
                """<?xml version="1.0"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><head><title>ch</title></head>
                <body><p>The chapter itself.</p></body></html>"""
            )
        }
        return out.toByteArray()
    }
}
