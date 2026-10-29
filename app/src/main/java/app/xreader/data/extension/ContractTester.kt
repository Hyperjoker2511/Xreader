package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.extensionapi.SourceBook

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

    suspend fun test(handle: SourceHandle): ContractVerdict {
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
        record(
            SourceCapability.LIST, listResult,
            if (listResult is AppResult.Ok) {
                "${listResult.value.books.size} book(s) listed"
            } else {
                ""
            }
        )
        val firstBook: SourceBook? = (listResult as? AppResult.Ok)?.value
            ?.books?.firstOrNull()

        // SEARCH — a distinctive word from the first listed title,
        // or a neutral word when the list was empty.
        val probeWord = firstBook?.title
            ?.split(Regex("[^A-Za-z]+"))
            ?.filter { it.length >= 4 }
            ?.maxByOrNull { it.length }
            ?: "the"
        val searchResult = service.guarded(handle) { it.search(probeWord, 1) }
        record(
            SourceCapability.SEARCH, searchResult,
            if (searchResult is AppResult.Ok) {
                "searching “$probeWord” found ${searchResult.value.books.size} book(s)"
            } else {
                ""
            }
        )

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
        proofBook = firstBook.title

        // DETAILS — the book page fills in.
        val detailsResult = service.guarded(handle) { it.details(firstBook) }
        record(
            SourceCapability.DETAILS, detailsResult,
            if (detailsResult is AppResult.Ok) {
                "author: ${detailsResult.value.author ?: "—"}"
            } else {
                ""
            }
        )
        val detailed = (detailsResult as? AppResult.Ok)?.value ?: firstBook

        // CHAPTERS — the list must exist and be non-empty.
        val chaptersResult = service.guarded(handle) { it.chapters(detailed) }
        val chapters = (chaptersResult as? AppResult.Ok)?.value
        record(
            SourceCapability.CHAPTERS, chaptersResult,
            if (chapters != null) "${chapters.size} chapter(s)" else ""
        )

        // TEXT — the first chapter must produce readable text.
        val firstChapter = chapters?.firstOrNull()
        if (firstChapter == null) {
            results += CapabilityResult(
                capability = SourceCapability.TEXT,
                ok = false,
                detail = "no chapter to read"
            )
        } else {
            proofChapter = firstChapter.name
            val textResult = service.guarded(handle) {
                it.chapterContent(firstChapter)
            }
            val plain = (textResult as? AppResult.Ok)?.value?.html
                ?.replace(Regex("<[^>]+>"), " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
            proofFirstLine = plain?.take(120) ?: ""
            results += CapabilityResult(
                capability = SourceCapability.TEXT,
                ok = textResult is AppResult.Ok && !plain.isNullOrBlank(),
                detail = if (textResult is AppResult.Ok) {
                    if (plain.isNullOrBlank()) {
                        "the chapter opened but its text was empty"
                    } else {
                        "${plain.length} characters read"
                    }
                } else {
                    (textResult as AppResult.Err).error.detail
                        ?: "the chapter text didn't load"
                }
            )
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
