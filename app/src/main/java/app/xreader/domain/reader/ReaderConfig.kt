package app.xreader.domain.reader

/** Reading mode: classic pages, or one continuous column. */
enum class ReaderMode { PAGED, SCROLL }

/**
 * Manga programme (slice M2): how an IMAGE chapter is read —
 * one continuous vertical strip (webtoon style) or one page
 * per screen. The plan's default at BOTH tiers of the
 * option-scope rule is [WEBTOON]: the value in
 * [ReaderConfig.imageMode] is the global default for every
 * series, and a per-series override (BookReaderPrefs) set from
 * inside the reader wins for that series only.
 */
enum class ImageReaderMode(val label: String) {
    WEBTOON("Webtoon"),
    PAGED("Paged"),
    /** v1.5.0 (Part C — Komikku transplant): right-to-left
     * paging (the donor's RTL viewer; stored names of the
     * two original entries are unchanged). */
    PAGED_RTL("Paged · RTL"),
    /** v1.5.0 (Part C): one page per screen, vertical turns
     * (the donor's vertical pager). */
    PAGED_VERTICAL("Vertical")
}

/**
 * v1.5.0 (Part C): the manga reader's tap-zone layouts —
 * Komikku's five navigation geometries, ported as pure
 * region maths (see MangaTapZones in the UI layer, adapted
 * from Komikku's ViewerNavigation, Apache-2.0). DEFAULT
 * keeps the donor's own defaults: right-and-left for paged
 * modes, the L shape for webtoon/vertical.
 */
enum class MangaTapLayout(val label: String) {
    DEFAULT("Default"),
    L_SHAPE("L shape"),
    RIGHT_AND_LEFT("Right and left"),
    KINDLISH("Kindlish"),
    EDGE("Edges"),
    DISABLED("Off — menu only")
}

/** v1.5.0 (Part C): tap-zone inversion (the donor's
 * TappingInvertMode). */
enum class TapInvert(val label: String) {
    NONE("Not inverted"),
    HORIZONTAL("Inverted ↔"),
    VERTICAL("Inverted ↕"),
    BOTH("Inverted both")
}

/** v1.5.0 (Part C): how a paged page fits its screen (the
 * donor's image scale types, the Compose-expressible set). */
enum class MangaScaleType(val label: String) {
    FIT("Fit screen"),
    FIT_WIDTH("Fit width"),
    FIT_HEIGHT("Fit height"),
    STRETCH("Stretch")
}

/** v1.5.0 (Part C): the manga reader's orientation
 * (the donor's per-manga second flag, generalised). */
