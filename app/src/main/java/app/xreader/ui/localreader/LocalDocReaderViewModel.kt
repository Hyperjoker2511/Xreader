package app.xreader.ui.localreader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
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
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.importer.ImportCompletionState
import app.xreader.data.prefs.settingsDataStore
import app.xreader.data.reader.ReadAloudController
import app.xreader.data.reader.TtsEngine
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.ChapterText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v1.5.6 (§3.1 row 9 / §3.3): the local-document reader's state.
 * ONE open behind ONE loading state — the old "Preparing chapter…"
 * state machine does not exist on this path.
 *
 * v1.7 OE-2: the EPUB position/settings state that used to
 * live here is gone with the river — an EPUB open now ends
 * in the prepare host's redirect or one of the honest
 * states below, never in a Ready carrying a session.
 */

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
        val initialIndex: Int,
        val fontSizeMultiplier: Double,
        /** v1.5.7: this book's PDF document-mode settings. */
        val initialPdfSettings: PdfDocSettings = PdfDocSettings()
    ) : LocalReaderState

    /** The file is gone / unreadable where it lives (§3.4(d)):
     * plain words + a re-point offer. */
    data class FileMissing(val book: Book?) : LocalReaderState
    data class PasswordRequired(val book: Book?) : LocalReaderState

    /** [canResumeImport] (v1.7 MR-1, A4): this failure is a
     * converted-format book that reached the local surface
     * because none of its chapters landed — its retry is the
     * import resume, offered by the Failed surface.
     * v1.7 OE-2 (R13): an EPUB whose one-time prepare failed
     * also carries it — its Try again re-runs the prepare.
     * Every other failure leaves it false and keeps the
     * Back-only shape. */
    data class Failed(
        val message: String,
        val canResumeImport: Boolean = false
    ) : LocalReaderState
}

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

/** v1.7 Patch 3 (Item 1a): the local open flow's verdict for
 * an EPUB — leave for the core route, or refuse honestly.
 * v1.7 OE-2 (R12): the three shapes are kept, but there is
 * no local EPUB road left to continue onto — [ContinueLocal]
 * now means the prepare host has already landed the book on
 * a local terminal state (Failed / FileMissing) and the
 * open flow simply stops. */
private sealed interface EpubOpenStep {
    data class Redirect(val chapterIndex: Int) : EpubOpenStep
    data class Refused(val message: String) : EpubOpenStep
    data object ContinueLocal : EpubOpenStep
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

    /** MR-2 (A11): the unfiltered bookmark rows — the
     * published [bookmarks] list is derived from these
     * plus the PDF conversion state, so a converted PDF's
     * core-shaped rows (locatorJson == null, chapterId in
     * the conversion) join the local surface's list,
     * translated through the bridge. Every other book
     * keeps the shipped filter exactly. */
    private val _bookmarksRaw = MutableStateFlow<List<Bookmark>>(emptyList())

    private val _jumps = MutableSharedFlow<LocalJump>(extraBufferCapacity = 4)
    val jumps: SharedFlow<LocalJump> = _jumps.asSharedFlow()

    /** v1.7 Patch 3 (Item 1a): a prepared EPUB leaves this
     * reader for the core route — the emission is the core
     * chapter index to open at. The screen collects it and
     * navigates (popping this destination); the state stays
     * Loading meanwhile, so nothing flashes behind it. */
    private val _coreRedirect = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val coreRedirect: SharedFlow<Int> = _coreRedirect.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ── v1.5.7 (Scopes B+C): the readers' settings + live
    // position. The setting sets are format-specific and are
    // loaded once at open (only this ViewModel writes their
    // keys), then updated through the update* functions below.
    // v1.7 OE-2: the EPUB half of this machinery (settings
    // flow, debounced save, locator gate, search memo,
    // positions seam, progress flow) is deleted with the
    // river — an EPUB never reaches a session here any more.
    private val localPrefs = LocalReaderPrefs(
        container.applicationContext.settingsDataStore
    )
    private val _pdfSettings = MutableStateFlow(PdfDocSettings())
    val pdfSettings: StateFlow<PdfDocSettings> = _pdfSettings
    private var pdfSaveJob: Job? = null

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

