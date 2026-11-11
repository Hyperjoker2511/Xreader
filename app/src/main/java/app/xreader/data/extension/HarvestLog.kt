package app.xreader.data.extension

/**
 * v1.5.0 (A2 — the API-harvest road): one network call a
 * site's own page made while Xreader's hidden browser view
 * rendered it, recorded by the view's injected observer.
 *
 * The browser view is a SCOUT, not a crutch (the user's
 * API-harvest direction): while it renders a pasted site it
 * records the XHR/fetch traffic the page's own scripts
 * fire; the learner ([ApiRecipeSynthesizer]) promotes those
 * calls into an API-first recipe, self-verifies all five
 * legs over the API road, and only then saves the source as
 * working — page scraping stays the fallback. The log is a
 * side channel keyed by the fetch's session key, exactly
 * like [PageShapeLog]: no API surface changes, and JVM
 * tests feed the synthesizer synthetic calls.
 */
data class HarvestedCall(
    val url: String,
    val method: String,
    val status: Int,
    /** The response's content type, when the page's scripts
     * could see it. */
    val contentType: String?,
    /** Request headers the page's own client set (fetch
     * options / XHR setRequestHeader) — the evidence a
     * cookie→header credential binding is synthesised from:
     * a header whose value equals a cookie's value is the
     * site replaying its own session token. Header values
     * are recorded ONLY for calls the page made to hosts in
     * the probe's own permission set; the synthesizer reads
     * them, nothing is ever replayed from the log itself. */
    val requestHeaders: Map<String, String> = emptyMap(),
    /** A bounded sample of the response body (the first
     * [HarvestLog.MAX_BODY_SAMPLE] characters) — enough to
     * recognise JSON shapes, never a chapter's full text. */
    val bodySample: String = "",
    /** The page-visible cookies (document.cookie) at record
     * time: name to value. JS-set cookies only — HttpOnly
     * cookies never appear here, by the web platform's own
     * rule. */
    val pageCookies: Map<String, String> = emptyMap()
) {
    val host: String? get() = CookieParsing.hostOf(url)

    /** True when the response looks like a JSON payload. */
    val isJson: Boolean
        get() = contentType?.contains("json", ignoreCase = true) == true ||
            bodySample.trimStart().let { it.startsWith("{") || it.startsWith("[") }
}

object HarvestLog {

    /** Per session: how many calls are kept (the newest). */
    const val MAX_CALLS_PER_SESSION = 60

    /** Response-body sample cap per call for NON-JSON bodies
     * (and the fallback when a JSON body can't be shaped). */
    const val MAX_BODY_SAMPLE = 4_000

    /** Hard bound for a SHAPED JSON sample (see [shapeSample]). */
    const val MAX_SHAPED_SAMPLE = 32_768

    /** Records kept whole per array in a shaped sample —
     * one more than the synthesizer's book-list bar, so a
     * fat-record API's list is still recognisable. */
    const val SHAPED_ARRAY_ITEMS = 4

    /** String values in a shaped sample are cut here — a
     * chapter's prose field becomes a shape excerpt, never
     * the chapter's full text. */
    const val SHAPED_STRING_CHARS = 512

    private val bySession = LinkedHashMap<String, MutableList<HarvestedCall>>()

    @Synchronized
    fun record(sessionKey: String, call: HarvestedCall) {
        val list = bySession.getOrPut(sessionKey) { mutableListOf() }
        list += call.copy(bodySample = shapeSample(call.bodySample))
        while (list.size > MAX_CALLS_PER_SESSION) list.removeAt(0)
    }

    /**
     * v1.5.2 fix (FIX-1c): the sample a call leaves behind.
     *
     * The old rule — a blind 4,000-char cut — made the API
     * road unreachable for a whole family of real sites: an
     * API whose book records are fat (story-wave's are
     * 1.7–5 KB each) never fits the synthesizer's 3-record
     * book-list bar inside 4,000 chars, so BOTI's harvest
     * could never synthesise no matter how clean the traffic
     * was. The mechanism fix: a JSON body is sampled by
     * SHAPE — envelope objects kept whole, arrays cut to
     * their first [SHAPED_ARRAY_ITEMS] records, string
     * values cut to [SHAPED_STRING_CHARS] (a chapter's prose
     * is an excerpt, never the full text) — bounded by
     * [MAX_SHAPED_SAMPLE]. Non-JSON bodies keep the plain
     * [MAX_BODY_SAMPLE] cut, exactly as before.
     */
    fun shapeSample(body: String): String {
        if (body.length <= MAX_BODY_SAMPLE) return body
        val trimmed = body.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return body.take(MAX_BODY_SAMPLE)
        }
        val parsed = parseWholeOrRepaired(trimmed) ?: return body.take(MAX_BODY_SAMPLE)
        val shaped = shapeElement(parsed)
        val encoded = shaped.toString()
        return if (encoded.length <= MAX_SHAPED_SAMPLE) {
            encoded
        } else {
            encoded.take(MAX_SHAPED_SAMPLE)
        }
    }

    private val shapeJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    private fun shapeElement(
        element: kotlinx.serialization.json.JsonElement
    ): kotlinx.serialization.json.JsonElement = when (element) {
        is kotlinx.serialization.json.JsonObject ->
            kotlinx.serialization.json.JsonObject(
                element.mapValues { (_, value) -> shapeElement(value) }
            )
        is kotlinx.serialization.json.JsonArray ->
            kotlinx.serialization.json.JsonArray(
                element.take(SHAPED_ARRAY_ITEMS).map { shapeElement(it) }
            )
        is kotlinx.serialization.json.JsonPrimitive ->
            if (element.isString && element.content.length > SHAPED_STRING_CHARS) {
                kotlinx.serialization.json.JsonPrimitive(
                    element.content.take(SHAPED_STRING_CHARS)
                )
            } else {
                element
            }
        else -> element
    }

    /** Parse a complete JSON body; when the transport cut it
     * mid-stream, repair by cutting at the last complete
     * object and closing every still-open bracket. */
    private fun parseWholeOrRepaired(
        text: String
    ): kotlinx.serialization.json.JsonElement? {
        runCatching { return shapeJson.parseToJsonElement(text) }
        val lastObject = text.lastIndexOf('}')
        if (lastObject <= 0) return null
        val cut = text.substring(0, lastObject + 1)
        val repaired = closeOpenBrackets(cut) ?: return null
        return runCatching { shapeJson.parseToJsonElement(repaired) }
            .getOrNull()
    }

    /** Append the closers for every bracket [cut] leaves open
     * (string-aware: brackets inside string literals don't
     * count). Null when the text is unbalanced the other way. */
    internal fun closeOpenBrackets(cut: String): String? {
        val open = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (ch in cut) {
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' || ch == '[' -> open.addLast(ch)
                ch == '}' -> {
                    if (open.removeLastOrNull() != '{') return null
                }
                ch == ']' -> {
                    if (open.removeLastOrNull() != '[') return null
                }
            }
        }
        if (inString) return null
        val sb = StringBuilder(cut)
        while (open.isNotEmpty()) {
            sb.append(if (open.removeLast() == '{') '}' else ']')
        }
        return sb.toString()
    }

    @Synchronized
    fun forSession(sessionKey: String): List<HarvestedCall> =
        bySession[sessionKey]?.toList() ?: emptyList()

    @Synchronized
    fun clearForTests() = bySession.clear()
}
