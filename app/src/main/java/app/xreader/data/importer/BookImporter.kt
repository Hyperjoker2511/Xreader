package app.xreader.data.importer

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Bookmark
import app.xreader.data.db.BookmarkDao
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadState
import app.xreader.data.db.Highlight
import app.xreader.data.db.HighlightDao
import app.xreader.data.db.LocalOutline
import app.xreader.data.db.LocalOutlineDao
import app.xreader.data.db.OutlineTarget
import app.xreader.data.db.ReadingMemoryDao
import app.xreader.data.db.ReadingProgress
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.db.SourceType
import app.xreader.data.library.ReadingMemoryCodec
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.ArchiveLimits
import app.xreader.data.parse.EpubParser
import app.xreader.data.parse.FormatSniffer
import app.xreader.data.parse.ParsedBook
import app.xreader.data.parse.ParsedChapterMeta
import app.xreader.data.parse.ParserRegistry
import app.xreader.data.parse.PdfBookParser
import app.xreader.data.parse.PdfProse
import app.xreader.domain.content.ChapterBlock
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the import sheet shows before the user confirms. */
data class ImportCandidate(
    /** Staging token — the staging dir's name under import-staging/. */
    val token: String,
    val displayName: String,
    val sizeBytes: Long,
    /** Sniffed format; [BookFormat.UNKNOWN] when unrecognised. */
    val format: BookFormat,
    /** Plain-words reason when the format can't be imported. */
    val unsupportedReason: String?,
    /** v1.5.6 (§3.4(d)): the picked document's URI (SAF), when the
     * pick carried one — the in-place registration reads the book
     * where it lives through this URI. Null for OPDS downloads and
     * any non-URI flow (those keep the retained-copy path). */
    val documentUri: String? = null,
    /** v1.5.6 (§3.7): the staged bytes' SHA-256, captured from
     * [BookStorage.copyStreamAtomic]'s return — half of the
     * file fingerprint. Null only for candidates built by hand. */
    val contentHash: String? = null
)

/**
 * v1.5.5 (§4): the live state of one book's import completion —
 * the seam between the background completion job and the
 * reader. While [running] is true, chapters at or beyond
 * [landedChapters] are still being extracted: opening one is a
 * live "Preparing chapter…" state (see ChapterTextLoader),
 * never the hollow-chapter error. When the job finishes, the
 * final counts and the under-floor ("hollow") chapter indexes
 * stay published so the honesty lines — and the reader's
 * labelling of a stored heading-only chapter — can read them.
 * In-memory by design: it describes THIS process's job. (A book
 * whose completion was interrupted by process death keeps its
 * provisional rows and its source file; [BookImporter.resumeCompletion]
 * re-runs the job from that file.)
 */
object ImportCompletionState {

    data class Status(
        val bookId: Long,
        val running: Boolean,
        /** Final chapter count once known, provisional before. */
        val totalChapters: Int,
        /** Chapters landed so far, in order, from index 0. */
        val landedChapters: Int,
        val hollowChapterIndexes: Set<Int> = emptySet(),
        val droppedImages: Int = 0,
        val droppedChapters: Int = 0,
        val failed: Boolean = false
    )

    private val statuses =
        java.util.concurrent.ConcurrentHashMap<Long, MutableStateFlow<Status?>>()

    private fun flowOf(bookId: Long): MutableStateFlow<Status?> =
        statuses.getOrPut(bookId) { MutableStateFlow(null) }

    fun statusFlow(bookId: Long): StateFlow<Status?> = flowOf(bookId).asStateFlow()

    fun status(bookId: Long): Status? = flowOf(bookId).value

    /** True while [chapterIndex] of [bookId] awaits extraction. */
    fun isPreparing(bookId: Long, chapterIndex: Int): Boolean {
        val status = status(bookId) ?: return false
        return status.running && chapterIndex >= status.landedChapters
    }

    internal fun publish(status: Status) {
        flowOf(status.bookId).value = status
    }

    internal fun clear(bookId: Long) {
        flowOf(bookId).value = null
    }
}

/**
 * Book import pipeline (PLAN §4): picked stream → staging file
 * (content sniffed, never trusted by extension) → parser → Room rows
 * + content files. Runs entirely on IO; failures come back typed so
 * the UI shows plain-words errors, never a spinner that dies silently
 * (PLAN §0.2). Also used for OPDS acquisitions (the downloader hands
 * it the downloaded file).
 *
 * v1.5.5 (§4): PDF and EPUB imports are FILE-FIRST. A probe load
 * (PDF: classification + page count + metadata + outline; EPUB:
 * metadata + spine skeleton + cover) registers the Book row and
 * its provisional chapter rows in the Library immediately — the
 * book is visible and openable in seconds — and the heavy
 * completion (full extraction) runs as a background job on
 * [jobScope], an importer-owned supervisor scope in the app's
 * existing job model (the same pattern as the download engine's
 * app-lifetime scope): leaving the import screen cannot cancel
 * it, chapter 1 lands first, the rest stream in in batched
 * transactions, and [ImportCompletionState] publishes progress
 * for the reader's "Preparing chapter…" state. The book's own
 * source file is kept under books/<id>/ from registration on,
 * so an interrupted completion can be re-run by
 * [resumeCompletion]. Other formats keep the original
 * parse-then-land path.
 */
