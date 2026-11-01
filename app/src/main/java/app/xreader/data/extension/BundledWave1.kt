package app.xreader.data.extension

/**
 * v1.3.0 Wave 1 — the bundled defaults assembly. Each entry is
 * a site that passed the full bar live (catalogue ≥10 real
 * books · a coherent book · chapters ≥98% of the stated total
 * or the gap explained by the site's own numbering · two
 * chapters of real prose · search returning a known title) —
 * see build-logs/V130-BUILD-REPORT.md for the per-site verdicts
 * and fixtures-capture/ for the evidence. A site that failed
 * any leg does not appear here; the report names it and the
 * failing leg instead.
 *
 * Recipes are parameterised through [BundledFamilies] (one
 * builder per site family) and [BundledSingles] (the API
 * sites). Novelight ships the very config its KnownSites lane
 * already proves on devices, so the bundled default and the
 * paste-a-link lane can never drift apart.
 */
object BundledWave1 {

    val SPECS: List<BundledDefaults.Spec> = listOf(
        BundledDefaults.Spec("novelbuddy", "NovelBuddy") { id, name ->
            BundledSingles.novelBuddy(id, name)
        },
        BundledDefaults.Spec("chikari", "Chikari") { id, name ->
            BundledSingles.chikari(id, name)
        },
        BundledDefaults.Spec("royalroad", "Royal Road") { id, name ->
            BundledSingles.royalRoad(id, name)
        },
        BundledDefaults.Spec("scribblehub", "Scribble Hub") { id, name ->
            BundledSingles.scribbleHub(id, name)
        },
        BundledDefaults.Spec("novelbin", "NovelBin") { id, name ->
            BundledSingles.novelPing(id, name)
        },
        BundledDefaults.Spec("novelfull", "NovelFull") { id, name ->
            BundledFamilies.allNovelOptions(
                id, name, "https://novelfull.com",
                listingResults = "div.col-truyen-main div.row",
                listingTitle = "h3.truyen-title a",
                listingLink = "h3.truyen-title a@href",
                listingCover = "img.cover@src",
                detailsTitle = "h3.title",
                detailsAuthor = "div.info a",
                detailsDescription = "div.desc-text"
            )
        },
        BundledDefaults.Spec("novelfull-net", "NovelFull.net") { id, name ->
            BundledFamilies.allNovelOptions(
                id, name, "https://novelfull.net",
                listingResults = "div.li",
                listingTitle = "h3.tit a",
                listingLink = "h3.tit a@href",
                listingCover = "img@src",
                detailsTitle = "h1",
                detailsAuthor = "a[href*='/author/']",
                detailsDescription = "meta[property='og:description']@content"
            )
        },
        BundledDefaults.Spec("freewebnovel", "FreeWebNovel") { id, name ->
            BundledFamilies.freeWebNovelFamily(
                id, name, "https://freewebnovel.com",
                popular = BundledFamilies.freeWebCards(
                    "https://freewebnovel.com/sort/most-popular/{page}"
                ),
                search = BundledFamilies.freeWebCards(
                    "https://freewebnovel.com/search?keyword={query}"
                )
            )
        },
        BundledDefaults.Spec("libread", "LibRead") { id, name ->
            BundledFamilies.freeWebNovelFamily(
                id, name, "https://libread.com",
                popular = BundledFamilies.freeWebCards(
                    "https://libread.com/sort/latest-release/{page}"
                ),
                search = BundledFamilies.freeWebCards(
                    url = "https://libread.com/search",
                    method = "POST",
                    body = "searchkey={query}"
                ),
                // Chapter pages 302 to freewebnovel.com for the
                // text itself — the whitelist must allow the hop.
                extraDomains = listOf("freewebnovel.com")
            )
        },
        BundledDefaults.Spec("wuxiabox", "WuxiaBox") { id, name ->
            BundledFamilies.empireCms(id, name, "https://www.wuxiabox.com")
        },
        BundledDefaults.Spec("fanmtl", "FanMTL") { id, name ->
            BundledFamilies.empireCms(id, name, "https://www.fanmtl.com")
        },
        BundledDefaults.Spec("novelight", "Novelight") { id, name ->
            val entry = KnownSites.entryFor("novelight.net")
                ?: error("novelight lane entry missing")
            KnownSites.fullConfig(entry, id, name, "https://novelight.net")
                ?: error("novelight lane config missing")
        },
        BundledDefaults.Spec("sonicmtl", "SonicMTL") { id, name ->
            BundledFamilies.madara(
                id, name, "https://www.sonicmtl.com",
                popularUrl = "https://www.sonicmtl.com/novel/",
                coverSelector = "img@src",
                searchResults = "div.c-tabs-item",
                searchTitle = "div.post-title a",
                detailsTitle = "h1",
                detailsDescription = "div.description-summary div.summary__content",
                chaptersViaAjaxPost = true
            )
        },
        BundledDefaults.Spec("hiraethtranslation", "Hiraeth Translation") { id, name ->
            BundledFamilies.madara(
                id, name, "https://hiraethtranslation.com",
                popularUrl = "https://hiraethtranslation.com/novel/page/{page}/",
                coverSelector = "img@src",
                searchResults = "div.c-tabs-item",
                searchTitle = "div.post-title a",
                detailsTitle = "h1",
                detailsDescription = "div.description-summary div.summary__content",
                chaptersViaAjaxPost = false
            )
        },
        BundledDefaults.Spec("wordrain69", "Wordrain69") { id, name ->
            // Recipe derived from the Apache-2.0 Mangayomi index
            // entry and proven live; attribution in the About
            // screen's open-source licences list.
            BundledFamilies.madara(
                id, name, "https://wordrain69.com",
                popularUrl = "https://wordrain69.com/manga/page/{page}/",
                coverSelector = "img@data-src",
                searchResults = "div.c-tabs-item__content",
                searchTitle = ".post-title a",
                detailsTitle = "h1",
                detailsDescription = "div.summary__content",
                chaptersViaAjaxPost = true
            )
        },
        BundledDefaults.Spec("faqwiki", "FaqWiki") { id, name ->
            BundledSingles.faqWiki(id, name)
        },
        BundledDefaults.Spec("wntl", "Web Novel Translations") { id, name ->
            BundledSingles.wntl(id, name)
        },
        BundledDefaults.Spec("mtlnovel", "MtlNovel") { id, name ->
            BundledSingles.mtlNovel(id, name)
        },
        BundledDefaults.Spec("nobadnovel", "NoBadNovel") { id, name ->
            BundledSingles.noBadNovel(id, name)
        },
        BundledDefaults.Spec("asianovel", "AsiaNovel") { id, name ->
            BundledSingles.asiaNovel(id, name)
        },
        BundledDefaults.Spec("woopread", "WoopRead") { id, name ->
            BundledSingles.woopRead(id, name)
        },
        BundledDefaults.Spec("noveli", "Noveli") { id, name ->
            BundledSingles.noveli(id, name)
        },
        BundledDefaults.Spec("novelarchive", "NovelArchive") { id, name ->
            BundledSingles.novelArchive(id, name)
        },
        BundledDefaults.Spec("wetriedtls", "WeTriedTLS") { id, name ->
            BundledSingles.weTriedTls(id, name)
        },
        // v1.3.2 (items 75/75b): FenrirRealm returns as a
        // SIGN-IN-SUPPORTED default — its API legs passed
        // anonymously at capture; chapter text unlocks via
        // the one-time sign-in hand-off (the LOGIN RULE).
        BundledDefaults.Spec("fenrirealm", "FenrirRealm") { id, name ->
            BundledSingles.fenrirRealm(id, name)
        },
        // ── Render-backed (Cloudflare) defaults — the user's
        // 2026-11-01 01:17 decision: needsRendering +
        // maintainerVerified under the E4 proof-pending
        // policy. DEVICE-PROOF-PENDING; NOT live-verified
        // passes (see the report's own category).
        BundledDefaults.Spec("novelfire", "NovelFire") { id, name ->
            BundledSingles.novelFireFamily(
                id, name, "https://novelfire.net"
            )
        },
        BundledDefaults.Spec("novelphoenix", "Novel Phoenix") { id, name ->
            BundledSingles.novelFireFamily(
                id, name, "https://novelphoenix.com"
            )
        },
        BundledDefaults.Spec("ranovel", "Ranovel") { id, name ->
            // Madara; catalogue/book/search/chapters all passed
            // statically — only chapter TEXT is Cloudflare-
            // challenged, so the render engine carries it.
            BundledFamilies.madara(
                id, name, "https://ranovel.com",
                popularUrl = "https://ranovel.com/novel/page/{page}/",
                coverSelector = "img@src",
                searchResults = "div.c-tabs-item__content",
                searchTitle = "div.post-title a",
                detailsTitle = ".post-title h1",
                detailsDescription = "div.summary__content",
                chaptersViaAjaxPost = true
            ).copy(needsRendering = true)
        },
        BundledDefaults.Spec("brightnovel", "BrightNovel") { id, name ->
            BundledSingles.brightNovel(id, name)
        },
        BundledDefaults.Spec("syosetu", "Syosetu") { id, name ->
            BundledSingles.syosetu(id, name)
        }
    )
}
