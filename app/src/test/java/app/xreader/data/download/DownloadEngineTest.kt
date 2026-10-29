package app.xreader.data.download

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.FakeBookDao
import app.xreader.data.FakeChapterDao
import app.xreader.data.FakeChapterSourceMapDao
import app.xreader.data.FakeDownloadQueueDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterSourceMap
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.DownloadState
import app.xreader.data.db.QueueState
import app.xreader.data.files.BookStorage
import app.xreader.data.net.HttpTransport
import app.xreader.domain.content.ChapterContent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadEngineTest {

    @get:Rule val temp = TemporaryFolder()

    private val bookDao = FakeBookDao()
    private val chapterDao = FakeChapterDao()
    private val queueDao = FakeDownloadQueueDao()
    private val sourceMapDao = FakeChapterSourceMapDao()
    private val delays = mutableListOf<Long>()
    private var now = 1_000L

    private class AllNetworks : NetworkGate {
        var unmetered = true
        override fun onUnmeteredNetwork() = unmetered
        override fun hasNetwork() = true
    }
    private val gate = AllNetworks()

    private lateinit var storage: BookStorage
    private var fileCompletionError: AppError? = null
    /** Contents of files handed to the completion hook (the engine
     * deletes the part file after a successful hand-off). */
    private val completedContents = mutableListOf<String>()

    private fun engine(
        transport: HttpTransport,
        resolver: (DownloadQueueItem) -> ChapterSourceResolver = { item ->
            RoomChapterSourceResolver(sourceMapDao) { UrlChapterFetcher(transport) }
        }
    ): DownloadEngine {
        storage = BookStorage(temp.newFolder("files-${System.nanoTime()}"))
        return DownloadEngine(
            queueDao = queueDao,
            chapterDao = chapterDao,
            bookDao = bookDao,
            sourceMapDao = sourceMapDao,
            storage = storage,
            transport = transport,
            resolverForItem = resolver,
            networkGate = gate,
            settingsProvider = { DownloadSettings(wifiOnlyPrefetch = true, autoPrefetch = true) },
            fileDownloaded = { _, file ->
                completedContents += file.readText()
                fileCompletionError
            },
            delayer = { delays += it },
            jitter = { 1.0 },
            clock = { now }
        )
    }

    private suspend fun bookWithChapter(url: String?): Pair<Long, Long> {
        val bookId = bookDao.insert(
            Book(title = "T", addedAt = 1, updatedAt = 1)
        )
        val chapterId = chapterDao.insert(
            Chapter(
                bookId = bookId, index = 0, title = "Chapter 1",
                remoteId = url, updatedAt = 1
            )
        )
        return bookId to chapterId
    }

    private suspend fun enqueueChapterItem(bookId: Long, chapterId: Long): Long =
        queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = chapterId, kind = DownloadKind.CHAPTER,
                enqueuedAt = now, updatedAt = now
            )
        )

    @Test fun chapterDownloadsAndStoresContent() = runTest {
        val (bookId, chapterId) = bookWithChapter("https://src.test/ch1")
        enqueueChapterItem(bookId, chapterId)
        val transport = FakeTransport { FakeTransport.ok("<html><body><p>Downloaded prose lives here.</p></body></html>") }
        engine(transport).runQueue()
        val item = queueDao.items.value.single()
        assertEquals(QueueState.DONE, item.state)
        val chapter = chapterDao.getById(chapterId)!!
        assertEquals(DownloadState.READY, chapter.downloadState)
        val stored = storage.readChapterContent(bookId, chapterId)
        assertTrue(stored is AppResult.Ok)
        assertTrue((stored as AppResult.Ok).value.plainText().contains("Downloaded prose"))
    }

    @Test fun transientFailuresRetryWithBackoff() = runTest {
        val (bookId, chapterId) = bookWithChapter("https://src.test/flaky")
        enqueueChapterItem(bookId, chapterId)
        val calls = AtomicInteger(0)
        val transport = FakeTransport {
            if (calls.incrementAndGet() < 3) {
                AppResult.Ok(FakeTransport.response(503, "busy"))
            } else {
                FakeTransport.ok("Plain text chapter body with enough words.")
            }
        }
        engine(transport).runQueue()
        val item = queueDao.items.value.single()
        assertEquals(QueueState.DONE, item.state)
        assertEquals(3, item.attempts)
        // Backoff after attempt 1 = 1s, after attempt 2 = 2s (jitter 1.0).
        assertEquals(listOf(1_000L, 2_000L), delays)
    }

    @Test fun permanent404FailsFast() = runTest {
        val (bookId, chapterId) = bookWithChapter("https://src.test/gone")
        enqueueChapterItem(bookId, chapterId)
        val transport = FakeTransport { AppResult.Ok(FakeTransport.response(404, "nope")) }
        engine(transport).runQueue()
        val item = queueDao.items.value.single()
        assertEquals(QueueState.FAILED, item.state)
        assertEquals(1, item.attempts)
        assertEquals(DownloadState.FAILED, chapterDao.getById(chapterId)!!.downloadState)
        assertTrue(item.lastErrorDetail!!.contains("isn't answering"))
    }

    @Test fun retryAfterHeaderRaisesTheDelay() = runTest {
        val (bookId, chapterId) = bookWithChapter("https://src.test/slow")
        enqueueChapterItem(bookId, chapterId)
        val calls = AtomicInteger(0)
        val transport = FakeTransport {
            if (calls.incrementAndGet() == 1) {
                AppResult.Ok(FakeTransport.response(429, "slow down", mapOf("Retry-After" to "7")))
            } else {
                FakeTransport.ok("Chapter body after waiting politely.")
            }
        }
        engine(transport).runQueue()
        assertEquals(QueueState.DONE, queueDao.items.value.single().state)
        assertEquals(listOf(7_000L), delays)
    }

    @Test fun failoverHealsFromAlternateSource() = runTest {
        val (bookId, chapterId) = bookWithChapter("https://dead.test/ch1")
        val mapId = sourceMapDao.upsert(
            ChapterSourceMap(
                chapterId = chapterId, altExtensionId = 0,
                altRemoteId = "Mirror copy|https://mirror.test/ch1",
                matchConfidence = 0.9f
            )
        )
        enqueueChapterItem(bookId, chapterId)
        val transport = FakeTransport { request ->
            if (request.url.contains("dead.test")) {
                AppResult.Ok(FakeTransport.response(404, "gone"))
            } else {
                FakeTransport.ok("Healed chapter text from the mirror source.")
            }
        }
        engine(transport).runQueue()
        val item = queueDao.items.value.single()
        assertEquals(QueueState.DONE, item.state)
        assertEquals(DownloadState.READY, chapterDao.getById(chapterId)!!.downloadState)
        assertTrue(sourceMapDao.maps.value.single { it.id == mapId }.verified)
        val stored = storage.readChapterContent(bookId, chapterId) as AppResult.Ok
        assertTrue(stored.value.plainText().contains("Healed chapter text"))
    }

    @Test fun prefetchWaitsForWifi() = runTest {
        gate.unmetered = false
        val bookId = bookDao.insert(Book(title = "T", addedAt = 1, updatedAt = 1))
        repeat(3) { i ->
            chapterDao.insert(
                Chapter(
                    bookId = bookId, index = i, title = "Ch ${i + 1}",
                    remoteId = "https://src.test/ch${i + 1}", updatedAt = 1
                )
            )
        }
        queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = null, kind = DownloadKind.PREFETCH,
                enqueuedAt = now, updatedAt = now
            )
        )
        val transport = FakeTransport { FakeTransport.ok("text") }
        engine(transport).runQueue()
        // Gate held: the prefetch item never ran, no chapter items spawned.
        assertEquals(QueueState.QUEUED, queueDao.items.value.single().state)
        assertEquals(0, transport.requests.size)

        gate.unmetered = true
        engine(transport).runQueue()
        val states = queueDao.items.value.map { it.state }
        assertTrue(states.all { it == QueueState.DONE })
        assertEquals(3, chapterDao.chapters.value.count { it.downloadState == DownloadState.READY })
    }

    @Test fun fileDownloadResumesFromPartFile() = runTest {
        val bookId = bookDao.insert(
            Book(
                title = "OPDS Book", sourceType = app.xreader.data.db.SourceType.OPDS,
                remoteId = "https://opds.test/book.epub", addedAt = 1, updatedAt = 1
            )
        )
        queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = null, kind = DownloadKind.CHAPTER,
                enqueuedAt = now, updatedAt = now
            )
        )
        val full = "EPUBBYTES-0123456789"
        val transport = FakeTransport { request ->
            assertEquals(10L, request.rangeStart)
            AppResult.Ok(
                HttpTransport.Response(
                    code = 206,
                    headers = mapOf("Content-Length" to (full.length - 10).toString()),
                    body = full.substring(10).byteInputStream(),
                    isPartialContent = true
                )
            )
        }
        val eng = engine(transport)
        // Pre-seed a 10-byte part file, as an interrupted run would leave.
        val part = File(storage.downloadsTempDir(), "queue-1.part")
        part.writeText(full.substring(0, 10))
        eng.runQueue()
        val item = queueDao.items.value.single()
        assertEquals(QueueState.DONE, item.state)
        assertEquals(full, completedContents.single())
    }

    @Test fun fileDownloadCompletionErrorFailsItem() = runTest {
        fileCompletionError = AppError.BadFile("not a real book")
        val bookId = bookDao.insert(
            Book(
                title = "OPDS Book", sourceType = app.xreader.data.db.SourceType.OPDS,
                remoteId = "https://opds.test/book.epub", addedAt = 1, updatedAt = 1
            )
        )
        queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = null, kind = DownloadKind.CHAPTER,
                enqueuedAt = now, updatedAt = now
            )
        )
        val transport = FakeTransport { FakeTransport.ok("garbage") }
        engine(transport).runQueue()
        val item = queueDao.items.value.single()
        assertEquals(QueueState.FAILED, item.state)
        assertTrue(item.lastErrorDetail!!.contains("couldn't be used"))
    }

    @Test fun pausedItemIsLeftAlone() = runTest {
        val (bookId, chapterId) = bookWithChapter("https://src.test/ch1")
        val itemId = enqueueChapterItem(bookId, chapterId)
        val transport = FakeTransport { request ->
            // The user pauses mid-flight: the engine must stop after
            // this attempt without marking DONE or FAILED.
            queueDao.update(queueDao.getById(itemId)!!.copy(state = QueueState.PAUSED))
            FakeTransport.ok("Some chapter text that arrived anyway.")
        }
        engine(transport).runQueue()
        // Chapter was stored by the in-flight attempt; the item stays PAUSED.
        assertEquals(QueueState.PAUSED, queueDao.getById(itemId)!!.state)
    }

    @Test fun backoffCapsAtThirtySeconds() = runTest {
        val eng = engine(FakeTransport { FakeTransport.ok("x") })
        assertEquals(1_000L, eng.backoffMillis(1))
        assertEquals(2_000L, eng.backoffMillis(2))
        assertEquals(16_000L, eng.backoffMillis(5))
        assertEquals(30_000L, eng.backoffMillis(6))
        assertEquals(30_000L, eng.backoffMillis(12))
        assertEquals(60_000L, eng.backoffMillis(1, retryAfterSeconds = 90))
    }
}
