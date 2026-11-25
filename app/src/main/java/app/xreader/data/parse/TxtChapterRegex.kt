package app.xreader.data.parse

import app.xreader.data.prefs.TxtLanguage
import app.xreader.data.prefs.TxtPrefs
import java.util.regex.Pattern

/**
 * MR-6 Item 1 (SPEC-MR6-TXT-2026-11-25 §1.3): Moon+'s per-language
 * TXT chapter regexes, copied character-for-character from the
 * decompiled constants at A.java:313-315 (`TXT_REG_CN`,
 * `TXT_REG_EN`, `TXT_REG_RU`), plus the user-editable override,
 * the max title length (`TOC_MAX_LEN`, default 80) and the
 * trailing-char blacklist (`TOC_REG_REMOVE`,
 * default `",.;:，。；：、"`).
 *
 * Fail-closed rule — Xreader's own improvement, labelled as such
 * (the spec's Item 1): a bad user regex falls back to the built-in
 * default for that language and never crashes the import. Moon+
 * wraps regex detection in try/catch and yields an EMPTY chapter
 * list on a bad pattern (A.java:4747-4753); Xreader never does.
 */
object TxtChapterRegex {

    // A.java:314 — TXT_REG_EN, verbatim.
    const val DEFAULT_EN: String =
        """(?<=[\u3000\s])(?:Chapter|CHAPTER|Section|SECTION|Part|PART|Episode|EPISODE).{0,30}$"""

    // A.java:315 — TXT_REG_RU, verbatim (EN + Глава).
    const val DEFAULT_RU: String =
        """(?<=[\u3000\s])(?:Chapter|CHAPTER|Section|SECTION|Part|PART|Episode|EPISODE|Глава).{0,30}$"""

    // A.java:313 — TXT_REG_CN, verbatim (incl. 两 in the numeral
    // class and the 正文(?!完|结) / 节(?!课) / 集(?![合和])
    // negative lookaheads).
    const val DEFAULT_CN: String =
        """(?<=[\u3000\s])(?:序章|楔子|正文(?!完|结)|终章|后记|尾声|番外|第\s{0,4}[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+?\s{0,4}(?:章|节(?!课)|卷|集(?![合和]))).{0,30}$"""

    fun defaultFor(language: TxtLanguage): String = when (language) {
        TxtLanguage.EN -> DEFAULT_EN
        TxtLanguage.CN -> DEFAULT_CN
        TxtLanguage.RU -> DEFAULT_RU
    }

    /**
     * Moon+'s `getTxtRegRule` language choice (A.java:4728-4743):
     * CN when the text is Chinese or Japanese, RU when Russian,
     * else EN. Decided on a sample of the decoded text by script
     * counts — Han/Hiragana/Katakana vs Cyrillic.
     */
    fun languageOf(text: String): TxtLanguage {
        val sample = if (text.length > SAMPLE_CHARS) text.substring(0, SAMPLE_CHARS) else text
        var cjk = 0
        var cyrillic = 0
        for (ch in sample) {
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ->
                    cjk++
                Character.UnicodeBlock.CYRILLIC,
                Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY ->
                    cyrillic++
                else -> Unit
            }
            if (cjk >= SCRIPT_THRESHOLD && cjk >= cyrillic) return TxtLanguage.CN
            if (cyrillic >= SCRIPT_THRESHOLD && cyrillic > cjk) return TxtLanguage.RU
        }
        return when {
            cjk >= SCRIPT_THRESHOLD && cjk >= cyrillic -> TxtLanguage.CN
            cyrillic >= SCRIPT_THRESHOLD -> TxtLanguage.RU
            else -> TxtLanguage.EN
        }
    }

    /** Compiles [rule] the way Moon+ does — `Pattern.compile(rule, 8)`
     * (8 = MULTILINE, A.java:4797-4825). Null when the rule is bad. */
    fun compile(rule: String): Pattern? = try {
        Pattern.compile(rule, Pattern.MULTILINE)
    } catch (e: Exception) {
        null
    }

    /**
     * Regex-mode detection (Moon+'s `getTxtChaptersReg` in kind):
     * every match whose trimmed title is non-empty, shorter than
     * the max title length, does not end with a blacklisted char,
     * and is not a repeat of the previous kept title becomes a
     * chapter boundary at its line.
     */
    fun detect(
        text: String,
        lines: List<TxtLine>,
        language: TxtLanguage,
        settings: TxtPrefs.State
    ): List<DetectedChapter> {
        val rule = settings.regexOverride(language) ?: defaultFor(language)
        val pattern = compile(rule)
            ?: compile(defaultFor(language))
            ?: return emptyList()
        val matcher = pattern.matcher(text)
        val found = mutableListOf<DetectedChapter>()
        var lastTitle: String? = null
        while (matcher.find()) {
            val title = trimChapterTitle(matcher.group())
            if (title.isEmpty()) continue
            if (title.length >= settings.maxTitleLength) continue
            if (title.last() in settings.titleBlacklist) continue
            if (title == lastTitle) continue
            val line = lines.lineContaining(matcher.start()) ?: continue
            found += DetectedChapter(
                title = title,
                lineStart = line.start,
                bodyStart = line.endWithNewline
            )
            lastTitle = title
        }
        return found
    }

    /**
     * Moon+'s `trimChapterTitle1` (A.java:6687) in kind: indent
     * chars and tabs become spaces, leading blanks are stripped,
     * the title is cut at the first newline past index 3, trimmed.
     */
    fun trimChapterTitle(raw: String): String {
        var title = raw.replace('\t', ' ').replace('　', ' ')
        title = title.trimStart(' ')
        val newline = title.indexOf('\n', startIndex = 3)
        if (newline >= 0) title = title.substring(0, newline)
        return title.trim()
    }

    private const val SAMPLE_CHARS = 32_768
    private const val SCRIPT_THRESHOLD = 20
}

/** One physical line of decoded text: [start] is the char offset of
 * its first character, [end] the offset just past its last content
 * char (before the newline), [endWithNewline] just past the
 * newline (== the next line's start, or the text length). */
data class TxtLine(val start: Int, val end: Int, val endWithNewline: Int)

/** Splits [text] into physical lines on '\n' (a trailing '\r' is
 * treated as content-free and excluded from [TxtLine.end]). */
fun splitTxtLines(text: String): List<TxtLine> {
    val lines = ArrayList<TxtLine>()
    var start = 0
    var i = 0
    while (i < text.length) {
        if (text[i] == '\n') {
            var end = i
            if (end > start && text[end - 1] == '\r') end--
            lines += TxtLine(start, end, i + 1)
            start = i + 1
        }
        i++
    }
    if (start < text.length) {
        var end = text.length
        if (end > start && text[end - 1] == '\r') end--
        lines += TxtLine(start, end, text.length)
    } else if (start == text.length && text.isNotEmpty()) {
        // Text ends with a newline: no trailing empty line.
    }
    return lines
}

/** The line whose [TxtLine.start]..[TxtLine.endWithNewline] span
 * contains [offset]; lines are in ascending order. */
fun List<TxtLine>.lineContaining(offset: Int): TxtLine? {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val line = this[mid]
        when {
            offset < line.start -> hi = mid - 1
            offset >= line.endWithNewline -> lo = mid + 1
            else -> return line
        }
    }
    return null
}
