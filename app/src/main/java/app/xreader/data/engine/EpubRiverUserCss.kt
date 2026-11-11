package app.xreader.data.engine

import app.xreader.ui.localreader.EpubBookSettings
import app.xreader.ui.localreader.EpubPlateFilter
import app.xreader.ui.localreader.EpubThemeChoice
import java.util.Locale
import kotlin.math.roundToInt

/**
 * v1.5.31 (EPUB RIVER R1, plan §2.4): the river's typography
 * submission. The settings object is unchanged ([EpubBookSettings],
 * the same per-book `LocalReaderPrefs` keys); only the last hop
 * changes — instead of `EpubPreferences` → `submitPreferences`,
 * the same fields render into a `<style id="xr-user">` block the
 * river WebView swaps LIVE on every settings change, the same
 * liveness the settings-honesty audit verified for the toolkit
 * road.
 *
 * The v1.5.20 contract is preserved by construction: reader
 * settings win over publisher styling for EXACTLY the properties
 * the owner controls — `!important` rides precisely the seven
 * advanced properties + size/weight/family, and every other
 * publisher rule stays in force.
 *
 * v1.5.32: heading scale IS normalised (see [generate]) — R1
 * left publisher heading sizes in force per plan §2.4 and the
 * owner's phone proof convicted them (chapter titles rendered
 * ~2.5× body text). The one property family beyond the owner's
 * seven that the user CSS now overrides is heading SIZE (+ the
 * margins that push a heading around); heading weight and
 * alignment remain the publisher's.
 */
object EpubRiverUserCss {

    /** Theme fallbacks used only when the settings carry no
     * explicit colour (the presets always set both). Close to
     * ReadiumCSS's own theme defaults, so a book that never
     * picked colours reads the same on both roads. */
    private fun defaultForeground(theme: EpubThemeChoice): Int = when (theme) {
        EpubThemeChoice.LIGHT -> 0xFF1A1A1A.toInt()
        EpubThemeChoice.DARK -> 0xFFF2F2F2.toInt()
        EpubThemeChoice.SEPIA -> 0xFF4A3520.toInt()
    }

    private fun defaultBackground(theme: EpubThemeChoice): Int = when (theme) {
        EpubThemeChoice.LIGHT -> 0xFFFFFFFF.toInt()
        EpubThemeChoice.DARK -> 0xFF000000.toInt()
        EpubThemeChoice.SEPIA -> 0xFFF7ECD2.toInt()
    }

    private fun cssColor(argb: Int): String =
        String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** Compact decimal: 1.0 → "1", 0.85 → "0.85", 1.06 → "1.06". */
    private fun num(value: Double): String {
        val asLong = value.toLong()
        if (value == asLong.toDouble()) return asLong.toString()
        return String.format(Locale.US, "%.3f", value)
            .trimEnd('0').trimEnd('.')
    }

    private fun bundledStack(fontFamilyName: String?): String? = when (fontFamilyName) {
        "SERIF" -> "Georgia, 'Times New Roman', 'Noto Serif', serif"
        "SANS_SERIF" -> "Roboto, 'Helvetica Neue', Arial, sans-serif"
        "MONOSPACE" -> "'Roboto Mono', 'Courier New', monospace"
        "OPEN_DYSLEXIC" -> "'OpenDyslexic', sans-serif"
        "ACCESSIBLE_DFA" -> "'Accessible DFA', sans-serif"
        "IA_WRITER_DUOSPACE" -> "'iA Writer Duospace', monospace"
        else -> null
    }

