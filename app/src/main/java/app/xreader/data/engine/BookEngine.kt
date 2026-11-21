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
 * the carrier choice (PdfRenderer+PdfBox for PDF) stays reversible
 * behind it. v1.7 OE-2: EPUB has no carrier here any more — every
 * EPUB converts into the core reader through the prepare step,
 * and Readium is retired whole.
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
 * PDF is the in-place format this registry still opens (§3.4(c)/Q6);
 * EPUB leaves through the prepare step into the core reader
 * (v1.7 OE-2) and never reaches [open]; every other format keeps
 * today's converted-chapter path.
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
     * v1.7 MR-1: the CONVERTED formats — a local book in one of
     * these reads through the converted-chapter (core) reader
     * once its chapters have landed; before landing (or after
     * its content is deleted) it reaches the local surface,
     * whose open throws the chapter-reader message below and
     * whose Failed state offers the import resume. EPUB and
     * PDF are the in-place formats, never this set; UNKNOWN
     * has no parser and is not either.
     */
    fun isConvertedFormat(format: BookFormat): Boolean =
        when (format) {
            BookFormat.TXT, BookFormat.FB2, BookFormat.MARKDOWN,
            BookFormat.HTML, BookFormat.MOBI, BookFormat.AZW3,
            BookFormat.CBR, BookFormat.CBZ -> true
            BookFormat.EPUB, BookFormat.PDF,
            BookFormat.UNKNOWN -> false
        }

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
        // v1.7 OE-2 (R28): no EPUB arm — an EPUB reaching
        // open() is a programming error (its road is the
        // prepare step, which runs before any session is
        // opened), and the else arm's honest exception is
        // its landing.
        BookFormat.PDF -> PdfSession.open(context, book, filesRoot, cacheDir, password)
        else -> throw LocalFileMissingException(
            "This kind of file reads in the chapter reader."
        )
    }
}
