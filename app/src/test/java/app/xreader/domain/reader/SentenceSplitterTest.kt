package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    private fun texts(text: String): List<String> =
        SentenceSplitter.split(text).map { it.textIn(text) }

    @Test
    fun basicBoundaries() {
        assertEquals(
            listOf("One sword.", "Two swords!", "Three swords?"),
            texts("One sword. Two swords! Three swords?")
        )
    }

    @Test
    fun rangesAreExactAndOrdered() {
        val text = "  First one.   Second one.  "
        val sentences = SentenceSplitter.split(text)
        assertEquals(2, sentences.size)
        assertEquals("First one.", sentences[0].textIn(text))
        assertEquals("Second one.", sentences[1].textIn(text))
        assertTrue(sentences[0].start < sentences[1].start)
    }

    @Test
    fun decimalsAndAbbreviationsDoNotSplit() {
        assertEquals(
            listOf("Dr. Su paid 3.14 taels.", "Then he left."),
            texts("Dr. Su paid 3.14 taels. Then he left.")
        )
    }

    @Test
    fun quotesAreAbsorbed() {
        assertEquals(
            listOf("\"After three hundred years,\" he murmured.", "The wind answered."),
            texts("\"After three hundred years,\" he murmured. The wind answered.")
        )
    }

    @Test
    fun cjkTerminatorsNeedNoSpace() {
        assertEquals(
            listOf("他站起身。", "风停了！", "真的吗？"),
            texts("他站起身。风停了！真的吗？")
        )
    }

    @Test
    fun paragraphBreakEndsSentenceWithoutPunctuation() {
        assertEquals(
            listOf("Chapter 812", "The mist clung to the peaks"),
            texts("Chapter 812\n\nThe mist clung to the peaks")
        )
    }

    @Test
    fun ellipsisIsOneSentenceEnd() {
        assertEquals(
            listOf("He waited...", "Nothing came."),
            texts("He waited... Nothing came.")
        )
    }

    @Test
    fun blankTextHasNoSentences() {
        assertTrue(SentenceSplitter.split("").isEmpty())
        assertTrue(SentenceSplitter.split("   \n  ").isEmpty())
    }
}
