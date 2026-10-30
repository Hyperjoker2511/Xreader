package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.FakeAiSummaryDao
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeDownloadQueueDao
import app.xreader.data.FakeReadingProgressDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.ReadingProgress
import app.xreader.data.download.ChapterTextLoader
import app.xreader.data.download.DownloadController
import app.xreader.data.download.WorkEnqueuer
import app.xreader.data.files.BookStorage
import app.xreader.data.prefs.AiSettings
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (F9): the unified AI content path — features load
 * chapter text the way the reader does (stored copy, else the
 * live download-queue fetch); Catch me up consumes the summaries
 * the summary feature writes; verdicts that aren't failures are
 * NeutralVerdicts and render without any failure prefix.
 */
class V1062AiPathTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun textBody(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}"""
    }

    private fun loaderHarness(): Triple<BookStorage, FakeChapterDao, ChapterTextLoader> {
        val storage = BookStorage(tmp.newFolder())
        val chapterDao = FakeChapterDao()
        val queueDao = FakeDownloadQueueDao()
        val controller = DownloadController(
            queueDao = queueDao,
            chapterDao = chapterDao,
            bookDao = FakeBookDao(),
            storage = storage,
            enqueuer = WorkEnqueuer {},
            clock = { 1_000L }
        )
        return Triple(
            storage, chapterDao,
            ChapterTextLoader(storage, controller, chapterDao, queueDao)
        )
    }

    @Test fun storedChapterLoadsThroughTheLoader() = runBlocking {
        val (storage, chapterDao, loader) = loaderHarness()
        val content = ChapterContent(
            listOf(ChapterBlock.Paragraph.plain("The stored chapter text."))
        )
        val saved = storage.writeChapterContent(1L, 11L, content)
        assertTrue(saved is AppResult.Ok)
        val relative = (saved as AppResult.Ok).value
        val chapterId = chapterDao.insert(
            Chapter(bookId = 1L, index = 0, title = "One", updatedAt = 0L)
        )
        chapterDao.setContent(chapterId, relative, 4, DownloadState.READY)
        val chapter = chapterDao.getById(chapterId)!!

        val loaded = loader.loadContent(1L, chapter)
        assertEquals("The stored chapter text.", loaded?.plainText())
    }

    @Test fun liveChapterIsFetchedThroughTheQueue() = runTest {
        val (storage, chapterDao, loader) = loaderHarness()
        val chapterId = chapterDao.insert(
            Chapter(bookId = 1L, index = 0, title = "One", updatedAt = 0L)
        )
        val chapter = chapterDao.getById(chapterId)!!
        assertTrue(chapter.contentPath == null)

        // The "download" lands shortly after the enqueue, the way
        // the real engine would land it.
        val landing = launch {
            delay(500L)
            val saved = storage.writeChapterContent(
                1L, chapterId,
                ChapterContent(
                    listOf(ChapterBlock.Paragraph.plain("Fetched live text."))
                )
            )
            val relative = (saved as AppResult.Ok).value
            chapterDao.setContent(chapterId, relative, 3, DownloadState.READY)
        }
        val loaded = loader.loadContent(1L, chapter)
        landing.join()
        assertEquals("Fetched live text.", loaded?.plainText())
    }

    @Test fun catchUpConsumesSummariesWrittenByTheSummaryPath() = runBlocking {
        val (storage, chapterDao, loader) = loaderHarness()
        var calls = 0
        val transport = FakeTransport {
            calls++
            FakeTransport.ok(
                textBody("""["Event one","Event two","Event three"]""")
            )
        }
        val summaryDao = FakeAiSummaryDao()
        val summarizer = Summarizer(
            client = GeminiClient(transport, keyProvider = { "key" }),
            summaryDao = summaryDao,
            settingsProvider = { AiSettings() }
        )
        // Two stored chapters with real text.
        val chapters = listOf(0, 1).map { index ->
            val id = chapterDao.insert(
                Chapter(
                    bookId = 1L, index = index,
                    title = "Chapter ${index + 1}", updatedAt = 0L
                )
            )
            val saved = storage.writeChapterContent(
                1L, id,
                ChapterContent(
                    listOf(
                        ChapterBlock.Paragraph.plain(
                            "Chapter ${index + 1} prose with things happening. ".repeat(20)
                        )
                    )
                )
            )
            chapterDao.setContent(
                id, (saved as AppResult.Ok).value, 100, DownloadState.READY
            )
            chapterDao.getById(id)!!
        }
        // The summary feature writes chapter 1's summary.
        val text = loader.loadContent(1L, chapters[0])!!.plainText()
        val written = summarizer.chapterSummary(1L, "Star Fall", chapters[0], text)
        assertTrue(written is AppResult.Ok)

        val progressDao = FakeReadingProgressDao()
        progressDao.upsert(
            ReadingProgress(
                bookId = 1L, chapterId = chapters[0].id, chapterIndex = 0,
                charOffset = 0, fraction = 0.95f, updatedAt = 1L
            )
        )
        val catchUp = CatchUp(
            summarizer = summarizer,
            progressDao = progressDao,
            chapterDao = chapterDao,
            loader = loader,
            settingsProvider = { AiSettings() }
        )
        val result = catchUp.recap(
            Book(id = 1L, title = "Star Fall", addedAt = 0L, updatedAt = 0L)
        )
        assertTrue("recap should succeed from the written summary, got $result",
            result is AppResult.Ok)
        result as AppResult.Ok
        assertEquals(1, result.value.chaptersIncluded)
        assertEquals(0, result.value.chaptersSkipped)
        assertTrue(calls >= 2) // chapter summary + arc recap
    }

    @Test fun catchUpOverABlankFrontMatterChapterIsACalmNote() = runBlocking {
        val (storage, chapterDao, loader) = loaderHarness()
        var calls = 0
        val transport = FakeTransport {
            calls++
            FakeTransport.ok(textBody("""["x","y","z"]"""))
        }
        val summarizer = Summarizer(
            client = GeminiClient(transport, keyProvider = { "key" }),
            summaryDao = FakeAiSummaryDao(),
            settingsProvider = { AiSettings() }
        )
        // Index 0: an imported front-matter page — stored, but its
        // text is blank. Index 1: the real first chapter.
        val phantomId = chapterDao.insert(
            Chapter(bookId = 1L, index = 0, title = "Cover", updatedAt = 0L)
        )
        val phantomSaved = storage.writeChapterContent(
            1L, phantomId, ChapterContent(emptyList())
        )
        chapterDao.setContent(
            phantomId, (phantomSaved as AppResult.Ok).value, 0, DownloadState.READY
        )
        val realId = chapterDao.insert(
            Chapter(bookId = 1L, index = 1, title = "Chapter 1", updatedAt = 0L)
        )
        val progressDao = FakeReadingProgressDao()
        progressDao.upsert(
            ReadingProgress(
                bookId = 1L, chapterId = realId, chapterIndex = 1,
                charOffset = 0, fraction = 0.5f, updatedAt = 1L
            )
        )
        val catchUp = CatchUp(
            summarizer = summarizer,
            progressDao = progressDao,
            chapterDao = chapterDao,
            loader = loader,
            settingsProvider = { AiSettings() }
        )
        val result = catchUp.recap(
            Book(id = 1L, title = "Star Fall", addedAt = 0L, updatedAt = 0L)
        )
        assertTrue(result is AppResult.Err)
        val error = (result as AppResult.Err).error
        assertTrue(
            "no-summaries over blank front matter is a verdict, got $error",
            error is AppError.NeutralVerdict
        )
        assertFalse(
            ErrorText.displayText(error).contains("That file couldn't be used")
        )
        assertEquals("no AI call may be spent on blank text", 0, calls)
    }

    @Test fun loreMaterialGateKnowsEmptyFromNonEmpty() {
        val position = ReadingPosition(chapterIndex = 1, charOffset = 0)
        assertFalse(
            LoreChatContext.hasMaterial(
                lore = emptyList(),
                summariesBefore = emptyList(),
                currentChapterText = "Chapter text here.",
                position = position
            )
        )
        // The current chapter's own summary counts as material (F9).
        assertTrue(
            LoreChatContext.hasMaterial(
                lore = emptyList(),
                summariesBefore = listOf(1 to listOf("Happened.")),
                currentChapterText = "",
                position = position
            )
        )
        // Text already read counts too.
        assertTrue(
            LoreChatContext.hasMaterial(
                lore = emptyList(),
                summariesBefore = emptyList(),
                currentChapterText = "Chapter text here.",
                position = ReadingPosition(chapterIndex = 1, charOffset = 5)
            )
        )
    }

    @Test fun neutralVerdictsRenderWithoutAnyFailurePrefix() {
        val verdict = AppError.NeutralVerdict("Already in English — nothing to translate.")
        assertEquals(
            "Already in English — nothing to translate.",
            ErrorText.displayText(verdict)
        )
    }
}
