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

    /** Response-body sample cap per call. */
    const val MAX_BODY_SAMPLE = 4_000

    private val bySession = LinkedHashMap<String, MutableList<HarvestedCall>>()

    @Synchronized
    fun record(sessionKey: String, call: HarvestedCall) {
        val list = bySession.getOrPut(sessionKey) { mutableListOf() }
        list += call.copy(
            bodySample = call.bodySample.take(MAX_BODY_SAMPLE)
        )
        while (list.size > MAX_CALLS_PER_SESSION) list.removeAt(0)
    }

    @Synchronized
    fun forSession(sessionKey: String): List<HarvestedCall> =
        bySession[sessionKey]?.toList() ?: emptyList()

    @Synchronized
    fun clearForTests() = bySession.clear()
}
