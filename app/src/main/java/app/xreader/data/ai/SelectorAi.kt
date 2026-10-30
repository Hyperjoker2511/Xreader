package app.xreader.data.ai

import app.xreader.core.error.AppError
import app.xreader.core.error.AppResult
import app.xreader.data.extension.ChapterNumbers
import app.xreader.data.extension.CookieParsing
import app.xreader.data.extension.DeclarativeConfig
import app.xreader.data.extension.DetailsSpec
import app.xreader.data.extension.ChapterListSpec
import app.xreader.data.extension.ChapterSpec
import app.xreader.data.extension.ExtensionHttpClient
import app.xreader.data.extension.ExtensionPermissions
import app.xreader.data.extension.HostContextFactory
import app.xreader.data.extension.LinkInference
import app.xreader.data.extension.DiagnosticLog
import app.xreader.data.extension.LinkProber
import app.xreader.data.extension.ListSpec
import app.xreader.data.extension.RateLimiter
import app.xreader.data.prefs.AiFeature
import app.xreader.data.prefs.AiSettings
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * URL Selector AI (PLAN §7 + Amendment 7): the smarter retry for
 * sites the heuristic prober can't read. Gemini studies the book
 * page's and one chapter page's HTML and proposes a
 * [DeclarativeConfig] — and then the config faces THE SAME mandatory
 * test-drive as any heuristic candidate (LinkProber.testDrive). An
 * AI config that doesn't actually read the site is never saved.
 */
