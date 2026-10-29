package app.xreader.data.ai.hub

import app.xreader.data.prefs.EnginePick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picking rule (v1.0.3): Automatic routes across configured
 * providers only; a Specific pick yields exactly that provider —
 * even unconfigured (its own plain error beats a silent detour) —
 * and a stale pick yields an EMPTY order the hub reports, never a
 * substitute.
 */
class EngineRoutingTest {

    private val gemini = EngineRouting.ProviderState(
        AiProviders.GEMINI_ID, "Google Gemini", configured = true, model = "AUTO"
    )
    private val openAi = EngineRouting.ProviderState(
        AiProviders.OPENAI_ID, "OpenAI", configured = true, model = "gpt-5-mini"
    )
    private val claudeNoKey = EngineRouting.ProviderState(
        AiProviders.CLAUDE_ID, "Claude", configured = false, model = null
    )
    private val all = listOf(gemini, openAi, claudeNoKey)

    @Test
    fun `automatic orders configured providers in registry order`() {
        val order = EngineRouting.order(EnginePick.Automatic, all)
        assertEquals(listOf(gemini, openAi), order)
    }

    @Test
    fun `specific pick is exactly the picked provider`() {
        val order = EngineRouting.order(
            EnginePick.Specific(AiProviders.OPENAI_ID, "gpt-5-mini"), all
        )
        assertEquals(listOf(openAi), order)
    }

    @Test
    fun `specific pick of an unconfigured provider still yields only it`() {
        val order = EngineRouting.order(
            EnginePick.Specific(AiProviders.CLAUDE_ID, "claude-haiku-4-5"), all
        )
        assertEquals(listOf(claudeNoKey), order)
    }

    @Test
    fun `specific pick of a removed provider yields an empty order`() {
        val order = EngineRouting.order(
            EnginePick.Specific("custom-gone", "any"), all
        )
        assertTrue(order.isEmpty())
    }

    @Test
    fun `chip label names the mode or the pick`() {
        assertEquals(
            "AI · Automatic",
            EngineRouting.chipLabel(EnginePick.Automatic, all)
        )
        assertEquals(
            "AI · OpenAI · gpt-5-mini",
            EngineRouting.chipLabel(
                EnginePick.Specific(AiProviders.OPENAI_ID, "gpt-5-mini"), all
            )
        )
        assertTrue(
            EngineRouting.chipLabel(
                EnginePick.Specific("custom-gone", "any"), all
            ).contains("removed")
        )
    }

    @Test
    fun `free badge follows the provider's own listing signals`() {
        assertTrue(FreeBadge.isListedFree("free/deepseek-v4.1-flash", null))
        assertTrue(FreeBadge.isListedFree("openai/gpt-oss-120b:free", null))
        assertTrue(
            !FreeBadge.isListedFree("gpt-5-mini", null)
        )
    }

    @Test
    fun `candidate bases cover the standard gateway shapes`() {
        val bases = ProviderUrls.candidateBases("https://openrouter.ai")
        assertTrue("https://openrouter.ai/v1" in bases)
        assertTrue("https://openrouter.ai/api/v1" in bases)
        assertEquals(
            "https://api.apinex.bond/v1",
            ProviderUrls.candidateBases("https://api.apinex.bond/v1").first()
        )
        assertEquals(
            "https://example.com/v1/models",
            ProviderUrls.anthropicModelsUrl("https://example.com")
        )
        assertEquals(
            "https://example.com/v1/models",
            ProviderUrls.anthropicModelsUrl("https://example.com/v1")
        )
        assertEquals(
            null, ProviderUrls.normaliseSiteInput("not a url at all ::")
        )
        assertEquals(
            "https://openrouter.ai",
            ProviderUrls.normaliseSiteInput("openrouter.ai")
        )
    }
}
