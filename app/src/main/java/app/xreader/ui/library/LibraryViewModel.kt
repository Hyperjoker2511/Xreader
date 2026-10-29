package app.xreader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.db.Book
import app.xreader.data.db.ReadingProgress
import app.xreader.data.importer.BookImporter
import app.xreader.data.importer.ImportCandidate
import app.xreader.di.AppContainer
import java.io.InputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One shelf row: the book plus its reading fraction (0 when unstarted). */
data class ShelfBook(val book: Book, val fraction: Float)

data class LibraryUiState(
    val books: List<ShelfBook> = emptyList(),
    /** v1.0.1: per-book metadata (status/track/new chapters). */
    val metas: Map<Long, app.xreader.data.library.BookMeta> = emptyMap(),
    /** v1.0.1: library customisation (layout/columns/hero/badges). */
    val libraryPrefs: app.xreader.data.library.LibraryState =
        app.xreader.data.library.LibraryState(),
    /** v1.0.1: "Check for updates" is running. */
    val checkingUpdates: Boolean = false,
    /** The most recently read book, for the Continue Reading hero. */
    val continueReading: ShelfBook? = null,
    val continueChapterIndex: Int = 0,
    val loading: Boolean = true,
    /** Phase 6: the reader's pace + identity for the header avatar
     * and the cards' time-to-read lines. */
    val wpm: Double = 180.0,
    val profileLevel: Int = 1,
    val profileName: String = "",
    val avatarFile: String? = null,
    // ── v1.0.2 organisation (R19–R23) ────────────────────────────
    val favourites: Set<Long> = emptySet(),
    val userShelves: List<app.xreader.data.db.Shelf> = emptyList(),
    /** shelfId → member bookIds (manual shelves only). */
    val memberships: Map<Long, Set<Long>> = emptyMap(),
    /** bookId → visible canonical tag slugs. */
    val bookTags: Map<Long, Set<String>> = emptyMap(),
    val downloadCounts: Map<Long, app.xreader.data.db.BookDownloadCount> = emptyMap(),
    val hiddenBuiltins: Set<String> = emptySet(),
    val selectedShelf: String = "all",
    /** R20: the pull gesture's WorkManager refresh is running. */
    val pullRefreshing: Boolean = false,
    /** R22 rec: "For you" — unfinished books carrying a followed
     * tag, computed locally. Empty until a tag is followed. */
    val forYou: List<ShelfBook> = emptyList()
) {
    /** ShelfLogic facts for every visible book (merged-away books
     * never appear: [books] already excludes them at the VM). */
    fun shelfFacts(nowMillis: Long): List<app.xreader.data.library.ShelfLogic.BookFacts> =
        books.map { shelf ->
            val counts = downloadCounts[shelf.book.id]
            val downloaded = counts?.downloaded ?: 0
            val readDownloaded = counts?.readDownloaded ?: 0
            app.xreader.data.library.ShelfLogic.BookFacts(
                bookId = shelf.book.id,
                favourite = shelf.book.id in favourites,
                downloadedChapters = downloaded,
                unreadDownloaded = (downloaded - readDownloaded).coerceAtLeast(0),
                unseenNewChapters = metas[shelf.book.id]?.newChapterCount ?: 0,
                lastUpdatedAt = shelf.book.updatedAt,
                status = metas[shelf.book.id]?.status
                    ?: app.xreader.data.library.ReadingStatus.NONE,
                tagSlugs = bookTags[shelf.book.id] ?: emptySet()
            )
        }
}

/** Import-sheet state for one staged candidate. */
sealed interface CandidateState {
    data class Ready(val candidate: ImportCandidate) : CandidateState
    data class Importing(val candidate: ImportCandidate, val progress: Float?) : CandidateState
    data class Done(val candidate: ImportCandidate, val bookTitle: String) : CandidateState
    data class Failed(val candidate: ImportCandidate, val message: String) : CandidateState
}

