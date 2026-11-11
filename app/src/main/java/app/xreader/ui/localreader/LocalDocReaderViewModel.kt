package app.xreader.ui.localreader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Bookmark
import app.xreader.data.db.ReadingProgress
import app.xreader.data.engine.BookEngine
import app.xreader.data.engine.DocSession
import app.xreader.data.engine.LocalFileMissingException
import app.xreader.data.engine.LocalPasswordRequiredException
import app.xreader.data.engine.LocatorJson
import app.xreader.data.engine.OutlineEntry
import app.xreader.data.engine.PdfSession
import app.xreader.data.engine.ReadiumEpubSession
import app.xreader.data.prefs.settingsDataStore
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import app.xreader.data.reader.AndroidTtsEngine
import app.xreader.data.reader.ReadAloudController
import app.xreader.data.reader.TtsEngine
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/**
 * v1.5.6 (§3.1 row 9 / §3.3): the local-document reader's state.
 * ONE open behind ONE loading state — the old "Preparing chapter…"
 * state machine does not exist on this path.
 */
/** v1.5.7 (Scope C): live EPUB position for the book reader's
 * bottom bar — percent + chapter, never a page count.
 * v1.5.10 (F4/F2 seams): [withinChapter] is the status line's
 * (position-in-chapter / pages-in-chapter) pair, derived in
 * the ViewModel from the navigator's own positions (see
 * [LocalDocReaderViewModel.onEpubLocator]); null while
 * positions are unavailable, in which case the status line
 * renders its pre-F4 shape. [inSectionFraction] feeds the
 * Book information sheet's chapter-remaining estimate. */
data class EpubProgress(
    val fraction: Float,
    val chapterTitle: String?,
    val sectionIndex: Int,
    val sectionCount: Int,
    val withinChapter: Pair<Int, Int>? = null,
    val inSectionFraction: Float = 0f
)

/** v1.5.7 (Scope C): live PDF position for the document reader's
 * bottom bar — a real file page out of a real total. */
data class PdfProgress(
    /** 0-based current file page. */
    val page: Int,
    val pageCount: Int
)

sealed interface LocalReaderState {
    /** v1.5.8 (F4): the loading state NAMES ITS PHASE — a long
     * open and a hung open must never look the same. (The PDF
     * strip no longer runs inside this state at all; it lands in
     * the background after Ready.) */
    data class Loading(val phase: String = "Opening the book…") : LocalReaderState
    data class Ready(
        val book: Book,
        val session: DocSession,
        /** Where reading starts: saved progress or the caller's
         * target, resolved once at open. */
        val initialTargetJson: String?,
        val initialEpubLocator: Locator?,
        val initialIndex: Int,
        val fontSizeMultiplier: Double,
        /** v1.5.7: this book's EPUB book-mode settings (loaded at
         * open; live updates ride [LocalDocReaderViewModel.epubSettings]). */
        val initialEpubSettings: EpubBookSettings = EpubBookSettings(),
        /** v1.5.7: this book's PDF document-mode settings. */
        val initialPdfSettings: PdfDocSettings = PdfDocSettings(),
        /** v1.5.8 (G10): the EPUB declares itself fixed-layout
         * (rendition:layout = pre-paginated) — the reader labels
         * it and its per-book default was paginated at open. */
        val isFixedLayout: Boolean = false
    ) : LocalReaderState

    /** The file is gone / unreadable where it lives (§3.4(d)):
     * plain words + a re-point offer. */
    data class FileMissing(val book: Book?) : LocalReaderState
    data class PasswordRequired(val book: Book?) : LocalReaderState
    data class Failed(val message: String) : LocalReaderState
}

data class LocalSearchHit(
    val index: Int,
    val title: String,
    val snippet: String,
    /** v1.5.34 (R6): the query that produced this hit and its
     * char offset in the section's extracted text — the river
     * lands a search jump ON the hit (transient mark), not
     * merely on its section. -1 when unknown. */
    val query: String = "",
    val charOffset: Int = -1
)

/** A one-shot jump request the surface consumes (page/section). */
data class LocalJump(
    val index: Int,
    val locatorJson: String? = null,
    /** v1.5.34 (R6): a search jump's transient-mark payload —
     * the query + its char offset in the target section.
     * Surfaces that can't mark (PDF, legacy) ignore both. */
    val markQuery: String? = null,
    val markCharOffset: Int = -1
)

/**
 * v1.5.9 (F-3b): the locator-pipeline throttle. A locator
 * emission earns the FULL derivation (JSON round-trip, anchor
 * lookup, progress flows, progress save) only when the section
 * changed or the whole-book progression crossed a 0.1% bucket
 * since the last derived emission. Emissions that earn neither
 * still update the VM's raw current locator (bookmark fidelity
 * is unaffected) — they just skip the expensive bookkeeping.
 * Pure and clock-free so the budget test drives it directly.
 */
internal class EpubLocatorGate {
    private var lastSection: Int? = null
    private var lastBucket: Long? = null

