package app.xreader.data.extension

/**
 * The known-site seed catalogue (v1.0.9): names + domains of
 * novel sites other reader ecosystems already catalogue —
 * the English-novel subset of Kototoro's parser catalogue
 * (its parsers are GPL-3.0, so only the public site LIST —
 * names and domains, never code — is carried here), the
 * tachiyomix repository's listed novel sites, and sites this
 * app already knows well.
 *
 * A seed is a head-start, never a verdict: the learner still
 * test-reads every site live, and a Live Source still derives
 * everything from the pages it actually gets. The seed only
 * (a) names a site when its own pages don't, and (b) marks a
 * probe's stage lines so a failure report can say the site
 * was a known one.
 */
object SeedCatalog {

    private val SEEDS: Map<String, String> = mapOf(
        // Kototoro catalogue (English-novel subset).
        "novelfull.com" to "NovelFull",
        "novelcool.com" to "NovelCool",
        "novelhi.com" to "NovelHi",
        "royalroad.com" to "Royal Road",
        "baka-tsuki.org" to "Baka-Tsuki",
        "gutenberg.org" to "Project Gutenberg",
        // Tachiyomix (Novel Sourcery) listed novel sites.
        "wtr-lab.com" to "WTR-LAB",
        "novelsparadise.site" to "NovelsParadise",
        // Sites this app already knows well.
        "novelight.net" to "Novelight",
        "chikari.moe" to "Chikari",
        "woopread.com" to "WoopRead",
        // v1.2.3: novelbuddy's canonical host is .me (the .com
        // domain redirects there); its home page carries no
        // og:site_name, which is how the raw <title> defect
        // reached saved sources on-device.
        "novelbuddy.com" to "NovelBuddy",
        "novelbuddy.me" to "NovelBuddy",
        "botitranslation.com" to "BotiTranslation"
    )

    /** The display name a seed carries for [host] (or any URL's
     * host), or null when the host isn't seeded. Subdomains of
     * a seeded domain count. */
    fun nameFor(hostOrUrl: String): String? {
        val host = CookieParsing.hostOf(hostOrUrl) ?: hostOrUrl.lowercase()
        SEEDS[host]?.let { return it }
        val parent = SEEDS.keys.firstOrNull { seed ->
            host.endsWith(".$seed")
        } ?: return null
        return SEEDS[parent]
    }

    fun isKnown(hostOrUrl: String): Boolean = nameFor(hostOrUrl) != null
}
