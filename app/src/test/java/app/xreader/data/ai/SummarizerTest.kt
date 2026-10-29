package app.xreader.data.ai

import app.xreader.core.error.AppResult
import app.xreader.data.FakeAiSummaryDao
import app.xreader.data.FakeTransport
import app.xreader.data.db.Chapter
import app.xreader.data.prefs.AiSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Summarizer caching (PLAN §7 gate): a chapter summary is paid for
 * once — the content hash decides reuse — and the story-so-far recap
 * is built ONLY from cached chapter summaries, cached in turn
 * against the exact set it used.
 */
class SummarizerTest {

    private fun textBody(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}"""
    }

    private fun chapter(id: Long, index: Int) = Chapter(
        id = id, bookId = 1, index = index, title = "Chapter ${index + 1}", updatedAt = 0
    )

    private fun summarizer(transport: FakeTransport, dao: FakeAiSummaryDao) =
        Summarizer(
            client = GeminiClient(transport, keyProvider = { "key" }),
            summaryDao = dao,
            settingsProvider = { AiSettings() }
        )

    @Test
    fun `chapter summary is cached by content hash`() = runBlocking {
        val transport = FakeTransport {
            FakeTransport.ok(textBody("""["A falls","B rises","C schemes"]"""))
        }
        val dao = FakeAiSummaryDao()
        val summarizer = summarizer(transport, dao)
        val text = "A very long chapter in which things happen. ".repeat(30)

        val first = summarizer.chapterSummary(1, "Star Fall", chapter(11, 0), text)
        assertTrue(first is AppResult.Ok)
        first as AppResult.Ok
        assertFalse(first.value.fromCache)
        assertEquals(listOf("A falls", "B rises", "C schemes"), first.value.bullets)
        assertEquals(1, transport.requests.size)

        val second = summarizer.chapterSummary(1, "Star Fall", chapter(11, 0), text)
        assertTrue(second is AppResult.Ok)
        second as AppResult.Ok
        assertTrue("second call must come from the cache", second.value.fromCache)
        assertEquals("no second Gemini call", 1, transport.requests.size)

        val changed = summarizer.chapterSummary(
            1, "Star Fall", chapter(11, 0), text + " A new paragraph changes the hash."
        )
        assertTrue(changed is AppResult.Ok)
        assertFalse((changed as AppResult.Ok).value.fromCache)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `book so far needs chapter summaries and caches against them`() = runBlocking {
        var calls = 0
        val transport = FakeTransport {
            calls++
            FakeTransport.ok(
                textBody("""["Arc point one","Arc point two","Arc point three"]""")
            )
        }
        val dao = FakeAiSummaryDao()
        val summarizer = summarizer(transport, dao)
        val chapters = listOf(chapter(11, 0), chapter(12, 1))
        val text = "Chapter text long enough to summarise. ".repeat(30)

        val early = summarizer.bookSoFar(1, "Star Fall", chapters, chapters[1])
        assertTrue(
            "no summaries yet → plain failure, got $early",
            early is AppResult.Err
        )
        assertEquals(0, calls)

        summarizer.chapterSummary(1, "Star Fall", chapters[0], text)
        summarizer.chapterSummary(1, "Star Fall", chapters[1], text)
        val callsAfterChapters = calls

        val arc = summarizer.bookSoFar(1, "Star Fall", chapters, chapters[1])
        assertTrue(arc is AppResult.Ok)
        assertEquals(callsAfterChapters + 1, calls)

        val arcAgain = summarizer.bookSoFar(1, "Star Fall", chapters, chapters[1])
        assertTrue(arcAgain is AppResult.Ok)
        assertTrue((arcAgain as AppResult.Ok).value.fromCache)
        assertEquals("recap served from its own cache", callsAfterChapters + 1, calls)
    }
}
