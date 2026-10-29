package app.xreader.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI tag suggestion parsing (v1.0.3): tolerant shapes, registry
 * canonicalisation (an alias lands on the canonical tag), unknown
 * labels become their own tags, already-decided slugs are never
 * re-suggested, and the list is capped.
 */
class TagSuggestionParseTest {

    @Test
    fun `json array of strings parses and canonicalises aliases`() {
        val suggestions = TagSuggester.parseSuggestions(
            """["Cultivation", "Male Lead", "Revenge"]""",
            existingSlugs = emptySet()
        )
        assertEquals(
            listOf("cultivation", "male-protagonist", "revenge"),
            suggestions.map { it.slug }
        )
        assertTrue(suggestions.all { it.known })
        assertEquals("Male MC", suggestions[1].displayName)
    }

    @Test
    fun `prose-wrapped and object-wrapped replies still parse`() {
        val wrapped = TagSuggester.parseSuggestions(
            "Here are the tags:\n```json\n{\"tags\": [\"Weak to Strong\", " +
                "\"Face-Slapping\"]}\n```",
            existingSlugs = emptySet()
        )
        assertEquals(
            listOf("weak-to-strong", "face-slapping"),
            wrapped.map { it.slug }
        )
    }

    @Test
    fun `unknown labels become their own tags, marked unknown`() {
        val suggestions = TagSuggester.parseSuggestions(
            """["Dragon Bloodline"]""",
            existingSlugs = emptySet()
        )
        assertEquals(1, suggestions.size)
        assertEquals("dragon-bloodline", suggestions[0].slug)
        assertFalse(suggestions[0].known)
    }

    @Test
    fun `existing and duplicate slugs are dropped`() {
        val suggestions = TagSuggester.parseSuggestions(
            """["Revenge", "revenge", "REVENGE", "Cultivation"]""",
            existingSlugs = setOf("cultivation")
        )
        assertEquals(listOf("revenge"), suggestions.map { it.slug })
    }

    @Test
    fun `line fallback works when there is no json`() {
        val suggestions = TagSuggester.parseSuggestions(
            "- Comedy\n- Smart MC\n",
            existingSlugs = emptySet()
        )
        assertEquals(
            listOf("comedy", "smart-mc"),
            suggestions.map { it.slug }
        )
    }

    @Test
    fun `the list is capped`() {
        val many = (1..20).joinToString(",", "[", "]") { "\"Tag Number $it\"" }
        val suggestions = TagSuggester.parseSuggestions(many, emptySet())
        assertEquals(TagSuggester.MAX_SUGGESTIONS, suggestions.size)
    }
}
