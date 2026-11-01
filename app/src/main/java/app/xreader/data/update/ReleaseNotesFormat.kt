package app.xreader.data.update

/**
 * Markdown-lite shaping for the in-app What's New screen
 * (v1.3.5): the full release body, rendered INSIDE the app —
 * no WebView, no browser. Deliberately small: headings,
 * bullets, numbered lines and paragraphs survive; everything
 * else (images, HTML, tables) degrades to plain text rather
 * than breaking the screen.
 */
sealed interface NoteLine {
    data class Heading(val text: String, val level: Int) : NoteLine
    data class Bullet(val text: String) : NoteLine
    data class Paragraph(val text: String) : NoteLine
    data object Gap : NoteLine
}

object ReleaseNotesFormat {

    fun lines(body: String): List<NoteLine> {
        val out = mutableListOf<NoteLine>()
        for (raw in body.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {
                    if (out.isNotEmpty() && out.last() != NoteLine.Gap) {
                        out += NoteLine.Gap
                    }
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    val text = line.drop(level).trim()
                    if (text.isNotEmpty()) out += NoteLine.Heading(clean(text), level)
                }
                line.startsWith("- ") || line.startsWith("* ") ||
                    line.startsWith("• ") ->
                    out += NoteLine.Bullet(clean(line.drop(2)))
                line == "---" || line == "***" -> out += NoteLine.Gap
                else -> out += NoteLine.Paragraph(clean(line))
            }
        }
        while (out.firstOrNull() == NoteLine.Gap) out.removeAt(0)
        while (out.lastOrNull() == NoteLine.Gap) out.removeAt(out.lastIndex)
        return out
    }

    /** Inline cleanup: links keep their words, emphasis and
     * code markers go, image syntax drops out whole. */
    private fun clean(text: String): String =
        text.replace(Regex("""!\[[^\]]*\]\([^)]*\)"""), "")
            .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .trim()
}