    fun generate(settings: EpubBookSettings, session: ReadiumEpubSession): String {
        val foreground = settings.textColorArgb ?: defaultForeground(settings.theme)
        val background = settings.backgroundColorArgb ?: defaultBackground(settings.theme)
        // Readium's pageMargins is a factor over the toolkit's
        // base gutter; the river's base gutter is 20 px at 1.0.
        val marginPx = (20.0 * settings.pageMargins).roundToInt().coerceAtLeast(0)

        val css = StringBuilder()
        css.append(":root{")
            .append("--xr-fg:").append(cssColor(foreground)).append(';')
            .append("--xr-bg:").append(cssColor(background)).append(';')
            .append("--xr-margin-x:").append(marginPx).append("px;")
            // Text size: Readium's fontSize is a fraction of the
            // base size; on the merged shell it is the root
            // font-size percentage (plan §2.4 mapping).
            .append("font-size:").append(num(settings.fontSize * 100.0)).append("%;")
            .append('}')

        // The user font (resolved by the session at open, as
        // today) declares through the same fake-origin road the
        // book's own resources ride (§2.3).
        val userFont = session.userFont
        val userFontActive = settings.userFontFileName != null && userFont != null
        if (userFontActive) {
            css.append("@font-face{font-family:'").append(userFont!!.familyName)
                .append("';src:url(\"")
                .append(riverResourceUrl(userFont.href))
                .append("\");}")
        }
        val familyStack = if (userFontActive) {
            "'" + userFont!!.familyName + "', Georgia, serif"
        } else {
            bundledStack(settings.fontFamilyName)
        }

        // Flow-level (inherited) properties: family + weight.
        // Headings keep their publisher/UA weight and family —
        // inheritance only reaches elements no rule targets.
        val flow = StringBuilder()
        if (familyStack != null) flow.append("font-family:").append(familyStack).append(';')
        settings.fontWeight?.let { weight ->
            val numeric = ((weight * 400.0).roundToInt() / 50 * 50).coerceIn(100, 900)
            flow.append("font-weight:").append(numeric).append(';')
        }
        if (flow.isNotEmpty()) {
            css.append("#xr-flow{").append(flow).append('}')
        }

        // The seven advanced properties, on the paragraph-level
        // selector, each present only when the setting speaks —
        // a null field leaves the publisher's rule in force,
        // exactly as the toolkit road does.
        val para = StringBuilder()
        settings.paragraphSpacing?.let {
            para.append("margin-top:").append(num(it)).append("em !important;")
            para.append("margin-bottom:").append(num(it)).append("em !important;")
        }
        settings.paragraphIndent?.let {
            para.append("text-indent:").append(num(it)).append("em !important;")
        }
        settings.letterSpacing?.let {
            para.append("letter-spacing:").append(num(it)).append("em !important;")
        }
        settings.wordSpacing?.let {
            para.append("word-spacing:").append(num(it)).append("em !important;")
        }
        settings.lineHeight?.let {
            para.append("line-height:").append(num(it)).append(" !important;")
        }
        para.append("text-align:").append(if (settings.justify) "justify" else "start")
            .append(" !important;")
        para.append("hyphens:").append(if (settings.hyphens) "auto" else "none")
            .append(" !important;")
        css.append("#xr-flow p,#xr-flow li{").append(para).append('}')

        // Headings (v1.5.32): a sane scale relative to the
        // owner's text size — h1 at 1.5× body stepping down to
        // h6 at body size. Sizes are in em, so they ride the
        // root font-size (the Text size setting) exactly like
        // the body text does. `!important` on size + margins so
        // publisher rules lose ON SIZE SPECIFICALLY: fixed px
        // sizes, viewport-unit sizes, and the page-push margins
        // publishers use to fake a page break before a chapter
        // title. Line-height is set with the size (a 1.5× line
        // at body leading looks broken); weight and alignment
        // stay the publisher's — a bold centred chapter title
        // still reads bold and centred, just at a sane size.
        css.append("#xr-flow h1{font-size:1.5em !important;")
            .append("line-height:1.3 !important;")
            .append("margin-top:0.85em !important;")
            .append("margin-bottom:0.45em !important;}")
        css.append("#xr-flow h2{font-size:1.35em !important;")
            .append("line-height:1.3 !important;")
            .append("margin-top:0.8em !important;")
            .append("margin-bottom:0.4em !important;}")
        css.append("#xr-flow h3{font-size:1.2em !important;")
            .append("line-height:1.3 !important;")
            .append("margin-top:0.75em !important;")
            .append("margin-bottom:0.35em !important;}")
        css.append("#xr-flow h4{font-size:1.1em !important;")
            .append("line-height:1.3 !important;")
            .append("margin-top:0.7em !important;")
            .append("margin-bottom:0.3em !important;}")
        css.append("#xr-flow h5{font-size:1.05em !important;")
            .append("line-height:1.3 !important;")
            .append("margin-top:0.65em !important;")
            .append("margin-bottom:0.3em !important;}")
        css.append("#xr-flow h6{font-size:1em !important;")
            .append("line-height:1.3 !important;")
            .append("margin-top:0.6em !important;")
            .append("margin-bottom:0.3em !important;}")

        // Illustrations in the dark theme: the exact ReadiumCSS
        // semantics the audit recorded (plan §2.4) — a stored
        // DARKEN behaves identically on the river.
        if (settings.theme == EpubThemeChoice.DARK) {
            when (settings.plateFilter) {
                EpubPlateFilter.DARKEN ->
                    css.append("#xr-flow img{filter:brightness(0.8);}")
                EpubPlateFilter.INVERT ->
                    css.append("#xr-flow img{filter:invert(1);}")
                EpubPlateFilter.ORIGINAL -> Unit
            }
        }
        return css.toString()
    }
}
