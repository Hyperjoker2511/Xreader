package app.xreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.core.motion.SpringMotionEngine
import app.xreader.data.db.Bookmark
import app.xreader.data.db.Chapter
import app.xreader.data.db.Highlight
import app.xreader.data.db.ReadingProgress
import app.xreader.data.reader.AndroidTtsEngine
import app.xreader.data.reader.MlKitEyeTracker
import app.xreader.data.reader.OverlayPlayer
import app.xreader.data.reader.ReadAloudController
import app.xreader.data.reader.SoundscapePlayer
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.FocusState
import app.xreader.domain.reader.FocusTimer
import app.xreader.domain.reader.OverlaySync
import app.xreader.domain.reader.PageLocator
import app.xreader.domain.reader.PageTurnTier
import app.xreader.domain.reader.PageTurnTiers
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.SessionTracker
import app.xreader.domain.reader.SleepMode
import app.xreader.domain.reader.StreamWindowPlanner
import app.xreader.domain.reader.Soundscape
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where a freshly loaded chapter should open. */
enum class PageHint { FIRST, LAST, OFFSET }

data class ReaderUiState(
    val bookTitle: String = "",
    val chapters: List<Chapter> = emptyList(),
    val chapterIndex: Int = 0,
    val chapterText: ChapterText? = null,
    /** One-shot restore request for the renderers (char offset + hint). */
    val restoreOffset: Int = 0,
    val restoreScrollFraction: Float = 0f,
    val restoreHint: PageHint = PageHint.OFFSET,
    val restoreGeneration: Int = 0,
    val loading: Boolean = true,
    val errorMessage: String? = null,
    /** v1.0.6.2 (F3): the error came from a live chapter fetch
     * that can simply be retried (the error block offers it). */
    val chapterFetchFailed: Boolean = false,
    /** v1.3.2 (A2): the fetch failed at a site check or a sign-in
     * gate — the error block offers the one-time hand-off screen
     * for this kind instead of only "Try again". */
    val sessionNeed: app.xreader.data.extension.SessionNeed.Kind? = null,
    /** v1.3.4 (U3): the sanctioned screen's target page for the
     * current [sessionNeed] — the source's READER page for the
     * failed chapter, when its config declares one (its check
     * widget lives there). Null = the site root, as before. */
    val sessionTargetUrl: String? = null,
    /** v1.3.4 (U3): the v2 classification of the current fetch
     * failure. TEMPORARILY_UNAVAILABLE and DNS_FAILURE carry
     * NO session action by construction ([sessionNeed] stays
     * null for them) — the error block offers Retry only. */
    val fetchKind: app.xreader.data.extension.FetchClassification.Kind? = null,
    /** Live position (char offset in the current chapter). */
    val positionOffset: Int = 0,
    val bookFraction: Float = 0f,
    val chapterFraction: Float = 0f,
    val minutesLeftInChapter: Int = 0,
    val minutesLeftInBook: Int = 0,
    val hasOverlay: Boolean = false,
    val overlayTextId: String? = null,
    val currentPositionBookmarked: Boolean = false,
    val pageTurnTier: PageTurnTier = PageTurnTier.FULL,
    val isImageBook: Boolean = false,
    // ── v1.0.2 (R17/R18) ─────────────────────────────────────────
    /** The endless stream's loaded window (scroll + continuous). */
    val streamEntries: List<StreamEntry> = emptyList(),
    val streamActive: Boolean = false,
    /** "This book / All books" settings scope (R18). */
    val settingsScope: ReaderSettingsScope = ReaderSettingsScope.ALL_BOOKS,
    val hasBookOverride: Boolean = false,
    /** True when the text on screen is the RAW translation, not the
     * book's own text (Phase 5). The original is never overwritten. */
    val showingTranslation: Boolean = false,
    /** v1.0.6 (W3.4): one-shot plain note when this book's source
     * reads through the browser view — shown once per source per
     * session via the reader's transient notice, then consumed. */
    val browserViewNotice: String? = null
) {
    val chapter: Chapter? get() = chapters.getOrNull(chapterIndex)
    val hasPrevious: Boolean get() = chapterIndex > 0
    val hasNext: Boolean get() = chapterIndex < chapters.lastIndex
}

data class FocusUiState(
    val state: FocusState = FocusState.IDLE,
    val remainingMs: Long = 0L,
    val durationMinutes: Int = FocusTimer.DEFAULT_MINUTES,
    val soundscape: Soundscape = Soundscape.NONE,
    val completedUninterrupted: Boolean = false
)

/** One chapter inside the endless stream's window (R17). A null
 * [text] with [missing] set renders the honest "not downloaded"
 * block instead of a gap. */
data class StreamEntry(
    val chapterIndex: Int,
    val chapterId: Long,
    val title: String,
    val text: ChapterText?,
    val missing: Boolean = false,
    /** v1.0.6.2 (F3-stream): this chapter's copy is travelling
     * through the download queue right now — the block says so
     * instead of offering a download that's already running. */
    val fetching: Boolean = false
)

/** R18 settings scope: where a reader-settings change lands. */
enum class ReaderSettingsScope { ALL_BOOKS, THIS_BOOK }

/**
 * The Phase 4 reader brain (PLAN §6): chapter loading (one chapter in
 * memory at a time + neighbour prefetch — never the whole book),
 * locator-based progress saved continuously, reading sessions/WPM,
 * hybrid read-aloud (TTS + Media Overlay), the focus timer, bookmarks
 * & highlights, and the Labs eye tracker. All rendering state is
 * derived from the char-offset locator, so paged and scroll modes —
 * and any typography change — share one position.
 */
