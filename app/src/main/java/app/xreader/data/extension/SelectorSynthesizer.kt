package app.xreader.data.extension

import org.jsoup.nodes.Element

/**
 * Builds stable CSS selectors for elements found by the link
 * inference heuristics (Amendment 7). Strategy, in order: a stable
 * `#id` wins; otherwise a path of tag + up to two stable classes,
 * with `:nth-of-type` only where siblings would be ambiguous. Class
 * names that look machine-generated (long, digit-heavy, hash-like)
 * are treated as unstable and skipped — selectors must survive the
 * site re-rendering the same page tomorrow.
 */
object SelectorSynthesizer {

    fun forElement(element: Element): String = build(element, listMode = false)

    /**
     * A REPEATING selector for a list item (v1.0.5, A2 — D18):
     * the selector must match EVERY row of the list, on every
     * page of the site, so nothing in it may name one specific
     * row. Differences from [forElement]: the item's own segment
     * never carries `:nth-of-type` or an `#id` (ids are unique
     * per row), and ancestor segments never carry `:nth-of-type`
     * either (a container's position shifts between pages — the
     * "last chapters" block and the full list trade places).
     * Ancestors still anchor on stable ids/classes exactly as
     * [forElement] does. Callers used to fake this by regex-
     * stripping `:nth-of-type` out of [forElement]'s output; the
     * list mode is that rule built in and unit-tested.
     */
    fun forList(element: Element): String = build(element, listMode = true)

    private fun build(element: Element, listMode: Boolean): String {
        val segments = mutableListOf<String>()
        var current: Element? = element
        var depth = 0
        while (current != null && depth < 7) {
            val el = current
            val tag = el.tagName()
            if (tag == "html") break
            if (tag == "body") {
                segments += "body"
                break
            }
            val isItem = depth == 0
            val id = el.id()
            if (id.isNotBlank() && isStableId(id) && !(listMode && isItem)) {
                segments += "$tag#$id"
                break
            }
            var segment = tag
            val classes = stableClasses(el).take(2)
            if (classes.isNotEmpty()) {
                segment += classes.joinToString("") { ".$it" }
            }
            if (!listMode) {
                val parent = el.parent()
                if (parent != null) {
                    val siblings = parent.children().filter { sibling ->
                        sibling.tagName() == tag &&
                            stableClasses(sibling).take(2) == classes
                    }
                    if (siblings.size > 1) {
                        val index = parent.children()
                            .filter { it.tagName() == tag }
                            .indexOf(el) + 1
                        if (index > 0) segment += ":nth-of-type($index)"
                    }
                }
            }
            segments += segment
            current = el.parent()
            depth++
        }
        return segments.asReversed().joinToString(" > ")
    }

    fun stableClasses(element: Element): List<String> =
        element.classNames().filter { isStableClass(it) }

    fun isStableId(id: String): Boolean =
        id.length in 2..40 && !id.any { it.isDigit() && id.indexOf(it) > id.length / 2 } &&
            !id.matches(Regex(".*\\d{5,}.*")) &&
            id.matches(Regex("[A-Za-z][A-Za-z0-9_-]*"))

    fun isStableClass(name: String): Boolean {
        if (name.length !in 2..32) return false
        if (!name.matches(Regex("[A-Za-z_-][A-Za-z0-9_-]*"))) return false
        // Hash-like / generated names: long digit runs or mixed-case gibberish.
        if (name.matches(Regex(".*\\d{4,}.*"))) return false
        if (name.length > 12 && name.count { it.isUpperCase() } >= 3 &&
            name.count { it.isLowerCase() } >= 3 && !name.contains('-') && !name.contains('_')
        ) return false
        return true
    }
}
