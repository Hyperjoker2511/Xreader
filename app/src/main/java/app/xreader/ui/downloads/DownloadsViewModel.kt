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
    val totalChapters: Int,
    /** v1.1.0 (§5.12): real bytes of this book's files on disk. */
    val bytesOnDisk: Long = 0L
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
    val loading: Boolean = true,
    /** v1.0.6.2 (D2): bytes of downloaded book files on disk. */
    val storageBytes: Long = 0L,
    /** v1.1.0 (§5.12): real bytes of the cover cache on disk. */
    val coverCacheBytes: Long = 0L,
    /** v1.1.0 (§5.12): real total storage of the device volume
     * the app's files live on (StatFs); 0 when unreadable. */
    val deviceTotalBytes: Long = 0L,
    /** v1.1.0 (§5.12): true when the current network is metered —
     * the snapshot the "Waiting for Wi-Fi" chip reads, taken when
     * this state was built (the same NetworkGate the download
     * engine consults). */
    val onMeteredNetwork: Boolean = false
) {
    /** v1.0.6.2 (D2): the one plain header line — storage used
     * and how many books it belongs to. */
    val storageLine: String
        get() {
            val books = active.size + finished.size
            return "${formatBytes(storageBytes)} · " +
                if (books == 1) "1 book" else "$books books"
        }

    /** Any item the queue could pause right now. */
    val hasPausable: Boolean
        get() = active.any { group ->
            group.items.any {
                it.state == QueueState.QUEUED || it.state == QueueState.RUNNING
            }
        }

    /** Any paused or failed item a Resume all would restart. */
    val hasResumable: Boolean
        get() = active.any { group ->
            group.items.any {
                it.state == QueueState.PAUSED || it.state == QueueState.FAILED
            }
        }

    val statusSummary: String
        get() = "${active.size} active • ${finished.size} finished"

    /** v1.0.6.2 (RB): failed chapters across every book —
     * drives the "Couldn't download (N)" section. */
    val failedCount: Int
        get() = active.sumOf { it.failedItems.size }
}

/** Plain byte sizes for the D2 line: "812 KB", "1.2 GB". */
fun formatBytes(bytes: Long): String = when {
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> "${bytes / 1_000} KB"
    bytes < 1_000_000_000L ->
        String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    else ->
        String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
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

    /** v1.1.0 (§5.12): bumped after Clean Cache so the storage
     * metering re-reads the disk instead of serving stale bytes. */
    private val storageTick =
        kotlinx.coroutines.flow.MutableStateFlow(0)

    val state: StateFlow<DownloadsUiState> = combine(
        queueDao.observeAll(),
        bookDao.observeShelf(),
        container.downloadPrefs.settings,
        storageTick
    ) { items, books, settings, _ ->
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
                totalChapters = if (book.totalChapters > 0) book.totalChapters else chapters.size,
                // v1.0.6.2 (D2): storage used = the bytes of this
                // book's files on disk (chapter content + the
                // book's own files share the books/<id> tree).
                bytesOnDisk = runCatching {
                    container.bookStorage.booksDir(book.id)
                        .walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                }.getOrDefault(0L)
            )
        }
        val coverCacheBytes = runCatching {
            container.bookStorage.coversDir()
                .walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        }.getOrDefault(0L)
        val deviceTotalBytes = runCatching {
            android.os.StatFs(container.bookStorage.filesDir.path).totalBytes
        }.getOrDefault(0L)
        DownloadsUiState(
            active = grouped.filter { it.activeItems.isNotEmpty() || it.failedItems.isNotEmpty() },
            finished = grouped.filter {
                it.activeItems.isEmpty() && it.failedItems.isEmpty() && it.items.isNotEmpty()
            },
            wifiOnlyPrefetch = settings.wifiOnlyPrefetch,
            loading = false,
            storageBytes = grouped.sumOf { it.bytesOnDisk },
            coverCacheBytes = coverCacheBytes,
            deviceTotalBytes = deviceTotalBytes,
            onMeteredNetwork = runCatching {
                !container.networkGate.onUnmeteredNetwork()
            }.getOrDefault(false)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    /** v1.1.0 (§5.12): Clean Cache — the same rebuildable caches
     * Customise → Storage clears (covers + temp + import staging).
     * Downloaded chapters and book files are never touched here. */
    fun clearCaches() {
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                container.bookStorage.coversDir().listFiles()
                    ?.forEach { it.deleteRecursively() }
                container.bookStorage.downloadsTempDir().listFiles()
                    ?.forEach { it.deleteRecursively() }
                container.bookStorage.importStagingRoot().listFiles()
                    ?.forEach { it.deleteRecursively() }
            }
            storageTick.value += 1
        }
    }

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

    /** v1.0.6.2 (RB): one retry for every failed chapter in
     * the queue, across all books. */
    fun retryAllFailed() {
        viewModelScope.launch {
            state.value.active.forEach { group ->
                group.failedItems.forEach {
                    container.downloadController.resume(it.id)
                }
            }
        }
    }

    /** v1.0.6.2 (D2): the header's Pause all / Resume all. */
    fun pauseAll() {
        viewModelScope.launch { container.downloadController.pauseAll() }
    }

    fun resumeAll() {
        viewModelScope.launch { container.downloadController.resumeAll() }
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