enum class MangaOrientation(val label: String) {
    FREE("Free"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

/** The user's page-turn override; AUTO lets the device tier decide. */
enum class PageTurnOverride { AUTO, FULL, MID, MINIMAL }

/** Bundled reader fonts, plus fonts the user imports himself. */
enum class ReaderFontChoice { LITERATA, LORA, MERRIWEATHER, NOTO_SERIF, JAKARTA, INTER, USER }

/**
 * v1.3.2 (items 86/87): the plain-words paragraph-spacing choice.
 * It writes through to [ReaderConfig.paragraphSpacingDp] — the
 * stored value stays a dp float exactly as before; the choice is
 * just the three named stops on that scale.
 *
 * v1.5.41: the sheet's exact-gap slider writes any dp in the
 * validation range, so a stored value can sit BETWEEN stops.
 * [of] still answers "nearest stop" for summaries that want a
 * word for a value; the sheet's chips use [exact] so a custom
 * gap never lights a preset it is not.
 */
enum class ParagraphSpacing(val dp: Float, val label: String) {
    COMPACT(4f, "Compact"),
    NORMAL(10f, "Normal"),
    RELAXED(18f, "Relaxed");

    companion object {
        /** The named stop nearest a stored dp value. */
        fun of(dp: Float): ParagraphSpacing =
            entries.minByOrNull { kotlin.math.abs(it.dp - dp) } ?: NORMAL

        /** The named stop a stored dp value matches EXACTLY,
         * or null when the value is a custom gap. */
        fun exact(dp: Float): ParagraphSpacing? =
            entries.firstOrNull { it.dp == dp }
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
    /** v1.7 (code 106, owner's "same to same" ruling
     * 2026-11-22): the default face is NOTO_SERIF — the
     * family Moon+'s "serif" names (its factory default,
     * useCssFont off), measured off his Moon+ screenshot of
     * the 'Grieving Soul' Prologue. Literata stays a user
     * option. Stored LITERATA values that are the untouched
     * old default move once (see ReaderPrefs). */
    val fontChoice: ReaderFontChoice = ReaderFontChoice.NOTO_SERIF,
    /** File name inside filesDir/fonts when [fontChoice] is USER. */
    val userFontName: String? = null,
    /** Code 106: 17 sp — the size the Moon+ photo measures
     * to (cap height 2.86 % of page width under Noto Serif);
     * the old 19 sp default moves once for untouched stores. */
    val fontSizeSp: Float = 17f,
    /** Code 106: 1.43 — the photo's measured pitch ÷ em
     * under Noto Serif (Moon+'s lineSpace has no plain em
     * equivalent; the photo is the binding number). */
    val lineHeightMultiplier: Float = 1.43f,
    /** v1.7 Moon+ standard fix (Part 4): the fresh-install
     * default was moved NORMAL → COMPACT. Code 106 (the
     * "same to same" ruling) moves it once more, COMPACT →
     * 0 dp: in the Moon+ photo paragraphs separate by their
     * first-line indent alone, with no extra vertical air
     * (pitch is uniform across paragraph boundaries). The
     * named stops and the slider range are unchanged;
     * stored values are untouched except the one-time
     * migration of an untouched old default (see
     * ReaderPrefs). */
    val paragraphSpacingDp: Float = 0f,
    /** Code 106: Moon+'s large-phone margin branch, which
     * the photo measures to (6.35 % of width ≈ 26 dp). */
    val marginHorizontalDp: Float = 26f,
    /** Code 106: Moon+'s fullscreen-phone top/bottom
     * margin (30 dp). */
    val marginVerticalDp: Float = 30f,
    /** Code 106: JUSTIFY — the photo's body text is flush
     * on both edges (Moon+ reaches it via the book's CSS
     * through its factory cssJustify; the owner's ruling
     * makes the photo state the default outright). */
    val alignment: ReaderAlignment = ReaderAlignment.JUSTIFY,
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
    val customTextArgb: Long = 0xFFE8DFD0L,
    /** Manga programme (slice M2): the GLOBAL default image-
     * chapter reading mode (option-scope rule tier 1). A
     * series' own override, set inside its reader, wins for
     * that series only and never writes back here. Appended
     * last so existing constructor call sites are untouched. */
    val imageMode: ImageReaderMode = ImageReaderMode.WEBTOON,
    // ── v1.5.0 (Part C — Komikku reader transplant): the
    // manga reader's own settings surface. Every field is a
    // GLOBAL default here (Settings tier); the ones a series
    // can override from inside its reader live in
    // BookReaderOverride and resolve through MangaSettings.
    // Keys/defaults mirror Komikku's ReaderPreferences.
    /** Tap-zone layout for image chapters. */
    val mangaTapLayout: MangaTapLayout = MangaTapLayout.DEFAULT,
    /** Tap-zone inversion. */
    val mangaTapInvert: TapInvert = TapInvert.NONE,
    /** Komikku's smaller tap zones (25% regions, not 33%). */
    val mangaSmallerTapZones: Boolean = false,
    /** Double-tap zoom in WEBTOON mode (paged mode uses
     * [doubleTapToZoom], as it always has). Donor default on. */
    val webtoonDoubleTapZoom: Boolean = true,
    /** Pinch zoom of the whole strip in webtoon mode. */
    val webtoonPinchZoom: Boolean = true,
    /** Webtoon zoom's floor: on = never below fit (1×). */
    val webtoonDisableZoomOut: Boolean = false,
    /** Trim uniform page borders — paged modes. */
    val cropBordersPaged: Boolean = false,
    /** Trim uniform page borders — webtoon mode. */
    val cropBordersWebtoon: Boolean = false,
    /** Greyscale pages (colour-matrix, the donor's recipe). */
    val mangaGrayscale: Boolean = false,
    /** Invert page colours. */
    val mangaInvertColors: Boolean = false,
    /** The RGBA colour overlay (the donor's colour filter). */
    val mangaColorFilter: Boolean = false,
    /** The overlay's colour, ARGB (stored as a Long). */
    val mangaColorFilterArgb: Long = 0x00000000L,
    /** Horizontal inset of the webtoon strip, percent 0–25. */
    val webtoonSidePadding: Int = 0,
    /** Paged pages' fit. */
    val mangaScaleType: MangaScaleType = MangaScaleType.FIT,
    /** Orientation while reading image chapters. */
    val mangaOrientation: MangaOrientation = MangaOrientation.FREE,
    /** v1.7 Moon+ standard fix (Part 3): the chapter-end
     * wall's scope in Chapter-by-chapter mode — the scroll
     * stops at a small wall at each chapter's end (and
     * start), and a deliberate pull past it crosses. EPUB
     * books take the wall by default (the Moon+ model);
     * novels keep the flowing crossing unless the owner
     * widens the scope. Appended LAST: positional
     * constructor callers must not shift. */
    val chapterEndWall: ChapterEndWall = ChapterEndWall.FOR_EPUB_BOOKS,
    /** v1.7 (code 106): the first-line paragraph indent —
     * Moon+'s indentParagraph with its factory indentLength
     * 2 × indentWidth (one full-width char ≈ 1 em), ON in
     * the owner's Moon+ and the target of his "same to
     * same" ruling. Carried here, inside the config, so the
     * layout cache's fingerprint (the whole config object)
     * invalidates stale layouts by construction. Applied
     * as a layout-only TextIndent in blockTextStyle: the
     * text itself is never rewritten, so char offsets,
     * highlights, TTS ranges and saved places are
     * untouched. Appended after [chapterEndWall] for the
     * same positional-caller reason it was. */
    val paragraphIndent: Boolean = true,
    /** The indent's depth, in em of the body font size
     * (Moon+'s factory indentLength is 2). */
    val paragraphIndentEm: Float = 2f,
    /** D13 (Moon+ copy): "Show footnotes directly in chapter
     * text" — Moon+'s `footnoteOnBottom` switch (its settings
     * section "Footnotes"). Moon+'s own default is OFF; the
     * owner's ruling (2026-11-22) supersedes: ON, so a book's
     * footnotes are never silently lost. This is the GLOBAL
     * default; a book's own override (BookReaderPrefs), set
     * from inside its reader, wins for that book only. The
     * choice is baked at EPUB prepare time — landed content
     * is keyed on the effective value and a change
     * re-prepares the book once (see EpubPrepareAccess).
     * Appended LAST for the same positional-caller reason as
     * the fields above. */
    val footnotesInText: Boolean = true
)

/** How the reader moves between chapters (v1.0.1).
 * v1.7 one-engine (Q1 RULED): PULL_FOR_NEXT is REMOVED — it was
 * a settings label with no mechanism behind it. A stored
 * "PULL_FOR_NEXT" pref string no longer parses and falls back
 * to CHAPTER_BY_CHAPTER through [ReaderValidation.parseEnum]
 * (ReaderPrefs) — migration is automatic, no format change. */
enum class ChapterTransition { CONTINUOUS, CHAPTER_BY_CHAPTER }

/** Part 3: where the chapter-end wall stands in
 * Chapter-by-chapter mode. FOR_EPUB_BOOKS is the default —
 * imported EPUBs read the full Moon+ way out of the box;
 * novels keep today's flowing crossing until the owner
 * picks EVERY_BOOK. OFF restores the flowing crossing
 * everywhere. Meaningless in CONTINUOUS (no wall exists
 * there), exactly as ChapterTransition is meaningless to
 * the paged surface. */
enum class ChapterEndWall { FOR_EPUB_BOOKS, EVERY_BOOK, OFF }

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
    val INDENT_EM_RANGE = 0.5f..4f
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
        // v1.5.0 (Part C): the webtoon side padding is a
        // percent in the donor's 0–25 range.
        webtoonSidePadding = config.webtoonSidePadding.coerceIn(0, 25),
        fontSizeSp = clamp(config.fontSizeSp, FONT_SIZE_RANGE, 17f),
        lineHeightMultiplier = clamp(config.lineHeightMultiplier, LINE_HEIGHT_RANGE, 1.43f),
        paragraphSpacingDp = clamp(config.paragraphSpacingDp, PARAGRAPH_SPACING_RANGE, 0f),
        marginHorizontalDp = clamp(config.marginHorizontalDp, MARGIN_H_RANGE, 26f),
        marginVerticalDp = clamp(config.marginVerticalDp, MARGIN_V_RANGE, 30f),
        paragraphIndentEm = clamp(config.paragraphIndentEm, INDENT_EM_RANGE, 2f),
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
