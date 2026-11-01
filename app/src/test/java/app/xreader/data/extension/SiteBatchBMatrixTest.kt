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
 * Site-batch B probe runs (DIAGNOSIS ONLY — no assertions about
 * probe success; verdicts land in `build-logs/ws2-raw-b/<site>.txt`
 * and in the batch-B probe report).
 *
 * Drives the AUTOMATIC add path ([LinkProber.probe], aiAvailable =
 * false, tryAllProposers = false — the ordinary user paste) against
 * the REAL captured pages in `~/workspace/xreader/fixtures-capture/`,
 * served through [FakeTransport] keyed by the URLs they were
 * captured from (see each host's README in the capture tree).
 *
 * Same transport honesty rules as SiteBatchMatrixTest: captured
 * pages answer at their real URLs (plus the bare-host spelling);
 * any OTHER chapter URL of the same book is answered with the ONE
 * captured chapter page (aliased requests recorded); everything else
 * answers 404 and is recorded as "uncaptured" — a failure caused by
 * one is a CAPTURE limit, not a learner failure. API endpoints
 * captured as JSON (novelarchive) or pinned live (mvlempyr) are
 * served via [SiteSpec.extraExact] so the log shows whether the
 * probe ever asks for them.
 */
class SiteBatchBMatrixTest {

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
        /** Distinct-href oracle patterns over a page's anchors. */
        val bookHrefPattern: Regex? = null,
        val chapterHrefPattern: Regex? = null,
        /** Extra exact URL → capture file (API JSON endpoints). */
        val extraExact: Map<String, String> = emptyMap()
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
        put(spec.homeUrl, spec.homeFile)
        // The probe fetches the site root as scheme://host.
        if (spec.homeUrl != null && spec.homeFile != null) {
            val root = LinkProber.baseOf(spec.homeUrl)
            exact.putIfAbsent(root, Captured(body(spec.homeFile)))
            exact.putIfAbsent("$root/", Captured(body(spec.homeFile)))
        }
        put(spec.listUrl, spec.listFile)
        put(spec.bookUrl, spec.bookFile)
        if (spec.chapterUrl != null && spec.chapterFile != null) {
            exact[spec.chapterUrl] =
                Captured(body(spec.chapterFile), spec.chapterCode)
        }
        for ((url, file) in spec.extraExact) put(url, file)

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
            // Query-agnostic serving for captured page URLs.
            exact[url.substringBefore('?')]?.let {
                // Pagination/ajax endpoints are NOT the page itself.
                if (!url.contains("ajax") && !url.contains("chapter-option")) {
                    return@FakeTransport serve(it)
                }
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
        val outDir = File(root, "build-logs/ws2-raw-b").apply { mkdirs() }
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
    fun novelarchive() = runSite(
        SiteSpec(
            key = "novelarchive", dirName = "novelarchive.cc", permDomain = "novelarchive.cc",
            probeEntries = listOf(
                "book" to "https://novelarchive.cc/novel?id=6adec7c99d5fbd59aa68281d",
                "root" to "https://novelarchive.cc/"
            ),
            homeUrl = "https://novelarchive.cc/", homeFile = "home.html",
            bookUrl = "https://novelarchive.cc/novel?id=6adec7c99d5fbd59aa68281d",
            bookFile = "book.html",
            bookHrefPattern = Regex("/novel\\?id="),
            chapterHrefPattern = Regex("/reader\\?novel="),
            extraExact = mapOf(
                "https://novelarchive.cc/api/novels/6adec7c99d5fbd59aa68281d" to "api-novel.json",
                "https://novelarchive.cc/api/novels/6adec7c99d5fbd59aa68281d/chapters/1" to "api-chapter1.json"
            )
        )
    )

    @Test(timeout = 300_000)
    fun noveli() = runSite(
        SiteSpec(
            key = "noveli", dirName = "noveli.to", permDomain = "noveli.to",
            probeEntries = listOf("root" to "https://noveli.to/"),
            homeUrl = "https://noveli.to/", homeFile = "home.html",
            bookUrl = null, bookFile = null
        )
    )

    @Test(timeout = 300_000)
    fun lnori() = runSite(
        SiteSpec(
            key = "lnori", dirName = "lnori.com", permDomain = "lnori.com",
            probeEntries = listOf(
                "book" to "https://lnori.com/series/10041/ishura",
                "root" to "https://lnori.com/"
            ),
            homeUrl = "https://lnori.com/", homeFile = "home.html",
            listUrl = "https://lnori.com/library", listFile = "library.html",
            bookUrl = "https://lnori.com/series/10041/ishura", bookFile = "book.html",
            bookHrefPattern = Regex("/series/\\d+/[^/]+$"),
            chapterHrefPattern = Regex("/series/10041/(chapter|read)")
        )
    )

    @Test(timeout = 300_000)
    fun mvlempyr() = runSite(
        SiteSpec(
            key = "mvlempyr", dirName = "mvlempyr.com", permDomain = "mvlempyr.io",
            probeEntries = listOf(
                "book" to "https://www.mvlempyr.io/novel/above-the-great-dao",
                "root" to "https://www.mvlempyr.io/"
            ),
            homeUrl = "https://www.mvlempyr.io/", homeFile = "home.html",
            bookUrl = "https://www.mvlempyr.io/novel/above-the-great-dao", bookFile = "book.html",
            chapterUrl = "https://www.mvlempyr.io/chapter/6937-1",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://www\\.mvlempyr\\.io/chapter/6937-.*"),
            bookHrefPattern = Regex("/novel/[^/]+$"),
            chapterHrefPattern = Regex("/chapter/6937-"),
            extraExact = mapOf(
                "https://chap.heliosarchive.online/wp-json/wp/v2/posts?tags=624493815&per_page=500&page=1" to "api-chapters-p1.json"
            )
        )
    )

    @Test(timeout = 300_000)
    fun novelBin() = runSite(
        SiteSpec(
            key = "novel-bin", dirName = "novel-bin.com", permDomain = "novel-bin.com",
            probeEntries = listOf(
                "book" to "https://novel-bin.com/novel-bin/star-odyssey/",
                "root" to "https://novel-bin.com/"
            ),
            homeUrl = "https://novel-bin.com/", homeFile = "home.html",
            bookUrl = "https://novel-bin.com/novel-bin/star-odyssey/", bookFile = "book.html",
            chapterUrl = "https://novel-bin.com/novel-bin/star-odyssey/chapter-1",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://novel-bin\\.com/novel-bin/star-odyssey/chapter-.*"),
            bookHrefPattern = Regex("/novel-bin/[^/]+/$"),
            chapterHrefPattern = Regex("/novel-bin/star-odyssey/chapter-")
        )
    )

    @Test(timeout = 300_000)
    fun lncrawler() = runSite(
        SiteSpec(
            key = "lncrawler", dirName = "lncrawler.monster", permDomain = "lncrawler.monster",
            probeEntries = listOf("root" to "https://lncrawler.monster/"),
            homeUrl = "https://lncrawler.monster/", homeFile = "home.html",
            bookUrl = null, bookFile = null
        )
    )

    @Test(timeout = 300_000)
    fun xnovel() = runSite(
        SiteSpec(
            key = "xnovel", dirName = "xnovel.me", permDomain = "xnovel.me",
            probeEntries = listOf(
                "book" to "https://xnovel.me/31933-spores-evolution",
                "root" to "https://xnovel.me/"
            ),
            homeUrl = "https://xnovel.me/", homeFile = "home.html",
            bookUrl = "https://xnovel.me/31933-spores-evolution", bookFile = "book.html",
            chapterUrl = "https://xnovel.me/31933-spores-evolution/9124204-chapter-1-great-just-add-a-little-more-cumin-and-i-can-eat-it",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://xnovel\\.me/31933-spores-evolution/\\d+-chapter-.*"),
            bookHrefPattern = Regex("xnovel\\.me/\\d+-[^/]+$"),
            chapterHrefPattern = Regex("/31933-spores-evolution/\\d+-chapter-")
        )
    )

    @Test(timeout = 600_000)
    fun noveldex() = runSite(
        SiteSpec(
            key = "noveldex", dirName = "noveldex.io", permDomain = "noveldex.io",
            probeEntries = listOf(
                "book" to "https://noveldex.io/series/novel/i-married-the-dragon-i-killed",
                "root" to "https://noveldex.io/"
            ),
            homeUrl = "https://noveldex.io/", homeFile = "home.html",
            bookUrl = "https://noveldex.io/series/novel/i-married-the-dragon-i-killed",
            bookFile = "book.html",
            chapterUrl = "https://noveldex.io/series/novel/i-married-the-dragon-i-killed/chapter/1",
            chapterFile = "chapter.html",
            chapterAlias = Regex("https://noveldex\\.io/series/novel/i-married-the-dragon-i-killed/chapter/.*"),
            bookHrefPattern = Regex("/series/novel/[^/]+$"),
            chapterHrefPattern = Regex("/series/novel/i-married-the-dragon-i-killed/chapter/")
        )
    )
}
