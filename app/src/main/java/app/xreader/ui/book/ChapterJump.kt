package app.xreader.ui.book

import app.xreader.data.db.Chapter
import app.xreader.data.extension.ChapterNumbers
import kotlin.math.abs

/**
 * v1.5.7 (Finding 5, second bug): the book page's "Jump to
 * chapter…" field is a JUMP, not a search. Before this fix the
 * field's text fed ChapterListFilter directly, so typing "1"
 * FILTERED the list by row text — the header switched to
 * "Chapters 863 of 1200" and the rows that surfaced were the
 * ones whose title/date text happened to contain a "1"
 * (463, 468, 469… — chapter 1 itself unreachable except by
 * scrolling hundreds of rows).
 *
 * The contract now, pinned by ChapterJumpResolutionTest:
 *  - a purely numeric entry resolves to THAT chapter — an
 *    exact match on the chapter's own number (parsed from its
 *    title / slug, the same identity the display order keys
 *    on), falling back to its ordinal position (index + 1)
 *    when the chapter carries no number of its own;
 *  - resolution NEVER looks at a row's date text and NEVER
 *    substring-matches a title — a chapter dated
 *    "14 Dec 2024" is not chapter 1, and "Chapter 12" is not
 *    chapter 1 either;
 *  - empty / non-numeric / zero / out-of-range entries
 *    resolve to nothing (the caller stays put — no crash, no
 *    filter, no "N of M" header).
 *
 * Pure, so the whole contract is JVM-tested.
 */
object ChapterJump {

    /** The number a jump entry names, or null when the entry
     * isn't a jump entry at all (blank, or any non-numeric
     * text — that text belongs to the separate chapter search,
     * never to this field's jump contract). Integers and
     * decimals ("10.5" specials) both count as numeric. */
    fun parseNumber(raw: String): Float? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.matches(Regex("""\d+(\.\d+)?"""))) return null
        return trimmed.toFloatOrNull()
    }

    /** True when [raw] is a jump entry (purely numeric). The
     * screen routes those to this resolver and keeps them OUT
     * of the text filter entirely. */
    fun isJumpQuery(raw: String): Boolean = parseNumber(raw) != null

    /**
     * A chapter's own number for jump/head purposes: parsed
     * from its title, then from a slug-shaped title or URL
     * ("chapter-214"). Deliberately NOT the bare numeric URL
     * tail — on sites like Novelight that tail is a database
     * id (…/chapter/346374 is chapter 760), and treating it as
     * a chapter number would send jumps to the wrong chapter
     * (ChapterNumbers.parseForOrder excludes it for the same
     * reason).
     */
    fun identityNumber(chapter: Chapter): Float? =
        ChapterNumbers.parse(chapter.title)
            ?: ChapterNumbers.parseSlug(chapter.title)
            ?: chapter.remoteId?.let { ChapterNumbers.parseSlug(it) }

    /**
     * The chapter [raw] names in [chapters], or null when it
     * names none. Number match first (order-independent — a
     * newest-first display finds the same chapter), ordinal
     * position second (integer entries only). When numbering
     * restarts (per-volume lists share a number), the FIRST
     * chapter carrying the number wins — the same choice the
     * display's identity order makes for its head.
     */
    fun resolve(raw: String, chapters: List<Chapter>): Chapter? {
        val target = parseNumber(raw) ?: return null
        if (target <= 0f) return null
        chapters.firstOrNull { chapter ->
            val number = identityNumber(chapter)
            number != null && abs(number - target) < 0.001f
        }?.let { return it }
        // Ordinal fallback — ONLY for lists whose chapters
        // carry (almost) no numbers of their own, where
        // position is the only identity there is. On a
        // numbered list a missing number means the chapter
        // isn't in the list (a headless list re-indexes its
        // first stored row — chapter 464 — to position 1, and
        // answering "1" with chapter 464 would repeat the
        // very identity lie this fix exists to kill).
        val numbered = chapters.count { identityNumber(it) != null }
        if (numbered >= 3) return null
        val asInt = target.toInt()
        if (target != asInt.toFloat()) return null
        return chapters.firstOrNull { it.index + 1 == asInt }
    }

    /** The position of the resolved chapter inside [display]
     * (the list as currently ordered/filtered for the page),
     * or -1 when the entry resolves nowhere in it. The screen
     * scrolls to exactly this position — the jump never
     * changes which chapters the list contains.
     *
     * Position is found by REFERENCE first: [resolve] returns
     * an element of this very list, so the reference hit is
     * exact even for not-yet-persisted rows whose Room id is
     * still the autogenerate placeholder 0 (an id-only search
     * would land on the first placeholder row — the failure
     * ChapterJumpResolutionTest caught). The id search remains
     * as a fallback for a persisted target (real ids are
     * unique); a placeholder id never falls back, because
     * every unpersisted row shares it. */
    fun resolvePosition(raw: String, display: List<Chapter>): Int {
        val target = resolve(raw, display) ?: return -1
        val byReference = display.indexOfFirst { it === target }
        if (byReference >= 0) return byReference
        if (target.id == 0L) return -1
        return display.indexOfFirst { it.id == target.id }
    }
}
