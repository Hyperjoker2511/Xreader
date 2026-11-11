package app.xreader.data.importer

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.ParserRegistry
import app.xreader.data.parse.PdfProbe
import app.xreader.data.parse.PdfTextExtractor
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.6 targeted checks for §1.5(a) and §1.7(a) — the two
 * coordinator fixes whose plan-named harnesses no workstream
 * owned.
 *
 * §1.5(a): the PDF first-open race. The invariant is ORDER:
 * the completion status must be published before the
 * registration transaction commits — i.e. at the instant the
 * provisional chapter rows become visible, a reader asking
 * "is this book still preparing?" must already get YES.
 * Proven at the importer's own transaction seam: a wrapping
 * inTransaction inspects the status the moment the
 * registration block returns (before the source-file copy,
 * which is where the pre-fix code published). The pre-fix
 * order fails this test; the shipped order passes it.
 *
 * §1.7(a): the chapter-content reads must execute on the
 * storage layer's IO dispatcher, not the caller's. Proven
 * with a recording dispatcher injected into BookStorage:
 * the reads return the right content, the recording
 * dispatcher saw the work, and the missing-file error is
 * unchanged.
 */
class V156RaceAndDispatcherTest {

    @get:Rule val temp = TemporaryFolder()

    private class FastExtractor : PdfTextExtractor {
        override fun probe(file: File): AppResult<PdfProbe> = AppResult.Ok(
            PdfProbe(
                pageCount = 2,
                title = "Race Book",
                author = "Race Author",
                description = null,
                outline = listOf(
                    app.xreader.data.parse.PdfOutlineEntry("Chapter 1", 0),
                    app.xreader.data.parse.PdfOutlineEntry("Chapter 2", 1)
                )
            )
        )

        override fun extractPages(file: File): AppResult<List<String>> =
            AppResult.Ok(
                listOf(
                    "Chapter 1\nThe road wound on through the long " +
                        "valley and the river ran beside it all the " +
                        "way north toward the mountains.\n",
                    "Chapter 2\nAnd then the snow began to fall over " +
                        "the pass, and the whole company slowed to a " +
                        "walk behind the wagons.\n"
                )
            )
    }

    @Test fun statusIsPublishedBeforeRegistrationCommits() {
        val storage = BookStorage(temp.newFolder("files"))
        val books = FakeBookDao()
        val chapters = FakeChapterDao()
        var statusAtCommit: ImportCompletionState.Status? = null
        var commitObserved = false
        val importer = BookImporter(
            storage, ParserRegistry(FastExtractor()), books, chapters,
            inTransaction = { block ->
                block()
                // The instant the registration transaction
                // returns, the provisional rows are visible to
                // every other reader. The status must ALREADY
                // be published at this point.
                val id = books.books.value.single().id
                statusAtCommit = ImportCompletionState.status(id)
                commitObserved = true
            }
        ) { 42_000L }
        val candidate = runBlocking {
            when (
                val r = importer.stage("race.pdf") {
                    ("%PDF-1.7\n% v156 race fixture\n".toByteArray() +
                        ByteArray(64) { 1 }).inputStream()
                }
            ) {
                is AppResult.Ok -> r.value
                is AppResult.Err -> throw AssertionError("stage failed: ${r.error}")
            }
        }
        try {
            val result = runBlocking { importer.importStaged(candidate) }
            assertTrue("import must succeed, got $result", result is AppResult.Ok)
            assertTrue("the transaction seam must have run", commitObserved)
            assertNotNull(
                "status must be published inside the registration " +
                    "transaction, before its rows become visible",
                statusAtCommit
            )
            assertEquals(true, statusAtCommit?.running)
        } finally {
            val id = (books.books.value.singleOrNull())?.id
            if (id != null) ImportCompletionState.clear(id)
        }
    }

    /** A dispatcher that counts the work routed through it. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        private val delegate =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "v156-recording-io")
            }.asCoroutineDispatcher()
        val dispatches = AtomicInteger(0)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }

        fun close() = delegate.close()
    }

    @Test fun chapterReadsExecuteOnTheStorageDispatcher() {
        val recording = RecordingDispatcher()
        try {
            val storage = BookStorage(temp.newFolder("files"), recording)
            val content = ChapterContent(
                blocks = listOf(
                    ChapterBlock.Paragraph.plain("The valley road ran north.")
                )
            )
            runBlocking {
                val written = storage.writeChapterContent(9L, 5L, content)
                assertTrue("write failed: $written", written is AppResult.Ok)
                val read = storage.readChapterContent(9L, 5L)
                assertTrue("read failed: $read", read is AppResult.Ok)
                assertEquals(
                    "The valley road ran north.",
                    (read as AppResult.Ok).value.plainText().trim()
                )
                val writtenT = storage.writeTranslatedContent(9L, 5L, content)
                assertTrue("translated write failed: $writtenT", writtenT is AppResult.Ok)
                val readT = storage.readTranslatedContent(9L, 5L)
                assertTrue("translated read failed: $readT", readT is AppResult.Ok)
                // The missing-file error is the same honest
                // failure as before the dispatcher switch.
                val missing = storage.readChapterContent(9L, 999L)
                assertTrue(
                    "missing chapter must stay an error, got $missing",
                    missing is AppResult.Err
                )
            }
            assertTrue(
                "the reads must have executed on the injected dispatcher",
                recording.dispatches.get() > 0
            )
        } finally {
            recording.close()
        }
    }
}
