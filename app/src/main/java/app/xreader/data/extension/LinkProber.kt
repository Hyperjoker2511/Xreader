package app.xreader.data.extension

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.core.error.ErrorText
import app.xreader.data.net.HttpTransport
import app.xreader.extensionapi.HostHtml
import app.xreader.extensionapi.HostLog
import app.xreader.extensionapi.HostStore
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Proposer order (v1.0.4, D15; v1.0.5, A11/A4): the known-sites
 * fast lane ([KnownSites]) for recognised domains first, then the
 * built-in templates and [LinkInference] (Madara fast-path,
 * cluster rules), then [HeuristicProposer] (density + clustering,
 * still no AI), and only then — as a separate user tap — the AI
 * selector. Repair ([probe] with tryAllProposers) test-drives
 * EVERY automatic candidate and keeps the best passing one. A
 * site readable without AI never waits for AI.
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
        val reviewsFound: Boolean = false,
        /** v1.0.5 (A5): WHY search failed its filtering proof, in
         * plain words ("search doesn't filter — …"). Null when
         * search verified or the source has no search at all. */
        val searchFailure: String? = null,
        /** v1.0.5 (A3): how many of the book's chapters are
         * lock-marked (paid) on the site. The sampled chapter is
         * always an unlocked one. */
        val lockedChapterCount: Int = 0,
        /** v1.0.6: the drive listed the book's chapters but
         * could not sample any chapter text, because EVERY
         * chapter on the test book is paid or locked. The
         * candidate PASSED with this limit recorded — it is
         * the one and only text-step outcome allowed to pass
         * without a proven chapter; any other text failure
         * (empty, unreadable, a paywall notice on an unlocked
         * chapter, a fetch error) still fails the candidate.
         * In candidate ranking a pass with this limit ranks
         * below any fully-proven pass. */
        val allChaptersLocked: Boolean = false,
        /** v1.0.5 (A6): the driven book page carried a cover the
         * source can show. False is surfaced as a warning, never
         * silently (D19's letter tiles). */
        val coverFound: Boolean = true,
        /** v1.0.5 (A6): plain-words cover warning when
         * [coverFound] is false. */
        val coverWarning: String? = null
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

    /**
     * One proposer's story in a probe (v1.0.6): which proposer
     * ran, the furthest step it reached ("book page",
     * "chapter list", "chapter text", "passed"), and a short
     * plain reason. One line per candidate actually driven —
     * persisted by Repair and pasted into problem details, so
     * a failure is debuggable from the user's own report
     * instead of a vanished on-screen message.
     */
    data class StageLine(
        val proposer: String,
        val step: String,
        val detail: String
    ) {
        /** The one-line rendering used in notes and pastes. */
        fun render(): String = "$proposer — $step: $detail"
    }

    sealed interface Outcome {
        data class Ready(
            val draft: Draft,
            /** v1.0.6: every proposer's stage line, in the
             * order they were driven. */
            val stageLines: List<StageLine> = emptyList()
        ) : Outcome

        data class LoginRequired(
            val loginUrl: String,
            val host: String,
            /** v1.0.6 (W4): the page asks for a human check, not
             * (only) a password — the sign-in screen opens in its
             * SITE_CHECK mode. */
            val siteCheck: Boolean = false
        ) : Outcome

        /**
         * A failed probe. [proposersTried] (v1.0.5.1) names the
         * proposer stages that were actually evaluated, in plain
         * words — empty when the probe refused before any stage
         * ran (a bad link, an unreachable page, a JS shell), so a
         * failure report starts from facts instead of guesses.
         * [stageLines] (v1.0.6) adds the per-proposer detail:
         * the furthest step each one reached and why it stopped.
         */
        data class Failed(
            val message: String,
            val proposersTried: List<String> = emptyList(),
            val stageLines: List<StageLine> = emptyList()
        ) : Outcome
    }

    /**
     * Probe a pasted link. [aiAvailable] (v1.0.5, A10) only
     * chooses the failure COPY: the AI detector is mentioned
     * solely when its button will actually render on the
     * Add-from-link screen; otherwise the copy points at
     * Providers & keys or a book-page link instead.
     * [tryAllProposers] (v1.0.5, A4 — Repair) test-drives every
     * automatic candidate, not just the first that analyses, and
     * keeps the best passing one.
     *
     * v1.0.5.1: the probe parses whole pages (jsoup over ~75 KB
     * documents, selector synthesis across the DOM) — that is
     * CPU work and must never run on the caller's thread, so
     * every public entry point of this class hops to
     * [Dispatchers.Default] first. Callers (view models, the
     * heal sweep) stay responsive no matter what they launch on.
     */
    suspend fun probe(
        rawUrl: String,
        aiAvailable: Boolean = false,
        tryAllProposers: Boolean = false
    ): Outcome = withContext(Dispatchers.Default) {
        probeImpl(rawUrl, aiAvailable, tryAllProposers)
    }

    /** The probe itself; see [probe] for the contract. Always
     * entered on [Dispatchers.Default] via the public wrapper. */
    private suspend fun probeImpl(
        rawUrl: String,
        aiAvailable: Boolean = false,
        tryAllProposers: Boolean = false
    ): Outcome {
        val url = normaliseUrl(rawUrl)
            ?: return Outcome.Failed("That doesn't look like a web address — check the link and try again.")
        val host = CookieParsing.hostOf(url)
            ?: return Outcome.Failed("That doesn't look like a web address — check the link and try again.")
        // v1.0.6 (W2/TR1): probes escalate — a page that arrives
        // as a JavaScript shell is re-fetched through the browser
        // view once, and the RENDERED page is what gets studied
        // below. With no renderer wired (or the policy saying no)
        // the probe runs on the static client exactly as before.
        val stack = probeStack(host)
        val escalating = stack.escalating
        val fetcher = stack.fetcher
        val context = probeContext(fetcher, host)

        val firstFetch = fetcher.get(url)
        val firstResponse = when (firstFetch) {
            is AppResult.Err -> return Outcome.Failed(
                "Xreader couldn't open that page. " +
                    (firstFetch.error.detail ?: "Check the link and your connection.")
            )
            is AppResult.Ok -> firstFetch.value
        }
        var bookUrl = firstResponse.finalUrl.ifBlank { url }
        var bookDoc: Document = Jsoup.parse(firstResponse.body, bookUrl)
        // v1.0.6 (W4): a bot-check interstitial routes to the same
        // sign-in screen in its SITE_CHECK mode — the person does
        // the check once; the saved session carries it afterwards.
        val siteCheck = SiteCheck.hasChallengeMarkers(bookDoc)
        if (firstResponse.status in setOf(401, 403) ||
            LinkInference.needsLogin(bookDoc, bookUrl) ||
            siteCheck
        ) {
            return Outcome.LoginRequired(bookUrl, host, siteCheck = siteCheck)
        }
        if (firstResponse.status !in 200..299) {
            return Outcome.Failed(
                "The site answered with an error (HTTP ${firstResponse.status}), so Xreader couldn't look at the page."
            )
        }
        // v1.0.5 (A9): a JavaScript app shell can't be read by
        // ANY static pipeline — refuse plainly, install nothing,
        // and never imply the AI detector would have saved it
        // (it reads the same fetched HTML).
        // v1.0.6: a page STILL a shell at this point was already
        // re-fetched through the browser view by the fetch layer
        // whenever that was allowed (TR1) — the copy says which
        // of the two honest things happened. When no render was
        // attempted (no renderer, master switch off, background),
        // the refusal is the pre-v1.0.6 one, word for word.
        if (LinkInference.isJsShell(bookDoc)) {
            return Outcome.Failed(
                if (escalating?.renderedAny == true) {
                    "This site builds its pages with JavaScript, and even the " +
                        "browser view couldn't get it to show its books — it may " +
                        "need a sign-in or a quick human check first, or it may " +
                        "have changed. No source was added."
                } else {
                    "This site loads its pages with JavaScript — Xreader can't read it yet, " +
                        "so no source was added. The page only exists after the site's own " +
                        "scripts run, which no automatic detector can follow."
                }
            )
        }

        // ── Candidate bookkeeping (A4) ──────────────────────────
        // Every proposer's candidate is test-driven and scored;
        // the best passing one wins, and when none pass, the
        // failure that got FURTHEST is the one reported (so the
        // message names the step that actually broke).
        var bestDraft: Draft? = null
        var bestRank: List<Int>? = null
        var bestFailure: Pair<Int, String>? = null
        // v1.0.5.1: the proposer stages actually evaluated, in
        // plain words and evaluation order — carried out on the
        // failure so the next diagnosis (Repair's note, a
        // support paste) starts from facts, not guesses.
        val proposersTried = mutableListOf<String>()
        // v1.0.6: one stage line per candidate actually driven
        // — the probe's own log, carried out on Ready and
        // Failed alike so Repair can persist it and a pasted
        // problem report shows what each proposer did.
        val stageLines = mutableListOf<StageLine>()
        // v1.0.6 (W2): when the browser view read pages during
        // this probe, the outcome's log leads with that fact —
        // Repair persists it, and the Details page can explain
        // why the source was flipped to the browser view (Q5).
        fun outcomeLines(): List<StageLine> =
            if (escalating?.renderedAny == true) {
                listOf(
                    StageLine(
                        "browser view", "pages",
                        "pages were read with the browser view — this " +
                            "site builds them with JavaScript"
                    )
                ) + stageLines
            } else {
                stageLines.toList()
            }
        fun tried(tag: String) {
            if (tag !in proposersTried) proposersTried += tag
        }
        fun consider(
            proposer: String,
            config: DeclarativeConfig,
            driveBookUrl: String,
            drive: ScoredDrive
        ) {
            when (val result = drive.result) {
                is AppResult.Ok -> {
                    stageLines += if (result.value.allChaptersLocked) {
                        StageLine(
                            proposer, "chapter text",
                            "all ${result.value.chapterCount} chapters on " +
                                "the test book are paid or locked, so the " +
                                "text couldn't be checked — passed with " +
                                "that limit"
                        )
                    } else {
                        StageLine(proposer, "passed", "the test-drive passed")
                    }
                    // Rank: a fully-proven candidate always
                    // beats a pass-with-limits (v1.0.6), then
                    // search proof, chapter count, text length.
                    val rank = listOf(
                        if (result.value.allChaptersLocked) 0 else 1,
                        if (result.value.searchVerified) 1 else 0,
                        result.value.chapterCount,
                        result.value.chapterTextLength
                    )
                    val current = bestRank
                    if (current == null || rankIsBetter(rank, current)) {
                        bestRank = rank
                        bestDraft = Draft(
                            config = config.copy(
                                searchNeedsSetup = config.search == null ||
                                    !result.value.searchVerified,
                                // v1.0.6 (TR1/Q5): a drive that
                                // passed with rendered pages marks
                                // the saved source — its fetches go
                                // straight to the browser view from
                                // now on (TR3). Repair flips a
                                // source the same way, and Details
                                // says so plainly.
                                needsRendering = config.needsRendering ||
                                    escalating?.renderedAny == true
                            ),
                            testDrive = result.value.copy(
                                reviewsFound = config.reviews != null
                            ),
                            bookUrl = driveBookUrl,
                            host = host
                        )
                    }
                }
                is AppResult.Err -> {
                    stageLines += StageLine(
                        proposer,
                        when (drive.steps) {
                            0 -> "book page"
                            1 -> "chapter list"
                            else -> "chapter text"
                        },
                        badFileReason(result.error)
                    )
                    val failure = bestFailure
                    if (failure == null || drive.steps > failure.first) {
                        bestFailure = drive.steps to badFileReason(result.error)
                    }
                }
            }
        }

        // A failure further down must never discard a candidate
        // that already passed its drive (the fast lane in Repair
        // mode): prefer the passing draft. And when an earlier
        // candidate failed DEEP into its drive (the text step),
        // its precise reason beats a vaguer local one.
        fun failOrDraft(message: String): Outcome =
            bestDraft?.let { Outcome.Ready(it, outcomeLines()) }
                ?: Outcome.Failed(
                    bestFailure?.takeIf { it.first >= 2 }?.second ?: message,
                    proposersTried.toList(),
                    outcomeLines()
                )

        // ── v1.0.5 (A11): the known-sites fast lane ─────────────
        // A recognised domain's known-good shape is driven FIRST,
        // against the pasted book page (or the catalogue's first
        // book when another page of the site was pasted). A failed
        // lane drive never blocks the ordinary pipeline below.
        //
        // v1.0.5.1: the lane is UNCONDITIONAL for recognised
        // domains. It used to be gated on the chapter-page
        // analysis returning null — but that analysis fires on
        // ANY page carrying a ≥300-char low-link prose block,
        // and a normal book page's synopsis is exactly that. On
        // real (untrimmed) pages the verified config was
        // therefore silently skipped, the guessing proposers
        // failed the drive, and Repair rolled back to whatever
        // broken rules were already stored (the novelight
        // 0-chapters device failure the trimmed fixtures never
        // reproduced). A book page's synopsis must never veto
        // curated rules: a recognised domain's known shape is
        // driven first, full stop — the chapter-page analysis
        // below keeps its one legitimate job (rescuing a pasted
        // CHAPTER link for sites the lane doesn't know).
        val entry = KnownSites.entryFor(host)
        if (entry != null) {
            val laneConfig = KnownSites.fullConfig(
                entry = entry,
                id = sourceIdFor(host, bookUrl),
                name = LinkInference.siteName(bookDoc) ?: host,
                baseUrl = baseOf(bookUrl)
            )
            if (laneConfig != null) {
                val sample = if (KnownSites.looksLikeBookPage(entry, bookUrl)) {
                    bookUrl
                } else {
                    runCatching {
                        DeclarativeSource(laneConfig, context)
                            .popular(1).books.firstOrNull()?.id
                    }.getOrNull()
                }
                if (sample != null) {
                    val laneFull = laneConfig.copy(
                        id = sourceIdFor(host, sample),
                        sampleBookUrl = sample
                    )
                    tried("fast lane")
                    consider(
                        "fast lane", laneFull, sample,
                        testDriveScored(laneFull, sample, context)
                    )
                }
            }
        }
        // The add path stops at a passing lane drive; Repair
        // (tryAllProposers) keeps going and lets every candidate
        // compete on rank (A4).
        bestDraft?.let {
            if (!tryAllProposers) return Outcome.Ready(it, outcomeLines())
        }

        tried("inference")
        var analysis = LinkInference.analyseBookPage(bookDoc, bookUrl)

        // The pasted link may be a CHAPTER page: find its book page.
        if (analysis == null) {
            val chapterAnalysis = LinkInference.analyseChapterPage(bookDoc, bookUrl)
            if (chapterAnalysis != null) {
                var rescuedBookPage = false
                val bookLink = LinkInference.findBookPageLink(bookDoc, bookUrl)
                if (bookLink != null && bookLink != bookUrl) {
                    val bookFetch = fetcher.get(bookLink)
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
                    tried("heuristics")
                    analysis = HeuristicProposer.analyseBookPage(bookDoc, bookUrl)
                }
                if (analysis == null) {
                    return failOrDraft(
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
                tried("heuristics")
                analysis = HeuristicProposer.analyseBookPage(bookDoc, bookUrl)
                if (analysis == null) {
                    return failOrDraft(
                        if (aiAvailable) {
                            "Xreader couldn't spot a chapter list or readable chapter text on that page. " +
                                "It may use a layout the automatic detector can't read — try the AI " +
                                "detector below; it reads pages the way a person would."
                        } else {
                            "Xreader couldn't spot a chapter list or readable chapter text on that page. " +
                                "It may use a layout the automatic detector can't read. Two things can " +
                                "help: paste the book's own page (the one with the whole chapter list), " +
                                "or add an AI key in More → Providers & keys and use the AI detector."
                        }
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
        // v1.0.5 (A4): Repair drives EVERY proposer's candidate —
        // the heuristic reading joins as a second candidate when
        // it genuinely reads the page differently.
        val heuristicAlt = if (tryAllProposers) {
            HeuristicProposer.analyseBookPage(bookDoc, bookUrl)
                ?.takeIf { it.chapterListSelector != book.chapterListSelector }
        } else {
            null
        }

        // Fetch the first chapter and check its text is readable.
        val chapterFetch = fetcher.get(book.firstChapterUrl)
        val chapterResponse = when (chapterFetch) {
            is AppResult.Err -> return failOrDraft(
                "Xreader found the chapter list but couldn't open a chapter. " +
                    (chapterFetch.error.detail ?: "")
            )
            is AppResult.Ok -> chapterFetch.value
        }
        if (chapterResponse.status !in 200..299) {
            return failOrDraft(
                "Xreader found the chapter list, but the chapter page answered with an error " +
                    "(HTTP ${chapterResponse.status})."
            )
        }
        val chapterDoc = Jsoup.parse(chapterResponse.body, chapterResponse.finalUrl)
        val chapterAnalysis = LinkInference.analyseChapterPage(
            chapterDoc, chapterResponse.finalUrl
        ) ?: return failOrDraft(
            if (aiAvailable) {
                "Xreader found the chapter list, but the chapter pages don't show readable text " +
                    "it can use. Try the AI detector below."
            } else {
                "Xreader found the chapter list, but the chapter pages don't show readable text " +
                    "it can use. Adding an AI key in More → Providers & keys would let the " +
                    "AI detector try reading it."
            }
        )

        // Optional catalogue pieces (best effort, never blocking).
        // v1.0.5 (A5): a search candidate is only ACCEPTED when it
        // provably FILTERS — a real word must return results and a
        // nonsense control word must return fewer (SearchFilterProof).
        // Order: the site's OWN search form first (A5a — the form
        // is a fact about the site; templates are guesses), then
        // the Madara template when the page carries Madara
        // markers, then the documented conventions. A candidate
        // that lists books but doesn't filter is remembered only
        // as a last-resort fallback — the test-drive's own
        // filtering proof has the final word either way.
        val baseUrlEarly = baseOf(bookUrl)
        val searchWord = distinctiveWord(book.title ?: "")
        suspend fun analyseResults(
            fetch: suspend () -> Pair<String, String>?
        ): LinkInference.ListingAnalysis? {
            val (body, finalUrl) = fetch() ?: return null
            val doc = Jsoup.parse(body, finalUrl)
            return LinkInference.analyseSearchResultsPage(doc, finalUrl, bookUrl)
        }
        fun specIds(spec: ListSpec, query: String): Set<String> {
            val checker = DeclarativeSource(
                DeclarativeConfig(
                    id = "search-check",
                    name = "search check",
                    baseUrl = baseUrlEarly,
                    search = spec,
                    details = DetailsSpec(
                        chapters = ChapterListSpec(
                            list = "a", name = "@text", link = "@href"
                        )
                    ),
                    chapter = ChapterSpec(content = "body")
                ),
                context
            )
            return runCatching {
                checker.search(query, 1).books.map { it.id }.toSet()
            }.getOrDefault(emptySet())
        }
        fun filters(spec: ListSpec): Boolean {
            val real = specIds(spec, searchWord)
            if (real.isEmpty()) return false
            val control = specIds(spec, SearchFilterProof.NONSENSE_TERM)
            return SearchFilterProof.judge(real, control, null).verified
        }
        var searchSpec: ListSpec? = null
        var unprovenSpec: ListSpec? = null
        fun accept(spec: ListSpec?) {
            if (spec == null || searchSpec != null) return
            if (filters(spec)) {
                searchSpec = spec
            } else if (unprovenSpec == null) {
                unprovenSpec = spec
            }
        }
        if (LinkInference.isMadaraPage(bookDoc)) {
            tried("templates")
            accept(
                runCatching {
                    LinkInference.madaraSearchSpec(baseUrlEarly)
                }.getOrNull()
            )
        }
        if (searchSpec == null) {
            val forms = runCatching {
                LinkInference.findSearchForms(bookDoc, bookUrl)
            }.getOrDefault(emptyList())
            for (form in forms) {
                if (searchSpec != null) break
                val spec = runCatching {
                    if (form.method == "POST") {
                        val fetched = fetcher.post(
                            form.actionUrl,
                            "${form.inputName}=${SearchCandidates.encodeQuery(searchWord)}",
                            contentType = "application/x-www-form-urlencoded"
                        )
                        if (fetched is AppResult.Ok && fetched.value.status in 200..299) {
                            val doc = Jsoup.parse(fetched.value.body, fetched.value.finalUrl)
                            LinkInference.analyseSearchResultsPage(
                                doc, fetched.value.finalUrl, bookUrl
                            )?.let { listing ->
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
                        val listing = analyseResults {
                            val fetched = fetcher.get(verifyUrl)
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
                accept(spec)
            }
            if (searchSpec == null) {
                // Conventions: try each documented shape on its own
                // results page; the first that lists books AND
                // provably filters wins.
                for (candidateUrl in SearchCandidates.conventionUrls(baseUrlEarly, searchWord)) {
                    if (searchSpec != null) break
                    tried("templates")
                    val spec = runCatching {
                        val listing = analyseResults {
                            val fetched = fetcher.get(candidateUrl)
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
                    accept(spec)
                }
            }
            if (searchSpec == null && unprovenSpec == null && forms.isNotEmpty()) {
                // Best effort: keep the site's own form as the
                // spec — the test-drive verdict decides the honest
                // flag (and now says WHY search failed its proof).
                val form = forms.first()
                unprovenSpec = if (form.method == "POST") {
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
        if (searchSpec == null) searchSpec = unprovenSpec
        val baseUrl = baseOf(bookUrl)
        val popularSpec = runCatching {
            val homeFetch = fetcher.get(baseUrl)
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

        val reviewsSpec = runCatching {
            LinkInference.detectReviews(bookDoc)
        }.getOrNull()
        val candidates = listOfNotNull(book, heuristicAlt)
        for (candidate in candidates) {
            if (candidate === heuristicAlt) tried("heuristics")
            var config = DeclarativeConfig(
                id = sourceIdFor(host, bookUrl),
                name = LinkInference.siteName(bookDoc) ?: host,
                lang = "en",
                baseUrl = baseUrl,
                search = searchSpec,
                sampleBookUrl = bookUrl,
                popular = popularSpec,
                latest = null,
                details = DetailsSpec(
                    // v1.0.5 (A7): a slug-derived title has no
                    // honest spec — store none, so the engine
                    // keeps the listing's title for the book.
                    title = if (candidate.titleFromSlug) {
                        null
                    } else {
                        candidate.titleSpec
                    },
                    author = candidate.authorSpec,
                    description = candidate.descriptionSpec,
                    // v1.0.5 (A6): the spec that actually
                    // reproduces the found cover — never a
                    // hardcoded og:image guess.
                    cover = candidate.coverSpec,
                    chapters = ChapterListSpec(
                        list = candidate.chapterListSelector,
                        name = candidate.chapterNameSpec,
                        link = candidate.chapterLinkSpec,
                        reverse = candidate.newestFirst
                    )
                ),
                chapter = ChapterSpec(
                    content = chapterAnalysis.contentSelector,
                    title = chapterAnalysis.titleSpec,
                    nextPage = chapterAnalysis.nextPageSelector
                ),
                reviews = reviewsSpec
            )
            if (entry != null) {
                config = KnownSites.applyOverrides(entry, config)
            }
            consider(
                if (candidate === heuristicAlt) "heuristics" else "inference",
                config, bookUrl, testDriveScored(config, bookUrl, context)
            )
            bestDraft?.let { draft ->
                // The add path stops at the first passing
                // candidate (today's behaviour); Repair
                // (tryAllProposers) drives them all and keeps
                // the best.
                if (!tryAllProposers) return Outcome.Ready(draft, outcomeLines())
            }
        }

        // ── The mandatory test-drive has spoken ─────────────────
        bestDraft?.let { return Outcome.Ready(it, outcomeLines()) }
        return Outcome.Failed(
            bestFailure?.second
                ?: "Xreader couldn't read that page well enough to build a source from it.",
            proposersTried.toList(),
            outcomeLines()
        )
    }

    /** True when candidate rank [a] beats [b]: fully-proven
     * first (a pass-with-limits never beats a proven pass),
     * then search proof, chapter count, readable text length.
     * Ties keep the EARLIER proposer (the caller only replaces
     * on a strict win). */
    private fun rankIsBetter(a: List<Int>, b: List<Int>): Boolean {
        for (i in a.indices) {
            if (a[i] != b[i]) return a[i] > b[i]
        }
        return false
    }

    /**
     * The mandatory test-drive for ANY candidate config — heuristic or
     * AI-proposed (Phase 5): run the real declarative engine against
     * the live site; the book's title must resolve, the chapter list
     * must be non-empty, and one chapter must yield readable text.
     * The single exception (v1.0.6): when every chapter on the test
     * book is paid or locked, the drive passes with that limit
     * recorded instead ([TestDriveResult.allChaptersLocked]).
     * Nothing is saved anywhere by this function.
     */
    suspend fun testDrive(
        config: DeclarativeConfig,
        bookUrl: String
    ): AppResult<TestDriveResult> = withContext(Dispatchers.Default) {
        testDriveImpl(config, bookUrl)
    }

    /** The test-drive itself; see [testDrive]. Always entered
     * on [Dispatchers.Default] via the public wrapper. */
    private suspend fun testDriveImpl(
        config: DeclarativeConfig,
        bookUrl: String
    ): AppResult<TestDriveResult> {
        val host = CookieParsing.hostOf(bookUrl) ?: config.baseUrl.let { CookieParsing.hostOf(it) }
            ?: return AppResult.Err(AppError.BadFile("that page address doesn't look right"))
        // v1.0.6: a marked config's drive renders its fetches
        // (needsRendering honoured); an unmarked one escalates
        // on shells like any probe.
        val stack = probeStack(host) { config.needsRendering }
        return testDrive(config, bookUrl, probeContext(stack.fetcher, host))
    }

    /**
     * Test-drive for configs that arrive WITHOUT a known book page
     * (Legado conversions, v1.0.1): take the first book from the
     * config's own popular listing, then run the full mandatory
     * drive on it. Honest failure when the listing yields nothing.
     */
    suspend fun testDriveFromCatalogue(
        config: DeclarativeConfig
    ): AppResult<TestDriveResult> = withContext(Dispatchers.Default) {
        testDriveFromCatalogueImpl(config)
    }

    /** The catalogue test-drive itself; see
     * [testDriveFromCatalogue]. Always entered on
     * [Dispatchers.Default] via the public wrapper. */
    private suspend fun testDriveFromCatalogueImpl(
        config: DeclarativeConfig
    ): AppResult<TestDriveResult> {
        val host = CookieParsing.hostOf(config.baseUrl)
            ?: return AppResult.Err(AppError.BadFile("that site address doesn't look right"))
        val stack = probeStack(host) { config.needsRendering }
        val context = probeContext(stack.fetcher, host)
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

    /**
     * v1.0.6: pick a FRESH sample book for a Repair probe from
     * the config's own catalogue — the stored `sampleBookUrl`
     * is never trusted as the seed, because a stored sample
     * can be exactly the wrong book (the device case: a book
     * whose every listed chapter is paid or locked, which no
     * candidate config could ever prove text against). Up to
     * the first [MAX_SAMPLE_BOOKS] catalogue books are read;
     * the first with at least one unlocked chapter wins. When
     * none qualifies, the first book WITH a chapter list is
     * returned (the drive's pass-with-limits then applies).
     * Null when the catalogue can't be read at all — the
     * caller falls back to the stored seed. Bounded: at most
     * [MAX_SAMPLE_BOOKS] book-page reads (plus the catalogue
     * itself), each through the same probe client a probe
     * would use.
     */
    suspend fun freshSampleUrl(config: DeclarativeConfig): String? =
        withContext(Dispatchers.Default) {
            val host = CookieParsing.hostOf(config.baseUrl)
                ?: return@withContext null
            val stack = probeStack(host) { config.needsRendering }
            val source = DeclarativeSource(
                config, probeContext(stack.fetcher, host)
            )
            val books = runCatching { source.popular(1).books }
                .getOrDefault(emptyList())
                .take(MAX_SAMPLE_BOOKS)
            var firstWithChapters: String? = null
            for (book in books) {
                val chapters = runCatching { source.chapters(book) }
                    .getOrDefault(emptyList())
                if (chapters.isEmpty()) continue
                if (firstWithChapters == null) firstWithChapters = book.id
                val lockedIds = runCatching { source.lockedChapterIds(book) }
                    .getOrDefault(emptySet())
                if (chapters.any { it.id !in lockedIds }) {
                    return@withContext book.id
                }
            }
            firstWithChapters
        }

    /** A test-drive outcome plus how far it got (v1.0.5, A4):
     * steps 0 = page/title, 1 = chapter list, 2 = chapter text,
     * 3 = passed. When several candidates fail, the failure with
     * the most steps is the honest one to report. */
    private data class ScoredDrive(
        val steps: Int,
        val result: AppResult<TestDriveResult>
    )

    private suspend fun testDrive(
        config: DeclarativeConfig,
        bookUrl: String,
        context: SourceContext
    ): AppResult<TestDriveResult> = testDriveScored(config, bookUrl, context).result

    /** The search filtering proof's outcome (A5), shared by
     * the fully-proven drive result and the v1.0.6
     * pass-with-limits one. */
    private data class SearchProof(
        val verified: Boolean,
        val proof: String?,
        val failure: String?
    )

    /**
     * v1.0.5 (A5): search must PROVE it filters — a real word
     * from the book's title, a nonsense control word, and the
     * unfiltered catalogue, judged by SearchFilterProof.
     */
    private fun verifySearch(
        source: DeclarativeSource,
        config: DeclarativeConfig,
        bookTitle: String
    ): SearchProof {
        if (config.search == null) return SearchProof(false, null, null)
        val word = distinctiveWord(bookTitle)
        var verified = false
        var proof: String? = null
        var failure: String? = null
        runCatching {
            val page = source.search(word, 1)
            val host = CookieParsing.hostOf(config.baseUrl)
            val resolving = page.books.count { candidate ->
                val candidateHost = CookieParsing.hostOf(candidate.id)
                candidateHost != null && candidateHost == host &&
                    candidate.id != config.search.url
            }
            val controlIds = runCatching {
                source.search(SearchFilterProof.NONSENSE_TERM, 1)
                    .books.map { it.id }.toSet()
            }.getOrDefault(emptySet())
            val catalogueIds = if (config.popular != null || config.latest != null) {
                runCatching {
                    source.popular(1).books.map { it.id }.toSet()
                }.getOrNull()
            } else {
                null
            }
            val verdict = SearchFilterProof.judge(
                page.books.map { it.id }.toSet(), controlIds, catalogueIds
            )
            if (resolving >= 1 && verdict.verified) {
                verified = true
                proof = "Search works — ‘$word’ found " +
                    "${page.books.size} book(s)"
            } else {
                failure = verdict.reason
                    ?: "search results didn't lead back to a book page"
            }
        }
        if (!verified && failure == null) {
            failure = "search couldn't be checked — the site didn't answer it"
        }
        return SearchProof(verified, proof, failure)
    }

    /**
     * v1.0.6: is this failure a TIMEOUT — the connection, not
     * the site, gave up? By the time a fetch error reaches the
     * drive it has been unwrapped into a plain message (the
     * host facade throws the transport's detail as a message),
     * so detection walks the cause chain for the exception
     * itself and matches the transport's timeout detail text.
     */
    private fun isTimeoutFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { link ->
            link is java.net.SocketTimeoutException ||
                link.message?.contains(HttpTransport.TIMEOUT_DETAIL) == true
        }

    /**
     * The scored test-drive (v1.0.5: A1 honest steps, A3
     * locked-aware sampling, A5 filtering proof, A6 cover flag).
     * The chapter-list step FAILS on an empty list (it previously
     * could pass and let the text step take the blame, D17); the
     * text step samples an UNLOCKED chapter — the first in
     * reading order, falling forward when a chapter is
     * individually unreadable. v1.0.6: when EVERY chapter on the
     * test book is paid or locked the drive PASSES WITH LIMITS
     * (the config listed the chapters; the text is simply
     * unprovable against a paywalled book) — the only text-step
     * outcome allowed to pass without a proven chapter. And a
     * fetch timeout is retried once, then reported as the
     * connection being too slow, never as a site fault.
     */
    private suspend fun testDriveScored(
        config: DeclarativeConfig,
        bookUrl: String,
        context: SourceContext
    ): ScoredDrive {
        val source = DeclarativeSource(config, context)
        val testBook = SourceBook(id = bookUrl, title = "")
        val fetched = runCatching { source.detailsAndChapters(testBook) }
            .recoverCatching { first ->
                when {
                    first is kotlinx.coroutines.CancellationException ->
                        throw first
                    // v1.0.6: one retry for a timed-out page —
                    // a slow link gets a second chance before
                    // the drive judges anything.
                    isTimeoutFailure(first) ->
                        source.detailsAndChapters(testBook)
                    else -> throw first
                }
            }
            .getOrElse { e ->
                return ScoredDrive(
                    0,
                    AppResult.Err(
                        AppError.BadFile(
                            if (isTimeoutFailure(e)) {
                                SLOW_CONNECTION_REASON
                            } else {
                                "The test read failed: " +
                                    (e.message?.take(160)
                                        ?: "the site didn't answer as expected")
                            }
                        )
                    )
                )
            }
        val (details, chapters) = fetched
        if (details.title.isBlank()) {
            return ScoredDrive(
                0,
                AppResult.Err(
                    AppError.BadFile(
                        "The test read found the page but not the book's title."
                    )
                )
            )
        }
        if (chapters.isEmpty()) {
            return ScoredDrive(
                1,
                AppResult.Err(
                    AppError.BadFile(
                        "The test read couldn't build a chapter list from that page."
                    )
                )
            )
        }
        // A3: skip lock-marked chapters when sampling text.
        val lockedIds = runCatching { source.lockedChapterIds(testBook) }
            .getOrDefault(emptySet())
        val unlocked = chapters.filter { it.id !in lockedIds }
        if (unlocked.isEmpty()) {
            // v1.0.6: PASS WITH LIMITS. The config DID its job —
            // it listed every chapter; the text is unprovable
            // only because this test book's chapters are all
            // paid or locked. Discarding correct rules over a
            // paywalled sample book was the device trap (a
            // stored all-locked sample made Repair unsavable
            // forever). The limit is recorded on the result,
            // the candidate ranks below any fully-proven one,
            // and the search proof still runs — HARD BOUNDARY:
            // this explicit all-locked outcome is the ONLY
            // text-step result that passes without a proven
            // chapter; every other text failure below still
            // fails the candidate.
            val search = verifySearch(source, config, details.title)
            val coverFoundLocked = details.coverUrl != null
            return ScoredDrive(
                3,
                AppResult.Ok(
                    TestDriveResult(
                        bookTitle = details.title,
                        chapterCount = chapters.size,
                        firstChapterTitle = chapters.first().name,
                        chapterTextPreview = "",
                        chapterTextLength = 0,
                        searchVerified = search.verified,
                        searchProof = search.proof,
                        searchFailure = search.failure,
                        provenChapters = chapters.map { it.id to it.name },
                        lockedChapterCount = lockedIds.size,
                        coverFound = coverFoundLocked,
                        coverWarning = if (coverFoundLocked) {
                            null
                        } else {
                            "No cover image was found on the book page — this book will " +
                                "show a plain letter tile until the site gives it one."
                        },
                        allChaptersLocked = true
                    )
                )
            )
        }
        var sampled: app.xreader.extensionapi.SourceChapter? = null
        var bestText = ""
        var lockedEvidence = false
        var firstError: Throwable? = null
        var timeoutError: Throwable? = null
        for (candidate in unlocked.take(MAX_TEXT_SAMPLES)) {
            fun readText(): String {
                source.chapterContent(candidate)
                return source.contentOf(candidate.id)?.blocks
                    ?.joinToString("\n") { block ->
                        when (block) {
                            is app.xreader.domain.content.ChapterBlock.Paragraph ->
                                block.text
                            is app.xreader.domain.content.ChapterBlock.Heading ->
                                block.text
                            else -> ""
                        }
                    }?.trim().orEmpty()
            }
            val text = runCatching { readText() }.getOrElse { e ->
                when {
                    e is kotlinx.coroutines.CancellationException -> throw e
                    // v1.0.6: a timed-out chapter fetch is
                    // retried once before it counts as a failure.
                    isTimeoutFailure(e) ->
                        runCatching { readText() }.getOrElse { retryError ->
                            if (retryError is
                                kotlinx.coroutines.CancellationException
                            ) {
                                throw retryError
                            }
                            if (timeoutError == null) timeoutError = retryError
                            ""
                        }
                    else -> {
                        if (firstError == null) firstError = e
                        ""
                    }
                }
            }
            if (text.length > bestText.length) bestText = text
            if (text.length >= LinkInference.MIN_READABLE_CHARS) {
                sampled = candidate
                break
            }
            if (ChapterLocks.pageSuggestsLocked(text)) lockedEvidence = true
        }
        val provenChapter = sampled ?: return ScoredDrive(
            2,
            AppResult.Err(
                AppError.BadFile(
                    when {
                        // v1.0.6: the connection, not the site,
                        // failed this drive — say so plainly.
                        timeoutError != null -> SLOW_CONNECTION_REASON
                        lockedEvidence ->
                            "The test read opened a chapter, but it asks for payment " +
                                "or sign-in instead of showing text — the readable " +
                                "chapters on this site appear to be locked."
                        bestText.isEmpty() && firstError != null ->
                            "The test read failed: " +
                                (firstError!!.message?.take(160)
                                    ?: "the site didn't answer as expected")
                        else ->
                            "The test read opened a chapter but only found " +
                                "${bestText.length} characters of text — that's not " +
                                "a readable chapter."
                    }
                )
            )
        )
        val text = bestText
        val search = verifySearch(source, config, details.title)
        // v1.0.5 (A6): a missing cover is a flagged warning,
        // never a silent letter tile.
        val coverFound = details.coverUrl != null
        return ScoredDrive(
            3,
            AppResult.Ok(
                TestDriveResult(
                    bookTitle = details.title,
                    chapterCount = chapters.size,
                    firstChapterTitle = provenChapter.name,
                    chapterTextPreview = text.take(LinkInference.PREVIEW_CHARS),
                    chapterTextLength = text.length,
                    searchVerified = search.verified,
                    searchProof = search.proof,
                    searchFailure = search.failure,
                    provenChapters = chapters.map { it.id to it.name },
                    lockedChapterCount = lockedIds.size,
                    coverFound = coverFound,
                    coverWarning = if (coverFound) {
                        null
                    } else {
                        "No cover image was found on the book page — this book will " +
                            "show a plain letter tile until the site gives it one."
                    }
                )
            )
        )
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

    /**
     * v1.0.6 (W1/W2): the probe family's fetch stack for [host] —
     * a fresh probe client, wrapped in the escalating decorator
     * when a renderer is wired (null then, and [fetcher] is the
     * client itself: the probe behaves exactly as it did before
     * the browser view existed).
     */
    private data class ProbeStack(
        val client: ExtensionHttpClient,
        val escalating: EscalatingPageFetcher?
    ) {
        val fetcher: PageFetcher get() = escalating ?: client
    }

    private fun probeStack(
        host: String,
        needsRendering: () -> Boolean = { false }
    ): ProbeStack {
        val limiter = RateLimiter(minIntervalMs = 0)
        val client = contextFactory.clientFor(
            sessionKey = ExtensionHttpClient.linkProbeSessionKey(host),
            label = host,
            permissions = ExtensionPermissions(domains = listOf(host)),
            rateLimiter = limiter
        )
        val escalating = contextFactory.escalatingFetcherOrNull(
            client = client,
            sessionKey = ExtensionHttpClient.linkProbeSessionKey(host),
            purpose = RenderPurpose.PROBE,
            needsRendering = needsRendering,
            permissions = { ExtensionPermissions(domains = listOf(host)) },
            rateLimiter = limiter
        )
        return ProbeStack(client, escalating)
    }

    /** A minimal context for probing: host HTTP through the probe
     * fetcher, host HTML, a throwaway in-memory store. */
    private fun probeContext(
        client: PageFetcher,
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
        /** Chapters sampled for readable text per drive (A3). */
        const val MAX_TEXT_SAMPLES = 3

        /** Catalogue books a Repair sample pick may read
         * before falling back (v1.0.6). */
        const val MAX_SAMPLE_BOOKS = 3

        /** v1.0.6: the plain-words failure when the CONNECTION
         * gave up mid-drive — the site is never blamed for a
         * slow link. */
        const val SLOW_CONNECTION_REASON =
            "The connection was too slow to finish checking this site. " +
                "Try again when your connection is better."

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
