package app.xreader.data.library

import app.xreader.data.db.Book
import app.xreader.data.db.BookDao
import app.xreader.data.db.SourceType
import app.xreader.data.extension.BookKind152
import app.xreader.data.extension.ExtensionService
import app.xreader.data.extension.MediaKind
import app.xreader.data.extension.SourceBookIds
import app.xreader.data.extension.SourceHandle
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.5.2 fix (FIX-5, §7): kind is a property of the BOOK.
 *
 * Resolution order for a book that exists:
 * 1. The book's stored kind ([Book.kind]) — tier-1 listing evidence
 *    (or a pinned tier-2 verdict) already landed. It governs filing.
 * 2. A both-kinds source (its config declares a tier-1 kind-field
 *    mapping) with no stored evidence: the verdict is UNKNOWN —
 *    the book files VISUALLY under Novels and never vanishes; the
 *    source's stamp NEVER files its books (the FenrirRealm defect:
 *    a MANGA-stamped record filed every novel as Manga).
 * 3. A single-kind source: the config kind is a PIN — its books
 *    file under the config kind (Chikari can never re-file).
 * 4. Non-extension books (imports, OPDS, JS/Dex) and books whose
 *    source can no longer be loaded: Novels, so a book stays
 *    reachable on a side instead of vanishing from both.
 *
 * Player/Keiyoushi sources take kind from the source object (their
 * books carry no per-book evidence in this build).
 */
