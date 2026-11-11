package app.xreader.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.BookmarkDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.HighlightDao
import app.xreader.data.db.LocalOutlineDao
import app.xreader.data.db.ReadingMemory
import app.xreader.data.db.ReadingMemoryDao
import app.xreader.data.db.ReadingProgressDao
import app.xreader.data.files.BookStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The §3.7 delete sheet's three options, as the data layer sees them. */
enum class LocalBookDeleteMode {
    /** Gone from the app only; the device file is untouched and
     * the reading-memory note stays, so re-adding resumes. */
    DELETE_BOOK,

    /** The app copy goes AND the memory note is wiped; the device
     * file is still untouched; re-adding starts fresh. */
    FORGET,

    /** Everything goes — app copy, memory note, AND the owner's
     * own file on the device (via its document URI). */
    PERMANENT
}

/**
 * v1.5.6 (§3.7): deleting a local book — no second copy, and the
 * YouTube rule. Delete removes the library entry and everything
 * the app holds for the book; what survives a DELETE_BOOK is a
 * NOTE (a [ReadingMemory]: fingerprint + position/bookmarks as
 * locators, a few KB, no book content), so re-adding the identical
 * file resumes. This class is the data operation only — the
 * delete sheet UI calls [deleteLocalBook] with the owner's choice.
 *
 * [clearLegacyData] is the §3.5 R3 manual-only path: it must
 * NEVER be called automatically (no startup hookup, no
 * migration hookup) — only the owner's "Clear Legacy Data" tap
 * in Settings may call it.
 */