    /** v1.7 Patch 2 (item 0b): the derived [position] is newer
     * than the last row [saveProgressNow] wrote. Set wherever a
     * position is derived (EPUB derive, PDF position), cleared
     * by the write itself. The exit flush consults it so a
     * forced exit derivation lands even when its debounce job
     * was already consumed or never scheduled. */
    @Volatile private var progressDirty: Boolean = false
    private var session: DocSession? = null
    private var book: Book? = null
    private var password: String? = null

    /** Patch 12: the global voice choice (Providers & keys),
     * collected for the engine factory — the local reader
     * follows the global choice; the per-book voice UI lives in
     * the novel reader's Listen sheet (spec §E.5). */
    @Volatile
    private var globalVoiceSettings: app.xreader.data.prefs.VoiceSettings =
        app.xreader.data.prefs.VoiceSettings()

    private fun globalVoiceEngine(): app.xreader.data.prefs.VoiceEngine =
        globalVoiceSettings.engine

    /**
     * v1.5.9 (F-5): the TTS engine factory seam. Production
     * builds the voice-engine router (Patch 12: device + Edge,
     * the global choice); the construction-counter test
     * substitutes a counting fake. The factory runs ONLY inside
     * the [readAloud] lazy — never on the open path.
     */
    internal var ttsEngineFactory: () -> TtsEngine = {
        container.readAloudEngine(
            scope = viewModelScope,
            engineChoice = { globalVoiceEngine() },
            voiceIdOverride = { null }
        )
    }

