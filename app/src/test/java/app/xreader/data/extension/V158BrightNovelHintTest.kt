package app.xreader.data.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (D3): the BrightNovel pagination hint is declared
 * ONLY if a recorded payload shape in the tree actually
 * states a total. Checked 2026-11-05: the recorded
 * endpoint records are `{name, slug, updated_at,
 * is_premium}` (the desk check + the shipped definition's
 * own comment) and the recorded series-page fixture's
 * data-page JSON carries no total anywhere. NO WIRE hint
 * is declared — a fabricated pattern would fail this
 * item — and this test pins that evidence: the shipped
 * definition declares no total path/pattern, and the
 * recorded shapes state no total for one to match.
 *
 * RE-DERIVED (v1.5.12, Front 1 S3): the shipped definition
 * NOW deliberately declares totalCount = 1 — not a wire
 * hint (nothing on the wire states it) but the floor that
 * keeps an endpoint [] from being stamped proven-complete.
 * Conviction: the endpoint's literal [] was stamped
 * proven-complete with 0 chapters (the v1.5.11 proof
 * failure), and with no hint of any kind the strict road
 * had no stated expectation to check it against. Since
 * v1.5.37, a hint whose only source is this spec floor
 * fires the no-chapters-yet flavour rather than
 * suspicious-empty. BUNDLED_DEFINITIONS_EPOCH moved to 3
 * with the floor and has since moved to 6 with later
 * bundled-definition changes. The two tests below still
 * pin the original D3 evidence (no recorded shape states
 * a total for a wire hint).
 */
class V158BrightNovelHintTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "missing test resource $name"
        }.readBytes().toString(Charsets.UTF_8)

    @Test
    fun `shipped brightnovel declares no wire total hint, only the v1_5_12 floor`() {
        val chapters = BundledSingles.brightNovel(
            "bundled-brightnovel", "BrightNovel"
        ).details.chapters
        assertNull(
            "no jsonTotalPath: no recorded endpoint shape states one",
            chapters.pagination?.jsonTotalPath
        )
        assertNull(
            "no totalCountPattern: no recorded page shape states one",
            chapters.totalCountPattern
        )
        assertEquals(
            "the v1.5.12 floor is declared (see class doc)",
            1, chapters.totalCount ?: 0
        )
        assertEquals(
            "the definitions epoch must have moved so installed rows receive the floor",
            6, BundledDefaults.BUNDLED_DEFINITIONS_EPOCH
        )
    }

    @Test
    fun `recorded endpoint record shape states no total`() {
        // The desk-check / fixture record shape the live
        // free endpoint returns per chapter.
        val record = Json.parseToJsonElement(
            "{\"name\":\"Chapter 1\",\"slug\":\"chapter-1\"," +
                "\"updated_at\":\"2026-11-01\",\"is_premium\":false}"
        ).jsonObject
        assertEquals(
            setOf("name", "slug", "updated_at", "is_premium"),
            record.keys
        )
        assertTrue(
            "no key in the recorded record shape names a total",
            record.keys.none {
                it.contains("total") || it.contains("count")
            }
        )
    }

    @Test
    fun `recorded series page shape states no total`() {
        val html = resource("v154/brightnovel-series.html")
        val start = html.indexOf("data-page=\"")
        assertTrue("fixture carries a data-page payload", start >= 0)
        val raw = html.substring(start + "data-page=\"".length)
            .substringBefore("\"")
            .replace("&quot;", "\"")
        val page = Json.parseToJsonElement(raw).jsonObject
        fun allKeys(element: kotlinx.serialization.json.JsonElement): Set<String> =
            when (element) {
                is kotlinx.serialization.json.JsonObject ->
                    element.keys + element.values.flatMap { allKeys(it) }
                is kotlinx.serialization.json.JsonArray ->
                    element.flatMap { allKeys(it) }.toSet()
                else -> emptySet()
            }
        val keys = allKeys(page)
        assertTrue(
            "the recorded page payload's keys state no total: $keys",
            keys.none {
                it.equals("total", ignoreCase = true) ||
                    it.equals("total_chapters", ignoreCase = true) ||
                    it.equals("chapter_count", ignoreCase = true)
            }
        )
    }
}
