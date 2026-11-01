package app.xreader.domain.reader

/** Reading mode: classic pages, or one continuous column. */
enum class ReaderMode { PAGED, SCROLL }

/** The user's page-turn override; AUTO lets the device tier decide. */
enum class PageTurnOverride { AUTO, FULL, MID, MINIMAL }

/** Bundled reader fonts, plus fonts the user imports himself. */
enum class ReaderFontChoice { LITERATA, LORA, MERRIWEATHER, NOTO_SERIF, JAKARTA, INTER, USER }

/**
 * v1.3.2 (items 86/87): the plain-words paragraph-spacing choice.
 * It writes through to [ReaderConfig.paragraphSpacingDp] — the
 * stored value stays a dp float exactly as before; the choice is
 * just the three named stops on that scale.
 */
enum class ParagraphSpacing(val dp: Float, val label: String) {
    COMPACT(4f, "Compact"),
    NORMAL(10f, "Normal"),
    RELAXED(18f, "Relaxed");

    companion object {
        /** The named stop nearest a stored dp value. */
        fun of(dp: Float): ParagraphSpacing =
            entries.minByOrNull { kotlin.math.abs(it.dp - dp) } ?: NORMAL
    }
}

enum class ReaderAlignment { LEFT, JUSTIFY }

/**
 * Everything the reader needs to render one chapter (PLAN §6, §2.3).
 * Persisted by `ReaderPrefs` (DataStore); sanitised by
 * [ReaderValidation] on every read so a corrupt stored value can never
 * produce a broken page (PLAN §1.5).
 */
data class ReaderConfig(
    val mode: ReaderMode = ReaderMode.PAGED,
    /** ReaderPalette.name — "AMOLED" is the Stitch default (§2.3). */
    val paletteName: String = "AMOLED",
    /** v1.0.1: replace the palette's ink with the maximum-contrast
     * pair for its page (pure white on dark pages, near-black on
     * light ones) — see ReaderPalette.withHighContrastText. */
    val highContrastText: Boolean = false,
    val fontChoice: ReaderFontChoice = ReaderFontChoice.LITERATA,
    /** File name inside filesDir/fonts when [fontChoice] is USER. */
    val userFontName: String? = null,
    val fontSizeSp: Float = 19f,
    val lineHeightMultiplier: Float = 1.55f,
    val paragraphSpacingDp: Float = 10f,
    val marginHorizontalDp: Float = 24f,
    val marginVerticalDp: Float = 20f,
    val alignment: ReaderAlignment = ReaderAlignment.LEFT,
    val bionicReading: Boolean = false,
    /** In-reader brightness (window brightness 0..1); null = system. */
    val brightness: Float? = null,
    /** Amber warm-light overlay strength, 0..1. */
    val warmLight: Float = 0f,
    val volumeKeyTurn: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** e-Ink mode: physics off, instant high-contrast page changes. */
    val eInkMode: Boolean = false,
    val turnOverride: PageTurnOverride = PageTurnOverride.AUTO,
    /** Two facing pages on wide screens (DESIGN.md tablet rules). */
    val twoPageSpread: Boolean = true,
    /** Labs: ML Kit eye-tracking page turns. Off by default (§0.2). */
    val eyeTracking: Boolean = false,
    /** Speech rate multiplier for TTS / overlay playback. */
    val speechRate: Float = 1f,
    /** v1.0.1 auto-scroll (scroll mode) / auto page-turn (paged mode):
     * the master switch from the reader sheet's Auto-scroll section. */
    val autoScrollEnabled: Boolean = false,
    /** Auto-scroll speed multiplier 0.25–3 (see AutoScroll). */
    val autoScrollSpeed: Float = 1f,
    /** v1.0.1: how the paged reader's tap zones are laid out. */
    val tapZoneLayout: TapZoneLayout = TapZoneLayout.STANDARD,
    /** v1.0.1: paged-mode reading direction (manga-style RTL). */
    val pagedDirection: PagedDirection = PagedDirection.LTR,
    /** v1.2.0 (WS2, manga zoom Option A): Mihon's long-strip
     * "Double-tap to zoom" — a double-tap on an image block
     * toggles 1× ↔ 2× zoom at the tapped point. On by default;
     * off leaves pinch only. Applies to image blocks in the
     * scroll/stream readers (and paged mode via the same
     * ZoomableImage component). */
    val doubleTapToZoom: Boolean = true,
    /** v1.2.0 (WS2, manga zoom Option A): Mihon's long-strip
     * "Disable zoom out" — when on, a pinch can never shrink an
     * image below its fit size (scale floor 1× instead of 0.5×).
     * Off by default, matching Mihon/Legado. */
    val disableZoomOut: Boolean = false,
    /** v1.0.1 (QuickNovel lesson): how chapters follow each other. */
    val chapterTransition: ChapterTransition = ChapterTransition.CHAPTER_BY_CHAPTER,
    /** v1.0.1: muted clock/battery overlay on the page while reading. */
    val showPageOverlay: Boolean = false,
    /** v1.0.2 (sweep): a reading line/ruler — a soft guide line at
     * reading height that helps the eye track across long lines. */
    val readingLine: Boolean = false,
    /** v1.0.2 (R18/G18): hardware-keyboard reader control preset
     * for the tablet — suspended while a sheet or the chrome has
     * focus. */
    val keyboardPreset: KeyboardPreset = KeyboardPreset.STANDARD,
    /** v1.0.1 custom colour pair: paletteName "Custom" uses these
     * ARGB values (stored as Longs for DataStore). */
    val customPageArgb: Long = 0xFF000000L,
    val customTextArgb: Long = 0xFFE8DFD0L
)