class ReaderViewModel(
    private val container: AppContainer,
    private val bookId: Long,
    initialChapterIndex: Int
) : ViewModel() {

    private val _state = MutableStateFlow(
        ReaderUiState(chapterIndex = initialChapterIndex)
    )
    val state: StateFlow<ReaderUiState> = _state

    private val _config = MutableStateFlow(ReaderConfig())
    val config: StateFlow<ReaderConfig> = _config

    /** v1.0.2 (R18): the two config layers behind [config]. */
    private var globalConfig: ReaderConfig = ReaderConfig()
    private var bookOverride: app.xreader.data.prefs.BookReaderOverride =
        app.xreader.data.prefs.BookReaderOverride()

    private fun publishConfig() {
        _config.value = globalConfig.copy(
            paletteName = bookOverride.paletteName ?: globalConfig.paletteName,
            fontSizeSp = bookOverride.fontSizeSp ?: globalConfig.fontSizeSp
        )
    }

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    val highlights: StateFlow<List<Highlight>> = _highlights

    private val _focus = MutableStateFlow(FocusUiState())
    val focus: StateFlow<FocusUiState> = _focus

    /** Cached AI summary of the chapter on screen (Phase 5) — shown
     * in the Contents sheet; null when none exists yet. */
    private val _chapterSummary = MutableStateFlow<app.xreader.data.db.AiSummary?>(null)
    val chapterSummary: StateFlow<app.xreader.data.db.AiSummary?> = _chapterSummary
    private var summaryJob: Job? = null

    /** External "turn forward" signals (volume keys, eye tracking). */
    private val _externalTurns = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val externalTurns: SharedFlow<Int> = _externalTurns

    // -- Read-aloud (lazy: the TTS engine initialises on first use) ---------
    private val readAloudLazy = lazy {
        ReadAloudController(
            engine = AndroidTtsEngine(container.applicationContext, viewModelScope),
            scope = viewModelScope
        ).also { controller ->
            controller.onChapterFinished = {
                if (_state.value.hasNext) {
                    pendingAutoplayTts = true
                    openChapter(_state.value.chapterIndex + 1)
                }
            }
        }
    }
    private val readAloud: ReadAloudController by readAloudLazy
    val ttsState: StateFlow<ReadAloudController.State> by lazy { readAloud.state }

    // -- Media Overlay -------------------------------------------------------
    private val overlayPlayerLazy = lazy { OverlayPlayer(viewModelScope) }
    private val overlayPlayer: OverlayPlayer by overlayPlayerLazy
    val overlayState: StateFlow<OverlayPlayer.State> by lazy { overlayPlayer.state }
    private var overlaySync: OverlaySync? = null

    // -- Focus timer -----------------------------------------------------------
    private val focusTimer = FocusTimer { System.currentTimeMillis() }
    private var focusJob: Job? = null
    private val soundscapePlayerLazy = lazy {
        SoundscapePlayer(container.applicationContext)
    }
    private val soundscapePlayer: SoundscapePlayer by soundscapePlayerLazy
    private var pendingFocusBonus = false

    // -- Eye tracking (Labs) ---------------------------------------------------
    private val eyeTrackerLazy = lazy {
        MlKitEyeTracker(container.applicationContext).also { tracker ->
            tracker.onTurnCommand = { _externalTurns.tryEmit(1) }
        }
    }
    val eyeTracker: MlKitEyeTracker by eyeTrackerLazy

    @Volatile
    private var reduceMotion: Boolean = false

    // -- Session / progress bookkeeping ---------------------------------------
    private val tracker = SessionTracker({ System.currentTimeMillis() })
    private var sessionBookId: Long = bookId
    private val finishedChapterIds = mutableSetOf<Long>()
    private var completedSessionWords = 0L
    private var lastFlushedWords = 0L
    private var currentChapterMaxOffset = 0
    private var saveJob: Job? = null
    private var loadJob: Job? = null

    // -- v1.2.0 (Wave 3): position-pipeline indexes & coalescing --
    /** The chapter list indexed once per list change (id→index +
     * word-count suffix aggregates), so a settled position costs
     * O(1) of book-level work instead of a scan of every later
     * chapter. Rebuilt ONLY through [chaptersUpdated]. */
    private var chapterPositionIndex: ChapterPositionIndex =
        ChapterPositionIndex.EMPTY

    /** Every assignment of a new chapter list goes through here so
     * the index can never drift from the state's list. */
    private fun chaptersUpdated(chapters: List<Chapter>): List<Chapter> {
        chapterPositionIndex = ChapterPositionIndex.build(chapters)
        return chapters
    }

    /** When progress writes may land (settle + coarse cap). */
    private val progressCoalescer = ProgressSaveCoalescer()

    /** The comic near-end warm: the chapter id currently being
     * warmed through the direct read path, if any. The in-flight
     * guard [PrefetchPolicy] reads — one warm at a time, and the
     * same chapter is never warmed twice at once. */
    private var imagePrefetchJob: Job? = null
    private var imagePrefetchChapterId: Long? = null

    /** Which chapter the on-screen [ReaderUiState.chapterText]
     * actually belongs to. With keep-previous-chapter, chapterIndex
     * moves to the incoming chapter while the previous text is
     * still visible; progress saves must only fire while the two
     * agree, or a mid-load flush would write the old chapter's
     * offset against the new chapter's row. Set wherever a text
     * is put on screen (load success, stream anchor/crossing). */
    private var visibleTextChapterIndex: Int = -1
    private var pendingAutoplayTts = false
    private val chapterCache = LinkedHashMap<Long, ChapterText>()

    /** Chapters the reader asked to see in the original even though
     * a RAW translation exists (in-memory only — the translation
     * stays the default view; nothing is ever deleted). */
    private val originalPreferred = mutableSetOf<Long>()

    /** v1.3.2 (A2): this book's declarative config, resolved once
     * at load — the session-need classifier's input. */
    private var bookConfig: app.xreader.data.extension.DeclarativeConfig? = null
    private val signInConfig: Boolean get() = bookConfig?.signInNeeded == true

    init {
        viewModelScope.launch { container.sessionRecorder.warmUp() }
        viewModelScope.launch {
            container.appearancePrefs.state.collectLatest { appearance ->
                reduceMotion = appearance.reduceMotion
                _state.value = _state.value.copy(
                    pageTurnTier = computeTier(_config.value)
                )
            }
        }
        viewModelScope.launch {
            container.readerPrefs.config.collectLatest { newConfig ->
                globalConfig = newConfig
                publishConfig()
                _state.value = _state.value.copy(
                    pageTurnTier = computeTier(newConfig)
                )
                if (newConfig.eyeTracking) eyeTracker.toggle(true)
                else if (eyeTracker.controller.isOn) eyeTracker.stop()
            }
        }
        // v1.0.2 (R18): this book's own palette/text-size override
        // layers over the global config — the reader never has to
        // know which store a value came from.
        viewModelScope.launch {
            container.bookReaderPrefs.overrides(bookId).collectLatest { override ->
                bookOverride = override
                _state.value = _state.value.copy(hasBookOverride = !override.isEmpty)
                publishConfig()
            }
        }
        viewModelScope.launch {
            container.database.bookmarkDao().observeForBook(bookId)
                .collectLatest { _bookmarks.value = it }
        }
        viewModelScope.launch {
            container.database.highlightDao().observeForBook(bookId)
                .collectLatest { _highlights.value = it }
        }
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            bookConfig = book?.extensionId
                ?.takeIf {
                    book.sourceType == app.xreader.data.db.SourceType.EXTENSION
                }
                ?.let { extId ->
                    container.extensionService.sourcesOf(extId).getOrNull()
                        ?.firstNotNullOfOrNull { handle ->
                            container.extensionService
                                .declarativeConfigOf(handle)
                        }
                }
            var chapters = container.database.chapterDao().forBook(bookId)
            // v1.3.3 (scope S6): the reader opened on an EMPTY
            // stored list (a pre-sign-in preview, a store the
            // repair hasn't reached) must FETCH before it
            // judges anything missing — an empty snapshot is
            // not evidence about the book.
            if (chapters.isEmpty() && book != null &&
                book.sourceType ==
                app.xreader.data.db.SourceType.EXTENSION
            ) {
                runCatching {
                    container.sourceBookSync.refresh(book)
                }
                chapters = container.database.chapterDao().forBook(bookId)
            }
            val saved = container.database.readingProgressDao().getForBook(bookId)
            // v1.0.6 (W3.4): a browser-view source gets ONE plain
            // note per source per session, so slower chapter turns
            // never read as the app hanging.
            val sourceNotice = browserViewNoticeFor(book)
            _state.value = _state.value.copy(
                bookTitle = book?.title ?: "",
                chapters = chaptersUpdated(chapters),
                pageTurnTier = computeTier(_config.value),
                browserViewNotice = sourceNotice ?: _state.value.browserViewNotice
            )
            val startIndex = initialChapterIndex.coerceIn(0, maxOf(0, chapters.lastIndex))
            val restore = if (saved != null && saved.chapterIndex == startIndex) {
                saved.charOffset to saved.scrollFraction
            } else {
                0 to 0f
            }
            loadChapter(startIndex, restore.first, restore.second)
        }
        viewModelScope.launch {
            overlayPlayer.state.collectLatest { playerState ->
                val sync = overlaySync
                _state.value = _state.value.copy(
                    overlayTextId = if (sync != null && playerState.prepared) {
                        sync.textIdAt(playerState.positionSec)
                    } else {
                        null
                    }
                )
            }
        }
        // v1.3.3 (scope S4): SIGN-IN COMPLETION — the one-time
        // solve/sign-in screen finished for this book's source:
        // refresh the book against the new session, re-resolve
        // the intended chapter BY IDENTITY against the
        // post-login list, and — when the reader is sitting on
        // the failure the gate caused — retry it automatically.
        // The user lands back on their chapter, not on a blank
        // screen or a false "isn't in the book anymore".
        viewModelScope.launch {
            container.sessionSavedEvents.collect { key ->
                val bookRow = container.database.bookDao()
                    .getById(bookId) ?: return@collect
                val extId = bookRow.extensionId ?: return@collect
                if (key !=
                    app.xreader.data.extension.ExtensionHttpClient
                        .sessionKeyFor(extId)
                ) {
                    return@collect
                }
                val intended = _state.value.chapters
                    .getOrNull(_state.value.chapterIndex)
                val intendedKey = intended?.let {
                    app.xreader.data.extension.ChapterIdentity
                        .canonicalKey(it.remoteId ?: "")
                }
                val intendedNumber = intended?.let {
                    app.xreader.domain.reader.ChapterDisplayOrder
                        .numberOf(it)
                }
                if (bookRow.sourceType ==
                    app.xreader.data.db.SourceType.EXTENSION
                ) {
                    runCatching {
                        container.sourceBookSync.refresh(bookRow)
                    }
                }
                val rows = container.database.chapterDao()
                    .forBook(bookId)
                if (rows.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        chapters = chaptersUpdated(rows)
                    )
                }
                val sittingOnFailure =
                    _state.value.chapterFetchFailed ||
                        _state.value.errorMessage != null ||
                        _state.value.chapterText == null
                if (sittingOnFailure && rows.isNotEmpty()) {
                    val newIndex = rows.indexOfFirst { row ->
                        (intendedKey != null &&
                            app.xreader.data.extension
                                .ChapterIdentity.canonicalKey(
                                    row.remoteId ?: ""
                                ) == intendedKey) ||
                            (intendedNumber != null &&
                                app.xreader.domain.reader
                                    .ChapterDisplayOrder
                                    .numberOf(row) == intendedNumber)
                    }
                    val target = when {
                        newIndex >= 0 -> newIndex
                        else -> _state.value.chapterIndex
                            .coerceIn(0, rows.lastIndex)
                    }
                    launchLoad { loadChapter(target, 0, 0f) }
                }
            }
        }
    }

    private fun computeTier(config: ReaderConfig): PageTurnTier {
        val motionTier = SpringMotionEngine.tierFor(
            totalRamMb = container.totalRamMb(),
            animationsOn = !reduceMotion
        )
        return PageTurnTiers.select(
            totalRamMb = container.totalRamMb(),
            memoryClassMb = container.memoryClassMb(),
            override = config.turnOverride,
            eInkMode = config.eInkMode,
            motionTier = motionTier
        )
    }

    // -- Chapter loading -------------------------------------------------------

    private fun launchLoad(block: suspend () -> Unit) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { block() }
    }

    fun openChapter(index: Int) {
        closeStream()
        if (index == _state.value.chapterIndex && _state.value.chapterText != null) return
        launchLoad { loadChapter(index, 0, 0f) }
    }

    fun openChapterAt(index: Int, charOffset: Int) {
        closeStream()
        launchLoad { loadChapter(index, charOffset, 0f, PageHint.OFFSET) }
    }

    fun nextChapter() {
        if (!_state.value.hasNext) return
        closeStream()
        markCurrentChapterFinished()
        launchLoad { loadChapter(_state.value.chapterIndex + 1, 0, 0f) }
    }

    fun previousChapter() {
        if (!_state.value.hasPrevious) return
        closeStream()
        launchLoad { loadChapter(_state.value.chapterIndex - 1, 0, 0f, PageHint.LAST) }
    }

    private var growJob: kotlinx.coroutines.Job? = null

    /** v1.0.8: reading near the loaded edge of a partially
     * loaded book grows the chapter list ahead of the reader —
     * and only then (a book he reads one chapter of never loads
     * more). When the list grows, the reader's snapshot refreshes
     * so Next unlocks onto the new chapters seamlessly. */
    private fun maybeGrowChapterList(index: Int, loadedCount: Int) {
        if (index < loadedCount -
            app.xreader.data.extension.SourceBookSync.EDGE_TRIGGER
        ) {
            return
        }
        if (growJob?.isActive == true) return
        growJob = viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
                ?: return@launch
            if (book.chapterListComplete) return@launch
            container.sourceBookSync.ensureChapters(
                book,
                loadedCount +
                    app.xreader.data.extension.SourceBookSync.EDGE_TARGET
            )
            val fresh = container.database.chapterDao().forBook(bookId)
            if (fresh.size > _state.value.chapters.size) {
                _state.value = _state.value.copy(chapters = chaptersUpdated(fresh))
            }
        }
    }

    private suspend fun loadChapter(
        index: Int,
        restoreOffset: Int,
        restoreScrollFraction: Float,
        hint: PageHint = PageHint.OFFSET
    ) {
        // v1.2.0 (Wave 3): the OUTGOING chapter's final position is
        // persisted exactly, before anything moves — a guaranteed
        // flush on chapter change that the progress coalescer can
        // never delay (it reads the still-current state, and is a
        // no-op on the very first load, when there is no chapter).
        flushProgressNow()
        flushSession()
        val chapters = _state.value.chapters
        var chapter = chapters.getOrNull(index)
        if (chapter == null) {
            // v1.3.3 (scopes S4/S6): the snapshot may be STALE —
            // a sign-in completion or a stored-list repair can
            // replace the rows under the reader (FenrirRealm:
            // the pre-sign-in chapter was declared "isn't in
            // the book anymore" while sitting right there in
            // the post-login list). Re-read the stored list
            // once and retry the index before that copy may
            // appear; only a genuinely absent chapter earns it.
            val fresh = container.database.chapterDao().forBook(bookId)
            if (fresh.isNotEmpty()) {
                _state.value = _state.value.copy(
                    chapters = chaptersUpdated(fresh)
                )
                chapter = fresh.getOrNull(index)
            }
            if (chapter == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = "This chapter isn't in the book anymore."
                )
                return
            }
        }
        maybeGrowChapterList(index, chapters.size)
        readAloudStopIfLoaded()
        if (overlayPlayerLazy.isInitialized()) overlayPlayer.release()
        overlaySync = null
        // v1.2.0 (Wave 3): KEEP THE PREVIOUS CHAPTER VISIBLE. The
        // old blanking (chapterText = null, loading = true) put a
        // spinner over the whole fetch, so every network/render
        // cost was fully visible with nothing to read. Now the
        // previous text stays on screen until the replacement is
        // set in the success copy below; `loading` is raised only
        // when there is no previous content at all (first open),
        // which is also the only case ReaderScreen shows the
        // full-screen spinner for. positionOffset /
        // currentPositionBookmarked are NOT touched here — they
        // still describe the visible (previous) text until
        // onPosition runs for the new chapter.
        // Restore invariant: restoreGeneration is bumped ONLY in
        // the success copy, in the same state emission that sets
        // the new chapterText — a stale restore can therefore
        // never anchor into the previous chapter's text.
        _state.value = _state.value.copy(
            chapterIndex = index,
            loading = _state.value.chapterText == null,
            errorMessage = null,
            chapterFetchFailed = false,
            // v1.3.4 (U3): a new load clears the old failure's
            // classification and hand-off with its message.
            sessionNeed = null,
            sessionTargetUrl = null,
            fetchKind = null
        )
        if (chapter.contentPath == null) {
            // v1.0.6.2 (F3): read live — fetch the chapter
            // through the same download queue downloads use, so
            // a live read and a download are ONE stored copy
            // (same chapter identity, same progress: RD).
            chapter = fetchChapterLive(chapter) ?: return
        }
        // Phase 5: a RAW translation, when one exists, is the default
        // view of the chapter; the original file is untouched.
        val useTranslation = chapter.id !in originalPreferred &&
            container.bookStorage.hasTranslatedContent(bookId, chapter.id)
        val text = chapterCache[chapter.id] ?: when (
            val result = if (useTranslation) {
                container.bookStorage.readTranslatedContent(bookId, chapter.id)
            } else {
                container.bookStorage.readChapterContent(bookId, chapter.id)
            }
        ) {
            is AppResult.Ok -> ChapterText(result.value).also { cacheChapter(chapter.id, it) }
            is AppResult.Err -> {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = "This chapter's text couldn't be opened."
                )
                return
            }
        }
        val content: ChapterContent = text.content
        tracker.begin()
        currentChapterMaxOffset = restoreOffset
        visibleTextChapterIndex = index
        _state.value = _state.value.copy(
            loading = false,
            chapterText = text,
            restoreOffset = restoreOffset,
            restoreScrollFraction = restoreScrollFraction,
            restoreHint = hint,
            restoreGeneration = _state.value.restoreGeneration + 1,
            hasOverlay = content.mediaOverlay != null,
            isImageBook = content.isImageBook,
            showingTranslation = useTranslation
        )
        // Follow this chapter's cached AI summary for the Contents
        // sheet (clears first so a stale summary never shows).
        _chapterSummary.value = null
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            container.database.aiSummaryDao().observe(bookId, chapter.id)
                .collectLatest { _chapterSummary.value = it }
        }
        onPosition(restoreOffset, restoreScrollFraction)
        // Media Overlay: prepare (but don't play) the book's own audio.
        content.mediaOverlay?.let { overlay ->
            overlaySync = OverlaySync(overlay.clips)
            val audio = File(
                container.bookStorage.contentDir(bookId), "img-${overlay.audioFileName}"
            )
            overlayPlayer.load(audio)
        }
        if (pendingAutoplayTts) {
            pendingAutoplayTts = false
            readAloud.loadChapter(text, 0)
            readAloud.setRate(_config.value.speechRate)
            readAloud.play()
        }
        prefetchNeighbours(index)
    }

    /**
     * v1.0.6.2 (F3): fetch an un-downloaded chapter through the
     * download queue and wait for the stored copy. Returns the
     * refreshed chapter row, or null after setting the state's
     * honest error — a paid/locked chapter says so, another
     * failure offers a retry, and a slow fetch says the
     * download continues in the queue.
     */
    private suspend fun fetchChapterLive(chapter: Chapter): Chapter? {
        // v1.0.6.2 (F9): the fetch itself is the shared
        // ChapterTextLoader's — the same resolution every AI
        // feature uses; only the error WORDING lives here.
        return when (
            val outcome = container.chapterTextLoader.ensureStored(bookId, chapter)
        ) {
            is app.xreader.data.download.ChapterTextLoader.Outcome.Stored -> {
                val fresh = outcome.chapter
                // The stored copy landed: the state's list now
                // holds the same row downloads and progress use
                // (one chapter identity — RD).
                _state.value = _state.value.copy(
                    chapters = chaptersUpdated(
                        _state.value.chapters.map {
                            if (it.id == fresh.id) fresh else it
                        }
                    )
                )
                fresh
            }
            is app.xreader.data.download.ChapterTextLoader.Outcome.Failed -> {
                // v1.3.3 (scope S6): an EXTRACTION failure ("the
                // text wasn't found on the page") against a
                // possibly stale stored row is re-resolved
                // ONCE against a fresh list fetch before any
                // failure is shown — his Shadow Slave Ch.1 was
                // a phantom row pointing at a dead address
                // while the real chapter sat in the source's
                // list all along.
                if (isExtractionFailure(outcome.detail) &&
                    extractionRetriedFor.add(chapter.id)
                ) {
                    val resolved = reresolveChapter(chapter)
                    if (resolved != null) {
                        extractionRetriedFor.add(resolved.id)
                        return fetchChapterLive(resolved)
                    }
                }
                // v1.3.2 (A2/F): the wording names the TRUE cause.
                // A site check or sign-in gate offers the one-time
                // hand-off; a block says the site blocked it; the
                // generic branch shows the fetch's own reason and
                // never blames the connection for a gate.
                val need = app.xreader.data.extension.SessionNeed.classify(
                    bookConfig, outcome.detail, null
                )
                // v1.3.4 (U3): classification v2 ALSO runs here.
                // An availability (503/429) or DNS failure gets
                // its honest state and NO sign-in/check action —
                // neither can be solved by signing in. A mint
                // challenge (SESSION_CHECK) offers the check on
                // the source's READER page for this chapter when
                // the config declares one.
                val classification =
                    app.xreader.data.extension.FetchClassification
                        .classifyDetail(bookConfig, outcome.detail)
                val effectiveNeed = need ?: when (classification.kind) {
                    app.xreader.data.extension.FetchClassification
                        .Kind.SESSION_CHECK ->
                        app.xreader.data.extension.SessionNeed
                            .Kind.SITE_CHECK
                    app.xreader.data.extension.FetchClassification
                        .Kind.SIGN_IN ->
                        app.xreader.data.extension.SessionNeed
                            .Kind.SIGN_IN
                    else -> null
                }
                val checkTargetUrl = bookConfig?.credentials
                    ?.operationToken
                    ?.checkUrlFor(chapter.remoteId ?: "")
                when {
                    outcome.detail.contains("paid or locked", ignoreCase = true) &&
                        !signInConfig -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            errorMessage = "This chapter is paid or locked " +
                                "on the site, so it can't be read here.",
                            chapterFetchFailed = true,
                            fetchKind = classification.kind
                        )
                    }
                    classification.kind ==
                        app.xreader.data.extension.FetchClassification
                            .Kind.TEMPORARILY_UNAVAILABLE -> {
                        // The ErrorText words for a RateLimited:
                        // 503-flavoured "temporarily unavailable"
                        // vs 429-flavoured "too many requests",
                        // with the wait the site named. Retry only.
                        val words = app.xreader.core.error.ErrorText.of(
                            app.xreader.core.error.AppError.RateLimited(
                                serviceId = "site",
                                retryAfterSeconds =
                                    classification.retryAfterSeconds,
                                detail = if (classification.rateLimited) {
                                    "429"
                                } else {
                                    "503"
                                }
                            )
                        ).message
                        _state.value = _state.value.copy(
                            loading = false,
                            errorMessage = words,
                            chapterFetchFailed = true,
                            sessionNeed = null,
                            sessionTargetUrl = null,
                            fetchKind = classification.kind
                        )
                    }
                    classification.kind ==
                        app.xreader.data.extension.FetchClassification
                            .Kind.DNS_FAILURE -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            errorMessage = "This chapter couldn't be " +
                                "read: " +
                                app.xreader.data.extension
                                    .FetchClassification.DNS_MARKER +
                                ". Try again in a little while.",
                            chapterFetchFailed = true,
                            sessionNeed = null,
                            sessionTargetUrl = null,
                            fetchKind = classification.kind
                        )
                    }
                    effectiveNeed == app.xreader.data.extension.SessionNeed.Kind.SITE_CHECK -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            errorMessage = "This site is asking for a quick " +
                                "human check before it shows the chapter.",
                            chapterFetchFailed = true,
                            sessionNeed = effectiveNeed,
                            sessionTargetUrl = checkTargetUrl,
                            fetchKind = classification.kind
                        )
                    }
                    effectiveNeed == app.xreader.data.extension.SessionNeed.Kind.SIGN_IN -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            errorMessage = "This chapter needs you to sign " +
                                "in on the site first — sign in once and " +
                                "it opens here like any other chapter.",
                            chapterFetchFailed = true,
                            sessionNeed = effectiveNeed,
                            sessionTargetUrl = checkTargetUrl,
                            fetchKind = classification.kind
                        )
                    }
                    outcome.detail.contains("HTTP 401") ||
                        outcome.detail.contains("HTTP 403") ||
                        outcome.detail.contains("HTTP 429") -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            errorMessage = "The site blocked this chapter " +
                                "load. Trying again in a moment sometimes " +
                                "helps; if it keeps happening, the site " +
                                "may be limiting readers right now.",
                            chapterFetchFailed = true,
                            fetchKind = classification.kind
                        )
                    }
                    else -> {
                        _state.value = _state.value.copy(
                            loading = false,
                            errorMessage = "This chapter couldn't be read: " +
                                outcome.detail,
                            chapterFetchFailed = true,
                            fetchKind = classification.kind
                        )
                    }
                }
                null
            }
            app.xreader.data.download.ChapterTextLoader.Outcome.TimedOut -> {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = "This chapter is taking a long time to arrive. " +
                        "It's still on its way — check Downloads, or try again.",
                    chapterFetchFailed = true
                )
                null
            }
        }
    }

    /** F3: the error block's retry — re-runs the current
     * chapter's load; its fetch resumes through the queue. */
    fun retryCurrentChapter() {
        launchLoad { loadChapter(_state.value.chapterIndex, 0, 0f) }
    }

    /** v1.3.3 (scope S6): chapters whose extraction failure
     * already earned its one re-resolution this session. */
    private val extractionRetriedFor = mutableSetOf<Long>()

    private fun isExtractionFailure(detail: String): Boolean =
        detail.contains("wasn't found on the page", ignoreCase = true) ||
            detail.contains("no readable text", ignoreCase = true)

    /**
     * v1.3.3 (scopes S4/S6): re-resolve a chapter against a
     * FRESH list fetch — the stored rows may have been
     * written before a sign-in, or by an older buggy sync.
     * Matching is by identity, never by position: same row
     * id, then canonical address, then the chapter's own
     * number. Returns the fresh row, or null when the book
     * isn't a source book or the refresh itself failed.
     */
    private suspend fun reresolveChapter(chapter: Chapter): Chapter? {
        val book = container.database.bookDao().getById(bookId)
            ?: return null
        if (book.sourceType !=
            app.xreader.data.db.SourceType.EXTENSION
        ) {
            return null
        }
        if (container.sourceBookSync.refresh(book)
                !is AppResult.Ok
        ) {
            return null
        }
        val rows = container.database.chapterDao().forBook(bookId)
        if (rows.isNotEmpty()) {
            _state.value = _state.value.copy(
                chapters = chaptersUpdated(rows)
            )
        }
        rows.firstOrNull { it.id == chapter.id }?.let { return it }
        val key = app.xreader.data.extension.ChapterIdentity
            .canonicalKey(chapter.remoteId ?: "")
        rows.firstOrNull {
            app.xreader.data.extension.ChapterIdentity
                .canonicalKey(it.remoteId ?: "") == key
        }?.let { return it }
        val number = app.xreader.domain.reader.ChapterDisplayOrder
            .numberOf(chapter)
        if (number != null) {
            rows.firstOrNull {
                app.xreader.domain.reader.ChapterDisplayOrder
                    .numberOf(it) == number
            }?.let { return it }
        }
        return null
    }

    /** Revision 2 (P1): is this device on the MINIMAL
     * performance tier (the 2 GB tablet's class)? Resolved the
     * same way the stream window resolves it. */
    private suspend fun isMinimalTier(): Boolean {
        val appearance = container.appearancePrefs.state.first()
        return app.xreader.data.prefs.PerformancePolicy.resolve(
            appearance.performanceMode, container.totalRamMb(),
            appearance.coverGlow
        ).tier == app.xreader.data.prefs.PerfTier.MINIMAL
    }

    private fun cacheChapter(chapterId: Long, text: ChapterText) {
        chapterCache[chapterId] = text
        // Keep at most 3 chapters resident (current + neighbours) —
        // a 1,500-chapter book never sits in RAM (§6 streaming rule).
        while (chapterCache.size > 3) {
            val eldest = chapterCache.keys.firstOrNull { it != chapterId } ?: break
            chapterCache.remove(eldest)
        }
    }

    private fun prefetchNeighbours(index: Int) {
        viewModelScope.launch {
            val chapters = _state.value.chapters
            for (neighbour in listOf(index - 1, index + 1)) {
                val chapter = chapters.getOrNull(neighbour) ?: continue
                if (chapter.contentPath == null) {
                    // v1.0.6.2 (F3): warm the NEXT chapter through
                    // the download queue, so turning the page
                    // doesn't wait on the network. One ahead only,
                    // and only when nothing is queued for it yet
                    // (the queue insert doesn't dedupe).
                    // Revision 2 (P1): prefetch is OFF on the
                    // MINIMAL tier — nothing warms in the
                    // background on the 2 GB tablet.
                    if (neighbour == index + 1 &&
                        chapter.downloadState ==
                        app.xreader.data.db.DownloadState.NONE &&
                        !isMinimalTier() &&
                        // v1.2.0: never queue-warm a chapter the
                        // direct near-end warm is already fetching.
                        imagePrefetchChapterId != chapter.id
                    ) {
                        runCatching {
                            container.downloadController
                                .enqueueChapter(bookId, chapter.id)
                        }
                    }
                    continue
                }
                if (chapterCache.containsKey(chapter.id)) continue
                val result = container.bookStorage.readChapterContent(bookId, chapter.id)
                if (result is AppResult.Ok) cacheChapter(chapter.id, ChapterText(result.value))
            }
        }
    }

    /**
     * v1.2.0 (Wave 3): the comic near-end warm (USER-SPECIFIED
     * policy — see [PrefetchPolicy]). Called from [onPosition]
     * with the settled in-chapter fraction: when an IMAGE chapter
     * crosses ~0.7 read, the NEXT chapter is warmed through the
     * DIRECT read path ([app.xreader.data.download.ChapterTextLoader.ensureStored]
     * — the same path a manual open uses), NOT only via the
     * download queue, whose item for a rendered source can sit
     * browser-gated behind other work. One warm at a time, next
     * chapter only; text chapters are untouched by this path.
     * The policy applies on all performance tiers (the user
     * stated it unconditionally; next-only bounds the cost) — the
     * MINIMAL-tier gate on the queue prefetch above does NOT
     * apply here.
     */
    private fun maybeWarmNextImageChapter(chapterFraction: Float) {
        val stateNow = _state.value
        val text = stateNow.chapterText ?: return
        val next = stateNow.chapters.getOrNull(stateNow.chapterIndex + 1)
        val shouldWarm = PrefetchPolicy.shouldWarmNext(
            isImageChapter = ReaderCounters.isImageChapter(text.content),
            fraction = chapterFraction,
            hasNext = next != null,
            nextStored = next?.contentPath != null,
            inFlight = imagePrefetchJob?.isActive == true
        )
        if (!shouldWarm || next == null) return
        imagePrefetchChapterId = next.id
        imagePrefetchJob = viewModelScope.launch {
            try {
                val outcome = container.chapterTextLoader
                    .ensureStored(bookId, next)
                if (outcome is
                    app.xreader.data.download.ChapterTextLoader.Outcome.Stored
                ) {
                    val fresh = outcome.chapter
                    // The state's row becomes the stored one, so
                    // the turn onto this chapter is a local read
                    // and later triggers see nextStored = true.
                    _state.value = _state.value.copy(
                        chapters = chaptersUpdated(
                            _state.value.chapters.map {
                                if (it.id == fresh.id) fresh else it
                            }
                        )
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // Warming is best-effort: a failure leaves the
                // chapter unstored and the manual open path will
                // surface its own honest error if it also fails.
            } finally {
                if (imagePrefetchChapterId == next.id) {
                    imagePrefetchChapterId = null
                }
            }
        }
    }

    // ── The endless stream (v1.0.2, R17) ─────────────────────────
    //
    // Scroll + Continuous renders ONE LazyColumn over a window of
    // chapters planned by StreamWindowPlanner (behind 2 reserved,
    // forward growth inside a tier-scaled char budget). The window
    // slides as the reader moves; position is always reported as
    // (chapterIndex, charOffset) into the same progress machinery
    // the single-chapter reader uses, so bookmarks, sessions and
    // sync don't know the stream exists.

    private val streamTexts = mutableMapOf<Long, ChapterText>()
    private var streamJob: kotlinx.coroutines.Job? = null
    private val streamFractions = mutableMapOf<Int, Float>()

    private suspend fun loadStreamText(chapter: Chapter): ChapterText? {
        streamTexts[chapter.id]?.let { return it }
        if (chapter.contentPath == null) return null
        val useTranslation = chapter.id !in originalPreferred &&
            container.bookStorage.hasTranslatedContent(bookId, chapter.id)
        val result = if (useTranslation) {
            container.bookStorage.readTranslatedContent(bookId, chapter.id)
        } else {
            container.bookStorage.readChapterContent(bookId, chapter.id)
        }
        return (result as? AppResult.Ok)?.value?.let { ChapterText(it) }
            ?.also { streamTexts[chapter.id] = it }
    }

    /** Open (or re-anchor) the stream at [anchorIndex]. */
    fun openStream(anchorIndex: Int, anchorOffset: Int = 0) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val chapters = _state.value.chapters
            if (chapters.isEmpty()) return@launch
            val appearance = container.appearancePrefs.state.first()
            val policy = app.xreader.data.prefs.PerformancePolicy.resolve(
                appearance.performanceMode, container.totalRamMb(),
                appearance.coverGlow
            )
            val counts = chapters.map { (it.wordCount * 6).coerceAtLeast(1) }
            val window = StreamWindowPlanner.plan(
                anchorIndex = anchorIndex,
                chapterCharCounts = counts,
                charBudget = StreamWindowPlanner.charBudgetFor(
                    policy.tier == app.xreader.data.prefs.PerfTier.MINIMAL
                )
            )
            val entries = buildStreamEntries(
                window, anchorIndex,
                minimalTier = policy.tier ==
                    app.xreader.data.prefs.PerfTier.MINIMAL
            )
            val anchorEntry = entries.firstOrNull { it.chapterIndex == anchorIndex }
            if (anchorEntry?.text != null) {
                visibleTextChapterIndex = anchorIndex
            }
            _state.value = _state.value.copy(
                streamActive = true,
                streamEntries = entries,
                loading = false,
                errorMessage = null,
                chapterIndex = anchorIndex,
                chapterText = anchorEntry?.text ?: _state.value.chapterText,
                restoreOffset = anchorOffset,
                restoreGeneration = _state.value.restoreGeneration + 1
            )
            anchorEntry?.text?.let { onPosition(anchorOffset, 0f) }
        }
    }

    private suspend fun buildStreamEntries(
        window: StreamWindowPlanner.Window,
        anchorIndex: Int,
        minimalTier: Boolean
    ): List<StreamEntry> {
        val chapters = _state.value.chapters
        val entries = mutableListOf<StreamEntry>()
        for (index in window.fromIndex..window.toIndex) {
            val chapter = chapters.getOrNull(index) ?: continue
            val text = loadStreamText(chapter)
            entries += StreamEntry(
                chapterIndex = index,
                chapterId = chapter.id,
                title = chapter.title,
                text = text,
                missing = text == null
            )
        }
        // v1.0.6.2 (F3-stream): a window chapter with no copy on
        // the phone is pulled LIVE through the same download
        // queue as everything else — the scroll chains straight
        // into it instead of stopping at a download prompt. The
        // stream is served first: these fills ARE the reader's
        // prefetch now. On the MINIMAL tier (P1) only the
        // anchor chapter itself is pulled — lookahead fills are
        // prefetch, and prefetch is off there.
        for (position in entries.indices) {
            val entry = entries[position]
            if (!entry.missing) continue
            if (minimalTier && entry.chapterIndex != anchorIndex) continue
            if (startStreamLiveFetch(entry.chapterIndex)) {
                entries[position] = entry.copy(fetching = true)
            }
        }
        return entries
    }

    /** Chapters the stream is already pulling live — one fetch
     * per chapter, however many triggers ask for it. */
    private val streamLiveFetches = mutableSetOf<Long>()

    /**
     * v1.0.6.2 (F3-stream): fetch a stream chapter that isn't on
     * the phone yet, through the download queue, and fill its
     * block in when the stored copy lands (the state's chapter
     * row becomes the stored one — one identity, as everywhere).
     * A failed fetch leaves the honest missing block standing;
     * its reason is already in the diagnostic log. Returns true
     * when a fetch is in flight for the chapter afterwards.
     */
    private fun startStreamLiveFetch(index: Int): Boolean {
        val chapter = _state.value.chapters.getOrNull(index) ?: return false
        if (chapter.contentPath != null) return false
        if (!streamLiveFetches.add(chapter.id)) return true
        _state.value = _state.value.copy(
            streamEntries = _state.value.streamEntries.map {
                if (it.chapterIndex == index && it.text == null) {
                    it.copy(fetching = true)
                } else {
                    it
                }
            }
        )
        viewModelScope.launch {
            try {
                // v1.0.6.2 (F9): the wait is the shared loader's —
                // one fetch path for reader, stream and AI alike.
                val outcome = container.chapterTextLoader
                    .ensureStored(bookId, chapter)
                if (outcome is
                    app.xreader.data.download.ChapterTextLoader.Outcome.Stored
                ) {
                    val fresh = outcome.chapter
                    val text = loadStreamText(fresh)
                    _state.value = _state.value.copy(
                        chapters = chaptersUpdated(
                            _state.value.chapters.map {
                                if (it.id == fresh.id) fresh else it
                            }
                        ),
                        streamEntries = _state.value.streamEntries.map {
                            if (it.chapterIndex == index) {
                                it.copy(
                                    text = text,
                                    missing = text == null,
                                    fetching = false
                                )
                            } else {
                                it
                            }
                        }
                    )
                }
            } finally {
                streamLiveFetches.remove(chapter.id)
                // Never leave a block claiming a fetch that ended.
                _state.value = _state.value.copy(
                    streamEntries = _state.value.streamEntries.map {
                        if (it.chapterIndex == index && it.text == null) {
                            it.copy(fetching = false)
                        } else {
                            it
                        }
                    }
                )
            }
        }
        return true
    }

    /** The stream reader reports its settled position here. */
    fun onStreamPosition(chapterIndex: Int, charOffset: Int, chapterFraction: Float) {
        val stateNow = _state.value
        val entry = stateNow.streamEntries.firstOrNull { it.chapterIndex == chapterIndex }
        val text = entry?.text ?: return
        val previousIndex = stateNow.chapterIndex
        streamFractions[chapterIndex] = chapterFraction
        if (chapterIndex != previousIndex) {
            // Crossing into a new chapter: the one left behind at
            // ≥90% read counts as finished (same rule the paged
            // reader's turns apply, honestly labelled).
            val leftFraction = streamFractions[previousIndex] ?: 0f
            visibleTextChapterIndex = chapterIndex
            _state.value = stateNow.copy(
                chapterIndex = chapterIndex,
                chapterText = text
            )
            if (chapterIndex > previousIndex && leftFraction >= 0.9f) {
                markCurrentChapterFinishedFor(previousIndex)
            }
        }
        onPosition(charOffset, chapterFraction)
        // Slide the window when the reader nears either edge.
        val entries = _state.value.streamEntries
        val first = entries.firstOrNull()?.chapterIndex ?: return
        val last = entries.lastOrNull()?.chapterIndex ?: return
        if (chapterIndex >= last - 1 || chapterIndex <= first + 1) {
            extendStream(chapterIndex)
        }
    }

    private fun markCurrentChapterFinishedFor(index: Int) {
        val chapter = _state.value.chapters.getOrNull(index) ?: return
        if (!finishedChapterIds.add(chapter.id)) return
        tracker.addChaptersFinished()
        completedSessionWords += chapter.wordCount
        viewModelScope.launch { container.database.chapterDao().setRead(chapter.id, true) }
        container.aiChapterHooks.onChapterRead(bookId, chapter.id)
        _state.value = _state.value.copy(
            chapters = chaptersUpdated(
                _state.value.chapters.map {
                    if (it.id == chapter.id) it.copy(read = true) else it
                }
            )
        )
    }

    private fun extendStream(anchorIndex: Int) {
        if (streamJob?.isActive == true) return
        streamJob = viewModelScope.launch {
            val chapters = _state.value.chapters
            if (chapters.isEmpty()) return@launch
            val appearance = container.appearancePrefs.state.first()
            val policy = app.xreader.data.prefs.PerformancePolicy.resolve(
                appearance.performanceMode, container.totalRamMb(),
                appearance.coverGlow
            )
            val counts = chapters.map { (it.wordCount * 6).coerceAtLeast(1) }
            val window = StreamWindowPlanner.plan(
                anchorIndex = anchorIndex,
                chapterCharCounts = counts,
                charBudget = StreamWindowPlanner.charBudgetFor(
                    policy.tier == app.xreader.data.prefs.PerfTier.MINIMAL
                )
            )
            val current = _state.value.streamEntries
            if (current.isNotEmpty() &&
                window.fromIndex == current.first().chapterIndex &&
                window.toIndex == current.last().chapterIndex
            ) {
                return@launch
            }
            val entries = buildStreamEntries(
                window, anchorIndex,
                minimalTier = policy.tier ==
                    app.xreader.data.prefs.PerfTier.MINIMAL
            )
            // Release texts that fell out of the window (the
            // planner's whole point); the anchor's text survives
            // because the window always contains the anchor.
            val keepIds = entries.map { it.chapterId }.toSet()
            streamTexts.keys.retainAll(keepIds)
            _state.value = _state.value.copy(streamEntries = entries)
        }
    }

    /** R17: a not-yet-downloaded chapter inside the stream can be
     * fetched in place; when it lands, its block fills in.
     * v1.0.6.2: this is the same live pull the stream now starts
     * on its own when a window chapter is missing — the tap is
     * just the explicit retry of it. */
    fun downloadStreamChapter(index: Int) {
        startStreamLiveFetch(index)
    }

    /** Leaving the stream (a chapter jump in chapter-by-chapter
     * terms, or closing): the single-chapter path takes over. */
    fun closeStream() {
        streamJob?.cancel()
        if (_state.value.streamActive) {
            _state.value = _state.value.copy(streamActive = false)
        }
    }

    // -- Position & progress -----------------------------------------------------

    /** Renderers report every settled position here (char offset). */
    fun onPosition(charOffset: Int, scrollFraction: Float) {
        val text = _state.value.chapterText ?: return
        val clamped = charOffset.coerceIn(0, text.length)
        if (clamped > currentChapterMaxOffset) currentChapterMaxOffset = clamped
        tracker.onActivity()
        // v1.1.2: an image chapter's progress cannot be read
        // off char offsets — its blocks are zero-width in the
        // chapter text, so the offset fraction sits at ~0
        // however much of the strip has been read, and the
        // saved progress would claim the chapter was barely
        // entered. The renderer's own fraction (scroll or
        // page) is the honest one there; text chapters keep
        // the char-derived fraction exactly as before.
        val chapterFraction = if (ReaderCounters.isImageChapter(text.content)) {
            scrollFraction.coerceIn(0f, 1f)
        } else {
            PageLocator.chapterFraction(clamped, text.length)
        }
        // v1.2.0 (Wave 3, audit #2): the current chapter is
        // resolved through the id→index map built when the list
        // changed — O(1) — instead of any per-settle scan of the
        // list. (The audit's claimed linear `firstOrNull` lookup
        // does not exist in this tree; the real per-settle linear
        // cost was the drop+map+estimate over EVERY later chapter
        // below, plus the remainder-of-chapter word count, which
        // is bounded by the chapter, not the book.)
        val stateBefore = _state.value
        val chapterIndex = chapterPositionIndex
            .indexOfChapter(stateBefore.chapter?.id)
            ?: stateBefore.chapterIndex
        val bookFraction = PageLocator.bookFraction(
            chapterIndex, chapterFraction, stateBefore.chapters.size
        )
        val estimator = container.wpmEstimator
        val wordsLeftChapter =
            ChapterContent.countWords(text.text.substring(clamped.coerceAtMost(text.length)))
        // v1.0.7 draw-check fix: the book remainder used to
        // sum only the word counts already known, so a source
        // book showed "≈17 min in book" at chapter 87 of
        // 1,692 — the book page's old lie, still alive in the
        // reader. Later chapters go through the same estimator
        // as the book page: chapters whose length isn't known
        // count at this book's own average. v1.2.0: that estimate
        // is now read from the index's precomputed suffix
        // aggregates — O(1), same value (pinned against
        // WpmEstimator.estimateRemainingMinutes by tests).
        val laterMinutes = chapterPositionIndex
            .remainingMinutesAfter(chapterIndex, estimator.wpm())
        val minutesLeftChapter = estimator.minutesLeft(wordsLeftChapter)
        val bookmarked = _bookmarks.value.any {
            it.chapterIndex == chapterIndex &&
                kotlin.math.abs(it.charOffset - clamped) <= BOOKMARK_SNAP_CHARS
        }
        // Narrowing (audit #2c): if nothing the UI reads actually
        // changed, don't republish the whole reader state at all.
        // The state itself stays a single StateFlow — ReaderScreen
        // collects it whole and position fields are interwoven
        // with it (chapterFraction drives the chrome slider,
        // positionOffset keys the AI bind and the bookmark
        // toggle), so a separate position flow would need dual
        // collection at every consumer for no cheaper copy.
        if (stateBefore.positionOffset != clamped ||
            stateBefore.chapterFraction != chapterFraction ||
            stateBefore.bookFraction != bookFraction ||
            stateBefore.minutesLeftInChapter != minutesLeftChapter ||
            stateBefore.minutesLeftInBook != minutesLeftChapter + laterMinutes ||
            stateBefore.currentPositionBookmarked != bookmarked
        ) {
            _state.value = stateBefore.copy(
                positionOffset = clamped,
                chapterFraction = chapterFraction,
                bookFraction = bookFraction,
                minutesLeftInChapter = minutesLeftChapter,
                minutesLeftInBook = minutesLeftChapter + laterMinutes,
                currentPositionBookmarked = bookmarked
            )
        }
        scheduleProgressSave()
        maybeWarmNextImageChapter(chapterFraction)
    }

    /**
     * v1.2.0 (Wave 3): progress writes are COALESCED through
     * [ProgressSaveCoalescer] — a write lands on a settle, but
     * never more often than the coarse interval during continuous
     * scrolling, so a long read no longer invalidates the
     * whole-library Flows every ~1.5 s. Each new position
     * reschedules the single pending write at the coalescer's
     * due time; forced flushes (chapter change, pause) bypass it.
     */
    private fun scheduleProgressSave() {
        val now = System.currentTimeMillis()
        val dueMs = progressCoalescer.onPosition(now)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay((dueMs - System.currentTimeMillis()).coerceAtLeast(0L))
            saveProgressNow()
        }
    }

    /** The guaranteed flush: cancel any coalesced write and
     * persist the CURRENT position immediately and exactly. */
    private suspend fun flushProgressNow() {
        saveJob?.cancel()
        saveProgressNow()
    }

    private suspend fun saveProgressNow() {
        val stateNow = _state.value
        val chapter = stateNow.chapter ?: return
        // Keep-previous guard: while a chapter load is in flight
        // the visible text (and the position describing it) still
        // belongs to the outgoing chapter — never write it against
        // the incoming chapter's row. The incoming chapter's own
        // onPosition resumes saves the moment its text lands.
        if (visibleTextChapterIndex != stateNow.chapterIndex) return
        val now = System.currentTimeMillis()
        container.database.readingProgressDao().upsert(
            ReadingProgress(
                bookId = bookId,
                chapterId = chapter.id,
                chapterIndex = stateNow.chapterIndex,
                charOffset = stateNow.positionOffset,
                fraction = stateNow.bookFraction,
                scrollFraction = stateNow.chapterFraction,
                updatedAt = now
            )
        )
        container.database.bookDao().getById(bookId)?.let { book ->
            // v1.3.1 (item 45): actually READING a chapter is
            // one of the three acts that make a preview row a
            // Library member — it flips here, unthrottled,
            // alongside the (throttled) lastOpenedAt stamp.
            val flip = !book.inLibrary
            if (flip || book.lastOpenedAt == null ||
                now - (book.lastOpenedAt ?: 0) > 30_000L
            ) {
                container.database.bookDao()
                    .update(
                        book.copy(
                            lastOpenedAt = now,
                            updatedAt = now,
                            inLibrary = true
                        )
                    )
            }
        }
        progressCoalescer.markPersisted(now)
    }

    /** Scroll-mode end-of-chapter detection + explicit paged turns. */
    fun markCurrentChapterFinished() {
        val chapter = _state.value.chapter ?: return
        if (!finishedChapterIds.add(chapter.id)) return
        tracker.addChaptersFinished()
        completedSessionWords += chapter.wordCount
        viewModelScope.launch { container.database.chapterDao().setRead(chapter.id, true) }
        // Phase 5: finishing a chapter is the moment LoreKeeper
        // studies it (guarded + best-effort inside the hook).
        container.aiChapterHooks.onChapterRead(bookId, chapter.id)
        _state.value = _state.value.copy(
            chapters = chaptersUpdated(
                _state.value.chapters.map {
                    if (it.id == chapter.id) it.copy(read = true) else it
                }
            )
        )
    }

    fun onScrollReachedEnd() = markCurrentChapterFinished()

    // -- Sessions ------------------------------------------------------------------

    private suspend fun flushSession() {
        if (!tracker.isRunning) return
        val text = _state.value.chapterText
        // A chapter already marked finished contributed its full word
        // count to completedSessionWords — never count it twice.
        val currentAlreadyCounted =
            _state.value.chapter?.id?.let { it in finishedChapterIds } ?: false
        val currentWords = if (text != null && !currentAlreadyCounted) {
            ChapterContent.countWords(
                text.text.substring(0, currentChapterMaxOffset.coerceAtMost(text.length))
            ).toLong()
        } else {
            0L
        }
        val totalWords = completedSessionWords + currentWords
        val delta = totalWords - lastFlushedWords
        if (delta > 0) tracker.addWords(delta.toInt())
        lastFlushedWords = totalWords
        val snapshot = tracker.end()
        container.sessionRecorder.record(sessionBookId, snapshot)
        if (pendingFocusBonus) {
            pendingFocusBonus = false
            container.sessionRecorder.markFocusBonus(sessionBookId)
        }
        // Phase 6: pay out EXP for what was just recorded (the
        // settle is idempotent — re-entry changes nothing).
        runCatching { container.expService.settle() }
        // A new segment begins with the next chapter load.
        finishedChapterIds.clear()
        completedSessionWords = 0L
        lastFlushedWords = 0L
        currentChapterMaxOffset = 0
    }

    /** Lifecycle hook from the screen: progress + session flush.
     * v1.2.0: the progress write is a forced flush — leaving /
     * backgrounding the reader always persists the exact final
     * position, whatever the coalescer's schedule said. */
    fun onReaderPaused() {
        viewModelScope.launch {
            flushProgressNow()
            tracker.pause()
        }
    }

    fun onReaderResumed() {
        tracker.resume()
        tracker.onActivity()
    }

    override fun onCleared() {
        // Best-effort final flush on a fire-and-forget scope: the
        // container outlives the VM, and Room writes are quick.
        // v1.2.0: the reader's final position is part of that
        // flush — captured synchronously from the state (the VM
        // scope is already gone), written on the same IO scope, so
        // leaving the reader can never lose the exact place.
        val stateAtClear = _state.value
        // Same keep-previous guard as saveProgressNow: a clear
        // mid-load must not write the visible text's position
        // against the incoming chapter's row.
        val chapterAtClear = stateAtClear.chapter
            ?.takeIf { visibleTextChapterIndex == stateAtClear.chapterIndex }
        val snapshot = if (tracker.isRunning) tracker.end() else null
        if (snapshot != null || chapterAtClear != null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                if (chapterAtClear != null) {
                    runCatching {
                        container.database.readingProgressDao().upsert(
                            ReadingProgress(
                                bookId = bookId,
                                chapterId = chapterAtClear.id,
                                chapterIndex = stateAtClear.chapterIndex,
                                charOffset = stateAtClear.positionOffset,
                                fraction = stateAtClear.bookFraction,
                                scrollFraction = stateAtClear.chapterFraction,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
                if (snapshot != null) {
                    container.sessionRecorder.record(sessionBookId, snapshot)
                    runCatching { container.expService.settle() }
                }
            }
        }
        if (readAloudLazy.isInitialized()) readAloud.shutdown()
        if (overlayPlayerLazy.isInitialized()) overlayPlayer.release()
        if (soundscapePlayerLazy.isInitialized()) soundscapePlayer.stop()
        if (eyeTrackerLazy.isInitialized()) eyeTracker.stop()
        super.onCleared()
    }

    // -- Read-aloud controls ---------------------------------------------------------

    fun listenPlayPause() {
        val text = _state.value.chapterText ?: return
        if (readAloud.state.value.playing) {
            readAloud.pause()
        } else {
            if (readAloud.state.value.sentenceCount == 0) {
                readAloud.loadChapter(text, _state.value.positionOffset)
                readAloud.setRate(_config.value.speechRate)
            }
            readAloud.play()
        }
    }

    fun ttsSkipNext() {
        if (readAloudLazy.isInitialized()) readAloud.skipNext()
    }

    fun ttsSkipPrevious() {
        if (readAloudLazy.isInitialized()) readAloud.skipPrevious()
    }

    fun setSpeechRate(rate: Float) {
        viewModelScope.launch {
            container.readerPrefs.update { it.copy(speechRate = rate) }
        }
        if (readAloudLazy.isInitialized()) readAloud.setRate(rate)
        if (overlayPlayerLazy.isInitialized()) overlayPlayer.setRate(rate)
    }

    fun armSleep(mode: SleepMode) {
        if (readAloudLazy.isInitialized()) readAloud.armSleep(mode)
    }

    private fun readAloudStopIfLoaded() {
        if (readAloudLazy.isInitialized()) readAloud.stopForChapterChange()
    }

    // -- Overlay controls ------------------------------------------------------------

    fun overlayPlayPause() {
        val playerState = overlayPlayer.state.value
        if (playerState.playing) overlayPlayer.pause() else overlayPlayer.play()
    }

    fun overlaySeek(positionSec: Double) {
        overlayPlayer.seekTo(positionSec)
    }

    fun overlaySkip(next: Boolean) {
        val sync = overlaySync ?: return
        val position = overlayPlayer.state.value.positionSec
        val target = if (next) sync.nextClip(position) else sync.previousClip(position)
        if (target != null) overlayPlayer.seekTo(target.beginSec + 0.01)
    }

    fun overlayFileForUi(): File? {
        val overlay = _state.value.chapterText?.content?.mediaOverlay ?: return null
        return File(container.bookStorage.contentDir(bookId), "img-${overlay.audioFileName}")
    }

    // -- Focus timer controls -----------------------------------------------------------

    fun focusStart(minutes: Int, soundscape: Soundscape) {
        focusTimer.start(minutes, soundscape)
        soundscapePlayer.play(soundscape)
        publishFocus()
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            while (true) {
                delay(500L)
                val completed = focusTimer.tick()
                publishFocus()
                if (completed) {
                    soundscapePlayer.stop()
                    pendingFocusBonus = true
                    tracker.onActivity()
                    break
                }
                if (focusTimer.state == FocusState.IDLE) break
            }
        }
    }

    fun focusPauseResume() {
        when (focusTimer.state) {
            FocusState.RUNNING -> {
                focusTimer.pause()
                if (soundscapePlayerLazy.isInitialized()) soundscapePlayer.stop()
            }
            FocusState.PAUSED -> {
                focusTimer.resume()
                        soundscapePlayer.play(focusTimer.soundscape)
            }
            else -> Unit
        }
        publishFocus()
    }

    fun focusStop() {
        focusTimer.stop()
        focusJob?.cancel()
        if (soundscapePlayerLazy.isInitialized()) soundscapePlayer.stop()
        publishFocus()
    }

    fun focusAcknowledgeDone() {
        focusTimer.acknowledgeDone()
        publishFocus()
    }

    private fun publishFocus() {
        _focus.value = FocusUiState(
            state = focusTimer.state,
            remainingMs = focusTimer.remainingMs(),
            durationMinutes = focusTimer.durationMinutes,
            soundscape = focusTimer.soundscape,
            completedUninterrupted = focusTimer.completedUninterrupted
        )
    }

    // -- Translated view (Phase 5) -----------------------------------------------

    /** A RAW translation just finished for [chapterId]: drop the
     * cached original and reload if that chapter is on screen. */
    fun onTranslationReady(chapterId: Long) {
        originalPreferred.remove(chapterId)
        chapterCache.remove(chapterId)
        val stateNow = _state.value
        if (stateNow.chapter?.id == chapterId) {
            launchLoad {
                loadChapter(
                    stateNow.chapterIndex,
                    stateNow.positionOffset,
                    stateNow.chapterFraction
                )
            }
        }
    }

    fun showOriginalChapter() {
        val chapter = _state.value.chapter ?: return
        originalPreferred.add(chapter.id)
        chapterCache.remove(chapter.id)
        val stateNow = _state.value
        launchLoad {
            loadChapter(
                stateNow.chapterIndex,
                stateNow.positionOffset,
                stateNow.chapterFraction
            )
        }
    }

    fun showTranslatedChapter() {
        val chapter = _state.value.chapter ?: return
        originalPreferred.remove(chapter.id)
        chapterCache.remove(chapter.id)
        val stateNow = _state.value
        launchLoad {
            loadChapter(
                stateNow.chapterIndex,
                stateNow.positionOffset,
                stateNow.chapterFraction
            )
        }
    }

    // -- Bookmarks & highlights -----------------------------------------------------------

    fun addBookmarkHere() {
        val stateNow = _state.value
        val chapter = stateNow.chapter ?: return
        viewModelScope.launch {
            container.database.bookmarkDao().insert(
                Bookmark(
                    bookId = bookId,
                    chapterId = chapter.id,
                    chapterIndex = stateNow.chapterIndex,
                    charOffset = stateNow.positionOffset,
                    label = "",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun removeBookmarkAtCurrentPosition() {
        val stateNow = _state.value
        val target = _bookmarks.value.firstOrNull {
            it.chapterIndex == stateNow.chapterIndex &&
                kotlin.math.abs(it.charOffset - stateNow.positionOffset) <= BOOKMARK_SNAP_CHARS
        } ?: return
        deleteBookmark(target)
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            container.database.bookmarkDao().delete(bookmark)
            // Tombstone so the deletion travels to other devices
            // instead of the bookmark resurrecting on sync (§7).
            container.database.bookDao().getById(bookId)?.let { book ->
                val key = container.syncService.bookKeyFor(book)
                container.syncService.recordDeletion(
                    "bookmark",
                    app.xreader.data.sync.BookmarkRecord(
                        bookKey = key,
                        chapterIndex = bookmark.chapterIndex,
                        charOffset = bookmark.charOffset,
                        label = bookmark.label
                    ).naturalKey
                )
            }
        }
    }

    fun updateBookmarkLabel(bookmark: Bookmark, label: String) {
        viewModelScope.launch {
            // BookmarkDao has insert/delete only; label edits ride a
            // delete+insert preserving the row's identity fields.
            container.database.bookmarkDao().delete(bookmark)
            container.database.bookmarkDao().insert(
                bookmark.copy(id = 0, label = label, updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun addHighlight(start: Int, end: Int, note: String? = null) {
        val stateNow = _state.value
        val chapter = stateNow.chapter ?: return
        val text = stateNow.chapterText ?: return
        if (start < 0 || end > text.length || start >= end) return
        viewModelScope.launch {
            container.database.highlightDao().insert(
                Highlight(
                    bookId = bookId,
                    chapterId = chapter.id,
                    startOffset = start,
                    endOffset = end,
                    quotedText = text.text.substring(start, end).take(500),
                    color = "amber",
                    note = note?.takeIf { it.isNotBlank() },
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateHighlightNote(highlight: Highlight, note: String?) {
        viewModelScope.launch {
            container.database.highlightDao().update(
                highlight.copy(
                    note = note?.takeIf { it.isNotBlank() },
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun deleteHighlight(highlight: Highlight) {
        viewModelScope.launch {
            val chapter = container.database.chapterDao()
                .getById(highlight.chapterId)
            container.database.highlightDao().delete(highlight)
            container.database.bookDao().getById(bookId)?.let { book ->
                val key = container.syncService.bookKeyFor(book)
                container.syncService.recordDeletion(
                    "highlight",
                    app.xreader.data.sync.HighlightRecord(
                        bookKey = key,
                        chapterIndex = chapter?.index ?: 0,
                        startOffset = highlight.startOffset,
                        endOffset = highlight.endOffset,
                        quotedText = highlight.quotedText,
                        color = highlight.color,
                        note = highlight.note
                    ).naturalKey
                )
            }
        }
    }

    // -- Settings ---------------------------------------------------------------------------

    fun updateConfig(transform: (ReaderConfig) -> ReaderConfig) {
        // v1.0.2 (R18): the settings scope decides where palette /
        // text-size changes land. Everything else is global — the
        // sheet labels the scope "for colours & text size" so the
        // promise matches the behaviour exactly.
        if (_state.value.settingsScope == ReaderSettingsScope.THIS_BOOK) {
            viewModelScope.launch {
                val before = _config.value
                val after = transform(before)
                if (after.paletteName != before.paletteName) {
                    container.bookReaderPrefs.setPalette(bookId, after.paletteName)
                }
                if (after.fontSizeSp != before.fontSizeSp) {
                    container.bookReaderPrefs.setFontSize(bookId, after.fontSizeSp)
                }
                val globalAfter = after.copy(
                    paletteName = globalConfig.paletteName,
                    fontSizeSp = globalConfig.fontSizeSp
                )
                if (globalAfter != globalConfig) {
                    container.readerPrefs.update { globalAfter }
                }
            }
        } else {
            viewModelScope.launch {
                val before = _config.value
                container.readerPrefs.update(transform)
                // Writing globally retires this book's override for
                // any field the change touched, so "All books"
                // really means this book too.
                val after = transform(before)
                if (after.paletteName != before.paletteName) {
                    container.bookReaderPrefs.setPalette(bookId, null)
                }
                if (after.fontSizeSp != before.fontSizeSp) {
                    container.bookReaderPrefs.setFontSize(bookId, null)
                }
            }
        }
    }

    fun setSettingsScope(scope: ReaderSettingsScope) {
        _state.value = _state.value.copy(settingsScope = scope)
    }

    /** R18: clear this book's override — back to the global look. */
    fun clearBookOverride() {
        viewModelScope.launch { container.bookReaderPrefs.clear(bookId) }
    }

    /**
     * v1.0.2 (sweep): save a word from this chapter to "Words I'm
     * learning" (Me tab), together with the sentence it appeared
     * in. Already-saved words are left as they are.
     */
    fun saveWord(raw: String) {
        val word = raw.trim().trim('.', ',', '"', '\'', '’', '‘')
        if (word.length < 2) return
        viewModelScope.launch {
            val dao = container.database.vocabDao()
            if (dao.findByWord(word) != null) return@launch
            val fullText = _state.value.chapterText?.text
                ?: _state.value.streamEntries
                    .firstOrNull { it.text != null }?.text?.text
                ?: ""
            val now = System.currentTimeMillis()
            dao.insert(
                app.xreader.data.db.VocabWord(
                    word = word,
                    sentence = sentenceContaining(fullText, word),
                    bookId = bookId,
                    chapterIndex = _state.value.chapterIndex,
                    state = app.xreader.data.db.VocabState.NEW,
                    dueAt = app.xreader.data.library.VocabReview
                        .initialDue(now),
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    /** The sentence around the first occurrence of [word]. */
    private fun sentenceContaining(text: String, word: String): String {
        if (text.isBlank()) return ""
        val idx = text.indexOf(word, ignoreCase = true)
        if (idx < 0) return ""
        val enders = charArrayOf('.', '!', '?', '\n')
        var start = 0
        for (ch in enders) {
            val candidate = text.lastIndexOf(ch, idx - 1)
            if (candidate >= start) start = candidate + 1
        }
        var end = text.length
        for (ch in enders) {
            val candidate = text.indexOf(ch, idx + word.length)
            if (candidate >= 0 && candidate < end) end = candidate + 1
        }
        return text.substring(start, end).trim().take(400)
    }

    fun onFontImported(fileName: String) {
        updateConfig {
            it.copy(
                fontChoice = app.xreader.domain.reader.ReaderFontChoice.USER,
                userFontName = fileName
            )
        }
    }

    // -- Eye tracking ---------------------------------------------------------------------------

    fun onEyePermissionResult(granted: Boolean) {
        eyeTracker.onPermissionResult(granted)
    }

    fun requestExternalTurn(direction: Int) {
        // v1.0.2 (R18): while read-aloud is playing, the volume
        // keys skip sentences instead of turning pages — the ears
        // own the keys while they're listening.
        if (readAloudLazy.isInitialized() && ttsState.value.playing) {
            if (direction > 0) readAloud.skipNext() else readAloud.skipPrevious()
            return
        }
        _externalTurns.tryEmit(direction)
    }

    /** v1.0.6 (W3.4): the browser-view note for [book]'s source,
     * or null when the source reads normally — or when this
     * session has already shown the note for that source. */
    private suspend fun browserViewNoticeFor(
        book: app.xreader.data.db.Book?
    ): String? {
        val extensionId = book?.extensionId ?: return null
        if (book.sourceType != app.xreader.data.db.SourceType.EXTENSION) {
            return null
        }
        if (extensionId in browserNoticeShownFor) return null
        val marked = container.extensionService.sourcesOf(extensionId)
            .getOrNull()
            ?.any { handle ->
                container.extensionService.declarativeConfigOf(handle)
                    ?.needsRendering == true
            } == true
        if (!marked) return null
        browserNoticeShownFor += extensionId
        return "This source loads its pages with the browser view — " +
            "chapters can take a few seconds."
    }

    /** The screen has shown the one-shot note; clear it. */
    fun consumeBrowserNotice() {
        if (_state.value.browserViewNotice != null) {
            _state.value = _state.value.copy(browserViewNotice = null)
        }
    }

    companion object {
        /** Kept for source compatibility; the settle window now
         * lives in [ProgressSaveCoalescer.SETTLE_MS] (same value). */
        const val SAVE_DEBOUNCE_MS = ProgressSaveCoalescer.SETTLE_MS
        const val BOOKMARK_SNAP_CHARS = 400

        /** v1.0.6.2 (F3): the live-fetch wait — polls × cadence
         * ≈ 3 minutes, generous for slow links; the queue item
         * outlives the wait either way. */

        /** v1.0.6 (W3.4): extension ids whose browser-view note
         * this session has already shown. Process-lifetime on
         * purpose: the note is a session courtesy, not a setting. */
        private val browserNoticeShownFor = mutableSetOf<Long>()

        fun factory(
            container: AppContainer,
            bookId: Long,
            chapterIndex: Int
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReaderViewModel(container, bookId, chapterIndex) as T
            }
    }
}
