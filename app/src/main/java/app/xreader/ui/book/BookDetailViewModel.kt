package app.xreader.ui.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import app.xreader.data.db.ReadingProgress
import app.xreader.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val progress: ReadingProgress? = null,
    val loading: Boolean = true,
    /** Phase 6: the reader's measured pace — chapter rows show
     * "≈N min" from it (the Phase 4 estimator). */
    val wpm: Double = 180.0,
    // Phase 5: "Story so far" recap (Summarizer.bookSoFar).
    val storyBullets: List<String>? = null,
    val storyBusy: Boolean = false,
    val storyError: String? = null,
    val storyOpen: Boolean = false,
    /** v1.0.1: the chapters section's own load state — loading and
     * failure are VISIBLE, never a silent blank list. */
    val chaptersLoading: Boolean = false,
    val chaptersError: String? = null,
    /** v1.0.5 (B1): this book's source is a link source — the
     * Chapters section may offer its "Go to Repair" door (Repair
     * lives on the source's Details page and works on the link
     * contract). Resolved once from the extension record. */
    val isLinkSource: Boolean = false,
    /** v1.0.6 (W4.3): this book's source reads through the
     * browser view and its last health verdict is NEEDS_SETUP —
     * the Chapters recovery doors offer the site check. */
    val sourceNeedsSiteCheck: Boolean = false,
    /** v1.3.3 (scope S8): the provider's name — it stays
     * visible on the opened book (its badge row), so a book
     * found through provider-grouped search keeps its
     * origin on its page. Resolved once from the source. */
    val providerName: String = "",
    /** v1.0.5 (B1/E2): the Re-check door's honest-drive outcome —
     * busy while the source's test-drive runs, then the verdict
     * in plain words (or null when no check has run). */
    val recheckBusy: Boolean = false,
    val recheckNote: String? = null,
    /** v1.0.1 provider enrichment: null = source doesn't offer it;
     * empty = offered but nothing there yet. */
    /** v1.0.1: per-book status + Track toggle (BookMeta sidecar). */
    val bookMeta: app.xreader.data.library.BookMeta =
        app.xreader.data.library.BookMeta(),
    val reviews: List<app.xreader.extensionapi.SourceReview>? = null,
    val comments: List<app.xreader.extensionapi.SourceComment>? = null,
    val related: List<app.xreader.extensionapi.SourceBook>? = null,
    val enrichmentLoaded: Boolean = false,
    // ── v1.0.2 (R05/R11/R12/R22 + sweep) ─────────────────────────
    val favourite: Boolean = false,
    /** Visible (non-suppressed) tag rows for this book. */
    val tags: List<app.xreader.data.db.BookTag> = emptyList(),
    /** "Next chapter expected…" line, or null when the rhythm
     * isn't knowable yet (UpdateRhythm says so honestly). */
    val expectedNextLabel: String? = null,
    /** Cover palette for the ambient backdrop (R05). */
    val coverPalette: app.xreader.ui.theme.CoverPalette? = null,
    /** Book-colours scope covers this page (all scopes do). */
    val backdropEnabled: Boolean = true,
    /** Blurred backdrop on STANDARD/FULL tiers; a static tint on
     * MINIMAL (PerformancePolicy decides, never the composable). */
    val backdropBlurred: Boolean = true,
    /** Same work elsewhere in the library (merge candidates and
     * already-merged copies of THIS book). */
    val duplicates: List<Book> = emptyList(),
    val mergedCopies: List<Book> = emptyList(),
    /** Cross-source copies found by "Other sources" (R11). */
    val otherSources: List<OtherSourceCandidate> = emptyList(),
    val otherSourcesState: OtherSourcesState = OtherSourcesState.NOT_LOADED,
    /** The deliberate-switch preview (R11): shown before moving. */
    val switchPreview: app.xreader.data.extension.SwitchPreview? = null,
    val switchCandidate: OtherSourceCandidate? = null,
    // ── v1.0.2 (R26): AI "More like this" ────────────────────────
    /** NO_KEY shows the Set-up-Gemini line instead of a dead card. */
    val aiSuggestionsState: AiSuggestionsState = AiSuggestionsState.NO_KEY,
    val aiSuggestions: List<String> = emptyList(),
    // ── v1.0.3 (AI hub): AI tag suggestions awaiting the user's word ──
    /** Model-proposed tags, shown as accept/remove chips. Nothing
     * here is a tag yet — accepting writes it (INFERRED origin). */
    val tagSuggestions: List<app.xreader.data.ai.TagSuggestion> = emptyList(),
    val tagSuggestBusy: Boolean = false,
    val tagSuggestNote: String? = null,
    /** v1.3.2 (scope A2): the session hand-off the current
     * chapters error calls for (a site check to solve, a
     * sign-in to make), or null when the error is ordinary.
     * The Chapters card renders it as its primary door. */
    val sessionNeed: app.xreader.data.extension.SessionNeed.Kind? = null,
    /** v1.3.2 (item 77): the chapters failure is "this source
     * is switched off" — the card offers one tap to turn it
     * back on and retry. */
    val sourceOff: Boolean = false,
    /** v1.3.2 (item 79): the stored index of the book's
     * identity-FIRST chapter — the Start Reading target when
     * there is no progress. Computed from the chapters' own
     * numbers, never assumed to be stored index 0 (a
     * newest-first store keeps chapter 1 at its END). */
    val startChapterIndex: Int = 0
) {
    val downloadedCount: Int get() = chapters.count { it.downloadState == DownloadState.READY }
    /** v1.3.2 (item 79): Continue follows saved progress
     * (already identity-anchored); Start follows identity —
     * the chapter whose own number is 1. */
    val continueChapterIndex: Int get() = progress?.chapterIndex ?: startChapterIndex
    /** v1.0.4 (D14): non-null blocks the Read action — the loaded
     * chapter list is empty, so reading could only fail downstream
     * in the reader ("isn't in the book anymore"). The reason is
     * shown right under the button instead. */
    val readBlockedReason: String?
        get() = ChapterReadGuard.blockedReason(
            isExtensionBook = book?.sourceType ==
                app.xreader.data.db.SourceType.EXTENSION,
            chapterCount = chapters.size,
            pageLoading = loading,
            chaptersLoading = chaptersLoading
        )
    val hasUndownloadedChapters: Boolean
        get() = chapters.any {
            it.downloadState != DownloadState.READY && !it.remoteId.isNullOrBlank()
        }
}

/**
 * The book page's Read guard (v1.0.4, D14): a source book whose
 * loaded chapter list is EMPTY must not offer a working Read
 * button — tapping it could only land in the reader's last-resort
 * "isn't in the book anymore" error. The guard applies once the
 * page has settled (not while the book or its chapter list is
 * still loading) and only to source books; local books' empty
 * lists are an import fact, not a broken source. Pure, so the
 * truth table is unit-tested.
 */
object ChapterReadGuard {
    fun blockedReason(
        isExtensionBook: Boolean,
        chapterCount: Int,
        pageLoading: Boolean,
        chaptersLoading: Boolean
    ): String? {
        if (!isExtensionBook || chapterCount > 0) return null
        if (pageLoading || chaptersLoading) return null
        return "No chapters found — this source may be broken. " +
            "Try Repair on the source's Details page."
    }
}

enum class OtherSourcesState { NOT_LOADED, LOADING, LOADED, FAILED }