    private val readAloud: ReadAloudController by lazy {
        // 2 GB perf pass (F5): the tier is read once here and
        // feeds both the prime depth and the chapter-ahead gate.
        val tier = app.xreader.data.prefs.PerformancePolicy.tierForRam(
            container.totalRamMb()
        )
        ReadAloudController(
            engine = ttsEngineFactory(),
            scope = viewModelScope,
            prefetchDepth = app.xreader.data.reader.TtsPrefetchDepth.forTier(tier),
            chapterAhead = app.xreader.data.reader.TtsChapterAhead.forTier(tier)
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
    // PDF turns ride the existing jump seam. (v1.7 OE-2: the
    // EPUB turn requests died with their only consumer, the
    // river body.)
    /** A volume-key turn from the local-reader comfort host
     * (registered only while the per-book setting is on). */
    fun onVolumeKeyTurn(direction: Int) {
        val pdf = session as? PdfSession
        if (pdf != null) {
            val current = _pdfProgress.value?.page ?: position?.index ?: 0
            val target = (current + direction)
                .coerceIn(0, (pdf.sectionCount - 1).coerceAtLeast(0))
            _jumps.tryEmit(LocalJump(target))
        }
    }

    init {
        open()
        // Patch 12: keep the global voice choice current for the
        // engine factory's per-sentence reads.
        viewModelScope.launch {
            container.voicePrefs.settings.collect { settings ->
                globalVoiceSettings = settings
            }
        }
        // v1.7 Patch 1 (item 6b): memory pressure flushes the
        // pending progress save at once — the trim can arrive
        // while this reader is still foreground, where no
        // pause/stop flush would fire. The collection dies
        // with the scope, so a cleared VM never flushes twice.
        viewModelScope.launch {
            container.memoryPressureEvents.collect {
                flushPendingProgressSave()
            }
        }
        viewModelScope.launch {
            container.database.bookmarkDao().observeForBook(bookId)
                .collect { rows -> _bookmarksRaw.value = rows }
        }
        // The published list — the shipped locator-only
        // filter (a mark with no locator belongs to a
        // surface this reader is not).
        viewModelScope.launch {
            _bookmarksRaw.collect { rows ->
                _bookmarks.value =
                    rows.filter { it.locatorJson != null }
            }
        }
        viewModelScope.launch {
            container.bookMetaPrefs.favourites.collect { favourites ->
                _isFavourite.value = bookId in favourites
            }
        }
    }

    private fun open() {
        _state.value = LocalReaderState.Loading()
        viewModelScope.launch(container.ioDispatcher) {
            val loaded = container.database.bookDao().getById(bookId)
            book = loaded
            if (loaded == null) {
                _state.value = LocalReaderState.Failed("That book is no longer in the library.")
                return@launch
            }
            try {
                val saved = container.database.readingProgressDao().getForBook(bookId)
                // v1.7 OE-2 (R12): an EPUB never reaches a
                // session open — BookEngine has no EPUB arm
                // any more. The prepare host below is its whole
                // road on this surface, and it is always
                // terminal: a redirect to the core route, the
                // DRM refusal, or a terminal state the host
                // itself set (prepare-failed / file-missing).
                if (loaded.localFormat == BookFormat.EPUB) {
                    when (val step = runEpubPrepareStep(loaded, saved)) {
                        is EpubOpenStep.Redirect ->
                            _coreRedirect.tryEmit(step.chapterIndex)
                        is EpubOpenStep.Refused ->
                            _state.value = LocalReaderState.Failed(step.message)
                        EpubOpenStep.ContinueLocal -> Unit
                    }
                    return@launch
                }
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
                val target = startTargetJson ?: saved?.locatorJson
                val fontMultiplier = runCatching {
                    (container.readerPrefs.config.first().fontSizeSp / 16.0)
                        .coerceIn(0.7, 2.5)
                }.getOrDefault(1.0)
                // v1.5.7: load this book's format-true settings.
                // v1.5.27 (programme item 1, R3): the read is
                // KIND-GATED on the opened session — only a PDF
                // open pays the PDF settings read; any other
                // session gets the untouched default seed, which
                // no surface for its format ever reads.
                // v1.7 OE-2: the EPUB settings load that stood
                // here (inherit-the-global-size seeding, the
                // fixed-layout paginated default) is deleted —
                // an EPUB's settings now live in the core
                // reader, fed by the prepare's settings merge.
                val pdfSettingsNow = if (opened is PdfSession) {
                    runCatching {
                        localPrefs.pdfSettings(bookId).first()
                    }.getOrDefault(PdfDocSettings())
                } else {
                    PdfDocSettings()
                }
                _pdfSettings.value = pdfSettingsNow
                val initialIndex = resolveInitialIndex(opened, target, saved)
                // v1.7 OE-2: the EPUB initial-locator ladder,
                // the EPUB progress seed and the positions seam
                // that stood here are deleted with the river —
                // an EPUB's place now travels through the
                // prepare's place migration into the core row.
                if (opened is PdfSession) {
                    _pdfProgress.value = PdfProgress(
                        page = initialIndex, pageCount = opened.sectionCount
                    )
                }
                _state.value = LocalReaderState.Ready(
                    book = loaded,
                    session = opened,
                    initialTargetJson = target,
                    initialIndex = initialIndex,
                    fontSizeMultiplier = fontMultiplier,
                    initialPdfSettings = pdfSettingsNow
                )
            } catch (e: LocalPasswordRequiredException) {
                _state.value = LocalReaderState.PasswordRequired(loaded)
            } catch (e: LocalFileMissingException) {
                _state.value = if (e.message == "file not found") {
                    LocalReaderState.FileMissing(loaded)
                } else {
                    LocalReaderState.Failed(
                        message = e.message ?: "That book couldn't be opened.",
                        // v1.7 MR-1 (A4): BookEngine.open's
                        // chapter-reader message is the designed
                        // landing for a converted-format book
                        // with nothing landed — its Failed state
                        // offers the import resume. EPUB/PDF
                        // failures never set this (their formats
                        // are not converted formats).
                        canResumeImport =
                            BookEngine.isConvertedFormat(loaded.localFormat)
                    )
                }
            } catch (e: Exception) {
                _state.value = LocalReaderState.Failed(
                    "That book couldn't be opened. (${e.javaClass.simpleName})"
                )
            }
        }
    }

    /**
     * v1.7 Patch 3 (Item 1): the EPUB prepare step of the
     * open flow — v1.7 OE-2 (R12): now the prepare HOST, the
     * only road an EPUB takes on this surface. No session is
     * opened for an EPUB any more: a book with a current
     * prepare record redirects to the core route at its
     * saved core place; anything else runs the one-time
     * prepare, whose outcomes land as the redirect, the DRM
     * refusal, or — with no local road left to fall back to
     * (R13) — the Failed state with a working Try again.
     * The detect is built from the parser probe (R5), never
     * from an engine session.
     */
    private suspend fun runEpubPrepareStep(
        book: Book,
        saved: ReadingProgress?
    ): EpubOpenStep {
        // The open's history start, exactly where the
        // session-era flow recorded it (after the session
        // opened, before the prepare step ran).
        runCatching { container.recordHistoryStart(bookId) }
        val record = runCatching { localPrefs.epubPrepareRecord(bookId) }
            .getOrNull()
        if (app.xreader.data.prepare.EpubPrepareAccess
                .isPrepared(record, book)
        ) {
            val rows = runCatching {
                container.database.chapterDao().forBook(bookId)
            }.getOrDefault(emptyList())
            val contentInPlace = rows.any { it.contentPath != null }
            val placeIsCore = saved == null || saved.chapterId != 0L
            if (contentInPlace && placeIsCore) {
                return EpubOpenStep.Redirect(
                    (saved?.chapterIndex ?: 0)
                        .coerceIn(0, maxOf(0, rows.lastIndex))
                )
            }
            // A record without its landed content (the book's
            // downloads were deleted) or with a place that was
            // never migrated: fall through and prepare again —
            // the landing re-lands and Item 3 carries the place.
        }
        val detect = epubDetectFor(book)
        _state.value = LocalReaderState.Loading(
            "Preparing the book for the reader…"
        )
        val service = app.xreader.data.prepare.EpubPrepareService(
            chapterDao = container.database.chapterDao(),
            readingProgressDao = container.database.readingProgressDao(),
            bookmarkDao = container.database.bookmarkDao(),
            highlightDao = container.database.highlightDao(),
            storage = container.bookStorage,
            prefs = localPrefs,
            bookReaderPrefs = container.bookReaderPrefs,
            selectionHighlightStore = SelectionHighlightStore(
                container.applicationContext.settingsDataStore
            ),
            contentResolver = container.applicationContext.contentResolver,
            inTransaction = { block ->
                container.database.withTransaction(block)
            },
            // The 42-chapters fix: prepare reports the
            // landed chapter count; write it onto the Book
            // row so totalChapters shows the segmented
            // count (13), not the raw-parse count (42)
            // stamped at import. ONLY totalChapters moves —
            // updatedAt and every other field stay as-is,
            // and a row already carrying the right count
            // is not rewritten.
            onChaptersLanded = { bookId, landedCount ->
                val bookDao = container.database.bookDao()
                val row = bookDao.getById(bookId)
                if (row != null && row.totalChapters != landedCount) {
                    bookDao.update(
                        row.copy(totalChapters = landedCount)
                    )
                }
            }
        )
        return when (
            val outcome = service.prepare(book, detect) { fraction ->
                val pct = fraction?.let {
                    " ${(it * 100).toInt()}%"
                } ?: ""
                _state.value = LocalReaderState.Loading(
                    "Preparing the book for the reader…$pct"
                )
            }
        ) {
            is app.xreader.data.prepare.EpubPrepareOutcome.Redirect ->
                EpubOpenStep.Redirect(outcome.chapterIndex)
            is app.xreader.data.prepare.EpubPrepareOutcome.Refused ->
                EpubOpenStep.Refused(outcome.message)
            is app.xreader.data.prepare.EpubPrepareOutcome.Fallback -> {
                // v1.7 OE-2 (R13): no local road remains —
                // the failure lands on Failed with the retry
                // offered; Try again re-runs this prepare
                // (see resumeImport's EPUB branch).
                DiagnosticLog.record(
                    "epub-prepare",
                    "book $bookId could not be prepared: ${outcome.reason}"
                )
                _state.value = LocalReaderState.Failed(
                    message = "That book couldn't be prepared " +
                        "for the reader.",
                    canResumeImport = true
                )
                EpubOpenStep.ContinueLocal
            }
            app.xreader.data.prepare.EpubPrepareOutcome.NotEligible -> {
                // The prepare found nothing to convert: a
                // book with no file reference at all is the
                // FileMissing shape (its re-point offer is
                // the honest road); anything else is a plain
                // couldn't-open.
                _state.value =
                    if (book.filePath == null && book.documentUri == null) {
                        LocalReaderState.FileMissing(book)
                    } else {
                        LocalReaderState.Failed(
                            "That book couldn't be opened."
                        )
                    }
                EpubOpenStep.ContinueLocal
            }
        }
    }

    /**
     * v1.7 OE-2 (R5): the sessionless EPUB detect, built
     * from [app.xreader.data.parse.EpubParser]'s probe of
     * the book's own file — the fixed-layout and
     * RTL-progression flags read from the OPF itself, and
     * the reading-order hrefs from the probe's spine list
     * (the space EpubHrefBridge matches BY HREF). The file
     * is staged exactly as the prepare stages it. A probe
     * that fails yields an empty detect and lets the
     * prepare's own parse produce the honest outcome
     * (Refused for a protected book, Fallback otherwise).
     */
    private suspend fun epubDetectFor(
        book: Book
    ): app.xreader.data.prepare.EpubDetect {
        val storage = container.bookStorage
        val scratch = storage.importStagingDir("epub-detect-${book.id}")
        try {
            val staged: java.io.File? = when {
                book.filePath != null -> {
                    val storedPath = book.filePath!!
                    val source = if (java.io.File(storedPath).isAbsolute) {
                        java.io.File(storedPath)
                    } else {
                        storage.resolve(storedPath)
                    }
                    source.takeIf { it.isFile }
                }
                book.documentUri != null -> {
                    val target = java.io.File(scratch, "book.epub")
                    val copied = runCatching {
                        container.applicationContext.contentResolver
                            .openInputStream(
                                android.net.Uri.parse(book.documentUri!!)
                            )
                            ?.let { input ->
                                storage.copyStreamAtomic(input, target)
                            }
                    }.getOrNull()
                    if (copied != null) target else null
                }
                else -> null
            }
            val skeleton = staged?.let { file ->
                val workDir = java.io.File(scratch, "probe")
                    .apply { mkdirs() }
                (app.xreader.data.parse.EpubParser().probe(file, workDir)
                    as? AppResult.Ok)?.value
            }
            return app.xreader.data.prepare.EpubDetect(
                isFixedLayout = skeleton?.isFixedLayout ?: false,
                isRtlProgression = skeleton?.isRtlProgression ?: false,
                sessionHrefs = skeleton?.spineHrefs ?: emptyList()
            )
        } finally {
            runCatching { scratch.deleteRecursively() }
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
        // v1.7 OE-2: the Readium-locator rung that stood here
        // is gone — no session resolves EPUB locators now;
        // an EPUB's place is a core chapter index by the time
        // anything reads it.
        return (saved?.chapterIndex ?: 0)
            .coerceIn(0, (opened.sectionCount - 1).coerceAtLeast(0))
    }

    fun retryWithPassword(candidate: String) {
        password = candidate.takeIf { it.isNotEmpty() }
        open()
    }

    /**
     * v1.7 MR-1 (A4): the Failed state's "Try again" for a
     * converted-format book that landed nothing — resume the
     * import completion from the book's retained source, watch
     * it land, and hand the book to the core reader through
     * the same redirect door the EPUB prepare uses. A resume
     * that cannot start (no retained source) or that finishes
     * without landing returns to the honest Failed state, with
     * the retry still offered.
     *
     * [BookImporter.resumeCompletion] launches the completion
     * job and returns at once (true = a resumable source
     * exists); the landing is observed on
     * [ImportCompletionState], whose every terminal path
     * publishes running = false (success, failure, cancel).
     *
     * v1.7 OE-2 (R13): for an EPUB the same button re-runs
     * the prepare instead (the branch at the head of the
     * function) — one Try again shape, two honest retries.
     */
    fun resumeImport() {
        val current = _state.value as? LocalReaderState.Failed ?: return
        if (!current.canResumeImport) return
        // v1.7 OE-2 (R13): an EPUB's Try again re-runs the
        // prepare — open() routes an EPUB straight back into
        // the prepare host, which re-runs the one-time
        // prepare for a book with no landed record.
        if (book?.localFormat == BookFormat.EPUB) {
            open()
            return
        }
        _state.value = LocalReaderState.Loading("Preparing the book…")
        viewModelScope.launch(container.ioDispatcher) {
            val started = runCatching {
                container.bookImporter.resumeCompletion(bookId)
            }.getOrDefault(false)
            if (!started) {
                _state.value = LocalReaderState.Failed(
                    message = "That book couldn't be prepared — " +
                        "its file may have been moved or deleted.",
                    canResumeImport = true
                )
                return@launch
            }
            val finished = runCatching {
                ImportCompletionState.statusFlow(bookId)
                    .first { it != null && !it.running }
            }.getOrNull()
            val landedRows = runCatching {
                container.database.chapterDao().forBook(bookId)
            }.getOrDefault(emptyList())
            val landed = landedRows.any { it.contentPath != null }
            if (finished != null && !finished.failed && landed) {
                val savedIndex = runCatching {
                    container.database.readingProgressDao()
                        .getForBook(bookId)?.chapterIndex
                }.getOrNull() ?: 0
                _coreRedirect.tryEmit(
                    savedIndex.coerceIn(0, maxOf(0, landedRows.lastIndex))
                )
            } else {
                _state.value = LocalReaderState.Failed(
                    message = "That book couldn't be prepared.",
                    canResumeImport = true
                )
            }
        }
    }

    // ── Position reports from the surfaces ───────────────────────


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
        progressDirty = true
        _pdfProgress.value = PdfProgress(page = page, pageCount = pdf.sectionCount)
        scheduleSave()
    }

    // ── v1.5.7: settings updates + seeks ─────────────────────────
    // v1.7 OE-2: the EPUB half (updateEpubSettings,
    // setEpubUserFontAndReopen, seekEpubToFraction) is deleted
    // with the river; the PDF half stays as it was.

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
     * No pending save and no unsaved derived position (below,
     * item 0b) = no-op. The commit rides the IO dispatcher like
     * the VM's other store writes, so the caller's thread never
     * blocks on the database.
     */
    fun flushPendingProgressSave() {
        // v1.7 OE-2: the EPUB raw-locator re-derive that stood
        // here (Patch 2 item 0b) is gone with the locator
        // pipeline — the surviving position reports (PDF)
        // derive on every report and need no exit re-derive.
        val pending = saveJob
        val hadPending = pending != null && pending.isActive
        if (pending != null) {
            pending.cancel()
            saveJob = null
        }
        // v1.7 Patch 2 (item 0b): the flush fires not only for a
        // live debounce job but whenever the derived position is
        // newer than the stored row (progressDirty) — that
        // derivation must land even when its debounce was
        // already consumed. Every caller of this shared flush
        // (surface exit, Patch 1's memory-pressure flush) gets
        // the same rule.
        // Nothing pending and nothing newer = no-op, as before.
        if (!hadPending && !progressDirty) return
        viewModelScope.launch(container.ioDispatcher) {
            runCatching { saveProgressNow() }
        }
    }

    private suspend fun saveProgressNow() {
        val current = position ?: return
        val currentBook = book ?: return
        // The snapshot being written is no longer dirty; a
        // derivation landing after this point re-marks it.
        progressDirty = false
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
        // v1.7 OE-2: the EPUB locator-carrying jump that stood
        // here is gone — the plain index jump is the only
        // shape left, as it always was for PDF.
        _jumps.tryEmit(LocalJump(index))
    }

    /**
     * The page a bookmark names on THIS surface — its PDF
     * locator's page when it carries one; its raw
     * chapterIndex otherwise (the shipped fallback).
     */
    fun pdfBookmarkPage(bookmark: Bookmark): Int {
        LocatorJson.parsePdfLocator(bookmark.locatorJson)?.let {
            return it.first
        }
        return bookmark.chapterIndex
    }

    fun goToBookmark(bookmark: Bookmark) {
        val locatorJson = bookmark.locatorJson
        LocatorJson.parsePdfLocator(locatorJson)?.let {
            _jumps.tryEmit(LocalJump(it.first, locatorJson))
            return
        }
        // v1.7 OE-2: the Readium section-resolution rung is
        // gone; the bookmark's own chapterIndex is the target,
        // as it always was for non-PDF locators.
        _jumps.tryEmit(LocalJump(bookmark.chapterIndex, locatorJson))
    }

    /**
     * v1.5.9 (F-9): the seed a bookmark falls back to before the
     * first position lands — the Ready state's initial index +
     * the stored target JSON it was resolved from. Null only
     * when no seed exists either. (v1.7 OE-2: the seed's EPUB
     * locator arm is gone with the Ready state field it read.)
     */
    private fun bookmarkSeedPosition(): Position? {
        val ready = _state.value as? LocalReaderState.Ready ?: return null
        val locatorJson = ready.initialTargetJson ?: return null
        val count = ready.session.sectionCount
        val fraction = LocatorJson.parsePdfLocator(locatorJson)
            ?.let { (page, inPage) ->
                if (count > 0) (page + inPage) / count else 0f
            }
            ?: if (count > 0) ready.initialIndex.toFloat() / count else 0f
        return Position(
            index = ready.initialIndex,
            locatorJson = locatorJson,
            bookFraction = fraction.coerceIn(0f, 1f),
            inSectionFraction = LocatorJson.parsePdfLocator(locatorJson)
                ?.second ?: 0f,
            anchorTitle = when (val s = ready.session) {
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

    // v1.7 OE-2 (R29): the session in-book search that stood
    // here (search / goToSearchHit / the text memo) is
    // deleted — in-book search lives in the core reader now,
    // which is where every EPUB lands.

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
