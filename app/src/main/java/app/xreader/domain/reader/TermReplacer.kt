package app.xreader.domain.reader

import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.ChapterContent
import kotlinx.serialization.Serializable

/**
 * One Reader Term (P0, Reader Terms): wherever [original] appears
 * in a book's text, the reader shows [replacement] instead. Terms
 * are applied at DISPLAY time only — the stored chapter text is
 * never modified, and removing a term restores the original view
 * exactly.
 *
 * Matching rules (pinned by TermReplacerTest):
 *  - Case-sensitive unless [matchCase] is off.
 *  - Whole-word unless [wholeWord] is off: a match is rejected
 *    when the character touching either end is a "word character"
 *    (a letter, digit or underscore) — with one exception below.
 *  - Scripts without spaces (Han, Hiragana, Katakana, Hangul)
 *    have no word boundaries to honour: a pair whose [original]
 *    contains such a character always matches as exact text, and
 *    such a character next to a match never blocks it (it is a
 *    complete morpheme, not part of the neighbouring word). This
 *    is what makes CJK names findable inside untranslated text.
 */
@Serializable
data class TermPair(
    val original: String,
    val replacement: String,
    val matchCase: Boolean = true,
    val wholeWord: Boolean = true
)

/**
 * The term replacement engine (P0, Reader Terms) — a pure,
 * dependency-free single pass over the text:
 *
 *  - One left-to-right scan; at each position the LONGEST
 *    matching original wins (so "Lin Dong" beats "Lin").
 *  - The output is never re-scanned, so replacements never
 *    cascade (A→B and B→C turns A into B, never into C).
 *  - Pairs are bucketed by first character, so chapter-sized
 *    text with a realistic pair list costs one pass plus a
 *    small bucket check per position — no regex per pair.
 *
 * Every replacement is recorded as a [Span] in both coordinate
 * systems; [OffsetMap] translates char offsets between the
 * original text and the displayed text (the read-aloud karaoke
 * highlight lives in original coordinates, the page on screen
 * in display coordinates).
 */
object TermReplacer {

    /** One replaced span, in original and display coordinates. */
    data class Span(
        val originalStart: Int,
        val originalEnd: Int,
        val displayStart: Int,
        val displayEnd: Int
    )

    /** Original↔display char-offset mapping for one text. */
    class OffsetMap(val spans: List<Span>) {

        val isEmpty: Boolean get() = spans.isEmpty()

        /** The display offset of an original-text offset. An
         * offset inside a replaced span lands the same distance
         * into the replacement, clamped to its length. */
        fun originalToDisplay(offset: Int): Int {
            var delta = 0
            for (span in spans) {
                if (offset < span.originalStart) return offset + delta
                if (offset < span.originalEnd) {
                    val into = (offset - span.originalStart)
                        .coerceAtMost(span.displayEnd - span.displayStart)
                    return span.displayStart + into
                }
                delta += (span.displayEnd - span.displayStart) -
                    (span.originalEnd - span.originalStart)
            }
            return offset + delta
        }

        /** The original-text offset of a display offset. */
        fun displayToOriginal(offset: Int): Int {
            var delta = 0
            for (span in spans) {
                if (offset < span.displayStart) return offset - delta
                if (offset < span.displayEnd) {
                    val into = (offset - span.displayStart)
                        .coerceAtMost(span.originalEnd - span.originalStart)
                    return span.originalStart + into
                }
                delta += (span.displayEnd - span.displayStart) -
                    (span.originalEnd - span.originalStart)
            }
            return offset - delta
        }
    }

    /** The outcome of replacing in one plain text. */
    class Result(val text: String, val offsetMap: OffsetMap)

    /** The outcome of replacing in a whole chapter: the display
     * copy of the content plus the offset map of its joined text
     * (the [ChapterText] coordinate system — blocks joined by a
     * blank line, images/rules zero-width). */
    class ContentResult(val content: ChapterContent, val offsetMap: OffsetMap)

    /** A pair prepared for the scan: its boundary rule resolved
     * once, not per position. */
    private class Prepared(
        val pair: TermPair,
        /** True when this pair matches as exact text (no
         * whole-word boundary check): [TermPair.wholeWord] off,
         * or the original is written in a space-less script. */
        val exactText: Boolean
    )

