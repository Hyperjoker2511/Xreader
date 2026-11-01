package app.xreader.data.library

import app.xreader.data.extension.MediaKind

/**
 * Manga programme (M3): the Novels | Manga split's pure rules,
 * shared by the Library, Explore and global search so the two
 * sides can never drift between surfaces.
 *
 * The law (CYCLE1-PLAN, manga decisions): the Library and
 * Explore each carry ONE switch with two sides; a book or
 * source belongs to exactly one side, decided by its media
 * kind (see [BookKindResolver] for how a book's kind is
 * derived); imported/local books are Novels. Nothing is ever
 * hidden by the split — a side shows its own kind, and the
 * other side is one tap away.
 */
object LibrarySplit {

    /** The plain side name the switch shows. */
    fun sideName(side: MediaKind): String = when (side) {
        MediaKind.TEXT -> "Novels"
        MediaKind.MANGA -> "Manga"
    }

    /**
     * A switch segment's label: the side's name, with its live
     * count riding on it when there is one ("Novels · 3").
     * What the count MEANS is each surface's own choice (the
     * Library rides its new-chapter counts, Explore its listed
     * source counts); the shape is shared.
     */
    fun switchLabel(side: MediaKind, count: Int): String =
        if (count > 0) "${sideName(side)} · $count" else sideName(side)

    /** A book's side: its kind, defaulting to Novels when the
     * kind map has no entry (local books, OPDS books, and
     * extension books whose source can't be read right now —
     * a book must never fall off both sides). */
    fun kindOf(bookId: Long, kinds: Map<Long, MediaKind>): MediaKind =
        kinds[bookId] ?: MediaKind.TEXT

    /** The books that belong on [side], order preserved. */
    fun <T> onSide(
        items: List<T>,
        side: MediaKind,
        kindOf: (T) -> MediaKind
    ): List<T> = items.filter { kindOf(it) == side }

    /**
     * New-chapter counts per side, for the Library switch's
     * labels: the sum of each book's unseen-new count
     * ([BookMeta.newChapterCount], fed by the library-update
     * pass) over the books of that kind.
     */
    fun newChaptersBySide(
        metas: Map<Long, BookMeta>,
        kinds: Map<Long, MediaKind>
    ): Map<MediaKind, Int> {
        var novels = 0
        var manga = 0
        for ((bookId, meta) in metas) {
            if (meta.newChapterCount <= 0) continue
            when (kindOf(bookId, kinds)) {
                MediaKind.TEXT -> novels += meta.newChapterCount
                MediaKind.MANGA -> manga += meta.newChapterCount
            }
        }
        return mapOf(MediaKind.TEXT to novels, MediaKind.MANGA to manga)
    }

    /**
     * Whether an extension appears on [side] of Explore: when
     * at least one of its sources belongs to that side. An
     * extension whose sources carry no recorded kind (JS/Dex
     * packages — prose by construction) belongs to the Novels
     * side only.
     */
    fun extensionOnSide(kinds: List<MediaKind>, side: MediaKind): Boolean =
        if (kinds.isEmpty()) {
            side == MediaKind.TEXT
        } else {
            kinds.any { it == side }
        }
}
