package app.xreader.data.prepare

import app.xreader.data.db.Book
import app.xreader.ui.localreader.PdfPrepareRecord

/**
 * v1.7 MR-2 (P1/P2): the ONE conversion-record validity
 * rule for PDFs — the twin of [EpubPrepareAccess] for the
 * page world. The route resolver (through the shell's
 * `pdfReflowReady` door), the local reader's open flow
 * and the core reader's converted-PDF state all judge a
 * conversion through this object, never through their own
 * copies of the rule.
 *
 * "Conversion landed" (amendment A8) is a conjunction the
 * CALLERS complete: this rule over the record, plus a
 * landed-content check against the chapter rows
 * (`chapterDao.forBook(id).any { it.contentPath != null }`).
 * The record half lives here; the rows half stays with
 * the caller because only the caller holds the DAO. A
 * record that stands while its content is gone (the
 * shipped delete-downloads paths reset conversion rows,
 * which are DownloadState.READY) therefore never routes:
 * the book falls back to the local surface, whose open
 * flow re-runs the conversion.
 */
object PdfPrepareAccess {

    /** The loader version records are written with. A
     * record from any other version does not key routing:
     * the book re-converts under the current loader. */
    const val LOADER_VERSION: Int = 1

    /**
     * A book counts as CONVERTED when a record exists,
     * its loader version is current, and its fingerprint
     * still matches the book's file: the record stores
     * the converted file's SHA-256, compared against the
     * sha segment (the last `|` segment) of
     * [Book.fileFingerprint]. A book whose fingerprint
     * was never captured (old rows) is judged on the
     * record alone; a changed file (re-point) fails the
     * match and re-converts. The [EpubPrepareAccess]
     * rule, verbatim in shape.
     */
    fun isConverted(record: PdfPrepareRecord?, book: Book): Boolean {
        record ?: return false
        if (record.loaderVersion != LOADER_VERSION) return false
        val bookSha = book.fileFingerprint
            ?.substringAfterLast('|')
            ?.takeIf { it.isNotBlank() }
            ?: return true
        return record.fingerprint == bookSha
    }

    /** The fingerprint a conversion of [book] records:
     * the file's sha segment when the book carries one,
     * else the book's whole fingerprint form, else a
     * book-scoped placeholder (such a book is judged on
     * the record alone — see [isConverted]). */
    fun fingerprintFor(book: Book): String =
        book.fileFingerprint
            ?.substringAfterLast('|')
            ?.takeIf { it.isNotBlank() }
            ?: book.fileFingerprint?.takeIf { it.isNotBlank() }
            ?: "book:${book.id}"
}
