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

    /** v1.7 Patch 3 (Item 3e): the `text.highlight` quote a
     * Readium locator JSON carries (selection-made locators
     * store the selected words there, with before/after
     * context) — the re-anchor the place migration searches
     * for in the converted chapter text. Null when the
     * locator carries no quote. */
    fun epubLocatorQuote(locatorJson: String?): String? {
        val obj = parseObject(locatorJson) ?: return null
        val text = runCatching { obj["text"]?.jsonObject }.getOrNull()
            ?: return null
        return text["highlight"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
    }

    fun parseObject(text: String?): kotlinx.serialization.json.JsonObject? {
        if (text.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }
}

/** The path portion of an href: for an absolute URL the scheme
 * and host are dropped; a relative href IS its path (stripping
 * its first segment was the v1.5.6 harness find — a relative
 * "OEBPS/ch1.xhtml" must not collapse to "ch1.xhtml").
 *
 * v1.7 OE-2: moved here, verbatim, from the retired
 * ReadiumEpubSession.kt — the href machinery outlives the
 * session: the EPUB prepare bridge (EpubHrefBridge) keys on
 * [canonicalHrefKey]. */
private fun String.pathPortion(): String {
    val noHost = if (contains("://")) {
        substringAfter("://").substringAfter('/', "")
    } else {
        this
    }
    return noHost.trimStart('/')
}

/**
 * v1.5.9 (F-3a): the canonical map key the href-equivalence
 * relation collapses to — decoded path portion of the
 * fragment-free href. Two hrefs that name the same resource
 * in different spellings (fragment, leading base path,
 * percent-encoding) share one key.
 *
 * v1.7 OE-2: moved here, verbatim, from the retired
 * ReadiumEpubSession.kt — its surviving consumer is the
 * EPUB prepare bridge (data/prepare/EpubPrepareAccess.kt).
 */
internal fun canonicalHrefKey(href: String): String {
    val path = href.substringBefore('#').pathPortion()
    return runCatching { java.net.URLDecoder.decode(path, "UTF-8") }
        .getOrDefault(path)
}
