package app.xreader.data.library

import app.xreader.data.db.Book
import app.xreader.data.db.SourceType
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.SourceHandle

/**
 * Manga programme (M3): how a book's media kind is resolved,
 * in ONE place — the Library split, the Updates filter and
 * global search's grouping all read through here.
 *
 * The rule (the M1 foundation's design): a book's kind
 * derives from its source's config — there is no kind column
 * on the book row and no migration. Concretely:
 *
 * - an EXTENSION book takes the [MediaKind] of its source
 *   (its declarative config's kind; a Keiyoushi player
 *   source is MANGA by construction — K4);
 * - everything else is a novel: imported/local files, OPDS
 *   books, JS/Dex sources (they carry no declarative config
 *   and serve prose), and — deliberately — an extension book
 *   whose source can no longer be loaded (uninstalled,
 *   untrusted, broken): it falls back to Novels so the book
 *   stays reachable on a side instead of vanishing from both.
 */
class BookKindResolver(
    private val service: ExtensionService
) {

    /** The kind of one already-loaded source handle. */
    fun kindOf(handle: SourceHandle): MediaKind =
        service.mediaKindOf(handle)

    suspend fun kindOf(book: Book): MediaKind =
        kindsOf(listOf(book))[book.id] ?: MediaKind.TEXT

    /**
     * Kinds for many books at once: one source load per
     * distinct (extension, source) pair, however many books
     * share it. Books that resolve to Novels by the fallback
     * rules above are simply absent from the result — every
     * consumer reads absence as Novels (see [LibrarySplit]).
     */
    suspend fun kindsOf(books: List<Book>): Map<Long, MediaKind> {
        val result = HashMap<Long, MediaKind>()
        val bySource = books
            .filter {
                it.sourceType == SourceType.EXTENSION && it.extensionId != null
            }
            .groupBy { book ->
                val sourceId = SourceBookIds.decode(book.remoteId)?.first
                book.extensionId!! to sourceId
            }
        for ((key, group) in bySource) {
            val (extensionId, sourceId) = key
            if (sourceId == null) continue
            val handle = service.source(extensionId, sourceId).getOrNull()
                ?: continue
            val kind = kindOf(handle)
            if (kind != MediaKind.TEXT) {
                for (book in group) result[book.id] = kind
            }
        }
        return result
    }
}
