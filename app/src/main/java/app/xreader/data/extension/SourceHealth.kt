package app.xreader.data.extension

/**
 * Source contract health (v1.0.2, R10): a source is versioned data
 * with separately-tested capabilities. The READING CORE — list,
 * search, book details, chapter list, chapter text — is required.
 * Everything else (login reading, bookshelf import, reviews…) is an
 * optional capability that is badged honestly, never allowed to
 * block a good reading source (review finding C10).
 *
 * Pure model + verdict logic; the contract tester fills results in
 * from live calls, the UI renders them in plain words.
 */
enum class SourceCapability {
    LIST, SEARCH, DETAILS, CHAPTERS, TEXT,
    LOGIN_READING, BOOKSHELF, ACCOUNT_PROGRESS, ACCOUNT_BOOKMARKS,
    REVIEWS, COMMENTS, RELATED
}

enum class SourceHealthState {
    UNKNOWN, WORKING, WORKING_WITH_LIMITS, NEEDS_SETUP, BROKEN
}

data class CapabilityResult(
    val capability: SourceCapability,
    val ok: Boolean,
    /** Plain-words detail: what was tried / what failed. */
    val detail: String = ""
)

data class ContractVerdict(
    val state: SourceHealthState,
    val results: List<CapabilityResult>,
    /** Test proof, shown not asserted (R10): the book the tester
     * actually opened, its chapter, and the text's first line. */
    val proofBookTitle: String = "",
    val proofChapterTitle: String = "",
    val proofFirstLine: String = "",
    val checkedAtMillis: Long = 0
) {
    fun failedCore(): List<SourceCapability> =
        results.filter { it.capability in READING_CORE && !it.ok }
            .map { it.capability }

    fun failedOptional(): List<SourceCapability> =
        results.filter { it.capability !in READING_CORE && !it.ok }
            .map { it.capability }

    /** One plain-words line for source rows and detail headers. */
    fun summaryLine(): String = when (state) {
        SourceHealthState.WORKING -> "Working"
        SourceHealthState.WORKING_WITH_LIMITS ->
            "Working for reading — some extras are not available on this site"
        SourceHealthState.NEEDS_SETUP -> {
            val part = failedCore().firstOrNull()?.let { corePartName(it) }
            if (part != null) "Needs setup — $part could not be read"
            else "Needs setup"
        }
        SourceHealthState.BROKEN -> "Not working right now — try Repair"
        SourceHealthState.UNKNOWN -> "Not checked yet"
    }

    companion object {
        val READING_CORE: Set<SourceCapability> = setOf(
            SourceCapability.LIST, SourceCapability.SEARCH,
            SourceCapability.DETAILS, SourceCapability.CHAPTERS,
            SourceCapability.TEXT
        )

        fun corePartName(capability: SourceCapability): String = when (capability) {
            SourceCapability.LIST -> "the book list"
            SourceCapability.SEARCH -> "search"
            SourceCapability.DETAILS -> "book pages"
            SourceCapability.CHAPTERS -> "chapter lists"
            SourceCapability.TEXT -> "chapter text"
            else -> "part of the site"
        }

        /**
         * The verdict rules (R10): every core capability must pass
         * for a source to be called working. Optional failures make
         * it "working with limits". Any core failure means the
         * source can only be saved as Needs setup, and only after
         * the user explicitly chooses that.
         */
        fun judge(results: List<CapabilityResult>, checkedAtMillis: Long): SourceHealthState {
            if (results.isEmpty()) return SourceHealthState.UNKNOWN
            val core = results.filter { it.capability in READING_CORE }
            if (core.size < READING_CORE.size || core.any { !it.ok }) {
                return SourceHealthState.NEEDS_SETUP
            }
            val optionalFailed = results.any {
                it.capability !in READING_CORE && !it.ok
            }
            return if (optionalFailed) SourceHealthState.WORKING_WITH_LIMITS
            else SourceHealthState.WORKING
        }
    }
}

/**
 * The persisted form of a verdict (R10): flat and display-ready —
 * state, proof, when, and which core parts failed (capability
 * names). Stored per source by SourcePrefs; the full per-step
 * results live only in the test-drive that produced them.
 */
data class SourceHealthRecord(
    val state: SourceHealthState,
    val proofBookTitle: String = "",
    val proofChapterTitle: String = "",
    val proofFirstLine: String = "",
    val checkedAtMillis: Long = 0,
    val failedCore: List<String> = emptyList()
) {
    fun summaryLine(): String = when (state) {
        SourceHealthState.WORKING -> "Working"
        SourceHealthState.WORKING_WITH_LIMITS ->
            "Working for reading — some extras are not available on this site"
        SourceHealthState.NEEDS_SETUP -> {
            val first = failedCore.firstOrNull()?.let { name ->
                runCatching { SourceCapability.valueOf(name) }.getOrNull()
            }
            if (first != null) {
                "Needs setup — ${ContractVerdict.corePartName(first)} could not be read"
            } else {
                "Needs setup"
            }
        }
        SourceHealthState.BROKEN -> "Not working right now — try Repair"
        SourceHealthState.UNKNOWN -> "Not checked yet"
    }

    companion object {
        /** The SourcePrefs key for one source of one extension. */
        fun key(extensionId: Long, sourceId: String): String = "$extensionId:$sourceId"

        val NOT_CHECKED = SourceHealthRecord(state = SourceHealthState.UNKNOWN)
    }
}

fun ContractVerdict.toRecord(): SourceHealthRecord = SourceHealthRecord(
    state = state,
    proofBookTitle = proofBookTitle,
    proofChapterTitle = proofChapterTitle,
    proofFirstLine = proofFirstLine,
    checkedAtMillis = checkedAtMillis,
    failedCore = failedCore().map { it.name }
)
