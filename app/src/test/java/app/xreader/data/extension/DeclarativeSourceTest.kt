package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The declarative engine end-to-end against the fixture site (PLAN §5
 * gate): search, popular, details, reversed chapter list, multi-page
 * chapter content with the ad stripped — all through the whitelist
 * HTTP client and the host facades, no live network.
 */
class DeclarativeSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun source(transport: app.xreader.data.FakeTransport): DeclarativeSource {
        val context = testContext(transport, tmp.newFolder())
        return DeclarativeSource(FixtureSite.config(), context)
    }

    @Test
    fun `popular lists books with resolved links covers and next flag`() = runBlocking {
        val page = source(FixtureSite.transport()).popular(1)
        assertEquals(2, page.books.size)
        val first = page.books.first()
        assertEquals("Sword Saga", first.title)
        assertEquals(FixtureSite.BOOK_URL, first.id)
        assertEquals("Jane Author", first.author)
        assertEquals("${FixtureSite.BASE}/covers/sword.jpg", first.coverUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `search substitutes the query`() = runBlocking {
        val transport = FixtureSite.transport()
        val page = source(transport).search("sword", 1)
        assertEquals(1, page.books.size)
        assertTrue(transport.requests.any { it.url.contains("q=sword") })
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `details fill title author description cover`() = runBlocking {
        val details = source(FixtureSite.transport())
            .details(SourceBook(id = FixtureSite.BOOK_URL, title = "?"))
        assertEquals("Sword Saga", details.title)
        assertEquals("Jane Author", details.author)
        assertEquals("A hero, a sword, a very long saga.", details.description)
        assertEquals("${FixtureSite.BASE}/covers/sword.jpg", details.coverUrl)
    }

    @Test
    fun `chapters come back in reading order despite newest-first listing`() = runBlocking {
        val chapters = source(FixtureSite.transport())
            .chapters(SourceBook(id = FixtureSite.BOOK_URL, title = "Sword Saga"))
        assertEquals(3, chapters.size)
        assertEquals("Chapter 1: The Beginning", chapters[0].name)
        assertEquals(FixtureSite.CHAPTER_1_URL, chapters[0].id)
        assertEquals(1f, chapters[0].number)
        assertEquals(3f, chapters[2].number)
    }

    @Test
    fun `chapter content chains pages and strips removed blocks`() = runBlocking {
        val source = source(FixtureSite.transport())
        val chapter = app.xreader.extensionapi.SourceChapter(
            id = FixtureSite.CHAPTER_1_URL, name = "Chapter 1: The Beginning", number = 1f
        )
        val result = source.chapterContent(chapter)
        assertEquals("Chapter 1: The Beginning", result.title)
        assertFalse("ad must be stripped", result.html.contains("BUY PREMIUM"))
        val content = source.contentOf(chapter.id)
            ?: error("engine must stash the normalised content")
        val text = content.blocks.joinToString("\n") { block ->
            when (block) {
                is app.xreader.domain.content.ChapterBlock.Paragraph -> block.text
                else -> ""
            }
        }
        assertTrue(text.contains("Paragraph 1 of the first chapter"))
        assertTrue("second page must be chained", text.contains("hero rests"))
    }

    @Test
    fun `http errors surface as source exceptions`() = runBlocking {
        val source = source(FixtureSite.transport())
        val missing = app.xreader.extensionapi.SourceChapter(
            id = "${FixtureSite.BASE}/book/nope/chapter-9", name = "x", number = null
        )
        val thrown = runCatching { source.chapterContent(missing) }.exceptionOrNull()
        assertTrue(thrown is app.xreader.extensionapi.SourceException)
        assertTrue(thrown!!.message!!.contains("404"))
    }

    @Test
    fun `selector spec parsing`() {
        val withAttr = SelectorSpec.parse("a.t@href")
        assertEquals("a.t", withAttr.css)
        assertEquals("href", withAttr.attr)
        val self = SelectorSpec.parse("@text")
        assertEquals("", self.css)
        assertEquals("text", self.attr)
        val plain = SelectorSpec.parse("div.content p")
        assertEquals("div.content p", plain.css)
        assertEquals(null, plain.attr)
    }
}
