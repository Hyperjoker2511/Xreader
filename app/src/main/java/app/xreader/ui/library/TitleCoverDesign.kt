package app.xreader.ui.library

import androidx.compose.ui.graphics.Color

/**
 * v1.3.3 (V132-CHECK 21, scope addition 4): the DESIGNED
 * TITLE COVER.
 *
 * Some sources serve no cover art at all — Syosetu is a
 * text-only site (live-verified: its rankings and book
 * pages carry no cover images) — and a cover can also
 * simply fail to load. Either way the book renders a
 * designed cover, not a bare initial letter: its full
 * title as cover typography over a per-book gradient in
 * the Nocturne Violet family.
 *
 * The hue derives DETERMINISTICALLY from the title, so
 * the same book wears the same colour in the catalogue,
 * the Library, search and its own page — a cover the
 * reader can recognise a book by. The band stays inside
 * the violet family (indigo → violet → magenta-violet):
 * varied enough to tell books apart, never off-brand.
 * It is honest typography, never imitation artwork.
 */
object TitleCoverDesign {

    /** The violet band, in HSV degrees. */
    const val HUE_MIN: Float = 232f
    const val HUE_SPAN: Int = 63

    /** The same title always yields the same hue. */
    fun hueFor(title: String): Float {
        val key = title.trim().lowercase()
        val hash = key.hashCode() and Int.MAX_VALUE
        return HUE_MIN + (hash % HUE_SPAN)
    }

    /** Vertical gradient stops for the title cover: a lit
     * upper tone settling into a deep base, both in the
     * book's hue — dark enough for white cover type. */
    fun gradientColors(title: String): List<Color> {
        val hue = hueFor(title)
        return listOf(
            Color.hsv(hue, 0.52f, 0.34f),
            Color.hsv(hue, 0.62f, 0.16f)
        )
    }

    /** The accent rule colour at the cover's head. */
    fun accentColor(title: String): Color =
        Color.hsv(hueFor(title), 0.45f, 0.85f)

    /** Whether a book renders the designed title cover:
     * no cover model at all, or a blank one. A real cover
     * model always wins over the generated cover. */
    fun usesTitleCover(coverModel: Any?): Boolean = when (coverModel) {
        null -> true
        is String -> coverModel.isBlank()
        is CharSequence -> coverModel.isBlank()
        else -> false
    }
}
