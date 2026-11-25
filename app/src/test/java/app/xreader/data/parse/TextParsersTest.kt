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
        // MR-6: three chapters — Moon+'s detector discards a
        // candidate list shorter than three, so a two-chapter
        // fixture no longer exercises detection. The claim
        // (GBK decodes to readable Chinese and splits on its
        // headings) is unchanged.
        val chapter1 = "第一章 开始\n\n" +
            "这是一个晴朗的早晨，主角背起行囊离开了村庄，走上了通往远方的大路，心里装着对未来的全部想象。\n\n" +
            "他不知道前方等待着什么，只知道这一次离开家乡，就再也不会回头了，村口的老槐树在风里轻轻摇晃。\n\n"
        val chapter2 = "第二章 旅程\n\n" +
            "道路延伸到群山之外，风吹过田野，麦浪一层一层地翻滚着，他走了整整一天，脚底磨出了水泡也没有停下。\n\n" +
            "夜里他睡在路边的草棚里，听着远处的狼叫，心里却出奇地平静，因为他知道自己正在走向想去的地方。\n\n"
        val chapter3 = "第三章 到达\n\n" +
            "傍晚时分，他终于到达了山顶的小镇，镇口的灯笼一盏一盏亮起来，照亮了他满是尘土的脸庞和眼睛。\n"
        val bytes = (chapter1 + chapter2 + chapter3).toByteArray(charset("GBK"))
        val (staged, work) = stage("novel.txt", bytes)
        val parsed = TxtParser().parse(staged, work) {}.unwrap()
        assertEquals(3, parsed.chapters.size)
        assertEquals("第一章 开始", parsed.chapters[0].title)
        assertTrue(chapterTexts(parsed)[0].contains("晴朗的早晨"))
        assertTrue(chapterTexts(parsed)[1].contains("道路延伸"))
        assertTrue(chapterTexts(parsed)[2].contains("到达了山顶"))
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
        // MR-6: bodies are real-chapter scale — Moon+'s detector
        // drops headings whose chapters start under 100 chars
        // apart, so a three-line fixture no longer exercises the
        // claim. The assertion itself is unchanged.
        val text = "Prologue\n\nBefore it all began, the world was quiet, the " +
            "valley slept under a windless sky, and no one in the village " +
            "remembered the old war or the road it came by.\n\n" +
            "Chapter 1\n\nThe story starts here with a loud bang that " +
            "rolled over the hills and woke every dog in the village at " +
            "once, and by noon the whole market knew something had changed.\n\n" +
            "Epilogue\n\nAnd afterwards, peace returned to the valley.\n"
        val (staged, work) = stage("novel.txt", text.toByteArray())
        val parsed = TxtParser().parse(staged, work) {}.unwrap()
        assertEquals(listOf("Prologue", "Chapter 1", "Epilogue"), parsed.chapters.map { it.title })
    }

    // ── MR-6 (Items 1/4/5) ──────────────────────────────────────

    @Test fun regexModeSplitsSectionAndEpisode() {
        val body = "The road went on through the hills and the hero " +
            "walked it without looking back at the village once. "
        val text = buildString {
            // A title line first: Moon+'s rule needs a whitespace
            // before the keyword (its lookbehind), so a heading on
            // the very first byte can never match — in Moon+ too.
            append("My Novel\n\n")
            append("SECTION 1\n\n").append(body.repeat(3)).append("\n\n")
            append("Episode 5\n\n").append(body.repeat(3)).append("\n\n")
            append("Chapter 9\n\n").append(body.repeat(3)).append("\n")
        }
        val (staged, work) = stage("novel.txt", text.toByteArray())
        val parser = TxtParser(
            settingsProvider = {
                app.xreader.data.prefs.TxtPrefs.State(tocRegMode = true)
            }
        )
        val parsed = parser.parse(staged, work) {}.unwrap()
        assertEquals(
            listOf("SECTION 1", "Episode 5", "Chapter 9"),
            parsed.chapters.map { it.title }
        )
    }

    @Test fun regexModeSplitsRussianGlava() {
        val body = "Дорога шла через холмы, и герой шёл по ней, " +
            "не оглядываясь на деревню ни разу. "
        val text = buildString {
            append(" Глава 1\n\n").append(body.repeat(3)).append("\n\n")
            append(" Глава 2\n\n").append(body.repeat(3)).append("\n")
        }
        val (staged, work) = stage("novel.txt", text.toByteArray(Charsets.UTF_8))
        val parser = TxtParser(
            settingsProvider = {
                app.xreader.data.prefs.TxtPrefs.State(tocRegMode = true)
            }
        )
        val parsed = parser.parse(staged, work) {}.unwrap()
        assertEquals(listOf("Глава 1", "Глава 2"), parsed.chapters.map { it.title })
    }

    @Test fun badUserRegexFallsBackToBuiltIn() {
        // MR-6 Item 1's fail-closed rule (Xreader's improvement
        // over Moon+'s empty list): a nonsense override must not
        // break the import — the built-in EN rule applies.
        val body = "The road went on through the hills and the hero " +
            "walked it without looking back at the village once. "
        val text = buildString {
            append("My Novel\n\n")
            append("Chapter 1\n\n").append(body.repeat(3)).append("\n\n")
            append("Chapter 2\n\n").append(body.repeat(3)).append("\n")
        }
        val (staged, work) = stage("novel.txt", text.toByteArray())
        val parser = TxtParser(
            settingsProvider = {
                app.xreader.data.prefs.TxtPrefs.State(
                    tocRegMode = true,
                    regexOverrides = mapOf(
                        app.xreader.data.prefs.TxtLanguage.EN to "(["
                    )
                )
            }
        )
        val parsed = parser.parse(staged, work) {}.unwrap()
        assertEquals(
            listOf("Chapter 1", "Chapter 2"),
            parsed.chapters.map { it.title }
        )
    }

    @Test fun big5ChineseNovelDecodesAndSplits() {
        // Three chapters, for the same MR-6 detector reason as
        // the GBK fixture above.
        val chapter1 = "第一章 開始\n\n" +
            "這是一個晴朗的早晨，主角背起行囊離開了村莊，走上了通往遠方的大路，心裡裝著對未來的全部想像。\n\n" +
            "他不知道前方等待著什麼，只知道這一次離開家鄉，就再也不會回頭了，村口的老槐樹在風裡輕輕搖晃。\n\n"
        val chapter2 = "第二章 旅程\n\n" +
            "道路延伸到群山之外，風吹過田野，麥浪一層一層地翻滾著，他走了整整一天，腳底磨出了水泡也沒有停下。\n\n" +
            "夜裡他睡在路邊的草棚裡，聽著遠處的狼叫，心裡卻出奇地平靜，因為他知道自己正在走向想去的地方。\n\n"
        val chapter3 = "第三章 到達\n\n" +
            "傍晚時分，他終於到達了山頂的小鎮，鎮口的燈籠一盞一盞亮起來，照亮了他滿是塵土的臉龐和眼睛。\n"
        val bytes = (chapter1 + chapter2 + chapter3).toByteArray(charset("Big5"))
        val (staged, work) = stage("novel.txt", bytes)
        val parsed = TxtParser().parse(staged, work) {}.unwrap()
        assertEquals(3, parsed.chapters.size)
        assertEquals("第一章 開始", parsed.chapters[0].title)
        assertTrue(chapterTexts(parsed)[0].contains("晴朗的早晨"))
    }

    @Test fun encodingOverrideForcesTheCharset() {
        // Item 5: a GBK file whose bytes would also decode as
        // Big5 garbage must come out as GBK when the per-file
        // override (keyed by the book's filePath) says GBK.
        val text = "第一章 开始\n\n这是一个晴朗的早晨，主角离开了村庄。\n\n" +
            "第二章 旅程\n\n道路延伸到群山之外。\n\n" +
            "第三章 到达\n\n傍晚时分，他终于到达了山顶的小镇。\n"
        val bytes = text.toByteArray(charset("GBK"))
        val (staged, work) = stage("novel.txt", bytes)
        val parser = TxtParser(
            pathKey = "books/7/book.txt",
            encodingOverrideFor = { key ->
                if (key == "books/7/book.txt") "GBK" else null
            }
        )
        val parsed = parser.parse(staged, work) {}.unwrap()
        assertTrue(parsed.chapters.isNotEmpty())
        assertTrue(chapterTexts(parsed)[0].contains("晴朗的早晨"))
        assertEquals("GBK", parsed.detectedEncoding)
    }

    @Test fun txtImportPreservesBlankLinesForTheRenderTrims() {
        // Item 9 (code-126 re-check fix): import stores the
        // as-printed structure — blank paragraphs survive — so
        // the render-time trim switches have something to act
        // on; with both trims ON the blanks are gone again.
        val text = buildString {
            append("Chapter 1\n\n")
            append("First paragraph of the chapter, telling how the morning began for him.\n\n\n\n")
            append("Second paragraph after the gap, telling what happened next that day.\n\n")
            append("Chapter 2\n\n")
            append("Another opening paragraph here, telling how the journey continued on.\n\n\n")
            append("Closing paragraph of this chapter, telling how the evening fell soft.\n\n")
            append("Chapter 3\n\n")
            append("Final chapter paragraph.\n")
        }
        val (staged, work) = stage("novel.txt", text.toByteArray())
        val parsed = TxtParser().parse(staged, work) {}.unwrap()
        assertEquals(3, parsed.chapters.size)
        val blocks = firstChapterBlocks(parsed)
        assertTrue(
            "stored chapter should keep a blank paragraph",
            blocks.any {
                it is ChapterBlock.Paragraph && it.text.isBlank()
            }
        )
        val trimmed = TxtDisplayTransform.applyTrims(
            app.xreader.domain.content.ChapterContent(blocks),
            trimBlankLines = true,
            trimPageTopBlanks = true
        )
        assertTrue(
            trimmed.blocks.none {
                it is ChapterBlock.Paragraph && it.text.isBlank()
            }
        )
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
