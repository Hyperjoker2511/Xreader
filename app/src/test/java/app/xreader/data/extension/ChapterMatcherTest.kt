package app.xreader.data.extension

import app.xreader.extensionapi.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-source chapter matching (§9.1): numbers beat titles beat
 * positions, the auto threshold is conservative, and matching is
 * one-to-one. */
class ChapterMatcherTest {

    private fun chapter(name: String, number: Float? = null) =
        SourceChapter(id = name, name = name, number = number)

    @Test
    fun `same number matches at high confidence`() {
        val primary = listOf(chapter("Chapter 1"), chapter("Chapter 2"))
        val alt = listOf(chapter("Ch. 1"), chapter("Ch. 2"))
        val matches = ChapterMatcher.match(primary, alt)
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.confidence >= 0.9f })
    }

    @Test
    fun `same title matches when numbers are absent`() {
        val primary = listOf(chapter("The Long Road Home"))
        val alt = listOf(chapter("the long road home!"))
        val matches = ChapterMatcher.match(primary, alt)
        assertEquals(1, matches.size)
        assertEquals(0.80f, matches.first().confidence)
    }

    @Test
    fun `different numbers never match`() {
        val primary = listOf(chapter("Chapter 5"))
        val alt = listOf(chapter("Chapter 6"))
        assertTrue(ChapterMatcher.match(primary, alt).isEmpty())
    }

    @Test
    fun `positional hint stays below the auto threshold`() {
        val primary = (1..10).map { chapter("Part $it") }
        val alt = (1..10).map { chapter("Segment $it") }
        val confidence = ChapterMatcher.confidence(
            primary[3], alt[3], primary, alt, 3, 3
        )
        assertEquals(0.50f, confidence)
        assertTrue(confidence < ChapterMatcher.AUTO_THRESHOLD)
    }

    @Test
    fun `matching is one to one`() {
        val primary = listOf(chapter("Chapter 1"), chapter("Chapter 1"))
        val alt = listOf(chapter("Chapter 1"))
        val matches = ChapterMatcher.match(primary, alt)
        assertEquals(1, matches.size)
    }

    @Test
    fun `title normalisation strips numbers and punctuation`() {
        assertEquals(
            ChapterMatcher.normaliseTitle("Chapter 12: The Fall!"),
            ChapterMatcher.normaliseTitle("the fall")
        )
    }

    @Test
    fun `locator encodings round trip`() {
        val encoded = SourceBookIds.encode("src-1", "https://x/book/1")
        assertEquals("src-1" to "https://x/book/1", SourceBookIds.decode(encoded))
        val alt = SourceBookIds.encodeAltLocator("src-2", "https://x/ch/9")
        assertEquals("src-2" to "https://x/ch/9", SourceBookIds.decodeAltLocator(alt))
    }
}
