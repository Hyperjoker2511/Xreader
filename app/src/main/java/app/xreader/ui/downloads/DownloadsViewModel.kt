package app.xreader.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.data.db.Book
import app.xreader.data.db.DownloadKind
import app.xreader.data.db.DownloadQueueItem
import app.xreader.data.db.QueueState
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Downloads grouped the way the design shows them: per book. */
data class BookDownloads(
    val book: Book,
    val items: List<DownloadQueueItem>,
    /** Chapters ready on disk / total chapters known. */
    val readyChapters: Int,
    val totalChapters: Int
) {
    val activeItems: List<DownloadQueueItem>
        get() = items.filter {
            it.state == QueueState.QUEUED || it.state == QueueState.RUNNING ||
                it.state == QueueState.PAUSED
        }
    val failedItems: List<DownloadQueueItem>
        get() = items.filter { it.state == QueueState.FAILED }
    val isFinished: Boolean
        get() = activeItems.isEmpty() && items.isNotEmpty()
    val fraction: Float
        get() {
            if (totalChapters <= 0) {
                return if (isFinished) 1f else 0f
            }
            return (readyChapters.toFloat() / totalChapters).coerceIn(0f, 1f)
        }
    val statusLine: String
        get() = when {
            failedItems.isNotEmpty() && activeItems.isEmpty() ->
                "${failedItems.size} couldn't download"
            activeItems.any { it.state == QueueState.PAUSED } && activeItems.none { it.state == QueueState.RUNNING } ->
                "Paused"
            activeItems.isNotEmpty() -> "Downloading…"
            readyChapters > 0 -> "Downloaded • $readyChapters chapters"
            else -> "Finished"
        }
}

data class DownloadsUiState(
    val active: List<BookDownloads> = emptyList(),
    val finished: List<BookDownloads> = emptyList(),
    val wifiOnlyPrefetch: Boolean = true,
    val loading: Boolean = true
) {
    val statusSummary: String
        get() = "${active.size} active • ${finished.size} finished"
}

/**
 * Downloads (PLAN §4, Stitch design): the queue mirror made visible —
 * per-book cards with animated progress, pause/resume/cancel, the
 * Wi-Fi-only prefetch toggle, and "Make EPUB" on finished books.
 */
class DownloadsViewModel(private val container: AppContainer) : ViewModel() {

    private val queueDao = container.database.downloadQueueDao()
    private val bookDao = container.database.bookDao()
    private val chapterDao = container.database.chapterDao()

    val state: StateFlow<DownloadsUiState> = combine(
        queueDao.observeAll(),
        bookDao.observeShelf(),
        container.downloadPrefs.settings
    ) { items, books, settings ->
        val byBook = items.groupBy { it.bookId }
        val grouped = books.mapNotNull { book ->
            val bookItems = byBook[book.id] ?: return@mapNotNull null
            if (bookItems.isEmpty()) return@mapNotNull null
            val chapters = chapterDao.forBook(book.id)
            BookDownloads(
                book = book,
                items = bookItems,
                readyChapters = chapters.count {
                    it.downloadState == app.xreader.data.db.DownloadState.READY
                },
                totalChapters = if (book.totalChapters > 0) book.totalChapters else chapters.size
            )
        }
        DownloadsUiState(
            active = grouped.filter { it.activeItems.isNotEmpty() || it.failedItems.isNotEmpty() },
            finished = grouped.filter {
                it.activeItems.isEmpty() && it.failedItems.isEmpty() && it.items.isNotEmpty()
            },
            wifiOnlyPrefetch = settings.wifiOnlyPrefetch,
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun pause(item: DownloadQueueItem) {
        viewModelScope.launch { container.downloadController.pause(item.id) }
    }

    fun resume(item: DownloadQueueItem) {
        viewModelScope.launch { container.downloadController.resume(item.id) }
    }

    fun cancel(item: DownloadQueueItem) {
        viewModelScope.launch { container.downloadController.cancel(item.id) }
    }

    fun pauseBook(group: BookDownloads) {
        viewModelScope.launch { container.downloadController.pauseAllForBook(group.book.id) }
    }

    fun cancelBook(group: BookDownloads) {
        viewModelScope.launch { container.downloadController.cancelAllForBook(group.book.id) }
    }

    fun retryFailed(group: BookDownloads) {
        viewModelScope.launch {
            group.failedItems.forEach { container.downloadController.resume(it.id) }
        }
    }

    fun setWifiOnlyPrefetch(value: Boolean) {
        viewModelScope.launch { container.downloadPrefs.setWifiOnlyPrefetch(value) }
    }

    /** First item of the group to show its error text, if any. */
    fun firstError(group: BookDownloads): String? =
        group.items.firstOrNull { it.lastErrorDetail != null }?.lastErrorDetail

    companion object {
        /** Whole-book acquisition items read as one download, not N chapters. */
        fun isAcquisition(item: DownloadQueueItem): Boolean =
            item.kind == DownloadKind.CHAPTER && item.chapterId == null

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DownloadsViewModel(container) as T
            }
    }
}