class BookKindResolver(
    private val service: ExtensionService,
    private val bookDao: BookDao? = null
) {

    /** The kind of one already-loaded source handle (no book). */
    fun kindOf(handle: SourceHandle): MediaKind =
        service.mediaKindOf(handle)

    /** Per-book resolution where a handle is already loaded. */
    fun kindOf(handle: SourceHandle, book: Book): MediaKind =
        service.mediaKindOf(handle, book)

    suspend fun kindOf(book: Book): MediaKind =
        kindsOf(listOf(book))[book.id] ?: MediaKind.TEXT

    /** The §7 verdict for one book: TEXT / MANGA / UNKNOWN. UNKNOWN
     * files visually under Novels (see [kindsOf]). */
    suspend fun verdictOf(book: Book): BookKind152.Verdict {
        BookKind152.verdictOf(book.kind).let {
            if (book.kind != null) return it
        }
        if (book.sourceType != SourceType.EXTENSION || book.extensionId == null) {
            return BookKind152.Verdict.TEXT
        }
        val handle = handleFor(book) ?: return BookKind152.Verdict.TEXT
        // v1.5.8 (B4): the player verdict is the adapter's own
        // kind (TEXT for a NovelSource extension), not a
        // blanket MANGA.
        service.mihonAdapterOf(handle)?.let { adapter ->
            return when (adapter.mediaKind) {
                MediaKind.MANGA -> BookKind152.Verdict.MANGA
                MediaKind.TEXT -> BookKind152.Verdict.TEXT
            }
        }
        return if (isBothKinds(handle)) BookKind152.Verdict.UNKNOWN
        else when (service.mediaKindOf(handle)) {
            MediaKind.MANGA -> BookKind152.Verdict.MANGA
            MediaKind.TEXT -> BookKind152.Verdict.TEXT
        }
    }

    suspend fun kindsOf(books: List<Book>): Map<Long, MediaKind> {
        val result = HashMap<Long, MediaKind>()
        for (book in books) {
            // (1) Stored per-book evidence governs.
            when (BookKind152.verdictOf(book.kind)) {
                BookKind152.Verdict.MANGA -> { result[book.id] = MediaKind.MANGA; continue }
                BookKind152.Verdict.TEXT -> continue
                BookKind152.Verdict.UNKNOWN -> Unit
            }
            if (book.sourceType != SourceType.EXTENSION ||
                book.extensionId == null
            ) continue
            val handle = handleFor(book) ?: continue
            // v1.5.8 (B4): player books file by the adapter's
            // kind — a novel extension's books stay Novels.
            val adapter = service.mihonAdapterOf(handle)
            if (adapter != null) {
                if (adapter.mediaKind == MediaKind.MANGA) {
                    result[book.id] = MediaKind.MANGA
                }
                continue
            }
            // (2) both-kinds + no evidence → UNKNOWN → visual Novels
            // (absent from the map, which consumers read as Novels).
            if (isBothKinds(handle)) continue
            // (3) single-kind pin.
            if (service.mediaKindOf(handle) == MediaKind.MANGA) {
                result[book.id] = MediaKind.MANGA
            }
        }
        return result
    }

    private suspend fun handleFor(book: Book): SourceHandle? {
        val sourceId = SourceBookIds.decode(book.remoteId)?.first ?: return null
        return service.source(book.extensionId!!, sourceId).getOrNull()
    }

    private fun isPlayerHandle(handle: SourceHandle): Boolean =
        service.mihonAdapterOf(handle) != null

    /** A source is both-kinds when any of its listing specs declares
     * a tier-1 kind-field mapping (§7 tier-2 gate). */
    fun isBothKinds(handle: SourceHandle): Boolean {
        val config = service.declarativeConfigOf(handle) ?: return false
        return listOfNotNull(
            config.popular, config.latest, config.search,
            config.related, config.bookshelf
        ).any { it.kindField != null && it.kindValueMap.isNotEmpty() }
    }

    // ── Tier application (production callers of BookKind152) ────

    private val tier2Streaks = ConcurrentHashMap<Long, Pair<String?, Int>>()

    /** Tier-1 evidence at catalogue fetch/sync: always overrides,
     * persisted at once (§7's one re-file trigger for tier 1). */
    suspend fun applyTier1(book: Book, tier1Kind: String?): Book {
        val dao = bookDao ?: return book
        val newKind = BookKind152.applyTier1(book.kind, tier1Kind)
        if (newKind == book.kind) return book
        val updated = book.copy(kind = newKind)
        dao.update(updated)
        tier2Streaks.remove(book.id)
        tier2TextPriorStreaks.remove(book.id)
        return updated
    }

    /** v1.5.8 (B3): consecutive IMAGE verdicts for a book filed
     * under a TEXT prior — its own streak, because the TEXT-prior
     * bar (three) differs from the neutral hysteresis (two). */
    private val tier2TextPriorStreaks = ConcurrentHashMap<Long, Int>()

    /** v1.5.8 (B3): books that have EVER recorded a prose
     * verdict while filed TEXT — a single prose verdict vetoes
     * tier-2 re-filing permanently (tier-1 can still file the
     * book; only the evidence road closes). */
    private val tier2ProseVeto =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /**
     * Tier-2 chapter-content evidence at open/sync (§7).
     *
     * v1.5.8 (B3): the gate is widened from "declares a kindField
     * map" to "is a declarative source" — undeclared both-kinds
     * sources and ALL learned sources now testify per book. The
     * config kind remains the filing prior until evidence clears
     * its bar, and the bar depends on the prior, because a novel
     * can testify falsely (colour-plate / interlude chapters are
     * image-only under the chapter-shape rule, and plates split
     * across two chapters is a real publishing shape):
     *  - neutral prior (UNKNOWN): the existing hysteresis — two
     *    consecutive consistent verdicts re-file;
     *  - TEXT prior: THREE consecutive image verdicts AND zero
     *    prose verdicts ever; one prose verdict vetoes tier-2
     *    permanently. Two consecutive plate chapters are
     *    absorbed by design.
     * Non-declarative sources (player, JS/Dex) never re-file
     * here — their kind comes from their own type/config.
     */
    suspend fun applyTier2Evidence(book: Book, tier2Kind: String?): Book {
        val dao = bookDao ?: return book
        if (tier2Kind == null) return book
        if (book.sourceType != SourceType.EXTENSION || book.extensionId == null) {
            return book
        }
        val handle = handleFor(book) ?: return book
        if (service.declarativeConfigOf(handle) == null) return book
        // The CURRENT stored kind governs (the caller's copy may
        // be stale across a streak of verdicts).
        val current = dao.getById(book.id) ?: book
        if (current.kind == BookKind152.TEXT) {
            if (tier2Kind == BookKind152.TEXT) {
                // A prose verdict: veto tier-2 for this book.
                tier2ProseVeto += book.id
                tier2TextPriorStreaks.remove(book.id)
                return current
            }
            if (tier2Kind != BookKind152.MANGA) return current
            if (book.id in tier2ProseVeto) return current
            val streak = (tier2TextPriorStreaks[book.id] ?: 0) + 1
            if (streak < 3) {
                tier2TextPriorStreaks[book.id] = streak
                return current
            }
            tier2TextPriorStreaks.remove(book.id)
            val updated = current.copy(kind = BookKind152.MANGA)
            dao.update(updated)
            return updated
        }
        val pending = tier2Streaks[book.id] ?: (null to 0)
        val (newKind, newPending) = BookKind152.applyTier2(
            storedKind = current.kind,
            bothKindsSource = true,
            tier2Kind = tier2Kind,
            pendingKind = pending.first,
            pendingStreak = pending.second
        )
        tier2Streaks[book.id] = newPending
        if (newKind == current.kind) return current
        val updated = current.copy(kind = newKind)
        dao.update(updated)
        return updated
    }
}
