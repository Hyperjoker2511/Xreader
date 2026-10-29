package app.xreader.domain.export

/**
 * Markdown export of a book's highlights + notes (PLAN §8),
 * Obsidian/Notion-flavoured: YAML front-matter up top, one section
 * per chapter, quotes as `[!quote]` callouts and notes as
 * `[!note]` callouts. Pure formatter — the golden tests pin the
 * exact shape, and any editor that reads Markdown gets a clean file.
 */
object HighlightsMarkdown {

    data class Item(
        val chapterIndex: Int,
        val chapterTitle: String,
        val quotedText: String,
        val note: String? = null,
        val color: String = "amber"
    )

    fun format(
        bookTitle: String,
        author: String,
        items: List<Item>,
        exportedDate: String
    ): String {
        val builder = StringBuilder()
        builder.append("---\n")
        builder.append("title: \"${yamlEscape(bookTitle)}\"\n")
        if (author.isNotBlank()) {
            builder.append("author: \"${yamlEscape(author)}\"\n")
        }
        builder.append("source: Xreader highlights\n")
        builder.append("exported: $exportedDate\n")
        builder.append("highlights: ${items.size}\n")
        builder.append("---\n\n")
        builder.append("# Highlights — $bookTitle\n\n")
        if (items.isEmpty()) {
            builder.append("_No highlights in this book yet._\n")
            return builder.toString()
        }
        val byChapter = items.sortedBy { it.chapterIndex }
            .groupBy { it.chapterIndex to it.chapterTitle }
        for ((chapter, chapterItems) in byChapter) {
            val (index, title) = chapter
            builder.append("## Chapter ${index + 1}")
            if (title.isNotBlank() && title != "Chapter ${index + 1}") {
                builder.append(" — $title")
            }
            builder.append("\n\n")
            for (item in chapterItems) {
                builder.append("> [!quote]\n")
                for (line in item.quotedText.lines()) {
                    builder.append("> $line\n")
                }
                val note = item.note
                if (!note.isNullOrBlank()) {
                    builder.append(">\n> [!note]\n")
                    for (line in note.lines()) {
                        builder.append("> $line\n")
                    }
                }
                builder.append("\n")
            }
        }
        return builder.toString()
    }

    private fun yamlEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
