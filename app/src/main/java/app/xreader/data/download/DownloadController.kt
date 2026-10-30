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
import kotlinx.coroutines.flow.first

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
    /** v1.0.6.2 (Gemini fix round, G3): runs a block of DAO writes
     * as ONE database transaction, so a batch enqueue lands whole
     * or not at all. AppContainer wires Room's withTransaction;
     * tests and other embeddings default to running the block
     * directly. Declared BEFORE [clock] on purpose: existing
     * callers pass the clock as a trailing lambda, and a trailing
     * lambda binds to the LAST function-type parameter. */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit =
        { block -> block() },
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
        // v1.0.6.2 (D3): Download-all is idempotent — a chapter
        // that already has a live queue item (queued, running
        // or paused) keeps it; only genuinely missing chapters
        // get a new item. Re-tapping Download-all, or calling
        // it again after an app restart, continues the book
        // from its first missing chapter instead of restarting
        // it or duplicating work.
        val activeChapterIds = queueDao.activeForBook(bookId)
            .mapNotNull { it.chapterId }
            .toSet()
        val toEnqueue = chapterDao.forBook(bookId).filter { chapter ->
            chapter.downloadState != DownloadState.READY &&
                !chapter.remoteId.isNullOrBlank() &&
                chapter.id !in activeChapterIds
        }
        // v1.0.6.2 (Gemini fix round, G3): the whole batch lands as
        // ONE transaction — a single insertAll for the queue rows
        // plus one bulk state flip — instead of two writes per
        // chapter racing every other database user on a big book.
        // A failure mid-batch now rolls back to nothing written.
        if (toEnqueue.isNotEmpty()) {
            inTransaction {
                queueDao.insertAll(toEnqueue.map { chapter ->
                    DownloadQueueItem(
                        bookId = bookId, chapterId = chapter.id,
                        kind = DownloadKind.CHAPTER,
                        enqueuedAt = now, updatedAt = now
                    )
                })
                chapterDao.setDownloadStateForIds(
                    toEnqueue.map { it.id }, DownloadState.QUEUED
                )
            }
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

    /** v1.0.6.2 (D2): pause EVERYTHING active — the Downloads
     * tab's "Pause all". Same per-item rules as [pause]. */
    suspend fun pauseAll() {
        for (item in queueDao.active()) pause(item.id)
    }

    /** v1.0.6.2 (D2): resume everything paused or failed — the
     * Downloads tab's "Resume all". Same per-item rules as
     * [resume] (failed items get a fresh attempt budget). Reads
     * the whole queue: active() is only QUEUED/RUNNING. */
    suspend fun resumeAll() {
        val resumable = queueDao.observeAll().first().filter {
            it.state == QueueState.PAUSED || it.state == QueueState.FAILED
        }
        for (item in resumable) resume(item.id)
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
