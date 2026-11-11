package app.xreader.ui.localreader

import app.xreader.data.engine.ReadiumEpubSession
import app.xreader.data.engine.RiverDocument
import org.readium.r2.shared.publication.Locator

/**
 * v1.5.32 (EPUB RIVER, plan §3 — R2's position core, pulled
 * forward by the owner: "it doesn't remember where I read"):
 * the river's anchor arithmetic.
 *
 * The anchor is (sectionIndex, charOffsetInSection) — a
 * character offset into the section's text as the merger's
 * char map counts it (plan §2.2). Character offsets are
 * SETTINGS-INDEPENDENT: font size, margins and spacing never
 * move an anchor, unlike pixel scroll positions. The JS
 * bridge reports anchors at BLOCK granularity — (section,
 * top-level block index) — and this object converts between
 * blocks, char offsets and the Readium [Locator] the rest of
 * the app consumes.
 *
 * The dual-write (plan §3.1): the river feeds the ViewModel
 * the SAME seam the legacy navigator feeds —
 * `onEpubLocator(locator)` — with a locator synthesised from
 * the anchor: the section's own link (via the session's
 * section-locator factory, so its href is exactly the shape
 * the seam's section lookup expects) with `locations`
 * carrying the char-derived fractions:
 *  - `progression` = charOffset / sectionChars — the river's
 *    own restore reads THIS field back, so a river-written
 *    save restores to the exact paragraph it was written
 *    from, through the standard field alone (no side record;
 *    unknown JSON fields would not survive Readium's locator
 *    parser anyway);
 *  - `totalProgression` = bookCharOffset / totalChars — the
 *    book % every existing progress consumer displays.
 * Feeding the seam — not merely writing the store — is what
 * makes the ViewModel's LIVE position follow the reader:
 * Bookmark here and Read aloud's start section read that
 * position (dead-control audit findings #4/#5).
 */
internal object EpubRiverPosition {

    /** The char offset at which [block] starts in [section]. */
    fun charOffsetOf(
        document: RiverDocument,
        section: Int,
        block: Int
    ): Int =
        document.sections.getOrNull(section)
            ?.blockStarts?.getOrNull(block) ?: 0

    /** The block whose start is the largest start ≤
     * [charOffset] — the paragraph a char offset lives in. */
    fun blockForCharOffset(
        document: RiverDocument,
        section: Int,
        charOffset: Int
    ): Int {
        val map = document.sections.getOrNull(section) ?: return 0
        var block = 0
        for (k in map.blockStarts.indices) {
            if (map.blockStarts[k] <= charOffset) {
                block = k
            } else {
                break
            }
        }
        return block
    }

    /** Resolve an in-section progression (any vintage) to a
     * (section, block) anchor — the §4.3 ladder's progression
     * rung. River-written progressions are char fractions and
     * land on the exact paragraph; legacy Readium
     * progressions are the toolkit's own in-resource fraction
     * and land paragraph-near (plan §8's promise). */
    fun anchorFor(
        document: RiverDocument,
        section: Int,
        progression: Double?
    ): Pair<Int, Int> {
        val map = document.sections.getOrNull(section)
            ?: return section to 0
        if (progression == null) return section to 0
        val charOffset = (progression.coerceIn(0.0, 1.0) *
            map.charCount).toInt()
        return section to blockForCharOffset(
            document, section, charOffset
        )
    }

    /** The synthesised Readium locator for a reported
     * (section, block) anchor — the dual-write the ViewModel
     * seam, the progress UI and every locator store consume
     * unchanged. Null only when the section names no
     * reading-order resource. */
    fun locator(
        session: ReadiumEpubSession,
        document: RiverDocument,
        section: Int,
        block: Int
    ): Locator? {
        val base = session.locatorForSection(section) ?: return null
        val map = document.sections.getOrNull(section) ?: return base
        val charOffset = charOffsetOf(document, section, block)
        var charsBefore = 0
        val upper = minOf(section, document.sections.size)
        for (i in 0 until upper) {
            charsBefore += document.sections[i].charCount
        }
        val inSection = if (map.charCount > 0) {
            (charOffset.toDouble() / map.charCount).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val totalProg = (
            (charsBefore + charOffset).toDouble() /
                document.totalChars.coerceAtLeast(1)
            ).coerceIn(0.0, 1.0)
        return base.copy(
            locations = base.locations.copy(
                progression = inSection,
                totalProgression = totalProg
            )
        )
    }

    /**
     * v1.5.34 (R4): the synthesised locator for a TEXT
     * SELECTION at [charOffset] inside [section] — [locator]'s
     * char-exact sibling. [charOffset] counts the section's
     * whitespace-normalised DOM text (the page script's own
     * arithmetic, self-consistent between capture and
     * re-application); it lands on the char map's scale closely
     * enough for the progression fractions, while the precise
     * rung of the §4.3 ladder is the quote: the locator carries
     * [text] (highlight + before/after context), so a stored
     * highlight re-resolves by its words, not its numbers.
     */
    fun locatorAtChar(
        session: ReadiumEpubSession,
        document: RiverDocument,
        section: Int,
        charOffset: Int,
        text: Locator.Text?
    ): Locator? {
        val base = session.locatorForSection(section) ?: return null
        val map = document.sections.getOrNull(section) ?: return base
        val offset = charOffset.coerceIn(0, map.charCount)
        var charsBefore = 0
        val upper = minOf(section, document.sections.size)
        for (i in 0 until upper) {
            charsBefore += document.sections[i].charCount
        }
        val inSection = if (map.charCount > 0) {
            (offset.toDouble() / map.charCount).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val totalProg = (
            (charsBefore + offset).toDouble() /
                document.totalChars.coerceAtLeast(1)
            ).coerceIn(0.0, 1.0)
        return base.copy(
            locations = base.locations.copy(
                progression = inSection,
                totalProgression = totalProg
            ),
            text = text ?: base.text
        )
    }
}
