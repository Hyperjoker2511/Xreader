package app.xreader.data.prepare

import android.content.ContentResolver
import android.net.Uri
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.BookmarkDao
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadState
import app.xreader.data.db.Highlight
import app.xreader.data.db.HighlightDao
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.db.SourceType
import app.xreader.data.engine.LocatorJson
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.files.BookStorage
import app.xreader.data.library.ReadingMemoryCodec
import app.xreader.data.parse.BookParser
import app.xreader.data.parse.EpubParser
import app.xreader.data.parse.ParsedBook
import app.xreader.data.parse.decodeChapter
import app.xreader.data.prefs.BookReaderPrefs
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.reader.BookDocument
import app.xreader.domain.reader.ChapterText
import app.xreader.ui.localreader.EpubPrepareRecord
import app.xreader.ui.localreader.LocalReaderPrefs
import app.xreader.ui.localreader.SelectionHighlightStore
import app.xreader.ui.localreader.StoredSelectionHighlight
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** v1.7 Patch 3 (Item 1b): what the detect session hands
 * prepare — fixed-layout and RTL-progression books are
 * refused the core road and keep the picture surface. */
data class EpubDetect(
    val isFixedLayout: Boolean,
    val isRtlProgression: Boolean,
    /** The detect session's reading-order hrefs (its own
     * index space — Item 3a's session-bridge input). */
    val sessionHrefs: List<String>
)

/** v1.7 Patch 3 (Item 1): prepare's verdict for one open. */
sealed interface EpubPrepareOutcome {
    /** Conversion landed and verified; the book belongs on
     * the core route at [chapterIndex] from now on. The
     * marks counts ride along for the record (Item 3e: the
     * unplaceable are counted, never silently dropped). */
    data class Redirect(
        val chapterIndex: Int,
        val marksConverted: Int = 0,
        val marksUnplaced: Int = 0
    ) : EpubPrepareOutcome

    /** Not eligible for the core road (fixed-layout / RTL /
     * no file to convert): the caller continues on the local
     * road exactly as before this patch. */
    data object NotEligible : EpubPrepareOutcome

    /** A non-DRM prepare failure (Item 1f): the book stays
     * on the local road for this open, unprepared and
     * retryable on a later open. Never less readable. */
    data class Fallback(val reason: String) : EpubPrepareOutcome

    /** DRM (Item 1b): content-blocking encryption refuses
     * honestly, with the converted road's own wording. */
    data class Refused(val message: String) : EpubPrepareOutcome
}

/**
 * v1.7 Patch 3 (Item 1): the EPUB loader for the shared
 * core. On a reflowable in-place EPUB's first open this runs
 * the existing [EpubParser]/XhtmlConverter over the book's
 * file and lands what a converted-chapter import lands —
 * Chapter rows, per-chapter ChapterContent JSON, extracted
 * image files — then migrates the place (Item 3) and the
 * marks (Item 3e), maps the settings (Item 4g), and writes
 * the prepare record LAST (Item 1e). From then on the book
 * IS a converted book on the novel core.
 *
 * Call off the main thread (the caller — the local reader's
 * open flow — already runs on the IO dispatcher). All
 * collaborators are interfaces or plain stores, so the whole
 * pipeline is JVM-testable with the tree's fakes.
 */
