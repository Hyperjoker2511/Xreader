package app.xreader.data.extension

import app.xreader.data.FakeTransport
import app.xreader.data.files.BookStorage
import app.xreader.domain.content.ChapterContent
import app.xreader.domain.content.ChapterPage
import app.xreader.domain.content.ChapterPages
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Manga programme (M1): the image-chapter model end-to-end
 * against a fixture manga site — a MANGA declarative config
 * whose catalogue/book/chapter legs behave exactly like a
 * text source's, whose chapter content is the ordered page
 * list (never prose), and whose record persists through the
 * ordinary chapter storage with room for downloaded files.
 * Plus the text regression: every pre-manga config shape
 * decodes with the new fields at their defaults.
 */
class MangaSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun source(
        transport: FakeTransport,
        config: DeclarativeConfig = MangaFixture.config()
    ): DeclarativeSource {
        val context = testContext(
            transport, tmp.newFolder(),
            permissions = ExtensionPermissions(
                domains = listOf(MangaFixture.HOST, "cdn.manga.example")
            )
        )
        return DeclarativeSource(config, context)
    }

    // ── config shape ────────────────────────────────────────

    @Test
    fun `manga config survives the fingerprint codec`() {
        val config = MangaFixture.config()
        val decoded = DeclarativeFingerprints.decode(
            DeclarativeFingerprints.encode(listOf(config))
        ).single()
        assertEquals(MediaKind.MANGA, decoded.mediaKind)
        assertEquals(ContentRating.MIXED, decoded.contentRating)
        assertEquals("div.reader img", decoded.chapter.pages)
    }

    @Test
    fun `legacy config json decodes with manga defaults`() {
        // A config file written before the manga programme:
        // no mediaKind, no contentRating, no pages field.
        val legacy = """
            [{"id":"old","name":"Old Site","baseUrl":"https://old.example",
              "details":{"chapters":{"list":"ul li","name":"a","link":"a@href"}},
              "chapter":{"content":"div.c"}}]
        """.trimIndent()
        val decoded = DeclarativeFingerprints.decode(legacy).single()
        assertEquals(MediaKind.TEXT, decoded.mediaKind)
        assertEquals(ContentRating.SAFE, decoded.contentRating)
        assertNull(decoded.chapter.pages)
    }

    @Test
    fun `text fixture config carries the defaults`() {
        val config = FixtureSite.config()
        assertEquals(MediaKind.TEXT, config.mediaKind)
        assertEquals(ContentRating.SAFE, config.contentRating)
        assertNull(config.chapter.pages)
    }

    // ── catalogue legs behave like a text source ────────────

    @Test
    fun `manga popular details and chapters read like a text source`() =
        runBlocking {
            val source = source(MangaFixture.transport())
            val page = source.popular(1)
            assertEquals(2, page.books.size)
            assertEquals("Panel Saga", page.books.first().title)

            val details = source.details(
                SourceBook(id = MangaFixture.BOOK_URL, title = "?")
            )
            assertEquals("Panel Saga", details.title)
            assertEquals("Ink Author", details.author)

            val chapters = source.chapters(
                SourceBook(id = MangaFixture.BOOK_URL, title = "Panel Saga")
            )
            assertEquals(2, chapters.size)
            assertEquals(MangaFixture.CHAPTER_1_URL, chapters[0].id)
            assertEquals(MangaFixture.CHAPTER_2_URL, chapters[1].id)
        }

    // ── the image chapter ───────────────────────────────────

    @Test
    fun `chapter content is the ordered page list, absolutised and deduped`() =
        runBlocking {
            val source = source(MangaFixture.transport())
            val chapter = SourceChapter(
                id = MangaFixture.CHAPTER_1_URL, name = "Chapter 1", number = 1f
            )
            val result = source.chapterContent(chapter)
            assertEquals("Chapter 1", result.title)

            val pages = source.pagesOf(chapter.id)
                ?: error("a manga read must stash its page record")
            assertEquals(
                listOf(
                    "${MangaFixture.BASE}/pages/c1-1.jpg",
                    // parked in data-src behind a data: placeholder
                    "${MangaFixture.BASE}/pages/c1-2.jpg",
                    "https://cdn.manga.example/pages/c1-3.jpg",
                    // chapter page 2 of the chain adds one more;
                    // its repeat of c1-3 is de-duplicated
                    "${MangaFixture.BASE}/pages/c1-4.jpg"
                ),
                pages.urls
            )
            // The normalised content carries the same record,
            // with no prose blocks.
            val content = source.contentOf(chapter.id)
                ?: error("the engine must stash the normalised content")
            assertTrue(content.isImageChapter)
            assertTrue(content.blocks.isEmpty())
            assertEquals(pages, content.pages)
            // Every page starts online-only: localFile is the
            // M3 download pass's room, still empty here.
            assertTrue(pages.pages.all { it.localFile == null })
        }

    @Test
    fun `pages spec may name a lazy attribute directly`() = runBlocking {
        val config = MangaFixture.config().copy(
            chapter = MangaFixture.config().chapter.copy(
                pages = "div.reader img@data-lazy"
            )
        )
        val source = source(MangaFixture.transport(), config)
        val chapter = SourceChapter(
            id = MangaFixture.CHAPTER_2_URL, name = "Chapter 2", number = 2f
        )
        source.chapterContent(chapter)
        val pages = source.pagesOf(chapter.id)
            ?: error("a manga read must stash its page record")
        assertEquals(
            listOf(
                "${MangaFixture.BASE}/pages/c2-1.jpg",
                "${MangaFixture.BASE}/pages/c2-2.jpg"
            ),
            pages.urls
        )
    }

    @Test
    fun `a manga chapter page with no pages fails in plain words`() =
        runBlocking {
            val source = source(MangaFixture.transport())
            val chapter = SourceChapter(
                id = "${MangaFixture.BASE}/book/panel-saga/chapter-9",
                name = "Chapter 9", number = 9f
            )
            val thrown = runCatching { source.chapterContent(chapter) }
                .exceptionOrNull()
            assertTrue(thrown is app.xreader.extensionapi.SourceException)
            assertTrue(thrown!!.message!!.contains("404"))
        }

    @Test
    fun `text chapter content has no page record`() = runBlocking {
        val context = testContext(FixtureSite.transport(), tmp.newFolder())
        val source = DeclarativeSource(FixtureSite.config(), context)
        val chapter = SourceChapter(
            id = FixtureSite.CHAPTER_1_URL, name = "Chapter 1", number = 1f
        )
        source.chapterContent(chapter)
        assertNull(source.pagesOf(chapter.id))
        val content = source.contentOf(chapter.id)
            ?: error("text content must still be stashed")
        assertFalse(content.isImageChapter)
        assertNull(content.pages)
        assertTrue(content.blocks.isNotEmpty())
    }

    // ── the persisted record ────────────────────────────────

    @Test
    fun `page record persists through chapter storage with local file room`() =
        runBlocking {
            val storage = BookStorage(tmp.newFolder())
            val pages = ChapterPages(
                listOf(
                    ChapterPage(url = "https://img.example/p1.jpg"),
                    ChapterPage(
                        url = "https://img.example/p2.jpg",
                        localFile = "img-p2.jpg"
                    )
                )
            )
            val written = storage.writeChapterContent(
                7L, 42L, ChapterContent(pages = pages)
            )
            assertTrue(written is app.xreader.core.error.AppResult.Ok)
            val read = storage.readChapterContent(7L, 42L)
            assertTrue(read is app.xreader.core.error.AppResult.Ok)
            val content = (read as app.xreader.core.error.AppResult.Ok).value
            assertTrue(content.isImageChapter)
            assertEquals(pages, content.pages)
            assertEquals(1, content.pages!!.downloadedCount)

            // The M3 write-back shape: record one page's file.
            val updated = pages.withLocalFile(0, "img-p1.jpg")
            assertEquals("img-p1.jpg", updated.pages[0].localFile)
            assertEquals(2, updated.downloadedCount)
            // Out-of-range is a no-op, never a crash.
            assertEquals(pages, pages.withLocalFile(9, "img-x.jpg"))
        }

    @Test
    fun `text chapter storage stays free of the pages field`() = runBlocking {
        val dir = tmp.newFolder()
        val storage = BookStorage(dir)
        val content = ChapterContent(
            blocks = listOf(
                app.xreader.domain.content.ChapterBlock.Paragraph
                    .plain("Some prose.")
            )
        )
        storage.writeChapterContent(3L, 9L, content)
        val raw = java.io.File(dir, "content/3/9.json").readText()
        assertFalse("pages" in raw)
        val read = storage.readChapterContent(3L, 9L)
        assertTrue(read is app.xreader.core.error.AppResult.Ok)
        assertNull(
            (read as app.xreader.core.error.AppResult.Ok).value.pages
        )
    }
}

