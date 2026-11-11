package app.xreader.data.importer

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeBookmarkDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeHighlightDao
import app.xreader.data.FakeLocalOutlineDao
import app.xreader.data.FakeReadingMemoryDao
import app.xreader.data.FakeReadingProgressDao
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Bookmark
import app.xreader.data.db.Chapter
import app.xreader.data.db.LocalOutline
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import app.xreader.data.library.LocalBookDeleteMode
import app.xreader.data.library.LocalBookDeletion
import app.xreader.data.library.ReadingMemoryCodec
import app.xreader.data.parse.ParserRegistry
import app.xreader.data.parse.PdfOutlineEntry
import app.xreader.data.parse.PdfProbe
import app.xreader.data.parse.PdfTextExtractor
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.6 (§3.4(d) + §3.7 + §3.6): in-place registration and the
 * delete model, proven against the fake-DAO rig — register a book
 * where it lives (no copy, no chapter rows, outline filed), read
 * to a locator, bookmark, delete, and re-add the identical file:
 * the place returns. Forget wipes the note; a same-name
 * different-content file never matches the fingerprint.
 */
class V156InPlaceImportTest {

    @get:Rule val temp = TemporaryFolder()

    private inner class Rig(pdfExtractor: PdfTextExtractor? = null) {
        val storage = BookStorage(temp.newFolder())
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        val memories = FakeReadingMemoryDao()
        val outlines = FakeLocalOutlineDao()
        val progress = FakeReadingProgressDao()
        val bookmarks = FakeBookmarkDao()
        val highlights = FakeHighlightDao()
        val importer = BookImporter(
            storage, ParserRegistry(pdfExtractor), books, chapters,
            memories, outlines, progress, bookmarks, highlights
        ) { 42_000L }
        val deleter = LocalBookDeletion(
            bookDao = books,
            chapterDao = chapters,
            readingProgressDao = progress,
            bookmarkDao = bookmarks,
            highlightDao = highlights,
            readingMemoryDao = memories,
            localOutlineDao = outlines,
            storage = storage,
            clock = { 43_000L }
        )
    }

    private suspend fun stageOrFail(
        importer: BookImporter, name: String, bytes: ByteArray, uri: String?
    ): ImportCandidate = when (
        val r = importer.stage(name, documentUri = uri) { bytes.inputStream() }
    ) {
        is AppResult.Ok -> r.value
        is AppResult.Err -> throw AssertionError("stage failed: ${r.error}")
    }

    private suspend fun importOrFail(
        importer: BookImporter, candidate: ImportCandidate
    ): Book = when (val r = importer.importStaged(candidate)) {
        is AppResult.Ok -> r.value
        is AppResult.Err -> throw AssertionError("import failed: ${r.error}")
    }

    @Test fun inPlaceEpubRegistersWithoutCopyChaptersOrContentTree() = runTest {
        val rig = Rig()
        val bytes = buildEpub("Fixture One", "A tiny book with real words.")
        val candidate = stageOrFail(
            rig.importer, "fixture.epub", bytes,
            "content://docs/document/fixture.epub"
        )
        // Staging captured the hash the fingerprint is built from.
        assertEquals(ReadingMemoryCodec.sha256Hex(bytes), candidate.contentHash)
        assertEquals("content://docs/document/fixture.epub", candidate.documentUri)

        val book = importOrFail(rig.importer, candidate)
        assertNull("in-place books hold NO app copy", book.filePath)
        assertEquals("content://docs/document/fixture.epub", book.documentUri)
        assertEquals(
            "fixture.epub|${bytes.size}|${candidate.contentHash}",
            book.fileFingerprint
        )
        assertEquals(2, book.totalChapters)
        assertTrue(rig.chapters.forBook(book.id).isEmpty())

        val outline = rig.outlines.forBook(book.id)
        assertEquals(2, outline.size)
        assertEquals("{\"section\":0}", outline[0].targetJson)
        assertEquals("{\"section\":1}", outline[1].targetJson)

        // No content tree, no retained-copy dir was ever created.
        assertTrue(!File(rig.storage.filesDir, "content/${book.id}").exists())
        assertTrue(!File(rig.storage.filesDir, "books/${book.id}").exists())

        // §3.6 accounting: held bytes are the cover only — the
        // device file is never counted.
        assertNotNull(book.coverPath)
        val coverBytes = rig.storage.resolve(book.coverPath!!).length()
        assertEquals(coverBytes, rig.storage.bookFilesSizeBytes(book))
        assertTrue(rig.storage.bookFilesSizeBytes(book) <= coverBytes + 16)
    }

