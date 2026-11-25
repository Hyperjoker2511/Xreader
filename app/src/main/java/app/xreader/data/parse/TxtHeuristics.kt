package app.xreader.data.parse

import app.xreader.data.prefs.TxtLanguage

/**
 * MR-6 Items 2–3 (SPEC-MR6-TXT-2026-11-25 §1.3): Moon+'s two
 * heuristic TXT chapter detectors, re-implemented in kind from the
 * REA description of `getTxtChapters1` (A.java:4846-5053) and
 * `getTxtChapters2` (A.java:5155-6430). The decompiled bodies are
 * mangled past transliteration, so these are re-implementations —
 * the fixtures in `TxtChapterDetectionTest` are the acceptance bar,
 * exactly as the spec's fidelity notes state.
 */
object TxtHeuristics {

    // ------------------------------------------------------------------
    // Shared Moon+ helpers (A.java:6672-6730 in kind)
    // ------------------------------------------------------------------

    /** `chapterLengthOk` (A.java:6715): under 3 chars never; under
     * 40 always; under 80 only when the title closes with a bracket. */
    fun chapterLengthOk(title: String): Boolean = when {
        title.length < 3 -> false
        title.length < 40 -> true
        title.length < 80 -> title.endsWith("]") || title.endsWith(")") || title.endsWith("）")
        else -> false
    }

    /** `chineseChapterTagOk` (A.java:6672): a 第 with a chapter tag
     * （卷/章/回/集/話/節/节/折） within the 9 chars after it, and the
     * title either short (≤ 18) or spaced near the 第. */
    fun chineseChapterTagOk(title: String): Boolean {
        val at = title.indexOf('第')
        if (at < 0) return false
        val after = title.substring(at + 1, minOf(title.length, at + 1 + 9))
        if (after.none { it in "卷章回集話節节折" }) return false
        if (title.length <= 18) return true
        val near = title.substring(at + 1, minOf(title.length, at + 1 + 15))
        return ' ' in near || '　' in near
    }

    /** `isChapterTitleBothQuotes` (A.java:6431): the title opens
     * with an initial-quote / start punctuation char and closes
     * with the matching final kind. */
    fun isChapterTitleBothQuotes(title: String): Boolean {
        if (title.length < 2) return false
        val first = Character.getType(title.first().code)
        val last = Character.getType(title.last().code)
        val opens = first == Character.START_PUNCTUATION.toInt() ||
            first == Character.INITIAL_QUOTE_PUNCTUATION.toInt()
        val closes = last == Character.END_PUNCTUATION.toInt() ||
            last == Character.FINAL_QUOTE_PUNCTUATION.toInt()
        return opens && closes
    }

    // ------------------------------------------------------------------
    // Detector 1 — line shape (getTxtChapters1 in kind)
    // ------------------------------------------------------------------

    /**
     * Short isolated lines and keyword lines as candidate headings.
     * Length caps: 80 chars for Asian languages, 100 otherwise
     * (A.java:4855). CJK candidates must pass the 第…tag rules;
     * other languages match their keyword （第 / Глава / chapter)
     * near the line start; plain short titles count when the line
     * stands isolated between blank lines (Item 2's proof shape).
     */
    fun detector1(
        text: String,
        lines: List<TxtLine>,
        language: TxtLanguage
    ): List<DetectedChapter> {
        val asian = language == TxtLanguage.CN
        val cap = if (asian) 80 else 100
        val keyword = when (language) {
            TxtLanguage.CN -> "第"
            TxtLanguage.RU -> "Глава"
            TxtLanguage.EN -> "chapter"
        }
        val found = mutableListOf<DetectedChapter>()
        for (i in lines.indices) {
            val line = lines[i]
            val raw = text.substring(line.start, line.end)
            val title = raw.trim()
            if (title.isEmpty()) continue
            val candidate = if (asian) {
                chapterLengthOk(title) && chineseChapterTagOk(title)
            } else {
                val at = title.lowercase().indexOf(keyword.lowercase())
                val keywordHit = at >= 0 && title.length > keyword.length &&
                    title.length < cap && at <= title.length / 4
                keywordHit || (isIsolated(lines, text, i) && plainTitleShape(title, cap))
            }
            if (candidate) {
                found += DetectedChapter(title, line.start, line.endWithNewline)
            }
        }
        return postFilter1(found, lines, text)
    }

    /** A plain (keyword-less) title shape: short, and not ending
     * like a sentence. */
    private fun plainTitleShape(title: String, cap: Int): Boolean =
        title.length in 2 until cap && title.last() !in SENTENCE_ENDERS

    /** Detector 1's post-filters (A.java:4846-5053 in kind):
     * fewer than 3 candidates is no book at all; over 10, the
     * minority shape (indent-first or punctuation-ending, under a
     * fifth of the list) is removed; chapters closer than 100
     * chars lose the earlier one; a stray head entry before a
     * 第一… second entry is dropped. */
    private fun postFilter1(
        found: MutableList<DetectedChapter>,
        lines: List<TxtLine>,
        text: String
    ): List<DetectedChapter> {
        if (found.size < 3) return emptyList()
        var list = found
        if (list.size > 10) {
            val indentFirst = list.count { chapter ->
                val line = lines.lineContaining(chapter.lineStart)
                line != null && line.start < text.length &&
                    (text[line.start] == ' ' || text[line.start] == '\t' || text[line.start] == '　')
            }
            if (indentFirst in 1 until list.size / 5) {
                list = list.filterTo(mutableListOf()) { chapter ->
                    val line = lines.lineContaining(chapter.lineStart)
                    !(line != null && line.start < text.length &&
                        (text[line.start] == ' ' || text[line.start] == '\t' || text[line.start] == '　'))
                }
            }
            val punctEnd = list.count { it.title.lastOrNull() in SENTENCE_ENDERS }
            if (punctEnd in 1 until list.size / 5) {
                list = list.filterTo(mutableListOf()) {
                    it.title.lastOrNull() !in SENTENCE_ENDERS
                }
            }
        }
        // Adjacent chapters < 100 chars apart: the earlier goes.
        val spaced = mutableListOf<DetectedChapter>()
        for (chapter in list) {
            val previous = spaced.lastOrNull()
            if (previous != null && chapter.lineStart - previous.lineStart < 100) {
                spaced[spaced.lastIndex] = chapter
            } else {
                spaced += chapter
            }
        }
        if (spaced.size >= 2 &&
            spaced[1].title.startsWith("第一") &&
            !spaced[0].title.startsWith("第一")
        ) {
            spaced.removeAt(0)
        }
        return spaced
    }

