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
import app.xreader.data.prefs.VoiceEngine
import app.xreader.data.prefs.settingsDataStore
import app.xreader.data.reader.EdgeVoiceTable
import app.xreader.data.reader.MlKitEyeTracker
import app.xreader.data.reader.OverlayPlayer
import app.xreader.data.reader.ReadAloudController
import app.xreader.data.reader.SoundscapePlayer
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.BookDocument
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.FocusState
import app.xreader.domain.reader.FocusTimer
import app.xreader.domain.reader.LayoutAheadPolicy
import app.xreader.domain.reader.OverlaySync
import app.xreader.domain.reader.PageLocator
import app.xreader.domain.reader.PageTurnTier
import app.xreader.domain.reader.PageTurnTiers
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.SessionTracker
import app.xreader.domain.reader.SleepMode
import app.xreader.domain.reader.StreamWindowPlanner
import app.xreader.domain.reader.Soundscape
import app.xreader.domain.reader.TermPair
import app.xreader.domain.reader.TermReplacer
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where a freshly loaded chapter should open. */
enum class PageHint { FIRST, LAST, OFFSET }

/** v1.7 Patch 3 (Item 4b): one in-book search hit on the core
 * road — the chapter it lives in and its char offset there,
 * with the snippet the results list shows. */
data class CoreSearchHit(
    val chapterIndex: Int,
    val chapterId: Long,
    val chapterTitle: String,
    val charOffset: Int,
    val snippet: String
)

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
    /** v1.5.10 (B4): the chapter-text wait has crossed the
     * honest bound (SourceBookSync.CHAPTER_PENDING_UI_BOUND_MS)
     * — the loading surface says "Still loading — this chapter
     * is taking long" instead of spinning inertly. Back still
     * works: leaving cancels the wait exactly as before. */
    val stalled: Boolean = false,
    val errorMessage: String? = null,
    /** v1.0.6.2 (F3): the error came from a live chapter fetch
     * that can simply be retried (the error block offers it). */
    val chapterFetchFailed: Boolean = false,
    /** v1.5.5 (§4): the chapter belongs to a local import whose
     * completion job is still extracting it — the screen shows
     * the live "Preparing chapter…" state (not an error), and
     * the load retries itself when the chapter lands. */
    val preparingChapter: Boolean = false,
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
    /** v1.7 one-engine (Item 3): the honest reason standing in
     * the pinned window's boundary FAILED row, when one does
     * (the fetch layer's diagnostic-log line for that
     * chapter). Null whenever no boundary failure stands. */
    val streamFailedReason: String? = null,
    /** "This book / All books" settings scope (R18). */
    val settingsScope: ReaderSettingsScope = ReaderSettingsScope.ALL_BOOKS,
    val hasBookOverride: Boolean = false,
    /** True when the text on screen is the RAW translation, not the
     * book's own text (Phase 5). The original is never overwritten. */
    val showingTranslation: Boolean = false,
    /** v1.0.6 (W3.4): one-shot plain note when this book's source
     * reads through the browser view — shown once per source per
     * session via the reader's transient notice, then consumed. */
    val browserViewNotice: String? = null,
    /** P0 (Reader Terms): this book's OWN term pairs, for the
     * settings sheet's "Terms for this book" editor. The global
     * list is edited from Settings, not here. */
    val bookTerms: List<TermPair> = emptyList(),
    // ── Manga programme (slice M2) ───────────────────────────
    /** The open chapter's pages when it is an IMAGE chapter
     * (a MANGA source's chapter): ReaderScreen renders the
     * image reader instead of the text renderers while this
     * is set. Sourced from the chapter's ORIGINAL content in
     * the same state copy that sets the chapter text, so a
     * chapter turn can never pair one chapter's text with
     * another's pages. Null for every text chapter. */
    val mangaPages: app.xreader.domain.content.ChapterPages? = null,
    /** The chapter [mangaPages] belongs to (same copy). */
    val mangaChapterId: Long? = null,
    /** The resolved image-reading mode for THIS book: its own
     * override when one is set, else the global default
     * (option-scope rule — see [MangaModeResolution]). */
    val imageMode: app.xreader.domain.reader.ImageReaderMode =
        app.xreader.domain.reader.ImageReaderMode.WEBTOON,
    /** v1.5.0 (Part C): the manga reader's FULL resolved
     * settings for this book (global defaults + this series'
     * overrides — see [MangaSettings.resolve]). Republished
     * with [imageMode] whenever either layer changes. */
    val mangaSettings: MangaSettings = MangaSettings.resolve(
        app.xreader.domain.reader.ReaderConfig(),
        app.xreader.data.prefs.BookReaderOverride(),
        app.xreader.domain.reader.ImageReaderMode.WEBTOON
    ),
    /** v1.5.0 (Part C): this series carries at least one
     * manga override (the in-reader sheet's reset row). */
    val mangaHasOverrides: Boolean = false,
    /** True on the low-RAM (MINIMAL) performance tier: manga
     * pages then decode downscaled to screen width. */
    val imageLowRam: Boolean = false,
    // ── v1.7 Patch 2 (item 2b) ───────────────────────────────
    /** Layout-ahead inputs for the paged reader: the NEXT
     * chapter's display text + id when prefetch already holds
     * it, so PagedReader can lay it out ahead through the
     * ChapterLayoutService. Null when the next chapter isn't
     * parsed yet — nothing is laid out ahead then. */
    val layoutAheadNextId: Long? = null,
    val layoutAheadNextText: ChapterText? = null,
    /** The tier gate for layout-ahead (FULL + STANDARD on,
     * MINIMAL off — LayoutAheadPolicy), resolved by the
     * ViewModel, which already reads the tier for prefetch. */
    val layoutAheadEnabled: Boolean = false
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
    val fetching: Boolean = false,
    /** v1.7 one-engine (Item 3): the live fetch for this
     * chapter ENDED without landing a copy — the pinned
     * window's boundary renders the in-flow FAILED row (the
     * reason is the fetch layer's honest one, from the
     * diagnostic log) with a retry door, QuickNovel's
     * FailedSpanned shape. Only the pinned policy's boundary
     * consumes it; Continuous keeps the shipped missing block. */
    val failed: Boolean = false
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

    /** v1.5.5 (§3): the post-save retry gate — this surface
     * counts as RESUMED while its state has subscribers (the
     * shell collects it with collectAsStateWithLifecycle);
     * a save that lands while the reader is in the backstack
     * parks here and retries on the next resume instead of
     * racing the foreground surface. */
    private val sessionRetryGate =
        app.xreader.data.extension.SurfaceRetryGate("reader") {
            _state.subscriptionCount.value > 0
        }

    /** v1.0.2 (R18): the two config layers behind [config]. */
    private var globalConfig: ReaderConfig = ReaderConfig()
    private var bookOverride: app.xreader.data.prefs.BookReaderOverride =
        app.xreader.data.prefs.BookReaderOverride()

    /** Patch 12: the global voice choice (Providers & keys),
     * collected at init; this book's layer is [bookOverride].
     * Read fresh by the engine router at every sentence. */
    @Volatile
    private var globalVoiceSettings: app.xreader.data.prefs.VoiceSettings =
        app.xreader.data.prefs.VoiceSettings()

    /** Patch 12: the effective performance tier, kept current by
     * the appearance collector; starts at the RAM tier (the
     * prefetch depth's source, spec §A.3). */
    @Volatile
    private var performanceTier: app.xreader.data.prefs.PerfTier =
        app.xreader.data.prefs.PerformancePolicy.tierForRam(container.totalRamMb())

    /** Patch 12: what the Listen sheet's Voice section renders —
     * the effective engine (this book's override over the global
     * choice), the chapter's detected language, its curated
     * voices and the effective pick. */
    data class ListenVoiceUiState(
        val engine: VoiceEngine = VoiceEngine.DEVICE,
        val isOverride: Boolean = false,
        val language: String = "en-US",
        val choices: List<EdgeVoiceTable.Voice> = emptyList(),
        val voiceId: String? = null,
        val hasOverride: Boolean = false
    )

    private val _voiceUi = MutableStateFlow(ListenVoiceUiState())
    val voiceUi: StateFlow<ListenVoiceUiState> = _voiceUi

    private var voiceLanguageChapterId: Long? = null
    private var voiceLanguage: String = "en-US"

    private fun effectiveVoiceEngine(): VoiceEngine =
        bookOverride.voiceEngine ?: globalVoiceSettings.engine

    private fun effectiveEdgeVoiceId(): String? = bookOverride.edgeVoiceId

    private fun publishVoiceState() {
        val chapterId = _state.value.chapter?.id
        if (chapterId != voiceLanguageChapterId) {
            voiceLanguageChapterId = chapterId
            voiceLanguage = detectVoiceLanguage(chapterId)
        }
        val override = bookOverride
        _voiceUi.value = ListenVoiceUiState(
            engine = override.voiceEngine ?: globalVoiceSettings.engine,
            isOverride = override.voiceEngine != null,
            language = voiceLanguage,
            choices = EdgeVoiceTable.voicesFor(voiceLanguage),
            voiceId = override.edgeVoiceId?.takeIf { it.startsWith(voiceLanguage) },
            hasOverride = override.voiceEngine != null || override.edgeVoiceId != null
        )
    }

    /** The chapter's language from its ORIGINAL text (the same
     * caches read-aloud speaks from), sampled at the first
     * substantial sentence. */
    private fun detectVoiceLanguage(chapterId: Long?): String {
        val original = chapterId?.let { chapterCache[it] ?: streamTexts[it] }
            ?: _state.value.chapterText
            ?: return "en-US"
        val sample = app.xreader.domain.reader.SentenceSplitter.split(original.text)
            .firstOrNull { it.end - it.start >= 12 }
            ?.textIn(original.text)
            ?: original.text.take(200)
        return EdgeVoiceTable.languageFor(sample, java.util.Locale.getDefault())
    }

    /** Patch 12: the Listen sheet's voice writes — THIS book
     * only (option-scope rule); the global choice in Providers &
     * keys is never touched from inside a reader. The override
     * collector republishes the sheet state. */
    fun setVoiceEngine(engine: VoiceEngine) {
        viewModelScope.launch {
            container.bookReaderPrefs.setVoice(
                bookId, engine, bookOverride.edgeVoiceId
            )
        }
    }

    fun setEdgeVoice(voiceId: String?) {
        viewModelScope.launch {
            container.bookReaderPrefs.setVoice(
                bookId, bookOverride.voiceEngine, voiceId
            )
        }
    }

    /** "Use the settings for all books": both of this book's
     * voice overrides go. */
    fun clearVoiceOverrides() {
        viewModelScope.launch {
            container.bookReaderPrefs.setVoice(bookId, null, null)
        }
    }

    private fun publishConfig() {
        _config.value = globalConfig.copy(
            paletteName = bookOverride.paletteName ?: globalConfig.paletteName,
            fontSizeSp = bookOverride.fontSizeSp ?: globalConfig.fontSizeSp
        )
    }

    /** Manga programme (slice M2): the image-reading mode in
     * force for this book — its own override over the global
     * default ([MangaModeResolution]). Republished whenever
     * either layer changes. */
    private fun publishImageMode() {
        val resolved = MangaModeResolution.resolve(
            bookOverride.imageMode, globalConfig.imageMode
        )
        _state.value = _state.value.copy(
            imageMode = resolved,
            // v1.5.0 (Part C): the whole manga surface
            // resolves with the mode — one publication, so
            // the reader never sees a half-updated settings
            // mix.
            mangaSettings = MangaSettings.resolve(
                globalConfig, bookOverride, resolved
            ),
            mangaHasOverrides = bookOverride.hasMangaOverrides
        )
    }

    /** Manga programme (slice M2): the image reader's mode
     * switch — option-scope tier 2: the change is written as
     * THIS series' override only; the global default in
     * ReaderConfig is never touched from inside a reader.
     * The override collector republishes the resolved mode;
     * the state is also set directly so the switch answers
     * the tap immediately. */
    fun setImageMode(mode: app.xreader.domain.reader.ImageReaderMode) {
        _state.value = _state.value.copy(imageMode = mode)
        viewModelScope.launch {
            container.bookReaderPrefs.setImageMode(bookId, mode)
        }
    }

    /** v1.5.0 (Part C): the in-reader manga sheet's other
     * per-series writes — same option-scope tier as
     * [setImageMode]: THIS series only, never the global
     * default. The override collector republishes the
     * resolved settings. */
    fun setMangaTapLayout(
        layout: app.xreader.domain.reader.MangaTapLayout?
    ) {
        viewModelScope.launch {
            container.bookReaderPrefs.setMangaTapLayout(bookId, layout)
        }
    }

    fun setMangaOrientation(
        orientation: app.xreader.domain.reader.MangaOrientation?
    ) {
        viewModelScope.launch {
            container.bookReaderPrefs.setMangaOrientation(bookId, orientation)
        }
    }

    fun setMangaCropBorders(crop: Boolean?) {
        viewModelScope.launch {
            container.bookReaderPrefs.setMangaCropBorders(bookId, crop)
        }
    }

    /** The sheet's reset: this series returns to the global
     * defaults for every manga field (the donor's
     * ResetViewerFlags shape). */
    fun resetMangaOverrides() {
        viewModelScope.launch {
            container.bookReaderPrefs.clearManga(bookId)
        }
    }

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _highlights = MutableStateFlow<List<Highlight>>(emptyList())
    val highlights: StateFlow<List<Highlight>> = _highlights

    // ── v1.7 Patch 3: prepared-EPUB state ────────────────────
    /** The local reader's prefs — the prepare record's store
     * (Item 1e) and the per-book EPUB settings (Item 4f's
     * plate filter). Read-only here: prepare writes, this
     * reader consumes. */
    private val localReaderPrefs =
        app.xreader.ui.localreader.LocalReaderPrefs(
            container.applicationContext.settingsDataStore
        )

    /** True when this book is a PREPARED local EPUB (Item 2a):
     * its bookmark/highlight lists then include only
     * core-shaped rows — the unconverted locator rows stay
     * stored but are not this road's marks (Item 3e). */
    private val _preparedEpub = MutableStateFlow(false)
    val preparedEpub: StateFlow<Boolean> = _preparedEpub

    /** Item 4f: the book's stored EPUB plate filter, applied
     * by the core plate viewer. ORIGINAL for every book that
     * never carried one. */
    private val _plateFilter =
        MutableStateFlow(app.xreader.ui.localreader.EpubPlateFilter.ORIGINAL)
    val plateFilter: StateFlow<app.xreader.ui.localreader.EpubPlateFilter> =
        _plateFilter

    /** Item 3e's first-open note: set once when a prepared
     * book with a pending note opens here; the screen shows
     * it through the reader's transient notice and consumes
     * it. The pending flag is cleared in the record as the
     * note is taken, so it is shown exactly once. */
    private val _migrationNote = MutableStateFlow<String?>(null)
    val migrationNote: StateFlow<String?> = _migrationNote

    fun consumeMigrationNote() {
        _migrationNote.value = null
    }

    // ── v1.7 Patch 3 (Item 4b): in-book search ───────────────
    private val _searchHits = MutableStateFlow<List<CoreSearchHit>?>(null)
    val searchHits: StateFlow<List<CoreSearchHit>?> = _searchHits
    private val _searchBusy = MutableStateFlow(false)
    val searchBusy: StateFlow<Boolean> = _searchBusy
    private var searchJob: Job? = null

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
            // Patch 12: the router over the device + Edge engines;
            // the choice and the per-book voice pick are read
            // fresh at every sentence, so Listen-sheet switches
            // land at sentence boundaries.
            engine = container.readAloudEngine(
                scope = viewModelScope,
                engineChoice = { effectiveVoiceEngine() },
                voiceIdOverride = { effectiveEdgeVoiceId() }
            ),
            scope = viewModelScope,
            prefetchDepth = app.xreader.data.reader.TtsPrefetchDepth.forTier(
                performanceTier
            ),
            // 2 GB perf pass (F5): no chapter-ahead fill on MINIMAL.
            chapterAhead = app.xreader.data.reader.TtsChapterAhead.forTier(
                performanceTier
            )
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

    /**
     * P0 (Reader Terms): the controller speaks the ORIGINAL text
     * (terms are a display-only feature), so its sentence range
     * is in original coordinates while the page shows display
     * coordinates. The published state is remapped through the
     * current chapter's term offset map, so the karaoke
     * highlight still lands on the sentence being spoken. With
     * no terms in force the state passes through untouched.
     */
    val ttsState: StateFlow<ReadAloudController.State> by lazy {
        readAloud.state
            .map { spoken -> remapSpokenRange(spoken) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                readAloud.state.value
            )
    }

    private fun remapSpokenRange(
        spoken: ReadAloudController.State
    ): ReadAloudController.State {
        if (spoken.sentenceStart < 0) return spoken
        val map = ttsChapterId?.let { termsMaps[it] } ?: return spoken
        if (map.isEmpty) return spoken
        return spoken.copy(
            sentenceStart = map.originalToDisplay(spoken.sentenceStart),
            sentenceEnd = map.originalToDisplay(spoken.sentenceEnd)
        )
    }

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
    /** v1.5.10 (B4): generation counter for the chapter stall
     * watcher — a newer load supersedes an older watcher. */
    @Volatile
    private var chapterStallSeq: Int = 0

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
        // v1.7 Patch 2 (item 1a): the char map rebuilds with the
        // list — exact lengths for still-present chapter ids
        // carry over, new chapters enter at the estimate.
        bookDocument = bookDocument.rebuild(chapters)
        return chapters
    }

    /** v1.7 Patch 2 (item 1a): the book-wide char map
     * ([BookDocument]) — rebuilt ONLY through [chaptersUpdated]
     * (exact lengths carry over by chapter id) and fed the
     * DISPLAY length wherever a display [ChapterText] is built:
     * chapter load, prefetch, stream. In-memory only; book
     * offsets are derived, never persisted. */
    private var bookDocument: BookDocument = BookDocument.EMPTY

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

    // ── P0 (Reader Terms): display-time replacements ────────────
    // The caches above always hold the ORIGINAL text (storage
    // bytes, untouched). What lands in the UI state is a display
    // copy built by [displayText] when terms are in force.
    /** Global + this book's pairs, book first (it wins ties). */
    @Volatile
    private var activeTerms: List<TermPair> = emptyList()

    /** Joined-text offset maps of transformed chapters, by
     * chapter id — the bridge between the read-aloud
     * controller's original coordinates and the page's. */
    private val termsMaps = mutableMapOf<Long, TermReplacer.OffsetMap>()

    /** The chapter the read-aloud controller currently holds. */
    private var ttsChapterId: Long? = null

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
        // v1.7 Patch 1 (item 6b): memory pressure flushes the
        // coalesced progress write at once — the trim can
        // arrive while this reader is still foreground, where
        // no pause/stop flush would fire. The collection dies
        // with the scope, so a cleared VM never flushes twice.
        viewModelScope.launch {
            container.memoryPressureEvents.collect {
                flushProgressNow()
            }
        }
        viewModelScope.launch {
            container.appearancePrefs.state.collectLatest { appearance ->
                reduceMotion = appearance.reduceMotion
                // Patch 12: keep the effective tier current for
                // the read-aloud prefetch depth (§A.3).
                performanceTier = app.xreader.data.prefs.PerformancePolicy.resolve(
                    appearance.performanceMode, container.totalRamMb(),
                    appearance.coverGlow
                ).tier
                _state.value = _state.value.copy(
                    pageTurnTier = computeTier(_config.value),
                    // v1.7 Patch 2 (item 2b): the layout-ahead
                    // gate follows the same tier (FULL +
                    // STANDARD on, MINIMAL off).
                    layoutAheadEnabled = LayoutAheadPolicy.enabled(
                        minimalTier = performanceTier ==
                            app.xreader.data.prefs.PerfTier.MINIMAL
                    )
                )
            }
        }
        viewModelScope.launch {
            container.readerPrefs.config.collectLatest { newConfig ->
                globalConfig = newConfig
                publishConfig()
                publishImageMode()
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
                publishImageMode()
                publishVoiceState()
            }
        }
        // Patch 12: the global voice choice (Providers & keys)
        // layers under this book's override for the Listen sheet
        // and the engine providers.
        viewModelScope.launch {
            container.voicePrefs.settings.collectLatest { settings ->
                globalVoiceSettings = settings
                publishVoiceState()
            }
        }
        // Patch 12: the Listen sheet's language follows the
        // chapter — republished only when the chapter changes
        // (publishVoiceState recomputes the language then).
        viewModelScope.launch {
            _state.map { it.chapter?.id }.distinctUntilChanged()
                .collectLatest { publishVoiceState() }
        }
        // Manga programme (slice M2): the low-RAM decode flag
        // resolves once from the same performance policy the
        // stream window uses (the 2 GB tier decodes manga
        // pages at screen width — see MangaDecode).
        viewModelScope.launch {
            _state.value = _state.value.copy(imageLowRam = isMinimalTier())
        }
        // P0 (Reader Terms): this book's own pairs feed the
        // settings sheet's editor; the merged list (book first,
        // then global) is what's in force for the display text.
        // Any change re-derives the visible chapter's display
        // copy from its cached original — the stored text and
        // the caches are never touched.
        viewModelScope.launch {
            container.termsPrefs.bookPairs(bookId).collectLatest { pairs ->
                _state.value = _state.value.copy(bookTerms = pairs)
            }
        }
        viewModelScope.launch {
            container.termsPrefs.pairsForBook(bookId).collectLatest { pairs ->
                activeTerms = pairs
                termsMaps.clear()
                reapplyTerms()
            }
        }
        viewModelScope.launch {
            // v1.7 Patch 3 (Item 3e): for a PREPARED EPUB only
            // core-shaped rows are this road's marks — a
            // locator row here is an unconverted leftover (or
            // a not-yet-converted mark), and showing it would
            // double-list every converted mark's chapter.
            // Every other book keeps the unfiltered list.
            combine(
                container.database.bookmarkDao().observeForBook(bookId),
                _preparedEpub
            ) { rows, prepared ->
                if (prepared) rows.filter { it.locatorJson == null } else rows
            }.collectLatest { _bookmarks.value = it }
        }
        viewModelScope.launch {
            combine(
                container.database.highlightDao().observeForBook(bookId),
                _preparedEpub
            ) { rows, prepared ->
                if (prepared) rows.filter { it.locatorJson == null } else rows
            }.collectLatest { _highlights.value = it }
        }
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            // v1.7 Patch 3: a prepared EPUB's per-book state —
            // the mark filter above, the plate filter (4f) and
            // the one-time migration note (3e) — resolves from
            // the prepare record in this book-load pass; the
            // collectors re-filter the moment the flag lands.
            if (book != null &&
                book.sourceType ==
                    app.xreader.data.db.SourceType.LOCAL_FILE &&
                book.localFormat == app.xreader.data.db.BookFormat.EPUB
            ) {
                val record = runCatching {
                    localReaderPrefs.epubPrepareRecord(bookId)
                }.getOrNull()
                if (app.xreader.data.prepare.EpubPrepareAccess
                        .isPrepared(record, book)
                ) {
                    _preparedEpub.value = true
                    _plateFilter.value = runCatching {
                        localReaderPrefs.epubSettings(bookId)
                            .first().plateFilter
                    }.getOrDefault(
                        app.xreader.ui.localreader.EpubPlateFilter.ORIGINAL
                    )
                    if (record!!.notePending) {
                        _migrationNote.value =
                            "Your place and marks were carried over " +
                                "from the old EPUB reader as closely " +
                                "as the old format allows — chapter " +
                                "counts may differ slightly."
                        runCatching {
                            localReaderPrefs.saveEpubPrepareRecord(
                                bookId,
                                record.copy(notePending = false)
                            )
                        }
                    }
                }
            }
            // v1.5.10 (B5): book-aware resolution (stable
            // identity + heal on a row-id miss).
            bookConfig = book
                ?.takeIf {
                    it.sourceType == app.xreader.data.db.SourceType.EXTENSION &&
                        it.extensionId != null
                }
                ?.let { storedBook ->
                    container.extensionService.resolveBookSources(storedBook)
                        .getOrNull()
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
            // Cycle 1 (F3): progress-linking — a RESUME launch
            // (the caller asked for the saved position, or
            // there is none yet) first lets a further-along
            // linked copy move this book's resume point, then
            // reads the row back. A deliberate chapter tap (an
            // index that is NOT the saved position) skips the
            // reconcile entirely: the reader's pick wins.
            val preSaved = container.database.readingProgressDao().getForBook(bookId)
            var resumeIndex = initialChapterIndex
            if (preSaved == null || preSaved.chapterIndex == initialChapterIndex) {
                val outcome = runCatching {
                    container.progressLinkService.reconcile(bookId)
                }.getOrNull()
                if (outcome is
                    app.xreader.data.library.ProgressLinkService.Outcome.Applied
                ) {
                    resumeIndex = outcome.plan.targetChapter.index
                }
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
            val startIndex = resumeIndex.coerceIn(0, maxOf(0, chapters.lastIndex))
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
        // v1.5.5 (§3): the broadcast replaced the claim — a
        // parked (backstack) retry fires the moment this
        // surface is resumed again.
        viewModelScope.launch {
            _state.subscriptionCount.collect { count ->
                if (count > 0) {
                    sessionRetryGate.onResumed()?.let { key ->
                        retryAfterSessionSave(key)
                    }
                }
            }
        }
        viewModelScope.launch {
            container.sessionUpdateBus.events.collect { event ->
                if (sessionRetryGate.onSessionUpdated(event.sessionKey)) {
                    retryAfterSessionSave(event.sessionKey)
                }
            }
        }
    }

    /** v1.5.5 (§3): this surface's OWN post-save retry — the
     * v1.3.3 return path (refresh, identity re-resolve,
     * failure retry) with the exactly-once claim REMOVED:
     * every interested surface now retries its own blocked
     * operation for the source; the durable record only notes
     * that the reader's op ran. */
    private suspend fun retryAfterSessionSave(key: String) {
                val bookRow = container.database.bookDao()
                    .getById(bookId) ?: return
                val extId = bookRow.extensionId ?: return
                if (key !=
                    app.xreader.data.extension.ExtensionHttpClient
                        .sessionKeyFor(extId)
                ) {
                    return
                }
                app.xreader.data.extension.DiagnosticLog.record(
                    "fenrir-unlock",
                    "[FenrirUnlock] surface=reader retried=true",
                    key
                )
                val readerHasOp = container.database.pendingOperationDao()
                    .getByKey(key) != null
                if (readerHasOp) {
                    viewModelScope.launch {
                        container.finishPendingOperation(key, true)
                    }
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

    /**
     * v1.7 Patch 3 (Item 4b): in-book search over the book's
     * stored chapter text — the core road's first search,
     * built for the converted EPUBs that lost the local
     * reader's search with the move. One hit per chapter (its
     * first match, the local search's per-section shape),
     * sequential over the chapter rows on IO, capped at
     * [SEARCH_HIT_CAP]. A tap on a hit is an [openChapterAt]
     * to its (chapter, charOffset). Live in-page hit
     * highlighting is Patch 5's find work, not this patch's.
     */
    fun searchBook(query: String) {
        val needle = query.trim()
        searchJob?.cancel()
        if (needle.isEmpty()) {
            _searchHits.value = null
            _searchBusy.value = false
            return
        }
        _searchBusy.value = true
        searchJob = viewModelScope.launch(container.ioDispatcher) {
            val hits = ArrayList<CoreSearchHit>()
            try {
                val chapters = container.database.chapterDao()
                    .forBook(bookId)
                for (chapter in chapters) {
                    if (hits.size >= SEARCH_HIT_CAP) break
                    if (chapter.contentPath == null) continue
                    val result = container.bookStorage
                        .readChapterContent(bookId, chapter.id)
                    val content = (result as? AppResult.Ok)?.value
                        ?: continue
                    val text = ChapterText(content).text
                    val at = text.indexOf(needle, ignoreCase = true)
                    if (at < 0) continue
                    val start = (at - 40).coerceAtLeast(0)
                    val end = (at + needle.length + 60)
                        .coerceAtMost(text.length)
                    hits += CoreSearchHit(
                        chapterIndex = chapter.index,
                        chapterId = chapter.id,
                        chapterTitle = chapter.title,
                        charOffset = at,
                        snippet = (if (start > 0) "…" else "") +
                            text.substring(start, end)
                                .replace('\n', ' ') +
                            (if (end < text.length) "…" else "")
                    )
                }
                _searchHits.value = hits
            } finally {
                _searchBusy.value = false
            }
        }
    }

    /** Item 4b: leaving the search sheet drops its results. */
    fun clearBookSearch() {
        searchJob?.cancel()
        _searchHits.value = null
        _searchBusy.value = false
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

    /**
     * P0 (Reader Terms): the display copy of [original] with the
     * terms in force applied. Returns [original] itself when no
     * term matches — the common case costs one engine pass and
     * no copy. The chapter's offset map is recorded so the
     * read-aloud highlight (original coordinates) can be
     * remapped onto the displayed page.
     */
    private fun displayText(original: ChapterText, chapterId: Long): ChapterText {
        if (activeTerms.isEmpty()) {
            termsMaps.remove(chapterId)
            return original
        }
        val result = TermReplacer.applyToContent(original.content, activeTerms)
        if (result.offsetMap.isEmpty) {
            termsMaps.remove(chapterId)
            return original
        }
        termsMaps[chapterId] = result.offsetMap
        return ChapterText(result.content)
    }

    /** Terms changed while reading: rebuild what's on screen
     * from the cached originals. The reading position is kept
     * as-is — offsets can drift by the length difference of
     * the terms before it, the same drift a translation toggle
     * already causes. */
    private fun reapplyTerms() {
        if (_state.value.streamActive) {
            // v1.7 one-engine (A1): a terms rebuild reopens the
            // window under the LIVE window policy — a pinned
            // book stays pinned.
            openStream(
                _state.value.chapterIndex,
                _state.value.positionOffset,
                pinned = streamPinned
            )
            return
        }
        val chapter = _state.value.chapter ?: return
        val original = chapterCache[chapter.id]
            ?: streamTexts[chapter.id] ?: return
        val display = displayText(original, chapter.id)
        // v1.7 Patch 2 (item 1a): a terms change rebuilds the
        // visible chapter's display text — its map length is
        // rebuilt with it, so the char map stays in display
        // coordinates.
        bookDocument.setExactLength(chapter.id, display.length)
        if (display !== _state.value.chapterText) {
            _state.value = _state.value.copy(chapterText = display)
        }
    }

    /** The settings sheet's "Terms for this book" editor writes
     * through here; the collectors above pick the change up and
     * re-derive the display text. */
    fun setBookTerms(pairs: List<TermPair>) {
        viewModelScope.launch {
            container.termsPrefs.setBookPairs(bookId, pairs)
        }
    }

    /**
     * v1.5.1 (item 4): the re-link ladder, run when a chapter
     * open can't resolve against the stored list — INCLUDING
     * when the stored list is empty. Steps: (1) the book's
     * source state is re-checked by refreshing the book from
     * its source (the refresh re-fetches details + chapters
     * and rebinds to a fuller same-title release when the
     * list comes back empty — the book-moved case); (2) the
     * stored list is re-read; (3) the target resolves by
     * position, then by canonical chapter number, then — for
     * an open that was aimed at the book's start against an
     * empty list — at the identity-first chapter. Returns
     * null only when the refreshed list genuinely lacks the
     * chapter; the caller then shows the honest message that
     * says the list was refreshed.
     */
    private suspend fun runRelinkLadder(
        index: Int,
        stale: app.xreader.data.db.Chapter? = null
    ): app.xreader.data.db.Chapter? {
        val book = container.database.bookDao().getById(bookId)
            ?: return null
        if (book.sourceType !=
            app.xreader.data.db.SourceType.EXTENSION
        ) {
            return null
        }
        runCatching { container.sourceBookSync.refresh(book) }
        val rows = container.database.chapterDao().forBook(bookId)
        if (rows.isEmpty()) return null
        val display = chaptersUpdated(rows)
        _state.value = _state.value.copy(chapters = display)
        return RelinkResolution.pick(
            display,
            index,
            intendedKey = stale?.let {
                app.xreader.data.extension.ChapterIdentity
                    .canonicalKey(it.remoteId ?: "")
            },
            intendedNumber = stale?.let {
                app.xreader.domain.reader.ChapterDisplayOrder
                    .numberOf(it)
            },
            intendedTitle = stale?.title
        )
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
        // v1.5.2 (§15.5): a read START writes the book's History
        // record (per-book, QuickNovel copy) — best effort, never
        // allowed to block the chapter itself.
        runCatching { container.recordHistoryStart(bookId) }
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
                // v1.5.1 (item 4): THE RE-LINK LADDER. The
                // stored re-read above only helps when the
                // rows exist under a stale snapshot — the
                // field case (a learned source whose book sits
                // at ZERO stored chapters while Start Reading
                // stayed live) dead-ended here without ever
                // asking the source again. Before any dead-end
                // message, the ladder runs: refresh the book
                // from its source (SourceBookSync.refresh —
                // which itself rebinds to a fuller release
                // when the list comes back empty, the
                // title-move case), re-read the stored list,
                // and resolve the target against it — by exact
                // stable ID, then canonical number, then
                // title (RelinkResolution). The stale row at
                // the target position, when one exists, is
                // the identity the ladder carries across the
                // refresh.
                val stale = fresh.getOrNull(index)
                chapter = runRelinkLadder(index, stale)
            }
            if (chapter == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = "This chapter isn't in the book " +
                        "anymore — the chapter list was refreshed " +
                        "and it wasn't found."
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
            stalled = false,
            errorMessage = null,
            chapterFetchFailed = false,
            // v1.5.5 (§4): a fresh load supersedes any Preparing
            // state the previous chapter was showing.
            preparingChapter = false,
            // v1.3.4 (U3): a new load clears the old failure's
            // classification and hand-off with its message.
            sessionNeed = null,
            sessionTargetUrl = null,
            fetchKind = null
        )
        // v1.5.10 (B4): arm the stall watcher for this load —
        // past the bound, a still-running text wait surfaces
        // the honest stall state + a DiagnosticLog record.
        // The watcher never touches the fetch itself.
        val stallChapterId = chapter.id
        val stallStartedAt = System.currentTimeMillis()
        val stallSeq = ++chapterStallSeq
        viewModelScope.launch {
            kotlinx.coroutines.delay(
                app.xreader.data.extension.SourceBookSync
                    .CHAPTER_PENDING_UI_BOUND_MS
            )
            if (stallSeq != chapterStallSeq) return@launch
            if (!_state.value.loading ||
                _state.value.chapter?.id != stallChapterId
            ) {
                return@launch
            }
            _state.value = _state.value.copy(stalled = true)
            val stallBook = container.database.bookDao()
                .getById(bookId)
            val stallSourceId = stallBook?.let {
                app.xreader.data.extension.SourceBookIds
                    .decode(it.remoteId)?.first
            } ?: "book $bookId"
            app.xreader.data.extension.ReaderStall.recordIfStalled(
                sourceId = stallSourceId,
                awaited = "chapter $stallChapterId text",
                startedAtMs = stallStartedAt,
                nowMs = System.currentTimeMillis()
            )
        }
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
        // v1.5.2 fix (FIX-5, §7): tier-2 chapter-content evidence at
        // open — an image chapter is MANGA evidence, a prose chapter
        // is TEXT evidence. The resolver applies the both-kinds gate
        // + hysteresis; a single-kind source's pin can never move.
        // Also the §12 eviction caller on the reading path: caps are
        // enforced after a body lands, current+next pinned.
        viewModelScope.launch {
            runCatching {
                val bookRow = container.database.bookDao().getById(bookId)
                if (bookRow != null) {
                    container.bookKindResolver.applyTier2Evidence(
                        bookRow,
                        if (content.isImageChapter) {
                            app.xreader.data.extension.BookKind152.MANGA
                        } else {
                            app.xreader.data.extension.BookKind152.TEXT
                        }
                    )
                }
            }
            runCatching {
                container.cacheEviction152.enforce(
                    openBookId = bookId, openChapterId = chapter.id
                )
            }
        }
        tracker.begin()
        currentChapterMaxOffset = restoreOffset
        visibleTextChapterIndex = index
        val displayCopy = displayText(text, chapter.id)
        // v1.7 Patch 2 (item 1a): feed the char map the DISPLAY
        // length — every offset the map serves is reported
        // against the display text the renderers consume.
        bookDocument.setExactLength(chapter.id, displayCopy.length)
        _state.value = _state.value.copy(
            loading = false,
            chapterText = displayCopy,
            restoreOffset = restoreOffset,
            restoreScrollFraction = restoreScrollFraction,
            restoreHint = hint,
            restoreGeneration = _state.value.restoreGeneration + 1,
            hasOverlay = content.mediaOverlay != null,
            isImageBook = content.isImageBook,
            // Manga programme (slice M2): an image chapter's
            // pages ride along with its text in the SAME copy
            // (the keep-previous invariant above holds for them
            // too). From the ORIGINAL content, never the
            // display copy — display-time transforms rebuild
            // ChapterContent and do not carry the pages record.
            mangaPages = if (content.isImageChapter) content.pages else null,
            mangaChapterId = if (content.isImageChapter) chapter.id else null,
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
            // Read-aloud deliberately gets the ORIGINAL text:
            // terms change what's seen, not what's spoken. Its
            // sentence range is remapped for the highlight in
            // [ttsState].
            ttsChapterId = chapter.id
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
                    // v1.5.37 (combined build): a source with
                    // no operation-token contract (Xnovel) can
                    // still meet its first-ever check on the
                    // chapter road — with no declared check
                    // page the hand-off would fall back to the
                    // source root, an unchallenged page with
                    // nothing to solve
                    // (XNOVEL-403-ESCALATION-FORENSICS, fix 2).
                    // For a SITE_CHECK need only, target the
                    // chapter's own page — the challenged
                    // route itself. SIGN_IN keeps the null
                    // target it has always had; a declared
                    // token target (Fenrir) still wins.
                    ?: chapter.remoteId?.takeIf {
                        effectiveNeed ==
                            app.xreader.data.extension.SessionNeed
                                .Kind.SITE_CHECK &&
                            it.startsWith("http")
                    }
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
                        // 503-flavoured "isn't answering right
                        // now" (v1.5.40) vs 429-flavoured "too
                        // many requests", with the wait the site
                        // named. Retry only.
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
            app.xreader.data.download.ChapterTextLoader.Outcome.Preparing -> {
                // v1.5.5 (§4): a local import is still extracting
                // this chapter in the background. Live non-error
                // state: show "Preparing chapter…" and re-run the
                // load when the completion publishes that the
                // chapter landed (or the job ended — the re-load
                // then resolves to the chapter or its honest
                // failure, never to Preparing again).
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = null,
                    chapterFetchFailed = false,
                    preparingChapter = true
                )
                val waitingChapterId = chapter.id
                val waitingIndex = _state.value.chapterIndex
                viewModelScope.launch {
                    app.xreader.data.importer.ImportCompletionState
                        .statusFlow(bookId)
                        .first { status ->
                            val row = container.database.chapterDao()
                                .getById(waitingChapterId)
                            row?.contentPath != null ||
                                status == null || !status.running
                        }
                    if (_state.value.chapterIndex == waitingIndex &&
                        _state.value.preparingChapter
                    ) {
                        launchLoad { loadChapter(waitingIndex, 0, 0f) }
                    }
                }
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

    /** v1.5.0 (A4): the failure shapes that mean "this
     * chapter's saved reference no longer resolves" — the
     * extraction shapes plus the resolution shapes (a moved
     * chapter URL answering 404, a reference the source no
     * longer lists). Any of them earns the ONE fresh-list
     * re-resolution in [reresolveChapter]; a dangling
     * reference never dead-ends while the book exists (the
     * link-Asura "Overgeared" / link-Comix exhibits). */
    private fun isExtractionFailure(detail: String): Boolean =
        detail.contains("wasn't found on the page", ignoreCase = true) ||
            detail.contains("no readable text", ignoreCase = true) ||
            detail.contains("the site answered HTTP 404", ignoreCase = true) ||
            detail.contains("isn't in the book anymore", ignoreCase = true) ||
            detail.contains("refresh chapter list", ignoreCase = true)

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
        // v1.5.39 (item 1): containment — a storage throw
        // out of the refresh reads as a failed refresh
        // (null), never as a crash on this dispatcher
        // (V1538-CRASH-FORENSICS §4).
        val refreshed = try {
            container.sourceBookSync.refresh(book)
        } catch (
            e: android.database.sqlite.SQLiteConstraintException
        ) {
            null
        } catch (e: java.sql.SQLException) {
            null
        }
        if (refreshed !is AppResult.Ok) {
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
                            // v1.5.2 fix (FIX-6d, §12.4): neighbour
                            // warm is an AUTOMATIC producer — stamped
                            // at enqueue so its bodies are cache,
                            // never phantom downloads.
                            container.downloadController
                                .enqueueChapter(
                                    bookId, chapter.id,
                                    origin = app.xreader.data.download
                                        .BodyOrigin152.AUTOMATIC
                                )
                        }
                    }
                    continue
                }
                if (chapterCache.containsKey(chapter.id)) continue
                val result = container.bookStorage.readChapterContent(bookId, chapter.id)
                if (result is AppResult.Ok) {
                    val parsed = ChapterText(result.value)
                    cacheChapter(chapter.id, parsed)
                    // v1.7 Patch 2 (item 1a): the cache holds
                    // ORIGINALS — they price the char map only
                    // when no terms copy exists (no terms in
                    // force ⇒ original == display). With terms
                    // in force the chapter is fed at load, when
                    // its display copy is built.
                    if (activeTerms.isEmpty()) {
                        bookDocument.setExactLength(
                            chapter.id, parsed.length
                        )
                    }
                }
            }
            // v1.7 Patch 2 (item 2b): publish the layout-ahead
            // candidate — the NEXT chapter's DISPLAY text when
            // prefetch holds its original (the display copy is
            // what the paged reader will paginate when the
            // chapter opens from this same cache) — plus the
            // tier gate, for PagedReader's ahead layout.
            val nextChapter = _state.value.chapters.getOrNull(index + 1)
            val nextOriginal = nextChapter?.let { chapterCache[it.id] }
            _state.value = _state.value.copy(
                layoutAheadNextId = nextChapter?.id,
                layoutAheadNextText = if (
                    nextChapter != null && nextOriginal != null
                ) {
                    displayText(nextOriginal, nextChapter.id)
                } else {
                    null
                },
                layoutAheadEnabled = LayoutAheadPolicy.enabled(
                    minimalTier = performanceTier ==
                        app.xreader.data.prefs.PerfTier.MINIMAL
                )
            )
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
            // Manga programme (slice M2): pages chapters warm
            // through the same near-end path as CBZ image
            // chapters (their blocks-based check is false —
            // a manga chapter's content is its page list).
            isImageChapter = ReaderCounters.isImageChapter(text.content) ||
                text.content.isImageChapter,
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

    // ── v1.7 one-engine: the window policy ───────────────────
    // The unified scroll core runs the stream machinery under
    // BOTH transitions. CONTINUOUS keeps the shipped sliding
    // window; CHAPTER_BY_CHAPTER pins the window to the current
    // chapter (the planner's `behind` reserve still applies —
    // a scroll back never waits) and never slides it. The one
    // extension of the pinned window is Item 3's boundary
    // crossing: as the current chapter's end is reached, the
    // next chapter is requested and joins the window when it
    // lands; the window then re-pins around it.
    @Volatile
    private var streamPinned: Boolean = false

    /** The pinned window's one outstanding boundary request:
     * the chapter being loaded in, or -1. Set when the current
     * chapter's last block becomes visible; cleared when the
     * chapter lands, fails, or the window re-plans. While set,
     * the boundary renders the LOADING item and is not
     * re-requested. */
    private var boundaryNextIndex: Int = -1

    /** The boundary chapter whose load FAILED (the FAILED row
     * stands at the boundary), or -1. Cleared by a retry, a
     * landing, or a window re-plan. */
    private var boundaryFailedIndex: Int = -1

    private suspend fun loadStreamText(chapter: Chapter): ChapterText? {
        // The stream cache holds ORIGINALS (like chapterCache);
        // each caller gets the display copy for the terms in
        // force right now.
        // v1.7 Patch 2 (item 1a): the display copy this returns
        // is a char-map feed, on both roads — the stream renders
        // display text, so the map prices display lengths.
        streamTexts[chapter.id]?.let { cached ->
            val display = displayText(cached, chapter.id)
            bookDocument.setExactLength(chapter.id, display.length)
            return display
        }
        if (chapter.contentPath == null) return null
        val useTranslation = chapter.id !in originalPreferred &&
            container.bookStorage.hasTranslatedContent(bookId, chapter.id)
        val result = if (useTranslation) {
            container.bookStorage.readTranslatedContent(bookId, chapter.id)
        } else {
            container.bookStorage.readChapterContent(bookId, chapter.id)
        }
        val original = (result as? AppResult.Ok)?.value
            ?.let { ChapterText(it) } ?: return null
        streamTexts[chapter.id] = original
        val display = displayText(original, chapter.id)
        bookDocument.setExactLength(chapter.id, display.length)
        return display
    }

    /** v1.7 Patch 2 (item 1d): the stream planner's per-chapter
     * char counts — the char map's EXACT length where the map
     * has the chapter parsed, the word-count estimate otherwise.
     * The planner's budget values are unchanged. */
    private fun streamCharCounts(chapters: List<Chapter>): List<Int> =
        chapters.mapIndexed { index, chapter ->
            if (bookDocument.isExact(index)) {
                bookDocument.lengthOf(index)
            } else {
                (chapter.wordCount * 6).coerceAtLeast(1)
            }
        }

    /** Open (or re-anchor) the stream at [anchorIndex].
     * v1.7 one-engine: [pinned] selects the window policy —
     * true under CHAPTER_BY_CHAPTER (the window is the current
     * chapter plus the planner's behind reserve, and it never
     * slides), false under CONTINUOUS (the shipped sliding
     * window). A (re)open under either policy drops any
     * outstanding boundary request: the window is being
     * re-planned around the anchor. */
    fun openStream(
        anchorIndex: Int,
        anchorOffset: Int = 0,
        pinned: Boolean = false
    ) {
        // Manga programme (slice M2): the endless stream is a
        // TEXT window — a manga book never enters it (its
        // chapters are page lists; the image reader owns them).
        // The book-config check covers the book; the pages
        // check covers the brief window before the config
        // resolves, once a chapter has shown what it is.
        if (bookConfig?.mediaKind?.isManga == true) return
        if (_state.value.mangaPages != null) return
        streamPinned = pinned
        boundaryNextIndex = -1
        boundaryFailedIndex = -1
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            val chapters = _state.value.chapters
            if (chapters.isEmpty()) return@launch
            val appearance = container.appearancePrefs.state.first()
            val policy = app.xreader.data.prefs.PerformancePolicy.resolve(
                appearance.performanceMode, container.totalRamMb(),
                appearance.coverGlow
            )
            val counts = streamCharCounts(chapters)
            val window = if (pinned) {
                pinnedWindow(anchorIndex, counts)
            } else {
                StreamWindowPlanner.plan(
                    anchorIndex = anchorIndex,
                    chapterCharCounts = counts,
                    charBudget = StreamWindowPlanner.charBudgetFor(
                        policy.tier == app.xreader.data.prefs.PerfTier.MINIMAL
                    )
                )
            }
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

    /** v1.7 one-engine: the pinned policy's window — the
     * anchor chapter plus up to the planner's behind reserve
     * (2), nothing ahead: the window never speculatively
     * extends, and Item 3's boundary is its only way forward. */
    private fun pinnedWindow(
        anchorIndex: Int,
        counts: List<Int>
    ): StreamWindowPlanner.Window {
        if (counts.isEmpty()) return StreamWindowPlanner.Window(0, -1)
        val anchor = anchorIndex.coerceIn(0, counts.lastIndex)
        return StreamWindowPlanner.Window(
            fromIndex = (anchor - 2).coerceAtLeast(0),
            toIndex = anchor
        )
    }

    /**
     * v1.7 one-engine (Item 3): the pinned window's boundary
     * request — the unified core calls this when the CURRENT
     * chapter's last block becomes visible (last-visible
     * tracking in the surface; NO fraction threshold anywhere).
     * The next chapter is requested over the same fetch path
     * the stream already uses for missing chapters
     * (ensureStored for a chapter with no copy; a stored copy
     * simply joins), and [boundaryNextIndex] marks the request
     * so the boundary renders the LOADING item and the request
     * is never doubled. When the chapter lands,
     * [landBoundaryChapter] extends the window by it and
     * re-pins; when the fetch fails, [boundaryFailedIndex]
     * stands the FAILED row at the boundary instead.
     */
    fun onBoundaryEndVisible() {
        if (!streamPinned || !_state.value.streamActive) return
        val stateNow = _state.value
        val next = stateNow.chapterIndex + 1
        if (next > stateNow.chapters.lastIndex) return
        if (boundaryNextIndex == next) return
        if (stateNow.streamEntries.any { it.chapterIndex == next }) return
        boundaryNextIndex = next
        boundaryFailedIndex = -1
        if (stateNow.streamFailedReason != null) {
            _state.value = stateNow.copy(streamFailedReason = null)
        }
        val chapter = stateNow.chapters.getOrNull(next) ?: return
        if (chapter.contentPath != null) {
            viewModelScope.launch { landBoundaryChapter(next) }
        } else if (!startStreamLiveFetch(next)) {
            // No fetch could start (the row vanished under
            // us): fail honestly rather than load forever.
            boundaryNextIndex = -1
            boundaryFailedIndex = next
        }
    }

    /** Item 3: the FAILED row's retry door — the same request
     * again, through the same paths. */
    fun retryBoundaryChapter() {
        if (boundaryFailedIndex < 0) return
        val next = boundaryFailedIndex
        boundaryFailedIndex = -1
        boundaryNextIndex = -1
        // A failed entry may be standing in the window (a
        // stored fetch that errored): drop it so the boundary
        // re-requests cleanly.
        _state.value = _state.value.copy(
            streamEntries = _state.value.streamEntries.filter {
                it.chapterIndex != next
            },
            streamFailedReason = null
        )
        onBoundaryEndVisible()
    }

    /** Item 3: the boundary chapter has landed (its stored
     * copy exists): extend the pinned window by exactly it,
     * re-pin around it, and let the scroll continue into it.
     * The entries are swapped only when the replacement window
     * is built — the outgoing window stays composed through
     * the gap (A1/C9). */
    private suspend fun landBoundaryChapter(next: Int) {
        if (boundaryNextIndex != next) return
        val stateNow = _state.value
        val appearance = container.appearancePrefs.state.first()
        val policy = app.xreader.data.prefs.PerformancePolicy.resolve(
            appearance.performanceMode, container.totalRamMb(),
            appearance.coverGlow
        )
        val counts = streamCharCounts(stateNow.chapters)
        val window = pinnedWindow(next, counts)
        val entries = buildStreamEntries(
            window, next,
            minimalTier = policy.tier ==
                app.xreader.data.prefs.PerfTier.MINIMAL
        )
        if (boundaryNextIndex != next) return
        boundaryNextIndex = -1
        boundaryFailedIndex = -1
        val keepIds = entries.map { it.chapterId }.toSet()
        streamTexts.keys.retainAll(keepIds + listOfNotNull(stateNow.chapter?.id))
        _state.value = _state.value.copy(streamEntries = entries)
    }

    /** Item 3: the boundary chapter's fetch ended WITHOUT a
     * copy — the FAILED row stands at the boundary with the
     * fetch layer's honest reason (its latest diagnostic-log
     * line for this chapter, the reader error block's own
     * source). No dead screen, no silent stop. */
    private fun failBoundaryChapter(next: Int) {
        if (boundaryNextIndex != next) return
        boundaryNextIndex = -1
        boundaryFailedIndex = next
        val chapter = _state.value.chapters.getOrNull(next) ?: return
        val reason = app.xreader.komikku.ReaderErrorDetail.fromLog(
            app.xreader.data.extension.DiagnosticLog.entries(),
            chapter.id
        ) ?: "This chapter couldn't be fetched."
        _state.value = _state.value.copy(
            streamEntries = _state.value.streamEntries.filter {
                it.chapterIndex != next
            } + StreamEntry(
                chapterIndex = next,
                chapterId = chapter.id,
                title = chapter.title,
                text = null,
                missing = true,
                fetching = false,
                failed = true
            ),
            streamFailedReason = reason
        )
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
            // v1.7 one-engine: under the pinned policy the same
            // restraint applies at every tier — a pinned window
            // never pulls a neighbour speculatively; the
            // boundary request (Item 3) is its only fetch door.
            if (streamPinned && entry.chapterIndex != anchorIndex) continue
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
            var landed = false
            try {
                // v1.0.6.2 (F9): the wait is the shared loader's —
                // one fetch path for reader, stream and AI alike.
                val outcome = container.chapterTextLoader
                    .ensureStored(bookId, chapter)
                if (outcome is
                    app.xreader.data.download.ChapterTextLoader.Outcome.Stored
                ) {
                    landed = true
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
                    // v1.7 one-engine (Item 3): this fetch was
                    // the pinned window's boundary request — the
                    // landed chapter joins the window and the
                    // window re-pins around it.
                    if (boundaryNextIndex == index) {
                        landBoundaryChapter(index)
                    }
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
                // Item 3: a boundary fetch that ended WITHOUT a
                // copy stands the FAILED row at the boundary.
                if (!landed && boundaryNextIndex == index) {
                    failBoundaryChapter(index)
                }
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
        // v1.7 one-engine: CONTINUOUS only — the pinned window
        // never slides; its forward motion is Item 3's boundary
        // landing, which re-pins around the landed chapter.
        if (streamPinned) return
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
            val counts = streamCharCounts(chapters)
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
        // v1.7 one-engine: a closed stream carries no boundary
        // request with it — the next open re-plans from scratch.
        boundaryNextIndex = -1
        boundaryFailedIndex = -1
        if (_state.value.streamActive) {
            _state.value = _state.value.copy(
                streamActive = false,
                streamFailedReason = null
            )
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
        // Manga programme (slice M2): a pages chapter is the
        // same shape of problem — its text is empty by
        // construction — so the image reader reports
        // (pageIndex, pageFraction) through this same method
        // and the renderer's fraction wins identically. The
        // offset clamps to the empty text as usual; the
        // FRACTION is the persisted carrier (restore maps it
        // back to the page — see MangaProgress.restorePage).
        val chapterFraction = if (
            ReaderCounters.isImageChapter(text.content) ||
            text.content.isImageChapter
        ) {
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
        // v1.7 Patch 2 (item 1b): the book fraction is CHAR-based
        // through the BookDocument map — bookOffset / totalChars
        // — so a 500-char chapter no longer weighs the same as a
        // 50 000-char one. The current chapter's length is exact
        // while it is being read (fed at load), so the numerator
        // is a true char offset into the book. An image chapter's
        // char offsets sit at ~0 by construction (see the
        // chapterFraction note above), so its in-chapter share
        // rides the renderer's fraction of its char length. The
        // count-based PageLocator fraction stays as the fallback
        // for any state the map cannot price (no chapters, or a
        // book whose priced total is zero).
        val doc = bookDocument
        val bookFraction = if (
            doc.chapterCount > 0 &&
            chapterIndex in 0 until doc.chapterCount &&
            doc.totalChars > 0
        ) {
            val withinChars = if (
                ReaderCounters.isImageChapter(text.content) ||
                text.content.isImageChapter
            ) {
                (chapterFraction * doc.lengthOf(chapterIndex)).toInt()
            } else {
                clamped
            }
            (doc.bookOffset(chapterIndex, withinChars).toFloat() /
                doc.totalChars).coerceIn(0f, 1f)
        } else {
            PageLocator.bookFraction(
                chapterIndex, chapterFraction, stateBefore.chapters.size
            )
        }
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
        // v1.7 Patch 2 (item 1b): the stored fraction never moves
        // backwards for an at-or-ahead position (BookProgressClamp
        // — estimate refinement under the char-based % can tick a
        // recomputed fraction down; the stored row is the guard).
        val storedRow = runCatching {
            container.database.readingProgressDao().getForBook(bookId)
        }.getOrNull()
        val writeFraction = BookProgressClamp.clamp(
            computed = stateNow.bookFraction,
            newChapterIndex = stateNow.chapterIndex,
            newCharOffset = stateNow.positionOffset,
            storedChapterIndex = storedRow?.chapterIndex,
            storedCharOffset = storedRow?.charOffset,
            storedFraction = storedRow?.fraction
        )
        container.database.readingProgressDao().upsert(
            ReadingProgress(
                bookId = bookId,
                chapterId = chapter.id,
                chapterIndex = stateNow.chapterIndex,
                charOffset = stateNow.positionOffset,
                fraction = writeFraction,
                scrollFraction = stateNow.chapterFraction,
                updatedAt = now
            )
        )
        container.database.bookDao().getById(bookId)?.let { book ->
            // v1.5.3 (finding #9, owner decision 2026-11-04):
            // saving progress stamps recency ONLY — reading no
            // longer makes a preview row a Library member. The
            // v1.3.1 (item 45) doctrine that lived here ("reading
            // is one of the acts that shelve a book", §15.1) is
            // SUPERSEDED by the owner's rule: "library is only
            // books that I add" — membership flips only on Add,
            // Favourite, Download or a status-set. The (throttled)
            // lastOpenedAt stamp stays: History/Recents and the
            // Continue hero read it, membership-free.
            if (book.lastOpenedAt == null ||
                now - (book.lastOpenedAt ?: 0) > 30_000L
            ) {
                container.database.bookDao()
                    .update(
                        book.copy(
                            lastOpenedAt = now,
                            updatedAt = now
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
                        // v1.7 Patch 2 (item 1b): the final upsert
                        // routes through the same clamp as
                        // saveProgressNow — the stored fraction
                        // never moves backwards for an at-or-ahead
                        // position, whichever writer lands last.
                        val storedRow = runCatching {
                            container.database.readingProgressDao()
                                .getForBook(bookId)
                        }.getOrNull()
                        val writeFraction = BookProgressClamp.clamp(
                            computed = stateAtClear.bookFraction,
                            newChapterIndex = stateAtClear.chapterIndex,
                            newCharOffset = stateAtClear.positionOffset,
                            storedChapterIndex = storedRow?.chapterIndex,
                            storedCharOffset = storedRow?.charOffset,
                            storedFraction = storedRow?.fraction
                        )
                        container.database.readingProgressDao().upsert(
                            ReadingProgress(
                                bookId = bookId,
                                chapterId = chapterAtClear.id,
                                chapterIndex = stateAtClear.chapterIndex,
                                charOffset = stateAtClear.positionOffset,
                                fraction = writeFraction,
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
        // v1.5.38 (§B mitigation): leaving the reader retires
        // any fetch-bridge attempt its loads set running — a
        // dead attempt must not hold the shared fetcher mutex
        // (and, behind it, other sources' renders) out to its
        // own timeout (V1537-PROOF-FORENSICS §B).
        runCatching {
            container.renderedPageFetcher.cancelInFlightBridge()
        }
        super.onCleared()
    }

    // -- Read-aloud controls ---------------------------------------------------------

    fun listenPlayPause() {
        val display = _state.value.chapterText ?: return
        if (readAloud.state.value.playing) {
            readAloud.pause()
        } else {
            if (readAloud.state.value.sentenceCount == 0) {
                // P0 (Reader Terms): the controller speaks the
                // ORIGINAL text, so it is loaded from the
                // caches — never the display copy in the state —
                // and the on-screen offset is mapped back into
                // original coordinates to start at the sentence
                // the reader is looking at.
                val chapterId = _state.value.chapter?.id
                val original = chapterId
                    ?.let { chapterCache[it] ?: streamTexts[it] }
                    ?: display
                val startOffset = if (original === display) {
                    _state.value.positionOffset
                } else {
                    chapterId?.let { termsMaps[it] }
                        ?.displayToOriginal(_state.value.positionOffset)
                        ?: _state.value.positionOffset
                }
                ttsChapterId = chapterId
                readAloud.loadChapter(original, startOffset)
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

    /** v1.7 one-engine (Item 1, A5): a bookmark for a passage
     * in ANY window chapter — the unified scroll core's
     * paragraph menu carries the paragraph's own chapter
     * identity, and the bookmark is written against THAT
     * chapter at the paragraph's offset. The saved reading
     * position is never touched (the retired single-chapter
     * handler routed through onPosition first — inside a
     * window that would have yanked the place). */
    fun addBookmarkInChapter(chapterIndex: Int, charOffset: Int) {
        val stateNow = _state.value
        val chapter = stateNow.chapters.getOrNull(chapterIndex) ?: return
        viewModelScope.launch {
            container.database.bookmarkDao().insert(
                Bookmark(
                    bookId = bookId,
                    chapterId = chapter.id,
                    chapterIndex = chapterIndex,
                    charOffset = charOffset,
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

    /** v1.7 one-engine (Item 1, A5): a highlight in ANY window
     * chapter — the range resolves against that entry's own
     * text and the row is written against that entry's
     * chapter. The current-chapter [addHighlight] above is
     * unchanged for its existing callers (the note dialog). */
    fun addHighlightInChapter(
        chapterIndex: Int,
        start: Int,
        end: Int,
        note: String? = null
    ) {
        val stateNow = _state.value
        val chapter = stateNow.chapters.getOrNull(chapterIndex) ?: return
        val text = if (chapterIndex == stateNow.chapterIndex) {
            stateNow.chapterText
        } else {
            stateNow.streamEntries
                .firstOrNull { it.chapterIndex == chapterIndex }?.text
        } ?: return
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
        // v1.5.10 (B5): book-aware resolution (stable
        // identity + heal on a row-id miss).
        val marked = container.extensionService.resolveBookSources(book)
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

        /** v1.7 Patch 3 (Item 4b): the in-book search result
         * cap — one hit per chapter, so the cap bounds the
         * chapters listed, not the matches counted. */
        const val SEARCH_HIT_CAP = 200

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

/**
 * v1.5.1 (item 4): how the re-link ladder resolves its
 * target against the REFRESHED chapter list — pure, so the
 * resolution is pinned by tests. The order is the sifted
 * rule (ChatGPT review, adopted): exact stable chapter ID
 * first (the canonical key of the chapter's own remote id —
 * position is NOT stable across a refresh: releases rebind
 * and lists re-sort), then canonical chapter number, then
 * an exact title match, then position, and only then the
 * identity-first chapter (number 1) — the Start Reading
 * target when the stored list was empty and neither
 * identity nor number meant anything yet.
 */
internal object RelinkResolution {

    fun pick(
        display: List<Chapter>,
        index: Int,
        intendedKey: String? = null,
        intendedNumber: Float? = null,
        intendedTitle: String? = null
    ): Chapter? {
        if (intendedKey != null) {
            display.firstOrNull { chapter ->
                app.xreader.data.extension.ChapterIdentity
                    .canonicalKey(chapter.remoteId ?: "") == intendedKey
            }?.let { return it }
        }
        val number = intendedNumber ?: (index + 1).toFloat()
        display.firstOrNull { chapter ->
            app.xreader.domain.reader.ChapterDisplayOrder
                .numberOf(chapter) == number
        }?.let { return it }
        if (intendedTitle != null) {
            display.firstOrNull { chapter ->
                chapter.title.equals(intendedTitle, ignoreCase = true)
            }?.let { return it }
        }
        return display.getOrNull(index)
            ?: display.firstOrNull { chapter ->
                app.xreader.domain.reader.ChapterDisplayOrder
                    .numberOf(chapter) == 1f
            }
    }
}
