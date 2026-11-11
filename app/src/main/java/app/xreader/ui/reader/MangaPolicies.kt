package app.xreader.ui.reader

import app.xreader.data.prefs.BookReaderOverride
import app.xreader.domain.content.ChapterPage
import app.xreader.domain.reader.ImageReaderMode
import app.xreader.domain.reader.MangaOrientation
import app.xreader.domain.reader.MangaScaleType
import app.xreader.domain.reader.MangaTapLayout
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.TapInvert
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
            ImageReaderMode.PAGED, ImageReaderMode.PAGED_RTL,
            ImageReaderMode.PAGED_VERTICAL -> {
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

    /** The pixel width a page should be decoded at.
     * v1.5.2 fix (FIX-4, §6.2): the width IS [SlicePlan152]'s
     * printed per-tier decode width — MINIMAL decodes at screen
     * width, STANDARD/FULL at the reader-wide cap. */
    fun widthPx(lowRam: Boolean, screenWidthPx: Int): Int =
        SlicePlan152.decodeWidthPx(
            if (lowRam) {
                app.xreader.data.prefs.PerfTier.MINIMAL
            } else {
                app.xreader.data.prefs.PerfTier.STANDARD
            },
            screenWidthPx
        )
}

/**
 * v1.5.1 (item 6 rider): the tall-strip safeguard. A webtoon
 * "page" can be one strip many thousands of pixels tall;
 * decoded whole it exceeds GPU texture limits on ordinary
 * phones (the image renders BLACK) and can OOM the decode —
 * the reader black-screen class. The hotfix guard caps the
 * decoded height at [MAX_HEIGHT_PX] (aspect preserved, the
 * whole strip stays visible, slightly softer); full tiled
 * decoding is feature-era work, and [sliceBounds] is the
 * chunk math it will use. Pure, so the table is testable.
 */
object TallStripPolicy {

    const val MAX_HEIGHT_PX: Int = 3_072

    fun needsGuard(heightPx: Int): Boolean =
        heightPx > MAX_HEIGHT_PX

    fun guardedHeight(heightPx: Int): Int =
        minOf(heightPx, MAX_HEIGHT_PX)

    fun guardedWidth(widthPx: Int, heightPx: Int): Int {
        if (!needsGuard(heightPx) || heightPx <= 0) return widthPx
        return (widthPx.toLong() * MAX_HEIGHT_PX / heightPx)
            .toInt().coerceAtLeast(1)
    }

    /** [top, bottom) chunk bounds covering [heightPx] in
     * slices of at most [slicePx] — the tiler's future map,
     * pinned now so the guard and the tiler agree. */
    fun sliceBounds(
        heightPx: Int,
        slicePx: Int = MAX_HEIGHT_PX
    ): List<IntRange> {
        if (heightPx <= 0 || slicePx <= 0) return emptyList()
        val bounds = mutableListOf<IntRange>()
        var top = 0
        while (top < heightPx) {
            val bottom = minOf(heightPx, top + slicePx)
            bounds += top until bottom
            top = bottom
        }
        return bounds
    }
}

// ── v1.5.0 (Part C — the Komikku transplant) ────────────────

/**
 * The manga reader's resolved settings for ONE series: the
 * global defaults from [ReaderConfig] with the series' own
 * overrides ([BookReaderOverride]) laid over the fields a
 * series may carry (the option-scope rule: Settings = the
 * default for all; in-reader changes = that series only).
 * Pure data — the reader composables consume this, the
 * ViewModel builds it.
 */
