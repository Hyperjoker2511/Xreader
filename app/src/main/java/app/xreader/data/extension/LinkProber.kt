package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.extensionapi.HostHtml
import app.xreader.extensionapi.HostLog
import app.xreader.extensionapi.HostStore
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * "Add source from link" probing (PLAN Amendment 7): fetch the page
 * the user pasted, infer selectors ([LinkInference]), then TEST-DRIVE
 * the candidate source — a real book fetch, a real chapter list, and
 * one real chapter's text — before anything is saved. If the site
 * needs login, the outcome says so and the assisted-login flow runs
 * first (the probe's provisional session then carries the cookies).
 *
 * Failure copy is plain and leaves the door open: Phase 5's AI
 * selector retries smarter on sites these heuristics can't read —
 * and faces the very same test-drive before anything is saved.
 *
 * Proposer order (v1.0.4, D15): the built-in templates and
 * [LinkInference] first (Madara fast-path, cluster rules), then
 * [HeuristicProposer] (density + clustering, still no AI), and only
 * then — as a separate user tap — the AI selector. A site readable
 * without AI never waits for AI.
 */
class LinkProber(
    private val contextFactory: HostContextFactory,
    private val sessionStore: SessionStore
) {

    data class TestDriveResult(
        val bookTitle: String,
        val chapterCount: Int,
        val firstChapterTitle: String,
        val chapterTextPreview: String,
        val chapterTextLength: Int,
        /** v1.0.1: search was VERIFIED against the live site (a real
         * search returned a result that resolves to a book page). */
        val searchVerified: Boolean = false,
        /** Plain-words proof line, e.g. "Search works — 'moon'
         * found 4 books". Null when search couldn't be verified. */
        val searchProof: String? = null,
        /** v1.0.1: the proven chapter list (remote id + name) — the
         * save paths persist these so a verified book never opens
         * to an empty chapter list. */
        val provenChapters: List<Pair<String, String>> = emptyList(),
        /** v1.0.1: the probe found a reviews section on the book
         * page (the source will offer Reviews on Book Detail). */
        val reviewsFound: Boolean = false
    )

    /** The distinctive search word for verification: the longest
     * word of the book's title (≥4 letters), else its first word. */
    fun distinctiveWord(title: String): String {
        val words = title.split(Regex("[^A-Za-z\u4e00-\u9fff\uac00-\ud7a3]+"))
            .filter { it.isNotBlank() }
        return words.maxByOrNull { it.length }?.takeIf { it.length >= 4 }
            ?: words.firstOrNull() ?: "the"
    }

    data class Draft(
        val config: DeclarativeConfig,
        val testDrive: TestDriveResult,
        val bookUrl: String,
        val host: String
    )

    sealed interface Outcome {
        data class Ready(val draft: Draft) : Outcome
        data class LoginRequired(val loginUrl: String, val host: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun probe(rawUrl: String): Outcome {
        val url = normaliseUrl(rawUrl)
            ?: return Outcome.Failed("That doesn't look like a web address — check the link and try again.")
        val host = CookieParsing.hostOf(url)
            ?: return Outcome.Failed("That doesn't look like a web address — check the link and try again.")
        val probeClient = contextFactory.clientFor(
            sessionKey = ExtensionHttpClient.linkProbeSessionKey(host),
            label = host,
            permissions = ExtensionPermissions(domains = listOf(host)),
            rateLimiter = RateLimiter(minIntervalMs = 0)
        )
        val context = probeContext(probeClient, host)

        val firstFetch = probeClient.get(url)
        val firstResponse = when (firstFetch) {
            is AppResult.Err -> return Outcome.Failed(
                "Xreader couldn't open that page. " +
                    (firstFetch.error.detail ?: "Check the link and your connection.")
            )
            is AppResult.Ok -> firstFetch.value
        }
        var bookUrl = firstResponse.finalUrl.ifBlank { url }
        var bookDoc: Document = Jsoup.parse(firstResponse.body, bookUrl)
        if (firstResponse.status in setOf(401, 403) ||
            LinkInference.needsLogin(bookDoc, bookUrl)
        ) {
            return Outcome.LoginRequired(bookUrl, host)
        }
        if (firstResponse.status !in 200..299) {
            return Outcome.Failed(
                "The site answered with an error (HTTP ${firstResponse.status}), so Xreader couldn't look at the page."
            )
        }

        var analysis = LinkInference.analyseBookPage(bookDoc, bookUrl)

        // The pasted link may be a CHAPTER page: find its book page.
        if (analysis == null) {
            val chapterAnalysis = LinkInference.analyseChapterPage(bookDoc, bookUrl)
            if (chapterAnalysis != null) {
                var rescuedBookPage = false
                val bookLink = LinkInference.findBookPageLink(bookDoc, bookUrl)
                if (bookLink != null && bookLink != bookUrl) {
                    val bookFetch = probeClient.get(bookLink)
                    if (bookFetch is AppResult.Ok && bookFetch.value.status in 200..299) {
                        bookUrl = bookFetch.value.finalUrl.ifBlank { bookLink }
                        bookDoc = Jsoup.parse(bookFetch.value.body, bookUrl)
                        rescuedBookPage = true
                        analysis = LinkInference.analyseBookPage(bookDoc, bookUrl)
                    }
                }
                if (analysis == null && rescuedBookPage) {
                    // The book page was found but the primary
                    // inference can't read it — the heuristic
                    // proposer (D15) reads it the second way.
                    analysis = HeuristicProposer.analyseBookPage(bookDoc, bookUrl)
                }
                if (analysis == null) {
                    return Outcome.Failed(
                        "That link opened a chapter, but Xreader couldn't find the book's own page " +
                            "from it. Paste the book's main page (the one with the whole chapter list) instead."
                    )
                }
            } else {
                // v1.0.4 (D15): before giving up, the heuristic
                // proposer reads the same page a second way —
                // text density and link clustering, no AI, no key.
                // Its candidate faces the very same mandatory
                // test-drive below as every other proposer.
                analysis = HeuristicProposer.analyseBookPage(bookDoc, bookUrl)
                if (analysis == null) {
                    return Outcome.Failed(
                        "Xreader couldn't spot a chapter list or readable chapter text on that page. " +
                            "It may use a layout the automatic detector can't read — if you have a " +
                            "Gemini key saved, try the AI detector below; it reads pages the way " +
                            "a person would."
                    )
                }
            }
        }
        // v1.0.4 (D15): when the primary inference found no
        // description, the heuristic density score may still spot
        // the synopsis block (an unnamed plain div its class-name
        // search walks past).
        val inferred = analysis!!
        val book = if (inferred.descriptionSpec == null) {
            HeuristicProposer.synopsisSpec(bookDoc)?.let { spec ->
                inferred.copy(descriptionSpec = spec)
            } ?: inferred
        } else {
            inferred
        }

        // Fetch the first chapter and check its text is readable.
        val chapterFetch = probeClient.get(book.firstChapterUrl)
        val chapterResponse = when (chapterFetch) {
            is AppResult.Err -> return Outcome.Failed(
                "Xreader found the chapter list but couldn't open a chapter. " +
                    (chapterFetch.error.detail ?: "")
            )
            is AppResult.Ok -> chapterFetch.value
        }
        if (chapterResponse.status !in 200..299) {
            return Outcome.Failed(
                "Xreader found the chapter list, but the chapter page answered with an error " +
                    "(HTTP ${chapterResponse.status})."
            )
        }
        val chapterDoc = Jsoup.parse(chapterResponse.body, chapterResponse.finalUrl)
        val chapterAnalysis = LinkInference.analyseChapterPage(
            chapterDoc, chapterResponse.finalUrl
        ) ?: return Outcome.Failed(
            "Xreader found the chapter list, but the chapter pages don't show readable text " +
                "it can use. If you have a Gemini key saved, try the AI detector below."
        )

        // Optional catalogue pieces (best effort, never blocking).
        // v1.0.1 search overhaul: candidates in order — Madara
        // fast-path, the page's own search forms (GET and POST), then
        // the documented site conventions (SearchCandidates). Each
        // candidate is judged on its OWN results page (search pages
        // often list books differently from the catalogue), and the
        // final verdict comes from the test-drive below.
        val baseUrlEarly = baseOf(bookUrl)
        val searchWord = distinctiveWord(book.title ?: "")
        suspend fun analyseResultsPage(
            fetch: suspend () -> Pair<String, String>?
        ): LinkInference.ListingAnalysis? {
            val (body, finalUrl) = fetch() ?: return null
            val doc = Jsoup.parse(body, finalUrl)
            return LinkInference.analyseListingPage(doc, finalUrl, bookUrl)
                ?: LinkInference.analyseListingPage(doc, finalUrl, null)
        }
        var searchSpec: ListSpec? = null
        if (LinkInference.isMadaraPage(bookDoc)) {
            searchSpec = runCatching {
                val template = LinkInference.madaraSearchTemplate(baseUrlEarly)
                val verifyUrl = template.replace(
                    "{query}", java.net.URLEncoder.encode(searchWord, "UTF-8")
                )
                val fetched = probeClient.get(verifyUrl)
                if (fetched is AppResult.Ok && fetched.value.status in 200..299) {
                    LinkInference.madaraSearchSpec(baseUrlEarly)
                } else {
                    null
                }
            }.getOrNull()
        }
        if (searchSpec == null) {
            val forms = runCatching {
                LinkInference.findSearchForms(bookDoc, bookUrl)
            }.getOrDefault(emptyList())
            for (form in forms) {
                if (searchSpec != null) break
                searchSpec = runCatching {
                    if (form.method == "POST") {
                        val fetched = probeClient.post(
                            form.actionUrl,
                            "${form.inputName}=${SearchCandidates.encodeQuery(searchWord)}",
                            contentType = "application/x-www-form-urlencoded"
                        )
                        if (fetched is AppResult.Ok && fetched.value.status in 200..299) {
                            val doc = Jsoup.parse(fetched.value.body, fetched.value.finalUrl)
                            (LinkInference.analyseListingPage(doc, fetched.value.finalUrl, bookUrl)
                                ?: LinkInference.analyseListingPage(doc, fetched.value.finalUrl, null))
                                ?.let { listing ->
                                    ListSpec(
                                        url = form.actionUrl,
                                        results = listing.resultsSelector,
                                        title = listing.titleSpec,
                                        link = listing.linkSpec,
                                        cover = listing.coverSpec,
                                        method = "POST",
                                        body = "${form.inputName}={query}"
                                    )
                                }
                        } else {
                            null
                        }
                    } else {
                        val template = form.toTemplate()
                        val verifyUrl = template.replace(
                            "{query}", SearchCandidates.encodeQuery(searchWord)
                        ).replace("{page}", "1")
                        val listing = analyseResultsPage {
                            val fetched = probeClient.get(verifyUrl)
                            if (fetched is AppResult.Ok && fetched.value.status in 200..299) {
                                fetched.value.body to fetched.value.finalUrl
                            } else {
                                null
                            }
                        }
                        listing?.let {
                            ListSpec(
                                url = template,
                                results = it.resultsSelector,
                                title = it.titleSpec,
                                link = it.linkSpec,
                                cover = it.coverSpec
                            )
                        }
                    }
                }.getOrNull()
            }
            if (searchSpec == null) {
                // Conventions: try each documented shape on its own
                // results page; the first that lists books wins.
                for (candidateUrl in SearchCandidates.conventionUrls(baseUrlEarly, searchWord)) {
                    if (searchSpec != null) break
                    searchSpec = runCatching {
                        val listing = analyseResultsPage {
                            val fetched = probeClient.get(candidateUrl)
                            if (fetched is AppResult.Ok && fetched.value.status in 200..299) {
                                fetched.value.body to fetched.value.finalUrl
                            } else {
                                null
                            }
                        }
                        listing?.let {
                            ListSpec(
                                url = candidateUrl.replace(
                                    SearchCandidates.encodeQuery(searchWord), "{query}"
                                ),
                                results = it.resultsSelector,
                                title = it.titleSpec,
                                link = it.linkSpec,
                                cover = it.coverSpec
                            )
                        }
                    }.getOrNull()
                }
            }
            if (searchSpec == null && forms.isNotEmpty()) {
                // Best effort: keep the site's own form as the spec —
                // the test-drive verdict decides the honest flag.
                val form = forms.first()
                searchSpec = if (form.method == "POST") {
                    ListSpec(
                        url = form.actionUrl,
                        results = "a",
                        title = "@text",
                        link = "@href",
                        method = "POST",
                        body = "${form.inputName}={query}"
                    )
                } else {
                    ListSpec(
                        url = form.toTemplate(),
                        results = "a",
                        title = "@text",
                        link = "@href"
                    )
                }
            }
        }
        val baseUrl = baseOf(bookUrl)
        val popularSpec = runCatching {
            val homeFetch = probeClient.get(baseUrl)
            if (homeFetch is AppResult.Ok && homeFetch.value.status in 200..299) {
                val homeDoc = Jsoup.parse(homeFetch.value.body, homeFetch.value.finalUrl)
                LinkInference.analyseListingPage(homeDoc, homeFetch.value.finalUrl, bookUrl)
                    ?.let { listing ->
                        ListSpec(
                            url = baseUrl,
                            results = listing.resultsSelector,
                            title = listing.titleSpec,
                            link = listing.linkSpec,
                            cover = listing.coverSpec
                        )
                    }
            } else {
                null
            }
        }.getOrNull()

        val config = DeclarativeConfig(
            id = sourceIdFor(host, bookUrl),
            name = LinkInference.siteName(bookDoc) ?: host,
            lang = "en",
            baseUrl = baseUrl,
            search = searchSpec,
            sampleBookUrl = bookUrl,
            popular = popularSpec,
            latest = null,
            details = DetailsSpec(
                title = book.titleSpec,
                author = book.authorSpec,
                description = book.descriptionSpec,
                cover = book.coverUrl?.let { "meta[property=og:image]@content" },
                chapters = ChapterListSpec(
                    list = book.chapterListSelector,
                    name = book.chapterNameSpec,
                    link = book.chapterLinkSpec,
                    reverse = book.newestFirst
                )
            ),
            chapter = ChapterSpec(
                content = chapterAnalysis.contentSelector,
                title = chapterAnalysis.titleSpec,
                nextPage = chapterAnalysis.nextPageSelector
            ),
            reviews = runCatching { LinkInference.detectReviews(bookDoc) }.getOrNull()
        )

        // ── The mandatory test-drive: run the real engine ──────────
        // (Shared with Phase 5's AI selector — an AI-proposed config
        // faces exactly this gate before it can be saved.)
        return when (val drive = testDrive(config, bookUrl, context)) {
            is AppResult.Err -> Outcome.Failed(badFileReason(drive.error))
            is AppResult.Ok -> Outcome.Ready(
                Draft(
                    config = config.copy(
                        searchNeedsSetup = config.search == null || !drive.value.searchVerified
                    ),
                    testDrive = drive.value.copy(
                        reviewsFound = config.reviews != null
                    ),
                    bookUrl = bookUrl,
                    host = host
                )
            )
        }
    }

    /**
     * The mandatory test-drive for ANY candidate config — heuristic or
     * AI-proposed (Phase 5): run the real declarative engine against
     * the live site; the book's title must resolve, the chapter list
     * must be non-empty, and one chapter must yield readable text.
     * Nothing is saved anywhere by this function.
     */
    suspend fun testDrive(
        config: DeclarativeConfig,
        bookUrl: String
    ): AppResult<TestDriveResult> {
        val host = CookieParsing.hostOf(bookUrl) ?: config.baseUrl.let { CookieParsing.hostOf(it) }
            ?: return AppResult.Err(AppError.BadFile("that page address doesn't look right"))
        val probeClient = contextFactory.clientFor(
            sessionKey = ExtensionHttpClient.linkProbeSessionKey(host),
            label = host,
            permissions = ExtensionPermissions(domains = listOf(host)),
            rateLimiter = RateLimiter(minIntervalMs = 0)
        )
        return testDrive(config, bookUrl, probeContext(probeClient, host))
    }

    /**
     * Test-drive for configs that arrive WITHOUT a known book page
     * (Legado conversions, v1.0.1): take the first book from the
     * config's own popular listing, then run the full mandatory
     * drive on it. Honest failure when the listing yields nothing.
     */
    suspend fun testDriveFromCatalogue(
        config: DeclarativeConfig
    ): AppResult<TestDriveResult> {
        val host = CookieParsing.hostOf(config.baseUrl)
            ?: return AppResult.Err(AppError.BadFile("that site address doesn't look right"))
        val probeClient = contextFactory.clientFor(
            sessionKey = ExtensionHttpClient.linkProbeSessionKey(host),
            label = host,
            permissions = ExtensionPermissions(domains = listOf(host)),
            rateLimiter = RateLimiter(minIntervalMs = 0)
        )
        val context = probeContext(probeClient, host)
        val source = DeclarativeSource(config, context)
        val first = runCatching { source.popular(1).books.firstOrNull() }
            .getOrNull()
            ?: return AppResult.Err(
                AppError.BadFile(
                    "The test read couldn't find any book on that site's front list."
                )
            )
        return testDrive(config.copy(sampleBookUrl = first.id), first.id, context)
    }

    private suspend fun testDrive(
        config: DeclarativeConfig,
        bookUrl: String,
        context: SourceContext
    ): AppResult<TestDriveResult> {
        val source = DeclarativeSource(config, context)
        val testBook = SourceBook(id = bookUrl, title = "")
        return runCatching {
            val (details, chapters) = source.detailsAndChapters(testBook)
            if (details.title.isBlank()) {
                return AppResult.Err(
                    AppError.BadFile("The test read found the page but not the book's title.")
                )
            }
            if (chapters.isEmpty()) {
                return AppResult.Err(
                    AppError.BadFile("The test read couldn't build a chapter list from that page.")
                )
            }
            val firstChapter = chapters.first()
            source.chapterContent(firstChapter)
            val content = source.contentOf(firstChapter.id)
            val text = content?.blocks?.joinToString("\n") { block ->
                when (block) {
                    is app.xreader.domain.content.ChapterBlock.Paragraph -> block.text
                    is app.xreader.domain.content.ChapterBlock.Heading -> block.text
                    else -> ""
                }
            }?.trim().orEmpty()
            if (text.length < LinkInference.MIN_READABLE_CHARS) {
                return AppResult.Err(
                    AppError.BadFile(
                        "The test read opened a chapter but only found ${text.length} characters " +
                            "of text — that's not a readable chapter."
                    )
                )
            }
            // v1.0.1: search is verified HERE, in the one test-drive
            // every config (heuristic, AI, converted) must pass: one
            // real search with a distinctive word from the book's own
            // title, and at least one result must resolve back to a
            // book page on the same site.
            var searchVerified = false
            var searchProof: String? = null
            if (config.search != null) {
                val word = distinctiveWord(details.title)
                runCatching {
                    val page = source.search(word, 1)
                    val host = CookieParsing.hostOf(config.baseUrl)
                    val resolving = page.books.count { candidate ->
                        val candidateHost = CookieParsing.hostOf(candidate.id)
                        candidateHost != null && candidateHost == host &&
                            candidate.id != config.search.url
                    }
                    if (resolving >= 1) {
                        searchVerified = true
                        searchProof = "Search works — ‘$word’ found " +
                            "${page.books.size} book(s)"
                    }
                }
            }
            AppResult.Ok(
                TestDriveResult(
                    bookTitle = details.title,
                    chapterCount = chapters.size,
                    firstChapterTitle = firstChapter.name,
                    chapterTextPreview = text.take(LinkInference.PREVIEW_CHARS),
                    chapterTextLength = text.length,
                    searchVerified = searchVerified,
                    searchProof = searchProof,
                    provenChapters = chapters.map { it.id to it.name }
                )
            )
        }.getOrElse { e ->
            AppResult.Err(
                AppError.BadFile(
                    "The test read failed: ${e.message?.take(160) ?: "the site didn't answer as expected"}"
                )
            )
        }
    }

    /**
     * "Set up search" retry (v1.0.1) for an already-saved link
     * source — including sources saved BEFORE the search overhaul:
     * re-probe the source's proven book page, take the fresh search
     * spec, and face the same test-drive verdict. Returns the
     * updated config for the caller to save, or an error message.
     */
    suspend fun setupSearch(config: DeclarativeConfig): AppResult<DeclarativeConfig> {
        val bookUrl = config.sampleBookUrl ?: return AppResult.Err(
            AppError.BadFile(
                "This source was saved before search setup existed, and Xreader " +
                    "doesn't have a page to learn from. Add the site again from its " +
                    "link and search will be set up as part of that."
            )
        )
        return when (val outcome = probe(bookUrl)) {
            is Outcome.Ready -> {
                val fresh = outcome.draft.config
                if (fresh.search != null && outcome.draft.testDrive.searchVerified) {
                    AppResult.Ok(
                        config.copy(
                            search = fresh.search,
                            searchNeedsSetup = false
                        )
                    )
                } else {
                    AppResult.Err(
                        AppError.BadFile(
                            "Search still couldn't be set up for this site. " +
                                "If you have a Gemini key saved, the AI detector " +
                                "on the Add-from-link screen may read it."
                        )
                    )
                }
            }
            is Outcome.LoginRequired -> AppResult.Err(
                AppError.BadFile(
                    "This site needs you to sign in first — use the assisted " +
                        "sign-in on the source's page, then try again."
                )
            )
            is Outcome.Failed -> AppResult.Err(AppError.BadFile(outcome.message))
        }
    }

    private fun badFileReason(error: AppError): String =
        (error as? AppError.BadFile)?.reason ?: ErrorText.of(error).message

    /** A minimal context for probing: host HTTP through the probe
     * client, host HTML, a throwaway in-memory store. */
    private fun probeContext(
        client: ExtensionHttpClient,
        label: String
    ): SourceContext {
        val memoryStore = object : HostStore {
            private val map = mutableMapOf<String, String>()
            override fun get(key: String): String? = map[key]
            override fun put(key: String, value: String) {
                map[key] = value
            }
            override fun remove(key: String) {
                map.remove(key)
            }
        }
        return object : SourceContext {
            override val http: app.xreader.extensionapi.HostHttp = HostHttpImpl(client)
            override val html: HostHtml = HostHtmlImpl()
            override val store: HostStore = memoryStore
            override val log: HostLog = HostLogImpl(label)
        }
    }

    companion object {
        fun normaliseUrl(raw: String): String? {
            var candidate = raw.trim()
            if (candidate.isEmpty()) return null
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                candidate = "https://$candidate"
            }
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null
            return candidate
        }

        fun baseOf(url: String): String = runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}" + if (uri.port > 0) ":${uri.port}" else ""
        }.getOrDefault(url)

        fun sourceIdFor(host: String, bookUrl: String): String {
            val slug = host.normaliseHost()
                .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30)
                .ifBlank { "site" }
            val hash = Hashes.sha256Hex(bookUrl).take(6)
            return "link-$slug-$hash"
        }
    }
}