    /** A PDF extractor whose probe carries a real outline with
     * page targets (the production probe shape). */
    private class OutlinePdfExtractor : PdfTextExtractor {
        override fun extractPages(file: File): AppResult<List<String>> =
            AppResult.Ok(listOf("Chapter 1\nSome prose on the page.\n"))

        override fun probe(file: File): AppResult<PdfProbe> = AppResult.Ok(
            PdfProbe(
                pageCount = 10,
                title = "PDF Fixture",
                author = "P Author",
                description = null,
                outline = listOf(
                    PdfOutlineEntry("Start", 0),
                    PdfOutlineEntry("Middle", 5)
                )
            )
        )
    }

    @Test fun inPlacePdfFilesOutlineAsPageTargets() = runTest {
        val rig = Rig(OutlinePdfExtractor())
        val bytes = "%PDF-1.7\n% v156 in-place fixture\n".toByteArray() +
            ByteArray(64) { 1 }
        val candidate = stageOrFail(
            rig.importer, "fixture.pdf", bytes, "content://docs/document/fixture.pdf"
        )
        assertEquals(BookFormat.PDF, candidate.format)
        val book = importOrFail(rig.importer, candidate)
        assertNull(book.filePath)
        assertEquals(2, book.totalChapters)
        assertTrue(rig.chapters.forBook(book.id).isEmpty())
        val outline = rig.outlines.forBook(book.id)
        assertEquals(
            listOf("{\"page\":0}", "{\"page\":5}"),
            outline.map { it.targetJson }
        )
        assertEquals(listOf("Start", "Middle"), outline.map { it.title })
    }

    @Test fun deleteThenReAddRestoresPositionAndBookmark() = runTest {
        val rig = Rig()
        val bytes = buildEpub("Fixture One", "A tiny book with real words.")
        val uri = "content://docs/document/fixture.epub"
        val book = importOrFail(
            rig.importer, stageOrFail(rig.importer, "fixture.epub", bytes, uri)
        )
        // Read to a locator + bookmark it.
        rig.progress.upsert(
            ReadingProgress(
                bookId = book.id, chapterId = 0, chapterIndex = 1,
                fraction = 0.4f, scrollFraction = 0.4f, updatedAt = 1,
                locatorJson = "{\"section\":1,\"bookFraction\":0.4}"
            )
        )
        rig.bookmarks.insert(
            Bookmark(
                bookId = book.id, chapterId = 0, chapterIndex = 1,
                charOffset = 7, label = "mark", createdAt = 1, updatedAt = 1,
                locatorJson = "{\"section\":1}"
            )
        )

        val deleted = rig.deleter.deleteLocalBook(book, LocalBookDeleteMode.DELETE_BOOK)
        assertTrue("delete failed: $deleted", deleted is AppResult.Ok)
        assertNull(rig.books.getById(book.id))
        // Zero content bytes: no retained copy, no content tree,
        // the cover went with the book.
        assertTrue(!File(rig.storage.filesDir, "books/${book.id}").exists())
        assertTrue(!File(rig.storage.filesDir, "content/${book.id}").exists())
        assertTrue(!rig.storage.resolve(book.coverPath!!).exists())
        // Exactly one memory NOTE survives.
        val memories = rig.memories.all()
        assertEquals(1, memories.size)
        assertEquals(book.fileFingerprint, memories[0].fingerprint)
        assertEquals(0.4f, memories[0].fraction)

        // Re-add the identical file → resume.
        val reAdded = importOrFail(
            rig.importer, stageOrFail(rig.importer, "fixture.epub", bytes, uri)
        )
        assertTrue(reAdded.id != book.id)
        val restored = rig.progress.getForBook(reAdded.id)
        assertNotNull(restored)
        assertEquals(0.4f, restored!!.fraction)
        assertEquals("{\"section\":1,\"bookFraction\":0.4}", restored.locatorJson)
        val restoredMarks = rig.bookmarks.forBook(reAdded.id)
        assertEquals(1, restoredMarks.size)
        assertEquals("mark", restoredMarks[0].label)
        assertEquals("{\"section\":1}", restoredMarks[0].locatorJson)
    }