class LocalBookDeletion(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val readingProgressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val readingMemoryDao: ReadingMemoryDao,
    private val localOutlineDao: LocalOutlineDao,
    private val storage: BookStorage,
    /** v1.5.8 (F4 gap): the app cache root — the PDF strip
     * (PdfStripStore) and render cache live under
     * `cacheDir/local-pdf/<bookId>/`, OUTSIDE [storage]'s
     * files-dir tree, so [BookStorage.deleteBookFiles] can
     * never reach them. The container wires the real cache
     * dir; null (tests, older embeddings) skips the strip
     * clear. */
    private val cacheDir: java.io.File? = null,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit =
        { block -> block() },
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Resolves the book's fingerprint: the stored
     * [Book.fileFingerprint] when present; else, for a retained
     * book, the fingerprint of its retained file (name|size|sha256
     * of the file on disk). A legacy book with neither stamped
     * fingerprint nor file yields null — no memory record is
     * possible for it, and deletion proceeds without one.
     */
    suspend fun resolveFingerprint(book: Book): String? = withContext(Dispatchers.IO) {
        book.fileFingerprint?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val relative = book.filePath ?: return@withContext null
        val file = storage.resolve(relative)
        if (!file.isFile) return@withContext null
        runCatching {
            ReadingMemoryCodec.fingerprintOf(
                file.name, file.length(), ReadingMemoryCodec.sha256Hex(file)
            )
        }.getOrNull()
    }

    /**
     * Builds AND stores the reading-memory note for [book] from
     * its current progress/bookmarks/highlights. Returns the
     * stored record, or null when no fingerprint is resolvable
     * (a legacy book with no file — documented above; the caller
     * proceeds with the deletion regardless).
     */
    suspend fun captureReadingMemory(book: Book): ReadingMemory? {
        val fingerprint = resolveFingerprint(book) ?: return null
        val progress = readingProgressDao.getForBook(book.id)
        val bookmarks = bookmarkDao.forBook(book.id)
        val highlights = highlightDao.forBook(book.id)
        val fraction = progress?.let {
            ReadingMemoryCodec.locatorBookFraction(it.locatorJson) ?: it.fraction
        } ?: 0f
        val memory = ReadingMemory(
            fingerprint = fingerprint,
            displayName = ReadingMemoryCodec.displayNameOf(fingerprint)
                .ifBlank { book.title },
            sizeBytes = ReadingMemoryCodec.sizeBytesOf(fingerprint)
                ?: book.filePath?.let { storage.resolve(it) }
                    ?.takeIf { it.isFile }?.length()
                ?: 0L,
            format = book.localFormat.name,
            fraction = fraction,
            locatorJson = progress?.locatorJson,
            bookmarksJson = ReadingMemoryCodec.encodeBookmarks(
                bookmarks.map { mark ->
                    MemoryBookmark(
                        label = mark.label,
                        locatorJson = mark.locatorJson
                            ?: ReadingMemoryCodec.legacyLocator(
                                mark.chapterIndex, mark.charOffset
                            ),
                        chapterIndex = mark.chapterIndex,
                        charOffset = mark.charOffset
                    )
                }
            ),
            highlightsJson = ReadingMemoryCodec.encodeHighlights(
                highlights.map { light ->
                    val chapterIndex = runCatching {
                        chapterDao.getById(light.chapterId)?.index
                    }.getOrNull() ?: 0
                    MemoryHighlight(
                        locatorJson = light.locatorJson
                            ?: ReadingMemoryCodec.legacyHighlightLocator(
                                chapterIndex, light.startOffset, light.endOffset
                            ),
                        chapterIndex = chapterIndex,
                        startOffset = light.startOffset,
                        endOffset = light.endOffset,
                        quotedText = light.quotedText,
                        color = light.color,
                        note = light.note
                    )
                }
            ),
            updatedAt = clock()
        )
        readingMemoryDao.upsert(memory)
        return memory
    }

    /**
     * Deletes [book] per [mode]. The book row's delete cascades
     * (FK) to chapters/progress/bookmarks/highlights/outline;
     * [BookStorage.deleteBookFiles] removes the retained copy
     * (where one exists), converted artifacts, lore art and cover.
     * Options DELETE_BOOK/FORGET never touch the owner's own file
     * on the device.
     *
     * PERMANENT additionally deletes the device file through its
     * documentUri (DocumentsContract) and therefore needs a
     * [context]; the device-file delete runs FIRST and any failure
     * aborts the whole operation with a typed Err — success is
     * never faked. Android may show its own system confirmation
     * for that delete; that prompt is part of the flow.
     */
    suspend fun deleteLocalBook(
        book: Book,
        mode: LocalBookDeleteMode,
        context: Context? = null
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (mode == LocalBookDeleteMode.PERMANENT) {
                val uri = book.documentUri
                if (uri != null) {
                    val ctx = context ?: return@withContext AppResult.Err(
                        AppError.StorageFailed(
                            "delete",
                            "permanent delete needs the device file's permission context"
                        )
                    )
                    val deleted = runCatching {
                        DocumentsContract.deleteDocument(
                            ctx.contentResolver, Uri.parse(uri)
                        )
                    }.getOrDefault(false)
                    if (!deleted) {
                        return@withContext AppResult.Err(
                            AppError.StorageFailed(
                                "delete", "the file on your device couldn't be deleted"
                            )
                        )
                    }
                }
                // No documentUri: the book's only file is the app's
                // retained copy, which deleteBookFiles removes below.
            }
            when (mode) {
                LocalBookDeleteMode.DELETE_BOOK -> captureReadingMemory(book)
                LocalBookDeleteMode.FORGET, LocalBookDeleteMode.PERMANENT -> {
                    resolveFingerprint(book)?.let { readingMemoryDao.delete(it) }
                    // A stored fingerprint differing from the resolved
                    // one is impossible (resolve prefers the stored
                    // value), but a memory filed under the book's own
                    // stamped fingerprint is covered by the same call.
                }
            }
            inTransaction {
                // Belt and braces alongside the FK cascade: the
                // outline goes with the book on every store.
                runCatching { localOutlineDao.deleteForBook(book.id) }
                bookDao.delete(book)
            }
            storage.deleteBookFiles(book)
            // v1.5.8 (F4 gap): the PDF strip + render cache
            // under cacheDir/local-pdf/<bookId>/ die with the
            // book too — on EVERY mode (even DELETE_BOOK,
            // whose surviving reading-memory note carries no
            // book content; the strip is re-derivable cache,
            // and a re-added identical file re-strips or
            // re-reads only its own fingerprint's record,
            // which a stale strip for a DIFFERENT book row
            // must never supply).
            cacheDir?.let { root ->
                runCatching {
                    java.io.File(root, "local-pdf/${book.id}")
                        .deleteRecursively()
                }
            }
            AppResult.Ok(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(
                AppError.StorageFailed("delete", (e.message ?: e.javaClass.simpleName).take(200))
            )
        }
    }

    /**
     * §3.5 R3 — Clear Legacy Data (MANUAL ONLY; never call this
     * automatically): for every book that can read in place
     * (documentUri != null OR a retained filePath), delete its
     * converted artifacts — the content/<id> tree — and its
     * chapter rows, keeping the Book row, the retained file, the
     * cover, progress/bookmark locator data and the outline.
     * Returns the number of books cleared.
     */
    suspend fun clearLegacyData(): Int = withContext(Dispatchers.IO) {
        val books = bookDao.allBooks().filter {
            it.documentUri != null || it.filePath != null
        }
        for (book in books) {
            storage.deleteDownloadedContent(book)
            inTransaction { chapterDao.deleteForBook(book.id) }
        }
        books.size
    }
}
