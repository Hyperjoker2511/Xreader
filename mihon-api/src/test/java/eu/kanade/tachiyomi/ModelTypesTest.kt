package eu.kanade.tachiyomi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour checks for the transplanted lib-1.6 model types. These run on the
 * JVM; they prove the donor implementations behave (defaults, copy, the
 * lib-1.6 `memo` field, Page's progress listener), not just compile.
 */
class ModelTypesTest {

    @Test
    fun `SManga create gives lib-1_6 defaults`() {
        val manga = SManga.create()
        assertEquals("", manga.title)
        assertEquals(SManga.UNKNOWN, manga.status)
        assertEquals(UpdateStrategy.ALWAYS_UPDATE, manga.update_strategy)
        assertFalse(manga.initialized)
        // The lib-1.6 addition: memo defaults to an empty JsonObject.
        assertEquals(JsonObject(emptyMap()), manga.memo)
        assertNull(manga.thumbnail_url)
        assertNull(manga.genre)
    }

    @Test
    fun `SManga fields round trip including memo`() {
        val manga = SManga.create().apply {
            url = "/manga/one-piece"
            title = "One Piece"
            author = "Oda"
            artist = "Oda"
            status = SManga.ONGOING
            description = "Pirates."
            genre = "Action, Adventure"
            thumbnail_url = "https://example.org/cover.jpg"
            memo = JsonObject(mapOf("key" to JsonPrimitive("value")))
            initialized = true
        }
        assertEquals("/manga/one-piece", manga.url)
        assertEquals("One Piece", manga.title)
        assertEquals(SManga.ONGOING, manga.status)
        assertEquals(JsonPrimitive("value"), manga.memo["key"])
        assertTrue(manga.initialized)
        // Komikku's original* accessors alias the base fields.
        assertEquals("One Piece", manga.originalTitle)
        assertEquals("Oda", manga.originalAuthor)
        assertEquals(listOf("Action", "Adventure"), manga.getGenres())
    }

    @Test
    fun `SManga status constants match the contract`() {
        assertEquals(0, SManga.UNKNOWN)
        assertEquals(1, SManga.ONGOING)
        assertEquals(2, SManga.COMPLETED)
        assertEquals(3, SManga.LICENSED)
        assertEquals(4, SManga.PUBLISHING_FINISHED)
        assertEquals(5, SManga.CANCELLED)
        assertEquals(6, SManga.ON_HIATUS)
    }

    @Test
    fun `SManga copy carries fields and memo`() {
        val manga = SManga.create().apply {
            url = "/m"
            title = "T"
            memo = JsonObject(mapOf("a" to JsonPrimitive(1)))
        }
        val copy = manga.copy()
        assertEquals("/m", copy.url)
        assertEquals("T", copy.title)
        assertEquals(manga.memo, copy.memo)
        copy.title = "T2"
        assertEquals("T", manga.title)
    }

    @Test
    fun `SChapter create gives lib-1_6 defaults`() {
        val chapter = SChapter.create()
        // url and name are lateinit in the donor impl; the valued defaults are:
        assertEquals(-1f, chapter.chapter_number)
        assertEquals(0L, chapter.date_upload)
        assertEquals(JsonObject(emptyMap()), chapter.memo)
        assertNull(chapter.scanlator)
    }

    @Test
    fun `SChapter fields and copyFrom`() {
        val chapter = SChapter.create().apply {
            url = "/m/chapter-1"
            name = "Chapter 1"
            chapter_number = 1.5f
            scanlator = "Group"
            date_upload = 123456789L
            memo = JsonObject(mapOf("x" to JsonPrimitive(true)))
        }
        assertEquals(1.5f, chapter.chapter_number)
        assertEquals(JsonPrimitive(true), chapter.memo["x"])

        val other = SChapter.create()
        other.copyFrom(chapter)
        assertEquals("/m/chapter-1", other.url)
        assertEquals("Chapter 1", other.name)
        assertEquals(1.5f, other.chapter_number)
    }

    @Test
    fun `Page construction defaults and progress listener`() {
        val page = Page(3)
        assertEquals(3, page.index)
        assertEquals(4, page.number)
        assertEquals("", page.url)
        assertNull(page.imageUrl)
        assertNull(page.uri)
        assertEquals(Page.State.Queue, page.status)
        assertEquals(0, page.progress)

        page.url = "https://example.org/p4"
        page.imageUrl = "https://example.org/img4.jpg"
        assertEquals("https://example.org/p4", page.url)
        assertEquals("https://example.org/img4.jpg", page.imageUrl)

        page.update(bytesRead = 50, contentLength = 200, done = false)
        assertEquals(25, page.progress)
        page.update(bytesRead = 10, contentLength = 0, done = false)
        assertEquals(-1, page.progress)

        page.status = Page.State.Ready
        assertEquals(Page.State.Ready, page.statusFlow.value)
    }

    @Test
    fun `MangasPage components copy and equality`() {
        val manga = SManga.create().apply { url = "/m"; title = "T" }
        val page = MangasPage(listOf(manga), hasNextPage = true)

        val (mangas, hasNext) = page
        assertEquals(listOf(manga), mangas)
        assertTrue(hasNext)

        val copy = page.copy(hasNextPage = false)
        assertEquals(listOf(manga), copy.mangas)
        assertFalse(copy.hasNextPage)

        assertEquals(MangasPage(listOf(manga), true), page)
        assertEquals(MangasPage(listOf(manga), true).hashCode(), page.hashCode())
    }

    @Test
    fun `FilterList vararg delegation and copy`() {
        val checkBox = object : Filter.CheckBox("Completed") {}
        val text = object : Filter.Text("Author") {}
        val filters = FilterList(checkBox, text)
        assertEquals(2, filters.size)
        assertSame(checkBox, filters[0])
        assertSame(text, filters.list[1])
        assertTrue(filters.contains(text))

        assertEquals(0, FilterList().size)
        assertEquals(0, FilterList(emptyList()).size)
        assertEquals(filters, filters.copy())
    }

    @Test
    fun `Filter TriState states and Sort selection`() {
        val tri = object : Filter.TriState("Genre") {}
        assertTrue(tri.isIgnored())
        tri.state = Filter.TriState.STATE_INCLUDE
        assertTrue(tri.isIncluded())
        tri.state = Filter.TriState.STATE_EXCLUDE
        assertTrue(tri.isExcluded())
        assertEquals(0, Filter.TriState.STATE_IGNORE)
        assertEquals(1, Filter.TriState.STATE_INCLUDE)
        assertEquals(2, Filter.TriState.STATE_EXCLUDE)

        val selection = Filter.Sort.Selection(index = 2, ascending = false)
        assertEquals(Filter.Sort.Selection(2, false), selection)
    }

    @Test
    fun `SMangaUpdate holds manga and chapters`() {
        val manga = SManga.create().apply { url = "/m" }
        val chapter = SChapter.create().apply { url = "/m/1" }
        val update = SMangaUpdate(manga, listOf(chapter))
        assertSame(manga, update.manga)
        assertEquals(listOf(chapter), update.chapters)
    }

    @Test
    fun `Requests build GET and POST`() {
        val get = GET("https://example.org/list?page=2")
        assertEquals("GET", get.method)
        assertEquals("https://example.org/list?page=2", get.url.toString())

        val getUrl = GET("https://example.org/a".toHttpUrl())
        assertEquals("https://example.org/a", getUrl.url.toString())

        val post = POST("https://example.org/search")
        assertEquals("POST", post.method)
        assertTrue(post.body != null)
    }

    @Test
    fun `HttpException carries the code`() {
        val e = HttpException(503)
        assertEquals(503, e.code)
        assertEquals("HTTP error 503", e.message)
    }

    @Test
    fun `AppInfo defaults until the host wires its version`() {
        assertEquals(0, AppInfo.getVersionCode())
        assertEquals("", AppInfo.getVersionName())
    }
}
