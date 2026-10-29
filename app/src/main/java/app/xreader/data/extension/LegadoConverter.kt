package app.xreader.data.extension

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Legado (阅读) source conversion (v1.0.1 hotfix): a Legado book
 * source is a JSON object of CSS-ish rules — close enough to
 * Xreader's [DeclarativeConfig] that the pure-selector sources can
 * be converted mechanically. Sources that run code cannot: any
 * mapped rule carrying `<js>…</js>`, `@js:` or `java.` is REFUSED,
 * in plain words, rather than silently half-converted.
 *
 * Conversions and losses are both reported: [LegadoConversionResult
 * .Converted] carries the config plus human-readable notes for every
 * rule that was cleaned, dropped or approximated, and every
 * `##regex##replacement` suffix becomes a [CleanerRuleData] on the
 * config (the form DeclarativeConfig supports for replace rules).
 *
 * Selector conventions (see DeclarativeConfig): a selector is CSS,
 * optionally suffixed `@attr` to read an attribute — exactly
 * Legado's own suffix form, so `a@href` / `img@src` / `.t@text`
 * carry over unchanged once the `@css:` prefix is stripped.
 * Legado's `@`-chained form (`class.list@tag.a@text`) is rewritten
 * to the descendant form (`.list a@text`), and leading
 * `class.` / `id.` / `tag.` steps are translated to CSS.
 */

/** One raw Legado source entry, as listed by [LegadoConverter.parseLegadoFile]. */
data class LegadoSource(
    val name: String,
    val url: String,
    val jsonObject: JsonObject
)

sealed interface LegadoConversionResult {
    data class Converted(
        val config: DeclarativeConfig,
        val notes: List<String>
    ) : LegadoConversionResult

    data class Refused(val reason: String) : LegadoConversionResult
}

