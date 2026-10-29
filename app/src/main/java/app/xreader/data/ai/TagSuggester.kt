package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.library.TagRegistry
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One AI-proposed tag, canonicalised and ready to show. */
data class TagSuggestion(
    val slug: String,
    val displayName: String,
    /** True when the label resolved to a registry tag (genre, MC
     * type, theme…); false for a new tag the registry doesn't know. */
    val known: Boolean
)

/**
 * AI tag suggestions from a book's synopsis (v1.0.3) — the model
 * proposes, the user disposes. Nothing is written by this class:
 * suggestions land in the book page's state as chips, and only an
 * explicit Accept writes a tag (with INFERRED origin, like the
 * heuristic extractor's, so it stays labelled as a guess and can be
 * removed — which writes the usual tombstone).
 *
 * Labels are canonicalised through [TagRegistry] exactly like source
 * labels: "Male Lead" becomes the Male MC tag, an unknown label
 * becomes its own slugified tag. Labels already on the book (visible
 * or user-suppressed) are never re-suggested.
 */
class TagSuggester(
    private val client: AiClient,
    private val settingsProvider: suspend () -> AiSettings
) {

    suspend fun suggest(
        book: Book,
        existingSlugs: Set<String>
    ): AppResult<List<TagSuggestion>> {
        if (!settingsProvider().enabled(AiFeature.TAG_SUGGESTIONS)) {
            return AppResult.Err(
                AppError.BadFile(
                    "AI tag suggestions are turned off — switch them on in " +
                        "Providers & keys."
                )
            )
        }
        val synopsis = book.description.trim()
        if (synopsis.isBlank()) {
            return AppResult.Err(
                AppError.BadFile(
                    "This book has no synopsis for the AI to read yet."
                )
            )
        }
        val prompt = buildString {
            append("You tag web novels for a reader's library. From the synopsis ")
            append("below, choose up to $MAX_SUGGESTIONS tags describing this ")
            append("novel: its genres, the main character's type (for example ")
            append("\"Male MC\", \"Overpowered MC\", \"Smart MC\"), and its big ")
            append("themes (for example \"Revenge\", \"Cultivation\", ")
            append("\"Kingdom Building\"). Prefer common NovelUpdates-style tag ")
            append("names. Answer with ONLY a JSON array of tag-name strings.\n\n")
            append("Title: ")
            append(book.title)
            append("\n")
            book.author?.takeIf { it.isNotBlank() }?.let {
                append("Author: ")
                append(it)
                append("\n")
            }
            append("Synopsis: ")
            append(synopsis.take(MAX_SYNOPSIS_CHARS))
        }
        return when (val result = client.generateText(prompt, jsonMode = true)) {
            is AppResult.Err -> result
            is AppResult.Ok -> AppResult.Ok(
                parseSuggestions(result.value.text, existingSlugs)
            )
        }
    }

    companion object {

        const val MAX_SUGGESTIONS = 8
        const val MAX_SYNOPSIS_CHARS = 2_000

        /**
         * Model output → canonical suggestions. Tolerant of the
         * usual shapes (a JSON array of strings, objects with a
         * `name`, or one label per line); dedupes by slug, drops
         * anything already decided for this book, caps the list.
         * Pure and unit-tested.
         */
        fun parseSuggestions(
            text: String,
            existingSlugs: Set<String>
        ): List<TagSuggestion> {
            val labels = mutableListOf<String>()
            val extracted = AiJson.extract(text)
            val array = when (extracted) {
                is JsonArray -> extracted
                is JsonObject -> (extracted["tags"] as? JsonArray)
                    ?: (extracted["suggestions"] as? JsonArray)
                else -> null
            }
            when (array) {
                is JsonArray -> array.forEach { item ->
                    when (item) {
                        is JsonPrimitive -> labels += item.content
                        is JsonObject -> {
                            val name = (item["name"] as? JsonPrimitive)?.content
                                ?: (item["tag"] as? JsonPrimitive)?.content
                            name?.let { labels += it }
                        }
                        else -> Unit
                    }
                }
                else -> text.lines().forEach { line ->
                    line.trim().trimStart('-', '•', '*', ' ').trim()
                        .takeIf { it.length in 2..60 }?.let { labels += it }
                }
            }
            val seen = mutableSetOf<String>()
            val suggestions = mutableListOf<TagSuggestion>()
            for (label in labels) {
                val trimmed = label.trim()
                if (trimmed.isBlank()) continue
                val canonical = TagRegistry.normalise(trimmed)
                val slug = canonical?.slug ?: TagRegistry.slugify(trimmed)
                if (slug.isBlank() || slug in existingSlugs || !seen.add(slug)) {
                    continue
                }
                suggestions += TagSuggestion(
                    slug = slug,
                    displayName = canonical?.displayName
                        ?: TagRegistry.displayFor(slug),
                    known = canonical != null
                )
                if (suggestions.size >= MAX_SUGGESTIONS) break
            }
            return suggestions
        }
    }
}