/**
 * Library (PLAN §4): the live shelf + the import flow. The importer is
 * the only writer of new books; this ViewModel stages picked files,
 * shows them for confirmation, then imports — plain-words errors via
 * [ErrorText], never a silent failure.
 */
class LibraryViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val importer: BookImporter = container.bookImporter

    private val checkingUpdatesFlow = MutableStateFlow(false)

    private val baseState: StateFlow<LibraryUiState> = combine(
        container.database.bookDao().observeShelf(),
        container.database.readingProgressDao().observeRecent(),
        container.database.profileDao().observe(),
        container.bookMetaPrefs.all,
        container.libraryPrefs.state
    ) { books, progresses, profile, metas, libraryPrefs ->
        val byBook = progresses.associateBy { it.bookId }
        val shelf = books.map { book ->
            ShelfBook(book, byBook[book.id]?.fraction ?: 0f)
        }
        val heroProgress: ReadingProgress? = progresses.firstOrNull()
        val hero = heroProgress?.let { progress ->
            shelf.firstOrNull { it.book.id == progress.bookId }
                ?.copy(fraction = progress.fraction)
        }
        LibraryUiState(
            books = shelf,
            metas = metas,
            libraryPrefs = libraryPrefs,
            continueReading = hero,
            continueChapterIndex = heroProgress?.chapterIndex ?: 0,
            loading = false,
            wpm = container.wpmEstimator.wpm(),
            profileLevel = profile?.level ?: 1,
            profileName = profile?.displayName ?: "",
            avatarFile = profile?.avatarPath
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** v1.0.2 (R21): organisation inputs, combined separately so
     * the main builder stays readable. */
    private data class OrgData(
        val favourites: Set<Long> = emptySet(),
        val userShelves: List<app.xreader.data.db.Shelf> = emptyList(),
        val memberships: Map<Long, Set<Long>> = emptyMap(),
        val bookTags: Map<Long, Set<String>> = emptyMap(),
        val downloadCounts: Map<Long, app.xreader.data.db.BookDownloadCount> = emptyMap(),
        val hiddenBuiltins: Set<String> = emptySet(),
        val selectedShelf: String = "all",
        val pullRefreshing: Boolean = false,
        /** R22 rec: slugs of tags the user follows ("tags you love"). */
        val followedTags: Set<String> = emptySet()
    )

    private val pullRefreshingFlow = MutableStateFlow(false)

    private val orgState: StateFlow<OrgData> = combine(
        container.bookMetaPrefs.favourites,
        container.database.shelfDao().observeAll(),
        container.database.shelfDao().observeMemberships(),
        container.database.tagDao().observeAllBookTags(),
        container.database.chapterDao().observeDownloadCounts(),
        container.libraryPrefs.hiddenBuiltinShelves,
        container.libraryPrefs.selectedShelf,
        pullRefreshingFlow,
        container.database.tagDao().observeFollows()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        OrgData(
            favourites = values[0] as Set<Long>,
            userShelves = values[1] as List<app.xreader.data.db.Shelf>,
            memberships = (values[2] as List<app.xreader.data.db.ShelfBook>)
                .groupBy({ it.shelfId }, { it.bookId })
                .mapValues { it.value.toSet() },
            bookTags = (values[3] as List<app.xreader.data.db.BookTag>)
                .filter { !it.suppressed }
                .groupBy({ it.bookId }, { it.slug })
                .mapValues { it.value.toSet() },
            downloadCounts = (values[4] as List<app.xreader.data.db.BookDownloadCount>)
                .associateBy { it.bookId },
            hiddenBuiltins = values[5] as Set<String>,
            selectedShelf = values[6] as String,
            pullRefreshing = values[7] as Boolean,
            followedTags = (values[8] as List<app.xreader.data.db.TagFollow>)
                .map { it.slug }.toSet()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrgData())

    val state: StateFlow<LibraryUiState> = combine(
        baseState, checkingUpdatesFlow, orgState
    ) { base, checking, org ->
        base.copy(
            checkingUpdates = checking,
            favourites = org.favourites,
            userShelves = org.userShelves,
            memberships = org.memberships,
            bookTags = org.bookTags,
            downloadCounts = org.downloadCounts,
            hiddenBuiltins = org.hiddenBuiltins,
            selectedShelf = org.selectedShelf,
            pullRefreshing = org.pullRefreshing,
            forYou = if (org.followedTags.isEmpty()) {
                emptyList()
            } else {
                base.books.filter { shelfBook ->
                    org.bookTags[shelfBook.book.id].orEmpty()
                        .any { it in org.followedTags } &&
                        shelfBook.fraction < 1f
                }.sortedByDescending { it.book.updatedAt }.take(12)
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // R20: the pull gesture runs unique WorkManager work; its
        // state drives the refresh indicator.
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(container.applicationContext)
                .getWorkInfosForUniqueWorkFlow(
                    app.xreader.data.library.LibraryRefreshWorker.UNIQUE_NAME
                )
                .collect { infos ->
                    pullRefreshingFlow.value = infos.any {
                        it.state == androidx.work.WorkInfo.State.RUNNING ||
                            it.state == androidx.work.WorkInfo.State.ENQUEUED
                    }
                }
        }
    }

    /** R20 pull gesture: enqueue the unique refresh; SILENT —
     * no message, no summary (Q2 addendum). */
    fun pullRefresh() {
        app.xreader.data.library.LibraryRefreshWorker.enqueue(
            container.applicationContext
        )
    }

    /**
     * A: "Check for updates" (v1.0.1) — refresh every source book's
     * chapter list (the sync path honours per-source rate limits +
     * the domain whitelist), count what's new into the cover badges,
     * and — for tracked books — queue a prefetch of the new chapters.
     */
    fun checkForUpdates() {
        viewModelScope.launch {
            if (checkingUpdatesFlow.value) return@launch
            checkingUpdatesFlow.value = true
            try {
                // v1.0.2: the loop lives in LibraryRefresher so the
                // button, the pull gesture's worker and any future
                // scheduled check behave identically.
                val outcome = container.libraryRefresher.refreshAll()
                _messages.emit(
                    if (outcome.totalNew > 0) {
                        "${outcome.booksWithNew} book(s) have new chapters — " +
                            "${outcome.totalNew} new"
                    } else {
                        "Everything is up to date"
                    }
                )
            } finally {
                checkingUpdatesFlow.value = false
            }
        }
    }

    fun setAutoDownloadTracked(enabled: Boolean) {
        viewModelScope.launch { container.libraryPrefs.setAutoDownloadTracked(enabled) }
    }

    /** Opening a book clears its new-chapter badge. */
    fun markSeen(bookId: Long) {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId) ?: return@launch
            val meta = container.bookMetaPrefs.get(bookId)
            container.bookMetaPrefs.set(
                bookId,
                app.xreader.data.library.BookMeta.markSeen(meta, book.totalChapters)
            )
        }
    }

    /** Downloaded size for the remove dialog's honest MB line. */
    suspend fun downloadedSizeBytes(book: Book): Long =
        container.bookStorage.bookFilesSizeBytes(book)

    /** Remove from library (v1.0.1): cancel downloads, one
     * cascading delete, app-side files only on request. */
    fun removeBook(book: Book, deleteFiles: Boolean) {
        viewModelScope.launch {
            runCatching { container.downloadController.cancelAllForBook(book.id) }
            container.database.bookDao().delete(book)
            container.bookMetaPrefs.set(book.id, app.xreader.data.library.BookMeta())
            if (deleteFiles) {
                runCatching { container.bookStorage.deleteBookFiles(book) }
            }
            _messages.emit("Removed “${book.title}” from your library.")
        }
    }

    private val _candidates = MutableStateFlow<List<CandidateState>>(emptyList())
    val candidates: StateFlow<List<CandidateState>> = _candidates

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Stages each picked file; streams are opened by the caller (SAF). */
    fun stagePicked(displayNames: List<String>, openStream: (Int) -> InputStream?) {
        viewModelScope.launch {
            displayNames.forEachIndexed { index, name ->
                when (val result = importer.stage(name) { openStream(index) }) {
                    is AppResult.Ok -> {
                        val candidate = result.value
                        if (candidate.unsupportedReason != null) {
                            _candidates.value = _candidates.value +
                                CandidateState.Failed(candidate, candidate.unsupportedReason)
                        } else {
                            _candidates.value = _candidates.value +
                                CandidateState.Ready(candidate)
                        }
                    }
                    is AppResult.Err -> _messages.emit(ErrorText.displayText(result.error))
                }
            }
        }
    }

    fun importCandidate(candidate: ImportCandidate) {
        viewModelScope.launch {
            replace(candidate, CandidateState.Importing(candidate, null))
            when (val result = importer.importStaged(candidate) { progress ->
                replace(candidate, CandidateState.Importing(candidate, progress))
            }) {
                is AppResult.Ok -> {
                    replace(candidate, CandidateState.Done(candidate, result.value.title))
                    _messages.emit("Added “${result.value.title}” to your library")
                }
                is AppResult.Err ->
                    replace(candidate, CandidateState.Failed(candidate, ErrorText.displayText(result.error)))
            }
        }
    }

    fun dismissCandidate(candidate: ImportCandidate) {
        importer.discardStaged(candidate.token)
        _candidates.value = _candidates.value.filterNot {
            it.candidateToken() == candidate.token
        }
    }

    fun clearFinishedCandidates() {
        _candidates.value = _candidates.value.filter {
            it is CandidateState.Ready || it is CandidateState.Importing
        }
    }

    private fun CandidateState.candidateToken(): String = when (this) {
        is CandidateState.Ready -> candidate.token
        is CandidateState.Importing -> candidate.token
        is CandidateState.Done -> candidate.token
        is CandidateState.Failed -> candidate.token
    }

    private fun replace(candidate: ImportCandidate, next: CandidateState) {
        _candidates.value = _candidates.value.map {
            if (it.candidateToken() == candidate.token) next else it
        }
    }

    /** SAF document display name lookup happens in the screen; the
     * picker there passes plain names + a stream opener per index. */
    fun stagePickedUris(uris: List<Uri>, names: List<String>, opener: (Uri) -> InputStream?) {
        stagePicked(names) { index -> opener(uris[index]) }
    }

    // ─────────────────────────────────────────────────────────────
    // v1.0.2 organisation actions (R21/R22/R23). Every add has a
    // visible remove; shelf deletion never deletes books.
    // ─────────────────────────────────────────────────────────────

    fun setFavourite(bookId: Long, favourite: Boolean) {
        viewModelScope.launch { container.bookMetaPrefs.setFavourite(bookId, favourite) }
    }

    fun selectShelf(ref: String) {
        viewModelScope.launch { container.libraryPrefs.setSelectedShelf(ref) }
    }

    fun setBuiltinShelfHidden(key: String, hidden: Boolean) {
        viewModelScope.launch {
            container.libraryPrefs.setBuiltinShelfHidden(key, hidden)
        }
    }

    fun createShelf(name: String, rule: app.xreader.data.db.SmartRule? = null, ruleValue: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val dao = container.database.shelfDao()
            val order = (dao.all().maxOfOrNull { it.sortOrder } ?: -1) + 1
            val now = System.currentTimeMillis()
            dao.insert(
                app.xreader.data.db.Shelf(
                    name = trimmed,
                    kind = if (rule == null) {
                        app.xreader.data.db.ShelfKind.MANUAL
                    } else {
                        app.xreader.data.db.ShelfKind.SMART
                    },
                    rule = rule,
                    ruleValue = ruleValue ?: "",
                    sortOrder = order,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    fun renameShelf(shelfId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val dao = container.database.shelfDao()
            dao.getById(shelfId)?.let {
                dao.update(it.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun deleteShelf(shelfId: Long) {
        viewModelScope.launch {
            val dao = container.database.shelfDao()
            dao.getById(shelfId)?.let { dao.delete(it) }
            if (state.value.selectedShelf == "user:$shelfId") {
                container.libraryPrefs.setSelectedShelf("all")
            }
        }
    }

    fun moveShelf(shelfId: Long, up: Boolean) {
        viewModelScope.launch {
            val dao = container.database.shelfDao()
            val shelves = dao.all().sortedBy { it.sortOrder }
            val index = shelves.indexOfFirst { it.id == shelfId }
            val swapWith = if (up) index - 1 else index + 1
            if (index < 0 || swapWith !in shelves.indices) return@launch
            val a = shelves[index]
            val b = shelves[swapWith]
            dao.update(a.copy(sortOrder = b.sortOrder))
            dao.update(b.copy(sortOrder = a.sortOrder))
        }
    }

    fun setShelfSort(shelfId: Long, sort: app.xreader.data.library.LibrarySort) {
        viewModelScope.launch {
            val dao = container.database.shelfDao()
            dao.getById(shelfId)?.let { dao.update(it.copy(sort = sort.name)) }
        }
    }

    fun addBooksToShelf(bookIds: Collection<Long>, shelfId: Long) {
        viewModelScope.launch {
            val dao = container.database.shelfDao()
            val now = System.currentTimeMillis()
            bookIds.forEach { bookId ->
                dao.addBook(
                    app.xreader.data.db.ShelfBook(
                        shelfId = shelfId, bookId = bookId, addedAt = now
                    )
                )
            }
        }
    }

    fun removeBookFromShelf(bookId: Long, shelfId: Long) {
        viewModelScope.launch {
            container.database.shelfDao().removeBook(shelfId, bookId)
        }
    }

    // ── Bulk actions (R23): the selection is screen state; the
    // effects are real and reported in plain counts. ──

    fun bulkSetStatus(bookIds: Collection<Long>, status: app.xreader.data.library.ReadingStatus) {
        viewModelScope.launch {
            bookIds.forEach { id ->
                container.bookMetaPrefs.update(id) { it.copy(status = status) }
            }
            _messages.emit("${bookIds.size} book(s) updated")
        }
    }

    fun bulkSetFavourite(bookIds: Collection<Long>, favourite: Boolean) {
        viewModelScope.launch {
            bookIds.forEach { id -> container.bookMetaPrefs.setFavourite(id, favourite) }
        }
    }

    fun bulkDownload(bookIds: Collection<Long>) {
        viewModelScope.launch {
            bookIds.forEach { id -> runCatching { container.downloadController.enqueueBook(id) } }
            _messages.emit("Downloads queued for ${bookIds.size} book(s)")
        }
    }

    fun bulkDeleteDownloads(bookIds: Collection<Long>) {
        viewModelScope.launch {
            var freed = 0L
            bookIds.forEach { id ->
                val book = container.database.bookDao().getById(id) ?: return@forEach
                runCatching { container.downloadController.cancelAllForBook(id) }
                freed += downloadedSizeBytes(book)
                runCatching { container.bookStorage.deleteDownloadedContent(book) }
            }
            _messages.emit(
                "Downloads deleted — freed " +
                    app.xreader.data.files.StorageMeter.formatBytes(freed)
            )
        }
    }

    /** Exact bytes the bulk-remove confirmation must state. */
    suspend fun bulkDownloadedSizeBytes(bookIds: Collection<Long>): Long {
        var total = 0L
        bookIds.forEach { id ->
            container.database.bookDao().getById(id)?.let {
                total += downloadedSizeBytes(it)
            }
        }
        return total
    }

    fun bulkRemoveBooks(books: List<app.xreader.data.db.Book>, deleteFiles: Boolean) {
        // Reuses the single-book removal path (job cancels, seed
        // retire, file forgetting) so bulk cannot drift from it.
        books.forEach { removeBook(it, deleteFiles) }
        viewModelScope.launch { _messages.emit("${books.size} book(s) removed") }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(container) as T
            }
    }
}
