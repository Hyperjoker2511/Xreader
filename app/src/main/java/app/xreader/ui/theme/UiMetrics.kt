package app.xreader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Adaptive metrics (next-update Patch 5, sizing piece 1): the
 * layout measures the phone it is actually on instead of carrying
 * one phone's fixed sizes onto every screen. The owner's rule:
 * "It should automatically adjust — there are so many sizes
 * there."
 *
 * A pure width-tier table: every value is a function of the
 * window width alone, pinned by UiMetricsTest. Metrics are
 * STRUCTURAL density only — padding, gaps, heights, counts. This
 * system never touches Density.fontScale or accessibility
 * scaling; the two text sizes below are plain sp values, which
 * the platform scales for the user exactly like any other text.
 *
 * This piece lays the pipe: the table, the CompositionLocal, and
 * the provider the theme installs at the root. Screens adopt the
 * values piece by piece (the Library grid's column count is the
 * first consumer — see the gridColumns note); a value sitting
 * unused until its piece lands is deliberate, not half-wired.
 */

/** Width tiers: COMPACT below 400 dp, REGULAR 400–600 dp,
 * EXPANDED above 600 dp. Boundaries are pinned by tests. */
enum class UiTier { COMPACT, REGULAR, EXPANDED }

fun uiTierFor(widthDp: Int): UiTier = when {
    widthDp < 400 -> UiTier.COMPACT
    widthDp <= 600 -> UiTier.REGULAR
    else -> UiTier.EXPANDED
}

/** One tier's full metric row. Every field is structural —
 * see the file doc. */
data class UiMetrics(
    val tier: UiTier,
    /** Side padding of main screens. */
    val screenPadding: Dp,
    /** Inner padding of cards. */
    val cardPadding: Dp,
    /** Cover-grid columns. In the Library this is the AUTO
     * default behind the user's preference: it applies only
     * while no explicit column choice is stored (see
     * LibraryState.gridColumnsAuto); a stored choice wins. */
    val gridColumns: Int,
    /** Minimum height of a content card. */
    val cardMinHeight: Dp,
    /** Height of the floating dock. */
    val dockHeight: Dp,
    /** Screen headline size. */
    val titleSize: TextUnit,
    /** Default body text size for metric-driven surfaces. */
    val bodySize: TextUnit,
    /** Size of a circular icon badge/button. */
    val iconBadge: Dp,
    /** Vertical gap between sections. */
    val sectionGap: Dp,
    /** Gap below the status-bar inset to the screen title
     * (Patch 6 makes the shell its single owner). */
    val topGap: Dp,
    /** The card corner radius, 24.dp on every tier — a DISPLAY
     * field so metric-driven surfaces can read the one radius
     * from the same row. It is deliberately NOT wired to book
     * covers: covers keep their real radii (the design system's
     * 24.dp cards, the book page's exempt 14.dp). */
    val coverCorner: Dp
)

fun uiMetricsFor(tier: UiTier): UiMetrics = when (tier) {
    UiTier.COMPACT -> UiMetrics(
        tier = UiTier.COMPACT,
        screenPadding = 12.dp,
        cardPadding = 14.dp,
        gridColumns = 3,
        cardMinHeight = 96.dp,
        dockHeight = 56.dp,
        titleSize = 21.sp,
        bodySize = 14.sp,
        iconBadge = 36.dp,
        sectionGap = 16.dp,
        topGap = 4.dp,
        coverCorner = 24.dp
    )
    UiTier.REGULAR -> UiMetrics(
        tier = UiTier.REGULAR,
        screenPadding = 16.dp,
        cardPadding = 18.dp,
        gridColumns = 4,
        cardMinHeight = 104.dp,
        dockHeight = 64.dp,
        titleSize = 24.sp,
        bodySize = 15.sp,
        iconBadge = 40.dp,
        sectionGap = 20.dp,
        topGap = 6.dp,
        coverCorner = 24.dp
    )
    UiTier.EXPANDED -> UiMetrics(
        tier = UiTier.EXPANDED,
        screenPadding = 24.dp,
        cardPadding = 22.dp,
        gridColumns = 6,
        cardMinHeight = 112.dp,
        dockHeight = 68.dp,
        titleSize = 28.sp,
        bodySize = 16.sp,
        iconBadge = 44.dp,
        sectionGap = 24.dp,
        topGap = 8.dp,
        coverCorner = 24.dp
    )
}

fun uiMetricsFor(widthDp: Int): UiMetrics = uiMetricsFor(uiTierFor(widthDp))

/**
 * Bottom content padding for a screen that scrolls under the
 * floating dock: with the dock visible the content must clear
 * the dock's height plus a 24.dp breathing margin; without it a
 * plain 16.dp end margin. The tier's topGap rides along in both
 * branches so the end of a list breathes like its start. Pure —
 * Patch 6's dock-visibility work is the consumer.
 */
fun bottomContentPadding(metrics: UiMetrics, dockVisible: Boolean): Dp =
    (if (dockVisible) metrics.dockHeight + 24.dp else 16.dp) + metrics.topGap

/** The active metrics row. The default (no provider above) is
 * the REGULAR row for a 400 dp width — the middle of the road,
 * never an extreme. */
val LocalUiMetrics = compositionLocalOf { uiMetricsFor(400) }

/** The metrics for the CURRENT window width, read from
 * LocalConfiguration — the width source the tree already uses
 * (PdfDocumentReader, KomikkuReaderScreen). Reading it in
 * composition means a rotation or fold change recomposes with
 * the new tier. */
@Composable
fun rememberUiMetrics(): UiMetrics {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) { uiMetricsFor(widthDp) }
}

/** Installs [LocalUiMetrics] for a subtree from the live window
 * width. XreaderTheme installs it at the app root, so any screen
 * can read LocalUiMetrics.current. */
@Composable
fun ProvideUiMetrics(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUiMetrics provides rememberUiMetrics()) {
        content()
    }
}
