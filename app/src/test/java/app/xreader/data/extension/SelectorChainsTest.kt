package app.xreader.data.extension

import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Selector fallback chains (v1.0.4, D14): the split/join rules, the
 * engine's first-non-empty-wins resolution, and the storage proof —
 * a config written the old way (single selectors, no new fields:
 * chains add NO storage fields) decodes byte-for-byte through the
 * existing codec.
 */
class SelectorChainsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── split / join ────────────────────────────────────────────────

    @Test
    fun `a single selector is a one element chain`() {
        assertEquals(
            listOf("div.content"),
            SelectorChains.split("div.content")
        )
        assertEquals(
            listOf("meta[name=description]@content"),
            SelectorChains.split("meta[name=description]@content")
        )
        // Even a blank field stays one candidate — the engine's
        // blank-spec behaviour is unchanged.
        assertEquals(listOf(""), SelectorChains.split(""))
    }

    @Test
    fun `chains split on the separator and trim`() {
        assertEquals(
            listOf(".old a", ".new a@href"),
            SelectorChains.split("  .old a || .new a@href ")
        )
        assertEquals(
            ".old a || .new a",
            SelectorChains.join(listOf(".old a", ".new a"))
        )
    }

    // ── the engine ──────────────────────────────────────────────────

    private fun source(config: DeclarativeConfig): DeclarativeSource =
        DeclarativeSource(
            config, testContext(FixtureSite.transport(), tmp.newFolder())
        )

    @Test
    fun `details fall back when the first candidate misses`() = runBlocking {
        val base = FixtureSite.config()
        val config = base.copy(
            details = base.details.copy(
                description = ".missing-desc || meta[name=description]@content"
            )
        )
        val details = source(config)
            .details(SourceBook(id = FixtureSite.BOOK_URL, title = "?"))
        assertEquals("A hero, a sword, a very long saga.", details.description)
    }

    @Test
    fun `first non empty candidate wins`() = runBlocking {
        val base = FixtureSite.config()
        val config = base.copy(
            details = base.details.copy(
                description = "h1.book-title || meta[name=description]@content"
            )
        )
        val details = source(config)
            .details(SourceBook(id = FixtureSite.BOOK_URL, title = "?"))
        // The h1 candidate matches and has text — it must win over
        // the meta candidate behind it.
        assertEquals("Sword Saga", details.description)
    }

    @Test
    fun `chapter list falls back to the second candidate`() = runBlocking {
        val base = FixtureSite.config()
        val config = base.copy(
            details = base.details.copy(
                chapters = base.details.chapters.copy(
                    list = "ol.nope > li || ul.chapters > li"
                )
            )
        )
        val chapters = source(config)
            .chapters(SourceBook(id = FixtureSite.BOOK_URL, title = "Sword Saga"))
        assertEquals(3, chapters.size)
        assertEquals("Chapter 1: The Beginning", chapters.first().name)
    }

    @Test
    fun `listing results fall back to the second candidate`() = runBlocking {
        val base = FixtureSite.config()
        val config = base.copy(
            popular = base.popular!!.copy(results = "div.nope || div.result")
        )
        val page = source(config).popular(1)
        assertEquals(2, page.books.size)
    }

    @Test
    fun `chapter content falls back to the second candidate`() = runBlocking {
        val base = FixtureSite.config()
        val config = base.copy(
            chapter = base.chapter.copy(content = "div.nope || div.content")
        )
        val src = source(config)
        val chapter = SourceChapter(
            id = FixtureSite.CHAPTER_1_URL,
            name = "Chapter 1: The Beginning",
            number = 1f
        )
        src.chapterContent(chapter)
        val content = src.contentOf(chapter.id)
        assertNotNullContent(content)
    }

    private fun assertNotNullContent(
        content: app.xreader.domain.content.ChapterContent?
    ) {
        assertTrue(content != null)
        val text = content!!.blocks.joinToString("\n") { block ->
            when (block) {
                is app.xreader.domain.content.ChapterBlock.Paragraph -> block.text
                else -> ""
            }
        }
        assertTrue(text.contains("Paragraph 1 of the first chapter"))
    }

    // ── storage: backward compatibility ─────────────────────────────

    @Test
    fun `old format stored config round trips through the codec`() {
        val config = FixtureSite.config()
        val decoded = DeclarativeFingerprints.decode(
            DeclarativeFingerprints.encode(listOf(config))
        )
        assertEquals(listOf(config), decoded)
    }

    @Test
    fun `minimal stored json from an older writer still parses`() {
        // Only the fields every version has always required —
        // defaults fill the rest, exactly as a v1.0.3 phone's
        // stored rules must keep parsing.
        val raw = """
            [{"id":"x","name":"X","baseUrl":"https://x.example",
              "details":{"chapters":{"list":"ul li","name":"a","link":"a@href"}},
              "chapter":{"content":"div.content"}}]
        """.trimIndent()
        val decoded = DeclarativeFingerprints.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("ul li", decoded.first().details.chapters.list)
        assertEquals("en", decoded.first().lang)
        assertEquals(null, decoded.first().search)
    }

    @Test
    fun `a chain in a stored field is just a string to the codec`() {
        val base = FixtureSite.config()
        val chained = base.copy(
            details = base.details.copy(
                description = ".a || meta[name=description]@content"
            )
        )
        val decoded = DeclarativeFingerprints.decode(
            DeclarativeFingerprints.encode(listOf(chained))
        )
        assertEquals(
            ".a || meta[name=description]@content",
            decoded.first().details.description
        )
    }
}
