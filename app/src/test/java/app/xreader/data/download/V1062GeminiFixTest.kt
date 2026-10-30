package app.xreader.data.download

import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeDownloadQueueDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.DownloadState
import app.xreader.data.db.QueueState
import app.xreader.data.files.BookStorage
import app.xreader.data.net.HttpTransport
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (Gemini fix round): the verified audit findings,
 * pinned.
 *  - G1: a live read fetches DIRECTLY through the chapter-source
 *    resolver — never the download queue — so a paused queue
 *    cannot stall the reader; the fetch persists the chapter's
 *    downloaded copy, so the next load is local.
 *  - G3: Download-all enqueues as ONE transaction (batch insert
 *    + bulk state flip); a failure mid-batch writes nothing.
 *  - G4: web chapters keep their images — downloaded through the
 *    same transport into the book's content dir; a dead image is
 *    skipped, the text survives.
 */
class V1062GeminiFixTest {

    @get:Rule val temp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val queueDao = FakeDownloadQueueDao()
    private val sourceMapDao = FakeChapterSourceMapDao()

    private fun liveContent() = ChapterContent(
        listOf(ChapterBlock.Paragraph.plain("Live text."))
    )

    private fun countingResolver(
        content: ChapterContent, counter: AtomicInteger
    ): ChapterSourceResolver = object : ChapterSourceResolver {
        override suspend fun candidatesFor(chapter: Chapter): List<ResolvedSource> =
            listOf(
                ResolvedSource(
                    ChapterCandidate(
                        label = "primary source",
                        locator = "https://src.test/ch",
                        bookId = chapter.bookId
                    ),
                    ChapterFetcher {
                        counter.incrementAndGet()
                        FetchOutcome.Success(content)
                    }
                )
            )
    }

    // -- G1 ---------------------------------------------------------------

    @Test
    fun liveLoadSucceedsWhileTheQueueIsPaused() = runTest {
        val storage = BookStorage(temp.newFolder("g1a"))
        val bookId = bookDao.insert(Book(title = "T", addedAt = 1, updatedAt = 1))
        val chapterId = chapterDao.insert(
            Chapter(
                bookId = bookId, index = 0, title = "One",
                remoteId = "https://src.test/ch", updatedAt = 1
            )
        )
        val controller = DownloadController(
            queueDao, chapterDao, bookDao, storage, WorkEnqueuer { }
        ) { 1_000L }
        // The user queued this very chapter, then paused
        // EVERYTHING (Downloads → Pause all) — the old loader
        // polled this queue and timed out.
        controller.enqueueChapter(bookId, chapterId)
        controller.pauseAll()
        val pausedItem = queueDao.items.value.single()
        assertEquals(QueueState.PAUSED, pausedItem.state)

        val fetches = AtomicInteger(0)
        val loader = ChapterTextLoader(
            storage = storage,
            resolver = countingResolver(liveContent(), fetches),
            chapterDao = chapterDao,
            sourceMapDao = sourceMapDao
        )
        val loaded = loader.loadContent(bookId, chapterDao.getById(chapterId)!!)
        assertEquals("Live text.", loaded?.plainText())
        assertEquals(1, fetches.get())
        // The queue was never touched: same single row, still
        // PAUSED; the direct fetch supplied the text.
        assertEquals(1, queueDao.items.value.size)
        assertEquals(
            QueueState.PAUSED, queueDao.getById(pausedItem.id)!!.state
        )
        // v1.0.7.1 (user rule, deliberate pin change): a live
        // READ caches the text but does NOT complete a
        // download — this chapter was QUEUED by the user
        // before the read, and QUEUED is exactly where the
        // read leaves it (never READY; only the engine's
        // Download promotes, instantly from this cache).
        assertEquals(
            DownloadState.QUEUED, chapterDao.getById(chapterId)!!.downloadState
        )
        assertTrue(chapterDao.getById(chapterId)!!.contentPath != null)
    }

    @Test
    fun liveFetchPersistsSoTheSecondLoadIsLocal() = runTest {
        val storage = BookStorage(temp.newFolder("g1b"))
        val chapterId = chapterDao.insert(
            Chapter(
                bookId = 3L, index = 0, title = "One",
                remoteId = "https://src.test/ch", updatedAt = 1
            )
        )
        val fetches = AtomicInteger(0)
        val loader = ChapterTextLoader(
            storage = storage,
            resolver = countingResolver(liveContent(), fetches),
            chapterDao = chapterDao,
            sourceMapDao = sourceMapDao
        )
        val staleRow = chapterDao.getById(chapterId)!!
        assertEquals("Live text.", loader.loadContent(3L, staleRow)?.plainText())
        // Even handed the STALE row again (its contentPath is
        // still null), the second load is local — the first
        // fetch persisted the copy and the loader re-reads the
        // row before fetching.
        assertEquals("Live text.", loader.loadContent(3L, staleRow)?.plainText())
        assertEquals(1, fetches.get())
        assertTrue(loader.storedContent(3L, chapterId) != null)
    }

    // -- G3 ---------------------------------------------------------------

