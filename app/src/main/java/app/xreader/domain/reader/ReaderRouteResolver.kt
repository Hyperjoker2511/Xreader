package app.xreader.domain.reader

import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.ChapterDao
import app.xreader.data.db.SourceType
import app.xreader.data.extension.BookKind152
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.SourceHandle

/**
 * v1.5.6 (§2): the reader routing law — novels → novel reader,
 * manga → Komikku reader, ALWAYS. The kind is decided from the
 * source/book BEFORE any reader opens — never from the fetched
 * content's shape (the §1.1 defect: a missing in-memory page
 * record let a manga chapter fall into the novel reader and
 * render as blurry in-text pictures), and never inside a reader.
 *
 * This resolver is the ONE authority every chapter-open entry
 * point consults (book page, Library Continue Reading, History,
 * app-start Continue Reading, the pendingOpenBook programmatic
 * open — see ui/shell/MainScaffold.kt). Its verdict is derived
 * at open time from source identity + stored kind; nothing is
 * persisted as a new flag, so it cannot drift the way content
 * classification did.
 *
 * Verdict priority (plan §2.1, with the Gemini-round amendment
 * pinning priority 1 to the tree's existing kind read):
 * 1. `ExtensionService.mediaKindOf(handle)` — the ONE media-kind
 *    read for a handle — for the book's extension source, ranked
 *    ABOVE the book's stored kind: a Mihon/player source is MANGA
 *    by construction, and a single-kind declarative source's
 *    declared kind is a pin (a declarative TEXT source sends its
 *    books to the novel reader even when a stored kind says
 *    otherwise). The book→source resolution reuses the existing
 *    seam (SourceBookIds + `extensionService.source(...)`, the
 *    same one BookDetailViewModel uses) — no second lookup.
 *    The one exception is a BOTH-KINDS declarative source (a
 *    listing spec declares a tier-1 kind-field mapping): its
 *    declared kind stamps the SOURCE, not the book, so it does
 *    not decide — priority 2 does.
 * 2. `BookKind152.verdictOf(book.kind)` for both-kinds and
 *    declarative sources (and as the fallback when an extension
 *    source can no longer be loaded): MANGA → Komikku,
 *    TEXT → novel reader.
 * 3. `sourceType == LOCAL_FILE` → the local-document reader
 *    (EPUB/PDF are text books for routing purposes, but they
 *    read the file in place, §3). Checked before priority 2's
 *    stored verdict for non-extension books, because priority 2
 *    is scoped to extension sources — a local book's stored
 *    kind never reroutes it away from its own file.
 * 4. UNKNOWN + non-player source → the novel reader (the
 *    long-standing default), backstopped by the novel reader's
 *    fail-closed guard (ui/reader/ReaderScreen.kt): if a chapter
 *    it loaded turns out to be an image chapter anyway, it hands
 *    off to Komikku as an explicit wrong-reader correction
 *    instead of rendering the pages as in-text pictures.
 */
sealed interface ReaderRoute {
    /** Open the Komikku reader Activity directly (never the
     * "reader/…" route) via KomikkuReaderActivity.newIntent(
     * mangaId = bookId, chapterId = chapterRowId). [chapterRowId]
     * is the Chapter row id for the requested chapter index, or
     * null when no row exists yet — the Komikku reader then
     * opens the book at its own resume point. */
    data class Komikku(
        val bookId: Long,
        val chapterRowId: Long?
    ) : ReaderRoute

    /** Open the novel reader route ("reader/<bookId>/<index>"). */
    data class NovelReader(
        val bookId: Long,
        val chapterIndex: Int
    ) : ReaderRoute

    /** Open the local-document reader ("local/<bookId>", §3). */
    data class LocalDoc(val bookId: Long) : ReaderRoute
}

