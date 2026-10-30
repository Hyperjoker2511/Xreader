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
        /** v1.0.6.1 (P4): the drive could not READ the book's
         * lock marks (the lock read itself failed), so no chapter
         * could be proven unlocked; the candidate passed only
         * because its sampled text passed the prose gate. The
         * limit is recorded so the verdict stays honest — the
         * text was read directly, the locks were never checked.
         * In candidate ranking a pass with this limit ranks below
         * a clean fully-proven pass and above an all-locked
         * pass-with-limits. The curated fast lane never carries
         * it (hand-verified configs are exempt from the gate). */
        val lockStateUnknown: Boolean = false,
        /** v1.0.5 (A6): the driven book page carried a cover the
         * source can show. False is surfaced as a warning, never
         * silently (D19's letter tiles). */
        val coverFound: Boolean = true,
        /** v1.0.5 (A6): plain-words cover warning when
         * [coverFound] is false. */
        val coverWarning: String? = null,
        /** v1.0.6.2 (F5): the semantic gate's verdict on the
         * site's list shape for this drive — CLEAN (the items
         * read as books), BORDERLINE (some post-shaped titles
         * among books; the borderline AI check may be consulted
         * by the flow), FEED (the items read as posts, not
         * books — such a drive never returns Ok). Lane-verified
         * candidates and drives that couldn't read a list
         * report CLEAN: the gate did not judge them. */
        val listShape: ListShapeVerdict = ListShapeVerdict.CLEAN,
        /** v1.0.6.2 (F5): the listing titles the semantic gate
         * judged (up to a small sample) — carried out so the
         * borderline AI check can be shown what the drive saw. */
        val sampleTitles: List<String> = emptyList()
    )

    /** v1.0.6.2 (F5): what a test-drive's semantic gate made of
     * the site's list — see [TestDriveResult.listShape]. */
    enum class ListShapeVerdict { CLEAN, BORDERLINE, FEED }

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
            val stageLines: List<StageLine> = emptyList(),
            /** v1.0.7: the plain probe failed and a renderer
             * is wired and permitted — one automatic retry
             * through the browser view is worth it (the
             * Add-from-link flow takes it without asking; the
             * user never picks a mode). Never set on a probe
             * that itself ran forced, on early refusals (bad
             * link, login, connection), or when no render
             * could actually happen. (Whether anything already
             * rendered during the plain pass does NOT clear it:
             * a rendered home page says nothing about the book
             * page the probe walked into.) */
            val renderWorthTrying: Boolean = false
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
        tryAllProposers: Boolean = false,
        // v1.0.6.2 (F5): the borderline AI verify hook — asked
        // at most once per probe, only when a passing
        // candidate's list shape judged BORDERLINE, with the
        // sampled listing titles and a chapter-text excerpt.
        // true = "these are books", false = "they are not",
        // null = the AI is unavailable (no key, feature off,
        // call failed) and the deterministic verdict stands.
        // Null (the default) = no AI check is wired in.
        semanticVerifier: (suspend (List<String>, String) -> Boolean?)? = null,
        // v1.0.7: run this probe through the browser view from
        // the first fetch (the automatic escalation retry — see
        // Outcome.Failed.renderWorthTrying). The saved source
        // is marked needsRendering when the drive passes, as
        // with any rendered probe (TR1/Q5).
        forceRender: Boolean = false
    ): Outcome = withContext(Dispatchers.Default) {
        probeImpl(rawUrl, aiAvailable, tryAllProposers, semanticVerifier, forceRender = forceRender)
    }

    /** The probe itself; see [probe] for the contract. Always
     * entered on [Dispatchers.Default] via the public wrapper.
     * [walkedIn] is the F4 guard: a probe that already walked
     * in from a home page never walks in again. */
    private suspend fun probeImpl(
        rawUrl: String,
        aiAvailable: Boolean = false,
        tryAllProposers: Boolean = false,
        semanticVerifier: (suspend (List<String>, String) -> Boolean?)? = null,
        walkedIn: Boolean = false,
        forceRender: Boolean = false
    ): Outcome {
        val url = normaliseUrl(rawUrl)
        if (url == null) {
            // v1.0.6.1 (P1/P6): the refusal stays word for word;
            // the rejected input's fingerprint (length, invisible
            // code points) goes to the diagnostic log instead, so
            // the next refusal arrives with its evidence attached
            // in Copy details.
            DiagnosticLog.record(
                "add from link",
                "That doesn't look like a web address — check the link and try again.",
                inputDiagnostics(rawUrl)
            )
            return Outcome.Failed("That doesn't look like a web address — check the link and try again.")
        }
        val host = CookieParsing.hostOf(url)
        if (host == null) {
            DiagnosticLog.record(
                "add from link",
                "That doesn't look like a web address — check the link and try again.",
                inputDiagnostics(rawUrl)
            )
            return Outcome.Failed("That doesn't look like a web address — check the link and try again.")
        }
        // v1.0.9: a seeded site is named in the diagnostics —
        // the seed is a head-start only; the probe still
        // test-reads the site live like any other.
        SeedCatalog.nameFor(host)?.let { seedName ->
            DiagnosticLog.record(
                "add from link",
                "Known site: $seedName ($host) — a seeded catalogue " +
                    "entry; the probe still test-reads it live."
            )
        }
        // v1.0.6 (W2/TR1): probes escalate — a page that arrives
        // as a JavaScript shell is re-fetched through the browser
        // view once, and the RENDERED page is what gets studied
        // below. With no renderer wired (or the policy saying no)
        // the probe runs on the static client exactly as before.
        val stack = probeStack(host, forceRender = forceRender)
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
        // v1.0.6.2 (F5): the borderline AI check is spent at
        // most once per probe, however many candidates judge
        // borderline while competing.
        var aiVerifyUsed = false
        suspend fun consider(
            proposer: String,
            config: DeclarativeConfig,
            driveBookUrl: String,
            drive: ScoredDrive
        ) {
            when (val result = drive.result) {
                is AppResult.Ok -> {
                    // v1.0.6.2 (F5): a BORDERLINE list shape is
                    // the one band where the AI is asked to
                    // verify MEANING before anything is saved —
                    // clear passes and clear feeds never spend
                    // the call. A NO turns this candidate into a
                    // semantic failure: it is not saved, and the
                    // probe's ordinary escalation (other
                    // proposers, then the AI detector) continues.
                    // A YES is recorded on the stage lines, so
                    // the AI's work is visible in the repair
                    // record instead of invisible. A null (AI
                    // unavailable) leaves the deterministic
                    // borderline verdict standing — the follow-up
                    // book check inside the drive already passed.
                    var semanticFailure: String? = null
                    if (result.value.listShape == ListShapeVerdict.BORDERLINE &&
                        semanticVerifier != null && !aiVerifyUsed
                    ) {
                        aiVerifyUsed = true
                        when (semanticVerifier.invoke(
                            result.value.sampleTitles,
                            result.value.chapterTextPreview
                        )) {
                            true -> stageLines += StageLine(
                                "AI check", "confirmed",
                                "confirmed these are books"
                            )
                            false -> {
                                semanticFailure =
                                    "The AI check looked at the site's list " +
                                        "and said the items don't look like " +
                                        "books — more like posts — so those " +
                                        "rules weren't saved."
                                stageLines += StageLine(
                                    "AI check", "chapter list",
                                    "the items don't look like books"
                                )
                            }
                            null -> Unit
                        }
                    }
                    if (semanticFailure != null) {
                        val failure = bestFailure
                        if (failure == null || 1 > failure.first) {
                            bestFailure = 1 to semanticFailure
                        }
                    } else {
                    stageLines += when {
                        result.value.allChaptersLocked -> StageLine(
                            proposer, "chapter text",
                            "all ${result.value.chapterCount} chapters on " +
                                "the test book are paid or locked, so the " +
                                "text couldn't be checked — passed with " +
                                "that limit"
                        )
                        // v1.0.6.1 (P4): a pass whose lock read
                        // failed says so — the text was read
                        // directly, the locks never checked.
                        result.value.lockStateUnknown -> StageLine(
                            proposer, "chapter text",
                            "the chapter locks couldn't be checked — " +
                                "the text was read directly"
                        )
                        else -> StageLine(
                            proposer, "passed", "the test-drive passed"
                        )
                    }
                    // v1.0.8.3: a drive that healed its own
                    // chapter rules drafts the HEALED rules —
                    // the ones that failed never reach the user.
                    val effectiveConfig = drive.healedConfig ?: config
                    if (drive.healedConfig != null) {
                        stageLines += StageLine(
                            proposer, "chapter text",
                            "the chapter rules from the first read didn't " +
                                "read as story text — the drive re-read a " +
                                "chapter page and healed them"
                        )
                    }
                    // Rank: a fully-proven candidate always
                    // beats a pass-with-limits (v1.0.6), then
                    // search proof, chapter count, text length.
                    // v1.0.6.1 (P4): three proof tiers now — a
                    // clean pass (2) beats a lock-unchecked pass
                    // (1), which beats an all-locked pass (0).
                    val rank = listOf(
                        when {
                            result.value.allChaptersLocked -> 0
                            result.value.lockStateUnknown -> 1
                            else -> 2
                        },
                        if (result.value.searchVerified) 1 else 0,
                        result.value.chapterCount,
                        result.value.chapterTextLength
                    )
                    val current = bestRank
                    if (current == null || rankIsBetter(rank, current)) {
                        bestRank = rank
                        bestDraft = Draft(
                            config = effectiveConfig.copy(
                                searchNeedsSetup = effectiveConfig.search == null ||
                                    !result.value.searchVerified,
                                // v1.0.6 (TR1/Q5): a drive that
                                // passed with rendered pages marks
                                // the saved source — its fetches go
                                // straight to the browser view from
                                // now on (TR3). Repair flips a
                                // source the same way, and Details
                                // says so plainly.
                                needsRendering = effectiveConfig.needsRendering ||
                                    escalating?.renderedAny == true
                            ),
                            testDrive = result.value.copy(
                                reviewsFound = effectiveConfig.reviews != null
                            ),
                            bookUrl = driveBookUrl,
                            host = host
                        )
                    }
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
                    outcomeLines(),
                    // v1.0.7: a stage failure with a renderer
                    // wired and permitted is worth ONE rendered
                    // retry (the caller's call — the Add-from-
                    // link flow takes it automatically). A probe
                    // that already ran forced never asks for
                    // another. v1.0.7 draw-check fix: an earlier
                    // version also required that NOTHING had
                    // rendered yet in this probe — wrong for the
                    // walk-in shape: a shell HOME page renders
                    // during the plain pass, which says nothing
                    // about the BOOK page, whose own fetcher
                    // only escalates on a shell detection the
                    // book page may not trip (chikari, on-device
                    // 2026-10-30: home pasted, "No browser").
                    renderWorthTrying = !forceRender &&
                        escalating != null &&
                        escalating.renderAvailable()
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
                    // v1.0.8.1: a lane that declares a render
                    // wait selector (an app-shell lane) drives
                    // through its own stack carrying the hint,
                    // so the drive's book/chapter renders cannot
                    // settle before the content the lane reads
                    // exists. The sample fetch above stays on
                    // the plain context — a listing page has no
                    // chapter container to wait for.
                    val laneContext = laneConfig.renderWaitSelector
                        ?.let { selector ->
                            probeContext(
                                probeStack(
                                    host,
                                    forceRender = forceRender,
                                    waitForSelector = { selector }
                                ).fetcher,
                                host
                            )
                        } ?: context
                    tried("fast lane")
                    consider(
                        "fast lane", laneFull, sample,
                        // v1.0.6.1 (P4): the fast lane is
                        // hand-verified — exempt from the
                        // prose gate.
                        testDriveScored(laneFull, sample, laneContext, laneVerified = true)
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
            // v1.0.6.2 (F4): the page may be the site's HOME or
            // a listing — neither a book page nor, whatever the
            // chapter analysis makes of its biggest text block,
            // a chapter. When the listing analysis reads book
            // links from it, walk into the first book and probe
            // THAT; the source is still recorded under the
            // pasted link's host. A site-root address earns the
            // walk-in even when a text block tripped the
            // chapter analysis — a home page is never a chapter.
            val listing = runCatching {
                LinkInference.analyseListingPage(bookDoc, bookUrl, null)
            }.getOrNull()
            val siteRoot = isSiteRoot(bookUrl)
            if (!walkedIn && listing != null &&
                (chapterAnalysis == null || siteRoot)
            ) {
                val walked = walkInFromListing(
                    listing, bookDoc, host, aiAvailable,
                    tryAllProposers, semanticVerifier,
                    forceRender = forceRender
                )
                if (walked != null) return walked
                // v1.0.6.3 (P6): ONE more hop — a home page may
                // link to a catalogue page instead of to books
                // directly. Follow the first catalogue-smelling
                // link and walk in from THERE: same fetcher, same
                // same-host rule, and never a second hop (the
                // inner walk-in probes with walkedIn = true, so
                // this block cannot re-enter itself).
                if (siteRoot) {
                    val catalogueUrl = pickCatalogueLink(bookDoc, host)
                    if (catalogueUrl != null && catalogueUrl != bookUrl) {
                        val catFetch = fetcher.get(catalogueUrl)
                        if (catFetch is AppResult.Ok &&
                            catFetch.value.status in 200..299
                        ) {
                            val catUrl = catFetch.value.finalUrl
                                .ifBlank { catalogueUrl }
                            val catDoc = Jsoup.parse(
                                catFetch.value.body, catUrl
                            )
                            val catListing = runCatching {
                                LinkInference.analyseListingPage(
                                    catDoc, catUrl, null
                                )
                            }.getOrNull()
                            if (catListing != null) {
                                val walkedCatalogue = walkInFromListing(
                                    catListing, catDoc, host, aiAvailable,
                                    tryAllProposers, semanticVerifier,
                                    forceRender = forceRender
                                )
                                if (walkedCatalogue != null) {
                                    return walkedCatalogue
                                }
                            }
                        }
                    }
                }
            }
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
                        // v1.0.6.2 (F4): a home/listing page is
                        // refused for what it IS — the chapter
                        // copy below is for chapter links only.
                        if (siteRoot || listing != null) {
                            HOME_PAGE_COPY
                        } else {
                            "That link opened a chapter, but Xreader couldn't find the book's own page " +
                                "from it. Paste the book's main page (the one with the whole chapter list) instead."
                        }
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
                        // v1.0.6.2 (F4): a home/listing page that
                        // yielded no readable book is refused for
                        // what it is; every other unreadable page
                        // keeps the copy it has always had.
                        if (siteRoot || listing != null) {
                            HOME_PAGE_COPY
                        } else if (aiAvailable) {
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

    /**
     * v1.0.6.2 (F4): walk in from a home/listing page — take its
     * first book link (same host only: a home page linking OUT
     * to another site's books is an aggregator, and the source
     * must belong to the pasted host) and probe that book page
     * as if it had been pasted. A [Outcome.Ready] is returned
     * with a leading stage line saying where the probe started;
     * anything else returns null and the caller falls through
     * to its honest refusal for the page it actually saw.
     */
    private suspend fun walkInFromListing(
        listing: LinkInference.ListingAnalysis,
        bookDoc: Document,
        host: String,
        aiAvailable: Boolean,
        tryAllProposers: Boolean,
        semanticVerifier: (suspend (List<String>, String) -> Boolean?)?,
        forceRender: Boolean = false
    ): Outcome? {
        val firstBook = firstListingBookUrl(bookDoc, listing) ?: return null
        if (CookieParsing.hostOf(firstBook) != host) return null
        val inner = probeImpl(
            firstBook, aiAvailable, tryAllProposers,
            semanticVerifier = semanticVerifier, walkedIn = true,
            forceRender = forceRender
        )
        return when (inner) {
            is Outcome.Ready -> inner.copy(
                stageLines = listOf(
                    StageLine(
                        "home page", "walked in",
                        "the pasted link was the site's home page — " +
                            "a book from its list was used to learn " +
                            "the site's shape"
                    )
                ) + inner.stageLines
            )
            else -> null
        }
    }

    /** The first usable book link of a listing page, resolved
     * against the page's own base by jsoup (the documents the
     * probe studies are parsed with their URL as base). */
    private fun firstListingBookUrl(
        doc: Document,
        listing: LinkInference.ListingAnalysis
    ): String? {
        val elements = runCatching { doc.select(listing.resultsSelector) }
            .getOrNull() ?: return null
        for (el in elements) {
            for (candidate in SelectorChains.split(listing.linkSpec)) {
                val spec = SelectorSpec.parse(candidate)
                val target = if (spec.css.isBlank()) {
                    el
                } else {
                    el.selectFirst(spec.css)
                } ?: continue
                val href = when (spec.attr) {
                    null, "text" -> target.absUrl("href")
                    else -> target.absUrl(spec.attr)
                        .ifBlank { target.attr(spec.attr) }
                }
                if (href.isNotBlank()) return href
            }
        }
        return null
    }

    /** Whether [url] addresses the site's root (no path). */
    private fun isSiteRoot(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull() ?: return false
        return path.isBlank() || path == "/"
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
     *
     * v1.0.6.1 (P3): the JUDGING is config-free. v1.0.6 judged
     * each candidate with the stored config's own chapter
     * rules — the very rules Repair exists because of — so a
     * config whose chapter rules were broken rejected every
     * candidate and the picker fell back to the stored
     * all-locked sample anyway. Enumeration still runs through
     * the stored config's catalogue (that parse works even
     * when the chapter rules are broken); each candidate page
     * is then read RAW and judged by [LinkInference]'s page
     * analysis plus a raw [ChapterLocks] scan of the chapter
     * rows: a cluster means the book lists chapters, an
     * un-marked row means an unlocked chapter exists.
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
                val judgement = judgeSamplePage(stack, book.id) ?: continue
                if (firstWithChapters == null) firstWithChapters = book.id
                if (judgement.hasUnlockedChapter) {
                    return@withContext book.id
                }
            }
            firstWithChapters
        }

    /** v1.0.6.1 (P3): one candidate's config-free verdict —
     * the page carries a chapter cluster, and whether any of
     * its rows is free of lock marks. */
    private data class SampleJudgement(
        val hasUnlockedChapter: Boolean
    )

    /**
     * v1.0.6.1 (P3): judge one candidate book page WITHOUT the
     * stored config: fetch the page through the probe's own
     * fetch stack, let [LinkInference.analyseBookPage] find
     * the chapter cluster on the raw DOM, and scan the
     * cluster's rows with [ChapterLocks.rowIsLocked]. Null
     * when the page doesn't read as a book page (no cluster) —
     * the candidate simply doesn't count.
     */
    private suspend fun judgeSamplePage(
        stack: ProbeStack,
        bookUrl: String
    ): SampleJudgement? {
        val (body, finalUrl) = try {
            when (val fetched = stack.fetcher.get(bookUrl)) {
                is AppResult.Err -> return null
                is AppResult.Ok -> {
                    if (fetched.value.status !in 200..299) return null
                    fetched.value.body to fetched.value.finalUrl
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return null
        }
        val doc = try {
            org.jsoup.Jsoup.parse(body, finalUrl)
        } catch (t: Throwable) {
            return null
        }
        val analysis = LinkInference.analyseBookPage(doc, finalUrl)
            ?: return null
        val rows = doc.select(analysis.chapterListSelector)
        if (rows.isEmpty()) return null
        return SampleJudgement(
            hasUnlockedChapter = rows.any { !ChapterLocks.rowIsLocked(it) }
        )
    }

    /** A test-drive outcome plus how far it got (v1.0.5, A4):
     * steps 0 = page/title, 1 = chapter list, 2 = chapter text,
     * 3 = passed. When several candidates fail, the failure with
     * the most steps is the honest one to report. */
    private data class ScoredDrive(
        val steps: Int,
        val result: AppResult<TestDriveResult>,
        /**
         * v1.0.8.3: when the drive healed the candidate's
         * chapter rules mid-drive (the first rules read a
         * chapter page as non-prose; a fresh analysis of the
         * same page found the real container), the healed
         * config rides here so the caller drafts IT, not the
         * rules that failed.
         */
        val healedConfig: DeclarativeConfig? = null
    )

    /** v1.0.6.1 (P4): the outcome of reading a test book's lock
     * marks. [Detected] carries the locked chapter ids exactly
     * as read; [DetectionFailed] means the read itself failed
     * (the lock spec broke, the page errored) — which must
     * never again be silently read as "nothing is locked". */
    private sealed interface LockCheck {
        data class Detected(val lockedIds: Set<String>) : LockCheck
        data object DetectionFailed : LockCheck
    }

    /**
     * v1.0.6.2 (F5): the list-shape judge — reads the candidate's
     * OWN listing through the candidate's own rules and decides
     * whether the items read as books or as feed posts. A post
     * title is "name + relative time + text" ("Monarch just
     * now Ruined the mood") — the chikari case, where inference
     * saved the site's social feed as a novel source. Returns
     * the verdict plus the titles sampled, for the borderline
     * AI check.
     */
    private suspend fun judgeListShape(
        source: DeclarativeSource,
        config: DeclarativeConfig
    ): SemanticShape {
        if (config.popular == null && config.latest == null) {
            return SemanticShape(ListShapeVerdict.CLEAN)
        }
        val books = runCatching { source.popular(1).books }
            .getOrDefault(emptyList())
            .take(LIST_SHAPE_SAMPLE)
        val titles = books.map { it.title }
        // v1.0.6.3 (P6): a config that DECLARES a list (this
        // function runs only when popular/latest exists) but
        // whose list reads back EMPTY is broken, not clean — it
        // used to sail through as CLEAN and get saved. One book
        // stays unjudged: a tiny catalogue is plausible and a
        // single title shows no shape either way.
        if (titles.isEmpty()) {
            return SemanticShape(ListShapeVerdict.FEED, titles)
        }
        if (titles.size < 2) {
            return SemanticShape(ListShapeVerdict.CLEAN, titles)
        }
        val hits = titles.count {
            RELATIVE_TIME_TITLE.containsMatchIn(it) ||
                RELATIVE_TIME_ANYWHERE.containsMatchIn(it)
        }
        if (hits * 2 >= titles.size) {
            return SemanticShape(ListShapeVerdict.FEED, titles)
        }
        if (hits == 0) {
            return SemanticShape(ListShapeVerdict.CLEAN, titles)
        }
        // Borderline: some items look post-shaped. Prove the
        // list with its first item — a real book page carries
        // chapters or a synopsis; a post page carries neither.
        return if (firstItemReadsAsBook(source, books.first())) {
            SemanticShape(ListShapeVerdict.BORDERLINE, titles)
        } else {
            SemanticShape(ListShapeVerdict.FEED, titles)
        }
    }

    /** F5 follow-check: does the list's first item open as a
     * book (a chapter list or a synopsis)? */
    private suspend fun firstItemReadsAsBook(
        source: DeclarativeSource,
        book: app.xreader.extensionapi.SourceBook
    ): Boolean = runCatching {
        val (details, chapters) = source.detailsAndChapters(book)
        chapters.isNotEmpty() || !details.description.isNullOrBlank()
    }.getOrDefault(false)

    private data class SemanticShape(
        val verdict: ListShapeVerdict,
        val sampleTitles: List<String> = emptyList()
    )

    /**
     * v1.0.8.3: the drive's first sampling pass — the first,
     * middle and last unlocked chapters (fewer when the list
     * is short), so one atypical opening chapter can't speak
     * for the whole book.
     */
    private fun spreadSamples(
        chapters: List<app.xreader.extensionapi.SourceChapter>
    ): List<app.xreader.extensionapi.SourceChapter> {
        if (chapters.size <= MAX_TEXT_SAMPLES) return chapters
        return linkedSetOf(
            chapters.first(),
            chapters[chapters.size / 2],
            chapters.last()
        ).toList()
    }

    /**
     * v1.0.8.3: the self-heal's fresh rules. Re-reads one
     * sampled chapter page through the drive's own context
     * and runs the chapter-page analysis on it as it stands
     * NOW: when the analysis names a different content
     * container than the candidate's rules use, and that
     * container's own text is genuine prose, the candidate
     * is rebuilt with the fresh chapter rules. Null when the
     * page can't be re-read, the analysis finds nothing, the
     * container is the one already in use, or its text isn't
     * prose either — the original failure then stands.
     */
    private fun healedChapterConfig(
        config: DeclarativeConfig,
        context: SourceContext,
        unlocked: List<app.xreader.extensionapi.SourceChapter>
    ): DeclarativeConfig? {
        val sample = unlocked.firstOrNull() ?: return null
        val doc = runCatching {
            val response = context.http.get(sample.id)
            if (response.status !in 200..299) return null
            Jsoup.parse(
                response.body,
                response.finalUrl.ifBlank { sample.id }
            )
        }.getOrNull() ?: return null
        val analysis = LinkInference.analyseChapterPage(doc, sample.id)
            ?: return null
        if (analysis.contentSelector == config.chapter.content) {
            return null
        }
        val element = runCatching {
            doc.selectFirst(analysis.contentSelector)
        }.getOrNull() ?: return null
        if (!isGenuineProse(element.text().trim())) return null
        return config.copy(
            chapter = config.chapter.copy(
                content = analysis.contentSelector,
                title = analysis.titleSpec ?: config.chapter.title,
                nextPage = analysis.nextPageSelector
                    ?: config.chapter.nextPage
            )
        )
    }

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
        context: SourceContext,
        // v1.0.6.1 (P4): the caller marks a candidate proposed
        // by the curated KnownSites fast lane. Hand-verified
        // lane configs are exempt from the prose gate — a lane
        // candidate whose lock read fails keeps the plain
        // sampled-text verdict it always had.
        laneVerified: Boolean = false,
        // v1.0.8.3: the self-heal redrive passes true so a
        // healed candidate is never healed twice.
        healAttempted: Boolean = false
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
        // v1.0.6.2 (F5): the SEMANTIC gate — structure is not
        // meaning. A repeating card list plus some text used to
        // be enough to pass, so a social feed's post cards were
        // saved as a "catalogue" on a real device (username +
        // "just now" titles, post pages for books). Before any
        // lock or text sampling, the site's own list is judged
        // for book-shape; a feed verdict fails the drive HERE,
        // at the list step, for every non-lane candidate —
        // which is also what routes the flow's ordinary
        // escalation (other proposers, then the AI detector)
        // to run before anything is saved. The curated lane is
        // exempt: hand-verified, like the prose gate below.
        val shape = if (laneVerified) {
            SemanticShape(ListShapeVerdict.CLEAN)
        } else {
            judgeListShape(source, config)
        }
        if (shape.verdict == ListShapeVerdict.FEED) {
            return ScoredDrive(
                1,
                AppResult.Err(
                    AppError.BadFile(
                        // v1.0.6.3 (P6): an EMPTY list and a POST
                        // list are different failures and say so.
                        if (shape.sampleTitles.isEmpty()) {
                            "The test read found the site's list page, but no books " +
                                "in it — the list these rules read comes back empty. " +
                                "Rules like that weren't saved."
                        } else {
                            "The test read found the site's list, but the items in it " +
                                "look like posts, not books — names with times like " +
                                "‘just now’ where book titles should be. Rules built " +
                                "from a list like that weren't saved."
                        }
                    )
                )
            )
        }
        // v1.0.7 (the wetriedtls lesson): the FURNITURE guard.
        // A candidate can clear every check above with a
        // chapter list that is not a chapter list at all: on
        // app-built sites the book page's server HTML carries
        // details and synopsis but the real chapter list loads
        // with the page's scripts — so inference settles for a
        // rail of OTHER BOOKS (same URL shape as the book page
        // itself), and the "chapter text" it samples is the
        // book's own synopsis, which passes the prose gate
        // because a synopsis IS prose. On-device proof
        // 2026-10-30: title read as the site's brand, "13
        // chapters" were book cards, the preview was the
        // synopsis. When the rows are book-page-shaped, the
        // candidate fails HERE, at the list step — and the
        // probe's failure is marked worth a rendered retry, so
        // the flow escalates to the browser view (where the
        // real list exists) instead of saving furniture. The
        // lane stays exempt, like the F5 and prose gates.
        if (!laneVerified &&
            chapterRowsAreBookPages(bookUrl, chapters.map { it.id })
        ) {
            return ScoredDrive(
                1,
                AppResult.Err(
                    AppError.BadFile(
                        "The test read found links on that page, but they lead to " +
                            "other books — not to this book's chapters. The real " +
                            "chapter list on this site loads after the page opens, " +
                            "so it wasn't in the page Xreader read. Rules like " +
                            "that weren't saved."
                    )
                )
            )
        }
        // A3: skip lock-marked chapters when sampling text.
        // v1.0.6.1 (P4): the lock read is TRI-STATE now. It
        // used to fail OPEN — a thrown lock read became an
        // empty locked set, so a broken lock spec (or a
        // spilled boilerplate page) silently became "nothing
        // is locked" and boilerplate length alone passed the
        // drive as proven chapter text. Detected(set) keeps
        // the old meaning; DetectionFailed is kept apart and
        // the candidate may then pass ONLY through the prose
        // gate below, carrying the limit on its result.
        val lockCheck: LockCheck = try {
            LockCheck.Detected(source.lockedChapterIds(testBook))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            LockCheck.DetectionFailed
        }
        val lockDetectionFailed =
            lockCheck is LockCheck.DetectionFailed && !laneVerified
        val lockedIds: Set<String> = when (lockCheck) {
            is LockCheck.Detected -> lockCheck.lockedIds
            LockCheck.DetectionFailed -> emptySet()
        }
        // When the locks couldn't be read, no chapter can be
        // called locked — sampling runs over the whole list
        // and the prose gate arbitrates instead.
        val unlocked = if (lockDetectionFailed) {
            chapters
        } else {
            chapters.filter { it.id !in lockedIds }
        }
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
                        allChaptersLocked = true,
                        listShape = shape.verdict,
                        sampleTitles = shape.sampleTitles
                    )
                )
            )
        }
        var sampled: app.xreader.extensionapi.SourceChapter? = null
        var bestText = ""
        var lockedEvidence = false
        var firstError: Throwable? = null
        var timeoutError: Throwable? = null
        // v1.0.8.3: one sampling read, hoisted so the first
        // pass and the recovery passes below share the exact
        // same read + timeout-retry + error bookkeeping.
        fun sampleText(
            candidate: app.xreader.extensionapi.SourceChapter
        ): String {
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
            return runCatching { readText() }.getOrElse { e ->
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
        }
        // v1.0.8.3: spread the first pass (first / middle /
        // last) instead of only the opening chapters — a book
        // whose first chapters are notices or announcements
        // shouldn't sink a drive its later chapters would pass.
        val firstPass = spreadSamples(unlocked)
        for (candidate in firstPass) {
            val text = sampleText(candidate)
            if (text.length > bestText.length) bestText = text
            if (text.length >= LinkInference.MIN_READABLE_CHARS) {
                sampled = candidate
                break
            }
            if (ChapterLocks.pageSuggestsLocked(text)) lockedEvidence = true
        }
        // v1.0.8.3 RECOVERY (the woopread lesson): the first
        // pass produced no prose — either nothing readable at
        // all, or a long non-prose sliver (a promo card, a
        // notice page) that cleared the length floor and
        // stopped the loop early. Before this candidate may
        // fail: (1) sample deeper into the list with the same
        // rules — real chapters further in can still prove the
        // text; (2) if the text still isn't prose, re-analyse
        // one sampled chapter page and, when the fresh
        // analysis finds a DIFFERENT container whose text is
        // genuine prose, redrive once with the healed rules
        // and draft those. A site is only refused when its
        // pages defeat the fresh analysis too.
        if (!laneVerified && !healAttempted &&
            (sampled == null || !isGenuineProse(bestText))
        ) {
            for (candidate in unlocked.drop(firstPass.size)
                .take(MAX_TEXT_SAMPLES)) {
                val text = sampleText(candidate)
                if (text.length > bestText.length) bestText = text
                if (text.length >= LinkInference.MIN_READABLE_CHARS &&
                    isGenuineProse(text)
                ) {
                    sampled = candidate
                    break
                }
                if (ChapterLocks.pageSuggestsLocked(text)) {
                    lockedEvidence = true
                }
            }
        }
        if (!laneVerified && !healAttempted &&
            (sampled == null || !isGenuineProse(bestText))
        ) {
            val healed = healedChapterConfig(config, context, unlocked)
            if (healed != null) {
                val redrive = testDriveScored(
                    healed, bookUrl, context, healAttempted = true
                )
                if (redrive.result is AppResult.Ok) {
                    return redrive.copy(healedConfig = healed)
                }
            }
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
        // v1.0.6.1 (P4) + v1.0.6.2 (F5): a bare length pass is
        // not proof of chapter text — the sampled text must
        // read like prose. The gate began as the lock-failed
        // case's guard (P4); F5 extends it to EVERY non-lane
        // candidate save, because a shell or placeholder page
        // is the same failure whether or not the lock read
        // happened to work. Boilerplate (nav lists, tag
        // clouds, related-title blocks) is long but has almost
        // no sentence punctuation; real story text always does.
        // The hand-verified lane stays exempt (P4's rule), and
        // the lock-failed case keeps its own precise words.
        if (!laneVerified && !isGenuineProse(bestText)) {
            return ScoredDrive(
                2,
                AppResult.Err(
                    AppError.BadFile(
                        if (lockDetectionFailed) {
                            "The test read opened a chapter, but the text it found " +
                                "doesn't read like story text — and the site wouldn't " +
                                "show which chapters are locked, so the chapter " +
                                "couldn't be proven."
                        } else {
                            "The test read opened a chapter, but the chapter text " +
                                "could not be read — what came back doesn't read " +
                                "like story text."
                        }
                    )
                )
            )
        }
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
                    },
                    // v1.0.6.1 (P4): a prose-gated pass
                    // carries its limit on the result.
                    lockStateUnknown = lockDetectionFailed,
                    // v1.0.6.2 (F5): the semantic verdict
                    // rides out for the flow's borderline
                    // AI check.
                    listShape = shape.verdict,
                    sampleTitles = shape.sampleTitles
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
        forceRender: Boolean = false,
        // v1.0.8.1: the wait-for-content hint for renders
        // through this stack (a lane's declared selector).
        waitForSelector: () -> String? = { null },
        // needsRendering stays LAST: three call sites pass it
        // as a trailing lambda.
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
            rateLimiter = limiter,
            forceRender = forceRender,
            waitForSelector = waitForSelector
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

        /** v1.0.6.2 (F5): listing items sampled per drive for
         * the list-shape judge. */
        const val LIST_SHAPE_SAMPLE = 8

        /** F5: a feed-post title opens with "name + relative
         * time" ("Monarch just now …", "Theonebeggar21 · 2h
         * ago …"). Anchored at the title's start. */
        private val RELATIVE_TIME_TITLE = Regex(
            "^\\S+\\s+(?:just now|\\d{1,3}\\s*[mhdw]\\s*ago|yesterday|·)",
            RegexOption.IGNORE_CASE
        )

        /** v1.0.6.3 (P6): the looser sibling — feed furniture
         * ANYWHERE in the title ("· 2h", "just now" mid-string).
         * Only ever counted toward the ≥half threshold, and the
         * borderline band still earns a book-page proof, so a
         * novel honestly titled with a time word can't fail on
         * this alone. */
        private val RELATIVE_TIME_ANYWHERE = Regex(
            "(?:·\\s*\\d|\\bjust now\\b|\\b\\d{1,3}\\s*[mhdw]\\s+ago\\b)",
            RegexOption.IGNORE_CASE
        )

        /** v1.0.6.3 (P6): first path segments that smell like a
         * book catalogue, for the home-page hop. */
        private val CATALOGUE_SEGMENTS = setOf(
            "novels", "novel", "books", "book", "catalog",
            "catalogue", "library", "series", "browse"
        )

        private val CATALOGUE_WORDS =
            listOf("novel", "book", "catalog", "library")

        /** v1.0.6.3 (P6): the home-page hop's target — the first
         * same-host link that smells like a catalogue: a first
         * path segment from the catalogue family, or link text
         * naming novels/books/a catalogue. Null when nothing
         * qualifies. Pure over the parsed page: unit-tested. */
        fun pickCatalogueLink(doc: Document, host: String): String? {
            for (anchor in doc.select("a[href]")) {
                val href = anchor.absUrl("href")
                    .ifBlank { anchor.attr("href") }
                if (href.isBlank()) continue
                if (CookieParsing.hostOf(href) != host) continue
                val firstSegment = runCatching { URI(href).path }
                    .getOrNull()
                    ?.trim('/')
                    ?.split('/')
                    ?.firstOrNull()
                    ?.lowercase()
                    .orEmpty()
                if (firstSegment in CATALOGUE_SEGMENTS) return href
                val text = anchor.text().trim().lowercase()
                if (CATALOGUE_WORDS.any { text.contains(it) }) return href
            }
            return null
        }

        /** v1.0.7: are these "chapter rows" actually links to
         * other BOOK pages? True when at least 3 rows remain
         * after dropping any row that points at the book page
         * itself, and at least half of them share the book
         * URL's path SHAPE (same segment count, same leading
         * segments, final segment free) while containing NONE
         * of the book's identity segments — every segment
         * after the first (the collection word): the book's
         * slug and anything under it. The identity condition
         * is what keeps a pasted CHAPTER link safe: the book
         * URL is then …/<slug>/chapter-1 and its sibling
         * chapters still contain the slug, so they never judge
         * as furniture. (Gate fix 3: the first version of this
         * check protected only the LAST segment — for a
         * chapter link that is "chapter-1", not the slug, and
         * the guard misfired on exactly that case.)
         * Real chapter lists differ in shape
         * (…/book/<slug>/<id>, …/novel/<slug>/chapter-1,
         * /chapter/<id>) and never judge either. Book URLs
         * with fewer than two path segments are never judged —
         * not enough shape to tell. Pure: unit-tested. */
        fun chapterRowsAreBookPages(
            bookUrl: String,
            chapterUrls: List<String>
        ): Boolean {
            fun segmentsOf(url: String): List<String> =
                runCatching { URI(url).path }
                    .getOrNull()
                    ?.trim('/')
                    ?.split('/')
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
            fun comparable(url: String): String =
                url.substringBefore('#').trim().trimEnd('/')
                    .lowercase()
            val bookSegments = segmentsOf(bookUrl)
            if (bookSegments.size < 2) return false
            val identity = bookSegments.drop(1)
                .map { it.lowercase() }.toSet()
            val bookShape = bookSegments.dropLast(1).map { it.lowercase() }
            val rows = chapterUrls.filter {
                comparable(it) != comparable(bookUrl)
            }
            if (rows.size < 3) return false
            val bookShaped = rows.count { row ->
                val segs = segmentsOf(row)
                segs.size == bookSegments.size &&
                    segs.dropLast(1).map { it.lowercase() } == bookShape &&
                    segs.none { it.lowercase() in identity }
            }
            return bookShaped * 2 >= rows.size
        }

        /** v1.0.6: the plain-words failure when the CONNECTION
         * gave up mid-drive — the site is never blamed for a
         * slow link. */
        const val SLOW_CONNECTION_REASON =
            "The connection was too slow to finish checking this site. " +
                "Try again when your connection is better."

        /** v1.0.6.2 (F4): the honest refusal for a home/listing
         * page the probe couldn't learn a book from — it says
         * what was actually seen, and is never used for a
         * chapter link (that refusal keeps its own words). */
        const val HOME_PAGE_COPY =
            "That link opened the site's home page. Xreader looked for a book " +
                "there to learn from, but couldn't read one well enough to " +
                "build the source. Paste one book's page instead — the one " +
                "with the whole chapter list."

        /** v1.0.6.1 (P1): characters stripped from a pasted
         * address before parsing — the whitespace/control range
         * plus the invisible Unicode marks a share sheet or a
         * notes app copies along (zero-width space U+200B
         * through the bidi marks U+200F, and the BOM U+FEFF).
         * trim() removes none of the invisible ones, and one
         * zero-width char inside the authority makes the URI
         * parser return a null host — a good address refused,
         * with nothing visible to blame. */
        private val INPUT_JUNK = Regex("[\\u0000-\\u0020\\u200B-\\u200F\\uFEFF]")

        /** v1.0.6.1 (P1): a full address token inside pasted
         * text ("Read this: https://…"). */
        private val URL_TOKEN =
            Regex("https?://[a-z0-9.-]+(?::[0-9]+)?(?:/\\S*)?", RegexOption.IGNORE_CASE)

        /** v1.0.6.1 (P1): a bare host token ("novelight.net" or
         * "novelight.net/book/x") — dot-separated labels ending
         * in a real-looking TLD, with an optional port/path. */
        private val BARE_DOMAIN_TOKEN =
            Regex(
                "[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?::[0-9]+)?(?:/\\S*)?",
                RegexOption.IGNORE_CASE
            )

        /**
         * v1.0.6.1 (P1): accept an address the way people
         * actually copy it. The raw input is first stripped of
         * whitespace and invisible marks; then the first full
         * URL token in it wins (share text, notes, wrapped
         * pastes); else the first bare-domain token, given the
         * https scheme; else null. A clean input comes out
         * byte-identical to before — same trim, same scheme
         * prepend, same URI/host validation.
         */
        fun normaliseUrl(raw: String): String? {
            val cleaned = INPUT_JUNK.replace(raw, "")
            if (cleaned.isEmpty()) return null
            val candidate = URL_TOKEN.find(cleaned)?.value
                ?: BARE_DOMAIN_TOKEN.find(cleaned)?.value?.let { "https://$it" }
                ?: return null
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank()) return null
            return candidate
        }

        /**
         * v1.0.6.1 (P1/P6): a refused input's fingerprint for
         * the diagnostic log — its RAW length and the invisible
         * or non-ASCII code points riding in it (control chars
         * and anything above plain ASCII), e.g.
         * "len=23, hidden=[U+200B]". A rejected string whose
         * length exceeds its visible length names the culprit
         * class immediately, where the refusal message alone
         * could not.
         */
        fun inputDiagnostics(raw: String): String {
            val hidden = raw.map { it.code }
                .filter { it < 0x20 || it == 0x7F || it > 0x7E }
                .distinct()
                .take(8)
            val base = "len=${raw.length}"
            if (hidden.isEmpty()) return base
            val points = hidden.joinToString(", ") {
                "U+" + String.format(java.util.Locale.ROOT, "%04X", it)
            }
            return "$base, hidden=[$points]"
        }

        /**
         * v1.0.6.1 (P4): does a sampled chapter's text read
         * like genuine prose? Deterministic and cheap: the
         * existing readable-length floor plus at least four
         * sentence-ending marks ('.', '?', '!', '"', '”') —
         * real story text punctuates; page boilerplate (nav
         * links, tag clouds, related-title lists) runs long
         * without ever ending a sentence.
         *
         * v1.0.6.2 (F5) calibration: the boundary was five
         * when the gate only guarded the lock-failed case.
         * Extending it to every candidate save put the app's
         * own genuine-prose shapes on the line — a real
         * four-sentence paragraph (the OddSite and novelight
         * fixture chapters) carries exactly four enders in
         * ~450 characters, while boilerplate carries none.
         * Four keeps both sides of that line.
         */
        fun isGenuineProse(text: String): Boolean {
            if (text.length < LinkInference.MIN_READABLE_CHARS) return false
            val enders = text.count {
                it == '.' || it == '?' || it == '!' || it == '"' || it == '”'
            }
            return enders >= 4
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
