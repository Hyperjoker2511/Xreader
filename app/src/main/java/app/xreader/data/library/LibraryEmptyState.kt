package app.xreader.data.library

/**
 * The Library's empty-state decision (v1.0.4, D3), extracted as pure
 * logic so the gate is unit-tested instead of living as one fragile
 * conjunction inside the composable.
 *
 * The v1.0.3 defect this guards: the decision read `loading` from a
 * doubly-combined StateFlow whose flag is cleared only inside a
 * five-flow combine of Room + DataStore sidecars. Any stall or
 * failure in that chain left the flag true forever — the header,
 * search field, toolbar and chips rendered (they need only the
 * initial value) over a permanently empty list, and the designed
 * "No books yet" state could never appear. The ViewModel now keeps
 * every sidecar flow in that chain from wedging it (a failed
 * sidecar degrades to its default), and this predicate is the one
 * place that decides what "empty" means:
 *
 *  - still loading → NOT empty (chrome alone, list area settles);
 *  - loaded, zero books, zero staged imports → the empty state
 *    owns the screen (no search/toolbar/chips over it);
 *  - any book or any staged candidate → the normal library.
 */
object LibraryEmptyState {

    fun show(loading: Boolean, bookCount: Int, candidateCount: Int): Boolean =
        !loading && bookCount == 0 && candidateCount == 0
}