    fun replace(text: String, pairs: List<TermPair>): Result {
        val prepared = prepare(pairs)
        if (prepared.isEmpty() || text.isEmpty()) {
            return Result(text, OffsetMap(emptyList()))
        }
        // Candidates bucketed by first character;
        // case-insensitive pairs sit under every case of theirs,
        // so a position only ever consults one bucket.
        val buckets = HashMap<Char, MutableList<Prepared>>()
        for (candidate in prepared) {
            val first = candidate.pair.original.first()
            buckets.getOrPut(first) { mutableListOf() } += candidate
            if (!candidate.pair.matchCase) {
                val lower = first.lowercaseChar()
                val upper = first.uppercaseChar()
                if (lower != first) {
                    buckets.getOrPut(lower) { mutableListOf() } += candidate
                }
                if (upper != first) {
                    buckets.getOrPut(upper) { mutableListOf() } += candidate
                }
            }
        }
        // Longest first inside each bucket: at a given position
        // the longest matching original wins.
        buckets.values.forEach { bucket ->
            bucket.sortByDescending { it.pair.original.length }
        }

        val out = StringBuilder(text.length)
        val spans = ArrayList<Span>()
        var i = 0
        while (i < text.length) {
            var matched: Prepared? = null
            val candidates = buckets[text[i]]
            if (candidates != null) {
                for (candidate in candidates) {
                    val pair = candidate.pair
                    if (i + pair.original.length > text.length) continue
                    if (!text.regionMatches(
                            i, pair.original, 0, pair.original.length,
                            ignoreCase = !pair.matchCase
                        )
                    ) {
                        continue
                    }
                    if (!candidate.exactText &&
                        !boundaryClear(text, i, i + pair.original.length)
                    ) {
                        continue
                    }
                    matched = candidate
                    break
                }
            }
            if (matched != null) {
                val pair = matched.pair
                val displayStart = out.length
                out.append(pair.replacement)
                spans += Span(
                    originalStart = i,
                    originalEnd = i + pair.original.length,
                    displayStart = displayStart,
                    displayEnd = out.length
                )
                i += pair.original.length
            } else {
                out.append(text[i])
                i++
            }
        }
        if (spans.isEmpty()) return Result(text, OffsetMap(emptyList()))
        return Result(out.toString(), OffsetMap(spans))
    }

    /**
     * Replace inside a chapter's prose blocks (headings and
     * paragraph spans), returning a display copy — [content]
     * itself is never mutated. Images and rules pass through.
     * A term split across two styled spans of one paragraph is
     * not matched (each span is replaced on its own); ordinary
     * single-span paragraphs — the MTL norm — are unaffected.
     * The returned [OffsetMap] is in joined-text coordinates,
     * built with the same layout [ChapterText] uses.
     */
    fun applyToContent(content: ChapterContent, pairs: List<TermPair>): ContentResult {
        if (pairs.none { it.original.isNotEmpty() }) {
            return ContentResult(content, OffsetMap(emptyList()))
        }
        val spans = ArrayList<Span>()
        var originalCursor = 0
        var displayCursor = 0
        val blocks = content.blocks.mapIndexed { index, block ->
            if (index > 0) {
                originalCursor += 2
                displayCursor += 2
            }
            when (block) {
                is ChapterBlock.Heading -> {
                    val result = replace(block.text, pairs)
                    collect(spans, result, originalCursor, displayCursor)
                    originalCursor += block.text.length
                    displayCursor += result.text.length
                    if (result.offsetMap.isEmpty) block
                    else block.copy(text = result.text)
                }
                is ChapterBlock.Paragraph -> {
                    var changed = false
                    val newSpans = block.spans.map { span ->
                        val result = replace(span.text, pairs)
                        collect(spans, result, originalCursor, displayCursor)
                        originalCursor += span.text.length
                        displayCursor += result.text.length
                        if (result.offsetMap.isEmpty) {
                            span
                        } else {
                            changed = true
                            span.copy(text = result.text)
                        }
                    }
                    if (changed) block.copy(spans = newSpans) else block
                }
                else -> block
            }
        }
        if (spans.isEmpty()) return ContentResult(content, OffsetMap(emptyList()))
        return ContentResult(
            ChapterContent(blocks = blocks, mediaOverlay = content.mediaOverlay),
            OffsetMap(spans)
        )
    }

    private fun collect(
        into: MutableList<Span>,
        result: Result,
        originalBase: Int,
        displayBase: Int
    ) {
        for (span in result.offsetMap.spans) {
            into += Span(
                originalStart = originalBase + span.originalStart,
                originalEnd = originalBase + span.originalEnd,
                displayStart = displayBase + span.displayStart,
                displayEnd = displayBase + span.displayEnd
            )
        }
    }

    private fun prepare(pairs: List<TermPair>): List<Prepared> =
        pairs.filter { it.original.isNotEmpty() }.map { pair ->
            Prepared(
                pair = pair,
                exactText = !pair.wholeWord ||
                    pair.original.any { isSpaceLessScript(it) }
            )
        }

    /** Whole-word boundary: neither touching character may be a
     * word character. Space-less-script characters never block —
     * they are complete morphemes, not parts of a word. */
    private fun boundaryClear(text: String, start: Int, end: Int): Boolean {
        val before = if (start > 0) text[start - 1] else null
        val after = if (end < text.length) text[end] else null
        return !isBlocking(before) && !isBlocking(after)
    }

    private fun isBlocking(char: Char?): Boolean {
        if (char == null) return false
        if (isSpaceLessScript(char)) return false
        return char.isLetterOrDigit() || char == '_'
    }

    /** Han, Hiragana, Katakana and Hangul: scripts written
     * without spaces, where "word boundary" has no meaning. */
    private fun isSpaceLessScript(char: Char): Boolean =
        when (Character.UnicodeScript.of(char.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
}
