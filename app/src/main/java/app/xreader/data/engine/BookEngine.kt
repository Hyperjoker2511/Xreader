package app.xreader.data.engine

import android.content.Context
import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType

/**
 * v1.5.6 (§3.1 rows 1–2): the render-the-file codec contract.
 *
 * A [DocSession] is ONE open document: outline + geometry up front,
 * sections/pages rendered or read on demand. The local reader
 * depends only on this contract, never on a carrier directly, so
 * the carrier choice (Readium for EPUB, PdfRenderer+PdfBox for PDF)
 * stays reversible behind it.
 */
data class OutlineEntry(
    val title: String,
    /** The fixed target forms: EPUB `{"section":i}` (reading-order
     * index), PDF `{"page":p}`. */
    val targetJson: String
)

interface DocSession {
    val format: BookFormat
    val outline: List<OutlineEntry>

    /** Sections (EPUB reading order) or pages (PDF). */
    val sectionCount: Int

    /** Plain text of one section/page — the TTS/search provider
     * (§3.1 row 11). Empty string when the section carries none. */
    suspend fun textAt(index: Int): String

    fun close()
}

/** The file a local book points at cannot be read (moved/deleted
 * outside the app, permission lost). The reader surfaces this as
 * the plain "file not found" state with a re-point offer (§3.4(d)). */
class LocalFileMissingException(message: String) : Exception(message)

/** A user-password PDF: rendering needs a password the app never
 * stores. The reader asks once per session (§3.1 row 6). */
class LocalPasswordRequiredException : Exception("password required")

/**
 * The registry (§3.1 row 1): format → engine for LOCAL_FILE books.
 * EPUB and PDF are the in-place formats this round (§3.4(c)/Q6);
 * every other format keeps today's converted-chapter path.
 */
object BookEngine {

    fun isInPlaceBook(book: Book): Boolean =
        book.sourceType == SourceType.LOCAL_FILE &&
            (book.localFormat == BookFormat.EPUB || book.localFormat == BookFormat.PDF) &&
            (book.documentUri != null || book.filePath != null)

    /** A local book whose original is GONE (§3.4(b) legacy lane):
     * it keeps reading through the converted-chapter reader. */
    fun isLegacyLaneBook(book: Book): Boolean =
        book.sourceType == SourceType.LOCAL_FILE &&
            book.documentUri == null && book.filePath == null

    /**
     * Opens [book]'s own file. Must be called off the main thread.
     * [filesRoot] resolves retained-copy relative paths
     * ([Book.filePath]); [cacheDir] hosts the bounded render cache.
     */
    suspend fun open(
        context: Context,
        book: Book,
        filesRoot: java.io.File,
        cacheDir: java.io.File,
        password: String? = null
    ): DocSession = when (book.localFormat) {
        BookFormat.EPUB -> ReadiumEpubSession.open(context, book, filesRoot)
        BookFormat.PDF -> PdfSession.open(context, book, filesRoot, cacheDir, password)
        else -> throw LocalFileMissingException(
            "This kind of file reads in the chapter reader."
        )
    }
}