    /** The 0.1%-bucket a whole-book progression falls in. */
    fun bucketOf(totalProgression: Double?): Long? =
        totalProgression?.let { (it * 1_000).toLong() }

    fun shouldDerive(section: Int, totalProgression: Double?): Boolean {
        val bucket = bucketOf(totalProgression)
        if (section == lastSection && bucket == lastBucket) return false
        lastSection = section
        lastBucket = bucket
        return true
    }

    fun reset() {
        lastSection = null
        lastBucket = null
    }
}

/**
 * v1.5.9 (F-4a): debounced settings persistence — the same
 * 500 ms shape `updatePdfSettings` has always had ("zoom rides
 * this path on every pinch move"), extracted so both the VM
 * and its test drive the identical mechanism. State applies
 * instantly in the caller; only the SAVE is debounced, and a
 * burst of submits lands exactly one save, of the LAST value.
 */
internal class DebouncedSettingsSave<T>(
    private val scope: CoroutineScope,
    private val delayMs: Long = 500L,
    private val dispatcher: CoroutineDispatcher? = null,
    private val save: suspend (T) -> Unit
) {
    private var job: Job? = null

    fun submit(value: T) {
        job?.cancel()
        job = if (dispatcher != null) {
            scope.launch(dispatcher) { delay(delayMs); save(value) }
        } else {
            scope.launch { delay(delayMs); save(value) }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * v1.5.9 (F-10): the in-book search sweep's session-lifetime
 * text memo. The first sweep fills it lazily, section by
 * section; second and later queries read the memo and perform
 * ZERO additional `textAt` reads. Bounded at 64 MB of chars —
 * beyond the cap a section's text is re-read on demand and
 * never retained. Cleared when a (new) session opens and when
 * the ViewModel is cleared.
 */
internal class SearchTextMemo(
    private val maxChars: Long = 64_000_000L
) {
    private val texts = mutableMapOf<Int, String>()
    private var totalChars: Long = 0L

    val size: Int get() = synchronized(texts) { texts.size }
    val retainedChars: Long get() = synchronized(texts) { totalChars }

    suspend fun textAt(index: Int, provider: suspend (Int) -> String): String {
        synchronized(texts) { texts[index] }?.let { return it }
        val text = provider(index)
        synchronized(texts) {
            if (!texts.containsKey(index) &&
                totalChars + text.length <= maxChars
            ) {
                texts[index] = text
                totalChars += text.length
            }
        }
        return text
    }

    fun clear() {
        synchronized(texts) {
            texts.clear()
            totalChars = 0L
        }
    }
}

class LocalDocReaderViewModel(
    private val container: AppContainer,
    private val bookId: Long,
    private val startTargetJson: String?
) : ViewModel() {

    private val _state = MutableStateFlow<LocalReaderState>(LocalReaderState.Loading())
    val state: StateFlow<LocalReaderState> = _state

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _searchHits = MutableStateFlow<List<LocalSearchHit>?>(null)
    val searchHits: StateFlow<List<LocalSearchHit>?> = _searchHits
    private val _searchBusy = MutableStateFlow(false)
    val searchBusy: StateFlow<Boolean> = _searchBusy

    private val _jumps = MutableSharedFlow<LocalJump>(extraBufferCapacity = 4)
    val jumps: SharedFlow<LocalJump> = _jumps.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ── v1.5.7 (Scopes B+C): the two readers' settings + live
    // position. The setting sets are format-specific and are
    // loaded once at open (only this ViewModel writes their
    // keys), then updated through the update* functions below.
    private val localPrefs = LocalReaderPrefs(
        container.applicationContext.settingsDataStore
    )
    private val _epubSettings = MutableStateFlow(EpubBookSettings())
    val epubSettings: StateFlow<EpubBookSettings> = _epubSettings
    private val _pdfSettings = MutableStateFlow(PdfDocSettings())
    val pdfSettings: StateFlow<PdfDocSettings> = _pdfSettings
    private var pdfSaveJob: Job? = null

    /** v1.5.9 (F-4a): EPUB settings persistence, debounced
     * 500 ms exactly like the PDF path — state still applies
     * instantly in [updateEpubSettings]. */
    private val epubSave = DebouncedSettingsSave<EpubBookSettings>(
        scope = viewModelScope,
        delayMs = 500L,
        dispatcher = container.ioDispatcher
    ) { next -> runCatching { localPrefs.saveEpub(bookId, next) } }

    /** v1.5.9 (F-3b): the locator derivation throttle. */
    internal val epubLocatorGate = EpubLocatorGate()

    /** v1.5.9 (F-10): search texts, memoised for the session. */
    internal val searchTextMemo = SearchTextMemo()

    private val _epubProgress = MutableStateFlow<EpubProgress?>(null)
    val epubProgress: StateFlow<EpubProgress?> = _epubProgress

    /**
     * v1.5.10 (F4): the navigator's positions per reading-order
     * section, computed ONCE at EPUB open from the
     * publication's own PositionsService
     * (`positionsByReadingOrder`): [epubPositionStarts][i] is
     * the 0-based global position index where section i
     * starts, [epubPositionCounts][i] its position count.
     * Null until the computation lands (locators derived
     * before then simply carry no within-chapter pair).
     */
    @Volatile private var epubPositionStarts: IntArray? = null
    @Volatile private var epubPositionCounts: IntArray? = null

    /** The within-chapter pair for a 1-based global
     * [position] in [section] — L-FG's pure
     * [withinChapterPosition] over the positions seam above. */
    private fun withinChapterFor(
        section: Int,
        globalPosition: Int?
    ): Pair<Int, Int>? {
        val starts = epubPositionStarts ?: return null
        val counts = epubPositionCounts ?: return null
        val start = starts.getOrNull(section) ?: return null
        val count = counts.getOrNull(section) ?: return null
        val position = globalPosition ?: return null
        return withinChapterPosition(position - 1 - start, count)
    }

    /**
     * v1.5.10 (F2): this book's favourite state + toggle — the
     * Book information sheet's FAVORITE row rides the same
     * BookMetaPrefs door the book page uses.
     */
    private val _isFavourite = MutableStateFlow(false)
    val isFavourite: StateFlow<Boolean> = _isFavourite

    fun toggleFavourite() {
        val target = !_isFavourite.value
        viewModelScope.launch(container.ioDispatcher) {
            runCatching {
                container.bookMetaPrefs.setFavourite(bookId, target)
            }
        }
    }
    private val _pdfProgress = MutableStateFlow<PdfProgress?>(null)
    val pdfProgress: StateFlow<PdfProgress?> = _pdfProgress

    /** The navigator's latest EPUB locator (drives "is this
     * contents entry current" highlighting). */
    @Volatile var currentEpubLocator: Locator? = null
        private set

    /** Current position, as the progress row will store it. */
    private data class Position(
        val index: Int,
        val locatorJson: String,
        val bookFraction: Float,
        val inSectionFraction: Float,
        val anchorTitle: String?
    )

    @Volatile private var position: Position? = null
    private var saveJob: Job? = null
    private var session: DocSession? = null
    private var book: Book? = null
    private var password: String? = null

    /**
     * v1.5.9 (F-5): the TTS engine factory seam. Production
     * builds the platform engine; the construction-counter
     * test substitutes a counting fake. The factory runs ONLY
     * inside the [readAloud] lazy — never on the open path.
     */
    internal var ttsEngineFactory: () -> TtsEngine = {
        AndroidTtsEngine(container.applicationContext, viewModelScope)
    }

    private val readAloud: ReadAloudController by lazy {
        ReadAloudController(
            engine = ttsEngineFactory(),
            scope = viewModelScope
        ).also { controller ->
            controller.onChapterFinished = {
                // v1.5.8 (H6): in PDF mode the finish advances
                // the strip feeder page by page; EPUB keeps the
                // section advance.
                if (ttsPdfActive) {
                    pdfSpeech?.onChapterFinished()
                } else {
                    advanceSectionForTts()
                }
            }
            // F-5: once the controller exists, mirror its
            // playing flag into the plain [ttsPlaying] flow.
            viewModelScope.launch {
                controller.state.collect { state ->
                    _ttsPlaying.value = state.playing
                }
            }
        }
    }
    val ttsState: StateFlow<ReadAloudController.State> by lazy { readAloud.state }

    /**
     * v1.5.9 (F-5): whether read-aloud is playing, backed by a
     * PLAIN flow that exists from construction. Collecting it
     * constructs ZERO TTS engines — the mirror into
     * [ReadAloudController.state] starts only once the
     * controller has been built by a first read-aloud use.
     * The EPUB reader collects this instead of [ttsState].
     */
    private val _ttsPlaying = MutableStateFlow(false)
    val ttsPlaying: StateFlow<Boolean> = _ttsPlaying
    @Volatile private var ttsSection: Int = -1
    @Volatile private var ttsUsed: Boolean = false

    /**
     * v1.5.34 (R6): the spoken section as a FLOW — the mirror
     * of [ttsSection] a surface can collect. Read-aloud FOLLOW
     * rides it: a surface that can scroll (the river) keeps the
     * section being spoken in view, driven by the same feed
     * the position reports write. -1 = nothing being spoken.
     * Every ttsSection assignment below mirrors into it.
     */
    private val _ttsSectionFlow = MutableStateFlow(-1)
    val ttsSectionFlow: StateFlow<Int> = _ttsSectionFlow

    // ── v1.5.8 (H6): the PDF strip feeder over the SAME
    // controller (the EPUB seam — no new TTS machinery).
    private var pdfSpeech: PdfStripSpeech? = null
    @Volatile private var ttsPdfActive: Boolean = false

    // ── v1.5.8 (G8): volume-key turns from the comfort host.
    // PDF turns ride the existing jump seam; EPUB turns are
    // consumed by the navigator's owner (EpubBody).
    private val _volumeTurnRequests = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val volumeTurnRequests: SharedFlow<Int> = _volumeTurnRequests.asSharedFlow()

    /** A volume-key turn from the local-reader comfort host
     * (registered only while the per-book setting is on). */
    fun onVolumeKeyTurn(direction: Int) {
        val pdf = session as? PdfSession
        when {
            pdf != null -> {
                val current = _pdfProgress.value?.page ?: position?.index ?: 0
                val target = (current + direction)
                    .coerceIn(0, (pdf.sectionCount - 1).coerceAtLeast(0))
                _jumps.tryEmit(LocalJump(target))
            }
            session is ReadiumEpubSession -> _volumeTurnRequests.tryEmit(direction)
            else -> Unit
        }
    }

    init {
        open()
        viewModelScope.launch {
            container.database.bookmarkDao().observeForBook(bookId)
                .collect { rows -> _bookmarks.value = rows.filter { it.locatorJson != null } }
        }
        viewModelScope.launch {
            container.bookMetaPrefs.favourites.collect { favourites ->
                _isFavourite.value = bookId in favourites
            }
        }
    }

    private fun open() {
        // v1.5.9 (F-10/F-3b): a new session starts with an empty
        // search memo and a fresh locator gate.
        searchTextMemo.clear()
        epubLocatorGate.reset()
        _state.value = LocalReaderState.Loading()
        viewModelScope.launch(container.ioDispatcher) {
            val loaded = container.database.bookDao().getById(bookId)
            book = loaded
            if (loaded == null) {
                _state.value = LocalReaderState.Failed("That book is no longer in the library.")
                return@launch
            }
            try {
                val opened = BookEngine.open(
                    context = container.applicationContext,
                    book = loaded,
                    filesRoot = container.bookStorage.filesDir,
                    cacheDir = container.applicationContext.cacheDir,
                    password = password
                )
                session?.close()
                session = opened
                runCatching { container.recordHistoryStart(bookId) }
                val saved = container.database.readingProgressDao().getForBook(bookId)
                val target = startTargetJson ?: saved?.locatorJson
                val fontMultiplier = runCatching {
                    (container.readerPrefs.config.first().fontSizeSp / 16.0)
                        .coerceIn(0.7, 2.5)
                }.getOrDefault(1.0)
                // v1.5.7: load this book's format-true settings.
                // A book whose EPUB settings were never touched
                // (still exactly the defaults) inherits the global
                // reader text size as its starting font size.
                // v1.5.27 (programme item 1, R3): the reads are
                // KIND-GATED on the opened session — a PDF open
                // never pays the EPUB settings reads, and an EPUB
                // open never pays the PDF one. The other format's
                // value is the untouched default seed, which no
                // surface for this book's format ever reads; the
                // stored values stay in the store, untouched.
                val fixedLayout = (opened as? ReadiumEpubSession)
                    ?.isFixedLayout == true
                val epubSettingsNow = if (opened is ReadiumEpubSession) {
                    val storedEpub = runCatching {
                        localPrefs.epubSettings(bookId).first()
                    }.getOrDefault(EpubBookSettings())
                    val epubSettingsSeeded =
                        if (storedEpub == EpubBookSettings()) {
                            storedEpub.copy(fontSize = fontMultiplier)
                        } else {
                            storedEpub
                        }
                    // v1.5.8 (G10): a fixed-layout EPUB's per-book
                    // DEFAULT is paginated — applied only when the
                    // book carries no stored scroll choice (a
                    // stored choice is the owner's and wins). The
                    // default is NOT persisted: it is a default,
                    // not a choice.
                    if (fixedLayout && !runCatching {
                            localPrefs.hasStoredEpubScroll(bookId)
                        }.getOrDefault(false)
                    ) {
                        epubSettingsSeeded.copy(scroll = false)
                    } else {
                        epubSettingsSeeded
                    }
                } else {
                    EpubBookSettings().copy(fontSize = fontMultiplier)
                }
                _epubSettings.value = epubSettingsNow
                val pdfSettingsNow = if (opened is PdfSession) {
                    runCatching {
                        localPrefs.pdfSettings(bookId).first()
                    }.getOrDefault(PdfDocSettings())
                } else {
                    PdfDocSettings()
                }
                _pdfSettings.value = pdfSettingsNow
                val initialIndex = resolveInitialIndex(opened, target, saved)
                val initialLocator = (opened as? ReadiumEpubSession)?.let { epub ->
                    epub.locatorFromJson(target)
                        ?: epub.locatorFromJson(saved?.locatorJson)
                        ?: epub.locatorForSection(initialIndex)
                }
                if (opened is PdfSession) {
                    _pdfProgress.value = PdfProgress(
                        page = initialIndex, pageCount = opened.sectionCount
                    )
                }
                if (opened is ReadiumEpubSession) {
                    _epubProgress.value = EpubProgress(
                        fraction = saved?.fraction ?: 0f,
                        chapterTitle = opened.anchorTitleFor(initialIndex),
                        sectionIndex = initialIndex,
                        sectionCount = opened.sectionCount
                    )
                    // F4: the positions seam — computed once,
                    // off this open path's critical section,
                    // from the publication's own positions
                    // service. A book whose service yields
                    // nothing simply never gains the pair.
                    viewModelScope.launch(container.ioDispatcher) {
                        runCatching {
                            val byResource = opened.publication
                                .positionsByReadingOrder()
                            if (byResource.isNotEmpty()) {
                                val starts = IntArray(byResource.size)
                                val counts = IntArray(byResource.size)
                                var acc = 0
                                byResource.forEachIndexed { i, positions ->
                                    starts[i] = acc
                                    counts[i] = positions.size
                                    acc += positions.size
                                }
                                epubPositionStarts = starts
                                epubPositionCounts = counts
                            }
                        }
                    }
                }
                _state.value = LocalReaderState.Ready(
                    book = loaded,
                    session = opened,
                    initialTargetJson = target,
                    initialEpubLocator = initialLocator,
                    initialIndex = initialIndex,
                    fontSizeMultiplier = fontMultiplier,
                    initialEpubSettings = epubSettingsNow,
                    initialPdfSettings = pdfSettingsNow,
                    isFixedLayout = fixedLayout
                )
            } catch (e: LocalPasswordRequiredException) {
                _state.value = LocalReaderState.PasswordRequired(loaded)
            } catch (e: LocalFileMissingException) {
                _state.value = if (e.message == "file not found") {
                    LocalReaderState.FileMissing(loaded)
                } else {
                    LocalReaderState.Failed(e.message ?: "That book couldn't be opened.")
                }
            } catch (e: Exception) {
                _state.value = LocalReaderState.Failed(
                    "That book couldn't be opened. (${e.javaClass.simpleName})"
                )
            }
        }
    }

    private fun resolveInitialIndex(
        opened: DocSession,
        targetJson: String?,
        saved: ReadingProgress?
    ): Int {
        LocatorJson.targetIndex(targetJson)?.let {
            return it.coerceIn(0, (opened.sectionCount - 1).coerceAtLeast(0))
        }
        LocatorJson.parsePdfLocator(targetJson ?: saved?.locatorJson)?.let {
            return it.first.coerceIn(0, (opened.sectionCount - 1).coerceAtLeast(0))
        }
        (opened as? ReadiumEpubSession)?.sectionIndexOfLocator(
            targetJson ?: saved?.locatorJson
        )?.let { return it }
        return (saved?.chapterIndex ?: 0)
            .coerceIn(0, (opened.sectionCount - 1).coerceAtLeast(0))
    }

    fun retryWithPassword(candidate: String) {
        password = candidate.takeIf { it.isNotEmpty() }
        open()
    }

    // ── Position reports from the surfaces ───────────────────────

    /** EPUB: the navigator's current locator moved. */
    fun onEpubLocator(locator: Locator) {
        val epub = session as? ReadiumEpubSession ?: return
        // v1.5.9 (F-3b): the RAW locator is always kept (bookmark
        // fidelity), and the section is a precomputed-map lookup
        // on the locator's own href — no JSON round-trip. The
        // full derivation below runs only when the gate earns it
        // (section changed, or the 0.1%-bucketed total
        // progression changed).
        currentEpubLocator = locator
        val section = epub.sectionIndexOfHref(locator.href.toString())
            ?: position?.index ?: 0
        if (!epubLocatorGate.shouldDerive(
                section, locator.locations.totalProgression
            )
        ) {
            return
        }
        val locatorJson = runCatching { locator.toJSON().toString() }.getOrNull() ?: return
        val fraction = locator.locations.totalProgression?.toFloat()
            ?: LocatorJson.epubTotalProgression(locatorJson)
            ?: if (epub.sectionCount > 0) section.toFloat() / epub.sectionCount else 0f
        val anchor = epub.anchorTitleFor(section)
        position = Position(
            index = section,
            locatorJson = locatorJson,
            bookFraction = fraction.coerceIn(0f, 1f),
            inSectionFraction = locator.locations.progression?.toFloat() ?: 0f,
            anchorTitle = anchor
        )
        _epubProgress.value = EpubProgress(
            fraction = fraction.coerceIn(0f, 1f),
            chapterTitle = anchor,
            sectionIndex = section,
            sectionCount = epub.sectionCount,
            withinChapter = withinChapterFor(
                section, locator.locations.position
            ),
            inSectionFraction = locator.locations.progression
                ?.toFloat() ?: 0f
        )
        scheduleSave()
    }

    /** PDF: the scrolling surface's current page moved. */
    fun onPdfPosition(page: Int, inPageFraction: Float) {
        val pdf = session as? PdfSession ?: return
        val count = pdf.sectionCount.coerceAtLeast(1)
        position = Position(
            index = page,
            locatorJson = LocatorJson.pdfLocator(page, inPageFraction),
            bookFraction = ((page + inPageFraction) / count).coerceIn(0f, 1f),
            inSectionFraction = inPageFraction,
            anchorTitle = pdf.anchorTitleFor(page)
        )
        _pdfProgress.value = PdfProgress(page = page, pageCount = pdf.sectionCount)
        scheduleSave()
    }

    // ── v1.5.7: settings updates + seeks ─────────────────────────

    /** Applies a book-mode (EPUB) settings change: state first
     * (the surface submits it to the navigator immediately), then
     * persisted for this book. v1.5.9 (F-4a): persistence is
     * debounced 500 ms through [epubSave], exactly like the PDF
     * path — a slider drag no longer writes the whole DataStore
     * file per tick. The save's SCOPE is unchanged: still this
     * book only; only WHEN it lands changed. */
    fun updateEpubSettings(transform: (EpubBookSettings) -> EpubBookSettings) {
        val next = transform(_epubSettings.value)
        _epubSettings.value = next
        epubSave.submit(next)
    }

    /**
     * v1.5.10 (G3): set (or clear, with null) this book's USER
     * FONT and reopen the book. The font joins the publication
     * AT OPEN — the session's container wrap and the
     * navigator's font declaration are both built there — so a
     * pick takes effect by re-opening on the spot; the saved
     * reading position restores as on any open. The save lands
     * FIRST, synchronously with this call rather than through
     * the 500 ms debounce: the reopened session reads the
     * stored choice from the DataStore itself.
     */
    fun setEpubUserFontAndReopen(fileName: String?) {
        val next = _epubSettings.value.copy(userFontFileName = fileName)
        _epubSettings.value = next
        viewModelScope.launch(container.ioDispatcher) {
            runCatching { localPrefs.saveEpub(bookId, next) }
            open()
        }
    }

    /** Applies a document-mode (PDF) settings change. Zoom rides
     * this path on every pinch move, so persistence is debounced;
     * state itself updates immediately. */
    fun updatePdfSettings(transform: (PdfDocSettings) -> PdfDocSettings) {
        val next = transform(_pdfSettings.value)
        _pdfSettings.value = next
        pdfSaveJob?.cancel()
        pdfSaveJob = viewModelScope.launch(container.ioDispatcher) {
            delay(500)
            runCatching { localPrefs.savePdf(bookId, next) }
        }
    }

    /** Percent-slider seek (EPUB): resolves the fraction through
     * the toolkit's locator service; falls back to the nearest
     * section start when a book can't resolve progressions. */
    fun seekEpubToFraction(fraction: Float) {
        val epub = session as? ReadiumEpubSession ?: return
        val clamped = fraction.coerceIn(0f, 1f)
        viewModelScope.launch {
            val locator = epub.locatorForProgression(clamped.toDouble())
            if (locator != null) {
                val json = runCatching { locator.toJSON().toString() }.getOrNull()
                val index = epub.sectionIndexOfLocator(json)
                    ?: position?.index ?: 0
                _jumps.tryEmit(LocalJump(index, json))
            } else {
                val index = (clamped * epub.sectionCount).toInt()
                    .coerceIn(0, (epub.sectionCount - 1).coerceAtLeast(0))
                _jumps.tryEmit(LocalJump(index))
            }
        }
    }

    /** Page seek (PDF): the document surface consumes the jump in
     * whichever scroll model it is currently showing. */
    fun goToPdfPage(page: Int) {
        val pdf = session as? PdfSession ?: return
        _jumps.tryEmit(
            LocalJump(page.coerceIn(0, (pdf.sectionCount - 1).coerceAtLeast(0)))
        )
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            saveProgressNow()
        }
    }

    /**
     * v1.5.9 delta (sift A-7): commit the pending debounced
     * PROGRESS save NOW, through the same store path
     * ([saveProgressNow]) the debounce itself uses. Called by
     * the EPUB UI layer's lifecycle observer on ON_STOP, while
     * [viewModelScope] is still healthy — the `onCleared`
     * launch remains only as a backstop (it fires as the scope
     * is being torn down, so it cannot be the only flush).
     * The 800 ms debounce itself is unchanged, and this is the
     * progress path only — never the F-4a settings persistence.
     * No pending save (debounce already landed, or no position
     * yet) = no-op. The commit rides the IO dispatcher like the
     * VM's other store writes, so the caller's thread never
     * blocks on the database.
     */
    fun flushPendingProgressSave() {
        val pending = saveJob
        if (pending == null || !pending.isActive) return
        pending.cancel()
        saveJob = null
        viewModelScope.launch(container.ioDispatcher) {
            runCatching { saveProgressNow() }
        }
    }

    private suspend fun saveProgressNow() {
        val current = position ?: return
        val currentBook = book ?: return
        container.database.readingProgressDao().upsert(
            ReadingProgress(
                bookId = currentBook.id,
                chapterId = 0,
                chapterIndex = current.index,
                charOffset = 0,
                fraction = current.bookFraction,
                scrollFraction = current.inSectionFraction,
                updatedAt = System.currentTimeMillis(),
                locatorJson = current.locatorJson,
                anchorText = current.anchorTitle
            )
        )
        runCatching {
            container.database.bookDao().update(
                currentBook.copy(lastOpenedAt = System.currentTimeMillis())
            )
        }
    }

    // ── Navigation ───────────────────────────────────────────────

    fun goToOutline(entry: OutlineEntry) {
        val index = LocatorJson.targetIndex(entry.targetJson) ?: return
        val epub = session as? ReadiumEpubSession
        if (epub != null) {
            val locator = epub.locatorForSection(index)
            _jumps.tryEmit(LocalJump(index, locator?.let {
                runCatching { it.toJSON().toString() }.getOrNull()
            }))
        } else {
            _jumps.tryEmit(LocalJump(index))
        }
    }

    fun goToBookmark(bookmark: Bookmark) {
        val locatorJson = bookmark.locatorJson ?: return
        LocatorJson.parsePdfLocator(locatorJson)?.let {
            _jumps.tryEmit(LocalJump(it.first, locatorJson))
            return
        }
        val index = (session as? ReadiumEpubSession)
            ?.sectionIndexOfLocator(locatorJson) ?: bookmark.chapterIndex
        _jumps.tryEmit(LocalJump(index, locatorJson))
    }

    /**
     * v1.5.9 (F-9): the seed a bookmark falls back to before the
     * first locator lands — the Ready state's initial index +
     * initial EPUB locator (or the stored target JSON it was
     * resolved from). Null only when no seed exists either.
     */
    private fun bookmarkSeedPosition(): Position? {
        val ready = _state.value as? LocalReaderState.Ready ?: return null
        val locatorJson = ready.initialEpubLocator?.let {
            runCatching { it.toJSON().toString() }.getOrNull()
        } ?: ready.initialTargetJson ?: return null
        val count = ready.session.sectionCount
        val fraction = LocatorJson.epubTotalProgression(locatorJson)
            ?: LocatorJson.parsePdfLocator(locatorJson)?.let { (page, inPage) ->
                if (count > 0) (page + inPage) / count else 0f
            }
            ?: if (count > 0) ready.initialIndex.toFloat() / count else 0f
        return Position(
            index = ready.initialIndex,
            locatorJson = locatorJson,
            bookFraction = fraction.coerceIn(0f, 1f),
            inSectionFraction = LocatorJson.epubProgression(locatorJson)
                ?: LocatorJson.parsePdfLocator(locatorJson)?.second ?: 0f,
            anchorTitle = when (val s = ready.session) {
                is ReadiumEpubSession -> s.anchorTitleFor(ready.initialIndex)
                is PdfSession -> s.anchorTitleFor(ready.initialIndex)
                else -> null
            }
        )
    }

    fun bookmarkHere() {
        val current = position ?: bookmarkSeedPosition() ?: run {
            _messages.tryEmit("Wait for the page to settle, then try again.")
            return
        }
        val currentBook = book ?: return
        viewModelScope.launch(container.ioDispatcher) {
            container.database.bookmarkDao().insert(
                Bookmark(
                    bookId = currentBook.id,
                    chapterId = 0,
                    chapterIndex = current.index,
                    charOffset = 0,
                    label = current.anchorTitle
                        ?: if (currentBook.localFormat == BookFormat.PDF) {
                            "Page ${current.index + 1}"
                        } else {
                            "Section ${current.index + 1}"
                        },
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    locatorJson = current.locatorJson
                )
            )
            _messages.emit("Bookmark added.")
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch(container.ioDispatcher) {
            container.database.bookmarkDao().delete(bookmark)
        }
    }

    // ── Search (in-book, §3.1 row 11) ────────────────────────────

    fun search(query: String) {
        val opened = session ?: return
        if (query.isBlank()) {
            _searchHits.value = null
            return
        }
        viewModelScope.launch(container.ioDispatcher) {
            _searchBusy.value = true
            try {
                val hits = mutableListOf<LocalSearchHit>()
                for (index in 0 until opened.sectionCount) {
                    if (hits.size >= 100) break
                    // v1.5.9 (F-10): the sweep reads through the
                    // session memo — a repeat query performs no
                    // additional textAt reads.
                    val text = runCatching {
                        searchTextMemo.textAt(index) { opened.textAt(it) }
                    }.getOrDefault("")
                    if (text.isBlank()) continue
                    var from = 0
                    while (hits.size < 100) {
                        val found = text.indexOf(query, from, ignoreCase = true)
                        if (found < 0) break
                        val start = (found - 40).coerceAtLeast(0)
                        val end = (found + query.length + 60).coerceAtMost(text.length)
                        hits += LocalSearchHit(
                            index = index,
                            title = opened.outline.lastOrNull {
                                (LocatorJson.targetIndex(it.targetJson)
                                    ?: Int.MAX_VALUE) <= index
                            }?.title ?: if (opened.format == BookFormat.PDF) {
                                "Page ${index + 1}"
                            } else {
                                "Section ${index + 1}"
                            },
                            snippet = text.substring(start, end).replace('\n', ' '),
                            query = query,
                            charOffset = found
                        )
                        from = found + query.length
                    }
                }
                _searchHits.value = hits
            } finally {
                _searchBusy.value = false
            }
        }
    }

    fun goToSearchHit(hit: LocalSearchHit) {
        val epub = session as? ReadiumEpubSession
        if (epub != null) {
            val locator = epub.locatorForSection(hit.index)
            _jumps.tryEmit(LocalJump(
                hit.index,
                locator?.let {
                    runCatching { it.toJSON().toString() }.getOrNull()
                },
                markQuery = hit.query.takeIf { it.isNotBlank() },
                markCharOffset = hit.charOffset
            ))
        } else {
            _jumps.tryEmit(LocalJump(hit.index))
        }
    }

    // ── Read aloud (TTS, §3.1 row 11) ────────────────────────────
    // Sentence-level through the existing ReadAloudController seam:
    // the section's engine text becomes a ChapterText of paragraph
    // blocks; per-word sync is NOT carried over — the spoken unit
    // is the section, and finishing it advances to the next one.

    fun toggleReadAloud() {
        ttsUsed = true
        if (readAloud.state.value.playing) {
            readAloud.pause()
            return
        }
        val start = position?.index ?: (_state.value as? LocalReaderState.Ready)
            ?.initialIndex ?: 0
        val pdf = session as? PdfSession
        if (pdf != null) {
            // v1.5.8 (H6): PDF speech = the strip through the
            // same controller, page-anchored from here.
            ttsPdfActive = true
            pdfSpeechFor(pdf).startAt(start)
        } else {
            ttsPdfActive = false
            speakSection(start)
        }
    }

    private fun pdfSpeechFor(pdf: PdfSession): PdfStripSpeech =
        pdfSpeech ?: PdfStripSpeech(
            textAt = { page -> pdf.textAt(page) },
            pageCount = pdf.sectionCount,
            sink = object : SpeechSink {
                override fun loadChapter(text: ChapterText) =
                    readAloud.loadChapter(text)
                override fun play() = readAloud.play()
            },
            scope = viewModelScope,
            onNoText = {
                _messages.tryEmit("There's no text to read on this page.")
            }
        ).also { pdfSpeech = it }

    fun stopReadAloud() {
        readAloud.stopAll()
        pdfSpeech?.stop()
        ttsPdfActive = false
        ttsSection = -1
        _ttsSectionFlow.value = -1
    }

    private fun speakSection(index: Int) {
        val opened = session ?: return
        viewModelScope.launch(container.ioDispatcher) {
            val text = runCatching { opened.textAt(index) }.getOrDefault("")
            if (text.isBlank()) {
                _messages.emit("There's no text to read on this page.")
                return@launch
            }
            ttsSection = index
            _ttsSectionFlow.value = index
            val blocks = text.split("\n\n", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map {
                    ChapterBlock.Paragraph(
                        spans = listOf(
                            app.xreader.domain.content.TextSpan(text = it)
                        )
                    ) as ChapterBlock
                }
            readAloud.loadChapter(ChapterText(ChapterContent(blocks = blocks)))
            readAloud.play()
        }
    }

    private fun advanceSectionForTts() {
        val opened = session ?: return
        val next = ttsSection + 1
        if (next in 0 until opened.sectionCount) {
            speakSection(next)
        } else {
            ttsSection = -1
            _ttsSectionFlow.value = -1
        }
    }

    // ── Re-point a moved file (§3.4(d)) ──────────────────────────

    /**
     * Re-attaches the book to a re-picked file through the data
     * workstream's seam (`BookImporter.stage` → `registerReAdd`):
     * the fingerprint keeps the reader's place and bookmarks.
     * [openStream] is invoked on an IO thread by the importer.
     */
    fun repointPicked(
        displayName: String,
        documentUri: String,
        openStream: () -> java.io.InputStream?
    ) {
        val currentBook = book ?: return
        _state.value = LocalReaderState.Loading()
        viewModelScope.launch(container.ioDispatcher) {
            val staged = container.bookImporter.stage(
                displayName, documentUri = documentUri, openStream = openStream
            )
            val result = when (staged) {
                is AppResult.Err -> staged
                is AppResult.Ok -> container.bookImporter.registerReAdd(
                    currentBook.id, staged.value
                )
            }
            when (result) {
                is AppResult.Ok -> {
                    _messages.emit("File re-attached — your place was kept.")
                    open()
                }
                is AppResult.Err -> {
                    _state.value = LocalReaderState.FileMissing(book)
                    _messages.emit("That file couldn't be attached to this book.")
                }
            }
        }
    }

    override fun onCleared() {
        // v1.5.9 (F-10): the search memo is session-lifetime.
        searchTextMemo.clear()
        // Backstop only (sift A-7): the reliable flush is the
        // UI lifecycle's ON_STOP → flushPendingProgressSave,
        // which runs while the scope is healthy. This launch
        // fires as the scope is being torn down.
        viewModelScope.launch { runCatching { saveProgressNow() } }
        if (ttsUsed) {
            runCatching { readAloud.stopAll() }
        }
        session?.close()
        session = null
        super.onCleared()
    }

    companion object {
        fun factory(
            container: AppContainer,
            bookId: Long,
            startTargetJson: String?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LocalDocReaderViewModel(container, bookId, startTargetJson) as T
        }
    }
}
