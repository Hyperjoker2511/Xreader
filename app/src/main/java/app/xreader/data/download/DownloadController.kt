package app.xreader.data.download

import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.DownloadQueueDao
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.DownloadState
import app.xreader.data.db.QueueState
import app.xreader.data.files.BookStorage
import java.io.File

/** Schedules the background drain; WorkManager in production, a fake in tests. */
fun interface WorkEnqueuer {
    fun enqueueDrain()
}

/**
 * User-facing queue control (PLAN §4): enqueue chapter downloads,
 * whole-book downloads and prefetch; pause/resume/cancel. All state
 * lives in the queue rows — this class only writes them (and cleans
 * up partial files on cancel). Where content comes FROM lives on the
 * rows it serves: a chapter's `remoteId`, a book's `remoteId`.
 */
class DownloadController(
    private val queueDao: DownloadQueueDao,
    private val chapterDao: ChapterDao,
    private val bookDao: BookDao,
    private val storage: BookStorage,
    private val enqueuer: WorkEnqueuer,
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun enqueueChapter(bookId: Long, chapterId: Long): Long {
        val now = clock()
        val id = queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = chapterId,
                kind = DownloadKind.CHAPTER, enqueuedAt = now, updatedAt = now
            )
        )
        chapterDao.setDownloadState(chapterId, DownloadState.QUEUED)
        enqueuer.enqueueDrain()
        return id
    }

    /** Enqueues every not-yet-downloaded chapter that has a source. */
    suspend fun enqueueBook(bookId: Long) {
        val now = clock()
        for (chapter in chapterDao.forBook(bookId)) {
            if (chapter.downloadState == DownloadState.READY) continue
            if (chapter.remoteId.isNullOrBlank()) continue
            queueDao.insert(
                DownloadQueueItem(
                    bookId = bookId, chapterId = chapter.id,
                    kind = DownloadKind.CHAPTER, enqueuedAt = now, updatedAt = now
                )
            )
            chapterDao.setDownloadState(chapter.id, DownloadState.QUEUED)
        }
        enqueuer.enqueueDrain()
    }

    suspend fun enqueuePrefetch(bookId: Long) {
        val now = clock()
        queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = null,
                kind = DownloadKind.PREFETCH, enqueuedAt = now, updatedAt = now
            )
        )
        enqueuer.enqueueDrain()
    }

    /** OPDS whole-book acquisition (book.remoteId = the file URL): the
     * file lands, then the engine's completion hook imports it. */
    suspend fun enqueueAcquisition(bookId: Long): Long {
        val now = clock()
        val id = queueDao.insert(
            DownloadQueueItem(
                bookId = bookId, chapterId = null,
                kind = DownloadKind.CHAPTER, enqueuedAt = now, updatedAt = now
            )
        )
        enqueuer.enqueueDrain()
        return id
    }

    suspend fun pause(itemId: Long) {
        val item = queueDao.getById(itemId) ?: return
        if (item.state == QueueState.RUNNING || item.state == QueueState.QUEUED) {
            queueDao.update(item.copy(state = QueueState.PAUSED, updatedAt = clock()))
        }
    }

    suspend fun resume(itemId: Long) {
        val item = queueDao.getById(itemId) ?: return
        if (item.state == QueueState.PAUSED || item.state == QueueState.FAILED) {
            queueDao.update(
                item.copy(
                    state = QueueState.QUEUED, attempts = 0,
                    lastErrorDetail = null, updatedAt = clock()
                )
            )
            enqueuer.enqueueDrain()
        }
    }

    suspend fun cancel(itemId: Long) {
        val item = queueDao.getById(itemId) ?: return
        queueDao.update(item.copy(state = QueueState.CANCELLED, updatedAt = clock()))
        val chapterId = item.chapterId
        if (chapterId != null) {
            val chapter = chapterDao.getById(chapterId)
            if (chapter != null && chapter.downloadState != DownloadState.READY) {
                chapterDao.setDownloadState(chapterId, DownloadState.NONE)
            }
        } else if (item.kind == DownloadKind.CHAPTER) {
            // Whole-book acquisition: drop the partial file.
            File(storage.downloadsTempDir(), "queue-${item.id}.part").delete()
        }
    }

    suspend fun pauseAllForBook(bookId: Long) {
        for (item in queueDao.activeForBook(bookId)) pause(item.id)
    }

    suspend fun cancelAllForBook(bookId: Long) {
        for (item in queueDao.activeForBook(bookId)) cancel(item.id)
    }

    /** Keeps the shelf's "recent" ordering honest after downloads. */
    suspend fun touchBook(bookId: Long) {
        val book = bookDao.getById(bookId) ?: return
        bookDao.update(book.copy(updatedAt = clock()))
    }
}
