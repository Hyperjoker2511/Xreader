package app.xreader.data.extension

/**
 * v1.3.0: the BUILT-IN default sources — the first layer of
 * the three-layer source model the app agreed with its user
 * (built-in verified defaults · provider packs through the
 * repo channel · the paste-a-link learner as safety net).
 *
 * A site earns a place here ONLY by passing the full bar at
 * bundle time — catalogue ≥10 real books, a coherent book,
 * ≥98% of its stated chapter total (or the gap explained by
 * the site's own numbering), two chapters of real prose, and
 * search returning a known title — driven live against the
 * real site (build-logs/V130-BUILD-REPORT.md carries the
 * per-site verdicts and the capture evidence under
 * fixtures-capture/). A site that fails any leg is NOT here;
 * it stays reachable through the learner and packs, and the
 * report says why.
 *
 * Every config is maintainer-verified ([DeclarativeConfig
 * .maintainerVerified]) and parameterised through the family
 * builders in [BundledFamilies] — the same recipe, written
 * once per site family, instantiated per site (the
 * family-template support of plan E2). Endpoints and shapes
 * are FACTS read off the live sites and off research into
 * other readers' provider tables (facts are not code; the
 * GPL sources consulted were never copied).
 *
 * This file is the Wave-1 assembly point: the verification
 * harness's PASS verdicts land here as one [Spec] per site.
 */
object BundledDefaults {

    /**
     * One bundled default before it is instantiated: a stable
     * config id (the row's identity — never rename one that
     * has shipped), the display name, and the builder that
     * produces the verified config for the row's id/name.
     */
    data class Spec(
        val configId: String,
        val name: String,
        val build: (id: String, name: String) -> DeclarativeConfig
    )

    /** Every bundled default, in Explore display order. */
    val SPECS: List<Spec> = BundledWave1.SPECS

    /** The instantiated configs (maintainer verification is
     * stamped here so no builder can forget it). */
    fun configs(): List<DeclarativeConfig> = SPECS.map { spec ->
        val config = spec.build("bundled-${spec.configId}", spec.name)
        if (config.maintainerVerified) config
        else config.copy(maintainerVerified = true)
    }

    /**
     * The fingerprints of bundled definition lists SHIPPED in
     * earlier app versions, per config id — the refresh
     * allow-list. A stored bundled row refreshes to the new
     * bundled rules ONLY while its stored definition still
     * hashes to one of these (proof the user never changed
     * it: any Repair or hand edit rewrites the definition and
     * its hash leaves the allow-list forever). v1.3.0 is the
     * first version carrying bundled defaults, so nothing has
     * shipped before: the map starts empty and each release
     * appends the fingerprints it supersedes.
     */
    val SHIPPED_FINGERPRINTS: Map<String, Set<String>> = emptyMap()
}
