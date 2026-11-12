package app.xreader.data.extension

import app.xreader.core.error.AppResult
import app.xreader.data.FakeTransport
import app.xreader.extensionapi.SourceBook
import app.xreader.extensionapi.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.5.8 (D2 / F6): the BrightNovel conviction, replayed
 * against the SHIPPED [BundledSingles.brightNovel]
 * definition (never a test-local copy): the declared JSON
 * endpoint answers a VALID EMPTY (`{"chapters": []}` — the
 * shape the desk check recorded the live free endpoint
 * returning) while the book page's DOM is the recorded
 * app-shell furniture (series cards for OTHER books, a
 * "Latest Chapter" nav anchor, an author anchor, genre
 * chips). Pre-fix the item-85 fallback swept that furniture
 * into the chapter list and stamped it complete. Post-fix:
 * the endpoint road is authoritative, the bare-`a` sweep is
 * not a scoped road, and the harvest is an honest EMPTY
 * carrying ENDPOINT provenance.
 *
 * RE-DERIVED (v1.5.12, Front 1 S3): the shipped definition
 * now declares the floor (totalCount = 1), so against the
 * SHIPPED config an endpoint [] correctly raises a
 * SourceException instead of returning Ok(empty). Since
 * v1.5.37, a hint sourced only from that spec floor carries
 * the no-chapters-yet flavour. The fixture's core property
 * is unchanged and still asserted: an empty endpoint NEVER
 * yields substituted furniture rows — the shipped-config
 * case now surfaces the honest no-chapters-yet failure
 * rather than any rows at all, and the hint-less case below
 * keeps the original honest-empty + ENDPOINT-provenance
 * shape (verified in DeclarativeSource.fetchJsonEndpointPageStrict:
 * hint 0 returns the valid empty page).
 */
class V158BrightNovelFurnitureFixture {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bookUrl = "https://brightnovels.com/series/the-dullard"
    private val endpointUrl =
        "https://brightnovels.com/series/the-dullard/chapters/free" +
            "?loaded=0&sort_order=desc"

    private fun bookPageHtml(): String {
        val dataPage = "{&quot;component&quot;:&quot;Series/Show&quot;," +
            "&quot;props&quot;:{&quot;series&quot;:{&quot;title&quot;:" +
            "&quot;The Dullard&quot;}},&quot;url&quot;:&quot;/series/" +
            "the-dullard&quot;,&quot;version&quot;:&quot;1&quot;}"
        return """
            <html><head><title>The Dullard</title></head><body>
            <div id="app" data-page="$dataPage"></div>
            <nav><a href="$bookUrl/chapter-9">Latest Chapter</a></nav>
            <div class="card">
              <a href="https://brightnovels.com/series/other-book">
                Other Book <span>Nov 4, 2026</span></a>
            </div>
            <div class="card">
              <a href="https://brightnovels.com/series/another-book">
                Another Book <span>Nov 3, 2026</span></a>
            </div>
            <a href="https://brightnovels.com/series?genre=Fantasy">Fantasy</a>
            <a href="https://brightnovels.com/authors/mary">Mary</a>
            </body></html>
        """.trimIndent()
    }

    private fun transport(): FakeTransport = FakeTransport { request ->
        when (request.url) {
            bookUrl -> FakeTransport.ok(bookPageHtml())
            endpointUrl -> FakeTransport.ok("{\"chapters\": []}")
            else -> AppResult.Ok(FakeTransport.response(404, "no shape"))
        }
    }

    @Test
    fun `valid empty endpoint answer against the shipped floor yields no-chapters-yet, never furniture`() {
        val config = BundledSingles.brightNovel(
            "bundled-brightnovel", "BrightNovel"
        )
        val source = DeclarativeSource(
            config,
            testContext(
                transport(), tmp.newFolder(),
                ExtensionPermissions(domains = listOf("brightnovels.com"))
            )
        )
        // The core property: the call must NOT return
        // furniture rows. Under the shipped floor it does
        // not return rows at all — it raises the honest
        // no-chapters-yet failure.
        try {
            val rows = source.chapters(
                SourceBook(id = bookUrl, title = "The Dullard")
            )
            fail(
                "an endpoint [] against the shipped floor must not " +
                    "return rows (furniture or otherwise), got $rows"
            )
        } catch (e: SourceException) {
            assertTrue(
                "the floor-only hint must fire the no-chapters-yet tier; got: ${e.message}",
                e.message.orEmpty().contains(
                    DeclarativeSource.ENDPOINT_NO_CHAPTERS_YET_MARKER
                )
            )
            assertFalse(
                "the floor alone must not be treated as a site-declared total",
                e.message.orEmpty().contains(
                    DeclarativeSource.ENDPOINT_SUSPICIOUS_EMPTY_MARKER
                )
            )
        }
    }

    @Test
    fun `valid empty endpoint answer yields honest empty, never furniture`() {
        // The same road with NO floor declared (the shipped
        // config with only totalCount removed): hint 0, so
        // the strict road accepts the valid empty page —
        // the original D2/F6 shape, honest empty carrying
        // ENDPOINT provenance, never furniture.
        val shipped = BundledSingles.brightNovel(
            "bundled-brightnovel", "BrightNovel"
        )
        val config = shipped.copy(
            details = shipped.details.copy(
                chapters = shipped.details.chapters.copy(
                    totalCount = null
                )
            )
        )
        val source = DeclarativeSource(
            config,
            testContext(
                transport(), tmp.newFolder(),
                ExtensionPermissions(domains = listOf("brightnovels.com"))
            )
        )
        val rows = source.chapters(
            SourceBook(id = bookUrl, title = "The Dullard")
        )
        assertTrue(
            "a valid-empty endpoint answer must read as an " +
                "honest empty — no furniture rows, got $rows",
            rows.isEmpty()
        )
        assertEquals(
            "the empty carries the ENDPOINT road's provenance " +
                "(never a DOM fallback's)",
            ChapterSuppliedBy.ENDPOINT, source.lastWalkSuppliedBy
        )
    }
}
