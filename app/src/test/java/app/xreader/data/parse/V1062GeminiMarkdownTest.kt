package app.xreader.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.6.2 (Gemini fix round, G5): the Markdown parser streams
 * the file line by line instead of readText-ing the whole book
 * into one String. The existing MarkdownParserTest pins the
 * semantics; this pins that a large book parses completely and
 * correctly through the streaming path.
 */
class V1062GeminiMarkdownTest : ParserTestBase() {

    @Test fun largeMarkdownBookStreamsIntoAllItsChapters() {
        val chapters = 300
        val md = buildString {
            append("# The Long Book\n\n")
            for (n in 1..chapters) {
                append("# Chapter $n\n\n")
                repeat(20) { line ->
                    append(
                        "Chapter $n paragraph $line with enough prose " +
                            "to make the file honestly large.\n\n"
                    )
                }
            }
        }
        val (staged, work) = stage("long.md", md.toByteArray())
        val parsed = MarkdownParser().parse(staged, work) {}.unwrap()
        assertEquals("The Long Book", parsed.title)
        assertEquals(chapters, parsed.chapters.size)
        assertEquals("Chapter 1", parsed.chapters.first().title)
        assertEquals("Chapter $chapters", parsed.chapters.last().title)
        val texts = chapterTexts(parsed)
        assertTrue(texts.first().contains("Chapter 1 paragraph 0"))
        assertTrue(texts.last().contains("Chapter $chapters paragraph 19"))
    }
}
