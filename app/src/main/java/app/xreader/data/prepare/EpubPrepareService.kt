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
                    val source = storage.resolve(book.filePath!!)
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
                    oldChapterLengths = oldLengths
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
                now = now
            )

            // ── Item 2e: the session→core section map ─────────
            val bridge = EpubHrefBridge(parsed.chapterHrefs)
            val sectionMap = LinkedHashMap<Int, Long>()
            detect.sessionHrefs.forEachIndexed { sessionIndex, href ->
                val parserIndex = bridge.chapterForHref(href)
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
     */
    private suspend fun convertMarks(
        book: Book,
        parsed: ParsedBook,
        sessionHrefs: List<String>,
        landedRows: List<Chapter>,
        parserLengths: List<Int>,
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

        /** The parser chapter a locator-shaped mark names:
         * its locator href first, the session bridge second. */
        fun resolveChapter(
            locatorJson: String?,
            sessionIndex: Int
        ): Int? {
            LocatorJson.epubLocatorHref(locatorJson)?.let { href ->
                bridge.chapterForHref(href)?.let { return it }
            }
            return bridge.chapterForSessionIndex(sessionHrefs, sessionIndex)
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
            val parserIndex = resolveChapter(row.locatorJson, row.chapterIndex)
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
