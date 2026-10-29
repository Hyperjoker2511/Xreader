package app.xreader.data.parse

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.domain.content.ChapterBlock
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Shared fixture helpers: staged parse outputs are real JSON files. */
abstract class ParserTestBase {
    @get:Rule val temp = TemporaryFolder()

    protected val json = Json { ignoreUnknownKeys = true }

    protected fun stage(name: String, bytes: ByteArray): Pair<File, File> {
        val dir = temp.newFolder()
        val staged = File(dir, name).apply { writeBytes(bytes) }
        val work = File(dir, "work").apply { mkdirs() }
        return staged to work
    }

    protected fun <T> AppResult<T>.unwrap(): T = when (this) {
        is AppResult.Ok -> value
        is AppResult.Err -> throw AssertionError("expected Ok, got error: $error")
    }

    protected fun AppResult<*>.unwrapErr(): AppError = when (this) {
        is AppResult.Ok -> throw AssertionError("expected Err, got Ok")
        is AppResult.Err -> error
    }

    protected fun chapterTexts(parsed: ParsedBook): List<String> = parsed.chapters.map { meta ->
        val file = File(parsed.chaptersDir, meta.contentFileName)
        json.decodeChapter(file.readText()).plainText()
    }

    protected fun firstChapterBlocks(parsed: ParsedBook): List<ChapterBlock> {
        val file = File(parsed.chaptersDir, parsed.chapters.first().contentFileName)
        return json.decodeChapter(file.readText()).blocks
    }
}

class TxtParserTest : ParserTestBase() {

    @Test fun englishChaptersSplitOnHeadings() {
        val text = buildString {
            append("My Novel\n\n")
            append("Chapter 1: The Beginning\n\n")
            append("It was a bright morning in the village.\n\n")
            append("The hero packed his bag and left.\n\n")
            append("Chapter 2: The Journey\n\n")
            append("Roads stretched far beyond the hills.\n")
        }
        val (staged, work) = stage("novel.txt", text.toByteArray())
        val parsed = TxtParser().parse(staged, work) {}.unwrap()
        assertEquals(2, parsed.chapters.size)
        assertEquals("Chapter 1: The Beginning", parsed.chapters[0].title)
        assertEquals("Chapter 2: The Journey", parsed.chapters[1].title)
        assertTrue(chapterTexts(parsed)[0].contains("bright morning"))
        assertTrue(chapterTexts(parsed)[1].contains("Roads stretched"))
    }

    @Test fun gbkChineseNovelDecodesAndSplits() {
        val chapter1 = "第一章 开始\n\n" +
            "这是一个晴朗的早晨，主角背起行囊离开了村庄。\n\n" +
            "他不知道前方等待着什么。\n\n"
        val chapter2 = "第二章 旅程\n\n" +
            "道路延伸到群山之外，风吹过田野。\n"
        val bytes = (chapter1 + chapter2).toByteArray(charset("GBK"))
        val (staged, work) = stage("novel.txt", bytes)
        val parsed = TxtParser().parse(staged, work) {}.unwrap()
        assertEquals(2, parsed.chapters.size)
        assertEquals("第一章 开始", parsed.chapters[0].title)
        assertTrue(chapterTexts(parsed)[0].contains("晴朗的早晨"))
        assertTrue(chapterTexts(parsed)[1].contains("道路延伸"))
    }

    @Test fun headinglessTextSoftSplits() {
        val paragraph = "This is a long paragraph of story text that keeps going. ".repeat(20)
        val text = (1..80).joinToString("\n\n") { paragraph }
        val (staged, work) = stage("long.txt", text.toByteArray())
        val parsed = TxtParser().parse(staged, work) {}.unwrap()
        assertTrue("expected soft splits, got ${parsed.chapters.size}", parsed.chapters.size >= 2)
        val totalWords = parsed.chapters.sumOf { it.wordCount }
        assertTrue(totalWords > 10_000)
    }

    @Test fun prologueAndEpilogueAreChapters() {
        val text = "Prologue\n\nBefore it all began, the world was quiet.\n\n" +
            "Chapter 1\n\nThe story starts here with a loud bang.\n\n" +
            "Epilogue\n\nAnd afterwards, peace returned to the valley.\n"
        val (staged, work) = stage("novel.txt", text.toByteArray())
        val parsed = TxtParser().parse(staged, work) {}.unwrap()
        assertEquals(listOf("Prologue", "Chapter 1", "Epilogue"), parsed.chapters.map { it.title })
    }
}

class MarkdownParserTest : ParserTestBase() {

    @Test fun splitsAtH1WithInlineEmphasis() {
        val md = """
            # My Book

            # Chapter One

            It was **very** bright and *calm* that morning.

            ## A Section

            More text follows here.

            # Chapter Two

            The journey continued onwards.
        """.trimIndent()
        val (staged, work) = stage("book.md", md.toByteArray())
        val parsed = MarkdownParser().parse(staged, work) {}.unwrap()
        assertEquals("My Book", parsed.title)
        assertEquals(2, parsed.chapters.size)
        val blocks = firstChapterBlocks(parsed)
        val paragraph = blocks.filterIsInstance<ChapterBlock.Paragraph>()
            .first { it.text.contains("bright") }
        assertTrue(paragraph.spans.any { it.bold && it.text.contains("very") })
        assertTrue(paragraph.spans.any { it.italic && it.text.contains("calm") })
        assertTrue(blocks.any { it is ChapterBlock.Heading && it.text == "A Section" })
    }
}

class HtmlParserTest : ParserTestBase() {

    @Test fun splitsAtH1AndStripsScripts() {
        val html = """
            <html><head><title>Web Novel</title><script>alert('x')</script></head>
            <body>
            <h1>Chapter 1</h1>
            <p>The <b>first</b> paragraph of the story.</p>
            <script>evil()</script>
            <h1>Chapter 2</h1>
            <p>The second chapter begins now.</p>
            </body></html>
        """.trimIndent()
        val (staged, work) = stage("book.html", html.toByteArray())
        val parsed = HtmlParser().parse(staged, work) {}.unwrap()
        assertEquals("Web Novel", parsed.title)
        assertEquals(2, parsed.chapters.size)
        val allText = chapterTexts(parsed).joinToString(" ")
        assertTrue(allText.contains("first"))
        assertTrue(!allText.contains("evil"))
        assertTrue(!allText.contains("alert"))
    }
}