data class MangaSettings(
    val mode: ImageReaderMode,
    val tapLayout: MangaTapLayout,
    val tapInvert: TapInvert,
    val smallerTapZones: Boolean,
    val doubleTapToZoomPaged: Boolean,
    val disableZoomOut: Boolean,
    val webtoonDoubleTapZoom: Boolean,
    val webtoonPinchZoom: Boolean,
    val webtoonDisableZoomOut: Boolean,
    val cropBorders: Boolean,
    val grayscale: Boolean,
    val invertColors: Boolean,
    val colorFilter: Boolean,
    val colorFilterArgb: Long,
    val webtoonSidePadding: Int,
    val scaleType: MangaScaleType,
    val orientation: MangaOrientation,
    val volumeKeyTurn: Boolean,
    val autoScrollEnabled: Boolean,
    val autoScrollSpeed: Float
) {
    companion object {
        /** The donor's per-family crop defaults differ
         * (paged vs webtoon toggles); the series override is
         * a single switch over whichever family is active. */
        fun resolve(
            global: ReaderConfig,
            override: BookReaderOverride,
            mode: ImageReaderMode
        ): MangaSettings {
            val cropGlobal = if (mode == ImageReaderMode.WEBTOON) {
                global.cropBordersWebtoon
            } else {
                global.cropBordersPaged
            }
            return MangaSettings(
                mode = mode,
                tapLayout = override.mangaTapLayout ?: global.mangaTapLayout,
                tapInvert = global.mangaTapInvert,
                smallerTapZones = global.mangaSmallerTapZones,
                doubleTapToZoomPaged = global.doubleTapToZoom,
                disableZoomOut = global.disableZoomOut,
                webtoonDoubleTapZoom = global.webtoonDoubleTapZoom,
                webtoonPinchZoom = global.webtoonPinchZoom,
                webtoonDisableZoomOut = global.webtoonDisableZoomOut,
                cropBorders = override.mangaCropBorders ?: cropGlobal,
                grayscale = global.mangaGrayscale,
                invertColors = global.mangaInvertColors,
                colorFilter = global.mangaColorFilter,
                colorFilterArgb = global.mangaColorFilterArgb,
                webtoonSidePadding = global.webtoonSidePadding,
                scaleType = global.mangaScaleType,
                orientation = override.mangaOrientation
                    ?: global.mangaOrientation,
                volumeKeyTurn = global.volumeKeyTurn,
                autoScrollEnabled = global.autoScrollEnabled,
                autoScrollSpeed = global.autoScrollSpeed
            )
        }
    }
}

/** What one tap in the image reader means. LEFT/RIGHT are
 * the Right-and-left layout's directional moves — the
 * caller maps them to page turns in its reading direction. */
enum class MangaTapAction { PREV, NEXT, MENU, LEFT, RIGHT }

/**
 * Tap-zone geometry, adapted from Komikku's
 * ViewerNavigation + its five navigation layouts
 * (Apache-2.0): normalised regions over the page, the
 * constant top-5% menu strip, inversion applied to the tap
 * point (equivalent to the donor inverting each rect), and
 * the smaller-zones variant (25% regions instead of 33%).
 * A point in no region is the menu — the donor's own
 * default. Pure, so the gate pins every layout's map.
 */
object MangaTapZones {

    private data class Region(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val action: MangaTapAction
    ) {
        fun contains(x: Float, y: Float): Boolean =
            x >= left && x < right && y >= top && y < bottom
    }

    private fun regions(
        layout: MangaTapLayout,
        smaller: Boolean
    ): List<Region> {
        val s1 = if (smaller) 0.25f else 1f / 3f
        val s2 = 1f - s1
        return when (layout) {
            MangaTapLayout.L_SHAPE -> listOf(
                Region(0f, s1, s1, s2, MangaTapAction.PREV),
                Region(0f, 0f, 1f, s1, MangaTapAction.PREV),
                Region(s2, s1, 1f, s2, MangaTapAction.NEXT),
                Region(0f, s2, 1f, 1f, MangaTapAction.NEXT)
            )
            MangaTapLayout.RIGHT_AND_LEFT -> listOf(
                Region(0f, 0f, s1, 1f, MangaTapAction.LEFT),
                Region(s2, 0f, 1f, 1f, MangaTapAction.RIGHT)
            )
            MangaTapLayout.KINDLISH -> listOf(
                Region(s1, s1, 1f, 1f, MangaTapAction.NEXT),
                Region(0f, s1, s1, 1f, MangaTapAction.PREV)
            )
            MangaTapLayout.EDGE -> listOf(
                Region(0f, 0f, s1, 1f, MangaTapAction.NEXT),
                Region(s1, s2, s2, 1f, MangaTapAction.PREV),
                Region(s2, 0f, 1f, 1f, MangaTapAction.NEXT)
            )
            MangaTapLayout.DISABLED,
            MangaTapLayout.DEFAULT -> emptyList()
        }
    }

    /** The layout in force: DEFAULT resolves the donor's
     * way — right-and-left for paged modes, the L shape for
     * the strip/vertical family. */
    fun effectiveLayout(
        layout: MangaTapLayout,
        mode: ImageReaderMode
    ): MangaTapLayout = when (layout) {
        MangaTapLayout.DEFAULT -> when (mode) {
            ImageReaderMode.WEBTOON, ImageReaderMode.PAGED_VERTICAL ->
                MangaTapLayout.L_SHAPE
            ImageReaderMode.PAGED, ImageReaderMode.PAGED_RTL ->
                MangaTapLayout.RIGHT_AND_LEFT
        }
        else -> layout
    }

