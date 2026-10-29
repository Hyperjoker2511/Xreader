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

    fun forElement(element: Element): String {
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
            val id = el.id()
            if (id.isNotBlank() && isStableId(id)) {
                segments += "$tag#$id"
                break
            }
            var segment = tag
            val classes = stableClasses(el).take(2)
            if (classes.isNotEmpty()) {
                segment += classes.joinToString("") { ".$it" }
            }
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
