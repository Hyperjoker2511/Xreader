package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.AiSummary
import app.xreader.data.db.AiSummaryDao
import app.xreader.data.db.Chapter
import app.xreader.data.extension.Hashes
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Chapter + book-so-far summaries (PLAN §7), cached in Room by
 * content hash so re-reads cost nothing: a chapter whose text hasn't
 * changed is never re-sent to the model. Arc recaps summarise the
 * cached chapter summaries — never the raw text again.
 */
class Summarizer(
    private val client: AiClient,
    private val summaryDao: AiSummaryDao,
    private val settingsProvider: suspend () -> AiSettings
) {

    data class Summary(
        val bullets: List<String>,
        val modelUsed: String,
        val fromCache: Boolean
    )

    suspend fun chapterSummary(
        bookId: Long,
        bookTitle: String,
        chapter: Chapter,
        chapterText: String,
        force: Boolean = false
    ): AppResult<Summary> {
        val hash = Hashes.sha256Hex(chapterText)
        if (!force) {
            summaryDao.get(bookId, chapter.id)?.takeIf { it.contentHash == hash }?.let {
                return AppResult.Ok(
                    Summary(decodeBullets(it.bulletsJson), it.modelUsed, fromCache = true)
                )
            }
        }
        if (!settingsProvider().enabled(AiFeature.SUMMARIZER)) {
            return AppResult.Err(featureOff())
        }
        val bullets = when (
            val result = summariseText(bookTitle, chapter.title, chapterText)
        ) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value
        }
        summaryDao.upsert(
            AiSummary(
                bookId = bookId,
                chapterId = chapter.id,
                contentHash = hash,
                bulletsJson = encodeBullets(bullets.first),
                modelUsed = bullets.second,
                createdAt = System.currentTimeMillis()
            )
        )
        return AppResult.Ok(Summary(bullets.first, bullets.second, fromCache = false))
    }

    /**
     * "Catch me up": an arc recap over the cached chapter summaries
     * for chapters up to and including [throughChapter]. The recap is
     * itself cached against the exact set of summaries it was built
     * from, so it refreshes only when a new chapter summary lands.
     */
    suspend fun bookSoFar(
        bookId: Long,
        bookTitle: String,
        chapters: List<Chapter>,
        throughChapter: Chapter,
        force: Boolean = false
    ): AppResult<Summary> {
        val eligible = chapters.filter { it.index <= throughChapter.index }
        val parts = mutableListOf<Pair<Chapter, AiSummary>>()
        for (chapter in eligible) {
            summaryDao.get(bookId, chapter.id)?.let { parts += chapter to it }
        }
        if (parts.isEmpty()) {
            return AppResult.Err(
                AppError.NeutralVerdict(
                    "There are no chapter summaries yet — summarize a chapter first, " +
                        "then the story-so-far recap can be built from them."
                )
            )
        }
        val fingerprint = Hashes.sha256Hex(
            parts.joinToString("|") { "${it.first.id}:${it.second.contentHash}" } +
                "#${throughChapter.id}"
        )
        if (!force) {
            summaryDao.get(bookId, ARC_CHAPTER_ID)?.takeIf { it.contentHash == fingerprint }
                ?.let {
                    return AppResult.Ok(
                        Summary(decodeBullets(it.bulletsJson), it.modelUsed, fromCache = true)
                    )
                }
        }
        if (!settingsProvider().enabled(AiFeature.SUMMARIZER)) {
            return AppResult.Err(featureOff())
        }
        val digest = parts.joinToString("\n") { (chapter, summary) ->
            "Chapter ${chapter.index + 1} (${chapter.title}): " +
                decodeBullets(summary.bulletsJson).joinToString(" ")
        }
        val prompt = """
            Below are short summaries of the chapters of the novel "$bookTitle" up to
            chapter ${throughChapter.index + 1}. Write a "story so far" recap as a JSON
            array of exactly 4 short bullet-point strings: where the story stands, what
            the main character wants, who matters most right now, and what is unresolved.
            Answer with ONLY the JSON array.

            $digest
        """.trimIndent()
        val result = client.generateText(prompt, jsonMode = true)
        val generated = when (result) {
            is AppResult.Err -> return result
            is AppResult.Ok -> parseBullets(result.value.text, expected = 4) to result.value.modelUsed
        }
        summaryDao.upsert(
            AiSummary(
                bookId = bookId,
                chapterId = ARC_CHAPTER_ID,
                contentHash = fingerprint,
                bulletsJson = encodeBullets(generated.first),
                modelUsed = generated.second,
                createdAt = System.currentTimeMillis()
            )
        )
        return AppResult.Ok(Summary(generated.first, generated.second, fromCache = false))
    }

    /** A cached chapter summary, when one exists and still matches. */
    suspend fun cachedChapterSummary(
        bookId: Long,
        chapter: Chapter,
        chapterText: String
    ): Summary? {
        val hash = Hashes.sha256Hex(chapterText)
        return summaryDao.get(bookId, chapter.id)
            ?.takeIf { it.contentHash == hash }
            ?.let { Summary(decodeBullets(it.bulletsJson), it.modelUsed, fromCache = true) }
    }

    // -- internals ------------------------------------------------------------

    private suspend fun summariseText(
        bookTitle: String,
        chapterTitle: String,
        text: String
    ): AppResult<Pair<List<String>, String>> {
        // Long chapters are chunk-summarised first, then the chunk
        // notes are summarised into the chapter's 3 bullets.
        val chunks = chunkText(text, CHUNK_CHARS)
        var modelUsed = ""
        val notes = mutableListOf<String>()
        for (chunk in chunks) {
            val prompt = if (chunks.size == 1) {
                chapterPrompt(bookTitle, chapterTitle, chunk)
            } else {
                "Summarize this part of a chapter of the novel \"$bookTitle\" in 2 short " +
                    "sentences of plain prose (no bullets, no JSON):\n\n$chunk"
            }
            when (val result = client.generateText(prompt, jsonMode = chunks.size == 1)) {
                is AppResult.Err -> return result
                is AppResult.Ok -> {
                    modelUsed = result.value.modelUsed
                    if (chunks.size == 1) {
                        return AppResult.Ok(parseBullets(result.value.text, expected = 3) to modelUsed)
                    }
                    notes += result.value.text.trim()
                }
            }
        }
        val finalPrompt = chapterPrompt(
            bookTitle, chapterTitle, notes.joinToString("\n")
        )
        return when (val result = client.generateText(finalPrompt, jsonMode = true)) {
            is AppResult.Err -> result
            is AppResult.Ok -> AppResult.Ok(
                parseBullets(result.value.text, expected = 3) to result.value.modelUsed
            )
        }
    }

    private fun chapterPrompt(bookTitle: String, chapterTitle: String, text: String): String =
        """
        Summarize this chapter of the novel "$bookTitle" (chapter title: "$chapterTitle")
        as a JSON array of exactly 3 short bullet-point strings: the key events, in the
        order they happen, in plain words a reader would understand. Answer with ONLY
        the JSON array.

        $text
        """.trimIndent()

    private fun featureOff(): AppError =
        AppError.NeutralVerdict("Chapter summaries are turned off — switch them on in Providers & keys.")

    companion object {
        /** chapterId marker for the book-so-far arc recap row (§1.3). */
        const val ARC_CHAPTER_ID: Long = -1L

        const val CHUNK_CHARS = 12_000

        private val json = Json { ignoreUnknownKeys = true }

        fun encodeBullets(bullets: List<String>): String =
            json.encodeToString(ListSerializer(String.serializer()), bullets)

        fun decodeBullets(raw: String): List<String> =
            runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), raw)
            }.getOrDefault(emptyList())

        /**
         * Model output → bullets. Prefers a JSON array of strings;
         * falls back to markdown-ish lines. Never throws; a response
         * with no usable lines yields a single trimmed bullet.
         */
        fun parseBullets(text: String, expected: Int): List<String> {
            val element = AiJson.extract(text)
            if (element is kotlinx.serialization.json.JsonArray) {
                val items = element.mapNotNull { item ->
                    (item as? kotlinx.serialization.json.JsonPrimitive)?.content
                        ?.trim()?.ifBlank { null }
                }
                if (items.isNotEmpty()) return items.take(expected + 2)
            }
            val lines = text.lines()
                .map { it.trim().trimStart('-', '•', '*', ' ').trim() }
                .filter { it.length > 3 }
            if (lines.isNotEmpty()) return lines.take(expected + 2)
            return listOf(text.trim().take(400)).filter { it.isNotBlank() }
        }

        /** Splits on paragraph boundaries into ≤ [maxChars] chunks. */
        fun chunkText(text: String, maxChars: Int): List<String> {
            if (text.length <= maxChars) return listOf(text)
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            for (paragraph in text.split("\n\n")) {
                if (paragraph.length > maxChars) {
                    if (current.isNotEmpty()) {
                        chunks += current.toString()
                        current.clear()
                    }
                    var start = 0
                    while (start < paragraph.length) {
                        chunks += paragraph.substring(
                            start, minOf(start + maxChars, paragraph.length)
                        )
                        start += maxChars
                    }
                    continue
                }
                if (current.length + paragraph.length + 2 > maxChars &&
                    current.isNotEmpty()
                ) {
                    chunks += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
            if (current.isNotEmpty()) chunks += current.toString()
            return chunks
        }
    }
}