    /**
     * The action for a tap at ([x], [y]) (fractions of the
     * reader surface, 0..1). [rtl] mirrors the directional
     * actions for a right-to-left pager (the donor's RTL
     * viewer semantics: LEFT and RIGHT swap their page
     * meaning; PREV/NEXT regions are unaffected).
     */
    fun action(
        layout: MangaTapLayout,
        invert: TapInvert,
        smaller: Boolean,
        mode: ImageReaderMode,
        x: Float,
        y: Float,
        rtl: Boolean = false
    ): MangaTapAction {
        val px = if (invert == TapInvert.HORIZONTAL ||
            invert == TapInvert.BOTH
        ) {
            1f - x
        } else {
            x
        }
        val py = if (invert == TapInvert.VERTICAL ||
            invert == TapInvert.BOTH
        ) {
            1f - y
        } else {
            y
        }
        val hit = regions(effectiveLayout(layout, mode), smaller)
            .firstOrNull { it.contains(px, py) }
        val action = when {
            hit != null -> hit.action
            // The donor's constant menu strip.
            py < 0.05f -> MangaTapAction.MENU
            else -> MangaTapAction.MENU
        }
        return if (rtl) {
            when (action) {
                MangaTapAction.LEFT -> MangaTapAction.RIGHT
                MangaTapAction.RIGHT -> MangaTapAction.LEFT
                else -> action
            }
        } else {
            action
        }
    }
}

/**
 * Border trimming, the donor's crop-borders behaviour as a
 * pure scan: from each edge, count the lines (rows/columns)
 * that are uniformly the edge's colour (within [tolerance]
 * per channel), stopping at the first line that isn't.
 * Sampling: a line is judged on samples every [SAMPLE_STEP]
 * pixels plus its ends — a page edge's margin is uniform by
 * construction, and sampling keeps the scan O(lines ×
 * samples) instead of O(pixels). The cap keeps a fully
 * blank page from being trimmed to nothing.
 */
object BorderTrim {

    data class Insets(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val isEmpty: Boolean
            get() = left == 0 && top == 0 && right == 0 && bottom == 0
    }

    const val SAMPLE_STEP = 16

    /** Never trim more than this share of a dimension. */
    const val MAX_TRIM_FRACTION = 0.45f

    fun scan(
        width: Int,
        height: Int,
        tolerance: Int = 24,
        pixelAt: (Int, Int) -> Int
    ): Insets {
        if (width <= 2 || height <= 2) return Insets(0, 0, 0, 0)
        fun similar(a: Int, b: Int): Boolean {
            fun channel(v: Int, shift: Int) = (v shr shift) and 0xFF
            return kotlin.math.abs(channel(a, 16) - channel(b, 16)) <= tolerance &&
                kotlin.math.abs(channel(a, 8) - channel(b, 8)) <= tolerance &&
                kotlin.math.abs(channel(a, 0) - channel(b, 0)) <= tolerance
        }
        fun rowIsBorder(y: Int, reference: Int): Boolean {
            var x = 0
            while (x < width) {
                if (!similar(pixelAt(x, y), reference)) return false
                x += SAMPLE_STEP
            }
            return similar(pixelAt(width - 1, y), reference)
        }
        fun columnIsBorder(x: Int, reference: Int): Boolean {
            var y = 0
            while (y < height) {
                if (!similar(pixelAt(x, y), reference)) return false
                y += SAMPLE_STEP
            }
            return similar(pixelAt(x, height - 1), reference)
        }
        val maxX = (width * MAX_TRIM_FRACTION).toInt()
        val maxY = (height * MAX_TRIM_FRACTION).toInt()
        var top = 0
        val topRef = pixelAt(0, 0)
        while (top < maxY && rowIsBorder(top, topRef)) top++
        var bottom = 0
        val bottomRef = pixelAt(0, height - 1)
        while (bottom < maxY && rowIsBorder(height - 1 - bottom, bottomRef)) {
            bottom++
        }
        var left = 0
        val leftRef = pixelAt(0, 0)
        while (left < maxX && columnIsBorder(left, leftRef)) left++
        var right = 0
        val rightRef = pixelAt(width - 1, 0)
        while (right < maxX && columnIsBorder(width - 1 - right, rightRef)) {
            right++
        }
        // A page that is ALL border (a blank scan) keeps a
        // sane window instead of collapsing to zero size.
        if (left + right >= width - 2 || top + bottom >= height - 2) {
            return Insets(0, 0, 0, 0)
        }
        return Insets(left, top, right, bottom)
    }
}

// ── FIX-18: pipeline transformations relocated from the removed
// MangaReader.kt surface (ZoomableImage and the Komikku reader
// both consume them; they are decode pipeline, not a reader). ──

/**
 * v1.5.1 (item 6 rider): the tall-strip guard as a Coil
 * transformation — any page bitmap taller than
 * [TallStripPolicy.MAX_HEIGHT_PX] is scaled down to that
 * height (aspect preserved) before it can reach a texture.
 * Pages at or under the cap pass through untouched.
 */
