package app.xreader.ui.reader

import app.xreader.domain.content.ChapterPage
import app.xreader.domain.reader.ImageReaderMode
import kotlin.math.roundToInt

/**
 * Manga programme (slice M2): the image reader's pure rules,
 * in one place, free of Android/Compose types so the JVM pins
 * them — the same arrangement as [PrefetchPolicy] and
 * [ReaderCounters] for the text reader. The composables in
 * MangaReader.kt keep the side effects; these objects keep
 * the decisions.
 */

/**
 * The option-scope rule (user, 2026-11-01) applied to the
 * manga reading mode: the Settings value is the global
 * default for ALL series; a mode chosen inside one series'
 * reader is stored as that series' override and wins for
 * that series only. Resolution is nothing more than
 * override-first — the interesting half is WHERE each value
 * is written (ReaderConfig.imageMode globally,
 * BookReaderPrefs per book), which the stores own.
 */
object MangaModeResolution {

    /** The mode a series reads in: its own override when one
     * is set, else the global default. */
    fun resolve(
        bookOverride: ImageReaderMode?,
        globalDefault: ImageReaderMode
    ): ImageReaderMode = bookOverride ?: globalDefault

    /** The plan's default at both tiers. */
    val DEFAULT: ImageReaderMode = ImageReaderMode.WEBTOON
}

/**
 * Where one page's pixels come from. A page that M3's
 * download pass has stored carries a local file name and is
 * read from disk; every other page rides its remote URL
 * through the app's image pipeline (whose call factory
 * resolves the source's session cookies per request, so
 * signed-in sources' pages load). The URL stays the page's
 * identity either way — this choice is per page, per moment.
 */
sealed interface MangaPageSource {

    /** A downloaded page: [fileName] inside the book's
     * content directory (resolved by the caller's assetFile
     * seam, the same one chapter images use). */
    data class Local(val fileName: String) : MangaPageSource

    /** An online page: fetched from [url] on display. */
    data class Remote(val url: String) : MangaPageSource
}

object MangaPageSources {

    /** The display source for [page]: the local file when
     * one is recorded, the remote URL otherwise. A blank
     * file name is not a file — it falls back to the URL. */
    fun sourceFor(page: ChapterPage): MangaPageSource =
        page.localFile
            ?.takeIf { it.isNotBlank() }
            ?.let { MangaPageSource.Local(it) }
            ?: MangaPageSource.Remote(page.url)

    /** True when [page] still needs the network to display —
     * the preload window only warms these (a stored page is
     * already as warm as it gets). */
    fun needsNetwork(page: ChapterPage): Boolean =
        sourceFor(page) is MangaPageSource.Remote
}

/**
 * Position maths for image chapters. Reading progress rides
 * the EXISTING ReadingProgress model (chapter + fraction), so
 * the in-chapter fraction is defined over PAGES:
 *
 *  - paged mode: the fraction of page i in an n-page chapter
 *    is i / (n − 1) — page 0 is 0, the last page is 1, and
 *    the mapping round-trips exactly through [pageForFraction]
 *    (that round-trip is what resume relies on);
 *  - webtoon mode: the same scale fed with a fractional page
 *    position (the page at the reading line plus the part of
 *    it already scrolled past), so a mid-page stop resumes
 *    mid-page;
 *  - a one-page chapter sits at fraction 0 on its only page
 *    (there is no distance to travel); finishing it is the
 *    end-of-chapter flow's business, not the fraction's.
 */
object MangaProgress {

    /** Paged mode: the in-chapter fraction of [pageIndex]. */
    fun fractionForPage(pageIndex: Int, pageCount: Int): Float {
        if (pageCount <= 1) return 0f
        val index = pageIndex.coerceIn(0, pageCount - 1)
        return (index.toFloat() / (pageCount - 1)).coerceIn(0f, 1f)
    }

    /** Webtoon mode: the in-chapter fraction of a fractional
     * page position ([pagePosition] = page index + the part
     * of that page already scrolled past, 0..1). */
    fun fractionForPagePosition(pagePosition: Float, pageCount: Int): Float {
        if (pageCount <= 1) return 0f
        return (pagePosition.coerceIn(0f, (pageCount - 1).toFloat()) /
            (pageCount - 1)).coerceIn(0f, 1f)
    }

