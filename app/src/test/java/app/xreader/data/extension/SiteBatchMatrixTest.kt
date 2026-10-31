package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.domain.content.ChapterBlock
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Site-batch Phase A / WS2-MATRIX (DIAGNOSIS ONLY — no assertions
 * about probe success; the verdicts land in
 * `build-logs/ws2-raw/<site>.txt` and in the WS2 report).
 *
 * Drives the AUTOMATIC add path ([LinkProber.probe], aiAvailable =
 * false, tryAllProposers = false — the ordinary user paste) against
 * the REAL captured pages in `~/workspace/xreader/fixtures-capture/`,
 * served through [FakeTransport] keyed by the URLs they were
 * captured from (see each host's README in the capture tree).
 *
 * Transport honesty rules:
 *  - Captured pages answer at their real URLs (plus the bare-host
 *    spelling of the same page — the probe fetches the site root
 *    as `scheme://host` with no path).
 *  - Any OTHER chapter URL of the SAME book is answered with the
 *    ONE captured chapter page of that book (same template, same
 *    book): the probe's test-drive samples first/middle/last
 *    chapters, and only one chapter per book was captured. Every
 *    such aliased request is recorded in the raw log, and S4 is
 *    ALSO checked directly (no aliasing) on the exact captured
 *    chapter URL / document below.
 *  - Everything else (search-results pages, Madara POST
 *    ajax/chapters, chapter-list pagination pages beyond the
 *    captured ones, novelight's read-chapter ajax endpoint, …)
 *    is NOT captured and answers 404. Those requests are recorded
 *    as "uncaptured" in the raw log — a failure caused by one is
 *    a CAPTURE limit, not a learner failure.
 *  - royalroad chapter URLs answer with the captured Cloudflare
 *    block page at HTTP 403; ranobes answers its captured block
 *    page at HTTP 503 — the statuses the capture actually got.
 *
 * Stage-isolated direct checks (LinkInference over the captured
 * documents) run alongside every probe, so a probe that stops at
 * one stage still yields evidence for the other stages.
 */
class SiteBatchMatrixTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── capture location (WS1's walk-up pattern) ────────────────

    private fun xreaderRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val base = dir ?: return null
            if (File(base, "fixtures-capture").isDirectory) return base
            dir = base.parentFile
        }
        return null
    }

    private fun captureDir(name: String): File? =
        xreaderRoot()?.let { File(it, "fixtures-capture/$name") }
            ?.takeIf { it.isDirectory }

    private fun readCapture(dir: File, name: String): String =
        File(dir, name).readText(Charsets.UTF_8)

    // ── site definitions ────────────────────────────────────────

    private data class Captured(val body: String, val code: Int = 200)

    private data class SiteSpec(
        val key: String,
        val dirName: String,
        val permDomain: String,
        /** label → URL for each probe run. */
        val probeEntries: List<Pair<String, String>>,
        val homeUrl: String?,
        val homeFile: String?,
        val listUrl: String? = null,
        val listFile: String? = null,
        val bookUrl: String?,
        val bookFile: String?,
        val chapterUrl: String? = null,
        val chapterFile: String? = null,
        val chapterCode: Int = 200,
        /** Aliased chapter serving: other chapter URLs of the same
         * book → the captured chapter page. */
        val chapterAlias: Regex? = null,
        val aliasServesChapterFile: Boolean = true,
        /** Distinct-href oracle patterns over a page's anchors. */
        val bookHrefPattern: Regex? = null,
        val chapterHrefPattern: Regex? = null,
        val chaptersUrl: String? = null,
        val chaptersFile: String? = null,
        /** Whole root/home served with a non-200 status (blocked). */
        val homeCode: Int = 200,
        val novelightPagination: Boolean = false
    )

    private class Recorder {
        val requests = mutableListOf<String>()
        val aliased = mutableListOf<String>()
        val uncaptured = mutableListOf<String>()
    }

    private fun transportFor(
        spec: SiteSpec,
        dir: File,
        rec: Recorder
    ): FakeTransport {
        val cache = mutableMapOf<String, String>()
        fun body(file: String): String =
            cache.getOrPut(file) { readCapture(dir, file) }

        // Exact URL → captured response.
        val exact = mutableMapOf<String, Captured>()
        fun put(url: String?, file: String?, code: Int = 200) {
            if (url != null && file != null) exact[url] = Captured(body(file), code)
        }
        put(spec.homeUrl, spec.homeFile, spec.homeCode)
        // The probe fetches the site root as scheme://host.
        if (spec.homeUrl != null && spec.homeFile != null) {
            val root = LinkProber.baseOf(spec.homeUrl)
            exact.putIfAbsent(root, Captured(body(spec.homeFile), spec.homeCode))
            exact.putIfAbsent("$root/", Captured(body(spec.homeFile), spec.homeCode))
        }
        put(spec.listUrl, spec.listFile)
        put(spec.bookUrl, spec.bookFile)
        put(spec.chaptersUrl, spec.chaptersFile)
        if (spec.chapterUrl != null && spec.chapterFile != null) {
            exact[spec.chapterUrl] =
                Captured(body(spec.chapterFile), spec.chapterCode)
        }

        return FakeTransport { request ->
            val url = request.url
            rec.requests += "${request.method} $url"
            fun serve(c: Captured) =
                AppResult.Ok(FakeTransport.response(c.code, c.body))
            if (request.method != "GET") {
                rec.uncaptured += "${request.method} $url"
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(404, "not captured (POST)")
                )
            }
            exact[url]?.let { return@FakeTransport serve(it) }
            // Query-agnostic serving for captured page URLs (a
            // chapter page's ?page=2 slice, a catalogue ?page=,
            // a search-shaped reload of the root, …): the captured
            // page is the only version of that path we hold.
            exact[url.substringBefore('?')]?.let {
                // Pagination/ajax endpoints are NOT the page itself.
                if (!url.contains("ajax") && !url.contains("chapter-option")) {
                    return@FakeTransport serve(it)
                }
            }
            if (spec.novelightPagination &&
                url.contains("/book/ajax/chapter-pagination")
            ) {
                val page = Regex("[?&]page=(\\d+)").find(url)
                    ?.groupValues?.get(1)?.toIntOrNull()
                val file = when (page) {
                    2 -> "chpag2.json"
                    16 -> "chpag16.json"
                    else -> null
                }
                if (file != null) {
                    return@FakeTransport serve(Captured(body(file)))
                }
                rec.uncaptured += "GET $url"
                return@FakeTransport AppResult.Ok(
                    FakeTransport.response(404, "pagination page not captured")
                )
            }
            val alias = spec.chapterAlias
            if (alias != null && alias.containsMatchIn(url) &&
                spec.chapterFile != null
            ) {
                rec.aliased += url
                return@FakeTransport serve(
                    Captured(body(spec.chapterFile), spec.chapterCode)
                )
            }
            rec.uncaptured += "GET $url"
            AppResult.Ok(FakeTransport.response(404, "not captured"))
        }
    }

    private fun prober(transport: FakeTransport): LinkProber {
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        return LinkProber(factory, testSessionStore())
    }

    // ── logging helpers ─────────────────────────────────────────

    private fun StringBuilder.line(s: String = "") {
        appendLine(s)
        println(s)
    }

    private fun orderStats(chapters: List<SourceChapter>): String {
        val numbers = chapters.mapNotNull { ch ->
            ChapterNumbers.parse(ch.name)
                ?: Regex("(\\d+(?:\\.\\d+)?)[^/]*$").find(
                    ch.id.trimEnd('/').substringAfterLast('/')
                )?.groupValues?.get(1)?.toFloatOrNull()
                ?: Regex("chapter[-_/]?(\\d+(?:\\.\\d+)?)")
                    .find(ch.id.lowercase())?.groupValues?.get(1)?.toFloatOrNull()
        }
        if (numbers.size < 2) return "numbered=${numbers.size} (too few to judge order)"
        var asc = 0
        var desc = 0
        for (i in 1 until numbers.size) {
            if (numbers[i] > numbers[i - 1]) asc++
            if (numbers[i] < numbers[i - 1]) desc++
        }
        return "numbered=${numbers.size}/${chapters.size} first=${numbers.first()} " +
            "last=${numbers.last()} ascSteps=$asc descSteps=$desc"
    }

    private fun distinctHrefs(doc: Document, pattern: Regex?): Int {
        if (pattern == null) return -1
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            href.takeIf { pattern.containsMatchIn(it) }
        }.distinct().size
    }

    // ── the per-site run ────────────────────────────────────────

    private fun runSite(spec: SiteSpec) {
        val dir = captureDir(spec.dirName)
        assumeTrue("capture ${spec.dirName} not on disk", dir != null)
        dir!!
        val log = StringBuilder()
        log.line("════════ SITE ${spec.key} (capture dir ${spec.dirName}) ════════")

        // ── direct, stage-isolated checks on the captured docs ──
        fun docOf(file: String?, url: String?): Document? =
            if (file == null || url == null) null
            else Jsoup.parse(readCapture(dir, file), url)

        val homeDoc = docOf(spec.homeFile, spec.homeUrl)
        if (homeDoc != null) {
            val listing = runCatching {
                LinkInference.analyseListingPage(homeDoc, spec.homeUrl!!, spec.bookUrl)
            }.getOrNull()
            log.line(
                "DIRECT S1 home(${spec.homeUrl}): anchors=" +
                    "${homeDoc.select("a[href]").size} bookPatternHrefs=" +
                    "${distinctHrefs(homeDoc, spec.bookHrefPattern)} " +
                    "listingAnalysis=" + (listing?.let {
                        "count=${it.count} results='${it.resultsSelector}' " +
                            "title='${it.titleSpec}' link='${it.linkSpec}' " +
                            "cover=${it.coverSpec} linkPattern=${it.linkPattern}"
                    } ?: "null")
            )
        }
        val listDoc = docOf(spec.listFile, spec.listUrl)
        if (listDoc != null) {
            val listing = runCatching {
                LinkInference.analyseListingPage(listDoc, spec.listUrl!!, spec.bookUrl)
            }.getOrNull()
            log.line(
                "DIRECT S1 list(${spec.listUrl}): anchors=" +
                    "${listDoc.select("a[href]").size} bookPatternHrefs=" +
                    "${distinctHrefs(listDoc, spec.bookHrefPattern)} " +
                    "listingAnalysis=" + (listing?.let {
                        "count=${it.count} results='${it.resultsSelector}' " +
                            "title='${it.titleSpec}' link='${it.linkSpec}'"
                    } ?: "null")
            )
        }
        val bookDoc = docOf(spec.bookFile, spec.bookUrl)
        if (bookDoc != null) {
            val analysis = runCatching {
                LinkInference.analyseBookPage(bookDoc, spec.bookUrl!!)
            }.getOrNull()
            log.line(
                "DIRECT S2/S3 book(${spec.bookUrl}): anchors=" +
                    "${bookDoc.select("a[href]").size} chapterPatternHrefs=" +
                    "${distinctHrefs(bookDoc, spec.chapterHrefPattern)} " +
                    "nextDataChapterMentions=" +
                    "${Regex("chapter-\\d+").findAll(bookDoc.html()).count()} " +
                    "bookAnalysis=" + (analysis?.let {
                        "title='${it.title}' titleFromSlug=${it.titleFromSlug} " +
                            "chapterCount=${it.chapterCount} newestFirst=${it.newestFirst} " +
                            "listSelector='${it.chapterListSelector}' " +
                            "nameSpec='${it.chapterNameSpec}' linkSpec='${it.chapterLinkSpec}' " +
                            "firstChapter=${it.firstChapterUrl} " +
                            "cover=${it.coverUrl != null} authorSpec=${it.authorSpec} " +
                            "descSpec=${it.descriptionSpec}"
                    } ?: "null")
            )
        }
        val chaptersDoc = docOf(spec.chaptersFile, spec.chaptersUrl)
        if (chaptersDoc != null) {
            val t0 = System.currentTimeMillis()
            val analysis = runCatching {
                LinkInference.analyseBookPage(chaptersDoc, spec.chaptersUrl!!)
            }.getOrNull()
            log.line(
                "DIRECT S3 chaptersPage(${spec.chaptersUrl}): bytes=" +
                    "${readCapture(dir, spec.chaptersFile!!).length} anchors=" +
                    "${chaptersDoc.select("a[href]").size} chapterPatternHrefs=" +
                    "${distinctHrefs(chaptersDoc, spec.chapterHrefPattern)} " +
                    "bookAnalysis=" + (analysis?.let {
                        "chapterCount=${it.chapterCount} newestFirst=${it.newestFirst} " +
                            "firstChapter=${it.firstChapterUrl}"
                    } ?: "null") +
                    " analysedInMs=${System.currentTimeMillis() - t0}"
            )
        }
        val chapterDoc = docOf(spec.chapterFile, spec.chapterUrl)
        if (chapterDoc != null) {
            val analysis = runCatching {
                LinkInference.analyseChapterPage(chapterDoc, spec.chapterUrl!!)
            }.getOrNull()
            log.line(
                "DIRECT S4 chapter(${spec.chapterUrl}) code=${spec.chapterCode}: " +
                    "pTags=${chapterDoc.select("p").size} " +
                    "imgTags=${chapterDoc.select("img").size} " +
                    "chapterAnalysis=" + (analysis?.let {
                        "content='${it.contentSelector}' textLength=${it.textLength}"
                    } ?: "null")
            )
        }

        // ── the automatic add path, per entry ──
        for ((label, entryUrl) in spec.probeEntries) {
            val rec = Recorder()
            val transport = transportFor(spec, dir, rec)
            val t0 = System.currentTimeMillis()
            val outcome = runBlocking {
                prober(transport).probe(entryUrl, aiAvailable = false)
            }
            val elapsed = System.currentTimeMillis() - t0
            log.line("── PROBE [$label] $entryUrl (${elapsed}ms, ${rec.requests.size} requests, aliased=${rec.aliased.size}, uncaptured=${rec.uncaptured.size})")
            when (outcome) {
                is LinkProber.Outcome.LoginRequired -> log.line(
                    "  OUTCOME LoginRequired loginUrl=${outcome.loginUrl} siteCheck=${outcome.siteCheck}"
                )
                is LinkProber.Outcome.Failed -> {
                    log.line("  OUTCOME Failed: ${outcome.message}")
                    log.line("  proposersTried=${outcome.proposersTried} renderWorthTrying=${outcome.renderWorthTrying}")
                }
                is LinkProber.Outcome.Ready -> {
                    val draft = outcome.draft
                    val td = draft.testDrive
                    val cfg = draft.config
                    log.line("  OUTCOME Ready bookUrl=${draft.bookUrl} host=${draft.host}")
                    log.line(
                        "  DRIVE title='${td.bookTitle}' chapterCount=${td.chapterCount} " +
                            "firstChapterTitle='${td.firstChapterTitle}' textLength=${td.chapterTextLength} " +
                            "searchVerified=${td.searchVerified} searchProof=${td.searchProof} " +
                            "searchFailure=${td.searchFailure} locked=${td.lockedChapterCount} " +
                            "allChaptersLocked=${td.allChaptersLocked} lockStateUnknown=${td.lockStateUnknown} " +
                            "coverFound=${td.coverFound} listShape=${td.listShape} sampleTitles=${td.sampleTitles}"
                    )
                    log.line(
                        "  CONFIG id=${cfg.id} name='${cfg.name}' baseUrl=${cfg.baseUrl} " +
                            "needsRendering=${cfg.needsRendering} renderWait=${cfg.renderWaitSelector} " +
                            "sampleBookUrl=${cfg.sampleBookUrl}"
                    )
                    log.line("  CONFIG popular=${cfg.popular} ")
                    log.line("  CONFIG latest=${cfg.latest} search=${cfg.search}")
                    log.line(
                        "  CONFIG details.title=${cfg.details.title} author=${cfg.details.author} " +
                            "desc=${cfg.details.description} cover=${cfg.details.cover}"
                    )
                    log.line("  CONFIG chapters=${cfg.details.chapters}")
                    log.line("  CONFIG chapter=${cfg.chapter}")
                    // Independent drive of the derived config.
                    driveDerived(spec, cfg, draft.bookUrl, log)
                }
            }
            for (sl in when (outcome) {
                is LinkProber.Outcome.Ready -> outcome.stageLines
                is LinkProber.Outcome.Failed -> outcome.stageLines
                else -> emptyList()
            }) {
                log.line("  STAGE ${sl.render()}")
            }
            log.line("  ALIASED ${rec.aliased.distinct().take(12)}")
            log.line(
                "  UNCAPTURED ${rec.uncaptured.distinct().take(25)}"
            )
        }

        // Persist the raw log next to the other build logs.
        val root = xreaderRoot()!!
        val outDir = File(root, "build-logs/ws2-raw").apply { mkdirs() }
        File(outDir, "${spec.key}.txt").writeText(log.toString())
        assertTrue(log.isNotEmpty())
    }

    /** S1–S4 through the derived config's own engine, on a fresh
     * transport (same serving rules). */
    private fun driveDerived(
        spec: SiteSpec,
        config: DeclarativeConfig,
        bookUrl: String,
        log: StringBuilder
    ) {
        val dir = captureDir(spec.dirName) ?: return
        val rec = Recorder()
        val transport = transportFor(spec, dir, rec)
        val context = testContext(
            transport, tmp.newFolder(),
            permissions = ExtensionPermissions(domains = listOf(spec.permDomain))
        )
        val source = DeclarativeSource(config, context)
        runCatching { source.popular(1) }
            .onSuccess { page ->
                log.line(
                    "  DRIVE S1 popular(1): count=${page.books.size} hasNext=${page.hasNextPage} " +
                        page.books.take(5).joinToString(" | ") { "'${it.title}' -> ${it.id}" }
                )
            }
            .onFailure { log.line("  DRIVE S1 popular(1): ERROR ${it.javaClass.simpleName}: ${it.message?.take(200)}") }
        val book = SourceBook(id = bookUrl, title = "")
        val fetched = runCatching { source.detailsAndChapters(book) }
            .onFailure {
                log.line("  DRIVE S2/S3 detailsAndChapters: ERROR ${it.javaClass.simpleName}: ${it.message?.take(200)}")
            }.getOrNull() ?: return
        val (details, chapters) = fetched
        log.line(
            "  DRIVE S2 details: title='${details.title}' author=${details.author} " +
                "descLen=${details.description?.length} cover=${details.coverUrl}"
        )
        log.line(
            "  DRIVE S3 chapters: count=${chapters.size} dupes=" +
                "${chapters.size - chapters.distinctBy { it.id }.size} ${orderStats(chapters)}"
        )
        log.line(
            "  DRIVE S3 first3=" + chapters.take(3).joinToString(" | ") { "'${it.name}' -> ${it.id}" } +
                " last3=" + chapters.takeLast(3).joinToString(" | ") { "'${it.name}' -> ${it.id}" }
        )
        val locked = runCatching { source.lockedChapterIds(book) }.getOrNull()
        log.line("  DRIVE S3 lockedIds=${locked?.size}")
        // S4: the captured chapter when it is in the list, else the
        // first chapter; plus the first chapter when different.
        val targets = linkedSetOf<SourceChapter>()
        chapters.firstOrNull { it.id == spec.chapterUrl }?.let { targets += it }
        chapters.firstOrNull()?.let { targets += it }
        for (target in targets.take(2)) {
            runCatching { source.chapterContent(target) }
                .onSuccess {
                    val content = source.contentOf(target.id)
                    val paragraphs = content?.blocks?.count { it is ChapterBlock.Paragraph } ?: -1
                    val images = content?.blocks?.count { it is ChapterBlock.Image } ?: -1
                    val chars = content?.blocks?.sumOf { b ->
                        when (b) {
                            is ChapterBlock.Paragraph -> b.text.length
                            is ChapterBlock.Heading -> b.text.length
                            else -> 0
                        }
                    } ?: -1
                    log.line(
                        "  DRIVE S4 chapterContent(${target.id}): htmlLen=${it.html.length} " +
                            "paragraphs=$paragraphs images=$images textChars=$chars"
                    )
                }
                .onFailure {
                    log.line("  DRIVE S4 chapterContent(${target.id}): ERROR ${it.javaClass.simpleName}: ${it.message?.take(200)}")
                }
        }
        log.line("  DRIVE requests=${rec.requests.size} aliased=${rec.aliased.size} uncaptured=${rec.uncaptured.distinct().take(15)}")
    }

    // ── sites ───────────────────────────────────────────────────

    @Test(timeout = 300_000)
    fun woopread() = runSite(
        SiteSpec(
            key = "woopread", dirName = "woopread.com", permDomain = "woopread.com",
            probeEntries = listOf("book" to "https://woopread.com/series/the-star-painting-genius"),
            homeUrl = "https://woopread.com/", homeFile = "home.html",
            bookUrl = "https://woopread.com/series/the-star-painting-genius", bookFile = "book.html",
            chapterUrl = "https://woopread.com/series/the-star-painting-genius/chapter-1-the-shadow-and-the-swirl",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://woopread\\.com/series/the-star-painting-genius/chapter-.*"),
            bookHrefPattern = Regex("/series/[^/]+$"),
            chapterHrefPattern = Regex("/series/the-star-painting-genius/chapter-")
        )
    )

    @Test(timeout = 300_000)
    fun royalroad() = runSite(
        SiteSpec(
            key = "royalroad", dirName = "royalroad.com", permDomain = "royalroad.com",
            probeEntries = listOf("book" to "https://www.royalroad.com/fiction/185692/the-great-xia-gunner"),
            homeUrl = "https://www.royalroad.com/home", homeFile = "home.html",
            bookUrl = "https://www.royalroad.com/fiction/185692/the-great-xia-gunner", bookFile = "book.html",
            chapterUrl = "https://www.royalroad.com/fiction/185692/the-great-xia-gunner/chapter/3796677/chapter-one-birth",
            chapterFile = "block.html", chapterCode = 403,
            chapterAlias = Regex("https://www\\.royalroad\\.com/fiction/185692/the-great-xia-gunner/chapter/.*"),
            bookHrefPattern = Regex("/fiction/\\d+/[^/]+$"),
            chapterHrefPattern = Regex("/fiction/185692/the-great-xia-gunner/chapter/")
        )
    )

    @Test(timeout = 300_000)
    fun wtrLab() = runSite(
        SiteSpec(
            key = "wtr-lab", dirName = "wtr-lab.com", permDomain = "wtr-lab.com",
            probeEntries = listOf(
                "book" to "https://wtr-lab.com/en/novel/111733/the-doll-master-miss-cant-be-a-lich",
                "list" to "https://wtr-lab.com/en/novel-list",
                "root" to "https://wtr-lab.com/"
            ),
            homeUrl = "https://wtr-lab.com/", homeFile = "home.html",
            listUrl = "https://wtr-lab.com/en/novel-list", listFile = "list.html",
            bookUrl = "https://wtr-lab.com/en/novel/111733/the-doll-master-miss-cant-be-a-lich",
            bookFile = "book.html",
            chapterUrl = "https://wtr-lab.com/en/novel/111733/the-doll-master-miss-cant-be-a-lich/chapter-1",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://wtr-lab\\.com/en/novel/111733/the-doll-master-miss-cant-be-a-lich/chapter-.*"),
            bookHrefPattern = Regex("/en/novel/\\d+/[^/]+$"),
            chapterHrefPattern = Regex("/en/novel/111733/the-doll-master-miss-cant-be-a-lich/chapter-")
        )
    )

    @Test(timeout = 300_000)
    fun chikari() = runSite(
        SiteSpec(
            key = "chikari", dirName = "chikari.moe", permDomain = "chikari.moe",
            probeEntries = listOf(
                "book" to "https://chikari.moe/series/i-want-to-enjoy-slow-living",
                "root" to "https://chikari.moe/"
            ),
            homeUrl = "https://chikari.moe/", homeFile = "home.html",
            bookUrl = "https://chikari.moe/series/i-want-to-enjoy-slow-living", bookFile = "book.html",
            chapterUrl = "https://chikari.moe/series/i-want-to-enjoy-slow-living/1",
            chapterFile = "chapter.html",
            bookHrefPattern = Regex("/series/[^/]+$"),
            chapterHrefPattern = Regex("/series/i-want-to-enjoy-slow-living/\\d+$")
        )
    )

    @Test(timeout = 300_000)
    fun wuxiaworldSite() = runSite(
        SiteSpec(
            key = "wuxiaworld-site", dirName = "wuxiaworld.site", permDomain = "wuxiaworld.site",
            probeEntries = listOf("book" to "https://wuxiaworld.site/novel/complete-martial-arts-attributes-spp/"),
            homeUrl = "https://wuxiaworld.site/", homeFile = "home.html",
            bookUrl = "https://wuxiaworld.site/novel/complete-martial-arts-attributes-spp/", bookFile = "book.html",
            chapterUrl = "https://wuxiaworld.site/novel/complete-martial-arts-attributes-spp/chapter-1/",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://wuxiaworld\\.site/novel/complete-martial-arts-attributes-spp/chapter-.*"),
            bookHrefPattern = Regex("/novel/[^/]+/?$"),
            chapterHrefPattern = Regex("/novel/complete-martial-arts-attributes-spp/chapter-")
        )
    )

    @Test(timeout = 300_000)
    fun novelcrow() = runSite(
        SiteSpec(
            key = "novelcrow", dirName = "novelcrow.com", permDomain = "novelcrow.com",
            probeEntries = listOf("book" to "https://novelcrow.com/comic/mass-breeding-blankken/"),
            homeUrl = "https://novelcrow.com/", homeFile = "home.html",
            bookUrl = "https://novelcrow.com/comic/mass-breeding-blankken/", bookFile = "book.html",
            chapterUrl = "https://novelcrow.com/comic/mass-breeding-blankken/1-mass-breeding-chapter-1-blankken/",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://novelcrow\\.com/comic/mass-breeding-blankken/[^/]+/?$"),
            bookHrefPattern = Regex("/comic/[^/]+/?$"),
            chapterHrefPattern = Regex("/comic/mass-breeding-blankken/[^/]+")
        )
    )

    @Test(timeout = 300_000)
    fun lightnovelheaven() = runSite(
        SiteSpec(
            key = "lightnovelheaven", dirName = "lightnovelheaven.com", permDomain = "lightnovelheaven.com",
            probeEntries = listOf("book" to "https://lightnovelheaven.com/series/against-heavens-will/"),
            homeUrl = "https://lightnovelheaven.com/", homeFile = "home.html",
            bookUrl = "https://lightnovelheaven.com/series/against-heavens-will/", bookFile = "book.html",
            chapterUrl = "https://lightnovelheaven.com/series/against-heavens-will/chapter-1/",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://lightnovelheaven\\.com/series/against-heavens-will/chapter-.*"),
            bookHrefPattern = Regex("/series/[^/]+/?$"),
            chapterHrefPattern = Regex("/series/against-heavens-will/chapter-")
        )
    )

    @Test(timeout = 300_000)
    fun novelfull() = runSite(
        SiteSpec(
            key = "novelfull", dirName = "novelfull.com", permDomain = "novelfull.com",
            probeEntries = listOf("book" to "https://novelfull.com/hidden-marriage.html"),
            homeUrl = "https://novelfull.com/", homeFile = "home.html",
            bookUrl = "https://novelfull.com/hidden-marriage.html", bookFile = "book.html",
            chapterUrl = "https://novelfull.com/hidden-marriage/chapter-2165-end-my-little-heart.html",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://novelfull\\.com/hidden-marriage/.*"),
            bookHrefPattern = Regex("novelfull\\.com/[^/]+\\.html$"),
            chapterHrefPattern = Regex("/hidden-marriage/chapter-")
        )
    )

    @Test(timeout = 300_000)
    fun freewebnovel() = runSite(
        SiteSpec(
            key = "freewebnovel", dirName = "freewebnovel.com", permDomain = "freewebnovel.com",
            probeEntries = listOf(
                "book" to "https://freewebnovel.com/novel/the-innkeeper",
                "root" to "https://freewebnovel.com/"
            ),
            homeUrl = "https://freewebnovel.com/home", homeFile = "home.html",
            bookUrl = "https://freewebnovel.com/novel/the-innkeeper", bookFile = "book.html",
            chapterUrl = "https://freewebnovel.com/novel/the-innkeeper/chapter-1",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://freewebnovel\\.com/novel/the-innkeeper/chapter-.*"),
            bookHrefPattern = Regex("/novel/[^/]+$"),
            chapterHrefPattern = Regex("/novel/the-innkeeper/chapter-")
        )
    )

    @Test(timeout = 300_000)
    fun novelcool() = runSite(
        SiteSpec(
            key = "novelcool", dirName = "novelcool.com", permDomain = "novelcool.com",
            probeEntries = listOf("book" to "https://www.novelcool.com/novel/Cheating-Men-Must-Die.html"),
            homeUrl = "https://www.novelcool.com/", homeFile = "home.html",
            bookUrl = "https://www.novelcool.com/novel/Cheating-Men-Must-Die.html", bookFile = "book.html",
            chapterUrl = "https://www.novelcool.com/chapter/Ch-513/14950690.html",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://www\\.novelcool\\.com/chapter/.*"),
            bookHrefPattern = Regex("/novel/[^/]+\\.html$"),
            chapterHrefPattern = Regex("/chapter/Ch-")
        )
    )

    @Test(timeout = 600_000)
    fun novelhi() = runSite(
        SiteSpec(
            key = "novelhi", dirName = "novelhi.com", permDomain = "novelhi.com",
            probeEntries = listOf(
                "book" to "https://novelhi.com/novel/fantasy/mad-god",
                "chapters" to "https://novelhi.com/novel/fantasy/mad-god/chapters"
            ),
            homeUrl = "https://novelhi.com/", homeFile = "home.html",
            bookUrl = "https://novelhi.com/novel/fantasy/mad-god", bookFile = "book.html",
            chaptersUrl = "https://novelhi.com/novel/fantasy/mad-god/chapters",
            chaptersFile = "chapters.html",
            chapterUrl = "https://novelhi.com/novel/fantasy/mad-god/1",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://novelhi\\.com/novel/fantasy/mad-god/\\d+$"),
            bookHrefPattern = Regex("/novel/[^/]+/[^/]+$"),
            chapterHrefPattern = Regex("/novel/fantasy/mad-god/\\d+$")
        )
    )

    @Test(timeout = 300_000)
    fun novelsparadise() = runSite(
        SiteSpec(
            key = "novelsparadise", dirName = "novelsparadise.site", permDomain = "novelsparadise.site",
            probeEntries = listOf(
                "book" to "https://novelsparadise.site/np-light/series/silent-crown/",
                "root" to "https://novelsparadise.site/"
            ),
            homeUrl = "https://novelsparadise.site/np-light/", homeFile = "home.html",
            bookUrl = "https://novelsparadise.site/np-light/series/silent-crown/", bookFile = "book.html",
            chapterUrl = "https://novelsparadise.site/np-light/series/silent-crown/chapter-1/",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://novelsparadise\\.site/np-light/series/silent-crown/chapter-.*"),
            bookHrefPattern = Regex("/np-light/series/[^/]+/?$"),
            chapterHrefPattern = Regex("/np-light/series/silent-crown/chapter-")
        )
    )

    @Test(timeout = 300_000)
    fun novelight() = runSite(
        SiteSpec(
            key = "novelight", dirName = "novelight", permDomain = "novelight.net",
            probeEntries = listOf(
                "book" to "https://novelight.net/book/return-of-the-sword-god-rank-civil-servant",
                "catalog" to "https://novelight.net/catalog/"
            ),
            homeUrl = "https://novelight.net/catalog/", homeFile = "catalog1.html",
            bookUrl = "https://novelight.net/book/return-of-the-sword-god-rank-civil-servant",
            bookFile = "swordgod.html",
            chapterUrl = "https://novelight.net/book/chapter/346374",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://novelight\\.net/book/chapter/\\d+$"),
            bookHrefPattern = Regex("/book/[^/]+$"),
            chapterHrefPattern = Regex("/book/chapter/\\d+$"),
            novelightPagination = true
        )
    )

    @Test(timeout = 300_000)
    fun ranobes() = runSite(
        SiteSpec(
            key = "ranobes", dirName = "ranobes.net", permDomain = "ranobes.net",
            probeEntries = listOf("root" to "https://ranobes.net/"),
            homeUrl = "https://ranobes.net/", homeFile = "block.html", homeCode = 503,
            bookUrl = null, bookFile = null,
            bookHrefPattern = Regex("/[^/]+/$"),
            chapterHrefPattern = Regex("/chapter")
        )
    )
}