class EpubPrepareService(
    private val chapterDao: ChapterDao,
    private val readingProgressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val storage: BookStorage,
    private val prefs: LocalReaderPrefs,
    private val bookReaderPrefs: BookReaderPrefs,
    private val selectionHighlightStore: SelectionHighlightStore,
    private val contentResolver: ContentResolver?,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    // The 42-chapters fix: the Book row's totalChapters is
    // written at import registration from the RAW parse and
    // prepare used to never correct it, so a book segmented
    // at prepare (42 spine sections → 13 TOC chapters)
    // kept showing the raw count on its book page and
    // library cards. The service has no bookDao; the owner
    // of the row is told the landed count at each
    // successful exit and writes it back. Defaulted so
    // existing constructions (tests) are untouched.
    private val onChaptersLanded: suspend (
        bookId: Long, landedCount: Int
    ) -> Unit = { _, _ -> },
    private val parser: BookParser = EpubParser(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(
        book: Book,
        detect: EpubDetect,
        onProgress: (Float?) -> Unit = {}
    ): EpubPrepareOutcome {
        if (book.sourceType != SourceType.LOCAL_FILE ||
            book.localFormat != BookFormat.EPUB
        ) {
            return EpubPrepareOutcome.NotEligible
        }
        if (detect.isFixedLayout || detect.isRtlProgression) {
            return EpubPrepareOutcome.NotEligible
        }
        val oldRecord = runCatching { prefs.epubPrepareRecord(book.id) }
            .getOrNull()
        val saved = runCatching {
            readingProgressDao.getForBook(book.id)
        }.getOrNull()

        val scratch = storage.importStagingDir("epub-prepare-${book.id}")
        try {
            // ── Item 1c: staging ─────────────────────────────
            val staged: File
            val fingerprint: String
            when {
                book.filePath != null -> {
                    // Stored paths arrive in both forms — the
                    // importer writes them relative, older rows
                    // may carry an absolute path — and resolve()
                    // is relative-only (it would mangle an
                    // absolute one), so an absolute stored path
                    // is used exactly as stored.
                    val storedPath = book.filePath!!
                    val source = if (File(storedPath).isAbsolute) {
                        File(storedPath)
                    } else {
                        storage.resolve(storedPath)
                    }
                    if (!source.isFile) {
                        return EpubPrepareOutcome.Fallback(
                            "the book's file is not where it was"
                        )
                    }
                    staged = source
                    fingerprint = runCatching {
                        ReadingMemoryCodec.sha256Hex(source)
                    }.getOrElse {
                        return EpubPrepareOutcome.Fallback(
                            "the book's file couldn't be read"
                        )
                    }
                }
                book.documentUri != null -> {
                    val resolver = contentResolver
                        ?: return EpubPrepareOutcome.Fallback(
                            "no content resolver available"
                        )
                    val target = File(scratch, "book.epub")
                    val copied = runCatching {
                        resolver.openInputStream(Uri.parse(book.documentUri!!))
                            ?.let { input ->
                                storage.copyStreamAtomic(input, target)
                            }
                    }.getOrNull()
                        ?: return EpubPrepareOutcome.Fallback(
                            "the book's file couldn't be staged"
                        )
                    staged = target
                    fingerprint = copied.second
                }
                else -> return EpubPrepareOutcome.NotEligible
            }

            // ── Idempotent second open (Item 6): a current
            // record over this exact file, with landed content
            // in place, needs no conversion at all.
            if (oldRecord != null &&
                EpubPrepareAccess.isPrepared(oldRecord, book) &&
                oldRecord.fingerprint == fingerprint &&
                EpubPlaceMigration.isCoreShaped(saved)
            ) {
                val rows = chapterDao.forBook(book.id)
                if (rows.any { it.contentPath != null }) {
                    // The 42-chapters fix, healing leg: an
                    // already-prepared book reports its
                    // landed row count on this ordinary
                    // open — no re-prepare, no loader bump —
                    // so a stale raw-parse totalChapters
                    // corrects itself. Same basis as the
                    // book page's "n/m downloaded" chip:
                    // the book's chapter-row count.
                    runCatching {
                        onChaptersLanded(book.id, rows.size)
                    }
                    return EpubPrepareOutcome.Redirect(
                        (saved?.chapterIndex ?: 0)
                            .coerceIn(0, maxOf(0, rows.lastIndex))
                    )
                }
            }

            // ── Item 1d: conversion ──────────────────────────
            val parsed = when (
                val result = parser.parse(
                    staged, File(scratch, "work")
                ) { fraction -> onProgress(fraction) }
            ) {
                is AppResult.Err -> {
                    return if (result.error is AppError.DrmProtected) {
                        EpubPrepareOutcome.Refused(
                            ErrorText.of(AppError.DrmProtected).message
                        )
                    } else {
                        DiagnosticLog.record(
                            "epub-prepare",
                            "conversion failed for book ${book.id}: " +
                                result.error.toString()
                        )
                        EpubPrepareOutcome.Fallback(
                            "conversion failed: ${result.error}"
                        )
                    }
                }
                is AppResult.Ok -> result.value
            }
            if (parsed.chapters.isEmpty() || parsed.chapterHrefs.size != parsed.chapters.size) {
                DiagnosticLog.record(
                    "epub-prepare",
                    "conversion produced no chapters for book ${book.id}"
                )
                return EpubPrepareOutcome.Fallback("conversion produced no chapters")
            }
            // Item 1d: unresolvable images are counted by the
            // parser and surfaced, never silent — the count
            // rides the diagnostic log, the same honesty line
            // the importer prints for its own landings.
            if (parsed.droppedImages > 0) {
                DiagnosticLog.record(
                    "epub-prepare",
                    "book ${book.id}: ${parsed.droppedImages} picture(s) " +
                        "in the file couldn't be read"
                )
            }
            // Patch 1 (A6): narration overlays a chapter
            // merge could not carry (a merged chapter holds
            // one overlay; a second absorbed file narrating
            // over different audio loses its slot) — counted
            // on the same honesty line as dropped pictures.
            if (parsed.droppedOverlays > 0) {
                DiagnosticLog.record(
                    "epub-prepare",
                    "book ${book.id}: ${parsed.droppedOverlays} narration " +
                        "overlay(s) couldn't ride a merged chapter"
                )
            }

            // Converted text lengths, one chapter at a time —
            // no whole-book text is held (Item 1g).
            val parserLengths = parsed.chapters.map { meta ->
                decodeStaged(parsed, meta.contentFileName)?.let {
                    ChapterText(it).length
                } ?: 0
            }

            // The pre-reprepare landing, for Item 3d's scaling
            // and map inversion (empty on a first prepare).
            val oldRows = chapterDao.forBook(book.id)
            val oldLengths = HashMap<Long, Int>()
            if (oldRecord != null) {
                for (row in oldRows) {
                    if (row.contentPath != null) {
                        val content = runCatching {
                            storage.readChapterContent(book.id, row.id)
                        }.getOrNull()
                        if (content is AppResult.Ok) {
                            oldLengths[row.id] = ChapterText(content.value).length
                        }
                    }
                }
            }

            // ── Item 1d: landing — staged files + ONE
            // transaction (the importer's stated equivalent).
            val now = clock()
            val landedIds = ArrayList<Long>(parsed.chapters.size)
            try {
                inTransaction {
                    chapterDao.deleteForBook(book.id)
                    parsed.assetsDir?.listFiles()?.forEach { asset ->
                        storage.copyIntoContentDir(book.id, asset, asset.name)
                    }
                    parsed.chapters.forEachIndexed { index, meta ->
                        val chapterId = chapterDao.insert(
                            Chapter(
                                bookId = book.id,
                                index = index,
                                title = meta.title.ifBlank {
                                    parsed.chapterHrefs.getOrNull(index)
                                        ?.substringAfterLast('/')
                                        ?.substringBeforeLast('.')
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "Chapter ${index + 1}"
                                },
                                wordCount = meta.wordCount,
                                updatedAt = now
                            )
                        )
                        val relative = storage.chapterContentRelative(
                            book.id, chapterId
                        )
                        val dest = storage.resolve(relative)
                        dest.parentFile?.mkdirs()
                        File(parsed.chaptersDir, meta.contentFileName)
                            .copyTo(dest, overwrite = true)
                        chapterDao.setContent(
                            chapterId, relative, meta.wordCount,
                            DownloadState.READY
                        )
                        landedIds += chapterId
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                DiagnosticLog.record(
                    "epub-prepare",
                    "landing failed for book ${book.id}: ${e.javaClass.simpleName}"
                )
                return EpubPrepareOutcome.Fallback(
                    "landing failed: ${e.javaClass.simpleName}"
                )
            }
            pruneContentDir(book.id, parsed, landedIds)

            // ── Verification (Item 1e): every row and content
            // file checks out BEFORE anything else is written.
            if (!verifyLanding(book.id, parsed, landedIds)) {
                DiagnosticLog.record(
                    "epub-prepare",
                    "landing verification failed for book ${book.id}"
                )
                return EpubPrepareOutcome.Fallback("landing verification failed")
            }
            val landedRows = chapterDao.forBook(book.id)

            // ── Item 4g: the settings mapping ────────────────
            runCatching {
                val stored = prefs.epubSettings(book.id).first()
                val hasStored = prefs.hasStoredEpubSettings(book.id)
                EpubSettingsMapping.map(stored, hasStored)?.let { mapped ->
                    bookReaderPrefs.setPalette(book.id, mapped.paletteName)
                    bookReaderPrefs.setFontSize(book.id, mapped.fontSizeSp)
                }
            }

            // ── Item 3: the place migration ──────────────────
            val place = EpubPlaceMigration.resolvePlace(
                EpubPlaceMigration.Inputs(
                    saved = saved,
                    oldSectionMap = oldRecord?.sectionMap ?: emptyMap(),
                    sessionHrefs = detect.sessionHrefs,
                    parserHrefs = parsed.chapterHrefs,
                    parserTitles = parsed.chapters.map { it.title },
                    parserLengths = parserLengths,
                    oldChapterIdsInOrder = oldRows.map { it.id },
                    oldChapterLengths = oldLengths,
                    chapterMerge = parsed.chapterMerge,
                    preSegmentChapterHrefs = parsed.preSegmentChapterHrefs
                )
            )
            val landedIndex = place.chapterIndex
                .coerceIn(0, maxOf(0, landedRows.lastIndex))
            val landedRow = landedRows.getOrNull(landedIndex)
            if (saved != null && landedRow != null) {
                val doc = BookDocument(landedRows)
                landedRows.forEachIndexed { i, row ->
                    doc.setExactLength(row.id, parserLengths.getOrElse(i) { 0 })
                }
                val total = doc.totalChars
                val sectionLength = parserLengths
                    .getOrElse(landedIndex) { 0 }
                    .coerceAtLeast(1)
                runCatching {
                    readingProgressDao.upsert(
                        ReadingProgress(
                            bookId = book.id,
                            chapterId = landedRow.id,
                            chapterIndex = landedIndex,
                            charOffset = place.charOffset,
                            fraction = if (total > 0) {
                                doc.bookOffset(landedIndex, place.charOffset)
                                    .toFloat() / total.toFloat()
                            } else {
                                0f
                            },
                            scrollFraction = (
                                place.charOffset.toFloat() / sectionLength
                                ).coerceIn(0f, 1f),
                            updatedAt = now,
                            deviceId = saved.deviceId,
                            // Item 3c: the old locator stays as
                            // dormant provenance on the row.
                            locatorJson = saved.locatorJson,
                            anchorText = landedRow.title
                        )
                    )
                }
            }

            // ── Item 3e: the marks, converted IN PLACE ───────
            val marks = convertMarks(
                book = book,
                parsed = parsed,
                sessionHrefs = detect.sessionHrefs,
                landedRows = landedRows,
                parserLengths = parserLengths,
                oldChapterIdsInOrder = oldRows.map { it.id },
                now = now
            )

            // ── Item 2e: the session→core section map ─────────
            // Patch 1 (A7a): the bridge is built over the
            // PRE-merge hrefs and each hit is carried through
            // the merge map — a session section naming an
            // absorbed file lands on the merged chapter that
            // absorbed it (the chapter holding the file's
            // start), never on the Contents door's Continue
            // fallback. Unsegmented parses keep the shipped
            // road exactly (the pre-merge list is empty and
            // the map is the identity).
            val bridge = EpubHrefBridge(
                parsed.preSegmentChapterHrefs
                    .ifEmpty { parsed.chapterHrefs }
            )
            val sectionMap = LinkedHashMap<Int, Long>()
            detect.sessionHrefs.forEachIndexed { sessionIndex, href ->
                val oldIndex = bridge.chapterForHref(href)
                val parserIndex = oldIndex?.let { oi ->
                    if (parsed.chapterMerge.isEmpty()) {
                        oi
                    } else {
                        parsed.chapterMerge.firstOrNull {
                            it.oldChapterIndex == oi &&
                                it.oldStartOffset == 0
                        }?.newChapterIndex ?: oi
                    }
                }
                val chapterId = parserIndex?.let { landedIds.getOrNull(it) }
                if (chapterId != null) sectionMap[sessionIndex] = chapterId
            }

            // ── Item 1e: the record, written LAST ────────────
            prefs.saveEpubPrepareRecord(
                book.id,
                EpubPrepareRecord(
                    fingerprint = fingerprint,
                    loaderVersion = EpubPrepareAccess.LOADER_VERSION,
                    sectionMap = sectionMap,
                    notePending = saved != null || marks.converted > 0
                )
            )
            // The 42-chapters fix: the record is written
            // (above, still LAST among prepare's own
            // stores); now report the landed count so the
            // Book row's totalChapters matches the rows
            // this prepare just landed — the same basis
            // the book page's "n/m downloaded" chip counts
            // (the book's chapter-row count). Best-effort:
            // a failed report never fails the prepare.
            runCatching {
                onChaptersLanded(book.id, landedRows.size)
            }
            if (marks.unplaced > 0) {
                DiagnosticLog.record(
                    "epub-prepare",
                    "book ${book.id}: ${marks.unplaced} mark(s) could not " +
                        "be placed in the converted text and keep their " +
                        "old shape"
                )
            }
            return EpubPrepareOutcome.Redirect(
                chapterIndex = landedIndex,
                marksConverted = marks.converted,
                marksUnplaced = marks.unplaced
            )
        } finally {
            runCatching { scratch.deleteRecursively() }
        }
    }

    private fun decodeStaged(
        parsed: ParsedBook,
        contentFileName: String
    ): ChapterContent? = runCatching {
        json.decodeChapter(
            File(parsed.chaptersDir, contentFileName).readText()
        )
    }.getOrNull()

    /** Patch 1 (A1): whitespace-insensitive equality for
     * the highlight quote check — the same normalisation
     * findQuote applies (runs of whitespace collapse to
     * one space), so a quote spanning a line break still
     * verifies at its mapped span. */
    private fun whitespaceMatches(a: String, b: String): Boolean =
        a.replace(Regex("\\s+"), " ").trim() ==
            b.replace(Regex("\\s+"), " ").trim()

    /** After a landing, the content dir holds exactly the new
     * chapters' files + the copied assets; anything else there
     * (a previous landing's chapter files, translations of
     * replaced chapter ids) is stale derived data and goes. */
    private fun pruneContentDir(
        bookId: Long,
        parsed: ParsedBook,
        landedIds: List<Long>
    ) {
        runCatching {
            val keep = HashSet<String>()
            landedIds.forEach { keep += "$it.json" }
            parsed.assetsDir?.listFiles()?.forEach { keep += "img-${it.name}" }
            storage.contentDir(bookId).listFiles()?.forEach { file ->
                if (file.isFile && file.name !in keep) file.delete()
            }
        }
    }

    /** Item 1e's verification: the landed rows are complete
     * (count, contentPath, wordCount, title) and every content
     * file exists on disk. Internal so the record-last test
     * can drive it directly. */
    internal suspend fun verifyLanding(
        bookId: Long,
        parsed: ParsedBook,
        landedIds: List<Long>
    ): Boolean {
        val rows = chapterDao.forBook(bookId)
        if (rows.size != parsed.chapters.size) return false
        if (rows.map { it.id } != landedIds) return false
        for ((index, row) in rows.withIndex()) {
            val meta = parsed.chapters[index]
            val path = row.contentPath ?: return false
            if (row.wordCount != meta.wordCount) return false
            if (row.title.isBlank()) return false
            if (!storage.resolve(path).isFile) return false
        }
        // v1.7 one-engine (Item 5): the pictures verify too —
        // every asset the parser emitted (the same enumeration
        // the landing copy above and the prune keep-set use)
        // must exist at content/<bookId>/img-<name> with bytes
        // in it. A miss fails the landing honestly (prepare
        // falls back to the old road) instead of landing a
        // book with silent picture gaps.
        val assets = parsed.assetsDir?.listFiles().orEmpty()
        for (asset in assets) {
            val landed = storage.contentFile(bookId, "img-${asset.name}")
            if (!landed.isFile || landed.length() <= 0L) return false
        }
        return true
    }

    private data class MarksResult(val converted: Int, val unplaced: Int)

    /**
     * Item 3e: bookmarks and highlights convert IN PLACE —
     * the same row flipped to core shape (real
     * chapterId/chapterIndex, charOffset set, locatorJson
     * cleared), never a second converted row (the core VM
     * observes the tables unfiltered; a copy would show every
     * mark twice). The river's DataStore selection store is
     * READ ONCE here and never written: its EPUB entries
     * become new core Highlight rows. Marks that cannot be
     * placed keep their old shape and are counted.
     *
     * Patch 1 (A1): marks that are ALREADY core-shaped
     * (locatorJson == null — the owner's own bookmarks and
     * highlights from the core road) are remapped through
     * the segmentation merge map in the last two passes
     * below: the landing deleted the chapters they keyed
     * on, so without the remap a re-prepare orphans them.
     */
    private suspend fun convertMarks(
        book: Book,
        parsed: ParsedBook,
        sessionHrefs: List<String>,
        landedRows: List<Chapter>,
        parserLengths: List<Int>,
        oldChapterIdsInOrder: List<Long>,
        now: Long
    ): MarksResult {
        val bridge = EpubHrefBridge(parsed.chapterHrefs)
        var converted = 0
        var unplaced = 0
        // Bounded on purpose (Item 1g): quote searches reuse a
        // chapter's text across its marks, but the cache never
        // grows into a whole-book text — past the bound it
        // simply re-reads.
        val textCache = HashMap<Int, String>()
        fun chapterText(parserIndex: Int): String? {
            textCache[parserIndex]?.let { return it }
            val meta = parsed.chapters.getOrNull(parserIndex) ?: return null
            val text = decodeStaged(parsed, meta.contentFileName)?.let {
                ChapterText(it).text
            } ?: return null
            if (textCache.size >= 8) textCache.clear()
            textCache[parserIndex] = text
            return text
        }

        /** Patch 1 (A1): the merge span carrying a
         * PRE-merge place — the parser's own map when the
         * parse segmented, the identity span when it did
         * not (a re-prepare re-lands every chapter under
         * NEW ids either way, so core-shaped marks must be
         * re-pointed even when no chapter moved). */
        fun mergeSpan(
            oldIndex: Int,
            oldOffset: Int
        ): app.xreader.data.parse.ChapterMergeSpan? {
            if (parsed.chapterMerge.isEmpty()) {
                return if (oldIndex in landedRows.indices) {
                    app.xreader.data.parse.ChapterMergeSpan(
                        oldIndex, 0, oldIndex, 0
                    )
                } else {
                    null
                }
            }
            return parsed.chapterMerge
                .filter {
                    it.oldChapterIndex == oldIndex &&
                        it.oldStartOffset <= oldOffset
                }
                .maxByOrNull { it.oldStartOffset }
        }

        /** The parser chapter a locator-shaped mark names:
         * its locator href first, the session bridge second. */
        fun resolveChapter(
            locatorJson: String?,
            sessionIndex: Int?
        ): Int? {
            LocatorJson.epubLocatorHref(locatorJson)?.let { href ->
                bridge.chapterForHref(href)?.let { return it }
            }
            return sessionIndex?.let {
                bridge.chapterForSessionIndex(sessionHrefs, it)
            }
        }

        /** The mark's offset inside its converted chapter:
         * quote search when the mark carries words, else the
         * Item 3b fraction ladder. */
        fun resolveOffset(
            parserIndex: Int,
            quote: String?,
            locatorJson: String?,
            fallbackFraction: Float
        ): Int? {
            val length = parserLengths.getOrElse(parserIndex) { 0 }
            if (!quote.isNullOrBlank()) {
                val text = chapterText(parserIndex) ?: return null
                EpubPlaceMigration.findQuote(text, quote)?.let { return it }
            }
            if (length <= 0) return 0
            val fraction = LocatorJson.epubProgression(locatorJson)
                ?: fallbackFraction.takeIf { it > 0f }
                ?: 0f
            return (fraction.coerceIn(0f, 1f) * length).toInt()
                .coerceIn(0, length)
        }

        // Fix round 1: the CORE-shaped rows are snapshotted
        // BEFORE the locator passes below run. Those passes
        // flip their rows to core shape IN PLACE (locatorJson
        // cleared, merged-space chapterId/chapterIndex
        // written), so a core query issued after them would
        // match the just-converted rows too — bookmarks
        // remapped a SECOND time (merged-space keys fed to
        // the merge map as pre-merge space) and highlights
        // counted unplaced after being counted converted.
        // At entry the two shapes are disjoint (a row's
        // locatorJson is either null or not), so this
        // snapshot IS the core set for the whole call, on
        // first prepare and re-prepare alike.
        val coreBookmarkRows = runCatching {
            bookmarkDao.forBook(book.id)
        }.getOrDefault(emptyList())
            .filter { it.locatorJson == null }
        val coreHighlightRows = runCatching {
            highlightDao.forBook(book.id)
        }.getOrDefault(emptyList())
            .filter { it.locatorJson == null }

        // Bookmarks: DB rows, flipped in place.
        val bookmarks = runCatching { bookmarkDao.forBook(book.id) }
            .getOrDefault(emptyList())
            .filter { it.locatorJson != null }
        for (row in bookmarks) {
            val parserIndex = resolveChapter(row.locatorJson, row.chapterIndex)
            val landed = parserIndex?.let { landedRows.getOrNull(it) }
            val offset = if (parserIndex != null && landed != null) {
                resolveOffset(
                    parserIndex,
                    LocatorJson.epubLocatorQuote(row.locatorJson),
                    row.locatorJson,
                    fallbackFraction = 0f
                )
            } else {
                null
            }
            if (parserIndex != null && landed != null && offset != null) {
                runCatching {
                    bookmarkDao.update(
                        row.copy(
                            chapterId = landed.id,
                            chapterIndex = parserIndex,
                            charOffset = offset,
                            locatorJson = null,
                            updatedAt = now
                        )
                    )
                }
                converted++
            } else {
                unplaced++
            }
        }

        // Highlights: DB rows, flipped in place by quote.
        val highlights = runCatching { highlightDao.forBook(book.id) }
            .getOrDefault(emptyList())
            .filter { it.locatorJson != null }
        for (row in highlights) {
            // Highlight rows carry no chapterIndex column —
            // the href rung inside resolveChapter is their
            // bridge; a miss leaves the row unplaced (counted).
            val parserIndex = resolveChapter(row.locatorJson, null)
            val landed = parserIndex?.let { landedRows.getOrNull(it) }
            var placed = false
            if (parserIndex != null && landed != null) {
                val text = chapterText(parserIndex)
                val quoted = if (text != null && row.quotedText.isNotBlank()) {
                    EpubPlaceMigration.findQuote(text, row.quotedText)
                } else {
                    null
                }
                val start = quoted ?: resolveOffset(
                    parserIndex, null, row.locatorJson, fallbackFraction = 0f
                )
                if (start != null) {
                    val end = (start + row.quotedText.length)
                        .coerceIn(start, text?.length ?: start)
                    runCatching {
                        highlightDao.update(
                            row.copy(
                                chapterId = landed.id,
                                startOffset = start,
                                endOffset = end,
                                locatorJson = null,
                                updatedAt = now
                            )
                        )
                    }
                    converted++
                    placed = true
                }
            }
            if (!placed) unplaced++
        }

        // Patch 1 (A1): CORE-shaped bookmarks — the marks
        // the owner actually made on the core road (the
        // locator passes above never touch them). Their
        // chapterIndex is PRE-merge space and their
        // chapterId names a row the landing just deleted;
        // the merge span carries each one across. Bookmarks
        // carry no quote, so the arithmetic is the whole
        // road — exact by construction (A3's seams are in
        // the prefixes). Fix round 1: this iterates the
        // pre-pass snapshot, never a fresh query (see the
        // snapshot above for why).
        val coreBookmarks = coreBookmarkRows
        for (row in coreBookmarks) {
            val span = mergeSpan(row.chapterIndex, row.charOffset)
            val landed = span?.let {
                landedRows.getOrNull(it.newChapterIndex)
            }
            if (span != null && landed != null) {
                runCatching {
                    bookmarkDao.update(
                        row.copy(
                            chapterId = landed.id,
                            chapterIndex = span.newChapterIndex,
                            charOffset = span.charPrefix +
                                (row.charOffset - span.oldStartOffset),
                            updatedAt = now
                        )
                    )
                }
                converted++
            } else {
                unplaced++
            }
        }

        // Patch 1 (A1): CORE-shaped highlights — keyed by
        // chapterId alone (no chapterIndex column), so the
        // old landing's id order gives the pre-merge index.
        // Both offsets ride their merge spans; the quote is
        // the check — when the mapped span does not
        // reproduce the highlighted words, findQuote
        // re-anchors (Item 3e's machinery, reused).
        // Fix round 1: the pre-pass snapshot again — a
        // fresh query here would re-match the highlights
        // the locator pass just converted (their chapterId
        // is a landed id now, absent from
        // oldChapterIdsInOrder) and count them unplaced.
        val coreHighlights = coreHighlightRows
        for (row in coreHighlights) {
            val oldIndex = oldChapterIdsInOrder.indexOf(row.chapterId)
            val startSpan = if (oldIndex >= 0) {
                mergeSpan(oldIndex, row.startOffset)
            } else {
                null
            }
            val landed = startSpan?.let {
                landedRows.getOrNull(it.newChapterIndex)
            }
            var placed = false
            if (startSpan != null && landed != null) {
                var start = startSpan.charPrefix +
                    (row.startOffset - startSpan.oldStartOffset)
                var end = mergeSpan(oldIndex, row.endOffset)
                    ?.takeIf {
                        it.newChapterIndex == startSpan.newChapterIndex
                    }
                    ?.let { endSpan ->
                        endSpan.charPrefix +
                            (row.endOffset - endSpan.oldStartOffset)
                    }
                    ?: (start + row.quotedText.length)
                val text = chapterText(startSpan.newChapterIndex)
                if (text != null && row.quotedText.isNotBlank()) {
                    val atMapped = text.substring(
                        start.coerceIn(0, text.length),
                        (start + row.quotedText.length)
                            .coerceIn(0, text.length)
                    )
                    if (!whitespaceMatches(atMapped, row.quotedText)) {
                        EpubPlaceMigration.findQuote(
                            text, row.quotedText
                        )?.let { found ->
                            start = found
                            end = found + row.quotedText.length
                        }
                    }
                }
                val limit = text?.length ?: Int.MAX_VALUE
                end = end.coerceIn(start, maxOf(start, limit))
                runCatching {
                    highlightDao.update(
                        row.copy(
                            chapterId = landed.id,
                            startOffset = start,
                            endOffset = end,
                            updatedAt = now
                        )
                    )
                }
                converted++
                placed = true
            }
            if (!placed) unplaced++
        }

        // The river's selection store: read once, never
        // written again (Item 4d). EPUB entries (locator-set,
        // page-less) become NEW core Highlight rows — the
        // store has no core counterpart row to flip.
        val stored = runCatching {
            selectionHighlightStore.highlights(book.id).first()
        }.getOrDefault(emptyList())
            .filter { it.locatorJson != null && it.page == null }
        for (entry in stored) {
            val parserIndex = resolveChapter(entry.locatorJson, -1)
            val landed = parserIndex?.let { landedRows.getOrNull(it) }
            var placed = false
            if (parserIndex != null && landed != null) {
                val text = chapterText(parserIndex)
                val quoted = if (text != null && entry.selectedText.isNotBlank()) {
                    EpubPlaceMigration.findQuote(text, entry.selectedText)
                } else {
                    null
                }
                val start = quoted ?: resolveOffset(
                    parserIndex, null, entry.locatorJson, fallbackFraction = 0f
                )
                if (start != null) {
                    val end = (start + entry.selectedText.length)
                        .coerceIn(start, text?.length ?: start)
                    runCatching {
                        highlightDao.insert(
                            Highlight(
                                bookId = book.id,
                                chapterId = landed.id,
                                startOffset = start,
                                endOffset = end,
                                quotedText = entry.selectedText,
                                color = "amber",
                                note = entry.note.takeIf { it.isNotBlank() },
                                createdAt = entry.createdAtEpochMillis,
                                updatedAt = now,
                                locatorJson = null
                            )
                        )
                    }
                    converted++
                    placed = true
                }
            }
            if (!placed) unplaced++
        }
        return MarksResult(converted = converted, unplaced = unplaced)
    }
}