class BookImporter(
    private val storage: BookStorage,
    private val registry: ParserRegistry,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    /** v1.5.6 (§3): the v10 stores — reading-memory restore on
     * (re-)registration and the in-place outline. Nullable so
     * existing embeddings/tests that never exercise the in-place
     * path keep constructing the importer as before; production
     * (AppContainer) always wires the real DAOs. */
    private val readingMemoryDao: ReadingMemoryDao? = null,
    private val localOutlineDao: LocalOutlineDao? = null,
    private val readingProgressDao: ReadingProgressDao? = null,
    private val bookmarkDao: BookmarkDao? = null,
    private val highlightDao: HighlightDao? = null,
    /** v1.5.5 (§4): the completion jobs' scope — importer-owned
     * and supervisor-rooted, so a job outlives whichever screen
     * started the import. AppContainer's importer is a
     * singleton, so in production this is an app-lifetime scope. */
    private val jobScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** v1.2.0 (audit D9): runs a block of DAO writes as ONE
     * database transaction, so a whole import commits once.
     * AppContainer wires Room's withTransaction; tests and other
     * embeddings default to running the block directly. Declared
     * BEFORE [clock] on purpose: existing callers pass the clock
     * as a trailing lambda, and a trailing lambda binds to the
     * LAST function-type parameter (the DownloadController
     * pattern). */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit =
        { block -> block() },
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** A staged file larger than this is refused and its staging
     * dir deleted again (production value:
     * [ArchiveLimits.MAX_SOURCE_FILE_BYTES]). A var only so tests
     * can trip the cap with a small fixture; the parsers' own
     * expansion caps are the zip-bomb defence behind it. */
    internal var maxSourceFileBytes: Long = ArchiveLimits.MAX_SOURCE_FILE_BYTES

    /** v1.5.5 (§4): the PDF completion job's dispatcher — a
     * single lane (Amendments 2): one book's strip/image work
     * never parallelises against itself on a 2 GB device. */
    private val pdfJobDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val chapterJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    private fun stagingDir(token: String): File =
        File(File(storage.filesDir, "import-staging"), token)

    private fun stagedFile(token: String): File = File(stagingDir(token), "book.bin")

    /** Copies a picked file into staging and sniffs its real format.
     * v1.5.6 (§3.4(d)): [documentUri] is the picked document's URI
     * when the pick carried one; the (bytes, sha256) pair
     * [BookStorage.copyStreamAtomic] returns rides the candidate
     * as its content hash for the §3.7 fingerprint. */
    suspend fun stage(
        displayName: String,
        documentUri: String? = null,
        openStream: () -> InputStream?
    ): AppResult<ImportCandidate> = withContext(Dispatchers.IO) {
        val dir = storage.importStagingDir("pick")
        val token = dir.name
        val staged = File(dir, "book.bin")
        val hash: String
        try {
            val stream = openStream()
                ?: return@withContext AppResult.Err(
                    AppError.StorageFailed("open", "the file couldn't be opened")
                )
            hash = storage.copyStreamAtomic(stream, staged).second
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            return@withContext AppResult.Err(e.toAppError("copy"))
        }
        candidateFor(token, displayName, staged, documentUri, hash)
    }

    /** Stages an already-downloaded file (OPDS acquisition hand-off).
     * No document URI exists on this flow: documentUri stays null
     * and the retained-copy path files the book (§3.4(d)). */
    suspend fun stageDownloadedFile(
        displayName: String,
        file: File
    ): AppResult<ImportCandidate> = withContext(Dispatchers.IO) {
        val dir = storage.importStagingDir("opds")
        val token = dir.name
        val staged = File(dir, "book.bin")
        val hash: String
        try {
            hash = file.inputStream().buffered()
                .use { input -> storage.copyStreamAtomic(input, staged) }.second
        } catch (e: Exception) {
            dir.deleteRecursively()
            return@withContext AppResult.Err(e.toAppError("copy"))
        }
        candidateFor(token, displayName, staged, documentUri = null, contentHash = hash)
    }

    private fun candidateFor(
        token: String,
        displayName: String,
        staged: File,
        documentUri: String? = null,
        contentHash: String? = null
    ): AppResult<ImportCandidate> {
        if (staged.length() > maxSourceFileBytes) {
            stagingDir(token).deleteRecursively()
            return AppResult.Err(
                AppError.BadFile("that file is too large to import")
            )
        }
        // The ORIGINAL name decides extension-tied formats (MOBI vs
        // AZW3) — the staged copy is always named book.bin.
        val format = FormatSniffer.sniff(staged, displayName)
        return AppResult.Ok(
            ImportCandidate(
                token = token,
                displayName = displayName,
                sizeBytes = staged.length(),
                format = format,
                unsupportedReason = when {
                    format == BookFormat.UNKNOWN ->
                        "Xreader can't read this kind of file yet."
                    registry.parserFor(format) == null ->
                        "This format needs a component that isn't available."
                    else -> null
                },
                documentUri = documentUri,
                contentHash = contentHash
            )
        )
    }

    /** The §3.7 fingerprint for a candidate: displayName|size|sha256.
     * The hash is the one staging captured; a hand-built candidate
     * without one falls back to hashing the staged file itself. */
    private fun fingerprintFor(candidate: ImportCandidate, staged: File): String? {
        val hash = candidate.contentHash
            ?: runCatching { ReadingMemoryCodec.sha256Hex(staged) }.getOrNull()
            ?: return null
        return ReadingMemoryCodec.fingerprintOf(candidate.displayName, candidate.sizeBytes, hash)
    }

    /**
     * Parses a staged file and lands it in the library.
     *
     * v1.5.5 (§4): for PDF and EPUB this is the file-first
     * pipeline — probe, register, background completion — and
     * this call returns once the completion job has finished
     * (the job itself lives on [jobScope]: if the CALLER goes
     * away mid-import, the job still lands the book). Other
     * formats keep the original contract: the Room inserts
     * happen only after a full successful parse; if landing the
     * files fails midway, the book row and its files are removed
     * again — no half-books (PLAN §0.2).
     */
    suspend fun importStaged(
        candidate: ImportCandidate,
        onProgress: (Float?) -> Unit = {},
        /** v1.5.4 (WS-7): called once with the count of pictures
         * the file referenced that couldn't be read, when that
         * count is non-zero — the Adding-books honesty line. */
        onDroppedImages: (Int) -> Unit = {},
        /** v1.5.4 (WS-7, addendum C): the one-time unlock password
         * for a locked PDF. Used for this call only; never stored. */
        password: String? = null,
        /** v1.5.5 (§4): called once with the count of chapters
         * whose body fell under the prose floor, when non-zero —
         * the same honesty pattern as [onDroppedImages]. */
        onDroppedChapters: (Int) -> Unit = {}
    ): AppResult<Book> = withContext(Dispatchers.IO) {
        val format = candidate.format
        if (format == BookFormat.UNKNOWN) {
            return@withContext AppResult.Err(
                AppError.BadFile(candidate.unsupportedReason ?: "Unknown file type.")
            )
        }
        val parser = registry.parserFor(format)
            ?: return@withContext AppResult.Err(AppError.BadFile("This format isn't supported yet."))
        val staged = stagedFile(candidate.token)
        if (!staged.isFile) {
            return@withContext AppResult.Err(
                AppError.StorageFailed("import", "the picked file is gone")
            )
        }
        if (format == BookFormat.PDF || format == BookFormat.EPUB) {
            // v1.5.6 (§3.4(d)): a pick that carried a document URI
            // registers IN PLACE — no app copy, no chapter rows, no
            // completion job. The retained-copy file-first path
            // below serves every flow without a URI (OPDS above
            // all), exactly as before.
            if (candidate.documentUri != null) {
                return@withContext importInPlace(candidate, staged, password)
            }
            return@withContext importFileFirst(
                candidate = candidate,
                staged = staged,
                password = password,
                onProgress = onProgress,
                onDroppedImages = onDroppedImages,
                onDroppedChapters = onDroppedChapters
            )
        }
        val workDir = File(stagingDir(candidate.token), "work").apply { mkdirs() }
        var createdBook: Book? = null
        // v1.5.4 (WS-7, addendum C): a locked file keeps its staged
        // copy so the password retry has something to unlock;
        // every other outcome cleans staging up as before. The
        // card's Dismiss discards a kept copy (discardStaged).
        var keepStaging = false
        try {
            val parsed = when (val result =
                if (password != null && parser is PdfBookParser) {
                    parser.parseWithPassword(staged, workDir, password, onProgress)
                } else {
                    parser.parse(staged, workDir, onProgress)
                }
            ) {
                is AppResult.Err -> {
                    if (result.error is AppError.DrmProtected) keepStaging = true
                    return@withContext AppResult.Err(result.error)
                }
                is AppResult.Ok -> result.value
            }
            val now = clock()
            val fallbackTitle = candidate.displayName
                .substringBeforeLast('.', candidate.displayName)
                .ifBlank { "Untitled book" }
            // v1.5.6 (§3.7): retained-copy books are fingerprinted
            // too, so the delete → re-add memory works for them.
            val fingerprint = fingerprintFor(candidate, staged)
            var book = Book(
                title = parsed.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                author = parsed.author ?: "",
                description = parsed.description ?: "",
                sourceType = SourceType.LOCAL_FILE,
                localFormat = format,
                totalChapters = parsed.chapters.size,
                totalWords = parsed.chapters.sumOf { it.wordCount }.toLong(),
                addedAt = now,
                updatedAt = now,
                fileFingerprint = fingerprint
            )
            // v1.2.0 (audit D9): every Room write of the import —
            // the book row, two calls per chapter (insert +
            // setContent, the two-step the content path's chapter
            // id forces), and the cover update — lands in ONE
            // transaction. A 1,500-chapter EPUB used to cost
            // 3,000+ separate commits; now it commits once, and a
            // failure mid-import rolls the rows back whole (the
            // catch below still removes any files that landed).
            inTransaction {
                val bookId = bookDao.insert(book)
                book = book.copy(id = bookId)
                createdBook = book

                // Assets (images, overlay audio) into the content dir —
                // on disk they carry the img- prefix (§1.4 layout).
                parsed.assetsDir?.let { assets ->
                    assets.listFiles()?.forEach { asset ->
                        storage.copyIntoContentDir(bookId, asset, asset.name)
                    }
                }
                // Chapter rows + content files. The content path contains
                // the chapter id, so: insert → move file → setContent.
                parsed.chapters.forEachIndexed { index, meta ->
                    val chapterId = chapterDao.insert(
                        Chapter(
                            bookId = bookId,
                            index = index,
                            title = meta.title,
                            wordCount = meta.wordCount,
                            downloadState = DownloadState.READY,
                            updatedAt = now
                        )
                    )
                    val relative = storage.chapterContentRelative(bookId, chapterId)
                    val stagedChapter = File(parsed.chaptersDir, meta.contentFileName)
                    val dest = storage.resolve(relative)
                    dest.parentFile?.mkdirs()
                    if (stagedChapter.isFile) {
                        if (!stagedChapter.renameTo(dest)) {
                            stagedChapter.copyTo(dest, overwrite = true)
                            stagedChapter.delete()
                        }
                    }
                    chapterDao.setContent(chapterId, relative, meta.wordCount, DownloadState.READY)
                }
                // Cover.
                parsed.coverFile?.let { cover ->
                    val ext = cover.extension.ifBlank { "jpg" }
                    val dest = storage.resolve(storage.coverRelative(bookId, ext))
                    val copied = runCatching {
                        cover.copyTo(dest, overwrite = true)
                        true
                    }.getOrDefault(false)
                    if (copied) {
                        book = book.copy(coverPath = storage.coverRelative(bookId, ext))
                        bookDao.update(book)
                    }
                }
            }
            // v1.5.4 (WS-7): name the loss — an import that
            // dropped pictures must not present as complete.
            if (parsed.droppedImages > 0) {
                DiagnosticLog.record(
                    "import",
                    "Added, but ${parsed.droppedImages} picture(s) in this " +
                        "file couldn't be read",
                    book.title
                )
                onDroppedImages(parsed.droppedImages)
            }
            if (parsed.droppedChapters > 0) {
                DiagnosticLog.record(
                    "import",
                    "Added, but ${parsed.droppedChapters} chapter(s) in this " +
                        "file had no readable text",
                    book.title
                )
                onDroppedChapters(parsed.droppedChapters)
            }
            // v1.5.6 (§3.7): re-add = resume, on this path too.
            restoreReadingMemory(book.id, fingerprint, now)
            AppResult.Ok(bookDao.getById(book.id) ?: book)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createdBook?.let { doomed ->
                runCatching { bookDao.delete(doomed) }
                runCatching { storage.deleteBookFiles(doomed) }
            }
            AppResult.Err(e.toAppError("import"))
        } finally {
            if (!keepStaging) stagingDir(candidate.token).deleteRecursively()
        }
    }

    // ── v1.5.6 (§3): reading-memory restore + probe sharing ──────

    /** What one probe of a staged EPUB/PDF learns, shared by the
     * file-first registration and the in-place registration. */
    private data class ProbeOutcome(
        val title: String?,
        val author: String,
        val description: String,
        val coverFile: File?,
        val provisionalTitles: List<String>,
        /** Contents for an in-place book: EPUB {"section":i},
         * PDF {"page":p} from the probe's own outline (section
         * targets from the provisional list when the PDF carries
         * no outline). */
        val outline: List<OutlineTarget>
    )

    /**
     * The ONE probe seam (§3.1 row 6): all WS-7 password/DRM
     * classification lives in the parsers' probe calls used here —
     * a user-password file answers DrmProtected and NO book is
     * filed, exactly as the file-first pipeline has always done.
     * Password nuance (§3.4(d)): a user-password PDF registered in
     * place opens only with the password per open session (never
     * stored) — registration itself follows this probe contract
     * (a password supplied at import unlocks the probe; the book
     * is filed); the reader workstream owns the open-time prompt.
     */
    private fun probeStagedFile(
        format: BookFormat,
        parser: app.xreader.data.parse.BookParser,
        staged: File,
        workDir: File,
        password: String?,
        onLocked: () -> Unit
    ): AppResult<ProbeOutcome> {
        when (format) {
            BookFormat.PDF -> {
                val pdfParser = parser as? PdfBookParser
                    ?: return AppResult.Err(AppError.BadFile("This format isn't supported yet."))
                val probed = when (val result =
                    if (password != null) {
                        pdfParser.probeWithPassword(staged, workDir, password)
                    } else {
                        pdfParser.probe(staged, workDir)
                    }
                ) {
                    is AppResult.Err -> {
                        if (result.error is AppError.DrmProtected) onLocked()
                        return AppResult.Err(result.error)
                    }
                    is AppResult.Ok -> result.value
                }
                val outlineEntries = probed.outline
                    .filter { it.title.isNotBlank() }
                    .sortedBy { it.pageIndex }
                val outline = if (outlineEntries.isNotEmpty()) {
                    outlineEntries.map {
                        OutlineTarget(it.title, "{\"page\":${it.pageIndex}}")
                    }
                } else {
                    probed.provisionalTitles.mapIndexed { index, title ->
                        OutlineTarget(title, "{\"section\":$index}")
                    }
                }
                return AppResult.Ok(
                    ProbeOutcome(
                        title = probed.title,
                        author = probed.author ?: "",
                        description = probed.description ?: "",
                        coverFile = probed.coverFile,
                        provisionalTitles = probed.provisionalTitles,
                        outline = outline
                    )
                )
            }
            BookFormat.EPUB -> {
                val epubParser = parser as? EpubParser
                    ?: return AppResult.Err(AppError.BadFile("This format isn't supported yet."))
                val skeleton = when (val result = epubParser.probe(staged, workDir)) {
                    is AppResult.Err -> {
                        if (result.error is AppError.DrmProtected) onLocked()
                        return AppResult.Err(result.error)
                    }
                    is AppResult.Ok -> result.value
                }
                val titles = skeleton.chapterTitles.ifEmpty { listOf("Chapter 1") }
                return AppResult.Ok(
                    ProbeOutcome(
                        title = skeleton.title,
                        author = skeleton.author ?: "",
                        description = skeleton.description ?: "",
                        coverFile = skeleton.coverFile,
                        provisionalTitles = titles,
                        outline = titles.mapIndexed { index, title ->
                            OutlineTarget(title, "{\"section\":$index}")
                        }
                    )
                )
            }
            else -> return AppResult.Err(AppError.BadFile("This format isn't supported yet."))
        }
    }

    /** §3.7 re-add = resume: when a reading-memory record exists
     * for [fingerprint], its position, bookmarks and highlights
     * land on the freshly registered book [bookId]. Chapter ids
     * resolve by index where the book HAS chapter rows (retained
     * path); in-place books carry the locator alone (chapterId 0 —
     * the bookmarks/progress tables key their foreign key on the
     * book, never the chapter). No record → nothing happens. */
    private suspend fun restoreReadingMemory(bookId: Long, fingerprint: String?, now: Long) {
        if (fingerprint == null) return
        val memoryDao = readingMemoryDao ?: return
        val memory = runCatching { memoryDao.get(fingerprint) }.getOrNull() ?: return
        suspend fun chapterIdFor(chapterIndex: Int): Long =
            runCatching { chapterDao.getByIndex(bookId, chapterIndex)?.id }
                .getOrNull() ?: 0L
        val progressIndex = ReadingMemoryCodec.locatorIndex(memory.locatorJson) ?: 0
        readingProgressDao?.upsert(
            ReadingProgress(
                bookId = bookId,
                chapterId = chapterIdFor(progressIndex),
                chapterIndex = progressIndex,
                charOffset = 0,
                fraction = memory.fraction,
                scrollFraction = memory.fraction,
                updatedAt = now,
                locatorJson = memory.locatorJson
            )
        )
        bookmarkDao?.let { dao ->
            for (mark in ReadingMemoryCodec.decodeBookmarks(memory.bookmarksJson)) {
                dao.insert(
                    Bookmark(
                        bookId = bookId,
                        chapterId = chapterIdFor(mark.chapterIndex),
                        chapterIndex = mark.chapterIndex,
                        charOffset = mark.charOffset,
                        label = mark.label,
                        createdAt = now,
                        updatedAt = now,
                        locatorJson = mark.locatorJson
                    )
                )
            }
        }
        highlightDao?.let { dao ->
            for (light in ReadingMemoryCodec.decodeHighlights(memory.highlightsJson)) {
                dao.insert(
                    Highlight(
                        bookId = bookId,
                        chapterId = chapterIdFor(light.chapterIndex),
                        startOffset = light.startOffset,
                        endOffset = light.endOffset,
                        quotedText = light.quotedText,
                        color = light.color,
                        note = light.note,
                        createdAt = now,
                        updatedAt = now,
                        locatorJson = light.locatorJson
                    )
                )
            }
        }
    }

    // ── v1.5.6 (§3.4(d)): in-place registration ──────────────────

    /**
     * NEW imports with a document URI register the file WHERE IT
     * LIVES: probe the staged copy exactly as the file-first path
     * does (all WS-7 classification preserved), then file the Book
     * row (documentUri + fingerprint, filePath = null, cover
     * stored once from the probe) plus its local_outline rows —
     * NO chapter rows, NO completion job, NO source-file copy.
     * A matching reading-memory record restores place + bookmarks
     * (§3.7). Owns staging cleanup like the file-first job: a
     * locked file's staged copy survives for the password retry.
     */
    private suspend fun importInPlace(
        candidate: ImportCandidate,
        staged: File,
        password: String?
    ): AppResult<Book> {
        var keepStaging = false
        try {
            return importInPlaceBody(candidate, staged, password, onLocked = { keepStaging = true })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AppResult.Err(e.toAppError("import"))
        } finally {
            if (!keepStaging) {
                runCatching { stagingDir(candidate.token).deleteRecursively() }
            }
        }
    }

    private suspend fun importInPlaceBody(
        candidate: ImportCandidate,
        staged: File,
        password: String?,
        onLocked: () -> Unit
    ): AppResult<Book> {
        val format = candidate.format
        val parser = registry.parserFor(format)
            ?: return AppResult.Err(AppError.BadFile("This format isn't supported yet."))
        val workDir = File(stagingDir(candidate.token), "work").apply { mkdirs() }
        val probe = when (
            val result = probeStagedFile(format, parser, staged, workDir, password, onLocked)
        ) {
            is AppResult.Err -> return AppResult.Err(result.error)
            is AppResult.Ok -> result.value
        }
        val fallbackTitle = candidate.displayName
            .substringBeforeLast('.', candidate.displayName)
            .ifBlank { "Untitled book" }
        val fingerprint = fingerprintFor(candidate, staged)
        val now = clock()
        var book = Book(
            title = probe.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            author = probe.author,
            description = probe.description,
            sourceType = SourceType.LOCAL_FILE,
            localFormat = format,
            totalChapters = probe.outline.size,
            totalWords = 0,
            addedAt = now,
            updatedAt = now,
            documentUri = candidate.documentUri,
            fileFingerprint = fingerprint
        )
        var bookId = 0L
        try {
            inTransaction {
                bookId = bookDao.insert(book)
                book = book.copy(id = bookId)
                localOutlineDao?.insertAll(
                    probe.outline.mapIndexed { index, target ->
                        LocalOutline(
                            bookId = bookId,
                            idx = index,
                            title = target.title,
                            targetJson = target.targetJson
                        )
                    }
                )
            }
        } catch (e: Exception) {
            return AppResult.Err(e.toAppError("import"))
        }
        // Cover: stored once from the probe, as-is (never re-encoded).
        try {
            probe.coverFile?.let { cover ->
                val ext = cover.extension.ifBlank { "jpg" }
                val dest = storage.resolve(storage.coverRelative(bookId, ext))
                val copied = runCatching {
                    cover.copyTo(dest, overwrite = true)
                    true
                }.getOrDefault(false)
                if (copied) {
                    book = book.copy(coverPath = storage.coverRelative(bookId, ext))
                    bookDao.update(book)
                }
            }
        } catch (e: Exception) {
            runCatching { bookDao.delete(book) }
            runCatching { storage.deleteBookFiles(book) }
            return AppResult.Err(e.toAppError("import"))
        }
        restoreReadingMemory(bookId, fingerprint, now)
        return AppResult.Ok(bookDao.getById(bookId) ?: book)
    }

    /**
     * §3.4(b) re-add data path: attach a document to an EXISTING
     * book row (the book page's "Re-add the original file" offer;
     * its UI is the reader workstream's). The row gains the
     * documentUri + fingerprint + outline; its progress/bookmark
     * rows are the book's own and stay untouched.
     */
    suspend fun attachDocument(
        bookId: Long,
        documentUri: String,
        fingerprint: String?,
        outline: List<OutlineTarget>
    ): AppResult<Book> = withContext(Dispatchers.IO) {
        val book = bookDao.getById(bookId)
            ?: return@withContext AppResult.Err(
                AppError.BadFile("that book is no longer in the library")
            )
        try {
            inTransaction {
                localOutlineDao?.deleteForBook(bookId)
                localOutlineDao?.insertAll(
                    outline.mapIndexed { index, target ->
                        LocalOutline(
                            bookId = bookId,
                            idx = index,
                            title = target.title,
                            targetJson = target.targetJson
                        )
                    }
                )
                bookDao.update(
                    book.copy(
                        documentUri = documentUri,
                        fileFingerprint = fingerprint ?: book.fileFingerprint,
                        totalChapters = if (outline.isNotEmpty()) {
                            outline.size
                        } else {
                            book.totalChapters
                        },
                        updatedAt = clock()
                    )
                )
            }
            AppResult.Ok(bookDao.getById(bookId) ?: book)
        } catch (e: Exception) {
            AppResult.Err(e.toAppError("import"))
        }
    }

    /**
     * The probed half of the §3.4(b) re-add: probe the staged
     * candidate exactly as an in-place registration would, then
     * [attachDocument] onto the existing row. The candidate must
     * carry the re-picked file's documentUri. Owns staging
     * cleanup (a locked file's copy survives for a password
     * retry, as everywhere).
     */
    suspend fun registerReAdd(
        bookId: Long,
        candidate: ImportCandidate,
        password: String? = null
    ): AppResult<Book> = withContext(Dispatchers.IO) {
        val documentUri = candidate.documentUri
            ?: return@withContext AppResult.Err(
                AppError.BadFile("the re-added file has no document to point at")
            )
        val staged = stagedFile(candidate.token)
        if (!staged.isFile) {
            return@withContext AppResult.Err(
                AppError.StorageFailed("import", "the picked file is gone")
            )
        }
        val parser = registry.parserFor(candidate.format)
            ?: return@withContext AppResult.Err(
                AppError.BadFile("This format isn't supported yet.")
            )
        var keepStaging = false
        try {
            val workDir = File(stagingDir(candidate.token), "work").apply { mkdirs() }
            val probe = when (
                val result = probeStagedFile(
                    candidate.format, parser, staged, workDir, password,
                    onLocked = { keepStaging = true }
                )
            ) {
                is AppResult.Err -> return@withContext AppResult.Err(result.error)
                is AppResult.Ok -> result.value
            }
            attachDocument(
                bookId = bookId,
                documentUri = documentUri,
                fingerprint = fingerprintFor(candidate, staged),
                outline = probe.outline
            )
        } finally {
            if (!keepStaging) {
                runCatching { stagingDir(candidate.token).deleteRecursively() }
            }
        }
    }

    // ── v1.5.5 (§4): file-first import (PDF + EPUB) ─────────────────

    /**
     * The file-first pipeline: launches the probe → register →
     * completion job on [jobScope] and awaits its outcome. The
     * job is NOT a child of this call — cancelling the caller
     * (leaving the import screen) leaves the job running, and
     * the book lands regardless.
     */
    private suspend fun importFileFirst(
        candidate: ImportCandidate,
        staged: File,
        password: String?,
        onProgress: (Float?) -> Unit,
        onDroppedImages: (Int) -> Unit,
        onDroppedChapters: (Int) -> Unit
    ): AppResult<Book> {
        val outcome = CompletableDeferred<AppResult<Book>>()
        val dispatcher =
            if (candidate.format == BookFormat.PDF) pdfJobDispatcher else Dispatchers.IO
        jobScope.launch(dispatcher) {
            val result = try {
                runFileFirstJob(
                    candidate, staged, password,
                    onProgress, onDroppedImages, onDroppedChapters
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.Err(e.toAppError("import"))
            }
            outcome.complete(result)
        }
        // The JOB owns staging cleanup (in runFileFirstJob's
        // finally): a locked file's staged copy survives for the
        // password retry, every other outcome's is deleted when
        // the job ends — which may be after this call returns if
        // the caller was cancelled first.
        return outcome.await()
    }

    /**
     * The completion job's body: probe, register the book with
     * provisional chapters, then complete it. Runs on [jobScope];
     * every step after registration works from the book's OWN
     * copy of the source file (books/<id>/), never from staging,
     * so staging cleanup and process death can't strand it —
     * [resumeCompletion] re-enters at the completion step.
     */
    private suspend fun runFileFirstJob(
        candidate: ImportCandidate,
        staged: File,
        password: String?,
        onProgress: (Float?) -> Unit,
        onDroppedImages: (Int) -> Unit,
        onDroppedChapters: (Int) -> Unit
    ): AppResult<Book> {
        // v1.5.4 (WS-7, addendum C): a locked file keeps its
        // staged copy so the password retry has something to
        // unlock; every other outcome cleans staging up when
        // the job ends. The card's Dismiss discards a kept copy.
        var keepStaging = false
        try {
            return runFileFirstJobBody(
                candidate, staged, password,
                onProgress, onDroppedImages, onDroppedChapters,
                onLocked = { keepStaging = true }
            )
        } finally {
            if (!keepStaging) {
                runCatching { stagingDir(candidate.token).deleteRecursively() }
            }
        }
    }

    private suspend fun runFileFirstJobBody(
        candidate: ImportCandidate,
        staged: File,
        password: String?,
        onProgress: (Float?) -> Unit,
        onDroppedImages: (Int) -> Unit,
        onDroppedChapters: (Int) -> Unit,
        onLocked: () -> Unit
    ): AppResult<Book> {
        val format = candidate.format
        val parser = registry.parserFor(format)
            ?: return AppResult.Err(AppError.BadFile("This format isn't supported yet."))
        val workDir = File(stagingDir(candidate.token), "work").apply { mkdirs() }
        val fallbackTitle = candidate.displayName
            .substringBeforeLast('.', candidate.displayName)
            .ifBlank { "Untitled book" }

        // ── Probe ──────────────────────────────────────────────
        // ALL WS-7 guarantees live in the shared probe seam
        // (probeStagedFile): the probe classifies encryption on
        // the first open (owner-password opens, user-password →
        // DrmProtected with NO book filed, EPUB vendor DRM →
        // DrmProtected), exactly as the old full-parse-first
        // pipeline surfaced them.
        val probe = when (
            val result = probeStagedFile(format, parser, staged, workDir, password, onLocked)
        ) {
            is AppResult.Err -> return AppResult.Err(result.error)
            is AppResult.Ok -> result.value
        }
        val probeTitle = probe.title
        val probeAuthor = probe.author
        val probeDescription = probe.description
        val probeCover = probe.coverFile
        val provisionalTitles = probe.provisionalTitles
        onProgress(null)

        // ── Registration ───────────────────────────────────────
        // The book row, its provisional chapter rows (no content
        // yet — the reader's Preparing state covers them) and
        // the cover land NOW, in one transaction; the source
        // file moves into the book's own directory alongside.
        val now = clock()
        // v1.5.6 (§3.7): retained-copy books carry the fingerprint
        // from registration on, so §3.7 memory works for them too.
        val fingerprint = fingerprintFor(candidate, staged)
        var book = Book(
            title = probeTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            author = probeAuthor,
            description = probeDescription,
            sourceType = SourceType.LOCAL_FILE,
            localFormat = format,
            totalChapters = provisionalTitles.size,
            totalWords = 0,
            addedAt = now,
            updatedAt = now,
            fileFingerprint = fingerprint
        )
        var bookId = 0L
        try {
            inTransaction {
                bookId = bookDao.insert(book)
                book = book.copy(id = bookId)
                chapterDao.insertAll(
                    provisionalTitles.mapIndexed { index, title ->
                        Chapter(
                            bookId = bookId,
                            index = index,
                            title = title,
                            wordCount = 0,
                            downloadState = DownloadState.NONE,
                            updatedAt = now
                        )
                    }
                )
                // v1.5.6 (§1.5a): the preparation status is
                // published INSIDE the registration transaction,
                // before the rows can become visible at commit —
                // a provisional row must never be observable
                // without its status (the first-open "no source
                // is recorded" race). Cleared in the catch below
                // if the transaction fails.
                ImportCompletionState.publish(
                    ImportCompletionState.Status(
                        bookId = bookId,
                        running = true,
                        totalChapters = provisionalTitles.size,
                        landedChapters = 0
                    )
                )
            }
        } catch (e: Exception) {
            if (bookId != 0L) ImportCompletionState.clear(bookId)
            return AppResult.Err(e.toAppError("import"))
        }
        // The book's own copy of the source (the completion's
        // input and the resume path's lifeline).
        val sourceName = if (format == BookFormat.PDF) "book.pdf" else "book.epub"
        val sourceRelative = storage.bookFileRelative(bookId, sourceName)
        try {
            val dest = storage.resolve(sourceRelative)
            dest.parentFile?.mkdirs()
            staged.copyTo(dest, overwrite = true)
            var updated = book.copy(filePath = sourceRelative)
            probeCover?.let { cover ->
                val ext = cover.extension.ifBlank { "jpg" }
                val coverDest = storage.resolve(storage.coverRelative(bookId, ext))
                val copied = runCatching {
                    cover.copyTo(coverDest, overwrite = true)
                    true
                }.getOrDefault(false)
                if (copied) updated = updated.copy(coverPath = storage.coverRelative(bookId, ext))
            }
            book = updated
            bookDao.update(book)
        } catch (e: Exception) {
            ImportCompletionState.clear(bookId)
            runCatching { bookDao.delete(book) }
            runCatching { storage.deleteBookFiles(book) }
            return AppResult.Err(e.toAppError("import"))
        }
        // v1.5.6 (§1.5a): no second publish here — the status was
        // already published inside the registration transaction
        // above, before the source-file copy. Publishing only here
        // (after the copy) was the race window.

        // ── Completion ─────────────────────────────────────────
        val completed = completeRegisteredBook(
            book = book,
            sourceFile = storage.resolve(sourceRelative),
            workDir = File(workDir, "completion").apply { mkdirs() },
            password = password,
            onProgress = onProgress,
            onDroppedImages = onDroppedImages,
            onDroppedChapters = onDroppedChapters
        )
        // v1.5.6 (§3.7): re-add = resume on the retained path too —
        // a memory record for this fingerprint restores place and
        // bookmarks onto the completed book.
        if (completed is AppResult.Ok) {
            restoreReadingMemory(bookId, fingerprint, clock())
            return AppResult.Ok(bookDao.getById(bookId) ?: completed.value)
        }
        return completed
    }

    /**
     * v1.5.5 (§4): re-run the completion for a book whose job
     * was interrupted (process death above all). The book keeps
     * whatever chapters already landed; the completion replaces
     * the chapter set from the book's own source file, exactly
     * as the original job would have. A book whose file needed
     * a password cannot resume this way (the password was never
     * stored — its completion reports the locked state and the
     * book is left as-is for a fresh import attempt). Returns
     * false when the book has no resumable local source.
     */
    suspend fun resumeCompletion(bookId: Long): Boolean {
        val book = bookDao.getById(bookId) ?: return false
        if (book.sourceType != SourceType.LOCAL_FILE) return false
        val relative = book.filePath ?: return false
        val source = storage.resolve(relative)
        if (!source.isFile) return false
        val workDir = storage.importStagingDir("resume-$bookId")
        ImportCompletionState.publish(
            ImportCompletionState.Status(
                bookId = bookId,
                running = true,
                totalChapters = book.totalChapters,
                landedChapters = 0
            )
        )
        val dispatcher =
            if (book.localFormat == BookFormat.PDF) pdfJobDispatcher else Dispatchers.IO
        jobScope.launch(dispatcher) {
            try {
                completeRegisteredBook(
                    book = book,
                    sourceFile = source,
                    workDir = File(workDir, "completion").apply { mkdirs() },
                    password = null,
                    onProgress = {},
                    onDroppedImages = {},
                    onDroppedChapters = {},
                    removeOnFailure = false
                )
            } finally {
                workDir.deleteRecursively()
            }
        }
        return true
    }

    /**
     * The completion proper, shared by the import job and
     * [resumeCompletion]: parse the book's source file and land
     * its chapters over the provisional set — chapter 1 first
     * (its own transaction, replacing the provisional rows),
     * the rest in batched transactions — then finalise the
     * book row and the honesty counts.
     *
     * A parse failure REMOVES the registered book again (the
     * old pipeline's no-half-books rule): a scanned PDF the
     * probe couldn't judge must not leave a hollow shell in
     * the Library posing as a book.
     */
    private suspend fun completeRegisteredBook(
        book: Book,
        sourceFile: File,
        workDir: File,
        password: String?,
        onProgress: (Float?) -> Unit,
        onDroppedImages: (Int) -> Unit,
        onDroppedChapters: (Int) -> Unit,
        /** True for a fresh import (a failed completion removes
         * the just-registered book — no half-books); false for a
         * resume (the book is the user's, a failed resume only
         * reports, never deletes). */
        removeOnFailure: Boolean = true
    ): AppResult<Book> {
        val bookId = book.id
        val parser = registry.parserFor(book.localFormat)
            ?: return failCompletion(
                book, AppError.BadFile("This format isn't supported yet."), removeOnFailure
            )
        val landing = ChapterLanding(book)
        try {
            val parsed: ParsedBook
            if (parser is PdfBookParser) {
                // Progressive commit: the parser writes chapter
                // files one by one; the channel hands each meta
                // to the landing consumer, so chapter 1 is in
                // Room while later chapters are still being
                // assembled. (EPUB's parser produces its metas
                // at the end of conversion; its landing below
                // still commits chapter 1 first, then batches.)
                val channel = Channel<ParsedChapterMeta>(Channel.UNLIMITED)
                parser.chapterWrittenListener = { meta -> channel.trySend(meta) }
                val landingJob = jobScope.async {
                    for (meta in channel) {
                        landing.offer(meta, workDir)
                    }
                }
                val result = try {
                    if (password != null) {
                        parser.parseWithPassword(sourceFile, workDir, password, onProgress)
                    } else {
                        parser.parse(sourceFile, workDir, onProgress)
                    }
                } finally {
                    parser.chapterWrittenListener = null
                    channel.close()
                }
                // A parse failure's staged chapters were already
                // consumed by the landing — failCompletion removes
                // the whole book, landed rows included.
                landingJob.await()
                parsed = when (result) {
                    is AppResult.Err -> return failCompletion(book, result.error, removeOnFailure)
                    is AppResult.Ok -> result.value
                }
                landing.bindParsed(parsed)
                landing.finish()
            } else {
                val result = parser.parse(sourceFile, workDir, onProgress)
                parsed = when (result) {
                    is AppResult.Err -> return failCompletion(book, result.error, removeOnFailure)
                    is AppResult.Ok -> result.value
                }
                landing.bindParsed(parsed)
                for (meta in parsed.chapters) {
                    landing.offer(meta, workDir)
                }
                landing.finish()
            }
            // Finalise: totals, the description the completion
            // learned, and the cover when registration had none
            // (a PDF whose dominant plate only the full image
            // walk could find).
            var updated = book.copy(
                totalChapters = parsed.chapters.size,
                totalWords = parsed.chapters.sumOf { it.wordCount }.toLong(),
                description = parsed.description?.takeIf { it.isNotBlank() }
                    ?: book.description,
                updatedAt = clock()
            )
            if (updated.coverPath == null && parsed.coverFile != null) {
                val cover = parsed.coverFile
                val ext = cover.extension.ifBlank { "jpg" }
                val dest = storage.resolve(storage.coverRelative(bookId, ext))
                val copied = runCatching {
                    cover.copyTo(dest, overwrite = true)
                    true
                }.getOrDefault(false)
                if (copied) {
                    updated = updated.copy(coverPath = storage.coverRelative(bookId, ext))
                }
            }
            bookDao.update(updated)
            ImportCompletionState.publish(
                ImportCompletionState.Status(
                    bookId = bookId,
                    running = false,
                    totalChapters = parsed.chapters.size,
                    landedChapters = parsed.chapters.size,
                    hollowChapterIndexes = landing.hollowIndexes,
                    droppedImages = parsed.droppedImages,
                    droppedChapters = parsed.droppedChapters
                )
            )
            if (parsed.droppedImages > 0) {
                DiagnosticLog.record(
                    "import",
                    "Added, but ${parsed.droppedImages} picture(s) in this " +
                        "file couldn't be read",
                    book.title
                )
                onDroppedImages(parsed.droppedImages)
            }
            if (parsed.droppedChapters > 0) {
                DiagnosticLog.record(
                    "import",
                    "Added, but ${parsed.droppedChapters} chapter(s) in this " +
                        "file had no readable text",
                    book.title
                )
                onDroppedChapters(parsed.droppedChapters)
            }
            return AppResult.Ok(bookDao.getById(bookId) ?: updated)
        } catch (e: CancellationException) {
            ImportCompletionState.publish(
                ImportCompletionState.Status(
                    bookId = bookId,
                    running = false,
                    totalChapters = book.totalChapters,
                    landedChapters = landing.landedCount,
                    failed = true
                )
            )
            throw e
        } catch (e: Exception) {
            return failCompletion(book, e.toAppError("import"), removeOnFailure)
        }
    }

    /** A failed completion removes the registered book whole —
     * rows and files — and reports the failure honestly. */
    private suspend fun failCompletion(
        book: Book,
        error: AppError,
        removeBook: Boolean
    ): AppResult<Book> {
        ImportCompletionState.publish(
            ImportCompletionState.Status(
                bookId = book.id,
                running = false,
                totalChapters = book.totalChapters,
                landedChapters = 0,
                failed = true
            )
        )
        if (removeBook) {
            runCatching { bookDao.delete(book) }
            runCatching { storage.deleteBookFiles(book) }
            ImportCompletionState.clear(book.id)
        }
        return AppResult.Err(error)
    }

    /**
     * v1.5.5 (§4): progressive, batched landing of a completion's
     * chapters over the book's provisional rows.
     *
     * The FIRST chapter replaces the whole provisional set in
     * its own transaction (the reader may already be waiting on
     * a provisional row — it re-resolves by index). The rest
     * accumulate and commit [BATCH_SIZE] at a time, one
     * transaction per batch — never one transaction per chapter
     * in a hot loop. Assets and the per-chapter hollow check
     * (PDF prose floor, read back off the staged chapter file)
     * ride the first chapter's landing.
     */
    private inner class ChapterLanding(private val book: Book) {
        private val now: Long = clock()
        private var parsed: ParsedBook? = null
        private var nextIndex = 0
        private var assetsCopied = false
        private val batch = mutableListOf<ParsedChapterMeta>()

        var landedCount = 0
            private set
        val hollowIndexes = mutableSetOf<Int>()

        fun bindParsed(value: ParsedBook) {
            parsed = value
        }

        suspend fun offer(meta: ParsedChapterMeta, workDir: File) {
            lastWorkDir = workDir
            if (nextIndex == 0) {
                landBatch(listOf(meta), replaceExisting = true, workDir = workDir)
            } else {
                batch += meta
                if (batch.size >= BATCH_SIZE) flushBatch()
            }
            nextIndex++
        }

        suspend fun finish() {
            flushBatch()
        }

        private var lastWorkDir: File? = null

        private suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val pending = batch.toList()
            batch.clear()
            landBatch(
                pending, replaceExisting = false,
                workDir = lastWorkDir ?: error("landing batch without a work dir")
            )
        }

        private suspend fun landBatch(
            metas: List<ParsedChapterMeta>,
            replaceExisting: Boolean,
            workDir: File
        ) {
            val currentParsed = parsed
            // Before bindParsed (the PDF streaming path, mid-
            // parse) the staged chapters live under the job's
            // work dir by the parser's own convention; after it,
            // the ParsedBook names the dir itself.
            val chaptersDir = currentParsed?.chaptersDir ?: File(workDir, "chapters")
            val assetsDir = currentParsed?.assetsDir
                ?: File(workDir, "assets").takeIf { it.isDirectory }
            inTransaction {
                if (replaceExisting) {
                    chapterDao.forBook(book.id).forEach { chapterDao.delete(it) }
                }
                if (!assetsCopied) {
                    assetsCopied = true
                    assetsDir?.let { assets ->
                        assets.listFiles()?.forEach { asset ->
                            storage.copyIntoContentDir(book.id, asset, asset.name)
                        }
                    }
                }
                for (meta in metas) {
                    val index = landedCount
                    val chapterId = chapterDao.insert(
                        Chapter(
                            bookId = book.id,
                            index = index,
                            title = meta.title,
                            wordCount = meta.wordCount,
                            downloadState = DownloadState.READY,
                            updatedAt = now
                        )
                    )
                    val relative = storage.chapterContentRelative(book.id, chapterId)
                    val stagedChapter = File(chaptersDir, meta.contentFileName)
                    val dest = storage.resolve(relative)
                    dest.parentFile?.mkdirs()
                    if (stagedChapter.isFile) {
                        // The hollow check reads the staged file
                        // before it moves: a PDF chapter under
                        // the prose floor (no Paragraph mass, no
                        // image) is recorded so the reader can
                        // label its stored state honestly.
                        if (book.localFormat == BookFormat.PDF &&
                            isHollowChapter(stagedChapter)
                        ) {
                            hollowIndexes += index
                        }
                        if (!stagedChapter.renameTo(dest)) {
                            stagedChapter.copyTo(dest, overwrite = true)
                            stagedChapter.delete()
                        }
                    }
                    chapterDao.setContent(
                        chapterId, relative, meta.wordCount, DownloadState.READY
                    )
                    landedCount++
                }
            }
            val status = ImportCompletionState.status(book.id)
            ImportCompletionState.publish(
                ImportCompletionState.Status(
                    bookId = book.id,
                    running = true,
                    totalChapters = status?.totalChapters ?: landedCount,
                    landedChapters = landedCount,
                    hollowChapterIndexes = hollowIndexes.toSet()
                )
            )
        }

        /** Under the PDF prose floor: no image block, and the
         * Paragraph blocks' printable prose below the floor. */
        private fun isHollowChapter(stagedChapter: File): Boolean = runCatching {
            val content = chapterJson.decodeFromString(
                app.xreader.domain.content.ChapterContent.serializer(),
                stagedChapter.readText()
            )
            if (content.blocks.any { it is ChapterBlock.Image }) {
                false
            } else {
                val prose = content.blocks
                    .filterIsInstance<ChapterBlock.Paragraph>()
                    .sumOf { PdfProse.printableCount(it.text) }
                prose < PdfBookParser.MIN_PROSE_CHARS_PER_CHAPTER
            }
        }.getOrDefault(false)
    }

    fun discardStaged(token: String) {
        stagingDir(token).deleteRecursively()
    }

    private fun Exception.toAppError(operation: String): AppError {
        if (this is AppError) return this
        val detail = (message ?: javaClass.simpleName).take(200)
        return if (detail.contains("No space", ignoreCase = true) ||
            detail.contains("ENOSPC")
        ) {
            AppError.StorageFull
        } else {
            AppError.StorageFailed(operation, detail)
        }
    }

    private companion object {
        /** v1.5.5 (§4): chapters per landing transaction after
         * chapter 1 (Amendments 2: batched Room inserts). */
        const val BATCH_SIZE = 20
    }
}
