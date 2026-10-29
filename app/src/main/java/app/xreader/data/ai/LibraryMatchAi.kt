package app.xreader.data.ai

import app.xreader.core.error.AppResult
import app.xreader.data.extension.AccountBook
import app.xreader.data.extension.ExistingBookState
import app.xreader.data.extension.WorkMatcher
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * AI-assisted matching for "Bring my library" (v1.0.3).
 *
 * The planner's own matching is exact (source identity, then
 * [WorkMatcher.sameWork]) — and stops there, because a wrong silent
 * match is worse than a missed one. This helper covers the gap the
 * exact rules can't: the same novel listed under a slightly
 * different title on another site ("Martial God Asura" vs
 * "Martial God Asura (Web Novel)", a translated subtitle, a dropped
 * leading "The").
 *
 * The shape of the assist is deliberately narrow:
 *  1. A pure token-overlap shortlist ([FuzzyShortlist]) picks the
 *     few existing books that could plausibly be the same work.
 *     No shortlist → the AI is never even asked.
 *  2. The model adjudicates ONE account book against its shortlist
 *     and answers with a candidate number or NONE.
 *  3. The answer is a PROPOSAL. The import screen shows it — "AI
 *     thinks this is your copy of X" — and only the user's Confirm
 *     treats it as a match. Nothing here writes anything.
 */
object FuzzyShortlist {

    /** Minimum title-token overlap (Jaccard) to make the list. */
    const val MIN_SCORE = 0.34
    const val MAX_CANDIDATES = 5

    fun score(
        titleA: String,
        authorA: String,
        titleB: String,
        authorB: String
    ): Double {
        val tokensA = tokens(titleA)
        val tokensB = tokens(titleB)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0
        val overlap = tokensA.intersect(tokensB).size.toDouble()
        val union = tokensA.union(tokensB).size.toDouble()
        var score = overlap / union
        val authorA = WorkMatcher.normaliseAuthor(authorA)
        val authorB = WorkMatcher.normaliseAuthor(authorB)
        if (authorA.isNotBlank() && authorA == authorB) score += 0.15
        return score.coerceAtMost(1.0)
    }

    /** Existing books that could be [account], best first. Books
     * the exact matcher already claimed are not re-shortlisted —
     * [claimedBookIds] carries them. */
    fun shortlist(
        account: AccountBook,
        existing: List<ExistingBookState>,
        claimedBookIds: Set<Long>
    ): List<ExistingBookState> =
        existing
            .filter { it.bookId !in claimedBookIds }
            .map { state ->
                state to score(account.title, account.author, state.title, state.author)
            }
            .filter { (_, score) -> score >= MIN_SCORE }
            .sortedByDescending { (_, score) -> score }
            .take(MAX_CANDIDATES)
            .map { (state, _) -> state }

    private fun tokens(title: String): Set<String> =
        WorkMatcher.normaliseTitle(title)
            .split(" ")
            .filter { it.length > 1 }
            .toSet()
}

class LibraryMatchAi(
    private val client: AiClient,
    private val settingsProvider: suspend () -> AiSettings
) {

    /** A proposed match awaiting the user's word. */
    data class Proposal(
        val account: AccountBook,
        val existing: ExistingBookState
    )

    /**
     * Asks the model about ONE account book and its shortlist.
     * Ok(null) = the model said none of these is the same work (or
     * its answer couldn't be read) — a proposal is never invented
     * from an unclear answer.
     */
    suspend fun adjudicate(
        account: AccountBook,
        candidates: List<ExistingBookState>
    ): AppResult<Proposal?> {
        if (candidates.isEmpty()) return AppResult.Ok(null)
        if (!settingsProvider().enabled(AiFeature.LIBRARY_MATCH)) {
            return AppResult.Ok(null)
        }
        val prompt = buildString {
            append("A reader is importing their bookshelf from another site. ")
            append("Decide whether the book below is the SAME novel as one of ")
            append("the numbered candidates in their library — the same work ")
            append("can appear under a slightly different title, a translated ")
            append("subtitle, or a spelling variant. Different novels in the ")
            append("same series are NOT the same book.\n\n")
            append("Book: \"")
            append(account.title)
            append("\"")
            if (account.author.isNotBlank()) {
                append(" by ")
                append(account.author)
            }
            append("\n\nCandidates:\n")
            candidates.forEachIndexed { index, candidate ->
                append(index + 1)
                append(". \"")
                append(candidate.title)
                append("\"")
                if (candidate.author.isNotBlank()) {
                    append(" by ")
                    append(candidate.author)
                }
                append("\n")
            }
            append(
                "\nAnswer with ONLY a JSON object: {\"match\": N} where N is " +
                    "the candidate number, or {\"match\": 0} when none is the " +
                    "same novel."
            )
        }
        return when (val result = client.generateText(prompt, jsonMode = true)) {
            is AppResult.Err -> result
            is AppResult.Ok -> AppResult.Ok(
                parseChoice(result.value.text, account, candidates)
            )
        }
    }

    companion object {

        /**
         * The model's reply → a proposal. Reads `{"match": N}`
         * (1-based; 0/none/invalid → null), tolerating a bare
         * number in prose. An out-of-range or unreadable answer is
         * NO proposal — never a guess. Pure and unit-tested.
         */
        fun parseChoice(
            text: String,
            account: AccountBook,
            candidates: List<ExistingBookState>
        ): Proposal? {
            val obj = AiJson.extractObject(text)
            val number = obj?.let { extractMatchNumber(it) }
                ?: Regex("""-?\d+""").find(text)?.value?.toIntOrNull()
                ?: return null
            if (number <= 0 || number > candidates.size) return null
            return Proposal(account, candidates[number - 1])
        }

        private fun extractMatchNumber(obj: JsonObject): Int? {
            val raw = obj["match"] as? JsonPrimitive ?: return null
            return raw.content.trim().toIntOrNull()
        }
    }
}