    // ------------------------------------------------------------------
    // Detector 2 — Chinese-oriented, five candidate lists
    // (getTxtChapters2 in kind)
    // ------------------------------------------------------------------

    data class Detector2Result(
        val chapters: List<DetectedChapter>,
        /** `txtChapterType` (A.java:928): which candidate list won, 1–5. */
        val type: Int
    )

    fun detector2(text: String, lines: List<TxtLine>): Detector2Result {
        val list1 = mutableListOf<DetectedChapter>()
        val list2 = mutableListOf<DetectedChapter>()
        val list3 = mutableListOf<DetectedChapter>()
        val list4 = mutableListOf<DetectedChapter>()
        val list5 = mutableListOf<DetectedChapter>()
        val hashSplit = text.contains("###")
        for (i in lines.indices) {
            val line = lines[i]
            val title = text.substring(line.start, line.end).trim()
            if (title.isEmpty()) continue
            val chapter = DetectedChapter(title, line.start, line.endWithNewline)
            if (chapterLengthOk(title) && chineseChapterTagOk(title)) list1 += chapter
            if (looseNumberedShape(title)) list2 += chapter
            if (isIsolated(lines, text, i) && title.length in 2..80 &&
                isChapterTitleBothQuotes(title)
            ) {
                list3 += chapter
            }
            if (BARE_NUMERAL.matches(title)) list4 += chapter
            if (isIsolated(lines, text, i) &&
                title.length in 2..(if (hashSplit) 80 else 40) &&
                title.last() !in SENTENCE_ENDERS
            ) {
                list5 += chapter
            }
        }

        // Winner selection — the readable skeleton of
        // A.java:5813/6426 in kind.
        var winner = list1
        var type = 1
        if (list1.size > 10 && list2.size >= list1.size) {
            winner = list2; type = 2
        }
        if ((winner.size >= 4 && list3.size > 4) || list3.size > 2 * winner.size) {
            winner = list3; type = 3
        }
        if (list4.size > winner.size && list4.size > 20) {
            winner = list4; type = 4
        }
        if (winner.size < 4) return Detector2Result(emptyList(), type)
        if ((winner.size >= 10 && list5.size > 10) || list5.size > 4 * winner.size) {
            winner = list5; type = 5
        }
        var result: List<DetectedChapter> = winner
        if (type == 3 && result.size > 10) {
            val punctEnd = result.count { it.title.lastOrNull() in SENTENCE_ENDERS }
            if (punctEnd in 1 until result.size / 5) {
                result = result.filter { it.title.lastOrNull() !in SENTENCE_ENDERS }
            }
        }
        return Detector2Result(clearNoTagChapter(result), type)
    }

    /** The looser numbered shape: 第, a numeral shortly after, then
     * a chapter tag — without detector 1's length/tag strictness. */
    private fun looseNumberedShape(title: String): Boolean {
        if (title.length !in 2 until 80) return false
        val at = title.indexOf('第')
        if (at < 0) return false
        val after = title.substring(at + 1, minOf(title.length, at + 1 + 8))
        val numeralEnd = after.indexOfFirst { it !in NUMERAL_CHARS && !it.isDigit() }
        val numerals = if (numeralEnd < 0) after else after.substring(0, numeralEnd)
        if (numerals.isEmpty()) return false
        val rest = after.substring(numerals.length)
        return rest.isNotEmpty() && rest.first() in "章节節卷回集話话折篇幕"
    }

    /** `clearNoTagChapter` (A.java:~6440) in kind: unless the list
     * is small or untagged entries are common, entries past the
     * second that carry neither the 第 keyword nor 番外 go. */
    private fun clearNoTagChapter(list: List<DetectedChapter>): List<DetectedChapter> {
        if (list.size <= 1) return list
        val untagged = list.take(100).count { '第' !in it.title }
        val keep = (list.size < 100 || untagged >= 20) && (list.size <= 20 || untagged > 4)
        if (keep) return list
        return list.filterIndexed { index, chapter ->
            index <= 1 || '第' in chapter.title || "番外" in chapter.title
        }
    }

    // ------------------------------------------------------------------

    /** A line standing alone: blank (or edge) before and after. */
    private fun isIsolated(lines: List<TxtLine>, text: String, index: Int): Boolean {
        fun blank(line: TxtLine): Boolean =
            text.substring(line.start, line.end).isBlank()
        val before = index == 0 || blank(lines[index - 1])
        val after = index == lines.lastIndex || blank(lines[index + 1])
        return before && after
    }

    private val SENTENCE_ENDERS = setOf(
        '.', ',', ';', ':', '!', '?', '。', '；', '：', '！', '？', '、', '…'
    )

    private const val NUMERAL_CHARS = "〇零一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟"

    private val BARE_NUMERAL = Regex("^[0-9〇零一二三四五六七八九十百千]+[、.．]?$")
}