object TallStripGuardTransformation : coil.transform.Transformation {

    override val cacheKey: String = "xreader-tall-strip-guard-v1"

    override suspend fun transform(
        input: android.graphics.Bitmap,
        size: coil.size.Size
    ): android.graphics.Bitmap {
        if (!TallStripPolicy.needsGuard(input.height)) return input
        return android.graphics.Bitmap.createScaledBitmap(
            input,
            TallStripPolicy.guardedWidth(input.width, input.height),
            TallStripPolicy.guardedHeight(input.height),
            true
        )
    }
}

/**
 * v1.5.0 (Part C): Komikku's crop-borders, Xreader-native —
 * a Coil transformation that trims each page's uniform
 * margins per [BorderTrim]'s sampled scan (the donor trims
 * in its native decoder; the behaviour — uniform border
 * lines removed, content untouched — is the same). A page
 * whose scan finds nothing passes through unchanged.
 */
object CropBordersTransformation : coil.transform.Transformation {

    override val cacheKey: String = "xreader-crop-borders-v1"

    override suspend fun transform(
        input: android.graphics.Bitmap,
        size: coil.size.Size
    ): android.graphics.Bitmap {
        val insets = BorderTrim.scan(
            width = input.width,
            height = input.height,
            pixelAt = { x, y -> input.getPixel(x, y) }
        )
        if (insets.isEmpty) return input
        val width = input.width - insets.left - insets.right
        val height = input.height - insets.top - insets.bottom
        if (width <= 0 || height <= 0) return input
        return android.graphics.Bitmap.createBitmap(
            input, insets.left, insets.top, width, height
        )
    }
}

/** v1.5.0 (Part C): the page colour matrix for the
 * greyscale / invert switches (the donor's combined-paint
 * recipe): saturation 0 and/or the −1 invert matrix. Null
 * when neither is in force (the common case costs nothing). */
internal fun mangaColorFilter(
    grayscale: Boolean,
    invert: Boolean
): androidx.compose.ui.graphics.ColorFilter? {
    if (!grayscale && !invert) return null
    // The luminance row Android's saturation-0 matrix uses;
    // inversion after greyscale negates it and adds the
    // 255 offset (the two matrices composed by hand, so the
    // result is exact and allocation-free).
    val lr = 0.213f
    val lg = 0.715f
    val lb = 0.072f
    val values = when {
        grayscale && invert -> floatArrayOf(
            -lr, -lg, -lb, 0f, 255f,
            -lr, -lg, -lb, 0f, 255f,
            -lr, -lg, -lb, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
        grayscale -> floatArrayOf(
            lr, lg, lb, 0f, 0f,
            lr, lg, lb, 0f, 0f,
            lr, lg, lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        else -> floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    }
    return androidx.compose.ui.graphics.ColorFilter.colorMatrix(
        androidx.compose.ui.graphics.ColorMatrix(values)
    )
}

// ── FIX-18: webtoon position maths relocated from the removed
// MangaReader.kt surface (pure policy; its tests stand). ──

/** One visible item of the webtoon strip, reduced to the
 * numbers the position maths needs (so the maths is
 * unit-testable without Compose layout types). */
internal data class VisiblePageItem(
    val index: Int,
    val offset: Int,
    val size: Int
)

/** (current page, fractional page position) from one layout
 * snapshot of the webtoon strip: the visible PAGE item with
 * the largest on-screen height wins; the fractional position
 * adds the part of it above the viewport's top edge. The end
 * card is not a page and never counts. An empty snapshot
 * reads as the first page. */
internal fun webtoonReadingPosition(
    items: List<VisiblePageItem>,
    viewportStart: Int,
    viewportEnd: Int,
    pageCount: Int
): Pair<Int, Float> {
    if (pageCount <= 0) return 0 to 0f
    var bestIndex = -1
    var bestVisible = -1
    var bestPosition = 0f
    for (item in items) {
        if (item.index >= pageCount) continue
        val visibleTop = maxOf(item.offset, viewportStart)
        val visibleBottom = minOf(item.offset + item.size, viewportEnd)
        val visible = visibleBottom - visibleTop
        if (visible > bestVisible) {
            bestVisible = visible
            bestIndex = item.index
            bestPosition = if (item.size > 0) {
                item.index + ((viewportStart - item.offset).toFloat() / item.size)
                    .coerceIn(0f, 1f)
            } else {
                item.index.toFloat()
            }
        }
    }
    if (bestIndex < 0) return 0 to 0f
    return bestIndex to bestPosition
}
