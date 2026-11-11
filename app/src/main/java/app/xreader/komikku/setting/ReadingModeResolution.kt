package app.xreader.komikku.setting

/**
 * v1.5.6 (§5.6 + Gemini amendment): explicit-vs-default
 * reading-mode resolution and the chapter shape verdict.
 *
 * The law:
 * - An explicitly saved SERIES mode always wins.
 * - An explicitly saved GLOBAL mode wins for books without a
 *   series choice. "Explicitly saved" is the marker
 *   [ReaderPreferences.defaultReadingModeExplicit] — the
 *   shipped RIGHT_TO_LEFT default nobody picked is NOT a
 *   choice.
 * - When nothing was explicitly chosen, the chapter's SHAPE
 *   decides: a webtoon-shaped chapter resolves WEBTOON,
 *   an ordinary chapter keeps the shipped default chain.
 *
 * The shape verdict is computed over the chapter's WHOLE
 * page set (never a prefix, page order ignored):
 * - a page is tall when height >= 2x width;
 * - the verdict is WEBTOON when tall pages are at least
 *   half of the pages whose dimensions are known;
 * - fewer than 3 dimension-known pages -> NO verdict (the
 *   default chain applies; the per-page TallStripPolicy
 *   slicing still guards any tall page in the pager).
 */

/** One page's pixel dimensions, as the loader resolves its
 * image request. A null entry in a verdict input list means
 * that page's dimensions are not (yet) known. */
data class PageDimension(val width: Int, val height: Int) {
    val isTall: Boolean get() = width > 0 && height >= 2 * width
}

object ChapterShape {

    /** Minimum dimension-known pages before any verdict. */
    const val MIN_KNOWN_PAGES = 3

    /**
     * The whole-set verdict: true = webtoon-shaped,
     * false = known set is NOT webtoon-shaped, null = no
     * verdict (fewer than [MIN_KNOWN_PAGES] known pages).
     */
    fun isWebtoonShaped(dimensions: List<PageDimension?>): Boolean? {
        val known = dimensions.filterNotNull()
            .filter { it.width > 0 && it.height > 0 }
        if (known.size < MIN_KNOWN_PAGES) return null
        val tall = known.count { it.isTall }
        return tall * 2 >= known.size
    }
}

object ReadingModeResolution {

    /**
     * @param seriesFlag the series store's raw flag
     *   (0 = DEFAULT = no series choice — the series store's
     *   stored meaning, unchanged).
     * @param globalFlag the global default's raw flag.
     * @param globalExplicit the global explicit-set marker.
     * @param shapeWebtoon the chapter shape verdict, or null
     *   when no verdict could be taken.
     */
    fun resolve(
        seriesFlag: Int,
        globalFlag: Int,
        globalExplicit: Boolean,
        shapeWebtoon: Boolean?
    ): ReadingMode {
        val series = ReadingMode.fromPreference(seriesFlag)
        if (series != ReadingMode.DEFAULT) return series
        if (globalExplicit) return ReadingMode.fromPreference(globalFlag)
        if (shapeWebtoon == true) return ReadingMode.WEBTOON
        return ReadingMode.fromPreference(globalFlag)
    }
}
