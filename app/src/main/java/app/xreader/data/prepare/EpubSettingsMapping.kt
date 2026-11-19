package app.xreader.data.prepare

import app.xreader.ui.localreader.EpubBookSettings
import app.xreader.ui.localreader.EpubThemePreset
import app.xreader.ui.localreader.currentPreset

/**
 * v1.7 Patch 3 (Item 4g): the settings mapping, as one pure
 * function. `EpubBookSettings` is per-book; the core's
 * per-book override (`BookReaderOverride`) covers palette +
 * font size only, so those are the carries. Everything else
 * has its fate named in the spec's table — dropped
 * (fontWeight, letterSpacing, wordSpacing, paragraphIndent,
 * hyphens), collapsed to the core's global settings
 * (scroll mode, volumeKeyTurn, keepScreenOn, autoScrollSpeed,
 * orientation), or superseded by the global `ReaderConfig`
 * (margins, spacing, line height, alignment — the reader's
 * current global values win; nothing is silently carried).
 */
object EpubSettingsMapping {

    /** What prepare writes through `BookReaderPrefs`: a null
     * field CLEARS the book's override for it (the book then
     * follows the global setting, exactly like a novel). */
    data class Mapped(
        val paletteName: String?,
        val fontSizeSp: Float?
    )

    /**
     * The mapping for one book, or null when it must not run
     * at all: a book with NO stored EPUB settings was never
     * shaped on the old road, and its core overrides (if the
     * owner set any directly) are not prepare's to touch.
     *
     * Palette: the stored set's exact preset decides — PAPER
     * → "Paper", SEPIA → "Sepia", NIGHT → "AMOLED" (the
     * core's dark palette; the core has no separate night
     * palette), AMOLED → null (AMOLED IS the core default —
     * clearing keeps the book on the global default instead
     * of pinning it against future global changes). A
     * free-swatch combination (no exact preset) maps to null:
     * arbitrary ARGB page colours have no core palette, and
     * the book follows the global palette rather than a
     * guessed one.
     *
     * Font size: the local road's own seed derives the EPUB
     * size from the global size as `fontSizeSp / 16` (see
     * LocalDocReaderViewModel's open), so `× 16` is the exact
     * inverse. 1.0 (the untouched default) maps to null.
     */
    fun map(settings: EpubBookSettings, hasStoredSettings: Boolean): Mapped? {
        if (!hasStoredSettings) return null
        val paletteName = when (settings.currentPreset()) {
            EpubThemePreset.PAPER -> "Paper"
            EpubThemePreset.SEPIA -> "Sepia"
            EpubThemePreset.NIGHT -> "AMOLED"
            EpubThemePreset.AMOLED -> null
            null -> null
        }
        val fontSizeSp = if (settings.fontSize != 1.0) {
            (settings.fontSize * 16.0).toFloat()
        } else {
            null
        }
        return Mapped(paletteName = paletteName, fontSizeSp = fontSizeSp)
    }
}
