package app.xreader.ui.localreader

/**
 * v1.5.10 (G5): Share — moved verbatim from the retired
 * PdfDocumentReader.kt in OE-1. The share payload for a
 * book's own file: the registered document URI (never a
 * copy), a read-permission grant, and the share-details
 * text. Pure — the gate test pins it. Shared by both
 * local readers (the EPUB reader's epubBookShareSpec
 * builds the same [BookShareSpec] shape).
 */
data class BookShareSpec(
    val uriString: String,
    val mimeType: String,
    val subject: String,
    val text: String
)

/** Share exists only for a book whose file is registered by
 * document URI (the in-place import model). A book without one
 * offers NO share action — never a dead button (plan G5). */
fun bookShareAvailable(documentUri: String?): Boolean =
    !documentUri.isNullOrBlank()

/** Fires the share chooser for [spec] — the file streams from
 * its registered URI under a read grant; nothing is copied. */
internal fun launchBookShare(
    context: android.content.Context,
    spec: BookShareSpec
) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_SEND
    ).apply {
        type = spec.mimeType
        putExtra(
            android.content.Intent.EXTRA_STREAM,
            android.net.Uri.parse(spec.uriString)
        )
        putExtra(android.content.Intent.EXTRA_SUBJECT, spec.subject)
        putExtra(android.content.Intent.EXTRA_TEXT, spec.text)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(intent, "Share book")
        )
    }
}

fun buildBookShareSpec(book: app.xreader.data.db.Book): BookShareSpec? {
    val uri = book.documentUri?.takeIf { it.isNotBlank() } ?: return null
    val mime = when (book.localFormat) {
        app.xreader.data.db.BookFormat.PDF -> "application/pdf"
        app.xreader.data.db.BookFormat.EPUB -> "application/epub+zip"
        else -> "*/*"
    }
    val details = buildString {
        append(book.title)
        if (book.author.isNotBlank()) append(" — ").append(book.author)
    }
    return BookShareSpec(
        uriString = uri,
        mimeType = mime,
        subject = book.title,
        text = details
    )
}