    @Test fun forgetWipesTheNoteAndReAddStartsFresh() = runTest {
        val rig = Rig()
        val bytes = buildEpub("Fixture One", "A tiny book with real words.")
        val uri = "content://docs/document/fixture.epub"
        val book = importOrFail(
            rig.importer, stageOrFail(rig.importer, "fixture.epub", bytes, uri)
        )
        rig.progress.upsert(
            ReadingProgress(
                bookId = book.id, chapterId = 0, chapterIndex = 1,
                fraction = 0.4f, scrollFraction = 0.4f, updatedAt = 1,
                locatorJson = "{\"section\":1,\"bookFraction\":0.4}"
            )
        )
        val deleted = rig.deleter.deleteLocalBook(book, LocalBookDeleteMode.FORGET)
        assertTrue(deleted is AppResult.Ok)
        assertTrue(rig.memories.all().isEmpty())

        val reAdded = importOrFail(
            rig.importer, stageOrFail(rig.importer, "fixture.epub", bytes, uri)
        )
        assertNull(rig.progress.getForBook(reAdded.id))
        assertTrue(rig.bookmarks.forBook(reAdded.id).isEmpty())
    }

    @Test fun sameNameDifferentContentNeverMatchesTheFingerprint() = runTest {
        val rig = Rig()
        val uri = "content://docs/document/fixture.epub"
        val first = buildEpub("Fixture One", "A tiny book with real words.")
        val book = importOrFail(
            rig.importer, stageOrFail(rig.importer, "fixture.epub", first, uri)
        )
        rig.progress.upsert(
            ReadingProgress(
                bookId = book.id, chapterId = 0, chapterIndex = 0,
                fraction = 0.9f, scrollFraction = 0.9f, updatedAt = 1
            )
        )
        rig.deleter.deleteLocalBook(book, LocalBookDeleteMode.DELETE_BOOK)
        assertEquals(1, rig.memories.all().size)

        val other = buildEpub("Fixture Two", "Entirely different words live here now.")
        val stranger = importOrFail(
            rig.importer, stageOrFail(rig.importer, "fixture.epub", other, uri)
        )
        assertTrue(stranger.fileFingerprint != book.fileFingerprint)
        assertNull(rig.progress.getForBook(stranger.id))
    }

    @Test fun clearLegacyDataDropsChaptersAndContentButKeepsTheBook() = runTest {
        val rig = Rig()
        val bookId = rig.books.insert(
            Book(
                title = "Legacy", sourceType = SourceType.LOCAL_FILE,
                localFormat = BookFormat.EPUB, filePath = "books/legacy/book.epub",
                totalChapters = 1, addedAt = 1, updatedAt = 1
            )
        )
        val book = rig.books.getById(bookId)!!
        rig.chapters.insert(
            Chapter(
                bookId = bookId, index = 0, title = "C1",
                contentPath = "content/$bookId/x.json", updatedAt = 1
            )
        )
        File(rig.storage.contentDir(bookId), "x.json").writeText("{}")
        rig.outlines.insertAll(
            listOf(
                LocalOutline(
                    bookId = bookId, idx = 0, title = "C1",
                    targetJson = "{\"legacyChapterIndex\":0}"
                )
            )
        )
        val cleared = rig.deleter.clearLegacyData()
        assertEquals(1, cleared)
        assertTrue(rig.chapters.forBook(bookId).isEmpty())
        assertTrue(!File(rig.storage.filesDir, "content/$bookId").exists())
        // The book, its retained-file pointer and its outline stay.
        assertNotNull(rig.books.getById(bookId))
        assertEquals("books/legacy/book.epub", rig.books.getById(bookId)?.filePath)
        assertEquals(1, rig.outlines.forBook(bookId).size)
        assertNotNull(book)
    }

    private fun buildEpub(title: String, body: String): ByteArray {
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
                <dc:title>$title</dc:title><dc:language>en</dc:language></metadata>
                <manifest>
                <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
                <item id="cov" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
                    .toByteArray()
            )
            entry(
                "c1.xhtml",
                ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" +
                    "<h1>Start</h1><p>$body</p></body></html>").toByteArray()
            )
            entry(
                "c2.xhtml",
                ("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" +
                    "<h1>Next</h1><p>Second section words follow here.</p></body></html>")
                    .toByteArray()
            )
            entry("cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2))
        }
        return out.toByteArray()
    }
}