object LegadoConverter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── file level ──────────────────────────────────────────────────

    /**
     * Split a Legado file's text into its per-source raw entries so
     * the UI can report (and convert) source by source. Accepts the
     * usual top-level array, a single source object, or an object
     * wrapping the array. Never throws; unusable input is an
     * empty list.
     */
    fun parseLegadoFile(text: String): List<LegadoSource> {
        if (text.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return emptyList()
        val objects: List<JsonObject> = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> {
                val wrapped = listOf("sources", "bookSources", "items", "list")
                    .firstNotNullOfOrNull { key ->
                        (root[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
                            ?.takeIf { it.isNotEmpty() }
                    }
                wrapped ?: listOf(root)
            }
            else -> return emptyList()
        }
        return objects.map { obj ->
            LegadoSource(
                name = stringOf(obj, "bookSourceName") ?: stringOf(obj, "name") ?: "",
                url = stringOf(obj, "bookSourceUrl") ?: stringOf(obj, "url")
                    ?: stringOf(obj, "baseUrl") ?: "",
                jsonObject = obj
            )
        }
    }

    /** Convert every source in a Legado file, in file order. */
    fun convertAll(text: String): List<LegadoConversionResult> =
        parseLegadoFile(text).map { convert(it) }

    // ── one source ──────────────────────────────────────────────────

    fun convert(source: LegadoSource): LegadoConversionResult = convert(source.jsonObject)

    fun convert(source: JsonObject): LegadoConversionResult {
        val name = stringOf(source, "bookSourceName") ?: stringOf(source, "name")
        val baseUrl = stringOf(source, "bookSourceUrl") ?: stringOf(source, "url")
            ?: stringOf(source, "baseUrl")
        if (name.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            return LegadoConversionResult.Refused(
                "This Legado source is missing its name or its site address, " +
                    "so it can't be converted."
            )
        }
        // Refusal gate first: no part of a scripted source converts.
        scriptedField(source)?.let { field ->
            return LegadoConversionResult.Refused(
                "This Legado source uses script code in its '$field' rule " +
                    "(<js>, @js: or java.). Xreader's rule-based sources can't " +
                    "run code, so this source was not converted."
            )
        }
        return try {
            buildConfig(source, name.trim(), baseUrl.trim())
        } catch (e: Exception) {
            LegadoConversionResult.Refused(
                "This Legado source couldn't be converted " +
                    "(${e.message?.take(80) ?: "unexpected format"})."
            )
        }
    }

    private fun buildConfig(
        source: JsonObject,
        name: String,
        baseUrl: String
    ): LegadoConversionResult.Converted {
        val notes = mutableListOf<String>()
        val cleaners = mutableListOf<CleanerRuleData>()

        val ruleSearch = objectOf(source, "ruleSearch")
        val ruleBookInfo = objectOf(source, "ruleBookInfo")
        val ruleToc = objectOf(source, "ruleToc")
        val ruleContent = objectOf(source, "ruleContent")

        // ── search ──────────────────────────────────────────────────
        val searchUrl = cleanSearchUrl(stringOf(source, "searchUrl"), notes)
        val searchSpec: ListSpec? = when {
            searchUrl == null -> null
            ruleSearch == null -> {
                notes += "A search address was present but there were no search " +
                    "rules with it, so search was not converted."
                null
            }
            else -> {
                val results = cleanRule(
                    stringOf(ruleSearch, "bookList"), "search results", notes, cleaners
                )
                if (results == null) {
                    notes += "Search was not converted: the Legado source has " +
                        "no search-results rule."
                    null
                } else {
                    ListSpec(
                        url = searchUrl,
                        results = results,
                        title = cleanRule(
                            stringOf(ruleSearch, "name"), "search title", notes, cleaners
                        ) ?: "@text",
                        link = cleanRule(
                            stringOf(ruleSearch, "bookUrl"), "search link", notes, cleaners
                        ) ?: "@href",
                        cover = cleanRule(
                            stringOf(ruleSearch, "coverUrl"), "search cover", notes, cleaners
                        ),
                        author = cleanRule(
                            stringOf(ruleSearch, "author"), "search author", notes, cleaners
                        )
                    )
                }
            }
        }

        // ── book page + chapter list ────────────────────────────────
        if (ruleBookInfo != null && stringOf(ruleBookInfo, "tocUrl") != null) {
            notes += "The book page points to a separate chapter-list page " +
                "(tocUrl); Xreader reads the chapter list from the book page " +
                "itself, so that step was not converted."
        }
        val tocNext = ruleToc?.let {
            stringOf(it, "chapterUrlNext") ?: stringOf(it, "tocUrlNext")
                ?: stringOf(it, "nextTocUrl") ?: stringOf(it, "nextUrl")
        }
        if (tocNext != null) {
            notes += "Chapter-list paging (a next chapter-list page) isn't " +
                "supported by the rule format, so only the first chapter-list " +
                "page will be read."
        }
        val chaptersSpec = ChapterListSpec(
            list = cleanRule(
                ruleToc?.let { stringOf(it, "chapterList") },
                "chapter list", notes, cleaners
            ) ?: run {
                notes += "No chapter-list rule was found; a generic link list " +
                    "was used instead, which may need fixing by hand."
                "a"
            },
            name = cleanRule(
                ruleToc?.let { stringOf(it, "chapterName") },
                "chapter name", notes, cleaners
            ) ?: "@text",
            link = cleanRule(
                ruleToc?.let { stringOf(it, "chapterUrl") },
                "chapter link", notes, cleaners
            ) ?: "@href",
            reverse = ruleToc?.let {
                boolOf(it, "reverseToc") || boolOf(it, "isReverse") || boolOf(it, "reverse")
            } ?: false
        )
        val detailsSpec = DetailsSpec(
            title = cleanRule(
                ruleBookInfo?.let { stringOf(it, "name") }, "book title", notes, cleaners
            ),
            author = cleanRule(
                ruleBookInfo?.let { stringOf(it, "author") }, "book author", notes, cleaners
            ),
            description = cleanRule(
                ruleBookInfo?.let { stringOf(it, "intro") },
                "book description", notes, cleaners
            ),
            cover = cleanRule(
                ruleBookInfo?.let { stringOf(it, "coverUrl") }, "book cover", notes, cleaners
            ),
            chapters = chaptersSpec
        )

        // ── chapter page ────────────────────────────────────────────
        val nextContentRaw = ruleContent?.let {
            stringOf(it, "nextContentUrl") ?: stringOf(it, "nextUrl")
                ?: stringOf(it, "contentUrlNext")
        }
        val nextPageSpec = when {
            nextContentRaw == null -> null
            // A fixed address or template is not a page rule the
            // declarative engine can follow.
            nextContentRaw.startsWith("http") || nextContentRaw.contains("{{") -> {
                notes += "The next-page rule for chapters is a fixed address, " +
                    "not a page selector, so multi-page chapters were not converted."
                null
            }
            else -> cleanRule(nextContentRaw, "next chapter page", notes, cleaners)
        }
        val replaceRegex = ruleContent?.let { stringOf(it, "replaceRegex") }
        if (!replaceRegex.isNullOrBlank()) {
            cleaners += CleanerRuleData(pattern = replaceRegex, replacement = "")
            notes += "The chapter-text replace rule (replaceRegex) was kept " +
                "as a cleaner rule: '$replaceRegex' is removed from the text."
        }
        val chapterSpec = ChapterSpec(
            content = cleanRule(
                ruleContent?.let { stringOf(it, "content") },
                "chapter content", notes, cleaners
            ) ?: run {
                notes += "No chapter-content rule was found; the whole page " +
                    "body was used instead, which may need fixing by hand."
                "body"
            },
            title = cleanRule(
                ruleContent?.let { stringOf(it, "title") }, "chapter title", notes, cleaners
            ),
            nextPage = nextPageSpec
        )

        if (stringOf(source, "exploreUrl") != null || objectOf(source, "ruleExplore") != null) {
            notes += "Explore / discover rules were not converted; search " +
                "and reading still work."
        }

        val config = DeclarativeConfig(
            id = sourceId(name, baseUrl),
            name = name,
            lang = stringOf(source, "lang") ?: stringOf(source, "bookSourceLang") ?: "en",
            baseUrl = baseUrl,
            search = searchSpec,
            popular = null,
            latest = null,
            details = detailsSpec,
            chapter = chapterSpec,
            cleaners = cleaners.toList()
        )
        return LegadoConversionResult.Converted(config, notes.toList())
    }

    // ── refusal ─────────────────────────────────────────────────────

    /**
     * The label of the first mapped rule that runs code, or null.
     * Mapped rules = the search address plus every string inside
     * ruleSearch / ruleBookInfo / ruleToc / ruleContent.
     */
    private fun scriptedField(source: JsonObject): String? {
        stringOf(source, "searchUrl")?.let { value ->
            if (isScripted(value)) return "searchUrl"
        }
        for (section in listOf("ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent")) {
            val obj = objectOf(source, section) ?: continue
            for ((key, element) in obj) {
                val hit = findScripted(element)
                if (hit) return "$section.$key"
            }
        }
        return null
    }

    private fun findScripted(element: JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull?.let { isScripted(it) } ?: false
        is JsonObject -> element.values.any { findScripted(it) }
        is JsonArray -> element.any { findScripted(it) }
        else -> false
    }

    private fun isScripted(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("<js") || lower.contains("@js:") || lower.contains("java.")
    }

    // ── search URL ──────────────────────────────────────────────────

    /**
     * Legado search addresses carry options after the URL itself —
     * `,{…}` request options or a `<…>` block — and write the query
     * as `{{key}}` / `{{keyword}}`. Strip the suffixes; rewrite the
     * placeholders to ListSpec's `{query}` / `{page}` convention.
     */
    private fun cleanSearchUrl(raw: String?, notes: MutableList<String>): String? {
        if (raw.isNullOrBlank()) return null
        var url = raw.trim()
        val original = url
        val angle = url.indexOf('<')
        if (angle >= 0) url = url.substring(0, angle).trim()
        val options = url.indexOf(",{")
        if (options >= 0) url = url.substring(0, options).trim()
        url = url.trimEnd(',').trim()
        if (url != original) {
            notes += "The search address carried extra Legado options after " +
                "the address itself; they were removed."
        }
        if (url.isBlank()) return null
        url = url.replace(
            Regex("\\{\\{\\s*(key|keyword|searchKey|search_key)\\s*\\}\\}", RegexOption.IGNORE_CASE),
            "{query}"
        )
        url = url.replace(
            Regex("\\{\\{\\s*page\\s*\\}\\}", RegexOption.IGNORE_CASE), "{page}"
        )
        // Single-brace Legado spellings, without touching {query}/{page}.
        url = url.replace("{keyword}", "{query}").replace("{key}", "{query}")
        return url
    }

    // ── selector cleaning ───────────────────────────────────────────

    /**
     * One Legado rule value → a Declarative selector, or null when
     * the value is blank / carries no selector. A `##regex##repl`
     * suffix is split off into [cleaners] (with a note) first.
     */
    private fun cleanRule(
        raw: String?,
        label: String,
        notes: MutableList<String>,
        cleaners: MutableList<CleanerRuleData>
    ): String? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("##")
        val selectorPart = parts[0].trim()
        if (parts.size >= 2) {
            val pattern = parts[1]
            val replacement = if (parts.size >= 3) {
                parts.drop(2).joinToString("##")
            } else {
                ""
            }
            if (pattern.isNotBlank()) {
                cleaners += CleanerRuleData(pattern = pattern, replacement = replacement)
                notes += "A '##' replace rule on the $label rule was kept as a " +
                    "cleaner rule: '$pattern' is replaced with '$replacement'."
            }
        }
        if (selectorPart.isBlank()) return null
        return cleanSelector(selectorPart, label, notes)
    }

    private fun cleanSelector(rawSelector: String, label: String, notes: MutableList<String>): String {
        var s = rawSelector.trim()
        var cssMode = false
        when {
            s.startsWith("@css:") -> {
                s = s.removePrefix("@css:").trim()
                cssMode = true
            }
            s.startsWith("css:") -> {
                s = s.removePrefix("css:").trim()
                cssMode = true
            }
        }
        if (s.startsWith("@xpath:") || s.startsWith("//")) {
            notes += "The $label rule looks like XPath; Xreader uses CSS " +
                "selectors, so it may need fixing by hand."
            return s.removePrefix("@xpath:")
        }
        if (s.contains("||")) {
            s = s.split("||").first().trim()
            notes += "The $label rule offered more than one selector (||); " +
                "only the first was kept."
            if (s.startsWith("@css:")) {
                s = s.removePrefix("@css:").trim()
                cssMode = true
            }
        }
        if (s.contains("&&")) {
            s = s.split("&&").joinToString(" ") { it.trim() }
            notes += "The $label rule chained steps with '&&'; they were " +
                "kept as a descendant selector."
        }
        if (!s.contains('@')) return translateSelectorTokens(s)

        // `@attr` suffix: DeclarativeConfig uses the same form, so
        // the CSS part is cleaned and the attribute carried over.
        // Extra `@` steps (Legado's chained form, non-CSS mode) are
        // descendant steps and become spaces.
        val lastAt = s.lastIndexOf('@')
        val tail = s.substring(lastAt + 1).trim()
        val head = s.substring(0, lastAt).trim()
        val tailIsAttr = tail.matches(ATTR_NAME)
        if (tailIsAttr && head.isBlank()) return "@$tail"
        if (tailIsAttr) {
            val css = if (!cssMode && head.contains('@')) {
                head.split('@').joinToString(" ") { translateSelectorTokens(it.trim()) }
            } else {
                translateSelectorTokens(head)
            }
            return if (css.isBlank()) "@$tail" else "$css@$tail"
        }
        // '@' that isn't an attribute suffix: a chain separator.
        return s.split('@').joinToString(" ") { translateSelectorTokens(it.trim()) }
    }

    /** Translate Legado's `class.` / `id.` / `tag.` steps to CSS,
     * token by whitespace-separated token; plain CSS passes through. */
    private fun translateSelectorTokens(selector: String): String =
        selector.split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { translateToken(it) }

    private fun translateToken(token: String): String = when {
        token.startsWith("class.") -> {
            val rest = token.removePrefix("class.").replace(Regex("\\.\\d+$"), "")
            ".$rest"
        }
        token.startsWith("id.") -> {
            val rest = token.removePrefix("id.").replace(Regex("\\.\\d+$"), "")
            "#$rest"
        }
        token.startsWith("tag.") -> token.removePrefix("tag.").replace(Regex("\\.\\d+$"), "")
        else -> token
    }

    // ── small helpers ───────────────────────────────────────────────

    private fun sourceId(name: String, baseUrl: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
            .ifBlank {
                baseUrl.normaliseHost()
                    .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
                    .ifBlank { "source" }
            }
        val hash = Hashes.sha256Hex(baseUrl).take(6)
        return "legado-$slug-$hash"
    }

    private fun stringOf(obj: JsonObject, key: String): String? {
        val primitive = obj[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.ifBlank { null }
    }

    private fun objectOf(obj: JsonObject, key: String): JsonObject? = obj[key] as? JsonObject

    private fun boolOf(obj: JsonObject, key: String): Boolean {
        val primitive = obj[key] as? JsonPrimitive ?: return false
        return primitive.contentOrNull?.toBooleanStrictOrNull() ?: false
    }

    private val ATTR_NAME = Regex("[A-Za-z][A-Za-z0-9_-]*")
}