class SelectorAi(
    private val client: AiClient,
    private val prober: LinkProber,
    private val contextFactory: HostContextFactory,
    private val settingsProvider: suspend () -> AiSettings
) {

    sealed interface Outcome {
        data class Ready(val draft: LinkProber.Draft) : Outcome
        data class LoginRequired(val loginUrl: String, val host: String) : Outcome
        data class Failed(val message: String) : Outcome
    }

    suspend fun buildFromLink(rawUrl: String): Outcome {
        if (!settingsProvider().enabled(AiFeature.SELECTOR_AI)) {
            return Outcome.Failed(
                "The AI detector is turned off — switch it on in Providers & keys."
            )
        }
        if (!client.hasKey()) {
            return Outcome.Failed(
                "The AI detector needs an AI key — add one in More → " +
                    "Providers & keys (a free Gemini key works), then try again."
            )
        }
        val url = LinkProber.normaliseUrl(rawUrl)
        if (url == null) {
            // v1.0.6.1 (P1/P6): same treatment as the prober —
            // the refusal stays word for word; the rejected
            // input's fingerprint goes to the diagnostic log.
            DiagnosticLog.record(
                "AI detector",
                "That doesn't look like a web address — check the link.",
                LinkProber.inputDiagnostics(rawUrl)
            )
            return Outcome.Failed("That doesn't look like a web address — check the link.")
        }
        val host = CookieParsing.hostOf(url)
        if (host == null) {
            DiagnosticLog.record(
                "AI detector",
                "That doesn't look like a web address — check the link.",
                LinkProber.inputDiagnostics(rawUrl)
            )
            return Outcome.Failed("That doesn't look like a web address — check the link.")
        }
        val probeLimiter = RateLimiter(minIntervalMs = 0)
        val probeClient = contextFactory.clientFor(
            sessionKey = ExtensionHttpClient.linkProbeSessionKey(host),
            label = host,
            permissions = ExtensionPermissions(domains = listOf(host)),
            rateLimiter = probeLimiter
        )
        // v1.0.6 (W3.2): the AI studies the same pages the probe
        // does — when a page only exists after its scripts run,
        // the model is shown the RENDERED document (the escalated
        // fetch), never the empty shell.
        val escalating = contextFactory.escalatingFetcherOrNull(
            client = probeClient,
            sessionKey = ExtensionHttpClient.linkProbeSessionKey(host),
            purpose = app.xreader.data.extension.RenderPurpose.PROBE,
            permissions = { ExtensionPermissions(domains = listOf(host)) },
            rateLimiter = probeLimiter
        )
        val fetcher: app.xreader.data.extension.PageFetcher =
            escalating ?: probeClient
        val bookFetch = fetcher.get(url)
        val bookResponse = when (bookFetch) {
            is AppResult.Err -> return Outcome.Failed(
                "Xreader couldn't open that page. " +
                    (bookFetch.error.detail ?: "Check the link and your connection.")
            )
            is AppResult.Ok -> bookFetch.value
        }
        val bookUrl = bookResponse.finalUrl.ifBlank { url }
        val bookDoc = Jsoup.parse(bookResponse.body, bookUrl)
        if (bookResponse.status in setOf(401, 403) ||
            LinkInference.needsLogin(bookDoc, bookUrl)
        ) {
            return Outcome.LoginRequired(bookUrl, host)
        }
        if (bookResponse.status !in 200..299) {
            return Outcome.Failed(
                "The site answered with an error (HTTP ${bookResponse.status})."
            )
        }

        // Find one chapter page for the model to study: the heuristic
        // cluster knows the chapter links even when it can't read the
        // chapter text — otherwise take any chapter-numbered link.
        val chapterUrl = LinkInference.analyseBookPage(bookDoc, bookUrl)?.firstChapterUrl
            ?: findChapterLink(bookDoc, bookUrl)
            ?: return Outcome.Failed(
                "Even the AI needs one chapter page to study, and no chapter link " +
                    "could be found on that page."
            )
        val chapterFetch = fetcher.get(chapterUrl)
        val chapterResponse = when (chapterFetch) {
            is AppResult.Err -> return Outcome.Failed(
                "Xreader found a chapter link but couldn't open it. " +
                    (chapterFetch.error.detail ?: "")
            )
            is AppResult.Ok -> chapterFetch.value
        }
        if (chapterResponse.status !in 200..299) {
            return Outcome.Failed(
                "The chapter page answered with an error (HTTP ${chapterResponse.status})."
            )
        }
        val chapterDoc = Jsoup.parse(chapterResponse.body, chapterResponse.finalUrl)

        val config = when (
            val proposed = proposeConfig(bookDoc, chapterDoc, bookUrl, chapterUrl, host)
        ) {
            is AppResult.Err -> return Outcome.Failed(
                "The AI couldn't work this site out. " +
                    (proposed.error.detail ?: "It didn't return usable page rules.")
            )
            is AppResult.Ok -> proposed.value
        }

        // v1.0.6: when the model studied rendered pages, the
        // config it proposed is a browser-view source — mark it
        // before the drive, so the drive renders too and the
        // saved source keeps the marker (TR1).
        val drivenConfig = if (escalating?.renderedAny == true) {
            config.copy(needsRendering = true)
        } else {
            config
        }
        // The gate every candidate faces — no exceptions for AI.
        return when (val drive = prober.testDrive(drivenConfig, bookUrl)) {
            is AppResult.Err -> Outcome.Failed(
                "The AI suggested page rules, but the test read failed, so nothing " +
                    "was saved. ${(drive.error as? AppError.BadFile)?.reason ?: ""}"
            )
            is AppResult.Ok -> Outcome.Ready(
                LinkProber.Draft(
                    config = drivenConfig,
                    testDrive = drive.value,
                    bookUrl = bookUrl,
                    host = host
                )
            )
        }
    }

    /**
     * v1.0.6.2 (F5): the borderline-list verdict — asks the AI
     * whether a candidate source's sampled items read as books.
     * Returns null when the check can't run (feature off, no
     * key, the call fails, the answer is unclear): a check that
     * never aired must never block a source the deterministic
     * drive otherwise passed. Callers use at most one call per
     * run (the borderline band only).
     */
    suspend fun verifySample(
        sampleTitles: List<String>,
        textExcerpt: String
    ): Boolean? {
        if (!settingsProvider().enabled(AiFeature.SELECTOR_AI)) return null
        if (!client.hasKey()) return null
        if (sampleTitles.isEmpty()) return null
        val prompt = buildString {
            append("You are checking a web-novel site for a reader app. Below are ")
            append("the first items of the site's book list, and an excerpt of one ")
            append("item's text. Are these items BOOKS (novels), and is the excerpt ")
            append("a chapter of a novel? Answer with ONE word only: YES if they ")
            append("are books, NO if they look like social-media posts, comments ")
            append("or other non-book content.\n\nBOOK LIST ITEMS:\n")
            sampleTitles.take(8).forEach { append("- ").append(it).append('\n') }
            append("\nTEXT EXCERPT:\n")
            append(textExcerpt.take(600))
        }
        val result = client.generateText(prompt, temperature = 0.1f)
        val text = when (result) {
            is AppResult.Err -> return null
            is AppResult.Ok -> result.value.text
        }
        val answer = text.trim().uppercase()
        return when {
            answer.startsWith("YES") -> true
            answer.startsWith("NO") -> false
            else -> null
        }
    }

    private fun findChapterLink(doc: Document, pageUrl: String): String? =
        doc.select("a[href]").firstNotNullOfOrNull { anchor ->
            val text = anchor.text().trim()
            val href = anchor.attr("abs:href")
                .ifBlank { anchor.attr("href") }
            when {
                href.isBlank() -> null
                ChapterNumbers.parse(text) != null -> href
                href.contains("chapter", ignoreCase = true) -> href
                else -> null
            }
        }

    // -- the model call ---------------------------------------------------------

    private suspend fun proposeConfig(
        bookDoc: Document,
        chapterDoc: Document,
        bookUrl: String,
        chapterUrl: String,
        host: String
    ): AppResult<DeclarativeConfig> {
        val prompt = buildString {
            append("You are configuring a web-novel reader. Study the two HTML pages ")
            append("below and answer with ONE JSON object of CSS selectors (jsoup ")
            append("syntax) that lets the reader scrape this site:\n")
            append("{\n")
            append(" \"name\": \"the site's name\",\n")
            append(" \"details\": {\n")
            append("   \"title\": \"selector for the book title on the book page\",\n")
            append("   \"author\": \"selector or null\",\n")
            append("   \"description\": \"selector or null\",\n")
            append("   \"cover\": \"selector for the cover image (its src) or null\",\n")
            append("   \"chapters\": {\n")
            append("     \"list\": \"selector matching EVERY chapter row/link on the book page\",\n")
            append("     \"name\": \"selector for the chapter name inside a row, or @text\",\n")
            append("     \"link\": \"selector for the chapter link inside a row, or @href\",\n")
            append("     \"reverse\": true if the list shows the newest chapter first\n")
            append("   }\n")
            append(" },\n")
            append(" \"chapter\": {\n")
            append("   \"content\": \"selector for the element holding the chapter's story text\",\n")
            append("   \"nextPage\": \"selector for a 'next page' link inside multi-page chapters, or null\",\n")
            append("   \"remove\": [\"selectors of ad/nav elements inside the content, if any\"]\n")
            append(" }\n")
            append("}\n")
            append("Selectors may end with @attribute (e.g. \"img@src\", \"a@href\") to read ")
            append("an attribute instead of text. Prefer stable selectors (ids, semantic ")
            append("classes); avoid generated-looking class names. Answer with ONLY the JSON.\n\n")
            append("== BOOK PAGE (")
            append(bookUrl)
            append(") ==\n")
            append(cleanHtml(bookDoc))
            append("\n\n== CHAPTER PAGE (")
            append(chapterUrl)
            append(") ==\n")
            append(cleanHtml(chapterDoc))
        }
        val result = client.generateText(prompt, jsonMode = true, temperature = 0.1f)
        val text = when (result) {
            is AppResult.Err -> return result
            is AppResult.Ok -> result.value.text
        }
        val config = parseConfig(text, bookUrl, host)
            ?: return AppResult.Err(
                AppError.ProviderDown(
                    "AI source detector", retryable = false,
                    detail = "the model's answer didn't contain usable selectors"
                )
            )
        return AppResult.Ok(config)
    }

    companion object {
        const val MAX_HTML_CHARS = 24_000

        /** Strips scripts/styles and caps the page for the prompt. */
        fun cleanHtml(doc: Document): String {
            val clone = doc.clone()
            clone.select("script, style, noscript, svg, iframe").remove()
            return clone.body()?.html()?.take(MAX_HTML_CHARS) ?: ""
        }

        /** Model JSON → a validated [DeclarativeConfig] (null when
         * the essential selectors are missing or blank). */
        fun parseConfig(text: String, bookUrl: String, host: String): DeclarativeConfig? {
            val root = AiJson.extractObject(text) ?: return null
            val details = root["details"] as? kotlinx.serialization.json.JsonObject
                ?: return null
            val chapters = details["chapters"] as? kotlinx.serialization.json.JsonObject
                ?: return null
            val chapter = root["chapter"] as? kotlinx.serialization.json.JsonObject
                ?: return null
            val list = chapters.stringField("list") ?: return null
            val content = chapter.stringField("content") ?: return null
            val reverse = (chapters["reverse"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false
            val baseUrl = LinkProber.baseOf(bookUrl)
            return DeclarativeConfig(
                id = LinkProber.sourceIdFor(host, bookUrl) + "-ai",
                name = root.stringField("name") ?: host,
                lang = "en",
                baseUrl = baseUrl,
                details = DetailsSpec(
                    title = details.stringField("title"),
                    author = details.stringField("author"),
                    description = details.stringField("description"),
                    cover = details.stringField("cover"),
                    chapters = ChapterListSpec(
                        list = list,
                        name = chapters.stringField("name") ?: "@text",
                        link = chapters.stringField("link") ?: "@href",
                        reverse = reverse
                    )
                ),
                chapter = ChapterSpec(
                    content = content,
                    nextPage = chapter.stringField("nextPage"),
                    remove = chapter.stringListField("remove")
                )
            )
        }
    }
}
