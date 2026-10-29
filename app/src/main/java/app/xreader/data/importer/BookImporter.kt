package app.xreader.data.importer

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.BookFormat
import app.xreader.data.db.Chapter
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.DownloadState
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import app.xreader.data.parse.FormatSniffer
import app.xreader.data.parse.ParserRegistry
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val unsupportedReason: String?
)

/**
 * Book import pipeline (PLAN §4): picked stream → staging file
 * (content sniffed, never trusted by extension) → parser → Room rows
 * + content files. Runs entirely on IO; failures come back typed so
 * the UI shows plain-words errors, never a spinner that dies silently
 * (PLAN §0.2). Also used for OPDS acquisitions (the downloader hands
 * it the downloaded file).
 */
class BookImporter(
    private val storage: BookStorage,
    private val registry: ParserRegistry,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private fun stagingDir(token: String): File =
        File(File(storage.filesDir, "import-staging"), token)

    private fun stagedFile(token: String): File = File(stagingDir(token), "book.bin")

    /** Copies a picked file into staging and sniffs its real format. */
    suspend fun stage(
        displayName: String,
        openStream: () -> InputStream?
    ): AppResult<ImportCandidate> = withContext(Dispatchers.IO) {
        val dir = storage.importStagingDir("pick")
        val token = dir.name
        val staged = File(dir, "book.bin")
        try {
            val stream = openStream()
                ?: return@withContext AppResult.Err(
                    AppError.StorageFailed("open", "the file couldn't be opened")
                )
            storage.copyStreamAtomic(stream, staged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dir.deleteRecursively()
            return@withContext AppResult.Err(e.toAppError("copy"))
        }
        candidateFor(token, displayName, staged)
    }

    /** Stages an already-downloaded file (OPDS acquisition hand-off). */
    suspend fun stageDownloadedFile(
        displayName: String,
        file: File
    ): AppResult<ImportCandidate> = withContext(Dispatchers.IO) {
        val dir = storage.importStagingDir("opds")
        val token = dir.name
        val staged = File(dir, "book.bin")
        try {
            file.inputStream().buffered().use { input -> storage.copyStreamAtomic(input, staged) }
        } catch (e: Exception) {
            dir.deleteRecursively()
            return@withContext AppResult.Err(e.toAppError("copy"))
        }
        candidateFor(token, displayName, staged)
    }

    private fun candidateFor(token: String, displayName: String, staged: File): AppResult<ImportCandidate> {
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
                }
            )
        )
    }

    /**
     * Parses a staged file and lands it in the library. The Room
     * inserts happen only after a full successful parse; if landing
     * the files fails midway, the book row and its files are removed
     * again — no half-books (PLAN §0.2).
     */
    suspend fun importStaged(
        candidate: ImportCandidate,
        onProgress: (Float?) -> Unit = {}
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
        val workDir = File(stagingDir(candidate.token), "work").apply { mkdirs() }
        var createdBook: Book? = null
        try {
            val parsed = when (val result = parser.parse(staged, workDir, onProgress)) {
                is AppResult.Err -> return@withContext AppResult.Err(result.error)
                is AppResult.Ok -> result.value
            }
            val now = clock()
            val fallbackTitle = candidate.displayName
                .substringBeforeLast('.', candidate.displayName)
                .ifBlank { "Untitled book" }
            var book = Book(
                title = parsed.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                author = parsed.author ?: "",
                description = parsed.description ?: "",
                sourceType = SourceType.LOCAL_FILE,
                localFormat = format,
                totalChapters = parsed.chapters.size,
                totalWords = parsed.chapters.sumOf { it.wordCount }.toLong(),
                addedAt = now,
                updatedAt = now
            )
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
            AppResult.Ok(book)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            createdBook?.let { doomed ->
                runCatching { bookDao.delete(doomed) }
                runCatching { storage.deleteBookFiles(doomed) }
            }
            AppResult.Err(e.toAppError("import"))
        } finally {
            stagingDir(candidate.token).deleteRecursively()
        }
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
}