    /** The page a saved fraction reopens at — the exact
     * inverse of [fractionForPage]. */
    fun pageForFraction(fraction: Float, pageCount: Int): Int {
        if (pageCount <= 1) return 0
        val scaled = fraction.coerceIn(0f, 1f) * (pageCount - 1)
        return scaled.roundToInt().coerceIn(0, pageCount - 1)
    }

    /**
     * The page a chapter opens at, from the reader's restore
     * triple — the same inputs the text renderers consume:
     * a LAST hint (turning back into the chapter) opens its
     * final page; a FIRST hint its first; otherwise the
     * saved FRACTION decides (it is the persisted carrier —
     * the ViewModel's offset slot clamps against the chapter
     * text, which is empty for a pages chapter, so no exact
     * page is ever stored there today; an in-range stored
     * page is still preferred if a future writer puts one
     * there).
     */
    fun restorePage(
        hint: PageHint,
        restoreOffset: Int,
        restoreFraction: Float,
        pageCount: Int
    ): Int {
        if (pageCount <= 0) return 0
        return when (hint) {
            PageHint.LAST -> pageCount - 1
            PageHint.FIRST -> 0
            PageHint.OFFSET ->
                if (restoreOffset in 1 until pageCount) {
                    restoreOffset
                } else {
                    pageForFraction(restoreFraction, pageCount)
                }
        }
    }

    /** The display line: "Page 12 of 40". [pageIndex] is
     * 0-based; the line is 1-based, and an empty chapter
     * shows nothing. */
    fun pageLine(pageIndex: Int, pageCount: Int): String {
        if (pageCount <= 0) return ""
        val shown = (pageIndex + 1).coerceIn(1, pageCount)
        return "Page $shown of $pageCount"
    }
}

/**
 * The preload window: which pages around the reading
 * position get their image requests started ahead of need,
 * so a fast connection means page-wait never exists (the
 * plan's rule) — bounded, because every warmed page is a
 * decoded bitmap the RAM budget has to hold.
 *
 *  - WEBTOON: the next [WEBTOON_AHEAD_STANDARD] pages ahead
 *    of the reading line (the strip only moves one way);
 *    [WEBTOON_AHEAD_LOW_RAM] on the low-RAM tier.
 *  - PAGED: the next page (the turn that is coming) plus the
 *    previous page (a turn back is the other common move);
 *    on the low-RAM tier, the next page only.
 *
 * The window never includes the current page (it is already
 * loading or shown) and never leaves the chapter.
 */
object MangaPreload {

    const val WEBTOON_AHEAD_STANDARD = 3
    const val WEBTOON_AHEAD_LOW_RAM = 2

    fun window(
        currentPage: Int,
        pageCount: Int,
        mode: ImageReaderMode,
        lowRam: Boolean
    ): List<Int> {
        if (pageCount <= 0) return emptyList()
        val current = currentPage.coerceIn(0, pageCount - 1)
        val indices = sortedSetOf<Int>()
        when (mode) {
            ImageReaderMode.WEBTOON -> {
                val ahead = if (lowRam) {
                    WEBTOON_AHEAD_LOW_RAM
                } else {
                    WEBTOON_AHEAD_STANDARD
                }
                for (i in (current + 1)..(current + ahead)) {
                    if (i < pageCount) indices += i
                }
            }
            ImageReaderMode.PAGED -> {
                if (current + 1 < pageCount) indices += current + 1
                if (!lowRam && current - 1 >= 0) indices += current - 1
            }
        }
        return indices.toList()
    }
}

/**
 * Decode sizing for manga pages. Standard tiers keep the
 * reader-wide cap ([ReaderImageDecode.MAX_WIDTH_PX]) — pages
 * decode at up to that width whatever the screen. On the
 * low-RAM tier (the 2 GB class, PerformancePolicy's MINIMAL)
 * a page decodes at SCREEN width instead: a full-width page
 * never displays wider than the screen, so the extra pixels
 * are pure RAM cost — and RAM is the manga killer on that
 * tier (the plan's rule).
 */
object MangaDecode {

    /** The pixel width a page should be decoded at. */
    fun widthPx(lowRam: Boolean, screenWidthPx: Int): Int =
        if (lowRam) {
            screenWidthPx.coerceAtLeast(1)
        } else {
            ReaderImageDecode.MAX_WIDTH_PX
        }
}
