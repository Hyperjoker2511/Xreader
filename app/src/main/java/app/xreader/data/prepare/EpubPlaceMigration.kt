package app.xreader.data.prepare

import app.xreader.data.db.ReadingProgress
import app.xreader.data.engine.LocatorJson
import app.xreader.data.parse.ChapterMergeSpan

/**
 * v1.7 Patch 3 (Item 3): the place migration, as pure
 * functions over plain inputs so every rung is JVM-pinned.
 * The promise is PARAGRAPH-NEAR, in the spec's words: the old
 * road's char map and the converter's text differ, so the
 * exact char is honestly unrecoverable; from the first
 * core-road save onward, places are char-exact forever.
 */
object EpubPlaceMigration {

    /** Everything one migration pass needs. [parserLengths]
     * are the converted chapters' [app.xreader.domain.reader.ChapterText]
     * lengths in parser order; [oldChapterIdsInOrder] /
     * [oldChapterLengths] describe the PRE-reprepare landing
     * (Item 3d's scaling source) and are empty on a first
     * prepare. */
    class Inputs(
        val saved: ReadingProgress?,
        val oldSectionMap: Map<Int, Long>,
        val sessionHrefs: List<String>,
        val parserHrefs: List<String>,
        val parserTitles: List<String>,
        val parserLengths: List<Int>,
        val oldChapterIdsInOrder: List<Long> = emptyList(),
        val oldChapterLengths: Map<Long, Int> = emptyMap(),
        /** Patch 1 (A2): the parser's segmentation merge
         * map (pre-merge chapter space → merged chapter +
         * char prefix), empty when the parse did not
         * segment. [parserHrefs]/[parserLengths]/
         * [parserTitles] always describe the MERGED parse;
         * [preSegmentChapterHrefs] names the pre-merge
         * chapters the map is keyed in. */
        val chapterMerge: List<ChapterMergeSpan> = emptyList(),
        val preSegmentChapterHrefs: List<String> = emptyList()
    )

    /** A resolved place in parser space. [resolved] is false
     * only for the book-start fallback (nothing named the
     * place — the honest zero). */
    data class Place(
        val chapterIndex: Int,
        val charOffset: Int,
        val resolved: Boolean
    )

    private val bookStart = Place(0, 0, resolved = false)

    /** A stored row is already CORE-shaped when it carries a
     * real chapter id — every local-road save writes
     * chapterId 0 with a locator, every core-road save writes
     * the chapter's own id. Item 3d keys on exactly this. */
    fun isCoreShaped(saved: ReadingProgress?): Boolean =
        saved != null && saved.chapterId != 0L

    fun resolvePlace(input: Inputs): Place {
        val saved = input.saved ?: return bookStart
        return if (isCoreShaped(saved)) {
            resolveCoreRow(input, saved)
        } else {
            resolveLocatorRow(input, saved)
        }
    }

    // ── Patch 1 (A2): the segmentation merge map ───────────

    /**
     * The merged landing of a PRE-merge place: the span of
     * [oldChapterIndex] whose start the offset has reached,
     * the offset carried across by the span's char prefix.
     * Char-exact by construction (the parser computed the
     * prefixes in ChapterText coordinates, seams included).
     * Null when the parse did not segment or the old
     * chapter is outside the map.
     */
    fun mergedPlace(
        input: Inputs,
        oldChapterIndex: Int,
        oldOffset: Int
    ): Place? {
        val spans = input.chapterMerge
            .filter { it.oldChapterIndex == oldChapterIndex }
        if (spans.isEmpty()) return null
        val span = spans
            .filter { it.oldStartOffset <= oldOffset }
            .maxByOrNull { it.oldStartOffset }
            ?: spans.minByOrNull { it.oldStartOffset }
            ?: return null
        val newLength = input.parserLengths
            .getOrElse(span.newChapterIndex) { 0 }
        return Place(
            span.newChapterIndex,
            (span.charPrefix + (oldOffset - span.oldStartOffset))
                .coerceIn(0, newLength),
            resolved = true
        )
    }

    /**
     * A pre-merge chapter's ChapterText length, derived
     * from its spans and the merged lengths: each slice
     * runs to the next span in its merged chapter (minus
     * the 2-char seam) or to that chapter's end, and a
     * file split across two merged chapters counts the
     * seam between its own halves once. Null when the old
     * chapter is outside the map.
     */
    fun mergedOldLength(
        input: Inputs,
        oldChapterIndex: Int
    ): Int? {
        val own = input.chapterMerge
            .filter { it.oldChapterIndex == oldChapterIndex }
            .sortedBy { it.oldStartOffset }
        if (own.isEmpty()) return null
        var total = 0
        for ((i, span) in own.withIndex()) {
            val newLength = input.parserLengths
                .getOrElse(span.newChapterIndex) { 0 }
            val nextInNew = input.chapterMerge
                .filter {
                    it.newChapterIndex == span.newChapterIndex &&
                        it.charPrefix > span.charPrefix
                }
                .minByOrNull { it.charPrefix }
            val slice = if (nextInNew != null) {
                nextInNew.charPrefix - span.charPrefix - 2
            } else {
                newLength - span.charPrefix
            }
            total += slice.coerceAtLeast(0)
            if (i > 0) total += 2
        }
        return total
    }