/** A tiny fake manga site (host manga.example): a catalogue,
 * a book page whose chapters list newest-first, and chapters
 * whose pages are <img> tags in a reader div — one page parked
 * in data-src behind a data: placeholder, one on a CDN host,
 * and chapter 1 split across two web pages. */
object MangaFixture {
    const val HOST = "manga.example"
    const val BASE = "https://manga.example"

    const val BOOK_URL = "$BASE/book/panel-saga"
    const val CHAPTER_1_URL = "$BASE/book/panel-saga/chapter-1"
    const val CHAPTER_2_URL = "$BASE/book/panel-saga/chapter-2"

    fun pages(): Map<String, String> = mapOf(
        "$BASE/popular" to """
            <html><head><title>Popular</title></head><body>
            <div class="results">
              <div class="result">
                <a class="t" href="/book/panel-saga">Panel Saga</a>
                <span class="a">Ink Author</span>
                <img class="c" src="/covers/panel.jpg"/>
              </div>
              <div class="result">
                <a class="t" href="/book/other-ink">Other Ink</a>
                <span class="a">Pen Writer</span>
              </div>
            </div>
            </body></html>
        """.trimIndent(),
        BOOK_URL to """
            <html><head>
              <title>Panel Saga - Manga Example</title>
              <meta name="description" content="A hero, drawn panel by panel."/>
            </head><body>
            <h1 class="book-title">Panel Saga</h1>
            <div class="author">Ink Author</div>
            <ul class="chapters">
              <li><a href="/book/panel-saga/chapter-2">Chapter 2: The Ink Road</a></li>
              <li><a href="/book/panel-saga/chapter-1">Chapter 1: First Panel</a></li>
            </ul>
            </body></html>
        """.trimIndent(),
        CHAPTER_1_URL to """
            <html><head><title>Chapter 1</title></head><body>
            <h1>Chapter 1</h1>
            <div class="reader">
              <img src="/pages/c1-1.jpg"/>
              <img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=" data-src="/pages/c1-2.jpg"/>
              <img src="https://cdn.manga.example/pages/c1-3.jpg"/>
            </div>
            <a class="next-page" href="/book/panel-saga/chapter-1?page=2">Next page</a>
            </body></html>
        """.trimIndent(),
        "$CHAPTER_1_URL?page=2" to """
            <html><head><title>Chapter 1 (2)</title></head><body>
            <div class="reader">
              <img src="https://cdn.manga.example/pages/c1-3.jpg"/>
              <img src="/pages/c1-4.jpg"/>
            </div>
            </body></html>
        """.trimIndent(),
        CHAPTER_2_URL to """
            <html><head><title>Chapter 2</title></head><body>
            <h1>Chapter 2</h1>
            <div class="reader">
              <img data-lazy="/pages/c2-1.jpg" src="data:image/gif;base64,R0lGODlhAQABAAAAACw="/>
              <img data-lazy="/pages/c2-2.jpg"/>
            </div>
            </body></html>
        """.trimIndent()
    )

    fun transport(): FakeTransport {
        val all = pages()
        return FakeTransport { request ->
            val body = all[request.url]
            if (body == null) {
                app.xreader.core.error.AppResult.Ok(
                    FakeTransport.response(404, "not found")
                )
            } else {
                FakeTransport.ok(body)
            }
        }
    }

    fun config(): DeclarativeConfig = DeclarativeConfig(
        id = "manga-example",
        name = "Manga Example",
        lang = "en",
        baseUrl = BASE,
        mediaKind = MediaKind.MANGA,
        contentRating = ContentRating.MIXED,
        popular = ListSpec(
            url = "$BASE/popular",
            results = "div.result",
            title = "a.t",
            link = "a.t@href",
            cover = "img.c@src",
            author = "span.a"
        ),
        details = DetailsSpec(
            title = "h1.book-title",
            author = "div.author",
            description = "meta[name=description]@content",
            chapters = ChapterListSpec(
                list = "ul.chapters > li",
                name = "a",
                link = "a@href",
                reverse = true
            )
        ),
        chapter = ChapterSpec(
            content = "div.reader",
            title = "h1",
            nextPage = "a.next-page@href",
            pages = "div.reader img"
        )
    )
}
