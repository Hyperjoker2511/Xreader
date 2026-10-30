package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.TerminologyEntry
import app.xreader.data.files.BookStorage
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The RAW source languages the translator handles (PLAN §0.1). */
enum class SourceLanguage(val label: String) {
    KOREAN("Korean"),
    CHINESE("Chinese"),
    JAPANESE("Japanese"),
    ENGLISH("English"),
    OTHER("another language")
}

/**
 * Script-ratio language detection — deliberately simple and honest:
 * Hangul syllables ⇒ Korean, kana ⇒ Japanese, Han-only ⇒ Chinese,
 * mostly Latin ⇒ English. Samples the text (head + middle) so a long
 * chapter costs nothing.
 */
object LanguageDetect {

    fun detect(text: String): SourceLanguage {
        val sample = buildString {
            append(text.take(2000))
            if (text.length > 6000) {
                append(text.substring(text.length / 2, text.length / 2 + 2000))
            }
        }
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        for (c in sample) {
            when {
                c in '가'..'힣' || c in 'ᄀ'..'ᇿ' -> hangul++
                c in 'ぁ'..'ヿ' -> kana++
                c in '一'..'鿿' -> han++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        val letters = hangul + kana + han + latin
        if (letters < 20) return SourceLanguage.OTHER
        return when {
            hangul.toFloat() / letters > 0.25f -> SourceLanguage.KOREAN
            kana > 0 && kana.toFloat() / letters > 0.03f -> SourceLanguage.JAPANESE
            han.toFloat() / letters > 0.25f -> SourceLanguage.CHINESE
            latin.toFloat() / letters > 0.6f -> SourceLanguage.ENGLISH
            else -> SourceLanguage.OTHER
        }
    }
}

/**
 * Smart RAW translation (PLAN §7): an untranslated chapter (Korean /
 * Chinese / Japanese) becomes English, paragraph by paragraph, with
 * the book's glossary injected — locked terms are instructions the
 * model must follow, and LoreKeeper names ride along so spellings
 * stay consistent with the rest of the book. Translations stream in
 * chunk by chunk and are cached beside the original content
 * (BookStorage's `tr-` files) as first-class, re-readable content.
 */
class RawTranslator(
    private val client: AiClient,
    private val storage: BookStorage,
    private val settingsProvider: suspend () -> AiSettings
) {

    sealed interface Event {
        /** Cumulative translated plain text so far. */
        data class Progress(val translatedText: String, val chunksDone: Int, val chunksTotal: Int) : Event
        data class Done(val content: ChapterContent, val modelUsed: String) : Event
        data class Failed(val error: AppError) : Event
    }

    fun translate(
        book: Book,
        chapter: Chapter,
        content: ChapterContent,
        glossary: List<TerminologyEntry>,
        loreNames: List<String>,
        language: SourceLanguage
    ): Flow<Event> = flow {
        if (!settingsProvider().enabled(AiFeature.RAW_TRANSLATION)) {
            emit(
                Event.Failed(
                    AppError.NeutralVerdict(
                        "RAW translation is turned off — switch it on in Providers & keys."
                    )
                )
            )
            return@flow
        }
        if (language == SourceLanguage.ENGLISH) {
            emit(
                Event.Failed(
                    AppError.NeutralVerdict("This chapter already reads as English — nothing to translate.")
                )
            )
            return@flow
        }
        val system = systemPrompt(book.title, glossary, loreNames, language)
        val outBlocks = mutableListOf<ChapterBlock>()
        val plainSoFar = StringBuilder()
        var modelUsed = ""
        // Headings translate in one small batch; paragraphs stream in
        // chunks, in order, so the reader shows progress live.
        val headingTexts = content.blocks.filterIsInstance<ChapterBlock.Heading>()
            .map { it.text }
        val translatedHeadings = if (headingTexts.isNotEmpty()) {
            when (
                val result = client.generateText(
                    prompt = headingTexts.joinToString("\n"),
                    system = system +
                        "\nTranslate each line separately and answer with the same " +
                        "number of lines, nothing else.",
                    temperature = 0.2f
                )
            ) {
                is AppResult.Err -> {
                    emit(Event.Failed(result.error))
                    return@flow
                }
                is AppResult.Ok -> {
                    modelUsed = result.value.modelUsed
                    val lines = result.value.text.lines().map { it.trim() }
                    if (lines.size == headingTexts.size) lines else headingTexts
                }
            }
        } else {
            emptyList()
        }
        var headingCursor = 0
        val paragraphTexts = content.blocks.filterIsInstance<ChapterBlock.Paragraph>()
            .map { it.text }
        val chunks = chunkParagraphs(paragraphTexts, CHUNK_CHARS)
        val translatedParagraphs = mutableListOf<String>()
        var failure: AppError? = null
        var chunkIndex = 0
        for (chunk in chunks) {
            chunkIndex++
            val source = chunk.joinToString("\n\n")
            var chunkText = ""
            var chunkFailed: AppError? = null
            client.generateTextStream(
                prompt = source,
                system = system,
                temperature = 0.2f
            ).collect { event ->
                when (event) {
                    is GeminiClient.StreamEvent.Delta -> {
                        chunkText += event.text
                        emit(
                            Event.Progress(
                                translatedText = (translatedParagraphs + chunkText)
                                    .joinToString("\n\n"),
                                chunksDone = chunkIndex - 1,
                                chunksTotal = chunks.size
                            )
                        )
                    }
                    is GeminiClient.StreamEvent.Done -> modelUsed = event.modelUsed
                    is GeminiClient.StreamEvent.Failed -> chunkFailed = event.error
                }
            }
            if (chunkFailed != null) {
                failure = chunkFailed
                break
            }
            translatedParagraphs += splitTranslated(chunkText, chunk.size)
        }
        if (failure != null) {
            emit(Event.Failed(failure!!))
            return@flow
        }
        var paragraphCursor = 0
        for (block in content.blocks) {
            when (block) {
                is ChapterBlock.Heading -> {
                    val text = translatedHeadings.getOrNull(headingCursor) ?: block.text
                    headingCursor++
                    outBlocks += ChapterBlock.Heading(block.level, text, block.id)
                    plainSoFar.append(text).append("\n\n")
                }
                is ChapterBlock.Paragraph -> {
                    val text = translatedParagraphs.getOrNull(paragraphCursor) ?: block.text
                    paragraphCursor++
                    outBlocks += ChapterBlock.Paragraph.plain(text, block.id)
                    plainSoFar.append(text).append("\n\n")
                }
                is ChapterBlock.Image -> outBlocks += block
                ChapterBlock.Rule -> outBlocks += block
            }
        }
        val translated = ChapterContent(blocks = outBlocks)
        when (val saved = storage.writeTranslatedContent(book.id, chapter.id, translated)) {
            is AppResult.Err -> emit(Event.Failed(saved.error))
            is AppResult.Ok -> emit(Event.Done(translated, modelUsed))
        }
    }

    companion object {
        const val CHUNK_CHARS = 3_500

        fun systemPrompt(
            bookTitle: String,
            glossary: List<TerminologyEntry>,
            loreNames: List<String>,
            language: SourceLanguage
        ): String = buildString {
            append("You are a literary translator. Translate the following ")
            append(language.label)
            append(" web-novel text from \"")
            append(bookTitle)
            append("\" into natural, readable English. Keep the paragraph breaks ")
            append("exactly as given. Translate dialogue and narration in the same ")
            append("voice; do not summarise, skip, or add anything.\n")
            val locked = glossary.filter { it.locked }
            val soft = glossary.filter { !it.locked }
            if (locked.isNotEmpty()) {
                append("ALWAYS translate these terms exactly as shown:\n")
                locked.forEach {
                    append("- ")
                    append(it.sourceTerm)
                    append(" → ")
                    append(it.chosenTranslation)
                    append("\n")
                }
            }
            if (soft.isNotEmpty()) {
                append("Preferred translations for these terms:\n")
                soft.take(60).forEach {
                    append("- ")
                    append(it.sourceTerm)
                    append(" → ")
                    append(it.chosenTranslation)
                    append("\n")
                }
            }
            if (loreNames.isNotEmpty()) {
                append("Keep these names spelled exactly like this: ")
                append(loreNames.take(60).joinToString(", "))
                append("\n")
            }
            append("Answer with ONLY the translated text.")
        }

        /** Splits paragraphs into ≤ [maxChars] chunks on paragraph
         * boundaries (a single oversize paragraph goes alone). */
        fun chunkParagraphs(paragraphs: List<String>, maxChars: Int): List<List<String>> {
            val chunks = mutableListOf<List<String>>()
            var current = mutableListOf<String>()
            var size = 0
            for (paragraph in paragraphs) {
                if (current.isNotEmpty() && size + paragraph.length > maxChars) {
                    chunks += current
                    current = mutableListOf()
                    size = 0
                }
                current += paragraph
                size += paragraph.length + 2
            }
            if (current.isNotEmpty()) chunks += current
            return chunks
        }

        /** A translated chunk back into per-paragraph strings. When
         * the model returns a different paragraph count than it was
         * given, paragraphs merge gracefully instead of breaking. */
        fun splitTranslated(translated: String, expected: Int): List<String> {
            val parts = translated.split(Regex("\n\\s*\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            return when {
                parts.isEmpty() -> listOf(translated.trim())
                parts.size == expected -> parts
                else -> parts
            }
        }
    }
}
