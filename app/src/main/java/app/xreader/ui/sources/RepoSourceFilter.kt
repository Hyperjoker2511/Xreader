package app.xreader.ui.sources

import app.xreader.data.extension.ForeignSite

/**
 * v1.3.2 (scope E): the per-repo screen's list logic — in-list
 * search, language filter, and the novels-led ordering — kept
 * pure so the gate can pin it without a device.
 *
 * Novels-led (the user's design call): a repository that mixes
 * novels with other media (Keiyoushi carries manga code too)
 * LEADS with its novel shelf; the rest waits behind the screen's
 * "show all" toggle. A novels-only repo (LNReader) never needs
 * the toggle. The novel heuristic is deliberately plain: the
 * name says "novel", the entry carries a known reading family
 * template, or the repo itself is a novel ecosystem.
 */
object RepoSourceFilter {

    /** One row of the per-repo screen, whatever its origin
     * (native package or foreign site). */
    data class Row(
        val name: String,
        val lang: String,
        val domain: String,
        val novel: Boolean
    )

    fun isNovelSite(site: ForeignSite, ecosystemName: String): Boolean =
        site.name.contains("novel", ignoreCase = true) ||
            site.family != null ||
            ecosystemName.contains("LNReader", ignoreCase = true) ||
            ecosystemName.contains("Novel", ignoreCase = true)

    fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://")
            .substringBefore('/').removePrefix("www.")

    /** One row's verdict under the screen's current controls. */
    fun matches(
        row: Row,
        query: String,
        language: String?,
        novelsOnly: Boolean
    ): Boolean {
        val q = query.trim().lowercase()
        return (q.isEmpty() ||
            row.name.lowercase().contains(q) ||
            row.domain.lowercase().contains(q)) &&
            (language == null ||
                row.lang.split(",").any {
                    it.trim().equals(language, ignoreCase = true)
                }) &&
            (!novelsOnly || row.novel)
    }

    /** Novels first, then A–Z inside each shelf — the list
     * always leads with reading. */
    val ORDER: Comparator<Row> =
        compareBy<Row>({ if (it.novel) 0 else 1 }, { it.name.lowercase() })

    /**
     * Filter + order a repo's rows.
     *
     * @param query in-list search text (name or domain).
     * @param language the chosen language code, or null for all.
     * @param novelsOnly when true (and the repo HAS non-novel
     * rows to hide), non-novel rows are dropped — the "show all"
     * toggle's off state.
     */
    fun filter(
        rows: List<Row>,
        query: String,
        language: String?,
        novelsOnly: Boolean
    ): List<Row> = rows
        .filter { matches(it, query, language, novelsOnly) }
        .sortedWith(ORDER)

    /** True when a "show all" toggle has anything to reveal. */
    fun hasNonNovelRows(rows: List<Row>): Boolean = rows.any { !it.novel }

    /** The languages a filter chip row should offer. */
    fun languages(rows: List<Row>): List<String> =
        rows.map { it.lang }.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}
