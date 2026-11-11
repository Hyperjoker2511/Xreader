package app.xreader.data.importer

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.download.ChapterSourceResolver
import app.xreader.data.download.ChapterTextLoader
import app.xreader.data.download.ResolvedSource
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.CommercialEpubFixture
import app.xreader.data.parse.ParserRegistry
import app.xreader.data.parse.PdfProbe
import app.xreader.data.parse.PdfTextExtractor
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.5 (§4): file-first import, proven at the seam the
 * owner felt: with the completion's text extraction BLOCKED
 * mid-flight, the book is already in the Library — row,
 * provisional chapters, kept source file, live completion
 * status — and only then does the completion land its
 * chapters, chapter 1 first, all READY.
 */
class V155FileFirstImportTest {

    @get:Rule val temp = TemporaryFolder()

    private val prose =
        "The road wound on through the long valley and the river " +
            "ran beside it all the way north toward the mountains."

    /** A PDF extractor whose probe answers instantly and whose
     * strip blocks until the test releases it — the owner's
     * 227-page import, slowed down enough to observe. */
    private inner class SlowExtractor : PdfTextExtractor {
        val stripStarted = CountDownLatch(1)
        val releaseStrip = CountDownLatch(1)
        private val pages = listOf(
            "Chapter 1\n$prose\n",
            "Chapter 2\n$prose And then the snow began to fall over the pass.\n"
        )

        override fun probe(file: File): AppResult<PdfProbe> = AppResult.Ok(
            PdfProbe(
                pageCount = 2,
                title = "Slow Book",
                author = "Slow Author",
                description = null,
                outline = listOf(
                    app.xreader.data.parse.PdfOutlineEntry("Chapter 1", 0),
                    app.xreader.data.parse.PdfOutlineEntry("Chapter 2", 1)
                )
            )
        )

        override fun extractPages(file: File): AppResult<List<String>> {
            stripStarted.countDown()
            releaseStrip.await(15, TimeUnit.SECONDS)
            return AppResult.Ok(pages)
        }
    }

    @Test fun bookIsRegisteredBeforeTheCompletionLands() {
        val storage = BookStorage(temp.newFolder("files"))
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        val extractor = SlowExtractor()
        val importer = BookImporter(
            storage, ParserRegistry(extractor), books, chapters
        ) { 42_000L }
        val pdfBytes = "%PDF-1.7\n% v155 slow fixture\n".toByteArray() +
            ByteArray(64) { 1 }
        val candidate = runBlocking {
            when (val r = importer.stage("slow.pdf") { pdfBytes.inputStream() }) {
                is AppResult.Ok -> r.value
                is AppResult.Err -> throw AssertionError("stage failed: ${r.error}")
            }
        }

        var importResult: AppResult<app.xreader.data.db.Book>? = null
        val thread = Thread {
            importResult = runBlocking { importer.importStaged(candidate) }
        }
        thread.start()
        try {
            assertTrue(
                "the completion job must reach its strip",
                extractor.stripStarted.await(15, TimeUnit.SECONDS)
            )
            // The strip is BLOCKED — and the book is already
            // filed: this is the whole point of file-first.
            val book = books.books.value.single()
            assertEquals("Slow Book", book.title)
            assertNotNull(book.filePath)
            assertTrue(storage.resolve(book.filePath!!).isFile)
            val provisional = runBlocking { chapters.forBook(book.id) }
            assertEquals(2, provisional.size)
            assertTrue(provisional.all { it.contentPath == null })
            assertEquals(
                true,
                ImportCompletionState.status(book.id)?.running
            )

            extractor.releaseStrip.countDown()
            thread.join(15_000)
            assertTrue("import thread must finish", !thread.isAlive)
            val result = importResult
            assertTrue("import must succeed, got $result", result is AppResult.Ok)
            val landed = runBlocking { chapters.forBook(book.id) }
            assertEquals(2, landed.size)
            assertTrue(landed.all { it.downloadState == DownloadState.READY })
            assertTrue(landed.all { it.contentPath != null })
            val first = runBlocking {
                storage.readChapterContent(book.id, landed[0].id)
            }
            assertTrue(first is AppResult.Ok)
            assertTrue(
                (first as AppResult.Ok).value.plainText().contains("long valley")
            )
            assertEquals(
                false,
                ImportCompletionState.status(book.id)?.running
            )
        } finally {
            extractor.releaseStrip.countDown()
            thread.join(5_000)
        }
    }

