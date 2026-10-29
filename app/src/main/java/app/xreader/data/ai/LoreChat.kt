package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.LoreEntity
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Where the reader currently is — the spoiler guard's boundary. */
data class ReadingPosition(val chapterIndex: Int, val charOffset: Int)

/**
 * Assembling the Lore Chat context (PLAN §7) — a pure function with a
 * hard rule: **nothing from beyond the reader's position may enter
 * the prompt.** Concretely:
 *  - chapter summaries are included only for chapters BEFORE the
 *    current one (the current chapter's own summary could describe
 *    its ending);
 *  - lore entities are included only when they first appeared at or
 *    before the current chapter;
 *  - the current chapter's text is cut exactly at the reader's char
 *    offset (a window ending there);
 *  - later chapters contribute nothing at all — not even titles.
 */
object LoreChatContext {

    const val MAX_CHAPTER_WINDOW = 6_000
    const val MAX_LORE_ENTITIES = 80
    const val MAX_SUMMARIES = 40

    fun build(
        bookTitle: String,
        lore: List<LoreEntity>,
        summariesBefore: List<Pair<Int, List<String>>>,
        currentChapterText: String,
        position: ReadingPosition
    ): String {
        val safeLore = lore
            .filter { it.firstChapterIndex <= position.chapterIndex }
            .sortedWith(compareBy({ it.firstChapterIndex }, { it.name }))
            .take(MAX_LORE_ENTITIES)
        val safeSummaries = summariesBefore
            .filter { it.first < position.chapterIndex }
            .sortedBy { it.first }
            .takeLast(MAX_SUMMARIES)
        val offset = position.charOffset.coerceIn(0, currentChapterText.length)
        val window = currentChapterText.substring(0, offset)
            .takeLast(MAX_CHAPTER_WINDOW)

        return buildString {
            append("You are answering questions about the novel \"")
            append(bookTitle)
            append("\" for a reader who is part-way through chapter ")
            append(position.chapterIndex + 1)
            append(". Answer ONLY from the material below — the reader has NOT read ")
            append("anything beyond this point, so never reveal, hint at, or use events ")
            append("after it. If the answer isn't in the material, say you don't know ")
            append("yet — do not guess from general knowledge of similar stories.\n")
            if (safeLore.isNotEmpty()) {
                append("\n== What the reader's lore notes know so far ==\n")
                for (entity in safeLore) {
                    append("- ")
                    append(entity.name)
                    append(" (")
                    append(entity.type.name.lowercase())
                    append("): ")
                    append(entity.description.ifBlank { "no notes yet" })
                    append("\n")
                }
            }
            if (safeSummaries.isNotEmpty()) {
                append("\n== Summaries of chapters already read ==\n")
                for ((index, bullets) in safeSummaries) {
                    append("Chapter ")
                    append(index + 1)
                    append(": ")
                    append(bullets.joinToString(" "))
                    append("\n")
                }
            }
            append("\n== The chapter being read, up to the reader's place ==\n")
            append(window.ifBlank { "(the reader is at the very start of this chapter)" })
            append("\n")
        }
    }
}

/** Streaming question-answer over the guarded context. */
class LoreChat(
    private val client: AiClient,
    private val settingsProvider: suspend () -> AiSettings
) {

    data class Exchange(val question: String, val answer: String)

    /**
     * Streams the answer for [question]. [history] holds the earlier
     * exchanges of this session (bounded by the caller); they ride in
     * the prompt after the guarded context.
     */
    fun ask(
        context: String,
        history: List<Exchange>,
        question: String
    ): Flow<GeminiClient.StreamEvent> = flow {
        if (!settingsProvider().enabled(AiFeature.LORE_CHAT)) {
            emit(
                GeminiClient.StreamEvent.Failed(
                    AppError.BadFile(
                        "Lore Chat is turned off — switch it on in Providers & keys."
                    )
                )
            )
            return@flow
        }
        val prompt = buildString {
            append(context)
            if (history.isNotEmpty()) {
                append("\n== Earlier in this conversation ==\n")
                for (exchange in history.takeLast(4)) {
                    append("Reader asked: ")
                    append(exchange.question.take(300))
                    append("\nYou answered: ")
                    append(exchange.answer.take(600))
                    append("\n")
                }
            }
            append("\nReader's question: ")
            append(question)
        }
        client.generateTextStream(prompt).collect { emit(it) }
    }

    /** Availability for UI states: key present + feature on. */
    suspend fun availability(): AppResult<Unit> =
        if (!client.hasKey()) {
            AppResult.Err(
                AppError.AuthFailed("AI", "no AI key is saved on this device")
            )
        } else {
            AppResult.Ok(Unit)
        }
}
