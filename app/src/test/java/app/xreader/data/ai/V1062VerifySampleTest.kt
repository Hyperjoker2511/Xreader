package app.xreader.data.ai

import app.xreader.data.FakeTransport
import app.xreader.data.extension.ExtensionStore
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.LinkProber
import app.xreader.data.extension.testSessionStore
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0.6.2 (F5): [SelectorAi.verifySample] — the borderline
 * list verdict. YES/NO answers map to true/false; everything
 * that means the check never really ran (no key, feature off,
 * an unclear answer) maps to null, so an unaired check can
 * never block a source.
 */
class V1062VerifySampleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun geminiBody(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"candidates":[{"content":{"parts":[{"text":"$escaped"}]}}]}"""
    }

    private fun selectorAi(
        geminiText: String,
        key: String? = "key",
        settings: AiSettings = AiSettings()
    ): SelectorAi {
        val transport = FakeTransport { FakeTransport.ok(geminiBody(geminiText)) }
        val factory = HostContextFactory(
            ExtensionStore(tmp.newFolder()), testSessionStore(), transport
        )
        val prober = LinkProber(factory, testSessionStore())
        val client = GeminiClient(transport, keyProvider = { key })
        return SelectorAi(client, prober, factory) { settings }
    }

    private val titles = listOf("Quiet Star", "Loud Moon", "Dim Sun")
    private val excerpt = "The quiet star watched over the harbour."

    @Test
    fun `a yes answer verifies the sample`() = runBlocking {
        assertEquals(
            true,
            selectorAi("YES").verifySample(titles, excerpt)
        )
    }

    @Test
    fun `a no answer rejects the sample`() = runBlocking {
        assertEquals(
            false,
            selectorAi("NO — these are posts, not books.")
                .verifySample(titles, excerpt)
        )
    }

    @Test
    fun `an unclear answer is no verdict`() = runBlocking {
        assertNull(
            selectorAi("I cannot decide from this.").verifySample(titles, excerpt)
        )
    }

    @Test
    fun `no key means no verdict`() = runBlocking {
        assertNull(
            selectorAi("YES", key = null).verifySample(titles, excerpt)
        )
    }

    @Test
    fun `feature off means no verdict`() = runBlocking {
        val settings = AiSettings(
            features = AiFeature.entries.associateWith {
                it != AiFeature.SELECTOR_AI
            }
        )
        assertNull(
            selectorAi("YES", settings = settings).verifySample(titles, excerpt)
        )
    }
}
