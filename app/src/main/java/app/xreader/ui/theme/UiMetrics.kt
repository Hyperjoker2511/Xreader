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
import kotlin.math.roundToInt

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
 * v1.6.2 (App size): the tier row is now only the BASE. The
 * provider scales it by the user's one App size number (see
 * [scaledMetrics]) and the theme scales the fontScale by the
 * same number — the manual control the owner asked for
 * ("customizable sizes… no need to automatically adopt")
 * replaces the tier as the size the user actually sees.
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
 * App size (v1.6.2): the owner's ONE size number applied to a
 * tier row. The tier now only picks the BASE row from the
 * phone's width (guardrails); what the user sees is this
 * scaling — "customizable sizes small normal… there's no
 * need to automatically adopt" (owner, 2026-11-16). The
 * factor arrives already clamped to 0.70–1.30 by
 * AppearanceValidation; the theme's fontScale is scaled by
 * the same number, so layout and words stay in proportion.
 *
 * Scaled: every Dp size (rounded to whole dp) and the two
 * sp sizes. NOT scaled: gridColumns (a count, not a size —
 * the Library's Auto default and the Cover size control own
 * counts) and coverCorner (the one-radius rule, 24.dp on
 * every tier at every factor). The tier itself is carried
 * through untouched: width-triggered layout variants keep
 * reading the phone's real tier. Factor 1.00 is an exact
 * identity — every row value is whole dp / exact sp, so the
 * scaled row is bit-identical to the base row.
 */
fun scaledMetrics(base: UiMetrics, factor: Float): UiMetrics {
    fun scaleDp(value: Dp): Dp = (value.value * factor).roundToInt().dp
    fun scaleSp(value: TextUnit): TextUnit = (value.value * factor).sp
    return base.copy(
        screenPadding = scaleDp(base.screenPadding),
        cardPadding = scaleDp(base.cardPadding),
        cardMinHeight = scaleDp(base.cardMinHeight),
        dockHeight = scaleDp(base.dockHeight),
        titleSize = scaleSp(base.titleSize),
        bodySize = scaleSp(base.bodySize),
        iconBadge = scaleDp(base.iconBadge),
        sectionGap = scaleDp(base.sectionGap),
        topGap = scaleDp(base.topGap)
    )
}

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

/**
 * The bottom zone the OVERLAY dock occupies when it floats
 * over a destination (visible-layer fix round, spec §A.2):
 * floating — the bar (64.dp) + its 10.dp top pad + 24.dp
 * bottom pad (the 10.dp bottom pad + the 14.dp chrome lift);
 * docked — the flush bar alone. The window's own navigation
 * inset is NOT in here: the shell adds it (it varies per
 * device — 0 under gesture nav, ~48.dp under 3-button), plus
 * a 12.dp breath, when it publishes [LocalDockClearance].
 */
fun dockOverlayClearance(docked: Boolean): Dp = if (docked) 64.dp else 98.dp

/**
 * What a scroll end must pad: its standing padding, or the
 * dock clearance when the dock overlays it — whichever is
 * larger. Existing wins whenever it is larger, so no surface
 * ever loses padding it has today; where the dock does not
 * overlay (clearance 0.dp — dock hidden, immersive, or the
 * rail shell) the result is byte-identical to the standing
 * padding. The single repayment rule for the overlay dock.
 */
fun effectiveBottomPadding(existing: Dp, clearance: Dp): Dp = maxOf(existing, clearance)

/** The active metrics row. The default (no provider above) is
 * the REGULAR row for a 400 dp width — the middle of the road,
 * never an extreme. */
val LocalUiMetrics = compositionLocalOf { uiMetricsFor(400) }

/** The user's App size factor (v1.6.2) — the same number
 * [scaledMetrics] applied to the metrics row and the theme
 * applied to the font scale. Provided beside [LocalUiMetrics]
 * by [ProvideUiMetrics]; the default (no provider above) is
 * 1.00 (Normal). Consumers that resolve COUNTS from sizes —
 * the Library's Cover size control — read the factor here. */
val LocalAppSize = compositionLocalOf { 1.0f }

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
 * width, scaled by the user's App size (v1.6.2 — [appSize]
 * arrives clamped from AppearancePrefs; 1.00 installs the raw
 * tier row unchanged). XreaderTheme installs it at the app
 * root, so any screen can read LocalUiMetrics.current. */
@Composable
fun ProvideUiMetrics(appSize: Float = 1.0f, content: @Composable () -> Unit) {
    val base = rememberUiMetrics()
    val metrics = remember(base, appSize) { scaledMetrics(base, appSize) }
    CompositionLocalProvider(
        LocalUiMetrics provides metrics,
        LocalAppSize provides appSize
    ) {
        content()
    }
}