/** How the reader moves between chapters (v1.0.1). */
enum class ChapterTransition { CONTINUOUS, CHAPTER_BY_CHAPTER, PULL_FOR_NEXT }

/** Hardware-keyboard control presets (v1.0.2, R18/G18). */
enum class KeyboardPreset { STANDARD, PAGE_KEYS, OFF }

/** Tap-zone layouts for the paged reader (v1.0.1 customisation). */
enum class TapZoneLayout { STANDARD, REVERSED, NINE_ZONE, OFF }

/** Paged-mode page order (v1.0.1 customisation). */
enum class PagedDirection { LTR, RTL }

/** Pure range/parse validation for [ReaderConfig] — unit-tested. */
object ReaderValidation {
    val FONT_SIZE_RANGE = 12f..34f
    val LINE_HEIGHT_RANGE = 1.1f..2.2f
    val PARAGRAPH_SPACING_RANGE = 0f..32f
    val MARGIN_H_RANGE = 0f..72f
    val MARGIN_V_RANGE = 0f..64f
    val WARM_LIGHT_RANGE = 0f..0.6f
    val SPEECH_RATE_RANGE = 0.5f..2f
    val AUTO_SCROLL_SPEED_RANGE = 0.25f..3f

    private fun clamp(value: Float, range: ClosedFloatingPointRange<Float>, fallback: Float): Float =
        if (value.isNaN()) fallback else value.coerceIn(range.start, range.endInclusive)

    // v1.2.0 (WS2): the zoom flags (doubleTapToZoom /
    // disableZoomOut) are plain booleans — every value is valid,
    // so validate() passes them through untouched by design.
    fun validate(config: ReaderConfig): ReaderConfig = config.copy(
        fontSizeSp = clamp(config.fontSizeSp, FONT_SIZE_RANGE, 19f),
        lineHeightMultiplier = clamp(config.lineHeightMultiplier, LINE_HEIGHT_RANGE, 1.55f),
        paragraphSpacingDp = clamp(config.paragraphSpacingDp, PARAGRAPH_SPACING_RANGE, 10f),
        marginHorizontalDp = clamp(config.marginHorizontalDp, MARGIN_H_RANGE, 24f),
        marginVerticalDp = clamp(config.marginVerticalDp, MARGIN_V_RANGE, 20f),
        warmLight = clamp(config.warmLight, WARM_LIGHT_RANGE, 0f),
        speechRate = clamp(config.speechRate, SPEECH_RATE_RANGE, 1f),
        autoScrollSpeed = clamp(config.autoScrollSpeed, AUTO_SCROLL_SPEED_RANGE, 1f),
        brightness = config.brightness?.let {
            if (it.isNaN()) null else it.coerceIn(0.02f, 1f)
        },
        paletteName = config.paletteName.ifBlank { "AMOLED" },
        userFontName = config.userFontName?.takeIf { it.isNotBlank() }
    )

    fun <T : Enum<T>> parseEnum(raw: String?, values: Array<T>, fallback: T): T =
        values.firstOrNull { it.name == raw } ?: fallback
}
