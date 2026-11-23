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
import app.xreader.data.engine.BookEngine
import app.xreader.data.engine.LocalFileMissingException
import app.xreader.data.engine.LocalPasswordRequiredException
import app.xreader.data.engine.LocatorJson
import app.xreader.data.engine.PdfPageFilter
import app.xreader.data.engine.PdfSession
import app.xreader.data.engine.PdfStripState
import app.xreader.data.prefs.VoiceEngine
import androidx.datastore.preferences.core.edit
import app.xreader.data.prefs.settingsDataStore
import app.xreader.data.reader.EdgeVoiceTable
import app.xreader.data.reader.MlKitEyeTracker
import app.xreader.data.reader.OverlayPlayer
import app.xreader.data.reader.ReadAloudController
import app.xreader.data.reader.SoundscapePlayer
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.BookDocument
import app.xreader.domain.reader.ChapterEndWall
import app.xreader.domain.reader.ChapterText
import app.xreader.domain.reader.FocusState
import app.xreader.domain.reader.FocusTimer
import app.xreader.domain.reader.LayoutAheadPolicy
import app.xreader.domain.reader.OverlaySync
import app.xreader.domain.reader.PageLocator
import app.xreader.domain.reader.PageTurnTier
import app.xreader.domain.reader.PageTurnTiers
import app.xreader.domain.reader.ChapterTransition
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderMode
import app.xreader.domain.reader.SessionTracker
import app.xreader.domain.reader.SleepMode
import app.xreader.domain.reader.StreamWindowPlanner
import app.xreader.domain.reader.Soundscape
import app.xreader.domain.reader.TermPair
import app.xreader.domain.reader.TermReplacer
import app.xreader.ui.localreader.PdfDocSettings
import app.xreader.ui.localreader.PdfStripSpeech
import app.xreader.ui.localreader.SelectionHighlightStore
import app.xreader.ui.localreader.SpeechSink
import app.xreader.ui.localreader.StoredSelectionHighlight
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

/** v1.7 OE-1: a PDF's reading mode inside the one reader —
 * the printed pages, or one page's Reflow text (R8). The
 * mode is per-session only: a PDF opens in PAGES every
 * time (R6) and nothing stores the choice. */
enum class PdfReadMode { PAGES, REFLOW }

/** v1.7 OE-1 (R18): one in-book search hit in a PDF — the
 * page it lives on and its char offset within the
 * representation that was searched ([reflow] says which:
 * the page's Reflow text in Reflow mode, the page's strip
 * text in Pages mode). */
data class PdfSearchHit(
    val page: Int,
    val charOffset: Int,
    val snippet: String,
    val query: String,
    val reflow: Boolean
)

/** v1.7 OE-1 (R18): a search hit's mark on the current
 * page — page + [start, end) char range in the searched
 * representation. The Reflow surface renders it on its
 * page; since MR-2b the Pages surface draws it too, via
 * the page's char geometry — and [setPdfMode] clears the
 * mark on every switch, because the offsets belong to the
 * representation that was searched. */
data class PdfSearchMark(
    val page: Int,
    val start: Int,
    val end: Int
)

/** v1.7 OE-1: the core reader's PDF state — one open
 * [PdfSession], one current-page variable serving both
 * modes (R15), and the mode's own state. Published only
 * for a local in-place PDF; null for every other book. */
sealed interface PdfReaderState {
    data object Loading : PdfReaderState

    /** The book asks for a password (K5): the screen
     * prompts and retries through the ViewModel. */
    data object PasswordRequired : PdfReaderState

    data class Failed(val message: String) : PdfReaderState

    data class Ready(
        val session: PdfSession,
        /** The page the book opened on (the surfaces'
         * first-composition seat). */
        val initialPage: Int,
        /** THE current page — both modes read and write
         * this one variable (R15). */
        val page: Int,
        val pageCount: Int,
        val mode: PdfReadMode,
        /** The PDF page settings (PrefPdf analogue),
         * per book — Pages mode's own set (R21). */
        val settings: PdfDocSettings,
        val stripState: PdfStripState,
        /** Whether the book has a usable text layer:
         * null while the strip is still landing; false
         * is R22's scanned verdict (Pages only, the
         * honest line at the switch). */
        val textAvailable: Boolean?,
        /** The outline title at-or-before [page]. */
        val anchorTitle: String?,
        /** (page + in-page fraction) / pageCount. */
        val bookFraction: Float,
        /** The current page's Reflow content, prepared
         * when Reflow shows it (R9); null outside Reflow
         * or before preparation lands. */
        val reflowContent: ChapterContent?,
        /** Reflow is waiting on the strip or on this
         * page's preparation. */
        val reflowPreparing: Boolean,
        /** AUTO contrast's resolved filter for the page
         * on screen, reported by the page cells (the
         * settings section's night toggle lands opposite
         * it). */
        val autoResolvedFilter: PdfPageFilter
    ) : PdfReaderState
}

/** v1.7 Moon+ standard fix (Part 2): one entry of the
 * book's own table of contents, bridged to the landed
 * chapter list — the label as the book prints it, the
 * chapter (segment) index it opens, and the char offset
 * inside that chapter (its fragment anchor's position, or
 * 0 when the entry carries no fragment). Published with
 * the open, atomically with the chapters themselves, so
 * the Contents sheet and the chrome counters never flip
 * mid-session. */
