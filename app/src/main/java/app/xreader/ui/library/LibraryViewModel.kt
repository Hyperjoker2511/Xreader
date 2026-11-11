package app.xreader.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.room.withTransaction
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
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
    val forYou: List<ShelfBook> = emptyList(),
    // ── Manga programme (M3): the Novels | Manga split ──────
    /** bookId → media kind for books whose kind ISN'T Novels
     * (absence = Novels; see BookKindResolver). */
    val bookKinds: Map<Long, app.xreader.data.extension.MediaKind> = emptyMap(),
    /** The split's active side (remembered across restarts). */
    val librarySide: app.xreader.data.extension.MediaKind =
        app.xreader.data.extension.MediaKind.TEXT
) {
    /** ShelfLogic facts for every visible book (merged-away books
     * never appear: [books] already excludes them at the VM). */
    fun shelfFacts(nowMillis: Long): List<app.xreader.data.library.ShelfLogic.BookFacts> =
        shelfFactsFor(books, nowMillis)

    /** M3: ShelfLogic facts for a SUBSET of the shelf (one side
     * of the split), same mapping — the Library screen builds
     * its chips and membership from the active side only. */
    fun shelfFactsFor(
        subset: List<ShelfBook>,
        nowMillis: Long
    ): List<app.xreader.data.library.ShelfLogic.BookFacts> =
        subset.map { shelf ->
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
    data class Done(
        val candidate: ImportCandidate,
        val bookTitle: String,
        /** v1.5.4 (WS-7): pictures the file referenced that
         * couldn't be read — the card states the loss in plain
         * words when this is non-zero. */
        val imagesDropped: Int = 0
    ) : CandidateState
    data class Failed(val candidate: ImportCandidate, val message: String) : CandidateState

    /** v1.5.4 (WS-7, addendum C): a PDF that needs its user
     * password — a solvable state, never a dead end. The card
     * offers password entry; [wrongPassword] marks a retry after
     * a password that didn't open the file. The password itself
     * is never part of this state (entered once, used once,
     * never stored). */
    data class PasswordNeeded(
        val candidate: ImportCandidate,
        val wrongPassword: Boolean = false
    ) : CandidateState
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

    /**
     * v1.0.4 (D3): a sidecar flow (DataStore prefs, the profile row)
     * must never wedge the library state. The base combine clears
     * `loading` only when ALL five inputs have emitted; in v1.0.3 a
     * single stalled/failed sidecar left the combined state at its
     * initial value forever — chrome rendered, the book list stayed
     * empty, and the "No books yet" empty state could never show.
     * A failed sidecar now degrades to its default value; the Room
     * flows (shelf, progress) stay raw — if those fail, the
     * database itself is broken and the crash reporter owns it.
     */
    private fun <T> Flow<T>.orDefault(value: T): Flow<T> = catch { emit(value) }

    private val baseState: StateFlow<LibraryUiState> = combine(
        container.database.bookDao().observeShelf(),
        container.database.readingProgressDao().observeRecent(),
        container.database.profileDao().observe().orDefault(null),
        container.bookMetaPrefs.all.orDefault(emptyMap()),
        container.libraryPrefs.state.orDefault(
            app.xreader.data.library.LibraryState()
        )
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
        // v1.5.4 (item 18, finding #2): the metas this state
        // carries are scoped to the SHELF's book ids. Every
        // consumer of state.metas reads it shelf-keyed (per-book
        // badges, the Updates surface's side-book filter, the
        // shelf facts), so scoping changes none of their numbers
        // — but the Novels|Manga switch label SUMS this map
        // (LibrarySplit.newChaptersBySide), and the unscoped map
        // (a meta for every book ever opened, preview and
        // history rows included) poured phantom new-chapter
        // counts into that sum: "Novels · 12121" over a 1-book
        // library. The store itself (BookMetaPrefs) stays whole;
        // its other owners read their own books directly.
        val shelfIds = books.mapTo(HashSet()) { it.id }
        LibraryUiState(
            books = shelf,
            metas = metas.filterKeys { it in shelfIds },
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
        container.bookMetaPrefs.favourites.orDefault(emptySet()),
        container.database.shelfDao().observeAll(),
        container.database.shelfDao().observeMemberships(),
        container.database.tagDao().observeAllBookTags(),
        container.database.chapterDao().observeDownloadCounts(),
        container.libraryPrefs.hiddenBuiltinShelves.orDefault(emptySet()),
        container.libraryPrefs.selectedShelf.orDefault("all"),
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

    /** M3: bookId → media kind (MANGA entries only; absence
     * means Novels), resolved from the shelf's sources whenever
     * the shelf changes. Resolution is stateless (the extension
     * service caches the loads), so a source converted by a
     * pack update re-kinds its books on the next emission. */
    private val bookKindsFlow =
        MutableStateFlow<Map<Long, app.xreader.data.extension.MediaKind>>(emptyMap())

    val state: StateFlow<LibraryUiState> = combine(
        baseState, checkingUpdatesFlow, orgState,
        bookKindsFlow, container.libraryPrefs.librarySide
    ) { base, checking, org, kinds, side ->
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
            bookKinds = kinds,
            librarySide = side,
            // M3: the hero and the For-you row belong to the
            // active side — a Novels page never leads with a
            // manga, nor the other way around.
            continueReading = base.continueReading?.takeIf { hero ->
                app.xreader.data.library.LibrarySplit
                    .kindOf(hero.book.id, kinds) == side
            },
            forYou = if (org.followedTags.isEmpty()) {
                emptyList()
            } else {
                base.books.filter { shelfBook ->
                    app.xreader.data.library.LibrarySplit
                        .kindOf(shelfBook.book.id, kinds) == side &&
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
                    // v1.5.34 (item 1c): the indicator reflects
                    // RUNNING work only. ENQUEUED also matches a
                    // job sitting in WorkManager's retry backoff —
                    // not running, possibly minutes from its next
                    // attempt — and counting it is what made the
                    // Library look like it was "refreshing again"
                    // on later visits after the owner's manual
                    // refresh had finished.
                    pullRefreshingFlow.value = infos.any {
                        it.state == androidx.work.WorkInfo.State.RUNNING
                    }
                }
        }
        // M3: keep the shelf's kind map current — resolved from
        // each book's source config whenever the shelf changes.
        viewModelScope.launch {
            container.database.bookDao().observeShelf().collect { books ->
                bookKindsFlow.value =
                    runCatching {
                        container.bookKindResolver.kindsOf(books)
                    }.getOrDefault(emptyMap())
            }
        }
    }

    /** M3: flip the Library/Explore split to a side; the
     * choice is remembered (LibraryPrefs) and Explore follows
     * the same switch. */
    fun setLibrarySide(side: app.xreader.data.extension.MediaKind) {
        viewModelScope.launch { container.libraryPrefs.setLibrarySide(side) }
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
                // v1.5.26: the button's walk is background work
                // exactly like the worker's (which already wraps
                // itself) — the user may leave the Library while
                // it runs, so its player chapter walks take the
                // details-only gate in SourceBookSync.
                val outcome = container.renderRuntime.asBackgroundWork {
                    container.libraryRefresher.refreshAll()
                }
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

    /** Downloaded size for the remove dialog's honest MB line.
     * v1.2.0 (audit D2): the cached variant — the raw walk of a
     * book's whole file tree ran per dialog/per bulk selection;
     * BookStorage invalidates the cache on its own writes and
     * deletes, and download completion writes through it. */
    suspend fun downloadedSizeBytes(book: Book): Long =
        container.bookStorage.bookFilesSizeBytesCached(book)

    /** v1.5.2 (§15): "Remove book only" is the SOFT tier — the
     * inLibrary flip (§15.1): shelves + hero stop offering the book
     * in the same commit, while progress, downloads, History and
     * the favourite survive (recoverable from History).
     * "Remove book and downloaded chapters" is a FULL book
     * deletion (§15.6): row, files, History record, favourite key
     * and a sync tombstone — gone forever. */
    fun removeBook(book: Book, deleteFiles: Boolean) {
        viewModelScope.launch {
            if (deleteFiles) {
                container.deleteBookFully(book)
            } else {
                container.removeFromLibrarySoft(book)
            }
            _messages.emit("Removed “${book.title}” from your library.")
        }
    }

    /** v1.5.6 (§3.7): the local-book three-option delete from the
     * Library's remove confirm — the same modes the book page
     * offers, through the same data operation. PERMANENT needs a
     * [android.content.Context] for the device file's delete. */
    fun removeLocalBook(
        book: Book,
        mode: app.xreader.data.library.LocalBookDeleteMode,
        context: android.content.Context?
    ) {
        viewModelScope.launch {
            when (container.localBookDeletion.deleteLocalBook(book, mode, context)) {
                is app.xreader.core.error.AppResult.Ok ->
                    _messages.emit("Removed “${book.title}”.")
                is app.xreader.core.error.AppResult.Err ->
                    _messages.emit("That delete didn't finish — the book is still here.")
            }
        }
    }

    private val _candidates = MutableStateFlow<List<CandidateState>>(emptyList())
    val candidates: StateFlow<List<CandidateState>> = _candidates

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Stages each picked file; streams are opened by the caller (SAF).
     * v1.5.6 (§3.4(d)): [documentUris] carries each pick's document
     * URI (string form) alongside its name, so EPUB/PDF imports can
     * register in place against the file where it lives. */
    fun stagePicked(
        displayNames: List<String>,
        openStream: (Int) -> InputStream?,
        documentUris: List<String?> = emptyList()
    ) {
        viewModelScope.launch {
            displayNames.forEachIndexed { index, name ->
                when (val result = importer.stage(
                    name, documentUri = documentUris.getOrNull(index)
                ) { openStream(index) }) {
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

    fun importCandidate(candidate: ImportCandidate, password: String? = null) {
        viewModelScope.launch {
            replace(candidate, CandidateState.Importing(candidate, null))
            var droppedImages = 0
            when (val result = importer.importStaged(
                candidate,
                onProgress = { progress ->
                    replace(candidate, CandidateState.Importing(candidate, progress))
                },
                onDroppedImages = { droppedImages = it },
                password = password
            )) {
                is AppResult.Ok -> {
                    replace(
                        candidate,
                        CandidateState.Done(candidate, result.value.title, droppedImages)
                    )
                    _messages.emit("Added “${result.value.title}” to your library")
                }
                is AppResult.Err -> {
                    // v1.5.4 (WS-7, addendum C): a locked PDF is a
                    // solvable state — offer password entry (a
                    // retry after a wrong password says so). EPUB
                    // locks are vendor DRM, not user passwords, and
                    // keep the plain Failed state: no password
                    // entry unlocks them.
                    if (result.error is app.xreader.core.error.AppError.DrmProtected &&
                        candidate.format == app.xreader.data.db.BookFormat.PDF
                    ) {
                        replace(
                            candidate,
                            CandidateState.PasswordNeeded(
                                candidate, wrongPassword = password != null
                            )
                        )
                    } else {
                        replace(
                            candidate,
                            CandidateState.Failed(candidate, ErrorText.displayText(result.error))
                        )
                    }
                }
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
            // A password-pending card is NOT finished — clearing
            // it would strand the kept staged file with no way
            // back to it.
            it is CandidateState.Ready || it is CandidateState.Importing ||
                it is CandidateState.PasswordNeeded
        }
    }

    private fun CandidateState.candidateToken(): String = when (this) {
        is CandidateState.Ready -> candidate.token
        is CandidateState.Importing -> candidate.token
        is CandidateState.Done -> candidate.token
        is CandidateState.Failed -> candidate.token
        is CandidateState.PasswordNeeded -> candidate.token
    }

    private fun replace(candidate: ImportCandidate, next: CandidateState) {
        _candidates.value = _candidates.value.map {
            if (it.candidateToken() == candidate.token) next else it
        }
    }

    /** SAF document display name lookup happens in the screen; the
     * picker there passes plain names + a stream opener per index.
     * v1.5.6 (§3.4(d)): the URI reaches the importer from here —
     * lasting read permission is taken for each pick first (the
     * picker is OpenMultipleDocuments, whose grants are transient
     * until persisted), so an in-place book stays readable after
     * the pick. A failed persist degrades to a null URI for that
     * file (the retained-copy path files it instead). */
    fun stagePickedUris(uris: List<Uri>, names: List<String>, opener: (Uri) -> InputStream?) {
        val resolver = container.applicationContext.contentResolver
        val documentUris = uris.map { uri ->
            val persisted = runCatching {
                resolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                true
            }.getOrDefault(false)
            if (persisted) uri.toString() else null
        }
        stagePicked(names, { index -> opener(uris[index]) }, documentUris)
    }

    // ─────────────────────────────────────────────────────────────
    // v1.0.2 organisation actions (R21/R22/R23). Every add has a
    // visible remove; shelf deletion never deletes books.
    // ─────────────────────────────────────────────────────────────

    fun setFavourite(bookId: Long, favourite: Boolean) {
        viewModelScope.launch {
            container.bookMetaPrefs.setFavourite(bookId, favourite)
            // v1.5.3 (item 24): favouriting is a membership act —
            // promote a preview row in the same action (see
            // BookDetailViewModel.setFavourite). Unfavouriting
            // never demotes membership.
            if (favourite) container.sourceBookSync.markInLibrary(bookId)
        }
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
            bookIds.forEach { id ->
                container.bookMetaPrefs.setFavourite(id, favourite)
                // v1.5.3 (item 24, bulk path): same membership
                // act as the single-book writers.
                if (favourite) container.sourceBookSync.markInLibrary(id)
            }
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
                // v1.5.7 (scope F smoothness): the recursive
                // per-book file deletion runs on IO — on Main
                // (this scope's dispatcher) a bulk delete over
                // several large books froze the Library for
                // the whole walk.
                kotlinx.coroutines.withContext(container.ioDispatcher) {
                    runCatching {
                        container.bookStorage.deleteDownloadedContent(book)
                    }
                }
                // v1.2.0 (audit D10): the chapter rows must agree
                // with the files just deleted (READY + a
                // contentPath pointing at a gone file is a lie
                // the reader trips over) — this bulk path deleted
                // the files without ever resetting the rows. The
                // reset runs inside ONE Room transaction per
                // book, so a several-thousand-chapter book costs
                // one commit and one Flow emission, not the
                // row-by-row commits the book page's writer of
                // the same shape (BookDetailViewModel, outside
                // this change's files) still pays per chapter.
                val chapterDao = container.database.chapterDao()
                val ready = chapterDao.forBook(id).filter {
                    it.downloadState == app.xreader.data.db.DownloadState.READY
                }
                if (ready.isNotEmpty()) {
                    container.database.withTransaction {
                        ready.forEach { chapter ->
                            chapterDao.update(
                                chapter.copy(
                                    downloadState =
                                        app.xreader.data.db.DownloadState.NONE,
                                    contentPath = null
                                )
                            )
                        }
                    }
                }
                // Files changed on disk above; the cached size
                // was already invalidated by the delete itself.
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
