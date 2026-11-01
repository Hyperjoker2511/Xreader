package app.xreader.ui.sources

/**
 * v1.3.0 (device spot-check fix): same-domain source twins.
 *
 * When a site the user added themselves in an earlier version
 * (a paste-a-link / repo row) is ALSO a built-in default, the
 * flat "Your sources" list showed two rows with the SAME name
 * — the user's NovelBuddy and the bundled NovelBuddy were
 * indistinguishable, and which one a tap opened was a coin
 * flip. Nothing is deleted or merged (the standing rule), so
 * the list itself disambiguates:
 *
 * - the BUNDLED row is the primary for its domain: it is moved
 *   ahead of its user-added twins, keeping its plain name;
 * - each user-added twin is reported in the returned id set so
 *   the row can carry a visible "Added by you" tag.
 *
 * Rows without a resolvable host, and hosts with no bundled
 * row, keep their exact incoming order (the sort is stable
 * and touches nothing else).
 */
object SourceTwinArrangement {

    /**
     * Returns the rows reordered (bundled primaries ahead of
     * their same-host user twins) together with the ids of the
     * user-added twin rows.
     */
    fun <T> arrange(
        rows: List<T>,
        idOf: (T) -> Long,
        hostOf: (T) -> String?,
        isBundled: (T) -> Boolean
    ): Pair<List<T>, Set<Long>> {
        val hosts = rows.map { hostOf(it)?.trim()?.lowercase() }
        val bundledHosts = rows.indices
            .filter { isBundled(rows[it]) }
            .mapNotNull { hosts[it] }
            .toSet()
        if (bundledHosts.isEmpty()) return rows to emptySet()
        val twinIds = rows.indices
            .filter {
                !isBundled(rows[it]) &&
                    hosts[it] != null && hosts[it] in bundledHosts
            }
            .map { idOf(rows[it]) }
            .toSet()
        if (twinIds.isEmpty()) return rows to emptySet()

        val bundledByHost: Map<String, List<T>> = rows.indices
            .filter { isBundled(rows[it]) && hosts[it] != null }
            .groupBy({ hosts[it]!! }, { rows[it] })
        val emitted = HashSet<Long>()
        val out = ArrayList<T>(rows.size)
        for ((index, row) in rows.withIndex()) {
            val id = idOf(row)
            if (id in emitted) continue
            val host = hosts[index]
            if (id in twinIds && host != null) {
                // Pull this domain's bundled primary (and any
                // bundled siblings) ahead of its first twin.
                for (bundled in bundledByHost[host].orEmpty()) {
                    val bundledId = idOf(bundled)
                    if (emitted.add(bundledId)) out.add(bundled)
                }
            }
            emitted.add(id)
            out.add(row)
        }
        return out to twinIds
    }
}