    @Test
    fun enqueueBookWritesTheWholeBatchTogether() = runTest {
        val storage = BookStorage(temp.newFolder("g3a"))
        val bookId = bookDao.insert(Book(title = "T", addedAt = 1, updatedAt = 1))
        val ids = (0 until 5).map { index ->
            chapterDao.insert(
                Chapter(
                    bookId = bookId, index = index,
                    title = "Chapter ${index + 1}",
                    remoteId = "https://src.test/$index", updatedAt = 1
                )
            )
        }
        // One chapter is already downloaded — not part of the batch.
        chapterDao.setContent(
            ids[0], "content/$bookId/${ids[0]}.json", 10, DownloadState.READY
        )
        val controller = DownloadController(
            queueDao, chapterDao, bookDao, storage, WorkEnqueuer { }
        ) { 1_000L }
        controller.enqueueBook(bookId)
        val items = queueDao.items.value.filter { it.bookId == bookId }
        assertEquals(4, items.size)
        assertEquals(ids.drop(1).toSet(), items.map { it.chapterId }.toSet())
        assertTrue(
            items.all {
                it.kind == DownloadKind.CHAPTER && it.state == QueueState.QUEUED
            }
        )
        assertTrue(
            ids.drop(1).all {
                chapterDao.getById(it)!!.downloadState == DownloadState.QUEUED
            }
        )
    }

    @Test
    fun enqueueBookRollsBackWhenTheBatchFails() = runTest {
        val storage = BookStorage(temp.newFolder("g3b"))
        val bookId = bookDao.insert(Book(title = "T", addedAt = 1, updatedAt = 1))
        val ids = (0 until 3).map { index ->
            chapterDao.insert(
                Chapter(
                    bookId = bookId, index = index,
                    title = "Chapter ${index + 1}",
                    remoteId = "https://src.test/$index", updatedAt = 1
                )
            )
        }
        // The bulk state flip fails AFTER the queue rows were
        // inserted — without one transaction the book would be
        // left half-enqueued (rows written, states unflipped).
        val failingChapterDao = object : ChapterDao by chapterDao {
            override suspend fun setDownloadStateForIds(
                ids: List<Long>, state: DownloadState
            ) {
                throw IllegalStateException("disk gone")
            }
        }
        // Room's withTransaction semantics, modelled on the fakes:
        // snapshot on entry, restore on failure.
        val tx: suspend (suspend () -> Unit) -> Unit = { block ->
            val itemsBefore = queueDao.items.value
            val chaptersBefore = chapterDao.chapters.value
            try {
                block()
            } catch (e: Exception) {
                queueDao.items.value = itemsBefore
                chapterDao.chapters.value = chaptersBefore
                throw e
            }
        }
        val controller = DownloadController(
            queueDao = queueDao,
            chapterDao = failingChapterDao,
            bookDao = bookDao,
            storage = storage,
            enqueuer = WorkEnqueuer { },
            inTransaction = tx,
            clock = { 1_000L }
        )
        try {
            controller.enqueueBook(bookId)
            fail("enqueueBook should have surfaced the batch failure")
        } catch (expected: IllegalStateException) {
            // The failure surfaces — and nothing was written.
        }
        assertTrue(queueDao.items.value.isEmpty())
        assertTrue(
            ids.all {
                chapterDao.getById(it)!!.downloadState == DownloadState.NONE
            }
        )
    }

    // -- G4 ---------------------------------------------------------------

    @Test
    fun webChapterKeepsItsImages() = runTest {
        val storage = BookStorage(temp.newFolder("g4a"))
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val html = """
            <html><body><p>The map room.</p><img src="/img/map.png" alt="Map"/>
            <p>After the map.</p></body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            when (request.url) {
                "https://src.test/ch1" -> FakeTransport.ok(html)
                "https://src.test/img/map.png" -> AppResult.Ok(
                    HttpTransport.Response(
                        code = 200,
                        headers = mapOf(
                            "Content-Length" to pngBytes.size.toString()
                        ),
                        body = pngBytes.inputStream(),
                        isPartialContent = false
                    )
                )
                else -> AppResult.Ok(FakeTransport.response(404, "gone"))
            }
        }
        val fetcher = UrlChapterFetcher(transport, storage = storage)
        val outcome = fetcher.fetch(
            ChapterCandidate(locator = "https://src.test/ch1", bookId = 5L)
        )
        assertTrue("fetch failed: $outcome", outcome is FetchOutcome.Success)
        val content = (outcome as FetchOutcome.Success).content
        val image = content.blocks.filterIsInstance<ChapterBlock.Image>().single()
        assertEquals("map.png", image.fileName)
        assertEquals("Map", image.alt)
        val stored = File(storage.contentDir(5L), "img-map.png")
        assertTrue("image bytes were not stored", stored.isFile)
        assertTrue(pngBytes.contentEquals(stored.readBytes()))
        assertTrue(content.plainText().contains("The map room."))
        assertTrue(content.plainText().contains("After the map."))
    }

    @Test
    fun aDeadImageIsSkippedAndTheTextSurvives() = runTest {
        val storage = BookStorage(temp.newFolder("g4b"))
        val html = """
            <html><body><p>Before the picture.</p><img src="/img/gone.png"/>
            <p>After the picture.</p></body></html>
        """.trimIndent()
        val transport = FakeTransport { request ->
            when (request.url) {
                "https://src.test/ch1" -> FakeTransport.ok(html)
                else -> AppResult.Ok(FakeTransport.response(404, "gone"))
            }
        }
        val fetcher = UrlChapterFetcher(transport, storage = storage)
        val outcome = fetcher.fetch(
            ChapterCandidate(locator = "https://src.test/ch1", bookId = 6L)
        )
        assertTrue(outcome is FetchOutcome.Success)
        val content = (outcome as FetchOutcome.Success).content
        assertFalse(
            content.blocks.any { it is ChapterBlock.Image }
        )
        assertTrue(content.plainText().contains("Before the picture."))
        assertTrue(content.plainText().contains("After the picture."))
        assertFalse(File(storage.contentDir(6L), "img-gone.png").exists())
    }
}
