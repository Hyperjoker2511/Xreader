package app.xreader.domain.reader

/**
 * Patch 11 (Reader Terms in the reader): where a tapped
 * "Replace" lands — the pure mapping from the text the reader
 * selected to the pair the Replace dialog should open.
 *
 * The reader DISPLAYS replaced text, so a re-selected word is
 * usually a pair's REPLACEMENT, not its original. The mapping
 * therefore checks the replacement side FIRST: the pair whose
 * replacement the reader is showing is the pair responsible
 * for that text (the engine never re-scans its own output, so
 * a displayed replacement can only have come from its own
 * pair). The original side is checked second — a word the
 * engine left alone in this spot (e.g. a whole-word boundary
 * kept it from firing) still finds its pair. No match on
 * either side means a brand-new pair whose "from" is the
 * selection itself.
 *
 * Case follows the pair's own rule, mirroring
 * [TermReplacer]'s `regionMatches(ignoreCase = !matchCase)`:
 * a case-sensitive pair matches its texts exactly; a
 * case-insensitive pair matches them in any case. The
 * selection is trimmed before comparing (a dragged selection
 * often carries a stray space; [TermsPrefs] trims originals
 * on save anyway).
 *
 * This function is pure and decides NOTHING about storage:
 * the dialog it feeds writes only when the user taps Save.
 */
sealed interface ReplaceTarget {

    /** The selection belongs to the book pair at [index] in
     * the book's own list — the dialog opens THAT pair for
     * editing (with Remove), never a duplicate create. */
    data class Existing(val index: Int, val pair: TermPair) : ReplaceTarget

    /** No book pair claims the selection — the dialog opens
     * as a new pair with [original] (the trimmed selection)
     * prefilled and an empty "to" side. */
    data class New(val original: String) : ReplaceTarget
}

/** Resolve [selectedText] against THIS BOOK's own pairs (the
 * global list is not consulted — the dialog writes to the
 * book's list, and the book's pair is the one a re-selection
 * can mean). */
fun resolveReplaceTarget(
    selectedText: String,
    bookPairs: List<TermPair>
): ReplaceTarget {
    val selected = selectedText.trim()
    if (selected.isEmpty()) return ReplaceTarget.New(selected)
    bookPairs.forEachIndexed { index, pair ->
        if (sameTermText(selected, pair.replacement, pair.matchCase)) {
            return ReplaceTarget.Existing(index, pair)
        }
    }
    bookPairs.forEachIndexed { index, pair ->
        if (sameTermText(selected, pair.original, pair.matchCase)) {
            return ReplaceTarget.Existing(index, pair)
        }
    }
    return ReplaceTarget.New(selected)
}

/** The pair's own case rule applied to one comparison — the
 * same rule [TermReplacer] matches text with. */
private fun sameTermText(selected: String, candidate: String, matchCase: Boolean): Boolean =
    if (matchCase) {
        selected == candidate
    } else {
        selected.equals(candidate, ignoreCase = true)
    }
