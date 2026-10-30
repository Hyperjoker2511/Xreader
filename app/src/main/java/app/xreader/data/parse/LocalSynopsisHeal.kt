package app.xreader.data.parse

import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import app.xreader.data.db.SourceType
import app.xreader.data.files.BookStorage
import java.io.File

/**
 * v1.0.6.2 (F6a): heal-on-open for local books whose stored synopsis
 * is empty — books imported before the importers mapped file
 * descriptions, or whose file carries one the import missed. When
 * the book's file is still on the phone, its metadata is re-read
 * ONCE (per details open) and the description filled in; nothing is
 * invented when the file is gone or carries no description.
 */
class LocalSynopsisHeal(
    private val storage: BookStorage,
    private val pdfExtractor: PdfTextExtractor?
) {

    /** File worth re-reading for [book], or null when none exists. */
    fun candidateFile(book: Book): File? {
        if (book.sourceType != SourceType.LOCAL_FILE) return null
        book.filePath?.takeIf { it.isNotBlank() }?.let { relative ->
            val file = storage.resolve(relative)
            if (file.isFile) return file
        }
        val dir = storage.booksDir(book.id)
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.firstOrNull { file ->
                when (book.localFormat) {
                    BookFormat.EPUB -> file.name.endsWith(".epub", ignoreCase = true)
                    BookFormat.PDF -> file.name.endsWith(".pdf", ignoreCase = true)
                    else -> true
                }
            }
    }

    /** The file's own description per its format's metadata, or null. */
    fun readDescription(book: Book, file: File): String? {
        val workDir = storage.importStagingDir("synopsis-heal-${book.id}")
        return try {
            when (book.localFormat) {
                BookFormat.EPUB -> EpubParser().readDescription(file, workDir)
                BookFormat.PDF -> pdfExtractor?.extractDescription(file)
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            workDir.deleteRecursively()
        }
    }
}
