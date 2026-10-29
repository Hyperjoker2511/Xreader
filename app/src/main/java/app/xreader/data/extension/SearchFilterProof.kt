package app.xreader.data.extension

/**
 * The search filtering proof (v1.0.5, A5 — D18/D21): a search
 * template only counts as WORKING when it provably FILTERS. The
 * old criterion — "a real search returned some books" — passed a
 * WordPress `?s=` guess on a site whose search lives elsewhere,
 * because the wrong address returned the whole unfiltered
 * catalogue and "24 found" looked like success.
 *
 * The proof, given the book-id sets three fetches return:
 * - a REAL term (a distinctive word from a proven book's title)
 *   must find at least one book;
 * - a NONSENSE control term must find FEWER books than the real
 *   term (a template that ignores the query returns the same set
 *   for both);
 * - the real term's set must differ from the unfiltered
 *   catalogue's first page (when a catalogue was fetched).
 *
 * Pure set logic — the fetches live in the callers — so fixtures
 * pin the judgement exactly.
 */
object SearchFilterProof {

    /** The control query: a token no real title contains. */
    const val NONSENSE_TERM = "zxqwvkj"

    data class Verdict(
        val verified: Boolean,
        /** Plain-words reason when [verified] is false. */
        val reason: String? = null
    )

    fun judge(
        realIds: Set<String>,
        controlIds: Set<String>,
        catalogueIds: Set<String>?
    ): Verdict {
        if (realIds.isEmpty()) {
            return Verdict(
                false,
                "search found no books for a word from a proven book's title"
            )
        }
        if (controlIds.size >= realIds.size) {
            return Verdict(
                false,
                "search doesn't filter — a nonsense word returned " +
                    "as many books as a real one"
            )
        }
        if (catalogueIds != null && realIds == catalogueIds) {
            return Verdict(
                false,
                "search doesn't filter — it returned the whole " +
                    "catalogue unchanged"
            )
        }
        return Verdict(true)
    }
}
