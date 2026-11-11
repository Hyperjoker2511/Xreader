package app.xreader.data.importer

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.db.BookFormat
import app.xreader.data.db.DownloadState
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.CommercialEpubFixture
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

    @Test fun oversizedFileIsRefusedAtStaging() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val importer = importer(storage, FakeBookDao(), FakeChapterDao())
        importer.maxSourceFileBytes = 8
        val text = "Chapter 1\n\nA story far longer than eight bytes."
        val result = importer.stage("big.txt") { text.byteInputStream() }
        assertTrue("expected Err, got $result", result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue("expected BadFile, got $error", error is AppError.BadFile)
        assertTrue((error as AppError.BadFile).reason.contains("too large"))
        // The staging dir went away again — nothing half-staged stays.
        val stagingRoot = File(storage.filesDir, "import-staging")
        assertTrue(stagingRoot.listFiles().isNullOrEmpty())
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

/**
 * v1.5.4 (WS-7): the local-import findings proven through the
 * production importer — the commercial EPUB lands with its OWN
 * cover and pictures (the owner's "show the book how it is"),
 * the honesty callback fires exactly when pictures were lost,
 * and a password-locked PDF is a solvable state: refused bare,
 * refused with a wrong password, imported in full with the
 * correct one, its staged file kept between tries.
 */
class BookImporterWs7Test {

    @get:Rule val temp = TemporaryFolder()

    private fun importerWith(
        storage: BookStorage,
        books: FakeBookDao,
        chapters: FakeChapterDao,
        registry: ParserRegistry
    ) = BookImporter(storage, registry, books, chapters) { 42_000L }

    private suspend fun stageOrFail(
        importer: BookImporter, name: String, bytes: ByteArray
    ): ImportCandidate = when (val r = importer.stage(name) { bytes.inputStream() }) {
        is AppResult.Ok -> r.value
        is AppResult.Err -> throw AssertionError("stage failed: ${r.error}")
    }

    private suspend fun importOrFail(
        importer: BookImporter,
        candidate: ImportCandidate,
        password: String? = null,
        onDroppedImages: (Int) -> Unit = {}
    ): app.xreader.data.db.Book = when (
        val r = importer.importStaged(
            candidate, onProgress = {}, onDroppedImages = onDroppedImages,
            password = password
        )
    ) {
        is AppResult.Ok -> r.value
        is AppResult.Err -> throw AssertionError("import failed: ${r.error}")
    }

    @Test fun commercialEpubLandsWithEmbeddedCoverAndPictures() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        val importer = importerWith(storage, books, chapters, ParserRegistry(null))
        val candidate = stageOrFail(
            importer, "commercial.epub",
            CommercialEpubFixture.bytes()
        )
        var droppedCalls = 0
        val book = importOrFail(importer, candidate) { droppedCalls++ }
        assertEquals("Commercial Fixture", book.title)
        // Addendum (A) for EPUB: the Library cover is the book's
        // OWN embedded artwork, not the title placeholder.
        assertNotNull(book.coverPath)
        val coverFile = storage.resolve(book.coverPath!!)
        assertTrue(coverFile.isFile)
        assertTrue(
            coverFile.readBytes().contentEquals(CommercialEpubFixture.COVER_BYTES)
        )
        // Every chapter image block resolves to a real file in the
        // content dir (the reader's img- resolution).
        val rows = chapters.forBook(book.id)
        assertEquals(2, rows.size)
        var imageBlocks = 0
        for (row in rows) {
            val content = storage.readChapterContent(book.id, row.id)
            assertTrue(content is AppResult.Ok)
            for (block in (content as AppResult.Ok).value.blocks) {
                if (block is app.xreader.domain.content.ChapterBlock.Image) {
                    imageBlocks++
                    assertTrue(
                        File(storage.contentDir(book.id), "img-${block.fileName}").isFile
                    )
                }
            }
        }
        assertEquals(3, imageBlocks)
        // A clean import shows no honesty line: the callback
        // never fired.
        assertEquals(0, droppedCalls)
    }

    @Test fun epubImportWithUnresolvableImageReportsTheLoss() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val importer = importerWith(
            storage, FakeBookDao(), FakeChapterDao(), ParserRegistry(null)
        )
        val candidate = stageOrFail(
            importer, "missing.epub",
            CommercialEpubFixture.bytes(
                CommercialEpubFixture.Fixture(
                    coverMode = CommercialEpubFixture.CoverMode.PROPERTIES,
                    mangleCase = false,
                    chapter2Image = CommercialEpubFixture.Chapter2Image.NONE,
                    missingImage = true
                )
            )
        )
        var reported = -1
        val book = importOrFail(importer, candidate) { reported = it }
        assertNotNull(book.coverPath)
        assertEquals(1, reported)
    }

    /** Models the production PDF classification (real fixtures
     * prove it in PdfLoadClassificationTest): bare = locked,
     * bound extractor opens only for the right password. */
    private class LockedPdfExtractor : app.xreader.data.parse.PdfTextExtractor {
        private val prose =
            "The road wound on through the long valley and the river " +
                "ran beside it all the way north toward the mountains."
        private val pages = listOf(
            "Chapter 1\n$prose\n",
            "Chapter 2\n$prose And then the snow began to fall over the pass.\n"
        )

        override fun extractPages(
            file: java.io.File
        ): AppResult<List<String>> = AppResult.Err(AppError.DrmProtected)

        override fun withPassword(
            password: String
        ): app.xreader.data.parse.PdfTextExtractor = Bound(password)

        private inner class Bound(
            private val password: String
        ) : app.xreader.data.parse.PdfTextExtractor {
            override fun extractPages(
                file: java.io.File
            ): AppResult<List<String>> =
                if (password == "reader-secret") {
                    AppResult.Ok(pages)
                } else {
                    AppResult.Err(AppError.DrmProtected)
                }

            override fun extractImages(
                file: java.io.File
            ): List<app.xreader.data.parse.PdfPageImage> =
                listOf(
                    app.xreader.data.parse.PdfPageImage(
                        0, ByteArray(120 * 1024) { 7 }
                    )
                )
        }
    }

    @Test fun lockedPdfIsSolvableAcrossPasswordTries() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        val importer = importerWith(
            storage, books, chapters, ParserRegistry(LockedPdfExtractor())
        )
        val pdfBytes = "%PDF-1.7\n% ws7 locked fixture\n".toByteArray() +
            ByteArray(64) { 1 }
        val candidate = stageOrFail(importer, "locked.pdf", pdfBytes)
        assertEquals(BookFormat.PDF, candidate.format)
        val stagedFile = File(
            File(storage.filesDir, "import-staging"), "${candidate.token}/book.bin"
        )

        val bare = importer.importStaged(candidate)
        assertTrue(bare is AppResult.Err)
        assertEquals(AppError.DrmProtected, (bare as AppResult.Err).error)
        assertTrue("staged file kept for the password try", stagedFile.isFile)

        val wrong = importer.importStaged(candidate, password = "not-the-password")
        assertTrue(wrong is AppResult.Err)
        assertEquals(AppError.DrmProtected, (wrong as AppResult.Err).error)
        assertTrue("staged file kept after a wrong password", stagedFile.isFile)

        val book = importOrFail(importer, candidate, password = "reader-secret")
        assertEquals(2, book.totalChapters)
        // Addendum (A) through the unlock: first-page cover set.
        assertNotNull(book.coverPath)
        assertTrue(storage.resolve(book.coverPath!!).isFile)
        // Success cleans staging up as always.
        assertTrue(!stagedFile.exists())
    }

    @Test fun drmFailureKeepsStagedFileUntilDismissed() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val importer = importerWith(
            storage, FakeBookDao(), FakeChapterDao(), ParserRegistry(null)
        )
        val candidate = stageOrFail(importer, "locked.epub", buildDrmEpub())
        val result = importer.importStaged(candidate)
        assertTrue(result is AppResult.Err)
        assertEquals(AppError.DrmProtected, (result as AppResult.Err).error)
        val stagedFile = File(
            File(storage.filesDir, "import-staging"), "${candidate.token}/book.bin"
        )
        assertTrue(stagedFile.isFile)
        importer.discardStaged(candidate.token)
        assertTrue(!stagedFile.exists())
    }

    private fun buildDrmEpub(): ByteArray {
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
                "META-INF/encryption.xml",
                ("<?xml version=\"1.0\"?>" +
                    "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" " +
                    "xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\">" +
                    "<enc:EncryptedData><enc:CipherData>" +
                    "<enc:CipherReference URI=\"c1.xhtml\"/>" +
                    "</enc:CipherData></enc:EncryptedData></encryption>").toByteArray()
            )
            entry(
                "content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Locked Fixture</dc:title><dc:language>en</dc:language></metadata>
                <manifest>
                <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/></spine></package>""".toByteArray()
            )
            entry(
                "c1.xhtml",
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Locked.</p></body></html>"
                    .toByteArray()
            )
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
            is AppResult.Err -> {
                // TEMP-CAPTURE (v1.5.2 re-scan): print the ParseFailed
                // detail — incl. the caught exception's simple class
                // name in `detail` — to the test log BEFORE asserting,
                // so a recurrence is diagnosable from the archived
                // report/console even before the assertion text is read.
                val err = r.error
                System.err.println(
                    "TEMP-CAPTURE BigTxtStreamingTest parse failed: error=$err " +
                        "detail=${(err as? AppError.ParseFailed)?.detail}"
                )
                throw AssertionError("parse failed: $err")
            }
        }
        assertEquals(chapterCount, parsed.chapters.size)
        assertEquals("Chapter 250", parsed.chapters.last().title)
        val totalWords = parsed.chapters.sumOf { it.wordCount }
        assertTrue("words: $totalWords", totalWords > 2_000_000)
    }
}
