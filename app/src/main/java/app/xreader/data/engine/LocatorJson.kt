package app.xreader.data.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v1.5.6 (§3.2): the fixed locator forms, as pure functions so the
 * round-trips are JVM-provable without an engine.
 *
 *  - PDF progress/bookmark: `{"kind":"pdfPage","page":N,"inPageFraction":f}`
 *  - EPUB in-place: the Readium Locator's own JSON (the toolkit
 *    serializes it; `locations.totalProgression` is the book
 *    fraction) — never hand-built here.
 *  - Outline targets: EPUB `{"section":i}`, PDF `{"page":p}`.
 */
object LocatorJson {

    private val json = Json { ignoreUnknownKeys = true }

    // ── PDF locators ─────────────────────────────────────────────

    fun pdfLocator(page: Int, inPageFraction: Float): String =
        "{\"kind\":\"pdfPage\",\"page\":$page,\"inPageFraction\":$inPageFraction}"

    /** (page, inPageFraction) or null when [locatorJson] is not a
     * PDF page locator. */
    fun parsePdfLocator(locatorJson: String?): Pair<Int, Float>? {
        val obj = parseObject(locatorJson) ?: return null
        if (obj["kind"]?.jsonPrimitive?.content != "pdfPage") return null
        val page = obj["page"]?.jsonPrimitive?.intOrNull ?: return null
        val inPage = obj["inPageFraction"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 0f
        return page to inPage
    }

    fun isPdfLocator(locatorJson: String?): Boolean = parsePdfLocator(locatorJson) != null

    // ── Outline targets ──────────────────────────────────────────

    fun epubSectionTarget(section: Int): String = "{\"section\":$section}"

    fun pdfPageTarget(page: Int): String = "{\"page\":$page}"

    /** The section/page index an outline target (or a locator in
     * target form) names, or null. */
    fun targetIndex(targetJson: String?): Int? {
        val obj = parseObject(targetJson) ?: return null
        obj["section"]?.jsonPrimitive?.intOrNull?.let { return it }
        obj["page"]?.jsonPrimitive?.intOrNull?.let { return it }
        return null
    }

    // ── Readium (EPUB) locator JSON ──────────────────────────────

    /** The resource href a Readium locator JSON names. */
    fun epubLocatorHref(locatorJson: String?): String? {
        val obj = parseObject(locatorJson) ?: return null
        return obj["href"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    /** `locations.totalProgression` of a Readium locator JSON. */
    fun epubTotalProgression(locatorJson: String?): Float? {
        val obj = parseObject(locatorJson) ?: return null
        val locations = runCatching { obj["locations"]?.jsonObject }.getOrNull()
            ?: return null
        return locations["totalProgression"]?.jsonPrimitive?.doubleOrNull?.toFloat()
    }

    /** `locations.progression` (in-resource fraction). */
    fun epubProgression(locatorJson: String?): Float? {
        val obj = parseObject(locatorJson) ?: return null
        val locations = runCatching { obj["locations"]?.jsonObject }.getOrNull()
            ?: return null
        return locations["progression"]?.jsonPrimitive?.doubleOrNull?.toFloat()
    }

    fun parseObject(text: String?): kotlinx.serialization.json.JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }
}
