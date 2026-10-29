package app.xreader.data.ai

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.LinkProber
import app.xreader.data.extension.MoonSite
import app.xreader.data.extension.testSessionStore
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * URL Selector AI (PLAN §7 gate): Gemini proposes a DeclarativeConfig
 * for a site the heuristic prober doesn't know — and the proposal
 * only becomes a Ready draft after passing the SAME mandatory
 * test-drive as every other config. Bad proposals fail plainly.
 */
class SelectorAiTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val selectorJson = """
        {
          "name": "MoonRead",
          "details": {
            "title": "h1",
            "author": "meta[name=author]@content",
            "cover": "meta[property=og:image]@content",
            "chapters": {
              "list": "#chapter-list li a",
              "name": "@text",
              "link": "@href",
              "reverse": true
            }
          },
          "chapter": { "content": "#chapter-content", "nextPage": "a.next-btn" }
        }
    """.trimIndent()

    private fun geminiBody(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}"""
    }

    private fun selectorAi(
        geminiText: String,
        settings: AiSettings = AiSettings()
    ): SelectorAi {
        val moon = MoonSite.transport()
        val transport = FakeTransport { request ->
            if (request.url.contains("generativelanguage.googleapis.com")) {
                FakeTransport.ok(geminiBody(geminiText))
            } else {
                moon.execute(request)
            }
        }
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        val prober = LinkProber(factory, testSessionStore())
        val client = GeminiClient(transport, keyProvider = { "key" })
        return SelectorAi(client, prober, factory) { settings }
    }

    @Test
    fun `ai config passes the same test drive and becomes a draft`() = runBlocking {
        val outcome = selectorAi(selectorJson).buildFromLink(MoonSite.BOOK_URL)
        assertTrue("expected Ready, got $outcome", outcome is SelectorAi.Outcome.Ready)
        val draft = (outcome as SelectorAi.Outcome.Ready).draft
        assertEquals("Star Fall", draft.testDrive.bookTitle)
        assertEquals(3, draft.testDrive.chapterCount)
        assertTrue(draft.testDrive.chapterTextPreview.contains("The star fell"))
        assertTrue(
            "AI configs are marked as such",
            draft.config.id.endsWith("-ai")
        )
    }

    @Test
    fun `nonsense from the model fails plainly`() = runBlocking {
        val outcome = selectorAi("I looked at the page but cannot decide.")
            .buildFromLink(MoonSite.BOOK_URL)
        assertTrue("expected Failed, got $outcome", outcome is SelectorAi.Outcome.Failed)
    }

    @Test
    fun `selectors that do not match the page fail the test drive`() = runBlocking {
        val wrong = selectorJson.replace("#chapter-content", "#no-such-block")
        val outcome = selectorAi(wrong).buildFromLink(MoonSite.BOOK_URL)
        assertTrue(
            "an AI config that reads nothing must not be offered for saving, got $outcome",
            outcome is SelectorAi.Outcome.Failed
        )
    }

    @Test
    fun `feature off fails before any model call`() = runBlocking {
        val settings = AiSettings(
            features = AiFeature.entries.associateWith { it != AiFeature.SELECTOR_AI }
        )
        val outcome = selectorAi(selectorJson, settings).buildFromLink(MoonSite.BOOK_URL)
        assertTrue(outcome is SelectorAi.Outcome.Failed)
        assertTrue(
            (outcome as SelectorAi.Outcome.Failed).message.contains("turned off")
        )
    }
}