    // ── Item 3d: a core row carried across a re-prepare ─────

    private fun resolveCoreRow(input: Inputs, saved: ReadingProgress): Place {
        // Rung 0 (Patch 1, A2): the merge map. The row's
        // chapter id names a chapter of the PRE-reprepare
        // landing; its position in that landing's order is
        // the pre-merge chapter the parser's map is keyed
        // in, and the map carries the place across exactly
        // — ahead of every href rung, whose bridges are
        // built over the merged hrefs and miss every place
        // in an absorbed file by construction.
        if (input.chapterMerge.isNotEmpty()) {
            val oldIndex = input.oldChapterIdsInOrder
                .indexOf(saved.chapterId)
            if (oldIndex >= 0) {
                mergedPlace(input, oldIndex, saved.charOffset)
                    ?.let { return it }
            }
        }
        val bridge = EpubHrefBridge(input.parserHrefs)
        // Rung 1: the stored session→core map, inverted — the
        // row's chapter id names a session section, whose href
        // (from the LIVE detect session) names the new parse's
        // chapter. The durable carrier.
        val sessionIndex = input.oldSectionMap.entries
            .firstOrNull { it.value == saved.chapterId }?.key
        if (sessionIndex != null) {
            bridge.chapterForSessionIndex(input.sessionHrefs, sessionIndex)
                ?.let { parserIndex ->
                    return Place(
                        parserIndex,
                        scaledOffset(
                            saved.charOffset,
                            input.oldChapterLengths[saved.chapterId],
                            input.parserLengths.getOrElse(parserIndex) { 0 }
                        ),
                        resolved = true
                    )
                }
        }
        // Rung 2: the dormant locatorJson's href through Item
        // 3a rung 1 — live only until the first core-road save
        // rewrote the row without it.
        LocatorJson.epubLocatorHref(saved.locatorJson)?.let { href ->
            bridge.chapterForHref(href)?.let { parserIndex ->
                return Place(
                    parserIndex,
                    fractionOffset(input, saved, parserIndex),
                    resolved = true
                )
            }
        }
        // Rung 3: the same parser-space chapter index, when in
        // range, scaled the same way.
        if (saved.chapterIndex in input.parserLengths.indices) {
            val oldId = input.oldChapterIdsInOrder
                .getOrNull(saved.chapterIndex)
            return Place(
                saved.chapterIndex,
                scaledOffset(
                    saved.charOffset,
                    oldId?.let { input.oldChapterLengths[it] },
                    input.parserLengths[saved.chapterIndex]
                ),
                resolved = true
            )
        }
        return bookStart
    }

    // ── Item 3a/3b: a never-migrated locator row ────────────

    private fun resolveLocatorRow(input: Inputs, saved: ReadingProgress): Place {
        // Patch 1 (A2, the locator side): when the parse
        // segmented, the locator's href and the saved
        // session-space index both name PRE-merge files —
        // the merged-href bridge below would miss every
        // absorbed file and misread the rest (a fraction of
        // a merged chapter is not a fraction of the file).
        // Resolve in pre-merge space and carry the place
        // across the merge map, the fraction applied to the
        // file it was taken in (Item 3b's promise, kept in
        // its own coordinates). A miss falls through to the
        // shipped rungs unchanged.
        if (input.chapterMerge.isNotEmpty() &&
            input.preSegmentChapterHrefs.isNotEmpty()
        ) {
            mergedLocatorPlace(input, saved)?.let { return it }
        }
        val bridge = EpubHrefBridge(input.parserHrefs)
        // Rung 1: the saved locator's own href, matched in
        // parser space.
        LocatorJson.epubLocatorHref(saved.locatorJson)?.let { href ->
            bridge.chapterForHref(href)?.let { parserIndex ->
                return Place(
                    parserIndex,
                    fractionOffset(input, saved, parserIndex),
                    resolved = true
                )
            }
        }
        // Rung 2: the session bridge — the saved chapterIndex
        // read as a SESSION-space key, resolved through the
        // detect session's href list into parser space.
        bridge.chapterForSessionIndex(input.sessionHrefs, saved.chapterIndex)
            ?.let { parserIndex ->
                return Place(
                    parserIndex,
                    fractionOffset(input, saved, parserIndex),
                    resolved = true
                )
            }
        // Rungs 3+4: the anchorText title match — with the
        // scrollFraction applied when there is one (rung 3),
        // at the section start when there is not (rung 4).
        val anchor = saved.anchorText?.takeIf { it.isNotBlank() }
        if (anchor != null) {
            val parserIndex = input.parserTitles.indexOfFirst { it == anchor }
            if (parserIndex >= 0) {
                return Place(
                    parserIndex,
                    fractionOffset(input, saved, parserIndex),
                    resolved = true
                )
            }
        }
        return bookStart
    }

