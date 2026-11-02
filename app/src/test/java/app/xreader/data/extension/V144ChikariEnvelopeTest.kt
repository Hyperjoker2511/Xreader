package app.xreader.data.extension

import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.4.4: the Chikari chapters-envelope regression pin.
 *
 * The chapters endpoint answers the paginated envelope
 * `{"items":[…],"total":N,"limit":L,"offset":O}` (re-verified
 * live 2026-11-02: a bare request defaults to limit=100;
 * larger limits are clamped server-side to 500). The bundled
 * lane now walks at the endpoint's OWN page size — 100 — so
 * the progressive list grows a page at a time as the reader
 * scrolls, instead of pulling 500-row pages. This test pins
 * the whole walk against a synthetic 250-chapter book: first
 * chunk = exactly one 100-row page (one request — page 1 is
 * never batched) + the page size on the cursor; growth then
 * walks the offsets in order (in the S5 design's bounded
 * parallel batches) to the stated total, completing exactly
 * there, and no request ever carries a bulk limit.
 */
class V144ChikariEnvelopeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val total = 250

    /** The synthetic envelope, exactly the live shape. */
    private fun envelope(offset: Int, limit: Int): String {
        val last = minOf(offset + limit, total)
        val items = (offset + 1..last).joinToString(",") { n ->
            """{"number":$n,"title":"Chapter $n",""" +
                """"created_at":"2026-10-31T06:40:47.434361+00:00"}"""
        }
        return """{"items":[$items],"total":$total,""" +
            """"limit":$limit,"offset":$offset}"""
    }

    @Test
    fun `bundled lane is pinned to the envelope at the native page size`() {
        val pagination = BundledSingles
            .chikari("chikari", "Chikari")
            .details.chapters.pagination
        assertTrue(pagination != null)
        assertEquals("items", pagination!!.jsonArrayPath)
        assertEquals("total", pagination.jsonTotalPath)
        assertEquals(100, pagination.pageSize)
        assertTrue(
            pagination.urlTemplate
                .contains("limit={limit}&offset={offset}")
        )
    }

    @Test
    fun `walk pages the envelope by offset as the list grows`() = runBlocking {
        val requests = mutableListOf<String>()
        val transport = FakeTransport { request ->
            requests += request.url
            when {
                request.url.contains("/chapters?") -> {
                    val offset = request.url
                        .substringAfter("offset=").toInt()
                    val limit = request.url
                        .substringAfter("limit=")
                        .substringBefore("&").toInt()
                    FakeTransport.ok(envelope(offset, limit))
                }
                else -> FakeTransport.ok(
                    """{"slug":"test-book","title":"Test Book"}"""
                )
            }
        }
        val context = testContext(
            transport, tmp.newFolder(),
            ExtensionPermissions(domains = listOf("chikari.moe"))
        )
        val source = DeclarativeSource(
            BundledSingles.chikari("chikari", "Chikari"), context
        )
        val book = SourceBook(
            id = "https://chikari.moe/api/novels/test-book",
            title = "Test Book"
        )

        // First chunk: ONE native page, not the whole book.
        val first = source.chaptersFirst(book, minCount = 10)
        assertEquals(100, first.chapters.size)
        assertEquals(1f, first.chapters.first().number)
        assertEquals(100f, first.chapters.last().number)
        assertFalse("250-chapter book must not complete on page 1",
            first.complete)
        assertEquals(2, first.walk.nextPage)
        // The cursor learned the endpoint's native page size
        // from page 1 (the walk's short-page arithmetic rides
        // on it across resumes).
        assertEquals(100, first.walk.pageSize)

        // Growing fetches the next offset only.
        val grown = source.chaptersMore(
            book, first.chapters, minTotal = 150, walk = first.walk
        )
        assertEquals(200, grown.chapters.size)
        assertFalse(grown.complete)

        // The final page completes the walk at the stated total.
        val done = source.chaptersMore(
            book, grown.chapters, minTotal = 250, walk = grown.walk
        )
        assertEquals(250, done.chapters.size)
        assertEquals(250f, done.chapters.last().number)
        assertTrue(done.complete)

        // The request log pins the design's guarantees:
        // the FIRST chunk cost exactly one chapter request
        // (page 1 is never batched — the v1.3.1 pin), and
        // every request the walk ever made was at the native
        // page size (growth batches ahead in bounded parallel
        // batches per the S5 design, capped by the stated
        // total once a session knows it — but never a bulk
        // 500-row page, and page 1 is fetched exactly once).
        val chapterRequests = requests.filter { it.contains("/chapters?") }
        assertTrue(chapterRequests.first().endsWith("offset=0"))
        assertEquals(
            1, chapterRequests.count { it.endsWith("offset=0") }
        )
        assertTrue(chapterRequests.all { it.contains("limit=100") })
        assertFalse(chapterRequests.any { it.contains("limit=500") })
        assertTrue(
            chapterRequests.all {
                it.substringAfter("offset=").toInt() % 100 == 0
            }
        )
    }
}