/** v1.0.2 (R26): AI suggestion lifecycle on the book page. */
enum class AiSuggestionsState { NO_KEY, NOT_ASKED, LOADING, READY, FAILED }

/** One other source's copy of this work (R11), ranked best-first. */
data class OtherSourceCandidate(
    val extensionId: Long,
    val sourceId: String,
    val sourceName: String,
    val book: app.xreader.extensionapi.SourceBook,
    val inLibraryBookId: Long? = null,
    val downloadedCount: Int = 0
)

/**
 * Book Detail (Stitch design, PLAN §4): cover header, Start reading /
 * Download actions, synopsis, and the chapter list with per-chapter
 * state. The basic reader opens from here.
 */
class BookDetailViewModel(
    private val container: AppContainer,
    private val bookId: Long
) : ViewModel() {

    private val storyLocal = kotlinx.coroutines.flow.MutableStateFlow(BookDetailUiState())

    private val baseDetailState: StateFlow<BookDetailUiState> = combine(
        container.database.bookDao().observeById(bookId),
        container.database.chapterDao().observeForBook(bookId),
        container.database.readingProgressDao().observeForBook(bookId),
        storyLocal,
        container.bookMetaPrefs.all
    ) { book, chapters, progress, story, metas ->
        // v1.3.1 (V120-CHECK 55): progress keys on chapter
        // IDENTITY. A stored pointer whose chapterId no longer
        // resolves in the current list (the list changed shape
        // — junk rows removed, a re-walk) must never render as
        // a position: that is how a book that was never read
        // acquired a "Chapter 3241" Continue pill. Resolved
        // pointers are re-anchored to the row's CURRENT index;
        // dangling pointers show as no progress at all.
        val resolvedProgress = progress?.let { p ->
            val anchor = chapters.firstOrNull { it.id == p.chapterId }
            when {
                anchor != null -> p.copy(chapterIndex = anchor.index)
                chapters.isNotEmpty() -> null
                else -> p
            }
        }
        BookDetailUiState(
            // v1.3.1 (item 55): a source book's content arrives
            // as web pages — presenting its format as the raw
            // enum name "UNKNOWN" told the user nothing in the
            // loudest place. Display copy only; the stored row
            // (and every format-keyed reader) is untouched.
            book = book?.let {
                if (it.sourceType ==
                    app.xreader.data.db.SourceType.EXTENSION &&
                    it.localFormat ==
                    app.xreader.data.db.BookFormat.UNKNOWN
                ) {
                    it.copy(
                        localFormat = app.xreader.data.db.BookFormat.HTML
                    )
                } else {
                    it
                }
            },
            chapters = chapters,
            progress = resolvedProgress,
            loading = false,
            wpm = container.wpmEstimator.wpm(),
            storyBullets = story.storyBullets,
            storyBusy = story.storyBusy,
            storyError = story.storyError,
            storyOpen = story.storyOpen,
            providerName = story.providerName,
            bookMeta = metas[bookId] ?: app.xreader.data.library.BookMeta(),
            chaptersLoading = story.chaptersLoading,
            chaptersError = story.chaptersError,
            // v1.3.2 (scope A2 + item 79): the hand-off the
            // chapters error calls for, and the identity-first
            // chapter's stored index for Start Reading.
            sessionNeed = story.sessionNeed,
            sourceOff = story.sourceOff,
            startChapterIndex =
                app.xreader.domain.reader.ChapterDisplayOrder
                    .firstChapter(chapters)?.index ?: 0,
            isLinkSource = story.isLinkSource,
            sourceNeedsSiteCheck = story.sourceNeedsSiteCheck,
            recheckBusy = story.recheckBusy,
            recheckNote = story.recheckNote,
            reviews = story.reviews,
            comments = story.comments,
            related = story.related,
            enrichmentLoaded = story.enrichmentLoaded,
            coverPalette = story.coverPalette,
            otherSources = story.otherSources,
            otherSourcesState = story.otherSourcesState,
            switchPreview = story.switchPreview,
            switchCandidate = story.switchCandidate,
            aiSuggestionsState = story.aiSuggestionsState,
            aiSuggestions = story.aiSuggestions,
            tagSuggestions = story.tagSuggestions,
            tagSuggestBusy = story.tagSuggestBusy,
            tagSuggestNote = story.tagSuggestNote,
            expectedNextLabel = app.xreader.data.library.UpdateRhythm.estimate(
                chapters.map { it.updatedAt }, System.currentTimeMillis()
            )?.let { estimate ->
                app.xreader.data.library.UpdateRhythm.label(
                    estimate, System.currentTimeMillis()
                )
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState())

    /** v1.0.2: organisation/appearance extras, combined on top so
     * the base builder above stays as it was. */
    val state: StateFlow<BookDetailUiState> = combine(
        baseDetailState,
        container.bookMetaPrefs.favourites,
        container.database.tagDao().observeForBook(bookId),
        container.database.bookDao().observeAllBooks(),
        container.appearancePrefs.state
    ) { base, favourites, tagRows, allBooks, appearance ->
        val book = base.book
        val policy = app.xreader.data.prefs.PerformancePolicy.resolve(
            appearance.performanceMode, container.totalRamMb(), appearance.coverGlow
        )
        base.copy(
            favourite = bookId in favourites,
            tags = tagRows.filter { !it.suppressed },
            backdropEnabled = true,
            backdropBlurred = policy.blurredBackdropAllowed,
            duplicates = if (book == null) {
                emptyList()
            } else {
                allBooks.filter { other ->
                    other.id != bookId && other.mergedIntoId == null &&
                        app.xreader.data.extension.WorkMatcher.sameWork(
                            book.title, book.author, other.title, other.author
                        )
                }
            },
            mergedCopies = if (book == null) {
                emptyList()
            } else {
                allBooks.filter { it.mergedIntoId == bookId }
            }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState())

    /** The engine chip's text (v1.0.3), shown on this page's AI
     * surfaces: which provider will answer, before anything is spent. */
    val engineLabel: StateFlow<String> = container.aiHub.chipLabel
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            "AI · Automatic"
        )

    /** "Catch me up" (v1.0.3; was "Story so far"): the recap bounded
     * by the saved READING POSITION — a chapter still being read
     * can't spoil its own ending — filling in summaries for chapters
     * whose text is on this device first. */
    fun storySoFar() {
        storyLocal.value = storyLocal.value.copy(
            storyOpen = true, storyBusy = true, storyError = null
        )
        viewModelScope.launch {
            val book = state.value.book
            if (book == null) {
                storyLocal.value = storyLocal.value.copy(
                    storyBusy = false,
                    storyError = "This book isn't available."
                )
                return@launch
            }
            when (val result = container.catchUp.recap(book)) {
                is app.xreader.core.error.AppResult.Ok -> {
                    storyLocal.value = storyLocal.value.copy(
                        storyBusy = false,
                        storyBullets = result.value.summary.bullets
                    )
                    if (result.value.chaptersSkipped > 0) {
                        _messages.tryEmit(
                            "The recap covers the chapters on this device — " +
                                "${result.value.chaptersSkipped} earlier " +
                                "chapter(s) aren't downloaded, so they're " +
                                "not in it."
                        )
                    }
                }
                is app.xreader.core.error.AppResult.Err -> storyLocal.value =
                    storyLocal.value.copy(
                        storyBusy = false,
                        storyError = app.xreader.core.error.ErrorText
                            .displayText(result.error)
                    )
            }
        }
    }

    fun dismissStory() {
        storyLocal.value = storyLocal.value.copy(storyOpen = false)
    }

    fun downloadAllChapters() {
        viewModelScope.launch {
            // v1.0.8: on a partially loaded book, "all" must
            // mean all — finish loading the chapter list first
            // (the one explicit complete-the-list action), then
            // queue every chapter it found.
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            container.sourceBookSync.markInLibrary(bookId)
            if (!book.chapterListComplete) {
                container.sourceBookSync.completeChapters(book)
            }
            container.downloadController.enqueueBook(bookId)
        }
    }

    private var edgeJob: kotlinx.coroutines.Job? = null

    /** v1.0.8: the chapter list composed a row near the loaded
     * edge — grow the list ahead of the user (and only then;
     * a book he never scrolls into never loads further). */
    fun onChapterNearEdge(index: Int) {
        val current = state.value
        val book = current.book ?: return
        if (book.chapterListComplete) return
        if (index < current.chapters.size -
            app.xreader.data.extension.SourceBookSync.EDGE_TRIGGER
        ) {
            return
        }
        launchGrow(book, current.chapters.size +
            app.xreader.data.extension.SourceBookSync.EDGE_TARGET)
    }

    /** v1.0.8 (recommendation): searching a chapter NUMBER
     * past the loaded edge loads forward until the list
     * reaches it — the search must not silently miss a
     * chapter just because it hasn't loaded yet. */
    fun onChapterNumberSearch(number: Int) {
        val current = state.value
        val book = current.book ?: return
        if (book.chapterListComplete) return
        if (number <= current.chapters.size) return
        launchGrow(book, number + 10)
    }

    private fun launchGrow(book: app.xreader.data.db.Book, target: Int) {
        if (edgeJob?.isActive == true) return
        edgeJob = viewModelScope.launch {
            container.sourceBookSync.ensureChapters(book, target)
        }
    }

    /** v1.0.8 (recommendation): the manual complete-the-list
     * door on a partially loaded book — for when he wants to
     * browse or search the WHOLE list now, without
     * downloading anything. Mirrors Refresh's state handling. */
    fun loadFullChapterList() {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION) {
                return@launch
            }
            if (book.chapterListComplete) return@launch
            storyLocal.value = storyLocal.value.copy(
                chaptersLoading = true, chaptersError = null
            )
            when (
                val result = container.sourceBookSync.completeChapters(book)
            ) {
                is app.xreader.core.error.AppResult.Ok -> {
                    storyLocal.value = storyLocal.value.copy(
                        chaptersLoading = false
                    )
                    _messages.tryEmit(
                        "Full chapter list loaded — " +
                            "${result.value.totalChapters} chapters"
                    )
                }
                is app.xreader.core.error.AppResult.Err -> {
                    noteChaptersError(result.error)
                }
            }
        }
    }

    /** v1.0.8: the auto-load for a progressive book whose first
     * chunk didn't land at open — retry the CHUNK, not a full
     * refresh (the whole point is not loading everything). */
    private fun loadFirstChunk() {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION) {
                return@launch
            }
            storyLocal.value = storyLocal.value.copy(
                chaptersLoading = true, chaptersError = null
            )
            when (
                val result = container.sourceBookSync.ensureChapters(
                    book,
                    app.xreader.data.extension.SourceBookSync.FIRST_CHUNK_MIN
                )
            ) {
                is app.xreader.core.error.AppResult.Ok -> {
                    storyLocal.value = storyLocal.value.copy(
                        chaptersLoading = false
                    )
                }
                is app.xreader.core.error.AppResult.Err -> {
                    noteChaptersError(result.error)
                }
            }
        }
    }

    fun downloadChapter(chapter: Chapter) {
        viewModelScope.launch {
            // v1.3.1 (item 45): downloading is an explicit
            // "this book is mine" act — a preview row becomes
            // a Library member.
            container.sourceBookSync.markInLibrary(bookId)
            container.downloadController.enqueueChapter(bookId, chapter.id)
        }
    }

    private var enrichmentTried = false

    /** v1.0.1: load provider enrichment (reviews/comments/related)
     * once, in the background — what's already shown never blanks. */
    fun loadEnrichment() {
        if (enrichmentTried) return
        enrichmentTried = true
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId) ?: return@launch
            if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION) {
                storyLocal.value = storyLocal.value.copy(enrichmentLoaded = true)
                return@launch
            }
            val enrichment = runCatching {
                container.sourceBookSync.enrichment(book)
            }.getOrNull()
            storyLocal.value = storyLocal.value.copy(
                enrichmentLoaded = true,
                reviews = enrichment?.reviews,
                comments = enrichment?.comments,
                related = enrichment?.related
            )
        }
    }

    /** "More like this" tap: open the related book through the same
     * source-book path as the catalogue (identity-safe by construction). */
    fun openRelated(related: app.xreader.extensionapi.SourceBook, onOpened: (Long) -> Unit) {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId) ?: return@launch
            val extensionId = book.extensionId ?: return@launch
            val (sourceId, _) = app.xreader.data.extension.SourceBookIds
                .decode(book.remoteId) ?: return@launch
            val handle = when (
                val found = container.extensionService.source(extensionId, sourceId)
            ) {
                is app.xreader.core.error.AppResult.Ok -> found.value
                is app.xreader.core.error.AppResult.Err -> return@launch
            }
            when (val opened = container.sourceBookSync.openBook(handle, related)) {
                is app.xreader.core.error.AppResult.Ok -> onOpened(opened.value.id)
                is app.xreader.core.error.AppResult.Err ->
                    _messages.tryEmit("That book couldn't be opened.")
            }
        }
    }

    private var autoLoadTried = false

    init {
        // v1.0.6.2 (F6a): a local book with an empty synopsis whose
        // file is still on the phone gets ONE metadata re-read —
        // books imported before descriptions were mapped heal
        // themselves on open, no re-import needed.
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            if (book.sourceType != app.xreader.data.db.SourceType.LOCAL_FILE ||
                book.description.isNotBlank()
            ) {
                return@launch
            }
            val heal = container.localSynopsisHeal
            val file = heal.candidateFile(book) ?: return@launch
            val found = kotlinx.coroutines.withContext(
                container.defaultDispatcher
            ) {
                runCatching { heal.readDescription(book, file) }.getOrNull()
            } ?: return@launch
            val current = container.database.bookDao().getById(bookId)
                ?: return@launch
            if (current.description.isBlank()) {
                container.database.bookDao().update(
                    current.copy(description = found)
                )
            }
        }
        // v1.0.1: a source book with zero local chapters loads them
        // on open — the user must never stare at an empty list for a
        // book the app itself verified has chapters.
        viewModelScope.launch {
            state.collect { current ->
                val book = current.book ?: return@collect
                if (!autoLoadTried && !current.loading &&
                    current.chapters.isEmpty() &&
                    book.sourceType == app.xreader.data.db.SourceType.EXTENSION
                ) {
                    autoLoadTried = true
                    // v1.0.8: a partially loaded book retries
                    // its first chunk; a complete book keeps
                    // the full auto-refresh it always had.
                    if (!book.chapterListComplete) {
                        loadFirstChunk()
                    } else {
                        refreshChapters()
                    }
                }
            }
        }
        // R26: prime the AI suggestions surface once per book
        // (key presence + any cached answer; never an API call).
        viewModelScope.launch {
            state.first { it.book != null }
            primeAiSuggestionsState(state.value.book!!)
        }
        // v1.3.3 (scope S8): resolve the provider's name once —
        // it stays visible on the book's page.
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            val extId = book.extensionId ?: return@launch
            val sourceId = app.xreader.data.extension.SourceBookIds
                .decode(book.remoteId)?.first
            val handles = container.extensionService
                .sourcesOf(extId).getOrNull().orEmpty()
            val name = handles
                .firstOrNull { it.source.id == sourceId }
                ?.source?.name
                ?: handles.firstOrNull()?.source?.name
                ?: return@launch
            storyLocal.value = storyLocal.value.copy(providerName = name)
        }
        // v1.3.3 (scope S4): the one-time solve/sign-in screen
        // completed for THIS book's source → reload the book's
        // chapters against the fresh session, automatically.
        // The hand-off returns the user to a working page, not
        // to the same wall they left.
        viewModelScope.launch {
            container.sessionSavedEvents.collect { key ->
                val book = container.database.bookDao()
                    .getById(bookId) ?: return@collect
                val extId = book.extensionId ?: return@collect
                if (key ==
                    app.xreader.data.extension.ExtensionHttpClient
                        .sessionKeyFor(extId)
                ) {
                    refreshChapters()
                }
            }
        }
        // v1.0.5 (B1): resolve once whether this book's source is
        // a link source — it decides the Chapters section's
        // "Go to Repair" door (Repair works on link contracts).
        // v1.0.6 (W4.3): the same resolution decides the site-
        // check door — a browser-view source whose health verdict
        // is NEEDS_SETUP almost always wants its human check.
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            val extensionId = book.extensionId ?: return@launch
            val extension = container.database.installedExtensionDao()
                .getById(extensionId) ?: return@launch
            val handle = container.extensionService
                .sourcesOf(extensionId).getOrNull()?.firstOrNull()
            // v1.3.2 (scope A2): cache the config for the
            // session hand-off classifier.
            bookConfig = handle?.let {
                container.extensionService.declarativeConfigOf(it)
            }
            val health = handle?.let {
                container.sourcePrefs.sourceHealth.first()[
                    app.xreader.data.extension.SourceHealthRecord.key(
                        extensionId, it.source.id
                    )
                ]
            }
            storyLocal.value = storyLocal.value.copy(
                // v1.1.0 (Fix 5): the Repair door follows the
                // ONE rebuildability helper, not the link.
                // prefix — Live rows and single-config repo
                // rows can be repaired too (§6.3/§6.4).
                isLinkSource =
                    app.xreader.data.extension.SourceRebuildability
                        .isRebuildable(
                            app.xreader.data.extension.SourceRebuildability
                                .classify(
                                    extension,
                                    runCatching {
                                        container.extensionStore.readText(
                                            container.extensionStore
                                                .definitionFile(
                                                    extension.packageId
                                                )
                                        )
                                    }.getOrNull()
                                )
                        ),
                sourceNeedsSiteCheck =
                    handle?.let {
                        container.extensionService.declarativeConfigOf(it)
                    }?.needsRendering == true &&
                        health?.state ==
                        app.xreader.data.extension.SourceHealthState.NEEDS_SETUP
            )
        }
    }

    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 4
    )
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    /** Surfaces a one-off message (export results) on the host snackbar. */
    fun notifyMessage(text: String) {
        _messages.tryEmit(text)
    }

    /** Phase 6: Obsidian/Notion-flavoured Markdown of this book's
     * highlights + notes (PLAN §8), written to the user's pick. */
    fun exportHighlightsMarkdown(output: java.io.OutputStream, done: (String) -> Unit) {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            if (book == null) {
                done("That book isn't in your library anymore.")
                return@launch
            }
            val chaptersById = container.database.chapterDao().forBook(bookId)
                .associateBy { it.id }
            val items = container.database.highlightDao().forBook(bookId).map { highlight ->
                val chapter = chaptersById[highlight.chapterId]
                app.xreader.domain.export.HighlightsMarkdown.Item(
                    chapterIndex = chapter?.index ?: 0,
                    chapterTitle = chapter?.title ?: "",
                    quotedText = highlight.quotedText,
                    note = highlight.note,
                    color = highlight.color
                )
            }
            val text = app.xreader.domain.export.HighlightsMarkdown.format(
                bookTitle = book.title,
                author = book.author,
                items = items,
                exportedDate = java.time.LocalDate.now().toString()
            )
            val result = runCatching {
                output.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            done(
                if (result.isSuccess) {
                    "Saved ${items.size} highlight(s) as Markdown"
                } else {
                    "That place couldn't be written to."
                }
            )
        }
    }

    /** Phase 3: for source books, re-pull details + the chapter list
     * (new chapters appear; downloaded ones keep their content). */
    fun refreshChapters() {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId) ?: return@launch
            if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION) return@launch
            storyLocal.value = storyLocal.value.copy(
                chaptersLoading = true, chaptersError = null
            )
            when (val result = container.sourceBookSync.refresh(book)) {
                is app.xreader.core.error.AppResult.Ok -> {
                    storyLocal.value = storyLocal.value.copy(chaptersLoading = false)
                    _messages.tryEmit("Chapter list refreshed — ${result.value.totalChapters} chapters")
                }
                is app.xreader.core.error.AppResult.Err -> {
                    noteChaptersError(result.error)
                    _messages.tryEmit(
                        app.xreader.core.error.ErrorText.displayText(result.error)
                    )
                }
            }
        }
    }

    /** v1.3.2 (scope A2): one place where a chapters failure
     * lands in state — its plain-words text AND the session
     * hand-off it calls for, classified against this book's
     * source config (cached at init; user-added lanes and
     * bundled defaults alike). */
    private fun noteChaptersError(error: app.xreader.core.error.AppError) {
        // v1.3.4 (U4-style honesty, minimal): an availability
        // (503/429) or DNS chapters failure shows its honest
        // words — never the raw detail, and never a session
        // action (SessionNeed already returns null for both).
        val classification = app.xreader.data.extension
            .FetchClassification.classify(bookConfig, error)
        val honestChaptersError = when (classification.kind) {
            app.xreader.data.extension.FetchClassification
                .Kind.TEMPORARILY_UNAVAILABLE ->
                app.xreader.ui.sources.CatalogueCopy
                    .failureMessage(classification)
            app.xreader.data.extension.FetchClassification
                .Kind.DNS_FAILURE ->
                app.xreader.ui.sources.CatalogueCopy
                    .failureMessage(classification)
            else -> null
        }
        storyLocal.value = storyLocal.value.copy(
            chaptersLoading = false,
            chaptersError = honestChaptersError
                ?: app.xreader.core.error.ErrorText
                    .displayText(error),
            sessionNeed = app.xreader.data.extension.SessionNeed
                .classify(bookConfig, error),
            sourceOff = error.detail?.contains("switched off") == true
        )
    }

    /** v1.3.2 (item 77): one tap — turn this book's source back
     * on AND retry the chapter load. */
    fun turnBackOnAndRetry() {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            val extId = book.extensionId ?: return@launch
            val row = container.database.installedExtensionDao()
                .getById(extId) ?: return@launch
            container.repoManager.setEnabled(row, true)
            storyLocal.value = storyLocal.value.copy(sourceOff = false)
            refreshChapters()
        }
    }

    /** This book's source config, resolved once at init for
     * the hand-off classifier. */
    private var bookConfig: app.xreader.data.extension.DeclarativeConfig? = null

    /**
     * v1.0.5 (B1/E2): the Chapters section's "Re-check now" door.
     * It re-runs BOTH halves of the truth: this book's chapter
     * fetch (via [refreshChapters]) and the source's honest
     * test-drive (the same ContractTester the source's Details
     * page runs). The drive's verdict lands in state as plain
     * words — when a core step failed, its detail is named, and
     * the section's "Go to Repair" door sits right beside it.
     */
    fun recheck() {
        if (storyLocal.value.recheckBusy) return
        refreshChapters()
        viewModelScope.launch {
            storyLocal.value = storyLocal.value.copy(
                recheckBusy = true, recheckNote = null
            )
            val note = recheckNote()
            storyLocal.value = storyLocal.value.copy(
                recheckBusy = false, recheckNote = note
            )
        }
    }

    private suspend fun recheckNote(): String {
        val book = container.database.bookDao().getById(bookId)
            ?: return "The source couldn't be checked — the book is gone."
        val extensionId = book.extensionId
            ?: return "This book has no source to check."
        val (sourceId, _) = app.xreader.data.extension.SourceBookIds
            .decode(book.remoteId)
            ?: return "This book's source couldn't be identified for a check."
        val handle = when (
            val found = container.extensionService.source(extensionId, sourceId)
        ) {
            is app.xreader.core.error.AppResult.Ok -> found.value
            is app.xreader.core.error.AppResult.Err ->
                return "The source couldn't be reached for a check."
        }
        val verdict = container.contractTester.test(handle)
        val failed = verdict.results.firstOrNull {
            !it.ok && it.capability in
                app.xreader.data.extension.ContractVerdict.READING_CORE
        }
        return if (failed == null) {
            "Source check: ${verdict.summaryLine()}."
        } else {
            "Source check: ${verdict.summaryLine()} — ${failed.detail}"
        }
    }

    /** C: the reader's own status for this book. */
    fun setReadingStatus(status: app.xreader.data.library.ReadingStatus) {
        viewModelScope.launch {
            container.bookMetaPrefs.update(bookId) { it.copy(status = status) }
        }
    }

    /** A: Track this book — new chapters are counted on the cover,
     * and (with the setting on) downloaded automatically on Wi-Fi. */
    fun setTracked(tracked: Boolean) {
        viewModelScope.launch {
            container.bookMetaPrefs.update(bookId) { it.copy(tracked = tracked) }
        }
    }

    // ── v1.0.1 recommendations B + E ───────────────────────────────

    /** v1.0.5 (B2/E6): the book page Download sheet's entry point.
     * All three choices are computed by [BookDownloadRanges] — the
     * counts the sheet shows are exactly what gets queued here. */
    fun downloadChoice(choice: BookDownloadChoice) {
        when (choice) {
            BookDownloadChoice.ALL -> downloadAllChapters()
            BookDownloadChoice.NEXT_10 -> downloadNextTen()
            BookDownloadChoice.UP_TO_LATEST_UNREAD -> downloadAllUnread()
        }
    }

    /** "Next 10": queue the next ten undownloaded chapters. */
    fun downloadNextTen() {
        viewModelScope.launch {
            val next = BookDownloadRanges.range(
                state.value.chapters, BookDownloadChoice.NEXT_10
            )
            next.forEach { container.downloadController.enqueueChapter(bookId, it.id) }
            if (next.isNotEmpty()) {
                _messages.tryEmit("Downloading the next ${next.size} chapters")
            }
        }
    }

    /** "All unread": queue every undownloaded chapter that hasn't
     * been read. v1.0.5: the range is the unread set itself
     * ([BookDownloadRanges]) — the chapter you're part-way through
     * counts as unread until it's finished, so it's included; the
     * old progress-index cut skipped it even when it wasn't on the
     * device yet. */
    fun downloadAllUnread() {
        viewModelScope.launch {
            val unread = BookDownloadRanges.range(
                state.value.chapters, BookDownloadChoice.UP_TO_LATEST_UNREAD
            )
            unread.forEach { container.downloadController.enqueueChapter(bookId, it.id) }
            if (unread.isNotEmpty()) {
                _messages.tryEmit("Downloading ${unread.size} unread chapters")
            }
        }
    }

    /** Size of this book's app-side files (downloaded chapters +
     * parsed content), for the confirmations ("Free 212 MB"). */
    suspend fun downloadedSizeBytes(): Long {
        val book = container.database.bookDao().getById(bookId) ?: return 0L
        return container.bookStorage.bookFilesSizeBytes(book)
    }

    /** E: free the space, keep the book + progress + bookmarks. */
    fun deleteDownloadedChapters() {
        viewModelScope.launch {
            val chapterDao = container.database.chapterDao()
            val ready = chapterDao.forBook(bookId)
                .filter { it.downloadState == DownloadState.READY }
            // v1.2.0 (WS4): the row resets run in ONE transaction
            // (the LibraryViewModel.bulkDeleteDownloads pattern) —
            // a several-thousand-chapter book used to pay one
            // commit + one Flow emission per chapter here. The
            // file deletion below stays outside the transaction,
            // exactly as before; only the writes are now atomic.
            if (ready.isNotEmpty()) {
                container.database.withTransaction {
                    ready.forEach { chapter ->
                        chapterDao.update(
                            chapter.copy(
                                downloadState = DownloadState.NONE,
                                contentPath = null
                            )
                        )
                    }
                }
            }
            val book = container.database.bookDao().getById(bookId)
            if (book != null) {
                runCatching { container.bookStorage.deleteDownloadedContent(book) }
            }
            _messages.tryEmit("Downloaded chapters deleted — the book and your place in it are kept.")
        }
    }

    /**
     * Remove from library (v1.0.1): cancel queued downloads first,
     * then ONE Room delete — chapters, progress, bookmarks and
     * highlights cascade with the book row. [deleteFiles] also
     * removes the app-side files; the user's ORIGINAL file (for
     * imported books, the SAF original) is never touched — the app
     * only ever deletes its own copies under its own storage.
     */
    fun removeFromLibrary(deleteFiles: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId) ?: run {
                onDone()
                return@launch
            }
            runCatching { container.downloadController.cancelAllForBook(bookId) }
            container.database.bookDao().delete(book)
            container.bookMetaPrefs.set(bookId, app.xreader.data.library.BookMeta())
            if (deleteFiles) {
                runCatching { container.bookStorage.deleteBookFiles(book) }
            }
            _messages.tryEmit("Removed “${book.title}” from your library.")
            onDone()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // v1.0.2 book-page actions (R05/R11/R12/R22 + sweep merge).
    // ─────────────────────────────────────────────────────────────

    fun setFavourite(favourite: Boolean) {
        viewModelScope.launch { container.bookMetaPrefs.setFavourite(bookId, favourite) }
    }

    /** R22: add a tag by the name the user typed (or a seed name).
     * The registry canonicalises it; a previously suppressed tag is
     * revived by an explicit add. */
    fun addTag(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val slug = app.xreader.data.library.TagRegistry.slugFor(name)
            val dao = container.database.tagDao()
            if (dao.tag(slug) == null) {
                dao.insertTag(
                    app.xreader.data.db.Tag(
                        slug = slug,
                        displayName = app.xreader.data.library.TagRegistry
                            .displayFor(slug),
                        family = "Yours",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            dao.upsertBookTag(
                app.xreader.data.db.BookTag(
                    bookId = bookId, slug = slug,
                    origin = app.xreader.data.db.TagOrigin.USER,
                    suppressed = false
                )
            )
        }
    }

    /** R22 corrections: removing a tag writes a tombstone so a
     * source refresh cannot silently bring it back. */
    fun removeTag(slug: String) {
        viewModelScope.launch {
            val dao = container.database.tagDao()
            val existing = dao.forBook(bookId).firstOrNull { it.slug == slug }
            dao.upsertBookTag(
                (existing ?: app.xreader.data.db.BookTag(bookId = bookId, slug = slug))
                    .copy(suppressed = true)
            )
        }
    }

    /** R22: cautious synopsis extraction — INFERRED origin only,
     * and never over a user correction (suppressed rows skipped). */
    fun suggestTagsFromSynopsis() {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId) ?: return@launch
            val dao = container.database.tagDao()
            val existing = dao.forBook(bookId).associateBy { it.slug }
            val found = app.xreader.data.library.TagRegistry
                .extractFromSynopsis(book.description)
            var added = 0
            for (slug in found) {
                // Any existing row — visible OR a user correction —
                // means this book's tags were already decided here.
                if (existing[slug] != null) continue
                if (dao.tag(slug) == null) {
                    dao.insertTag(
                        app.xreader.data.db.Tag(
                            slug = slug,
                            displayName = app.xreader.data.library.TagRegistry
                                .displayFor(slug),
                            family = "Suggested",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                dao.upsertBookTag(
                    app.xreader.data.db.BookTag(
                        bookId = bookId, slug = slug,
                        origin = app.xreader.data.db.TagOrigin.INFERRED
                    )
                )
                added++
            }
            _messages.tryEmit(
                if (added > 0) "Added $added suggested tag(s) — tap × on any to remove it"
                else "No tag suggestions found in this synopsis"
            )
        }
    }

    /** v1.0.3 (AI hub): ask the current engine for tag suggestions.
     * They arrive as PROPOSALS in state — chips the user accepts or
     * dismisses one by one. Nothing is written by asking. */
    fun suggestTagsWithAi() {
        if (storyLocal.value.tagSuggestBusy) return
        storyLocal.value = storyLocal.value.copy(
            tagSuggestBusy = true, tagSuggestNote = null
        )
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            if (book == null) {
                storyLocal.value = storyLocal.value.copy(tagSuggestBusy = false)
                return@launch
            }
            val existing = container.database.tagDao().forBook(bookId)
                .map { it.slug }.toSet()
            when (
                val result = container.tagSuggester.suggest(book, existing)
            ) {
                is app.xreader.core.error.AppResult.Ok -> storyLocal.value =
                    storyLocal.value.copy(
                        tagSuggestBusy = false,
                        tagSuggestions = result.value,
                        tagSuggestNote = if (result.value.isEmpty()) {
                            "The AI didn't suggest any new tags for this book."
                        } else {
                            null
                        }
                    )
                is app.xreader.core.error.AppResult.Err -> storyLocal.value =
                    storyLocal.value.copy(
                        tagSuggestBusy = false,
                        tagSuggestNote = app.xreader.core.error.ErrorText
                            .displayText(result.error)
                    )
            }
        }
    }

    /** Accept one AI suggestion: the tag is written with INFERRED
     * origin (it stays labelled a guess, feeds Follow-a-tag and
     * For You like any tag, and removing it later tombstones it). */
    fun acceptTagSuggestion(suggestion: app.xreader.data.ai.TagSuggestion) {
        viewModelScope.launch {
            val dao = container.database.tagDao()
            if (dao.tag(suggestion.slug) == null) {
                dao.insertTag(
                    app.xreader.data.db.Tag(
                        slug = suggestion.slug,
                        displayName = suggestion.displayName,
                        family = if (suggestion.known) "Suggested" else "Yours",
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            dao.upsertBookTag(
                app.xreader.data.db.BookTag(
                    bookId = bookId, slug = suggestion.slug,
                    origin = app.xreader.data.db.TagOrigin.INFERRED,
                    suppressed = false
                )
            )
            storyLocal.value = storyLocal.value.copy(
                tagSuggestions = storyLocal.value.tagSuggestions
                    .filter { it.slug != suggestion.slug }
            )
        }
    }

    /** Dismiss one AI suggestion — state only, nothing written. */
    fun dismissTagSuggestion(suggestion: app.xreader.data.ai.TagSuggestion) {
        storyLocal.value = storyLocal.value.copy(
            tagSuggestions = storyLocal.value.tagSuggestions
                .filter { it.slug != suggestion.slug }
        )
    }

    private var paletteTried = false

    /** R05: decode the cover once (off the UI thread, downscaled)
     * into the shared bounded palette cache. */
    fun loadPalette() {
        if (paletteTried) return
        paletteTried = true
        viewModelScope.launch {
            container.coverPaletteCache.get(bookId.toString())?.let { cached ->
                storyLocal.value = storyLocal.value.copy(coverPalette = cached)
                return@launch
            }
            val book = container.database.bookDao().getById(bookId) ?: return@launch
            val file = book.coverPath?.let { container.bookStorage.resolve(it) }
                ?: return@launch
            val palette = kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.Default
            ) {
                runCatching {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.path)
                        ?: return@runCatching null
                    val scaled = android.graphics.Bitmap.createScaledBitmap(
                        bitmap, 48, 48, true
                    )
                    val pixels = IntArray(48 * 48)
                    scaled.getPixels(pixels, 0, 48, 0, 0, 48, 48)
                    app.xreader.ui.theme.CoverPaletteExtractor.extract(pixels)
                }.getOrNull()
            }
            if (palette != null) {
                container.coverPaletteCache.put(bookId.toString(), palette)
                storyLocal.value = storyLocal.value.copy(coverPalette = palette)
            }
        }
    }

    /** R11: find this work on the user's OTHER sources. Ranking is
     * the user's one Best-source priority; a copy already in the
     * library is marked, never duplicated silently. */
    fun loadOtherSources() {
        if (state.value.otherSourcesState == OtherSourcesState.LOADING) return
        storyLocal.value = storyLocal.value.copy(
            otherSourcesState = OtherSourcesState.LOADING
        )
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            if (book == null) {
                storyLocal.value = storyLocal.value.copy(
                    otherSourcesState = OtherSourcesState.FAILED
                )
                return@launch
            }
            val own = book.remoteId?.let {
                app.xreader.data.extension.SourceBookIds.decode(it)
            }
            val found = mutableListOf<OtherSourceCandidate>()
            val rankInputs = mutableListOf<app.xreader.data.extension.SourceCandidate>()
            for (handle in container.extensionService.allSources()) {
                if (own != null && handle.extension.id == book.extensionId &&
                    handle.source.id == own.first
                ) {
                    continue
                }
                val result = container.extensionService.guarded(handle) {
                    it.search(book.title, 1)
                }
                val page = (result as? app.xreader.core.error.AppResult.Ok)?.value
                    ?: continue
                for (candidate in page.books) {
                    if (!app.xreader.data.extension.WorkMatcher.sameWork(
                            book.title, book.author, candidate.title,
                            candidate.author ?: ""
                        )
                    ) {
                        continue
                    }
                    val inLibrary = container.database.bookDao()
                        .findByExtensionRemote(
                            handle.extension.id,
                            app.xreader.data.extension.SourceBookIds.encode(
                                handle.source.id, candidate.id
                            )
                        )
                    val downloaded = inLibrary?.let {
                        container.database.chapterDao().readyCount(it.id)
                    } ?: 0
                    found += OtherSourceCandidate(
                        extensionId = handle.extension.id,
                        sourceId = handle.source.id,
                        sourceName = handle.source.name,
                        book = candidate,
                        inLibraryBookId = inLibrary?.id,
                        downloadedCount = downloaded
                    )
                    rankInputs += app.xreader.data.extension.SourceCandidate(
                        extensionId = handle.extension.id,
                        sourceName = handle.source.name,
                        remoteId = candidate.id,
                        downloadedChapters = downloaded,
                        totalChapters = inLibrary?.totalChapters ?: 0
                    )
                }
            }
            val priority = container.sourcePrefs.bestSourcePriority.first()
            val ranked = app.xreader.data.extension.BestSourceRanker
                .rank(rankInputs, priority)
            val order = ranked.mapIndexed { index, c ->
                (c.extensionId to c.remoteId) to index
            }.toMap()
            storyLocal.value = storyLocal.value.copy(
                otherSourcesState = OtherSourcesState.LOADED,
                otherSources = found.sortedBy {
                    order[it.extensionId to it.book.id] ?: Int.MAX_VALUE
                }
            )
        }
    }

    /** R11: preview the switch BEFORE anything changes. The new
     * copy is opened through the identity-safe path (which does
     * not disturb this book), its chapter list is read back, and
     * the pure planner says where progress would land. */
    fun previewSwitch(candidate: OtherSourceCandidate) {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId) ?: return@launch
            val handle = container.extensionService
                .source(candidate.extensionId, candidate.sourceId)
                .getOrNull() ?: run {
                    _messages.tryEmit("That source isn't available right now.")
                    return@launch
                }
            val opened = when (
                val result = container.sourceBookSync.openBook(handle, candidate.book)
            ) {
                is app.xreader.core.error.AppResult.Ok -> result.value
                is app.xreader.core.error.AppResult.Err -> {
                    _messages.tryEmit("That copy couldn't be opened.")
                    return@launch
                }
            }
            val oldChapters = container.database.chapterDao().forBook(bookId)
                .map {
                    app.xreader.extensionapi.SourceChapter(
                        id = it.remoteId ?: it.id.toString(),
                        name = it.title,
                        number = (it.index + 1).toFloat()
                    )
                }
            val newChapters = container.database.chapterDao().forBook(opened.id)
                .map {
                    app.xreader.extensionapi.SourceChapter(
                        id = it.remoteId ?: it.id.toString(),
                        name = it.title,
                        number = (it.index + 1).toFloat()
                    )
                }
            val progress = container.database.readingProgressDao().getForBook(bookId)
            val preview = app.xreader.data.extension.SourceSwitchPlanner.preview(
                oldChapters = oldChapters,
                newChapters = newChapters,
                progressChapterIndex = progress?.chapterIndex ?: 0,
                progressCharOffset = progress?.charOffset ?: 0
            )
            storyLocal.value = storyLocal.value.copy(
                switchPreview = preview,
                switchCandidate = candidate.copy(inLibraryBookId = opened.id)
            )
        }
    }

    fun dismissSwitch() {
        storyLocal.value = storyLocal.value.copy(
            switchPreview = null, switchCandidate = null
        )
    }

    /** R11/sweep: carry out the previewed move. Progress, read
     * flags, status, tracking, favourite and shelf memberships
     * move to the new copy; the old entry becomes a merged copy of
     * the new one (hidden, restorable via Unmerge) so nothing the
     * user had is ever destroyed by a move. Downloads stay with
     * the old entry — the preview said so. */
    fun confirmSwitch() {
        val candidate = state.value.switchCandidate ?: return
        val preview = state.value.switchPreview ?: return
        val targetId = candidate.inLibraryBookId ?: return
        viewModelScope.launch {
            val old = container.database.bookDao().getById(bookId) ?: return@launch
            val target = container.database.bookDao().getById(targetId) ?: return@launch
            // Read flags for matched chapters.
            val oldChapters = container.database.chapterDao().forBook(bookId)
            val newChapters = container.database.chapterDao().forBook(targetId)
            val matches = app.xreader.data.extension.ChapterMatcher.match(
                oldChapters.map {
                    app.xreader.extensionapi.SourceChapter(
                        id = it.remoteId ?: it.id.toString(), name = it.title,
                        // v1.3.1 (item 55): a chapter's number
                        // comes from its own data (its title),
                        // never from its list position — a list
                        // with junk rows or gaps would otherwise
                        // match phantom numbers. Position is the
                        // last-resort fallback only.
                        number = app.xreader.data.extension.ChapterNumbers
                            .parse(it.title) ?: (it.index + 1).toFloat()
                    )
                },
                newChapters.map {
                    app.xreader.extensionapi.SourceChapter(
                        id = it.remoteId ?: it.id.toString(), name = it.title,
                        number = app.xreader.data.extension.ChapterNumbers
                            .parse(it.title) ?: (it.index + 1).toFloat()
                    )
                }
            )
            val chapterDao = container.database.chapterDao()
            matches
                .filter {
                    it.confidence >= app.xreader.data.extension.ChapterMatcher.AUTO_THRESHOLD
                }
                .forEach { match ->
                    if (oldChapters.getOrNull(match.primaryIndex)?.read == true) {
                        newChapters.getOrNull(match.altIndex)?.let {
                            chapterDao.setRead(it.id, true)
                        }
                    }
                }
            // Progress lands where the preview said.
            val oldProgress = container.database.readingProgressDao()
                .getForBook(bookId)
            if (oldProgress != null && preview.progressChapterIndex >= 0) {
                container.database.readingProgressDao().upsert(
                    oldProgress.copy(
                        bookId = targetId,
                        chapterId = newChapters.getOrNull(preview.progressChapterIndex)
                            ?.id ?: oldProgress.chapterId,
                        chapterIndex = preview.progressChapterIndex,
                        charOffset = if (preview.progressApproximate) {
                            0
                        } else {
                            oldProgress.charOffset
                        },
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            // Meta, favourite, shelves follow the book.
            val meta = container.bookMetaPrefs.get(bookId)
            container.bookMetaPrefs.set(targetId, meta)
            if (state.value.favourite) {
                container.bookMetaPrefs.setFavourite(targetId, true)
                container.bookMetaPrefs.setFavourite(bookId, false)
            }
            val shelfDao = container.database.shelfDao()
            shelfDao.all().forEach { shelf ->
                if (bookId in shelfDao.bookIdsOn(shelf.id)) {
                    shelfDao.addBook(
                        app.xreader.data.db.ShelfBook(
                            shelfId = shelf.id, bookId = targetId,
                            addedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            // The old entry retires as a merged copy — reversible.
            container.database.bookDao().update(old.copy(mergedIntoId = targetId))
            storyLocal.value = storyLocal.value.copy(
                switchPreview = null, switchCandidate = null
            )
            _messages.tryEmit(
                "Moved to ${candidate.sourceName}. Your place, shelves and " +
                    "favourite came with it; the old copy is kept under " +
                    "“Merged copies” if you ever want it back."
            )
        }
    }

    /** Sweep: merge a duplicate INTO this book (reversible). */
    fun mergeDuplicate(duplicateId: Long) {
        viewModelScope.launch {
            val duplicate = container.database.bookDao().getById(duplicateId)
                ?: return@launch
            container.database.bookDao().update(
                duplicate.copy(mergedIntoId = bookId)
            )
            _messages.tryEmit(
                "Merged “${duplicate.title}” into this book. " +
                    "Unmerge any time — nothing was deleted."
            )
        }
    }

    /** Sweep: undo a merge — the copy returns to the Library. */
    fun unmerge(copyId: Long) {
        viewModelScope.launch {
            val copy = container.database.bookDao().getById(copyId) ?: return@launch
            container.database.bookDao().update(copy.copy(mergedIntoId = null))
            _messages.tryEmit("“${copy.title}” is back in your library.")
        }
    }

    // ── R26: AI "More like this" ─────────────────────────────────

    /** Key presence decides NO_KEY vs NOT_ASKED at first state. */
    private suspend fun primeAiSuggestionsState(book: Book) {
        if (!container.aiHub.client.hasKey()) return
        val fingerprint = app.xreader.data.prefs.AiSuggestionPrefs
            .fingerprint(book.title, book.author)
        val cached = container.aiSuggestionPrefs.cached(fingerprint)
        if (cached != null) {
            val dismissed = container.aiSuggestionPrefs.dismissed()
            storyLocal.value = storyLocal.value.copy(
                aiSuggestionsState = AiSuggestionsState.READY,
                aiSuggestions = cached.filter { title ->
                    app.xreader.data.prefs.AiSuggestionPrefs
                        .normaliseTitle(title) !in dismissed
                }
            )
        } else {
            storyLocal.value = storyLocal.value.copy(
                aiSuggestionsState = AiSuggestionsState.NOT_ASKED
            )
        }
    }

    /** Ask Gemini for similar books. Never automatic — the user's
     * key is spent only on tap, and the result is cached by book
     * fingerprint so it is never spent twice on the same book. */
    fun askAiSuggestions() {
        viewModelScope.launch {
            val book = state.value.book ?: return@launch
            storyLocal.value = storyLocal.value.copy(
                aiSuggestionsState = AiSuggestionsState.LOADING
            )
            val tagNames = state.value.tags.map {
                app.xreader.data.library.TagRegistry.displayFor(it.slug)
            }
            val prompt = buildString {
                append("Recommend 6 novels similar to this one.\n")
                append("Title: ${book.title}\n")
                book.author?.let { append("Author: $it\n") }
                if (tagNames.isNotEmpty()) {
                    append("Tags: ${tagNames.joinToString(", ")}\n")
                }
                book.description.take(600).takeIf { it.isNotBlank() }?.let {
                    append("Synopsis: $it\n")
                }
                append(
                    "Reply with ONLY a JSON array of title strings, " +
                        "no other text."
                )
            }
            when (val result = container.aiHub.client.generateText(
                prompt = prompt, jsonMode = true, maxOutputTokens = 400
            )) {
                is app.xreader.core.error.AppResult.Ok -> {
                    val titles = parseSuggestionTitles(result.value.text)
                    val fingerprint =
                        app.xreader.data.prefs.AiSuggestionPrefs
                            .fingerprint(book.title, book.author)
                    if (titles.isNotEmpty()) {
                        container.aiSuggestionPrefs.cache(fingerprint, titles)
                    }
                    val dismissed = container.aiSuggestionPrefs.dismissed()
                    storyLocal.value = storyLocal.value.copy(
                        aiSuggestionsState = AiSuggestionsState.READY,
                        aiSuggestions = titles.filter { title ->
                            app.xreader.data.prefs.AiSuggestionPrefs
                                .normaliseTitle(title) !in dismissed
                        }
                    )
                }
                is app.xreader.core.error.AppResult.Err -> storyLocal.value =
                    storyLocal.value.copy(
                        aiSuggestionsState = AiSuggestionsState.FAILED
                    )
            }
        }
    }

    fun dismissAiSuggestion(title: String) {
        viewModelScope.launch {
            container.aiSuggestionPrefs.dismiss(
                app.xreader.data.prefs.AiSuggestionPrefs.normaliseTitle(title)
            )
            storyLocal.value = storyLocal.value.copy(
                aiSuggestions = storyLocal.value.aiSuggestions - title
            )
        }
    }

    /** Tolerant parse: a JSON array of strings, or one title/line. */
    private fun parseSuggestionTitles(text: String): List<String> {
        val trimmed = text.trim()
        val fromJson = runCatching {
            val element = kotlinx.serialization.json.Json
                .parseToJsonElement(trimmed)
            (element as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()
        val raw = fromJson ?: trimmed.lines()
            .map { it.trim().trim('-', '*', '"', ' ') }
            .filter { it.isNotBlank() }
        return raw.map { it.trim() }.filter { it.length in 2..120 }
            .distinct().take(6)
    }

    /** R12 wiring: the synopsis is blank — look for this work's
     * details on the user's other sources and fill ONLY blanks,
     * through the MetadataFallback precedence (blank never
     * overwrites; a found value names its source). */
    fun findDetailsFromSources() {
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId) ?: return@launch
            if (book.description.isNotBlank()) return@launch
            var foundDescription: String? = null
            var foundSource: String? = null
            for (handle in container.extensionService.allSources()) {
                if (handle.extension.id == book.extensionId) continue
                val result = container.extensionService.guarded(handle) {
                    it.search(book.title, 1)
                }
                val page = (result as? app.xreader.core.error.AppResult.Ok)?.value
                    ?: continue
                val match = page.books.firstOrNull { candidate ->
                    app.xreader.data.extension.WorkMatcher.sameWork(
                        book.title, book.author, candidate.title, candidate.author ?: ""
                    ) && !candidate.description.isNullOrBlank()
                }
                if (match != null) {
                    foundDescription = match.description
                    foundSource = handle.source.name
                    break
                }
            }
            val resolved = app.xreader.data.extension.MetadataFallback.resolve(
                listOf(
                    app.xreader.data.extension.MetadataFallback.Candidate(
                        value = book.description,
                        origin = app.xreader.data.extension.MetadataFallback
                            .Origin.OWN_SOURCE
                    ),
                    app.xreader.data.extension.MetadataFallback.Candidate(
                        value = foundDescription,
                        origin = app.xreader.data.extension.MetadataFallback
                            .Origin.OTHER_SOURCE_EXACT
                    )
                )
            )
            if (resolved != null && resolved.value.isNotBlank() &&
                resolved.value != book.description
            ) {
                container.database.bookDao().update(
                    book.copy(description = resolved.value)
                )
                _messages.tryEmit("Synopsis found on $foundSource")
            } else {
                _messages.tryEmit(
                    "No other source has details for this book yet."
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BookDetailViewModel(container, bookId) as T
            }
    }
}