    /** Patch 1: a locator row's place through the merge
     * map — its href first, the session bridge second,
     * both read in PRE-merge space; the Item 3b fraction
     * (locator progression, else scrollFraction) applied
     * to the pre-merge file's own length, then carried
     * across by [mergedPlace]. */
    private fun mergedLocatorPlace(
        input: Inputs,
        saved: ReadingProgress
    ): Place? {
        val oldBridge = EpubHrefBridge(input.preSegmentChapterHrefs)
        val oldIndex = LocatorJson.epubLocatorHref(saved.locatorJson)
            ?.let { oldBridge.chapterForHref(it) }
            ?: oldBridge.chapterForSessionIndex(
                input.sessionHrefs, saved.chapterIndex
            )
            ?: return null
        val oldLength = mergedOldLength(input, oldIndex) ?: return null
        val fraction = LocatorJson.epubProgression(saved.locatorJson)
            ?: saved.scrollFraction.takeIf { it > 0f }
            ?: 0f
        val oldOffset = (fraction.coerceIn(0f, 1f) * oldLength).toInt()
            .coerceIn(0, oldLength)
        return mergedPlace(input, oldIndex, oldOffset)
    }

    /** Item 3b: the in-section fraction is the locator's
     * progression when it carries one, else the row's
     * scrollFraction; the offset is that fraction of the
     * converted section's text length. */
    private fun fractionOffset(
        input: Inputs,
        saved: ReadingProgress,
        parserIndex: Int
    ): Int {
        val length = input.parserLengths.getOrElse(parserIndex) { 0 }
        if (length <= 0) return 0
        val fraction = LocatorJson.epubProgression(saved.locatorJson)
            ?: saved.scrollFraction.takeIf { it > 0f }
            ?: 0f
        return (fraction.coerceIn(0f, 1f) * length).toInt()
            .coerceIn(0, length)
    }

    /** Item 3d's scaling: an offset keeps its FRACTION of the
     * section across a re-conversion whose text length moved. */
    private fun scaledOffset(
        charOffset: Int,
        oldLength: Int?,
        newLength: Int
    ): Int {
        if (newLength <= 0) return 0
        if (oldLength == null || oldLength <= 0) {
            return charOffset.coerceIn(0, newLength)
        }
        val fraction = charOffset.toDouble() / oldLength.toDouble()
        return (fraction * newLength).toInt().coerceIn(0, newLength)
    }

    // ── Item 3e: quote re-anchoring for marks ───────────────

    /**
     * Finds [quote] in [text]: exact match first, then a
     * whitespace-normalised match (the old road's char map
     * counts whitespace-normalised DOM text; the converter's
     * text does not — a quote spanning a line break must
     * still land). Returns the ORIGINAL text offset of the
     * match, or null. Pure; the normalised path builds an
     * index map so the returned offset is in the caller's
     * coordinates, never the normalised string's.
     */
    fun findQuote(text: String, quote: String): Int? {
        if (quote.isBlank() || text.isEmpty()) return null
        text.indexOf(quote).takeIf { it >= 0 }?.let { return it }
        val normalisedQuote = collapseWhitespace(quote).trim()
        if (normalisedQuote.isEmpty()) return null
        val builder = StringBuilder()
        val origin = ArrayList<Int>(text.length)
        var pendingSpaceOrigin = -1
        text.forEachIndexed { index, ch ->
            if (ch.isWhitespace()) {
                if (builder.isNotEmpty() && pendingSpaceOrigin < 0) {
                    pendingSpaceOrigin = index
                }
            } else {
                if (pendingSpaceOrigin >= 0) {
                    builder.append(' ')
                    origin += pendingSpaceOrigin
                    pendingSpaceOrigin = -1
                }
                builder.append(ch)
                origin += index
            }
        }
        val at = builder.indexOf(normalisedQuote)
        if (at < 0) return null
        return origin.getOrNull(at)
    }

    private fun collapseWhitespace(value: String): String =
        value.replace(Regex("\\s+"), " ")
}
