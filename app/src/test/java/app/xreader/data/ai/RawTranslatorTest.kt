package app.xreader.data.ai

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.db.Book
import app.xreader.data.db.Chapter
import app.xreader.data.db.TerminologyEntry
import app.xreader.data.files.BookStorage
import app.xreader.data.prefs.AiSettings
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Language detection + RAW translation (PLAN §7): the script-ratio
 * detector, the glossary orders inside the system prompt, and the
 * streaming translation landing as the chapter's cached translated
 * content (the original file is never touched).
 */
class RawTranslatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `detects korean chinese and english by script`() {
        assertEquals(
            SourceLanguage.KOREAN,
            LanguageDetect.detect("그는 검을 들고 어둠 속으로 걸어 들어갔다. 마을 사람들은 숨을 죽였다.")
        )
        assertEquals(
            SourceLanguage.CHINESE,
            LanguageDetect.detect("他握紧长剑，走入黑暗之中。村里的人都屏住了呼吸，望着天空。")
        )
        assertEquals(
            SourceLanguage.ENGLISH,
            LanguageDetect.detect("He gripped his sword and walked into the darkness.")
        )
    }

    @Test
    fun `system prompt carries locked glossary terms as orders`() {
        val prompt = RawTranslator.systemPrompt(
            bookTitle = "Star Fall",
            glossary = listOf(
                TerminologyEntry(
                    bookId = 1, sourceTerm = "천마", chosenTranslation = "Heavenly Demon",
                    locked = true
                ),
                TerminologyEntry(
                    bookId = 1, sourceTerm = "문파", chosenTranslation = "sect",
                    locked = false
                )
            ),
            loreNames = listOf("Jin Woo"),
            language = SourceLanguage.KOREAN
        )
        assertTrue(prompt.contains("ALWAYS translate these terms exactly"))
        assertTrue(prompt.contains("천마 → Heavenly Demon"))
        assertTrue(prompt.contains("Preferred translations"))
        assertTrue(prompt.contains("문파 → sect"))
        assertTrue(prompt.contains("Jin Woo"))
    }

    private fun sseBody(vararg deltas: String): String {
        fun esc(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")
        return deltas.joinToString("\n") { delta ->
            """data: {"candidates":[{"content":{"parts":[{"text":"${esc(delta)}"}]}}]}"""
        } + "\n"
    }

    @Test
    fun `translation streams and is cached as translated content`() = runBlocking {
        val storage = BookStorage(tmp.newFolder())
        val transport = FakeTransport { request ->
            if (request.url.contains("streamGenerateContent")) {
                FakeTransport.ok(sseBody("The star fell. ", "Everyone looked up."))
            } else {
                FakeTransport.ok(
                    """{"candidates":[{"content":{"parts":[{"text":"Chapter 1"}]}}]}"""
                )
            }
        }
        val translator = RawTranslator(
            client = GeminiClient(transport, keyProvider = { "key" }),
            storage = storage,
            settingsProvider = { AiSettings() }
        )
        val book = Book(id = 1, title = "Star Fall", addedAt = 0, updatedAt = 0)
        val chapter = Chapter(
            id = 11, bookId = 1, index = 0, title = "1화", updatedAt = 0
        )
        val content = ChapterContent(
            blocks = listOf(
                ChapterBlock.Paragraph.plain(
                    "별이 떨어졌다. 모두가 하늘을 올려다보았다. 마을은 조용해졌다."
                )
            )
        )
        val events = translator.translate(
            book, chapter, content,
            glossary = emptyList(),
            loreNames = emptyList(),
            language = SourceLanguage.KOREAN
        ).toList()
        assertTrue(
            "expected a Done event, got $events",
            events.any { it is RawTranslator.Event.Done }
        )
        assertTrue(events.none { it is RawTranslator.Event.Failed })
        assertTrue(storage.hasTranslatedContent(1, 11))
        val saved = storage.readTranslatedContent(1, 11)
        assertTrue(saved is AppResult.Ok)
        val text = (saved as AppResult.Ok).value.plainText()
        assertTrue(text.contains("The star fell."))
        assertTrue(text.contains("Everyone looked up."))
    }

    @Test
    fun `english chapters are refused plainly`() = runBlocking {
        val storage = BookStorage(tmp.newFolder())
        val transport = FakeTransport { FakeTransport.ok("unused") }
        val translator = RawTranslator(
            client = GeminiClient(transport, keyProvider = { "key" }),
            storage = storage,
            settingsProvider = { AiSettings() }
        )
        val events = translator.translate(
            Book(id = 1, title = "T", addedAt = 0, updatedAt = 0),
            Chapter(id = 11, bookId = 1, index = 0, title = "One", updatedAt = 0),
            ChapterContent(
                blocks = listOf(ChapterBlock.Paragraph.plain("Already English text."))
            ),
            glossary = emptyList(),
            loreNames = emptyList(),
            language = SourceLanguage.ENGLISH
        ).toList()
        val failed = events.filterIsInstance<RawTranslator.Event.Failed>()
        assertEquals(1, failed.size)
        // v1.0.6.2 (F9): "already in English" is a neutral verdict,
        // NOT a failure — it must never render with the
        // "That file couldn't be used" prefix BadFile acquires.
        assertTrue(
            "english refusal is a neutral verdict, got ${failed.single().error}",
            failed.single().error is app.xreader.core.error.AppError.NeutralVerdict
        )
        assertEquals(
            "This chapter already reads as English — nothing to translate.",
            app.xreader.core.error.ErrorText.displayText(failed.single().error)
        )
        assertEquals(0, transport.requests.size)
    }
}
