package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The full-path test-drive for ANY installed source (v1.0.2, R10):
 * list → search → book page → chapter list → chapter text, each
 * step proven against the live source through the same guarded
 * call path real reading uses, with the proof (the book actually
 * opened, its first chapter, the text's first line) captured for
 * display — shown, not asserted.
 *
 * Optional extras (reviews/comments/related) are distinguished
 * honestly: "not offered" is not a failure; offered-but-failing is
 * a limit. A login-walled list is reported as NEEDS_SETUP by the
 * verdict rules (a core step failed) with the detail saying why.
 */
class ContractTester(private val service: ExtensionService) {

    /**
     * v1.0.5.1: a full-path drive fetches and parses whole
     * pages — CPU work that must never run on the caller's
     * thread (Repair and the heal sweep both launch from
     * main-confined scopes), so the drive hops to
     * [Dispatchers.Default] first.
     */
    suspend fun test(handle: SourceHandle): ContractVerdict =
        withContext(Dispatchers.Default) { testImpl(handle) }

    /** The drive itself; see [test]. Always entered on
     * [Dispatchers.Default] via the public wrapper. */
    private suspend fun testImpl(handle: SourceHandle): ContractVerdict {
        val results = mutableListOf<CapabilityResult>()
        var proofBook = ""
        var proofChapter = ""
        var proofFirstLine = ""

        fun record(capability: SourceCapability, result: AppResult<*>, detail: String) {
            results += CapabilityResult(
                capability = capability,
                ok = result is AppResult.Ok,
                detail = if (result is AppResult.Ok) {
                    detail
                } else {
                    (result as AppResult.Err).error.detail
                        ?: "the source didn't answer"
                }
            )
        }

        // LIST — the catalogue's front page.
        val listResult = service.guarded(handle) { it.popular(1) }
        // v1.0.6.2 (F2): a config whose listing URL carries
        // {page} CLAIMS the catalogue pages — prove the claim.
        // Page 2 must not be page 1 again (a source answering
        // every page with its first one froze Browse on page 1
        // while looking alive). An EMPTY page 2 is a one-page
        // catalogue ending honestly, and a different set is
        // paging working; only an identical set fails the step.
        val listSpecUrl = service.declarativeConfigOf(handle)?.let { cfg ->
            (cfg.popular ?: cfg.latest ?: cfg.search)?.url
        }
        val pageTwoResult = if (
            listResult is AppResult.Ok &&
            listSpecUrl?.contains("{page}") == true
        ) {
            service.guarded(handle) { it.popular(2) }
        } else {
            null
        }
        val pageOneIds = (listResult as? AppResult.Ok)?.value
            ?.books?.map { it.id }?.toSet().orEmpty()
        val pageTwoBooks = (pageTwoResult as? AppResult.Ok)?.value?.books
        val pageTwoSame = pageTwoBooks != null && pageOneIds.isNotEmpty() &&
            pageTwoBooks.map { it.id }.toSet() == pageOneIds
        if (pageTwoSame) {
            results += CapabilityResult(
                capability = SourceCapability.LIST,
                ok = false,
                detail = "the catalogue claims to page, but page 2 " +
                    "shows the same books as page 1"
            )
        } else {
            record(
                SourceCapability.LIST, listResult,
                if (listResult is AppResult.Ok) {
                    "${listResult.value.books.size} book(s) listed" +
                        when {
                            pageTwoBooks == null -> ""
                            pageTwoBooks.isEmpty() ->
                                " — page 2 is empty, so this is the whole catalogue"
                            else ->
                                " — page 2 shows different books, so the catalogue pages"
                        }
                } else {
                    ""
                }
            )
        }
        val firstBook: SourceBook? = (listResult as? AppResult.Ok)?.value
            ?.books?.firstOrNull()

        // v1.1.0 (design §6.5b): a declarative source whose
        // config deliberately carries NO search spec has no
        // search BY DESIGN — the step is recorded as a neutral
        // fact, never run and never failed. (A config whose
        // search exists but is unverified still runs the step
        // exactly as before.)
        val searchByDesignAbsent =
            service.declarativeConfigOf(handle)?.let { it.search == null } == true ||
                // A Live Source carries no search spec at all —
                // its search is best-effort derivation, so the
                // same honest line applies (design §6.5b).
                handle.source is LiveSource
        if (searchByDesignAbsent) {
            results += CapabilityResult(
                capability = SourceCapability.SEARCH,
                ok = true,
                detail = ContractVerdict.SEARCH_NOT_SET_UP_LINE,
                notApplicable = true
            )
        }
        // SEARCH — a distinctive word from the first listed title,
        // or a neutral word when the list was empty.
        val probeWord = firstBook?.title
            ?.split(Regex("[^A-Za-z]+"))
            ?.filter { it.length >= 4 }
            ?.maxByOrNull { it.length }
            ?: "the"
        if (!searchByDesignAbsent) {
            val searchResult = service.guarded(handle) { it.search(probeWord, 1) }
            // v1.0.5 (A5): finding books is not enough — the search
            // must provably FILTER. A nonsense control word must
            // return fewer results, and the real results must differ
            // from the unfiltered list (SearchFilterProof).
            val controlResult = service.guarded(handle) {
                it.search(SearchFilterProof.NONSENSE_TERM, 1)
            }
            val searchVerdict = if (searchResult is AppResult.Ok) {
                SearchFilterProof.judge(
                    searchResult.value.books.map { it.id }.toSet(),
                    (controlResult as? AppResult.Ok)?.value?.books
                        ?.map { it.id }?.toSet() ?: emptySet(),
                    (listResult as? AppResult.Ok)?.value?.books
                        ?.map { it.id }?.toSet()
                )
            } else {
                null
            }
            if (searchResult is AppResult.Ok && searchVerdict?.verified == false) {
                results += CapabilityResult(
                    capability = SourceCapability.SEARCH,
                    ok = false,
                    detail = searchVerdict.reason ?: "search results couldn't be judged"
                )
            } else {
                record(
                    SourceCapability.SEARCH, searchResult,
                    if (searchResult is AppResult.Ok) {
                        "searching “$probeWord” found " +
                            "${searchResult.value.books.size} book(s) — and the " +
                            "nonsense control word found fewer, so it filters"
                    } else {
                        ""
                    }
                )
            }
        }

        if (firstBook == null) {
            // Without a book, the remaining core steps cannot run —
            // record them as failed with the honest reason so the
            // verdict names the real breakage point.
            for (capability in listOf(
                SourceCapability.DETAILS,
                SourceCapability.CHAPTERS,
                SourceCapability.TEXT
            )) {
                results += CapabilityResult(
                    capability = capability,
                    ok = false,
                    detail = "no book could be opened from the list"
                )
            }
            return ContractVerdict(
                state = ContractVerdict.judge(results, System.currentTimeMillis()),
                results = results,
                checkedAtMillis = System.currentTimeMillis()
            )
        }
        fun detailsDetail(book: SourceBook): String =
            "author: ${book.author ?: "—"}, " +
                if (book.coverUrl != null) {
                    "cover found"
                } else {
                    "no cover found (the book will show a letter tile)"
                }

        // DETAILS — the book page fills in.
        // v1.0.6.2 (F5): judge the book the rules were SAVED
        // from — the config's recorded sample — when there is
        // one. Repair proved THAT book; Health re-judging a
        // different book off the catalogue's front page let an
        // unrepresentative pick contradict a good Repair. The
        // catalogue's first book stays the fallback: when no
        // sample is recorded, or the recorded book no longer
        // opens at all.
        val sampleUrl = service.declarativeConfigOf(handle)
            ?.sampleBookUrl?.takeIf { it.isNotBlank() }
        var sampled: SourceBook? = null
        if (sampleUrl != null) {
            val sampleResult = service.guarded(handle) {
                it.details(SourceBook(id = sampleUrl, title = ""))
            }
            if (sampleResult is AppResult.Ok &&
                sampleResult.value.title.isNotBlank()
            ) {
                record(
                    SourceCapability.DETAILS, sampleResult,
                    detailsDetail(sampleResult.value)
                )
                sampled = sampleResult.value
                proofBook = sampleResult.value.title
            }
        }
        val detailed: SourceBook = if (sampled != null) {
            sampled
        } else {
            proofBook = firstBook.title
            val detailsResult = service.guarded(handle) { it.details(firstBook) }
            record(
                SourceCapability.DETAILS, detailsResult,
                if (detailsResult is AppResult.Ok) {
                    detailsDetail(detailsResult.value)
                } else {
                    ""
                }
            )
            (detailsResult as? AppResult.Ok)?.value ?: firstBook
        }

        // CHAPTERS — the list must exist and be non-empty.
        // v1.0.5 (A1): an Ok-but-EMPTY list fails this step with
        // its own name — it used to pass silently and let the
        // text step report a misleading "no chapter to read".
        val chaptersResult = service.guarded(handle) { it.chapters(detailed) }
        val chapters = (chaptersResult as? AppResult.Ok)?.value
        if (chapters != null && chapters.isEmpty()) {
            results += CapabilityResult(
                capability = SourceCapability.CHAPTERS,
                ok = false,
                detail = "the book page listed 0 chapters"
            )
        } else {
            record(
                SourceCapability.CHAPTERS, chaptersResult,
                if (chapters != null) "${chapters.size} chapter(s)" else ""
            )
        }

        // TEXT — a chapter must produce readable text.
        // v1.0.5 (A3): sample an UNLOCKED chapter — chapters
        // whose names mark them paid/locked are skipped, the
        // sampler steps forward when one chapter is individually
        // unreadable, and when nothing unlocked exists (or every
        // sample is a paywall notice) the step reports the
        // distinct paid/locked state instead of "unreadable".
        val chapterList = chapters ?: emptyList()
        // v1.0.6 (WT): name-based lock detection misses sites that
        // mark locks only in the chapter ROW markup (novelight's
        // lock badge) — sampling then reads a locked chapter, gets
        // empty text and reports a breakage that is really the
        // site's paywall. For declarative sources the row-locked ids
        // are subtracted too; other source kinds behave exactly as
        // before (the service answers an empty set for them).
        val rowLockedIds = if (chapters != null) {
            service.lockedChapterIds(handle, detailed)
        } else {
            emptySet()
        }
        val unlockedChapters = chapterList.filterNot {
            ChapterLocks.nameSuggestsLocked(it.name) || it.id in rowLockedIds
        }
        when {
            chapters == null -> results += CapabilityResult(
                capability = SourceCapability.TEXT,
                ok = false,
                detail = "no chapter to read"
            )
            chapterList.isEmpty() -> results += CapabilityResult(
                capability = SourceCapability.TEXT,
                ok = false,
                detail = "not checked — the chapter list was empty"
            )
            unlockedChapters.isEmpty() -> results += CapabilityResult(
                capability = SourceCapability.TEXT,
                ok = false,
                detail = "all ${chapterList.size} chapter(s) are marked paid or " +
                    "locked on this site — locked chapters can't be sampled",
                // v1.0.6: a limit of the site, not a breakage —
                // the verdict reads WORKING_WITH_LIMITS when
                // this is the only core failure (see judge).
                limitOnly = true
            )
            else -> {
                var sampledName = ""
                var sampledPlain: String? = null
                var sampledError: String? = null
                var paywalled = false
                for (candidate in unlockedChapters.take(3)) {
                    val textResult = service.guarded(handle) {
                        it.chapterContent(candidate)
                    }
                    val plain = (textResult as? AppResult.Ok)?.value?.html
                        ?.replace(Regex("<[^>]+>"), " ")
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                    if (plain != null &&
                        (sampledPlain == null || plain.length > sampledPlain.length)
                    ) {
                        sampledPlain = plain
                        sampledName = candidate.name
                    }
                    if (textResult is AppResult.Err && sampledError == null) {
                        sampledError = textResult.error.detail
                    }
                    if (ChapterLocks.pageSuggestsLocked(plain ?: "")) paywalled = true
                    if (!plain.isNullOrBlank()) break
                }
                proofChapter = sampledName
                proofFirstLine = sampledPlain?.take(120) ?: ""
                results += CapabilityResult(
                    capability = SourceCapability.TEXT,
                    ok = !sampledPlain.isNullOrBlank(),
                    detail = when {
                        !sampledPlain.isNullOrBlank() ->
                            "${sampledPlain.length} characters read from " +
                                "'${sampledName.take(40)}'"
                        paywalled ->
                            "the chapters sampled ask for payment or sign-in " +
                                "instead of showing text"
                        sampledError != null ->
                            sampledError ?: "the chapter text didn't load"
                        else ->
                            "the chapter opened but its text was empty"
                    }
                )
            }
        }


        // R24: the account bookshelf, when the source declares one.
        // Read through the user's login session; a failure here is a
        // capability gap, never a core failure.
        if (service.declarativeConfigOf(handle)?.bookshelf != null) {
            results += when (val shelf = service.bookshelf(handle, 1)) {
                is AppResult.Ok -> CapabilityResult(
                    SourceCapability.BOOKSHELF, ok = true,
                    detail = "${shelf.value.books.size} books on the " +
                        "first bookshelf page"
                )
                is AppResult.Err -> CapabilityResult(
                    SourceCapability.BOOKSHELF, ok = false,
                    detail = "the bookshelf didn't load — log in on " +
                        "this source, then test again"
                )
            }
        }

        // Optional extras: not offered ≠ failure. The enrichment
        // calls run through the guarded path like everything else.
        results += optionalViaGuard(handle, SourceCapability.REVIEWS) {
            it.reviews(detailed)
        }
        results += optionalViaGuard(handle, SourceCapability.COMMENTS) {
            it.comments(detailed)
        }
        results += optionalViaGuard(handle, SourceCapability.RELATED) {
            it.related(detailed)
        }

        val now = System.currentTimeMillis()
        return ContractVerdict(
            state = ContractVerdict.judge(results, now),
            results = results,
            proofBookTitle = proofBook,
            proofChapterTitle = proofChapter,
            proofFirstLine = proofFirstLine,
            checkedAtMillis = now
        )
    }

    private suspend fun optionalViaGuard(
        handle: SourceHandle,
        capability: SourceCapability,
        call: (app.xreader.extensionapi.Source) -> Any?
    ): CapabilityResult {
        return when (val result = service.guarded(handle, call)) {
            is AppResult.Ok -> CapabilityResult(
                capability = capability,
                ok = true,
                detail = if (result.value == null) {
                    "this source doesn't offer it"
                } else {
                    "available"
                }
            )
            is AppResult.Err -> CapabilityResult(
                capability = capability,
                ok = false,
                detail = "offered, but it failed to load"
            )
        }
    }
}
