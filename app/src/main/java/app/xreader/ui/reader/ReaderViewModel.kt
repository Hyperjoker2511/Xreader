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
    private var pendingAutoplayTts = false
    private val chapterCache = LinkedHashMap<Long, ChapterText>()

    /** Chapters the reader asked to see in the original even though
     * a RAW translation exists (in-memory only — the translation
     * stays the default view; nothing is ever deleted). */
    private val originalPreferred = mutableSetOf<Long>()

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
            val chapters = container.database.chapterDao().forBook(bookId)
            val saved = container.database.readingProgressDao().getForBook(bookId)
            // v1.0.6 (W3.4): a browser-view source gets ONE plain
            // note per source per session, so slower chapter turns
            // never read as the app hanging.
            val sourceNotice = browserViewNoticeFor(book)
            _state.value = _state.value.copy(
                bookTitle = book?.title ?: "",
                chapters = chapters,
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

    private suspend fun loadChapter(
        index: Int,
        restoreOffset: Int,
        restoreScrollFraction: Float,
        hint: PageHint = PageHint.OFFSET
    ) {
        flushSession()
        val chapters = _state.value.chapters
        var chapter = chapters.getOrNull(index)
        if (chapter == null) {
            _state.value = _state.value.copy(
                loading = false,
                errorMessage = "This chapter isn't in the book anymore."
            )
            return
        }
        readAloudStopIfLoaded()
        if (overlayPlayerLazy.isInitialized()) overlayPlayer.release()
        overlaySync = null
        _state.value = _state.value.copy(
            chapterIndex = index,
            chapterText = null,
            loading = true,
            errorMessage = null,
            chapterFetchFailed = false,
            positionOffset = restoreOffset,
            currentPositionBookmarked = false
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
                    chapters = _state.value.chapters.map {
                        if (it.id == fresh.id) fresh else it
                    }
                )
                fresh
            }
            is app.xreader.data.download.ChapterTextLoader.Outcome.Failed -> {
                if (outcome.detail.contains("paid or locked", ignoreCase = true)) {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = "This chapter is paid or locked " +
                            "on the site, so it can't be read here.",
                        chapterFetchFailed = true
                    )
                } else {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = "This chapter couldn't be read just " +
                            "now. Check your connection and try again.",
                        chapterFetchFailed = true
                    )
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
                        !isMinimalTier()
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
                        chapters = _state.value.chapters.map {
                            if (it.id == fresh.id) fresh else it
                        },
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
            chapters = _state.value.chapters.map {
                if (it.id == chapter.id) it.copy(read = true) else it
            }
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
        val chapterFraction = PageLocator.chapterFraction(clamped, text.length)
        val bookFraction = PageLocator.bookFraction(
            _state.value.chapterIndex, chapterFraction, _state.value.chapters.size
        )
        val estimator = container.wpmEstimator
        val wordsLeftChapter =
            ChapterContent.countWords(text.text.substring(clamped.coerceAtMost(text.length)))
        // v1.0.7 draw-check fix: the book remainder used to
        // sum only the word counts already known, so a source
        // book showed "≈17 min in book" at chapter 87 of
        // 1,692 — the book page's old lie, still alive in the
        // reader. Later chapters now go through the same
        // estimator as the book page: chapters whose length
        // isn't known count at this book's own average.
        val laterMinutes = app.xreader.domain.reader.WpmEstimator
            .estimateRemainingMinutes(
                _state.value.chapters
                    .drop(_state.value.chapterIndex + 1)
                    .map {
                        app.xreader.domain.reader.WpmEstimator
                            .ChapterWords(it.read, it.wordCount)
                    },
                estimator.wpm()
            ).first
        _state.value = _state.value.copy(
            positionOffset = clamped,
            chapterFraction = chapterFraction,
            bookFraction = bookFraction,
            minutesLeftInChapter = estimator.minutesLeft(wordsLeftChapter),
            minutesLeftInBook =
                estimator.minutesLeft(wordsLeftChapter) + laterMinutes,
            currentPositionBookmarked = _bookmarks.value.any {
                it.chapterIndex == _state.value.chapterIndex &&
                    kotlin.math.abs(it.charOffset - clamped) <= BOOKMARK_SNAP_CHARS
            }
        )
        scheduleProgressSave()
    }

    private fun scheduleProgressSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveProgressNow()
        }
    }

    private suspend fun saveProgressNow() {
        val stateNow = _state.value
        val chapter = stateNow.chapter ?: return
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
            if (book.lastOpenedAt == null || now - (book.lastOpenedAt ?: 0) > 30_000L) {
                container.database.bookDao()
                    .update(book.copy(lastOpenedAt = now, updatedAt = now))
            }
        }
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
            chapters = _state.value.chapters.map {
                if (it.id == chapter.id) it.copy(read = true) else it
            }
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

    /** Lifecycle hook from the screen: progress + session flush. */
    fun onReaderPaused() {
        viewModelScope.launch {
            saveProgressNow()
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
        val snapshot = if (tracker.isRunning) tracker.end() else null
        if (snapshot != null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                container.sessionRecorder.record(sessionBookId, snapshot)
                runCatching { container.expService.settle() }
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
        const val SAVE_DEBOUNCE_MS = 1_500L
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