    @Test fun epubFileFirstKeepsItsSourceAndLandsChapters() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        val importer = BookImporter(storage, ParserRegistry(null), books, chapters) { 42_000L }
        val candidate = when (
            val r = importer.stage("commercial.epub") {
                CommercialEpubFixture.bytes().inputStream()
            }
        ) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("stage failed: ${r.error}")
        }
        val book = when (val r = importer.importStaged(candidate)) {
            is AppResult.Ok -> r.value
            is AppResult.Err -> throw AssertionError("import failed: ${r.error}")
        }
        assertEquals("Commercial Fixture", book.title)
        assertNotNull(book.coverPath)
        assertNotNull("the book's own source copy is kept for resume", book.filePath)
        assertTrue(storage.resolve(book.filePath!!).isFile)
        val rows = chapters.forBook(book.id)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.downloadState == DownloadState.READY })
        assertEquals(false, ImportCompletionState.status(book.id)?.running)
    }
}

/**
 * v1.5.5 (§4): the reader-side seam — a not-yet-extracted
 * chapter of a running import reads as Preparing (never a
 * fetch failure), and a stored heading-only chapter the
 * completed job flagged reads as honestly unextractable.
 */
class V155PreparingStateTest {

    @get:Rule val temp = TemporaryFolder()

    private val noSourceResolver = object : ChapterSourceResolver {
        override suspend fun candidatesFor(chapter: Chapter): List<ResolvedSource> =
            emptyList()
    }

    @Test fun pendingChapterIsPreparingWhileTheJobRuns() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val chapters = FakeChapterDao()
        val chapterId = chapters.insert(
            Chapter(bookId = 77, index = 3, title = "Chapter 4", updatedAt = 0)
        )
        val chapter = chapters.getById(chapterId)!!
        val loader = ChapterTextLoader(storage, noSourceResolver, chapters)
        try {
            ImportCompletionState.publish(
                ImportCompletionState.Status(
                    bookId = 77, running = true,
                    totalChapters = 6, landedChapters = 1
                )
            )
            val outcome = loader.ensureStored(77, chapter)
            assertTrue(
                "expected Preparing, got $outcome",
                outcome is ChapterTextLoader.Outcome.Preparing
            )
            // The job finished without this chapter landing a
            // body: the same read is now an ordinary failure,
            // NOT Preparing — the state is live, not latched.
            ImportCompletionState.publish(
                ImportCompletionState.Status(
                    bookId = 77, running = false,
                    totalChapters = 6, landedChapters = 6
                )
            )
            val after = loader.ensureStored(77, chapter)
            assertTrue(after is ChapterTextLoader.Outcome.Failed)
        } finally {
            ImportCompletionState.clear(77)
        }
    }

    @Test fun flaggedHollowChapterIsLabelledNotServed() = runTest {
        val storage = BookStorage(temp.newFolder("files"))
        val chapters = FakeChapterDao()
        val chapterId = chapters.insert(
            Chapter(bookId = 78, index = 3, title = "Chapter 4", updatedAt = 0)
        )
        storage.writeChapterContent(
            78, chapterId,
            ChapterContent(listOf(ChapterBlock.Heading(2, "Chapter 4")))
        )
        chapters.setContent(
            chapterId, storage.chapterContentRelative(78, chapterId),
            0, DownloadState.READY
        )
        val chapter = chapters.getById(chapterId)!!
        val loader = ChapterTextLoader(storage, noSourceResolver, chapters)
        try {
            // Unflagged: the stored chapter serves as always (a
            // divider page of any other provenance is untouched).
            val served = loader.ensureStored(78, chapter)
            assertTrue(served is ChapterTextLoader.Outcome.Stored)
            // Flagged by the completed job: honestly labelled.
            ImportCompletionState.publish(
                ImportCompletionState.Status(
                    bookId = 78, running = false,
                    totalChapters = 6, landedChapters = 6,
                    hollowChapterIndexes = setOf(3)
                )
            )
            val flagged = loader.ensureStored(78, chapter)
            assertTrue(flagged is ChapterTextLoader.Outcome.Failed)
            assertTrue(
                (flagged as ChapterTextLoader.Outcome.Failed).detail
                    .contains("couldn't be extracted")
            )
        } finally {
            ImportCompletionState.clear(78)
        }
    }
}