class ReaderRouteResolver(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val extensionService: ExtensionService
) {

    /** Resolve by book id (the entry-point shape: most surfaces
     * hold only an id + a chapter index). A book row that no
     * longer exists falls back to the novel reader — today's
     * default — rather than stranding the tap. */
    suspend fun resolve(bookId: Long, chapterIndex: Int = 0): ReaderRoute {
        val book = bookDao.getById(bookId)
            ?: return ReaderRoute.NovelReader(bookId, chapterIndex)
        return resolve(book, chapterIndex)
    }

    /** Resolve for an already-loaded [book] at [chapterIndex]. */
    suspend fun resolve(book: Book, chapterIndex: Int = 0): ReaderRoute {
        // Priority 3 for non-extension books (see class doc):
        // a local file always reads in place.
        if (book.sourceType == SourceType.LOCAL_FILE) {
            return ReaderRoute.LocalDoc(book.id)
        }
        val handle = handleFor(book)
        if (handle != null) {
            // Priority 1 — the source's own kind read outranks
            // the stored kind, except on a both-kinds source,
            // whose declared kind does not decide the book.
            if (!isBothKinds(handle)) {
                return when (extensionService.mediaKindOf(handle)) {
                    MediaKind.MANGA -> komikku(book, chapterIndex)
                    MediaKind.TEXT ->
                        ReaderRoute.NovelReader(book.id, chapterIndex)
                }
            }
            // Priority 2 — a both-kinds source: the book's
            // stored verdict decides; UNKNOWN falls through to
            // priority 4's novel-reader default.
            return when (BookKind152.verdictOf(book.kind)) {
                BookKind152.Verdict.MANGA -> komikku(book, chapterIndex)
                BookKind152.Verdict.TEXT,
                BookKind152.Verdict.UNKNOWN ->
                    ReaderRoute.NovelReader(book.id, chapterIndex)
            }
        }
        // No resolvable extension source: the stored verdict is
        // the only evidence left (priority 2 as fallback), and
        // UNKNOWN lands on priority 4's novel-reader default.
        return when (BookKind152.verdictOf(book.kind)) {
            BookKind152.Verdict.MANGA -> komikku(book, chapterIndex)
            BookKind152.Verdict.TEXT,
            BookKind152.Verdict.UNKNOWN ->
                ReaderRoute.NovelReader(book.id, chapterIndex)
        }
    }

    /** The Komikku verdict, with the chapter ROW id the launch
     * contract needs — resolved from the chapter index through
     * ChapterDao's existing (bookId, index) lookup. */
    private suspend fun komikku(
        book: Book,
        chapterIndex: Int
    ): ReaderRoute.Komikku {
        val chapterRowId = chapterDao.getByIndex(book.id, chapterIndex)?.id
        return ReaderRoute.Komikku(
            bookId = book.id,
            chapterRowId = chapterRowId
        )
    }

    /**
     * The book→source seam, mirrored from BookDetailViewModel
     * (:649-652, :955-959, :1147-1151): SourceBookIds.decode of
     * the book's remoteId gives the source id, and
     * [ExtensionService.source] resolves the handle. Null when
     * the book is not an extension book or its source can no
     * longer be loaded — the caller falls back to the stored
     * verdict, never to a second lookup.
     */
    private suspend fun handleFor(book: Book): SourceHandle? {
        if (book.sourceType != SourceType.EXTENSION) return null
        val extensionId = book.extensionId ?: return null
        val (sourceId, _) = SourceBookIds.decode(book.remoteId) ?: return null
        return extensionService.source(extensionId, sourceId).getOrNull()
    }

    /** A source is both-kinds when any of its listing specs
     * declares a tier-1 kind-field mapping — the same test
     * BookKindResolver.isBothKinds and ExtensionService's
     * book-aware mediaKindOf apply. */
    private fun isBothKinds(handle: SourceHandle): Boolean {
        val config = extensionService.declarativeConfigOf(handle)
            ?: return false
        return listOfNotNull(
            config.popular, config.latest, config.search,
            config.related, config.bookshelf
        ).any { it.kindField != null && it.kindValueMap.isNotEmpty() }
    }
}