data class ReaderTocEntry(
    val label: String,
    val chapterIndex: Int,
    val charOffset: Int
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
    // ── v1.7 Moon+ document engine (Item 1) ──────────────────
    // The document is the book's chapter list as SEGMENTS, one
    // per chapter, keyed by chapter ID (never a bare index — a
    // mid-session chapter-list change must not rebind a key).
    // Segment state is DERIVED from the chapter row plus the
    // fetch machinery and published per segment; it is never
    // persisted. The windowed stream these fields replace is
    // retired: there is no window and no stream to re-enter.
    /** Every segment's state, keyed by chapter id. */
    val segmentStates: Map<Long, SegmentState> = emptyMap(),
    /** Display copies of the RESIDENT segments, keyed by
     * chapter id — what the document surface renders. */
    val segmentTexts: Map<Long, ChapterText> = emptyMap(),
    /** FAILED segments' honest reasons, keyed by chapter id. */
    val segmentFailedReasons: Map<Long, String> = emptyMap(),
    /** v1.7 Moon+ standard fix (Part 1): the segments whose
     * own text opens with its own title (decided by the
     * ViewModel at fill time from the ORIGINAL text — A2),
     * keyed by chapter id. The surface composes NO app
     * header for these: the book's own heading stands
     * alone, as the book made it. An undecided (unfilled)
     * segment is never in this set AND composes no header
     * either, so no header can pop at fill landing. */
    val segmentsWithOwnTitle: Set<Long> = emptySet(),
    /** Part 2: the book's own TOC, bridged to the landed
     * chapters — empty for every book without a prepared-
     * EPUB TOC (novels, unprepared books, TOC-less EPUBs),
     * which is exactly the signal the Contents sheet and
     * the counters use to stay on today's behaviour. */
    val tocEntries: List<ReaderTocEntry> = emptyList(),
    /** MR-4 (R5): the landed chapters' Contents depth
     * (parallel to `chapters`), read from the importer's
     * chapter-levels record at open. Empty = flat (every
     * book today except FB2); published only when the
     * record's fingerprint matches the Book row's AND its
     * size matches the landed chapter count. */
    val chapterLevels: List<Int> = emptyList(),
    /** Part 3: the open book is a local EPUB (prepared or
     * not — the document surface only ever serves the
     * prepared ones, and the chapter-end wall's FOR_EPUB
     * scope reads this flag through that surface). */
    val isEpubBook: Boolean = false,
    /** The resident run's chapter ids (Item 7) — the UI-side
     * layout worker evicts a segment's records when its id
     * leaves this set (Item 2, A4). */
    val residentSegmentIds: Set<Long> = emptySet(),
    /** Layout-ahead candidates (Item 2): the resident run's
     * display texts in distance order from the anchor, anchor
     * first — the UI-side worker measures them ahead into the
     * shared layout store. */
    val layoutAheadCandidates: List<Pair<Long, ChapterText>> = emptyList(),
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
    // ── v1.7 OE-4: the local CBZ/CBR page world ─────────────
    /** A local CBZ/CBR read in THIS reader (the "CBZ opens
     * in: Book reader" option, or the automatic fallback from
     * the manga reader): the chapter's stored page assets in
     * reading order (plain base names, resolved through the
     * same asset door as chapter images). ReaderScreen renders
     * the page world while this is non-empty. Empty for every
     * other book and chapter. */
    val cbzPages: List<String> = emptyList(),
    /** The current page inside [cbzPages] — the place is a
     * page number, kept in the progress row in the manga
     * reader's own shape (scrollFraction = page/(pages−1)),
     * so the two readers hand the place to each other. */
    val cbzPage: Int = 0,
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
    /** The tier gate for layout-ahead (FULL + STANDARD on,
     * MINIMAL off — LayoutAheadPolicy), resolved by the
     * ViewModel, which already reads the tier for prefetch.
     * Under the document engine the candidates are
     * [layoutAheadCandidates] above; the gate is unchanged. */
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

/**
 * v1.7 Moon+ document engine (Item 1): one segment's state in
 * the document. Derived, never persisted:
 * RESIDENT — its text is held and it renders its blocks;
 * LOADING — a parse or a live fetch is in flight (or a stored
 * copy stands ready to parse) and it renders the small
 * in-flow loading row;
 * MISSING — no copy on the phone and nothing in flight; it
 * renders the honest not-downloaded row with a download door;
 * FAILED — its last fill ended without a copy; it renders the
 * in-flow FAILED row with the reason and a retry door, and it
 * never auto-refills from composition (its retry is explicit).
 */
enum class SegmentState { RESIDENT, LOADING, MISSING, FAILED }

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
        val original = chapterId?.let { chapterCache[it] ?: segmentOriginals[it] }
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

    /** D13: republish the settings sheet's footnotes value
     * from the two layers (this book's override over the
     * global default) — called wherever those layers move
     * and when the prepared-EPUB flag lands. A non-prepared
     * book publishes null: no Footnotes section. */
    private fun publishFootnotes() {
        _footnotesInText.value = if (_preparedEpub.value) {
            bookOverride.footnotesInText ?: globalConfig.footnotesInText
        } else {
            null
        }
    }

    /** D13: the in-reader footnotes switch — option-scope
     * tier 2, like [setImageMode]: the change is written as
     * THIS book's override only; the global default is never
     * touched from inside a reader. The value republishes
     * optimistically so the switch answers the tap at once;
     * the new shape takes effect the next time the book
     * opens (the prepare is keyed on the value). */
    fun setFootnotesInText(value: Boolean) {
        _footnotesInText.value = value
        viewModelScope.launch {
            container.bookReaderPrefs.setFootnotesInText(bookId, value)
        }
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

    /** D13: this book's effective footnotes-in-text value
     * for the settings sheet — null unless the book is a
     * prepared EPUB (the sheet's Footnotes section renders
     * only then). The value is the book's own override over
     * the global default — the same effective value the
     * prepare is keyed on. */
    private val _footnotesInText = MutableStateFlow<Boolean?>(null)
    val footnotesInText: StateFlow<Boolean?> = _footnotesInText

    // ── v1.7 OE-1: PDF in the one reader ─────────────────
    /** The PDF state (null for every non-PDF book — the
     * chapter machinery below is then the whole reader). */
    private val _pdfReader = MutableStateFlow<PdfReaderState?>(null)
    val pdfReader: StateFlow<PdfReaderState?> = _pdfReader

    private var pdfSession: PdfSession? = null
    private var pdfBook: app.xreader.data.db.Book? = null
    private var pdfPassword: String? = null

    /** The one current-page variable (R15) + the in-page
     * fraction the surfaces report with it. */
    @Volatile private var pdfPage: Int = 0

    // v1.7 OE-4 patch (Fix B): once the reader starts closing
    // (ON_PAUSE on the way out), position reports are no longer
    // accepted. OE-1 phone proof (2026-11-21): the owner stopped
    // on p. 17, the pause flush saved p. 17 correctly — then a
    // report that arrived while the surface was leaving
    // composition (after the last frame he ever saw) moved the
    // field to p. 18, and the clear-time writer persisted that.
    // The saved page must be the page on screen when the reader
    // closes; freezing intake at pause makes the label he last
    // saw and the saved place the same number by construction.
    @Volatile private var pdfClosing: Boolean = false
    @Volatile private var pdfInPageFraction: Float = 0f

    /** Page jumps for the Pages bodies (Contents,
     * bookmarks, search, chrome seeks) — the shipped
     * LocalDoc jumps seam, re-homed. Emitted only in
     * Pages mode: the bodies are its only consumers, and
     * a jump buffered while Reflow is up would land
     * stale when Pages returns. */
    private val _pdfJumps =
        MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val pdfJumps: SharedFlow<Int> = _pdfJumps.asSharedFlow()

    private var pdfSaveJob: Job? = null
    private var pdfSettingsSaveJob: Job? = null

    /** The strip's cleaned page texts (R9's one
     * whole-strip pass), computed once per strip landing
     * and cached; per-page Reflow content is prepared
     * from it on demand. */
    @Volatile private var pdfCleanedTexts: List<String>? = null

    /** R20's two highlight forms: the Pages form is the
     * existing selection store's list (page + strip-text
     * range); the Reflow form lives under its own key
     * (page + Reflow-text range) — the forms never mix,
     * so each mode lists exactly its own. */
    private val _pdfPageHighlights =
        MutableStateFlow<List<StoredSelectionHighlight>>(emptyList())
    val pdfPageHighlights: StateFlow<List<StoredSelectionHighlight>> =
        _pdfPageHighlights
    private val _pdfReflowHighlights =
        MutableStateFlow<List<StoredSelectionHighlight>>(emptyList())
    val pdfReflowHighlights: StateFlow<List<StoredSelectionHighlight>> =
        _pdfReflowHighlights

    // ── v1.7 OE-1: PDF in-book search ────────────────────
    private val _pdfSearchHits =
        MutableStateFlow<List<PdfSearchHit>?>(null)
    val pdfSearchHits: StateFlow<List<PdfSearchHit>?> = _pdfSearchHits
    private val _pdfSearchBusy = MutableStateFlow(false)
    val pdfSearchBusy: StateFlow<Boolean> = _pdfSearchBusy
    private var pdfSearchJob: Job? = null
    private val _pdfSearchMark = MutableStateFlow<PdfSearchMark?>(null)
    val pdfSearchMark: StateFlow<PdfSearchMark?> = _pdfSearchMark

    /** The PDF strip feeder over this reader's own
     * ReadAloudController (OQ5: page-as-unit — the
     * shipped PdfStripSpeech, unaltered). */
    private var pdfSpeech: PdfStripSpeech? = null
    @Volatile private var ttsPdfActive: Boolean = false

    /** A PDF place captured synchronously at clear time
     * for the final progress write (onCleared's shape). */
    private data class PdfPlaceSnapshot(
        val page: Int,
        val inPageFraction: Float,
        val pageCount: Int,
        val anchorTitle: String?
    )

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

    /** v1.7 OE-4: the automatic-fallback switch line, set once
     * when this book arrived because the manga reader couldn't
     * open it; the screen shows it through the reader's
     * transient notice and consumes it (the migration note's
     * exact pattern). */
    private val _cbzSwitchNote = MutableStateFlow<String?>(null)
    val cbzSwitchNote: StateFlow<String?> = _cbzSwitchNote

    fun consumeCbzSwitchNote() {
        _cbzSwitchNote.value = null
    }

    /** v1.7 OE-4: this book is a local CBZ/CBR — its image
     * chapters render as the page world, not as in-text
     * pictures in the document flow. Set in the init book
     * pass, before any chapter lands. */
    private var isLocalCbzBook: Boolean = false

    private val _cbzJumps =
        MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val cbzJumps: SharedFlow<Int> = _cbzJumps.asSharedFlow()

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
                if (ttsPdfActive) {
                    // v1.7 OE-1 (R17): the PDF feeder advances
                    // itself — page-as-unit, never the wall.
                    pdfSpeech?.onChapterFinished()
                } else if (chapterWallActive()) {
                    // v1.6.2-mr10 (audit B1): the chapter-end
                    // wall stays closed to auto-crossing in
                    // listening as in reading — narration ENDS
                    // at this chapter's end instead of advancing
                    // across it. No pendingAutoplayTts, no
                    // advanceNarration: the end state is the
                    // same as finishing the book's last chapter,
                    // and the reader's place stays at the
                    // chapter's end.
                } else if (_state.value.hasNext) {
                    pendingAutoplayTts = true
                    // v1.7 document engine (B2): the advance is
                    // the NARRATION LANDING, not a chapter open —
                    // in scroll it follows into the next segment
                    // without touching the restore generation
                    // (a follow-class move, Item 5); in paged it
                    // is the shipped chapter turn.
                    advanceNarration(_state.value.chapterIndex + 1)
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
    private var growJob: kotlinx.coroutines.Job? = null
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

    /** The segment store: ORIGINALS held per segment, keyed
     * by chapter id (the successor of the stream cache —
     * chapterCache's discipline: originals only, display
     * copies derived per read). */
    private val segmentOriginals = mutableMapOf<Long, ChapterText>()

    /** The current DISPLAY derivation of each held original,
     * keyed by chapter id — published into the state by
     * [publishDocumentState] and read by the anchor paths.
     * Kept beside the originals so a terms swap re-derives
     * every resident segment in one pass. */
    private val segmentDisplay = mutableMapOf<Long, ChapterText>()

    /** Segments whose held text came from the RAW translation
     * (their display rides the translated road). */
    private val segmentTranslatedIds = mutableSetOf<Long>()

    /** Part 1: the own-title decisions, keyed by chapter id —
     * computed by [detectOwnTitle] when a segment's original
     * is first held, evicted with the segment, published by
     * [publishDocumentState]. */
    private val segmentsWithOwnTitle = mutableSetOf<Long>()

    /** FAILED segments' honest reasons, keyed by chapter id. */
    private val segmentFailedReasonsMap = mutableMapOf<Long, String>()

    /** THE one in-flight set (Item 1): every segment fill —
     * parse fills and fetch fills alike — registers here, so
     * any number of triggers for one segment produce exactly
     * one fill. */
    private val segmentFillsInFlight = mutableSetOf<Long>()

    private var residencyJob: kotlinx.coroutines.Job? = null

    /** The current resident run (chapter indices), the
     * planner's output — published as ids in the state. */
    private var residentRunIndices: List<Int> = emptyList()

    /** True once the book's first load has returned and the
     * document exists (Item 3, row 10). Residency planning
     * and mode flips act only after this. */
    @Volatile
    private var readerOpened: Boolean = false

    /** The segment the surface last reported approaching
     * (Item 1's approach door): protected from eviction
     * while it is the target. One field; a newer report
     * replaces it. */
    private var approachTargetId: Long? = null

    /** B2: a narration advance waiting on its segment's
     * fill — the landing runs when the fill completes. */
    private var pendingNarrationChapterId: Long? = null

    /** Item 3, row 1: anchor segments whose content swap
     * (translation / terms) is in flight — when the swapped
     * text lands, the place re-asserts once, scoped to the
     * anchor, through [moveAnchor]. */
    private val pendingSwapReassertIds = mutableSetOf<Long>()

    /** v1.3.3 (scope S6): chapters whose extraction failure
     * already earned its one re-resolution this session. */
    private val extractionRetriedFor = mutableSetOf<Long>()

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
                publishFootnotes()
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
                publishFootnotes()
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
                if (prepared) {
                    rows.filter { it.locatorJson == null }
                } else {
                    rows
                }
            }.collectLatest { _bookmarks.value = it }
        }
        viewModelScope.launch {
            combine(
                container.database.highlightDao().observeForBook(bookId),
                _preparedEpub
            ) { rows, prepared ->
                if (prepared) {
                    rows.filter { it.locatorJson == null }
                } else {
                    rows
                }
            }.collectLatest { _highlights.value = it }
        }
        viewModelScope.launch {
            val book = container.database.bookDao().getById(bookId)
            // v1.7 OE-4: a local CBZ/CBR's image chapters are
            // this reader's page world (never in-text pictures),
            // and a book that arrived through the automatic
            // fallback carries its one plain switch line.
            isLocalCbzBook = book != null &&
                book.sourceType ==
                    app.xreader.data.db.SourceType.LOCAL_FILE &&
                (book.localFormat ==
                    app.xreader.data.db.BookFormat.CBZ ||
                    book.localFormat ==
                        app.xreader.data.db.BookFormat.CBR)
            if (container.cbzSwitchNote.value == bookId) {
                container.cbzSwitchNote.value = null
                _cbzSwitchNote.value =
                    "This comic didn't open in the manga reader, " +
                        "so it's opened in the book reader."
            }
            // v1.7 OE-1 (R1, R3): a local in-place PDF opens
            // HERE, in the one reader — its own branch
            // (cleanup, then the PdfSession, then
            // page-shaped state). The chapter machinery
            // below never runs for it. A local PDF with no
            // file to open (the legacy lane) is not this
            // branch: it keeps the converted-chapter road
            // below, exactly as before.
            if (book != null &&
                book.sourceType ==
                    app.xreader.data.db.SourceType.LOCAL_FILE &&
                book.localFormat == app.xreader.data.db.BookFormat.PDF &&
                BookEngine.isInPlaceBook(book)
            ) {
                openPdfBook(book, initialChapterIndex)
                return@launch
            }
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
                    publishFootnotes()
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
            // v1.7 MR-2 (Part C): the chapter-count heal
            // where prepared books actually open. A prepared
            // EPUB's Library card count is the LANDED row
            // count; the stored count is the import-era
            // spine number and never self-corrects on this
            // road (the local reader's heal never fires for
            // a book that opens straight here — the mr4
            // phone proof's '42 chapters' card). A4: the
            // row is re-read here and written only when the
            // count differs.
            if (_preparedEpub.value && book != null) {
                val row = container.database.bookDao().getById(bookId)
                if (row != null && row.totalChapters != chapters.size) {
                    container.database.bookDao().update(
                        row.copy(totalChapters = chapters.size)
                    )
                }
            }
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
            // Part 2 + Part 3: the open book's EPUB-ness.
            // Moon Reader update (patch 1): the bridged-TOC
            // overlay that used to be computed here is
            // RETIRED — segmentation made a prepared EPUB's
            // chapters BE the book's own TOC at conversion,
            // so the Contents sheet and the chrome counters
            // read the chapter rows themselves and
            // tocEntries stays empty for every book.
            val epubBook = book != null &&
                book.sourceType ==
                    app.xreader.data.db.SourceType.LOCAL_FILE &&
                book.localFormat == app.xreader.data.db.BookFormat.EPUB
            // v1.0.6 (W3.4): a browser-view source gets ONE plain
            // note per source per session, so slower chapter turns
            // never read as the app hanging.
            val sourceNotice = browserViewNoticeFor(book)
            // MR-4 (R5): the chapter-levels record, read once
            // at open. Published only when the record's
            // fingerprint is the Book row's own AND the list
            // is parallel to the landed chapters — any
            // mismatch renders the Contents flat.
            val levelsRecord = runCatching {
                localReaderPrefs.chapterLevelsRecord(bookId)
            }.getOrNull()
            val chapterLevels = levelsRecord
                ?.takeIf { record ->
                    book?.fileFingerprint != null &&
                        record.fingerprint == book.fileFingerprint &&
                        record.levels.size == chapters.size
                }
                ?.levels ?: emptyList()
            _state.value = _state.value.copy(
                bookTitle = book?.title ?: "",
                chapters = chaptersUpdated(chapters),
                tocEntries = emptyList(),
                chapterLevels = chapterLevels,
                isEpubBook = epubBook,
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
            // v1.7 document engine (Item 3, row 10 + Item 6):
            // the book opens exactly as it always has — the
            // anchor chapter through loadChapter, its saved
            // place consumed untouched. Only AFTER that first
            // load returns does the document exist: in scroll
            // mode the residency planner starts filling the
            // segments around the anchor. Nothing else is read
            // at open; the segment index the surface builds is
            // O(chapters) over the descriptors already loaded.
            readerOpened = true
            if (_config.value.mode == ReaderMode.SCROLL) {
                planResidency(startIndex)
            }
        }
        // v1.7 document engine (Item 5): a reading-mode flip
        // mid-session carries the LIVE place — paged→scroll
        // anchors the document at (chapterIndex, positionOffset)
        // through moveAnchor; scroll→paged re-enters through
        // the shipped loadChapter road at the same place. The
        // first emission never acts (it is the stored config
        // arriving, and the book may not be open yet).
        viewModelScope.launch {
            var lastMode: ReaderMode? = null
            _config.map { it.mode }.distinctUntilChanged()
                .collectLatest { mode ->
                    val previous = lastMode
                    lastMode = mode
                    if (!readerOpened || previous == null ||
                        previous == mode
                    ) {
                        return@collectLatest
                    }
                    if (mode == ReaderMode.SCROLL) {
                        moveAnchor(
                            _state.value.chapterIndex,
                            _state.value.positionOffset,
                            PageHint.OFFSET
                        )
                    } else {
                        openChapterAt(
                            _state.value.chapterIndex,
                            _state.value.positionOffset
                        )
                    }
                }
        }
        // A transition flip inside scroll re-plans the
        // residency shape (Item 4: headers, fill policy and
        // end reporting are the surface's and the planner's
        // readings of the same config value).
        viewModelScope.launch {
            _config.map { it.chapterTransition }.distinctUntilChanged()
                .collectLatest {
                    if (readerOpened) {
                        planResidency(_state.value.chapterIndex)
                    }
                }
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

    /**
     * v1.7 document engine (Item 3, row 2): a chapter jump is
     * an ANCHOR MOVE in scroll mode — the document stays one
     * document; the anchor, the restore fields and the
     * generation move through [moveAnchor], and the target
     * segment fills if it isn't resident. In paged mode the
     * shipped loadChapter road runs exactly as before.
     * Deliberate small behaviour change (spec Item 3, row 2):
     * opening the CURRENT chapter in scroll now scrolls to
     * its segment's top — the windowed build's early return
     * made Contents-on-current-chapter a no-op; the paged
     * early return below is unchanged.
     */
    fun openChapter(index: Int) {
        if (_config.value.mode == ReaderMode.SCROLL) {
            moveAnchor(index, 0, PageHint.FIRST)
            return
        }
        if (index == _state.value.chapterIndex && _state.value.chapterText != null) return
        launchLoad { loadChapter(index, 0, 0f) }
    }

    /** Item 3, row 2: a jump to a place inside a chapter
     * (Contents, search, bookmarks) — an anchor move in
     * scroll, the shipped load in paged. */
    fun openChapterAt(index: Int, charOffset: Int) {
        if (_config.value.mode == ReaderMode.SCROLL) {
            moveAnchor(index, charOffset, PageHint.OFFSET)
            return
        }
        launchLoad { loadChapter(index, charOffset, 0f, PageHint.OFFSET) }
    }

    /**
     * v1.7 document engine (Item 5): THE anchor move — the
     * only scroll-mode bumper of restoreGeneration (the only
     * other bump is loadChapter's landing). Discipline
     * (smoothness fix §1, Moon+'s shape — the swap is the
     * gesture's own step, bookkeeping is not on its path):
     * ONE state copy publishes FIRST (chapterIndex,
     * restoreOffset, restoreHint, restoreGeneration + 1) —
     * and, when the target segment is RESIDENT, its display
     * as chapterText in the same copy, so the surface's
     * landing seed can assert in the same composition. The
     * outgoing place is flushed AFTER the publish (progress
     * + session segment, exactly) from the door's capture —
     * [OutgoingFlush], snapshotted below while the state
     * still describes the outgoing chapter, so the saved row
     * is the same row the old flush-first order wrote. When
     * the target is not resident, chapterText keeps the
     * previous chapter (the keep-previous discipline) until
     * the fill lands and [followAnchorChapter] publishes it.
     * The restore hint is carried, never pre-resolved: LAST
     * resolves against the segment's text length at assertion
     * time, inside the surface.
     */
    fun moveAnchor(index: Int, charOffset: Int, hint: PageHint) {
        val stateNow = _state.value
        val chapter = stateNow.chapters.getOrNull(index) ?: return
        val chapterChanges = index != stateNow.chapterIndex
        // Smoothness fix (§1): the persistence chain's inputs,
        // captured from the OUTGOING state here at the door —
        // the publish below no longer waits for the chain, and
        // the chain, running after it, reads only this capture.
        val outgoing = if (chapterChanges) {
            captureOutgoingFlush(stateNow)
        } else {
            null
        }
        viewModelScope.launch {
            val display = segmentDisplay[chapter.id]
            _state.value = _state.value.copy(
                chapterIndex = index,
                restoreOffset = charOffset,
                restoreHint = hint,
                restoreGeneration = _state.value.restoreGeneration + 1,
                chapterText = display ?: _state.value.chapterText,
                showingTranslation = if (display != null) {
                    chapter.id in segmentTranslatedIds
                } else {
                    _state.value.showingTranslation
                }
            )
            // Smoothness fix (§1): the persistence chain runs
            // AFTER the publish, consuming only the door's
            // capture of the outgoing state — and still ahead
            // of tracker.begin(), so the session segment it
            // ends is the outgoing one, as before.
            if (chapterChanges && outgoing != null) {
                flushProgressNow(outgoing)
                flushSession(outgoing)
                runCatching { container.recordHistoryStart(bookId) }
            }
            if (chapterChanges) tracker.begin()
            if (display != null) {
                visibleTextChapterIndex = index
                followSummaryFor(index)
            }
            requestSegmentFill(chapter.id)
            planResidency(index)
        }
    }

    /** The anchor segment's display is on screen: publish it
     * as the visible chapter (chapterText + its translation
     * road) and follow it with the summary observation — the
     * scroll-mode half of a landing, used when a fill lands
     * on the anchor and by [moveAnchor] for resident targets.
     * Never touches the restore fields or the generation. */
    private fun followAnchorChapter(index: Int) {
        val chapter = _state.value.chapters.getOrNull(index) ?: return
        val display = segmentDisplay[chapter.id] ?: return
        visibleTextChapterIndex = index
        _state.value = _state.value.copy(
            chapterText = display,
            showingTranslation = chapter.id in segmentTranslatedIds
        )
        followSummaryFor(index)
    }

    /** Follow [index]'s cached AI summary for the Contents
     * sheet (clears first so a stale summary never shows) —
     * the summary half of every landing, extracted so
     * loadChapter's landing and the scroll follows share it. */
    private fun followSummaryFor(index: Int) {
        val chapter = _state.value.chapters.getOrNull(index) ?: return
        _chapterSummary.value = null
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            container.database.aiSummaryDao().observe(bookId, chapter.id)
                .collectLatest { _chapterSummary.value = it }
        }
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
        // v1.7 OE-1 (R18): a PDF searches page by page,
        // not chapter by chapter.
        if (pdfSession != null) {
            searchPdfBook(query.trim())
            return
        }
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
        // v1.7 OE-1: the PDF sweep clears the same way.
        // The search MARK is not cleared here: a hit tap
        // closes the sheet (which calls this) on its way
        // to showing the mark on the page — the next
        // search replaces it instead.
        pdfSearchJob?.cancel()
        _pdfSearchHits.value = null
        _pdfSearchBusy.value = false
    }

    fun nextChapter() {
        if (!_state.value.hasNext) return
        markCurrentChapterFinished()
        val target = _state.value.chapterIndex + 1
        if (_config.value.mode == ReaderMode.SCROLL) {
            moveAnchor(target, 0, PageHint.FIRST)
        } else {
            launchLoad { loadChapter(target, 0, 0f) }
        }
    }

    /**
     * Fix Round G (B7): the wall surface appended past a
     * chapter's end — that chapter counts as finished. In
     * wall mode the append is the ONLY finished-marking
     * door: the surface never fires onChapterEndReached
     * there, position reports never mark (the
     * CHAPTER_BY_CHAPTER ≥90% rule is CONTINUOUS-only), and
     * scrolling on into an appended chapter marks nothing
     * more — so nothing is marked twice and nothing is
     * marked by mere visibility. (The non-wall pinned mode
     * keeps its own door.) The anchor is NOT moved here:
     * the Fix F hard swap is gone (B5) — the surface's
     * position reports move the anchor as the stream
     * follows the reader across the gap, and the surface
     * names the crossed chapter itself, so the marking
     * lands on the right chapter even when the anchor has
     * already followed an earlier crossing.
     */
    fun markFinishedAtWall(chapterIndex: Int) {
        markCurrentChapterFinishedFor(chapterIndex)
    }

    fun previousChapter() {
        if (!_state.value.hasPrevious) return
        val target = _state.value.chapterIndex - 1
        if (_config.value.mode == ReaderMode.SCROLL) {
            // Item 3, row 2: chrome Previous in scroll is an
            // anchor move carrying the LAST hint — the surface
            // resolves it against the target segment's text
            // length when it asserts (the hint travels; it is
            // never resolved here against a text that may not
            // be resident yet).
            moveAnchor(target, 0, PageHint.LAST)
        } else {
            launchLoad { loadChapter(target, 0, 0f, PageHint.LAST) }
        }
    }

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
     * already causes.
     *
     * v1.7 document engine (Item 3, row 9): a terms change is
     * a content swap on EVERY resident segment — each held
     * original is re-derived under the new terms, each
     * derivation feeds the char map its display length, and
     * the published segment texts are refreshed in the same
     * pass. The paged road below it is the shipped one: the
     * visible chapter re-derives from its cached original.
     * In scroll the anchor's scoped re-assert rides
     * [moveAnchor] with the saved offset clamped to the new
     * text — today's clamped-offset semantics, preserved
     * deliberately (a content-swap landing can drift a
     * sentence; that is the accepted cost, recorded in the
     * spec's risks). */
    private fun reapplyTerms() {
        if (segmentOriginals.isNotEmpty()) {
            for ((id, original) in segmentOriginals.toMap()) {
                val display = displayText(original, id)
                segmentDisplay[id] = display
                // Item 1a: the map prices DISPLAY lengths.
                bookDocument.setExactLength(id, display.length)
            }
            publishDocumentState()
        }
        val stateNow = _state.value
        val chapter = stateNow.chapter ?: return
        if (_config.value.mode == ReaderMode.SCROLL) {
            val anchorDisplay = segmentDisplay[chapter.id]
            if (anchorDisplay != null) {
                if (anchorDisplay !== stateNow.chapterText) {
                    _state.value = stateNow.copy(chapterText = anchorDisplay)
                }
                moveAnchor(
                    stateNow.chapterIndex,
                    stateNow.positionOffset.coerceAtMost(anchorDisplay.length),
                    PageHint.OFFSET
                )
            }
            return
        }
        val original = chapterCache[chapter.id]
            ?: segmentOriginals[chapter.id] ?: return
        val display = displayText(original, chapter.id)
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
        // v1.7 document engine: the windowed build's Fix-1
        // stream close stood here. It is retired WITH the
        // machinery it patched — there is no stream to close
        // and no window to re-enter; a load's landing is a
        // state publish the document surface reads directly
        // (Item 3's table: every ex-A1 path is a segment
        // operation now, and the ones that still ride this
        // function — paged opens, retries, the Preparing
        // reload — behave exactly as they did).
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
        // The landing itself is the extracted narration
        // landing (B2) — loadChapter calls it with the
        // generation bump, exactly the landing this function
        // has always performed.
        performNarrationLanding(
            index = index,
            chapter = chapter,
            original = text,
            useTranslation = useTranslation,
            restoreOffset = restoreOffset,
            restoreScrollFraction = restoreScrollFraction,
            hint = hint,
            bumpGeneration = true
        )
    }

    /**
     * v1.7 document engine (B2): THE NARRATION LANDING —
     * loadChapter's landing sequence, extracted as one named
     * step with the generation bump as a parameter, performing
     * in loadChapter's landing order: (1) tracker.begin();
     * (2) visibleTextChapterIndex = the landed chapter and
     * currentChapterMaxOffset = the restore offset (0 on a
     * narration follow); (3) the char map fed the landed
     * chapter's DISPLAY length; (4) the landing state publish
     * (chapterText = the display copy, showingTranslation,
     * hasOverlay / isImageBook / mangaPages / mangaChapterId
     * from the content, loading = false; the restore fields
     * and the generation bump ONLY when [bumpGeneration]);
     * (5) the summary follow; (6) onPosition(restoreOffset,
     * restoreScrollFraction); (7) the Media Overlay prepare
     * when the chapter carries one; (8) prefetchNeighbours
     * for the landed chapter; (9) the autoplay block, when
     * armed — the controller is loaded from the SEGMENT
     * STORE'S HELD ORIGINAL for the chapter, never the
     * display copy (the P0 rule: the voice speaks originals;
     * terms change what's seen, not what's spoken).
     *
     * Callers: loadChapter (bump = true — the open/paged
     * road, behaviour unchanged) and the read-aloud follow
     * advance (bump = false — a follow-class move: the
     * surface is never re-anchored out from under the
     * listener).
     */
    private fun performNarrationLanding(
        index: Int,
        chapter: Chapter,
        original: ChapterText,
        useTranslation: Boolean,
        restoreOffset: Int,
        restoreScrollFraction: Float,
        hint: PageHint,
        bumpGeneration: Boolean
    ) {
        val content: ChapterContent = original.content
        // v1.7 OE-4: a local CBZ/CBR chapter's stored pages —
        // its image blocks in order — and the page the saved
        // place names (the row's scrollFraction is the manga
        // reader's own restore currency: page/(pages−1)).
        val cbzPageList = if (isLocalCbzBook && content.isImageBook) {
            content.blocks
                .filterIsInstance<ChapterBlock.Image>()
                .map { it.fileName }
        } else {
            emptyList()
        }
        val cbzStartPage = if (cbzPageList.size > 1) {
            (restoreScrollFraction * (cbzPageList.size - 1) + 0.5f)
                .toInt()
                .coerceIn(0, cbzPageList.size - 1)
        } else {
            0
        }
        tracker.begin()
        currentChapterMaxOffset = restoreOffset
        visibleTextChapterIndex = index
        val displayCopy = displayText(original, chapter.id)
        // v1.7 Patch 2 (item 1a): feed the char map the DISPLAY
        // length — every offset the map serves is reported
        // against the display text the renderers consume.
        bookDocument.setExactLength(chapter.id, displayCopy.length)
        _state.value = _state.value.copy(
            loading = false,
            chapterIndex = index,
            chapterText = displayCopy,
            restoreOffset = restoreOffset,
            restoreScrollFraction = restoreScrollFraction,
            restoreHint = hint,
            restoreGeneration = if (bumpGeneration) {
                _state.value.restoreGeneration + 1
            } else {
                _state.value.restoreGeneration
            },
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
            // v1.7 OE-4: the CBZ page world rides the same
            // copy, so a chapter turn can never pair one
            // chapter's text with another's pages.
            cbzPages = cbzPageList,
            cbzPage = cbzStartPage,
            showingTranslation = useTranslation
        )
        followSummaryFor(index)
        onPosition(restoreOffset, restoreScrollFraction)
        // Media Overlay: prepare (but don't play) the book's own audio.
        content.mediaOverlay?.let { overlay ->
            overlaySync = OverlaySync(overlay.clips)
            val audio = File(
                container.bookStorage.contentDir(bookId), "img-${overlay.audioFileName}"
            )
            overlayPlayer.load(audio)
        }
        prefetchNeighbours(index)
        if (pendingAutoplayTts) {
            pendingAutoplayTts = false
            // Read-aloud deliberately gets the ORIGINAL text —
            // the segment store's held original for this
            // chapter (chapterCache's copy of the same
            // original on the paged road), never the display
            // copy. Its sentence range is remapped for the
            // highlight in [ttsState].
            ttsChapterId = chapter.id
            readAloud.loadChapter(
                segmentOriginals[chapter.id]
                    ?: chapterCache[chapter.id]
                    ?: original,
                0
            )
            readAloud.setRate(_config.value.speechRate)
            readAloud.play()
        }
    }

    /**
     * v1.6.2-mr10 (audit B1): the chapter-end wall's gate,
     * computed from exactly the inputs the screen's own
     * wall gate uses (ReaderScreen's `wallActive`): the
     * Chapter-by-chapter transition mode, the wall scope
     * setting, and — for the FOR_EPUB_BOOKS scope — whether
     * this book is an EPUB book. Listening consults the
     * same gate reading does.
     */
    private fun chapterWallActive(): Boolean {
        val config = _config.value
        if (config.chapterTransition !=
            ChapterTransition.CHAPTER_BY_CHAPTER
        ) {
            return false
        }
        return when (config.chapterEndWall) {
            ChapterEndWall.EVERY_BOOK -> true
            ChapterEndWall.OFF -> false
            ChapterEndWall.FOR_EPUB_BOOKS -> _state.value.isEpubBook
        }
    }

    /**
     * v1.7 document engine (B2): the read-aloud follow
     * advance — the controller finished a chapter and the
     * next one takes over the narration. PAGED: the shipped
     * chapter turn (loadChapter, which lands through
     * [performNarrationLanding] with the bump). SCROLL: if
     * the next segment is RESIDENT, the narration landing
     * runs at once from the store's held original — NO
     * generation bump, so the surface never re-anchors; if
     * it is not resident, its fill is requested at anchor
     * priority and the landing runs when the fill completes
     * (the pending record below) — Listen advancing faster
     * than fills simply waits, honestly.
     */
    private fun advanceNarration(index: Int) {
        if (_config.value.mode == ReaderMode.PAGED) {
            launchLoad { loadChapter(index, 0, 0f) }
            return
        }
        val chapter = _state.value.chapters.getOrNull(index) ?: return
        val original = segmentOriginals[chapter.id]
        if (original != null) {
            performNarrationLanding(
                index = index,
                chapter = chapter,
                original = original,
                useTranslation = chapter.id in segmentTranslatedIds,
                restoreOffset = 0,
                restoreScrollFraction = 0f,
                hint = PageHint.FIRST,
                bumpGeneration = false
            )
        } else {
            pendingNarrationChapterId = chapter.id
            requestSegmentFill(chapter.id)
        }
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
     * chapter's load; its fetch resumes through the queue.
     * v1.7 document engine (Item 3, row 7): in scroll with no
     * load error standing, the retry is the anchor segment's
     * explicit fill re-request — the FAILED row's own door
     * (retrySegment) is the same call for any segment. */
    fun retryCurrentChapter() {
        val stateNow = _state.value
        if (_config.value.mode == ReaderMode.SCROLL &&
            stateNow.errorMessage == null
        ) {
            stateNow.chapter?.id?.let { retrySegment(it) }
        } else {
            launchLoad { loadChapter(stateNow.chapterIndex, 0, 0f) }
        }
    }

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
            // v1.7 document engine (Item 2): the Patch-2
            // layout-ahead candidate pair this block used to
            // publish is retired — the candidates are the
            // resident run's display texts, published by
            // publishDocumentState() from the segment store;
            // the tier gate rides the appearance collector.
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

    // ── The document (v1.7 Moon+ document engine) ────────────
    //
    // The book is ONE document of segments (Item 1): every
    // chapter is a segment, keyed by chapter ID, holding one
    // of four derived states (RESIDENT / LOADING / MISSING /
    // FAILED). Segments fill IN PLACE — there is no window to
    // open, close, slide or re-enter, and the windowed build's
    // whole A1 apparatus (openStream / extendStream /
    // closeStream, the pinned window, the boundary indices)
    // is retired with it. Position is always reported as
    // (chapterIndex, charOffset) into the same progress
    // machinery the paged reader uses, so bookmarks, sessions
    // and sync don't know the document exists.

    /** Per-chapter settled fractions, for the CONTINUOUS
     * left-behind marking (Item 4). */
    private val reportedFractions = mutableMapOf<Int, Float>()

    /**
     * The segment store's read (Item 1): the parse road of a
     * segment fill. Holds ORIGINALS (like chapterCache); each
     * caller gets the display copy for the terms in force
     * right now, and the derivation is kept in
     * [segmentDisplay] for the anchor paths.
     * v1.7 Patch 2 (item 1a): the display copy is a char-map
     * feed on both roads — the document renders display text,
     * so the map prices display lengths.
     *
     * A7 (the swap-vs-fill race, decided): the parse captures
     * its inputs — the originalPreferred membership and the
     * translated-content presence — BEFORE the storage read;
     * if either changed when the read lands, the landed text
     * is DISCARDED (never held, never published) and the
     * parse re-runs under the current preference. The current
     * preference always wins; no interleaving can publish a
     * stale-road text. The re-run is bounded; past the bound
     * the segment stays LOADING and a later trigger re-runs
     * it (a preference cannot flip forever).
     */
    private suspend fun loadSegmentText(chapter: Chapter): ChapterText? {
        segmentOriginals[chapter.id]?.let { cached ->
            val display = displayText(cached, chapter.id)
            segmentDisplay[chapter.id] = display
            bookDocument.setExactLength(chapter.id, display.length)
            return display
        }
        if (chapter.contentPath == null) return null
        var attempts = 0
        while (true) {
            val useTranslation = chapter.id !in originalPreferred &&
                container.bookStorage.hasTranslatedContent(bookId, chapter.id)
            val result = if (useTranslation) {
                container.bookStorage.readTranslatedContent(bookId, chapter.id)
            } else {
                container.bookStorage.readChapterContent(bookId, chapter.id)
            }
            val original = (result as? AppResult.Ok)?.value
                ?.let { ChapterText(it) } ?: return null
            val stillUseTranslation = chapter.id !in originalPreferred &&
                container.bookStorage.hasTranslatedContent(bookId, chapter.id)
            if (stillUseTranslation != useTranslation) {
                if (++attempts >= 3) return null
                continue
            }
            segmentOriginals[chapter.id] = original
            detectOwnTitle(chapter, original)
            if (useTranslation) {
                segmentTranslatedIds += chapter.id
            } else {
                segmentTranslatedIds -= chapter.id
            }
            val display = displayText(original, chapter.id)
            segmentDisplay[chapter.id] = display
            bookDocument.setExactLength(chapter.id, display.length)
            return display
        }
    }

    /**
     * Part 1 (A2): the own-title decision for one segment,
     * computed from its ORIGINAL text at the moment the
     * original is first held — never from the display copy,
     * so a translated title line can never resurrect the
     * doubled header in translated reading. The reconciled
     * header line is the surface's own (ReaderCounters,
     * unchanged logic), computed against the chapter's
     * position in the CURRENT list.
     */
    private fun detectOwnTitle(chapter: Chapter, original: ChapterText) {
        val chapters = _state.value.chapters
        val index = chapters.indexOfFirst { it.id == chapter.id }
        if (index < 0) return
        val ownNumberFloat = app.xreader.data.extension.ChapterNumbers
            .parseAny(chapter.title, "")
        val headerLine = ReaderCounters.headerLine(
            chapterTitle = chapter.title,
            chapterIndex = index,
            chapterCount = chapters.size,
            ownNumber = ownNumberFloat
        )
        val ownNumber = ownNumberFloat?.toInt() ?: (index + 1)
        if (ChapterTitleDetection.opensWithOwnTitle(
                original.content.blocks, chapter.title, headerLine, ownNumber
            )
        ) {
            segmentsWithOwnTitle += chapter.id
        } else {
            segmentsWithOwnTitle -= chapter.id
        }
    }

    // Moon Reader update (patch 1): the Part 2 TOC bridge
    // (computeEpubToc / bridgeEpubToc / fragmentOffset and
    // its EpubTocData cache) is REMOVED, not merely unused.
    // Segmentation made a prepared EPUB's chapter rows BE
    // the book's own table of contents at conversion time,
    // so the reader needs no open-time bridge — and the
    // bridge's load-bearing assumption (spine index ==
    // chapter index) is false under segmentation. Contents
    // and the counters read chapter rows; the stale cache
    // in LocalReaderPrefs went with it.

    /** v1.7 Patch 2 (item 1d): the residency planner's
     * per-chapter char counts — the char map's EXACT length
     * where the map has the chapter parsed, the word-count
     * estimate otherwise. The planner's budget values are
     * unchanged. */
    private fun documentCharCounts(chapters: List<Chapter>): List<Int> =
        chapters.mapIndexed { index, chapter ->
            if (bookDocument.isExact(index)) {
                bookDocument.lengthOf(index)
            } else {
                (chapter.wordCount * 6).coerceAtLeast(1)
            }
        }

    /**
     * The ONE writer of the document's published state
     * (Item 1): segment states are a PURE FUNCTION of the
     * facts — a segment is RESIDENT when its original is
     * held, FAILED when a failure reason stands, LOADING
     * when a fill is in flight or a stored copy stands ready
     * to parse, MISSING otherwise — re-derived over the
     * chapter list in O(chapters) and published in ONE state
     * copy together with the resident display texts, the
     * failure reasons, the resident run's ids and the
     * layout-ahead candidates. Called only at disciplined
     * sites: a fill starting, landing or failing; an
     * invalidation; an eviction; a residency (re)plan; a
     * chapter-list change that adds segments. Read-flag
     * copies (chaptersUpdated) never call it — the id-keyed
     * maps stay valid across them by construction.
     */
    private fun publishDocumentState() {
        val stateNow = _state.value
        val chapters = stateNow.chapters
        if (chapters.isEmpty()) return
        val states = HashMap<Long, SegmentState>(chapters.size)
        for (chapter in chapters) {
            states[chapter.id] = when {
                chapter.id in segmentOriginals -> SegmentState.RESIDENT
                chapter.id in segmentFailedReasonsMap -> SegmentState.FAILED
                chapter.id in segmentFillsInFlight -> SegmentState.LOADING
                chapter.contentPath != null -> SegmentState.LOADING
                else -> SegmentState.MISSING
            }
        }
        val anchor = stateNow.chapterIndex
        val candidates = residentRunIndices
            .sortedBy { kotlin.math.abs(it - anchor) }
            .mapNotNull { i ->
                chapters.getOrNull(i)?.let { ch ->
                    segmentDisplay[ch.id]?.let { ch.id to it }
                }
            }
        _state.value = stateNow.copy(
            segmentStates = states,
            segmentTexts = segmentDisplay.toMap(),
            segmentFailedReasons = segmentFailedReasonsMap.toMap(),
            segmentsWithOwnTitle = segmentsWithOwnTitle.toSet(),
            residentSegmentIds = residentRunIndices
                .mapNotNull { chapters.getOrNull(it)?.id }
                .toSet(),
            layoutAheadCandidates = candidates
        )
    }

    /**
     * Item 7: residency planning — a launched job (a segment
     * loop never runs on the composition path), scroll mode
     * only, and only once the book is open. The run's SHAPE
     * is the transition's (Item 4): CONTINUOUS plans through
     * [StreamWindowPlanner.plan] under the tier budget (the
     * planner and its pinned test stand unmodified);
     * CHAPTER_BY_CHAPTER keeps the shipped pinned shape —
     * the anchor plus up to 2 behind, nothing ahead — as a
     * residency shape: the boundary trigger is its only fill
     * door forward. Fills are requested in distance order
     * from the anchor (anchor first); segments outside the
     * run ± the 2-segment hysteresis are evicted (never the
     * anchor, the approach target, the narration chapters or
     * a fill in flight).
     */
    private fun planResidency(anchorIndex: Int) {
        if (!readerOpened) return
        if (_config.value.mode != ReaderMode.SCROLL) return
        // Manga programme (slice M2): the document is a TEXT
        // model — a manga book never plans residency in it
        // (its chapters are page lists; the image reader owns
        // them). Carried from the retired openStream guard.
        if (bookConfig?.mediaKind?.isManga == true) return
        if (_state.value.mangaPages != null) return
        residencyJob?.cancel()
        residencyJob = viewModelScope.launch {
            val chapters = _state.value.chapters
            if (chapters.isEmpty()) return@launch
            val anchor = anchorIndex.coerceIn(0, chapters.lastIndex)
            val counts = documentCharCounts(chapters)
            val minimal = performanceTier ==
                app.xreader.data.prefs.PerfTier.MINIMAL
            val run: List<Int> = if (
                _config.value.chapterTransition ==
                ChapterTransition.CHAPTER_BY_CHAPTER
            ) {
                ((anchor - 2).coerceAtLeast(0)..anchor).toList()
            } else {
                val window = StreamWindowPlanner.plan(
                    anchorIndex = anchor,
                    chapterCharCounts = counts,
                    charBudget = StreamWindowPlanner.charBudgetFor(minimal)
                )
                (window.fromIndex..window.toIndex).toList()
            }
            residentRunIndices = run
            publishDocumentState()
            for (index in run.sortedBy { kotlin.math.abs(it - anchor) }) {
                chapters.getOrNull(index)?.let {
                    requestSegmentFill(it.id)
                }
            }
            evictSegmentsOutsideRun(run, anchor)
        }
    }

    /** Item 7: drop the held text of segments outside the
     * run ± the hysteresis — on the fill-worker's side,
     * never the composition path. A segment whose list
     * position no longer resolves (the repair path rebuilt
     * the list) is dropped too: the maps are id-keyed, so a
     * survivor is never mis-bound, and a dropped segment
     * simply re-derives from its chapter row. */
    private fun evictSegmentsOutsideRun(run: List<Int>, anchor: Int) {
        if (run.isEmpty()) return
        val chapters = _state.value.chapters
        val from = (run.first() - 2).coerceAtLeast(0)
        val to = (run.last() + 2).coerceAtMost(chapters.lastIndex)
        val stateNow = _state.value
        val protectedIds = buildSet {
            chapters.getOrNull(anchor)?.id?.let { add(it) }
            approachTargetId?.let { add(it) }
            ttsChapterId?.let { add(it) }
            pendingNarrationChapterId?.let { add(it) }
            // A segment carrying the Media Overlay target
            // (overlay ids live on heading/paragraph blocks).
            stateNow.overlayTextId?.let { overlayId ->
                segmentDisplay.entries.firstOrNull { (_, text) ->
                    text.content.blocks.any { block ->
                        (block as? ChapterBlock.Heading)?.id == overlayId ||
                            (block as? ChapterBlock.Paragraph)?.id == overlayId
                    }
                }?.key?.let { add(it) }
            }
        }
        val evicted = segmentOriginals.keys.filter { id ->
            val idx = chapterPositionIndex.indexOfChapter(id)
            (idx == null || idx < from || idx > to) &&
                id !in protectedIds &&
                id !in segmentFillsInFlight
        }
        if (evicted.isEmpty()) return
        for (id in evicted) {
            segmentOriginals.remove(id)
            segmentDisplay.remove(id)
            segmentTranslatedIds.remove(id)
            segmentsWithOwnTitle.remove(id)
        }
        publishDocumentState()
    }

    /**
     * v1.7 document engine (Item 4): the CHAPTER_BY_CHAPTER
     * boundary trigger — the surface calls this when the
     * CURRENT segment's last block becomes the last visible
     * item (last-visible tracking; NO fraction threshold
     * anywhere). The next segment's fill is requested through
     * the one fill door; its state item already stands at the
     * boundary in the document (LOADING while the fill runs,
     * FAILED with its retry if it ends without a copy), and
     * when it lands the scroll simply continues into it —
     * the QuickNovel pattern, with per-segment state carrying
     * everything the retired boundary indices used to.
     */
    fun onBoundaryEndVisible() {
        if (_config.value.mode != ReaderMode.SCROLL) return
        if (_config.value.chapterTransition !=
            ChapterTransition.CHAPTER_BY_CHAPTER
        ) {
            return
        }
        val stateNow = _state.value
        val next = stateNow.chapters.getOrNull(stateNow.chapterIndex + 1)
            ?: return
        requestSegmentFill(next.id)
    }

    /** Item 3, row 7: the FAILED row's retry door — an
     * explicit re-request of that segment's fill (the one
     * trigger allowed to clear a FAILED state). */
    fun retrySegment(chapterId: Long) {
        requestSegmentFill(chapterId, explicit = true)
    }

    /** Item 1: the MISSING row's download door — the same
     * fill, explicitly requested: a stored copy parses, no
     * copy fetches live through the download queue. */
    fun downloadSegmentChapter(chapterId: Long) {
        requestSegmentFill(chapterId, explicit = true)
    }

    /**
     * Item 1: THE FILL — the one door every trigger uses
     * (residency planning, the state item's composition
     * trigger, the approach door, the boundary trigger, the
     * MISSING row's download door, the FAILED row's explicit
     * retry, a translation invalidation, the narration
     * advance). One in-flight set covers BOTH roads, so any
     * number of triggers for one segment produce exactly one
     * fill:
     * — a segment already RESIDENT or already in flight is a
     *   no-op;
     * — a FAILED segment refills only on an [explicit]
     *   request (its retry door); the explicit request clears
     *   the failure first;
     * — a segment WITH a stored copy parses through
     *   [loadSegmentText] (the parse road, with the A7
     *   swap-race rule inside);
     * — a segment with NO copy fetches through the shared
     *   loader (ChapterTextLoader.ensureStored — the same
     *   fetch path the reader, downloads and AI use): Stored
     *   updates the chapter row and parses; Preparing keeps
     *   the segment LOADING and re-requests when the import
     *   lands (fetchChapterLive's discipline); a failure
     *   stands the segment FAILED with the fetch layer's
     *   honest reason from the diagnostic log.
     * Fills run as launched jobs — never on the composition
     * path (the MINIMAL-tier discipline: composition requests,
     * the worker fills).
     */
    private fun requestSegmentFill(chapterId: Long, explicit: Boolean = false) {
        if (chapterId in segmentOriginals) return
        if (chapterId in segmentFillsInFlight) return
        if (!explicit && chapterId in segmentFailedReasonsMap) return
        val chapter = _state.value.chapters
            .firstOrNull { it.id == chapterId } ?: return
        if (explicit) segmentFailedReasonsMap.remove(chapterId)
        segmentFillsInFlight += chapterId
        publishDocumentState()
        viewModelScope.launch {
            var refillAfterImport = false
            try {
                if (chapter.contentPath != null) {
                    loadSegmentText(chapter)
                } else {
                    when (val outcome = container.chapterTextLoader
                        .ensureStored(bookId, chapter)
                    ) {
                        is app.xreader.data.download.ChapterTextLoader
                            .Outcome.Stored -> {
                            val fresh = outcome.chapter
                            _state.value = _state.value.copy(
                                chapters = chaptersUpdated(
                                    _state.value.chapters.map {
                                        if (it.id == fresh.id) fresh else it
                                    }
                                )
                            )
                            loadSegmentText(fresh)
                        }
                        is app.xreader.data.download.ChapterTextLoader
                            .Outcome.Preparing -> {
                            // The segment stays LOADING; the
                            // fill re-requests itself when the
                            // import lands (below, after the
                            // in-flight mark is released).
                            refillAfterImport = true
                        }
                        is app.xreader.data.download.ChapterTextLoader
                            .Outcome.Failed,
                        is app.xreader.data.download.ChapterTextLoader
                            .Outcome.TimedOut -> {
                            segmentFailedReasonsMap[chapterId] =
                                app.xreader.komikku.ReaderErrorDetail.fromLog(
                                    app.xreader.data.extension.DiagnosticLog
                                        .entries(),
                                    chapterId
                                ) ?: if (outcome is
                                    app.xreader.data.download.ChapterTextLoader
                                        .Outcome.TimedOut
                                ) {
                                    "This chapter is taking a long time " +
                                        "to arrive — it may still land " +
                                        "in Downloads."
                                } else {
                                    "This chapter couldn't be fetched."
                                }
                        }
                    }
                }
            } finally {
                segmentFillsInFlight -= chapterId
                if (chapterId in segmentOriginals) {
                    onSegmentLanded(chapterId)
                } else {
                    publishDocumentState()
                }
                if (refillAfterImport) {
                    viewModelScope.launch {
                        runCatching {
                            app.xreader.data.importer.ImportCompletionState
                                .statusFlow(bookId)
                                .first { status ->
                                    val row = container.database
                                        .chapterDao().getById(chapterId)
                                    row?.contentPath != null ||
                                        status == null || !status.running
                                }
                        }
                        requestSegmentFill(chapterId)
                    }
                }
            }
        }
    }

    /** A fill landed: the segment is RESIDENT. Publish, then
     * run the landing hooks — a waiting narration advance
     * (B2) takes its landing; an anchor landing follows the
     * anchor onto the screen; a pending content swap on the
     * anchor re-asserts the place once, scoped to the anchor,
     * with the saved offset clamped to the new text (Item 3,
     * row 1). */
    private fun onSegmentLanded(chapterId: Long) {
        publishDocumentState()
        val index = chapterPositionIndex.indexOfChapter(chapterId)
            ?: return
        var narrated = false
        if (pendingNarrationChapterId == chapterId) {
            pendingNarrationChapterId = null
            val chapter = _state.value.chapters.getOrNull(index)
            val original = segmentOriginals[chapterId]
            if (chapter != null && original != null) {
                performNarrationLanding(
                    index = index,
                    chapter = chapter,
                    original = original,
                    useTranslation = chapterId in segmentTranslatedIds,
                    restoreOffset = 0,
                    restoreScrollFraction = 0f,
                    hint = PageHint.FIRST,
                    bumpGeneration = false
                )
                narrated = true
            }
        }
        if (index == _state.value.chapterIndex) {
            if (!narrated) followAnchorChapter(index)
            if (pendingSwapReassertIds.remove(chapterId)) {
                val display = segmentDisplay[chapterId]
                if (display != null) {
                    moveAnchor(
                        index,
                        _state.value.positionOffset
                            .coerceAtMost(display.length),
                        PageHint.OFFSET
                    )
                }
            }
        }
    }

    /**
     * Moon+ full copy: the document surface's position
     * report, DEMOTED to Moon+'s one-way derivation. In
     * Moon+ there is no onScrollChanged at all: scrolling
     * feeds only the within-chapter offset, derived
     * one-way (chapter, scroll) → saved offset, and the
     * derivation never runs scroll → chapter. So a
     * report here may ONLY price the CURRENT anchor: a
     * report naming any other chapter is refused outright
     * — the anchor is written by doors alone (see
     * [onChapterCrossed] for the one scroll-side door, and
     * the moveAnchor doors for the rest). The refusal is
     * structural, not a fence: there is no branch in this
     * function that can move the anchor, so no report
     * — stale, parked, or post-jump — can rename the
     * chapter at any time, in any mode. The resident
     * backstop stands as the second half of B1's contract:
     * a report for the anchor whose display is not held
     * prices nothing.
     */
    fun onDocumentPosition(chapterIndex: Int, charOffset: Int, chapterFraction: Float) {
        val stateNow = _state.value
        if (chapterIndex != stateNow.chapterIndex) {
            return
        }
        val chapter = stateNow.chapter ?: return
        if (segmentDisplay[chapter.id] == null) {
            return
        }
        reportedFractions[chapterIndex] = chapterFraction
        onPosition(charOffset, chapterFraction)
        // The anchor reporting its own place spends any
        // approach target aimed at another segment.
        if (approachTargetId != null && approachTargetId != chapter.id) {
            approachTargetId = null
        }
    }

    /**
     * Moon+ full copy: THE CROSSING DOOR — the
     * joint-stream analogue of Moon+'s
     * pageDownToNextChapter / pageUpToPriorChapter. In
     * Moon+ a boundary crossing is an EVENT (the armed
     * wheel released, a page turn past the edge): the
     * chapter changes once, from the door's parameters.
     * Here the surface fires this when the settled viewport
     * has verifiably crossed into a neighbouring segment
     * (the report handler re-reads the live layout before
     * firing), and this door is the ONLY scroll-side writer
     * of the anchor. It mirrors the retired report-branch
     * exactly: the visible chapter and its text publish
     * together; in CONTINUOUS a chapter left behind at
     * ≥90% read counts as finished (Item 4 — in
     * CHAPTER_BY_CHAPTER the finish is the surface's end
     * report instead); residency re-plans around the new
     * anchor. Resident-only (B1): a segment whose display
     * is not held can never become the anchor from here
     * — its fill is requested through the approach /
     * visible doors, and the crossing fires when it is
     * held and the viewport stands in it. The door touches
     * no restore field and no generation: the anchor move
     * IS the whole event, and the surface's restore stages
     * belong to deliberate jumps, not to crossings.
     */
    fun onChapterCrossed(targetIndex: Int) {
        val stateNow = _state.value
        val previousIndex = stateNow.chapterIndex
        if (targetIndex == previousIndex) {
            return
        }
        val chapter = stateNow.chapters.getOrNull(targetIndex)
        if (chapter == null) {
            return
        }
        val text = segmentDisplay[chapter.id]
        if (text == null) {
            return
        }
        val leftFraction = reportedFractions[previousIndex] ?: 0f
        visibleTextChapterIndex = targetIndex
        _state.value = stateNow.copy(
            chapterIndex = targetIndex,
            chapterText = text
        )
        if (targetIndex > previousIndex && leftFraction >= 0.9f &&
            _config.value.chapterTransition ==
            ChapterTransition.CONTINUOUS
        ) {
            markCurrentChapterFinishedFor(previousIndex, leftFraction)
        }
        planResidency(targetIndex)
    }

    /** Item 1: the composition trigger's other half — a
     * state item standing in the composed range asks for its
     * segment's fill at approach priority (the same door;
     * ahead of speculative run fills because it is called
     * directly, not queued behind the residency job). */
    fun onSegmentVisible(chapterId: Long) {
        requestSegmentFill(chapterId)
    }

    /**
     * Item 1: THE APPROACH DOOR. The surface calls this when
     * the first visible item belongs to a segment with no
     * text. It records the
     * approached segment as the eviction-protected target
     * and requests its fill at anchor priority — and writes
     * NOTHING else: no chapterIndex, no chapterText, no
     * position, no progress. The reader's place is exactly
     * where they left it; when the fill lands, the segment
     * simply has text.
     */
    fun onSegmentApproached(chapterId: Long) {
        approachTargetId = chapterId
        requestSegmentFill(chapterId)
    }

    /**
     * v1.7 OE-4 patch (Fix A): the EXP pace gate for one chapter
     * finish. The session's pace pool (active minutes × 500 wpm,
     * [app.xreader.domain.game.ExpAwards]) is spent in reading
     * order: chapters finished earlier in this session consumed
     * their words first ([completedSessionWords] — this chapter's
     * own words are added only after this check). The finish is
     * counted for EXP only when ≥80% of the chapter's words were
     * credited. The read flag and the word ledger are NOT gated —
     * a crossed chapter is still marked read; only the +25
     * finish credit is earned at reading pace.
     */
    private fun chapterFinishCountsForExp(
        chapterWords: Long,
        scrolledWordsInChapter: Long
    ): Boolean {
        val pool = tracker.activeMinutes().toLong() *
            app.xreader.domain.game.ExpAwards.WORDS_CREDITED_PER_MINUTE
        val consumedBefore = minOf(completedSessionWords, pool)
        val creditedForChapter = minOf(
            scrolledWordsInChapter,
            (pool - consumedBefore).coerceAtLeast(0L)
        )
        return app.xreader.domain.game.ExpAwards
            .chapterCountsAsFinished(chapterWords, creditedForChapter)
    }

    private fun markCurrentChapterFinishedFor(
        index: Int,
        leftFraction: Float = 1f
    ) {
        val chapter = _state.value.chapters.getOrNull(index) ?: return
        if (!finishedChapterIds.add(chapter.id)) return
        // v1.7 OE-4 patch (Fix A): the crossing already proves
        // ≥90% of the chapter was scrolled past — the gate asks
        // whether that scrolling happened at reading pace.
        if (chapterFinishCountsForExp(
                chapterWords = chapter.wordCount.toLong(),
                scrolledWordsInChapter = (
                    chapter.wordCount *
                        leftFraction.coerceIn(0f, 1f)
                    ).toLong()
            )
        ) {
            tracker.addChaptersFinished()
        }
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

    /**
     * Smoothness fix (§1): everything the chapter-change
     * persistence chain (the progress flush, the session
     * flush, the history start) reads from the OUTGOING
     * state, captured at the [moveAnchor] door BEFORE the
     * publish. The chain used to run in front of the publish
     * because its inputs were the outgoing chapter's live
     * state; captured, it runs behind the publish and reads
     * only this — the outgoing saved row is value-identical
     * to the old order's. The session ledger's own
     * accumulators (completedSessionWords, lastFlushedWords,
     * currentChapterMaxOffset, finishedChapterIds,
     * pendingFocusBonus, the tracker itself) are NOT
     * captured: the publish never mutates them, and the
     * session flush's resets must land on the live fields.
     */
    private data class OutgoingFlush(
        /** The progress flush's chapter arm: the outgoing row. */
        val chapter: Chapter?,
        val chapterIndex: Int,
        val positionOffset: Int,
        val bookFraction: Float,
        val chapterFraction: Float,
        /** The keep-previous guard's witness, at the door. */
        val visibleTextChapterIndex: Int,
        /** The session flush's word source: the outgoing text. */
        val chapterText: ChapterText?,
        val currentChapterId: Long?,
        /** The progress flush's PDF arm (null for chapter books). */
        val pdfPlace: PdfPlaceSnapshot?,
        val pdfBook: app.xreader.data.db.Book?
    )

    private fun captureOutgoingFlush(
        stateNow: ReaderUiState
    ): OutgoingFlush = OutgoingFlush(
        chapter = stateNow.chapter,
        chapterIndex = stateNow.chapterIndex,
        positionOffset = stateNow.positionOffset,
        bookFraction = stateNow.bookFraction,
        chapterFraction = stateNow.chapterFraction,
        visibleTextChapterIndex = visibleTextChapterIndex,
        chapterText = stateNow.chapterText,
        currentChapterId = stateNow.chapter?.id,
        pdfPlace = capturePdfPlace(),
        pdfBook = pdfBook
    )

    /** The PDF place, captured synchronously (onCleared's
     * shape): the session's page, in-page fraction, page
     * count and anchor title — null for a chapter book. */
    private fun capturePdfPlace(): PdfPlaceSnapshot? {
        val session = pdfSession ?: return null
        return PdfPlaceSnapshot(
            page = pdfPage,
            inPageFraction = pdfInPageFraction,
            pageCount = session.sectionCount,
            anchorTitle = session.anchorTitleFor(pdfPage)
        )
    }

    /** The guaranteed flush: cancel any coalesced write and
     * persist the CURRENT position immediately and exactly. */
    private suspend fun flushProgressNow() {
        saveJob?.cancel()
        pdfSaveJob?.cancel()
        saveProgressNow()
    }

    /** Smoothness fix (§1): the same guaranteed flush,
     * consuming the door's capture of the OUTGOING state —
     * [moveAnchor]'s post-publish chain. */
    private suspend fun flushProgressNow(outgoing: OutgoingFlush) {
        saveJob?.cancel()
        pdfSaveJob?.cancel()
        saveProgressNow(outgoing)
    }

    private suspend fun saveProgressNow() {
        saveProgressNow(captureOutgoingFlush(_state.value))
    }

    private suspend fun saveProgressNow(outgoing: OutgoingFlush) {
        // v1.7 OE-1: a PDF's progress is page-shaped and
        // has its own writer; the chapter writer below
        // never runs for one.
        if (outgoing.pdfPlace != null) {
            savePdfProgressNow(outgoing.pdfPlace, outgoing.pdfBook)
            return
        }
        val chapter = outgoing.chapter ?: return
        // Keep-previous guard: while a chapter load is in flight
        // the visible text (and the position describing it) still
        // belongs to the outgoing chapter — never write it against
        // the incoming chapter's row. The incoming chapter's own
        // onPosition resumes saves the moment its text lands.
        if (outgoing.visibleTextChapterIndex != outgoing.chapterIndex) {
            return
        }
        val now = System.currentTimeMillis()
        // v1.7 Patch 2 (item 1b): the stored fraction never moves
        // backwards for an at-or-ahead position (BookProgressClamp
        // — estimate refinement under the char-based % can tick a
        // recomputed fraction down; the stored row is the guard).
        val storedRow = runCatching {
            container.database.readingProgressDao().getForBook(bookId)
        }.getOrNull()
        val writeFraction = BookProgressClamp.clamp(
            computed = outgoing.bookFraction,
            newChapterIndex = outgoing.chapterIndex,
            newCharOffset = outgoing.positionOffset,
            storedChapterIndex = storedRow?.chapterIndex,
            storedCharOffset = storedRow?.charOffset,
            storedFraction = storedRow?.fraction
        )
        container.database.readingProgressDao().upsert(
            ReadingProgress(
                bookId = bookId,
                chapterId = chapter.id,
                chapterIndex = outgoing.chapterIndex,
                charOffset = outgoing.positionOffset,
                fraction = writeFraction,
                scrollFraction = outgoing.chapterFraction,
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

    // ── v1.7 OE-1: the PDF branch ────────────────────────

    /**
     * Opens a local in-place PDF in this reader (R1, R3):
     * the session through the engine door first, then
     * the MR-2 legacy cleanup (R31 — it rewrites the
     * saved place to page form, so the place read below
     * is already the reader's own), then page-shaped
     * state. A PDF opens in Pages mode, every time (R6).
     * The locked-PDF password path rides the same door
     * (K5): a password-required open publishes
     * [PdfReaderState.PasswordRequired] and
     * [retryPdfWithPassword] reopens with the candidate.
     */
    private fun openPdfBook(
        book: app.xreader.data.db.Book,
        initialChapterIndex: Int
    ) {
        pdfBook = book
        _pdfReader.value = PdfReaderState.Loading
        viewModelScope.launch(container.ioDispatcher) {
            val opened = try {
                BookEngine.open(
                    context = container.applicationContext,
                    book = book,
                    filesRoot = container.bookStorage.filesDir,
                    cacheDir = container.applicationContext.cacheDir,
                    password = pdfPassword
                ) as PdfSession
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: LocalPasswordRequiredException) {
                _pdfReader.value = PdfReaderState.PasswordRequired
                return@launch
            } catch (e: LocalFileMissingException) {
                _pdfReader.value = PdfReaderState.Failed(
                    if (e.message == "file not found") {
                        "File not found — the file isn't where it " +
                            "was. Your place and bookmarks are kept."
                    } else {
                        e.message ?: "That book couldn't be opened."
                    }
                )
                return@launch
            } catch (e: Exception) {
                _pdfReader.value = PdfReaderState.Failed(
                    "That book couldn't be opened. " +
                        "(${e.javaClass.simpleName})"
                )
                return@launch
            }
            pdfSession?.close()
            pdfSession = opened
            runCatching { container.recordHistoryStart(bookId) }
            tracker.begin()
            // R31: the one-time MR-2 cleanup, before the
            // place is read — it must see the conversion
            // era's rows exactly as they stand.
            app.xreader.data.prepare.PdfLegacyCleanup.runIfNeeded(
                book = book,
                pageCount = opened.sectionCount,
                chapterDao = container.database.chapterDao(),
                readingProgressDao =
                    container.database.readingProgressDao(),
                bookmarkDao = container.database.bookmarkDao(),
                storage = container.bookStorage,
                stripDir = java.io.File(
                    container.applicationContext.cacheDir,
                    "local-pdf/$bookId"
                ),
                settingsStore =
                    container.applicationContext.settingsDataStore
            )
            val settings = runCatching {
                localReaderPrefs.pdfSettings(bookId).first()
            }.getOrDefault(PdfDocSettings())
            val saved = runCatching {
                container.database.readingProgressDao().getForBook(bookId)
            }.getOrNull()
            val savedLocator = LocatorJson.parsePdfLocator(saved?.locatorJson)
            // A deliberate door (a Contents page tap rides
            // the route's chapter index) outranks the saved
            // place; an ordinary open resumes it.
            val startPage = when {
                saved == null -> initialChapterIndex
                initialChapterIndex != saved.chapterIndex ->
                    initialChapterIndex
                else -> savedLocator?.first ?: saved.chapterIndex
            }.coerceIn(0, (opened.sectionCount - 1).coerceAtLeast(0))
            pdfPage = startPage
            pdfInPageFraction = savedLocator?.second
                ?: saved?.scrollFraction ?: 0f
            _pdfReader.value = PdfReaderState.Ready(
                session = opened,
                initialPage = startPage,
                page = startPage,
                pageCount = opened.sectionCount,
                mode = PdfReadMode.PAGES,
                settings = settings,
                stripState = opened.stripState.value,
                textAvailable = null,
                anchorTitle = opened.anchorTitleFor(startPage),
                bookFraction = if (opened.sectionCount > 0) {
                    (startPage + pdfInPageFraction) / opened.sectionCount
                } else {
                    0f
                },
                reflowContent = null,
                reflowPreparing = false,
                autoResolvedFilter = PdfPageFilter.NONE
            )
            _state.value = _state.value.copy(
                bookTitle = book.title,
                loading = false
            )
            // The strip's landing drives Reflow availability
            // (R22, R32) and, while Reflow is up, its text.
            viewModelScope.launch {
                opened.stripState.collect { strip ->
                    onPdfStripState(strip)
                }
            }
            // R20's two forms, each from its own store.
            viewModelScope.launch {
                SelectionHighlightStore(
                    container.applicationContext.settingsDataStore
                ).highlights(bookId).collect { rows ->
                    _pdfPageHighlights.value = rows
                }
            }
            viewModelScope.launch {
                container.applicationContext.settingsDataStore.data
                    .map { prefs ->
                        app.xreader.ui.localreader
                            .decodeSelectionHighlights(
                                prefs[pdfReflowHighlightsKey(bookId)]
                            )
                    }
                    .collect { rows -> _pdfReflowHighlights.value = rows }
            }
        }
    }

    /** K5: the password prompt's retry — the candidate is
     * used for this session only and never saved (the
     * shipped LocalDoc rule). */
    fun retryPdfWithPassword(candidate: String) {
        val book = pdfBook ?: return
        pdfPassword = candidate.takeIf { it.isNotEmpty() }
        openPdfBook(book, pdfPage)
    }

    private fun onPdfStripState(strip: PdfStripState) {
        val ready = _pdfReader.value as? PdfReaderState.Ready ?: return
        val available = when (strip) {
            is PdfStripState.Pending -> null
            is PdfStripState.Unavailable -> false
            is PdfStripState.Ready ->
                app.xreader.data.prepare.PdfReflowText
                    .hasUsableText(strip.result.texts)
        }
        if (strip is PdfStripState.Ready) {
            // A fresh strip invalidates the cleaned cache;
            // it is recomputed lazily on next need.
            pdfCleanedTexts = null
        }
        _pdfReader.value = ready.copy(
            stripState = strip,
            textAvailable = available
        )
        if (ready.mode == PdfReadMode.REFLOW) {
            preparePdfReflowPage(pdfPage)
        }
    }

    /** The strip's cleaned page texts (R9), computed once
     * per landing on a background dispatcher; suspends
     * until the strip settles, null when it never lands. */
    private suspend fun pdfCleanedTexts(): List<String>? {
        pdfCleanedTexts?.let { return it }
        val session = pdfSession ?: return null
        val strip = session.stripState.first {
            it is PdfStripState.Ready || it is PdfStripState.Unavailable
        }
        val result = (strip as? PdfStripState.Ready)?.result
            ?: return null
        val cleaned = kotlinx.coroutines.withContext(
            kotlinx.coroutines.Dispatchers.Default
        ) {
            app.xreader.data.prepare.PdfReflowText
                .cleanedPageTexts(result.texts)
        }
        pdfCleanedTexts = cleaned
        return cleaned
    }

    /** R13/R14: the mode toggle — one page variable, no
     * finer place carried in either direction. Entering
     * Reflow shows the current page's text from its top;
     * leaving returns to the page Reflow had reached. */
    fun setPdfMode(reflow: Boolean) {
        val ready = _pdfReader.value as? PdfReaderState.Ready ?: return
        val target = if (reflow) PdfReadMode.REFLOW else PdfReadMode.PAGES
        if (target == ready.mode) return
        // R22: a book with no text layer has no Reflow to
        // enter (the switch itself carries the line).
        if (reflow && ready.textAvailable == false) return
        // MR-2b (Revision 1, Part F): a mode switch clears
        // the search mark in BOTH directions — a mark's char
        // offsets belong to the representation that was
        // searched (Reflow's cleaned text ≠ Pages' strip
        // text), so a carried mark could draw on the wrong
        // words in the other mode.
        _pdfSearchMark.value = null
        _pdfReader.value = if (target == PdfReadMode.PAGES) {
            ready.copy(
                mode = target,
                reflowContent = null,
                reflowPreparing = false
            )
        } else {
            ready.copy(mode = target)
        }
        if (target == PdfReadMode.REFLOW) {
            preparePdfReflowPage(pdfPage)
        }
    }

    /** Prepares the current page's Reflow content (R9):
     * strip page text → A5 cleanup → PdfReflow blocks. */
    private fun preparePdfReflowPage(page: Int) {
        val ready = _pdfReader.value as? PdfReaderState.Ready ?: return
        if (ready.mode != PdfReadMode.REFLOW) return
        _pdfReader.value = ready.copy(reflowPreparing = true)
        viewModelScope.launch {
            val cleaned = pdfCleanedTexts()
            val current =
                _pdfReader.value as? PdfReaderState.Ready
                    ?: return@launch
            if (current.mode != PdfReadMode.REFLOW ||
                pdfPage != page
            ) {
                return@launch
            }
            val content = cleaned?.getOrNull(page)?.let { text ->
                app.xreader.data.prepare.PdfReflowText
                    .pageContent(text)
            }
            _pdfReader.value = current.copy(
                reflowContent = content,
                reflowPreparing = false
            )
        }
    }

    /** The surfaces' position report (the shipped
     * LocalDoc onPdfPosition, re-homed): the one page
     * variable moves, the status follows, the save is
     * debounced exactly as the local surface's was. */
    fun onPdfPosition(page: Int, inPageFraction: Float) {
        // v1.7 OE-4 patch (Fix B): teardown-time reports (the
        // surface leaving composition during the exit transition)
        // must never move the saved place past the page the user
        // last saw — see pdfClosing.
        if (pdfClosing) return
        applyPdfPosition(page, inPageFraction)
    }

    private fun applyPdfPosition(page: Int, inPageFraction: Float) {
        val session = pdfSession ?: return
        val ready = _pdfReader.value as? PdfReaderState.Ready ?: return
        val clamped = page.coerceIn(
            0, (session.sectionCount - 1).coerceAtLeast(0)
        )
        pdfPage = clamped
        pdfInPageFraction = inPageFraction
        _pdfReader.value = ready.copy(
            page = clamped,
            anchorTitle = session.anchorTitleFor(clamped),
            bookFraction = if (session.sectionCount > 0) {
                ((clamped + inPageFraction) / session.sectionCount)
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
        )
        schedulePdfProgressSave()
    }

    /** Every explicit page door (Contents, bookmarks,
     * search, chrome turns, filmstrip) lands here: Pages
     * bodies consume the jump; Reflow swaps its text. */
    fun pdfGoToPage(page: Int) {
        val session = pdfSession ?: return
        val ready = _pdfReader.value as? PdfReaderState.Ready ?: return
        val target = page.coerceIn(
            0, (session.sectionCount - 1).coerceAtLeast(0)
        )
        if (ready.mode == PdfReadMode.PAGES) {
            _pdfJumps.tryEmit(target)
        }
        // An explicit door is a deliberate jump, not a surface
        // report — it applies even while pdfClosing is set.
        applyPdfPosition(target, 0f)
        if (ready.mode == PdfReadMode.REFLOW) {
            preparePdfReflowPage(target)
        }
    }

    /**
     * v1.7 OE-4: a page turn inside the local CBZ/CBR page
     * world. The place is kept as a page number, written
     * through the ordinary progress saver in the manga
     * reader's own row shape — scrollFraction = page/(pages−1),
     * charOffset 0 — so a place saved here is the place
     * Komikku resumes from, and the other way round.
     */
    fun onCbzPage(page: Int) {
        val stateNow = _state.value
        val count = stateNow.cbzPages.size
        if (count == 0) return
        val clamped = page.coerceIn(0, count - 1)
        val chapterFraction = if (count > 1) {
            clamped.toFloat() / (count - 1)
        } else {
            1f
        }
        val bookFraction = if (stateNow.chapters.isNotEmpty()) {
            (stateNow.chapterIndex + chapterFraction) /
                stateNow.chapters.size
        } else {
            chapterFraction
        }
        _state.value = stateNow.copy(
            cbzPage = clamped,
            chapterFraction = chapterFraction,
            bookFraction = bookFraction
        )
        tracker.onActivity()
        // The manga reader's rule, mirrored: reaching the last
        // page finishes the chapter (the row is marked read).
        if (clamped == count - 1) markCurrentChapterFinished()
        scheduleProgressSave()
    }

    /** Every explicit CBZ page door (chrome turns, the seek
     * slider) lands here: the surface consumes the jump; the
     * place applies at once. */
    fun cbzGoToPage(page: Int) {
        val count = _state.value.cbzPages.size
        if (count == 0) return
        val target = page.coerceIn(0, count - 1)
        _cbzJumps.tryEmit(target)
        onCbzPage(target)
    }

    /** A PDF settings change (R21): state first, then
     * persisted for this book — the shipped debounced
     * shape (zoom rides this path on every pinch move). */
    fun updatePdfSettings(transform: (PdfDocSettings) -> PdfDocSettings) {
        val ready = _pdfReader.value as? PdfReaderState.Ready ?: return
        val next = transform(ready.settings)
        _pdfReader.value = ready.copy(settings = next)
        pdfSettingsSaveJob?.cancel()
        pdfSettingsSaveJob = viewModelScope.launch(container.ioDispatcher) {
            delay(500)
            runCatching { localReaderPrefs.savePdf(bookId, next) }
        }
    }

    /** AUTO contrast's per-page resolution, reported by
     * the page cells (the settings section's night
     * toggle reads it). */
    fun onPdfResolvedFilter(page: Int, filter: PdfPageFilter) {
        if (page != pdfPage) return
        val ready = _pdfReader.value as? PdfReaderState.Ready ?: return
        if (ready.autoResolvedFilter == filter) return
        _pdfReader.value = ready.copy(autoResolvedFilter = filter)
    }

    // ── PDF bookmarks (R19: page-anchored, one list) ─────

    /** The page a bookmark names in a PDF: its locator's
     * page when it carries one, else its stored index
     * (a pre-locator row's honest reading). */
    fun pdfBookmarkPage(bookmark: Bookmark): Int =
        LocatorJson.parsePdfLocator(bookmark.locatorJson)?.first
            ?: bookmark.chapterIndex

    fun pdfBookmarkedHere(bookmarks: List<Bookmark>): Boolean =
        bookmarks.any { pdfBookmarkPage(it) == pdfPage }

    fun togglePdfBookmark() {
        val session = pdfSession ?: return
        val existing = _bookmarks.value.firstOrNull {
            pdfBookmarkPage(it) == pdfPage
        }
        if (existing != null) {
            deleteBookmark(existing)
            return
        }
        val now = System.currentTimeMillis()
        viewModelScope.launch(container.ioDispatcher) {
            container.database.bookmarkDao().insert(
                Bookmark(
                    bookId = bookId,
                    chapterId = 0,
                    chapterIndex = pdfPage,
                    charOffset = 0,
                    label = session.anchorTitleFor(pdfPage)
                        ?: "Page ${pdfPage + 1}",
                    createdAt = now,
                    updatedAt = now,
                    locatorJson = LocatorJson.pdfLocator(
                        pdfPage, pdfInPageFraction
                    )
                )
            )
        }
    }

    // ── PDF highlights, Reflow form (R20) ────────────────

    private fun pdfReflowHighlightsKey(id: Long) =
        androidx.datastore.preferences.core.stringPreferencesKey(
            "localreader.$id.pdfreflow.highlights_json"
        )

    fun addPdfReflowHighlight(highlight: StoredSelectionHighlight) {
        val next = _pdfReflowHighlights.value + highlight
        _pdfReflowHighlights.value = next
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                container.applicationContext.settingsDataStore.edit {
                    prefs ->
                    prefs[pdfReflowHighlightsKey(bookId)] =
                        app.xreader.ui.localreader
                            .encodeSelectionHighlights(next)
                }
            }
        }
    }

    fun removePdfReflowHighlight(highlight: StoredSelectionHighlight) {
        val next = _pdfReflowHighlights.value
            .filterNot { it.id == highlight.id }
        _pdfReflowHighlights.value = next
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                container.applicationContext.settingsDataStore.edit {
                    prefs ->
                    prefs[pdfReflowHighlightsKey(bookId)] =
                        app.xreader.ui.localreader
                            .encodeSelectionHighlights(next)
                }
            }
        }
    }

    // ── PDF highlights, Pages form (R20 / MR-2b) ─────────

    /** MR-2b (Revision 1, D2): ALL Pages-form writes go
     * through the selection kit's store — the same
     * [app.xreader.ui.localreader.SelectionHighlightStore]
     * (and the same per-book key) the EPUB road and this
     * book's Pages collector above read. No hand-rolled
     * DataStore edits for this form: one writer shape, so
     * the forms can never drift apart in encoding. The
     * _pdfPageHighlights flow republishes on its own. */
    private fun pdfPageHighlightStore() =
        app.xreader.ui.localreader.SelectionHighlightStore(
            container.applicationContext.settingsDataStore
        )

    fun addPdfPageHighlight(
        highlight: app.xreader.ui.localreader.StoredSelectionHighlight
    ) {
        viewModelScope.launch(container.ioDispatcher) {
            runCatching { pdfPageHighlightStore().add(bookId, highlight) }
        }
    }

    fun removePdfPageHighlight(
        highlight: app.xreader.ui.localreader.StoredSelectionHighlight
    ) {
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                pdfPageHighlightStore().remove(bookId, highlight.id)
            }
        }
    }

    fun updatePdfPageHighlightNote(
        highlight: app.xreader.ui.localreader.StoredSelectionHighlight,
        note: String
    ) {
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                pdfPageHighlightStore()
                    .updateNote(bookId, highlight.id, note)
            }
        }
    }

    /** SelectionBar (§7.3): the colour row's re-tint of an
     * existing Pages highlight — the mirror of
     * [updatePdfPageHighlightNote] over the same store. */
    fun updatePdfPageHighlightTint(
        highlight: app.xreader.ui.localreader.StoredSelectionHighlight,
        tintArgb: Int
    ) {
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                pdfPageHighlightStore()
                    .updateTint(bookId, highlight.id, tintArgb)
            }
        }
    }

    // ── PDF Listen (R17, OQ5: page-as-unit) ──────────────

    private fun togglePdfListen() {
        val session = pdfSession ?: return
        if (readAloud.state.value.playing) {
            readAloud.pause()
            return
        }
        ttsPdfActive = true
        pdfSpeechFor(session).startAt(pdfPage)
    }

    private fun pdfSpeechFor(session: PdfSession): PdfStripSpeech =
        pdfSpeech ?: PdfStripSpeech(
            textAt = { page -> pdfListenText(page) },
            pageCount = session.sectionCount,
            sink = object : SpeechSink {
                override fun loadChapter(text: ChapterText) =
                    readAloud.loadChapter(text)
                override fun play() = readAloud.play()
            },
            scope = viewModelScope
        ).also { pdfSpeech = it }

    /** The text Listen speaks for [page]: in Reflow, the
     * page's Reflow text (R17 — and page changes during
     * speech requeue through the feeder, so the new
     * page's text follows in the mode in force); in
     * Pages, the page's strip text, as the shipped
     * surface spoke it. */
    private suspend fun pdfListenText(page: Int): String {
        val session = pdfSession ?: return ""
        val ready = _pdfReader.value as? PdfReaderState.Ready
        return if (ready?.mode == PdfReadMode.REFLOW) {
            val cleaned = pdfCleanedTexts() ?: return ""
            cleaned.getOrNull(page)?.let { text ->
                app.xreader.data.prepare.PdfReflowText.pageText(text)
            } ?: ""
        } else {
            session.textAt(page)
        }
    }

    // ── PDF search (R18) ─────────────────────────────────

    /** The PDF sweep: page by page through the searched
     * representation — in Reflow, each page's Reflow text
     * prepared as the sweep reaches it (OQ6); in Pages,
     * the page's strip text. */
    private fun searchPdfBook(needle: String) {
        val session = pdfSession ?: return
        pdfSearchJob?.cancel()
        _pdfSearchMark.value = null
        _pdfSearchBusy.value = true
        pdfSearchJob = viewModelScope.launch(container.ioDispatcher) {
            val hits = ArrayList<PdfSearchHit>()
            try {
                val reflow = (_pdfReader.value as? PdfReaderState.Ready)
                    ?.mode == PdfReadMode.REFLOW
                val cleaned = if (reflow) pdfCleanedTexts() else null
                for (page in 0 until session.sectionCount) {
                    if (hits.size >= SEARCH_HIT_CAP) break
                    val text = if (reflow) {
                        cleaned?.getOrNull(page)?.let { pageText ->
                            app.xreader.data.prepare.PdfReflowText
                                .pageText(pageText)
                        } ?: ""
                    } else {
                        runCatching { session.textAt(page) }
                            .getOrDefault("")
                    }
                    if (text.isBlank()) continue
                    var from = 0
                    while (hits.size < SEARCH_HIT_CAP) {
                        val found = text.indexOf(
                            needle, from, ignoreCase = true
                        )
                        if (found < 0) break
                        val start = (found - 40).coerceAtLeast(0)
                        val end = (found + needle.length + 60)
                            .coerceAtMost(text.length)
                        hits += PdfSearchHit(
                            page = page,
                            charOffset = found,
                            snippet = (if (start > 0) "…" else "") +
                                text.substring(start, end)
                                    .replace('\n', ' ') +
                                (if (end < text.length) "…" else ""),
                            query = needle,
                            reflow = reflow
                        )
                        from = found + needle.length
                    }
                }
                _pdfSearchHits.value = hits
            } finally {
                _pdfSearchBusy.value = false
            }
        }
    }

    /** A hit tap: the page is shown; in Reflow its text
     * carries the hit's mark (R18). */
    fun goToPdfSearchHit(hit: PdfSearchHit) {
        _pdfSearchMark.value = PdfSearchMark(
            page = hit.page,
            start = hit.charOffset,
            end = hit.charOffset + hit.query.length
        )
        pdfGoToPage(hit.page)
    }

    // ── PDF progress (page form, R15) ────────────────────

    private fun schedulePdfProgressSave() {
        pdfSaveJob?.cancel()
        pdfSaveJob = viewModelScope.launch {
            delay(800)
            savePdfProgressNow()
        }
    }

    /** The page-form progress write — the shipped
     * LocalDoc saver's row shape, re-homed: chapterId 0,
     * the page as the index, the pdfLocator carrying the
     * in-page fraction. */
    private suspend fun savePdfProgressNow() {
        val place = capturePdfPlace() ?: return
        savePdfProgressNow(place, pdfBook)
    }

    /** The same write from a captured place (smoothness
     * fix §1: [moveAnchor]'s post-publish chain carries the
     * door's capture, never a post-publish read). */
    private suspend fun savePdfProgressNow(
        place: PdfPlaceSnapshot,
        book: app.xreader.data.db.Book?
    ) {
        if (book == null) return
        val count = place.pageCount.coerceAtLeast(1)
        container.database.readingProgressDao().upsert(
            ReadingProgress(
                bookId = bookId,
                chapterId = 0,
                chapterIndex = place.page,
                charOffset = 0,
                fraction = ((place.page + place.inPageFraction) / count)
                    .coerceIn(0f, 1f),
                scrollFraction = place.inPageFraction,
                updatedAt = System.currentTimeMillis(),
                locatorJson = LocatorJson.pdfLocator(
                    place.page, place.inPageFraction
                ),
                anchorText = place.anchorTitle
            )
        )
        runCatching {
            container.database.bookDao().update(
                book.copy(lastOpenedAt = System.currentTimeMillis())
            )
        }
    }

    /** Scroll-mode end-of-chapter detection + explicit paged turns. */
    fun markCurrentChapterFinished() {
        val chapter = _state.value.chapter ?: return
        if (!finishedChapterIds.add(chapter.id)) return
        // v1.7 OE-4 patch (Fix A): same pace gate as the crossing
        // door — the chapter's scrolled words are the ones up to
        // the furthest offset reached (flushSession's own count).
        val finishText = _state.value.chapterText
        val scrolledInChapter = if (finishText != null) {
            ChapterContent.countWords(
                finishText.text.substring(
                    0,
                    currentChapterMaxOffset.coerceAtMost(finishText.length)
                )
            ).toLong()
        } else {
            chapter.wordCount.toLong()
        }
        if (chapterFinishCountsForExp(
                chapterWords = chapter.wordCount.toLong(),
                scrolledWordsInChapter = scrolledInChapter
            )
        ) {
            tracker.addChaptersFinished()
        }
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
        flushSession(captureOutgoingFlush(_state.value))
    }

    /** Smoothness fix (§1): the session flush consuming the
     * door's capture — [moveAnchor]'s post-publish chain
     * must price the OUTGOING chapter's text, not the text
     * the publish just seated. The ledger's accumulators
     * stay live (see [OutgoingFlush]). */
    private suspend fun flushSession(outgoing: OutgoingFlush) {
        if (!tracker.isRunning) return
        val text = outgoing.chapterText
        // A chapter already marked finished contributed its full word
        // count to completedSessionWords — never count it twice.
        val currentAlreadyCounted =
            outgoing.currentChapterId?.let { it in finishedChapterIds } ?: false
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
        // v1.7 OE-4 patch (Fix B): closing starts here — freeze
        // PDF position intake BEFORE the flush, so the place this
        // flush (and the later clear-time writer) persists is the
        // last page the user actually saw.
        pdfClosing = true
        viewModelScope.launch {
            flushProgressNow()
            tracker.pause()
        }
    }

    fun onReaderResumed() {
        pdfClosing = false
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
        // v1.7 OE-1: a PDF's final place, captured the same
        // synchronous way (page + in-page fraction + the
        // session's page count and anchor for the row).
        val pdfPlaceAtClear = pdfSession?.let { session ->
            PdfPlaceSnapshot(
                page = pdfPage,
                inPageFraction = pdfInPageFraction,
                pageCount = session.sectionCount,
                anchorTitle = session.anchorTitleFor(pdfPage)
            )
        }
        val snapshot = if (tracker.isRunning) tracker.end() else null
        if (snapshot != null || chapterAtClear != null ||
            pdfPlaceAtClear != null
        ) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                if (pdfPlaceAtClear != null) {
                    runCatching {
                        val count = pdfPlaceAtClear.pageCount
                            .coerceAtLeast(1)
                        container.database.readingProgressDao().upsert(
                            ReadingProgress(
                                bookId = bookId,
                                chapterId = 0,
                                chapterIndex = pdfPlaceAtClear.page,
                                charOffset = 0,
                                fraction = (
                                    (pdfPlaceAtClear.page +
                                        pdfPlaceAtClear.inPageFraction) /
                                        count
                                    ).coerceIn(0f, 1f),
                                scrollFraction =
                                    pdfPlaceAtClear.inPageFraction,
                                updatedAt = System.currentTimeMillis(),
                                locatorJson = LocatorJson.pdfLocator(
                                    pdfPlaceAtClear.page,
                                    pdfPlaceAtClear.inPageFraction
                                ),
                                anchorText = pdfPlaceAtClear.anchorTitle
                            )
                        )
                    }
                }
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
        // v1.7 OE-1: the PDF session is this VM's to close
        // (the LocalDoc VM closed its own the same way).
        pdfSpeech?.stop()
        runCatching { pdfSession?.close() }
        pdfSession = null
        super.onCleared()
    }

    // -- Read-aloud controls ---------------------------------------------------------

    fun listenPlayPause() {
        // v1.7 OE-1 (R17): a PDF listens page by page
        // through the strip feeder, not the chapter
        // controller path below.
        if (pdfSession != null) {
            togglePdfListen()
            return
        }
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
                    ?.let { chapterCache[it] ?: segmentOriginals[it] }
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
     * cached original and reload if that chapter is on screen.
     * v1.7 document engine (Item 3, row 1): the reload is a
     * segment CONTENT SWAP — the segment store's entry is
     * invalidated (the Fix-1 evictions, subsumed: the store's
     * invalidation rule IS that eviction, generalised), the
     * segment returns to LOADING, and its fill re-reads under
     * the new preference; PAGED keeps the shipped loadChapter
     * road for the on-screen chapter. */
    fun onTranslationReady(chapterId: Long) {
        originalPreferred.remove(chapterId)
        invalidateSegmentForSwap(chapterId)
        val stateNow = _state.value
        if (stateNow.chapter?.id == chapterId &&
            _config.value.mode == ReaderMode.PAGED
        ) {
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
        swapCurrentChapterRoad()
    }

    fun showTranslatedChapter() {
        val chapter = _state.value.chapter ?: return
        originalPreferred.remove(chapter.id)
        swapCurrentChapterRoad()
    }

    /** Item 3, row 1: the current chapter's translation
     * toggle — in paged, the shipped reload; in scroll, the
     * segment swap: invalidate the anchor segment and refill
     * it, arming the scoped re-assert so the landing restores
     * the place against the new text. */
    private fun swapCurrentChapterRoad() {
        val stateNow = _state.value
        val chapter = stateNow.chapter ?: return
        if (_config.value.mode == ReaderMode.SCROLL) {
            invalidateSegmentForSwap(chapter.id)
        } else {
            chapterCache.remove(chapter.id)
            segmentOriginals.remove(chapter.id)
            segmentDisplay.remove(chapter.id)
            segmentTranslatedIds.remove(chapter.id)
            launchLoad {
                loadChapter(
                    stateNow.chapterIndex,
                    stateNow.positionOffset,
                    stateNow.chapterFraction
                )
            }
        }
    }

    /**
     * Item 3, row 1: a segment's content swap (a translation
     * arriving or a road toggle). The store entry is dropped
     * — original, display and failure alike — so the segment
     * returns to LOADING; chapterCache is evicted beside it
     * (the Fix-1 discipline, kept). If the segment is
     * resident-or-anchor in scroll, its fill is re-requested
     * at once; if it is the anchor, the scoped re-assert is
     * armed so the landing restores the reader's place
     * against the new text and no other segment's place
     * moves. The A7 race rule inside the parse covers a swap
     * landing while a fill is already in flight.
     */
    private fun invalidateSegmentForSwap(chapterId: Long) {
        chapterCache.remove(chapterId)
        val wasHeld = segmentOriginals.remove(chapterId) != null
        segmentDisplay.remove(chapterId)
        segmentTranslatedIds.remove(chapterId)
        segmentFailedReasonsMap.remove(chapterId)
        publishDocumentState()
        if (_config.value.mode != ReaderMode.SCROLL) return
        val stateNow = _state.value
        val index = chapterPositionIndex.indexOfChapter(chapterId)
            ?: return
        val isAnchor = index == stateNow.chapterIndex
        if (isAnchor || wasHeld || index in residentRunIndices) {
            if (isAnchor) pendingSwapReassertIds += chapterId
            requestSegmentFill(chapterId)
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
            // v1.7 document engine: any RESIDENT segment's
            // display, by its chapter's id.
            stateNow.chapters.getOrNull(chapterIndex)
                ?.let { stateNow.segmentTexts[it.id] }
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
                ?: _state.value.segmentTexts.values
                    .firstOrNull()?.text
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
        // v1.7 OE-1: volume-key turns page a PDF.
        if (pdfSession != null) {
            pdfGoToPage(pdfPage + direction)
            return
        }
        // v1.7 OE-4: volume keys turn pages in the CBZ page
        // world too.
        val cbzState = _state.value
        if (cbzState.cbzPages.isNotEmpty()) {
            cbzGoToPage(cbzState.cbzPage + direction)
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
