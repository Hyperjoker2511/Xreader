package app.xreader.domain.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The one normalised chapter model (PLAN §4). Every parser produces it,
 * the reader and the AI layer consume only it, and it is what gets
 * written to `content/<bookId>/<chapterId>.json` — chapter text never
 * lives in Room (§1.3).
 *
 * Blocks carry an optional source [id] (the XHTML element id) because
 * EPUB 3 Media Overlays address text by element id (§4) — the overlay
 * clips stored beside the blocks reference those ids.
 */
@Serializable
sealed interface ChapterBlock {

    @Serializable
    @SerialName("heading")
    data class Heading(
        val level: Int,
        val text: String,
        val id: String? = null
    ) : ChapterBlock

    @Serializable
    @SerialName("para")
    data class Paragraph(
        val spans: List<TextSpan>,
        val id: String? = null
    ) : ChapterBlock {
        /** The paragraph as plain text (search, word counts, TTS). */
        val text: String get() = spans.joinToString("") { it.text }

        companion object {
            fun plain(text: String, id: String? = null): Paragraph =
                Paragraph(listOf(TextSpan(text)), id)
        }
    }

    /**
     * An image stored beside the chapter content. [fileName] is a plain
     * base name resolved inside the book's content directory
     * (`content/<bookId>/img-<fileName>`).
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val fileName: String,
        val alt: String = ""
    ) : ChapterBlock

    @Serializable
    @SerialName("rule")
    data object Rule : ChapterBlock
}

@Serializable
data class TextSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false
)

/** One Media Overlay clip: the audio span for one text element (§4). */
@Serializable
data class MediaClip(
    /** XHTML element id the clip narrates (matches a block's id). */
    val textId: String,
    val beginSec: Double,
    val endSec: Double
)

/**
 * EPUB 3 Media Overlay sync data for one chapter: which audio file
 * narrates it and the clip map. Playback/highlighting is Phase 4; this
 * phase parses and persists the model (§4 deliverable).
 */
@Serializable
data class MediaOverlay(
    /** Audio file name inside the book's content directory. */
    val audioFileName: String,
    val clips: List<MediaClip>
)

@Serializable
data class ChapterContent(
    val blocks: List<ChapterBlock> = emptyList(),
    val mediaOverlay: MediaOverlay? = null,
    /** Manga programme (slice M1): an image chapter's ordered
     * pages, when this content came from a MANGA source. Null
     * for every text chapter (and, with the storage layer's
     * encodeDefaults off, absent from their stored files —
     * text content is byte-identical to before). A manga
     * chapter's [blocks] stay empty: its pages ARE its
     * content. See [ChapterPages]. */
    val pages: ChapterPages? = null
) {
    /** True for a manga chapter: content is an ordered page
     * list, not prose blocks. */
    val isImageChapter: Boolean get() = pages?.isEmpty == false
    /** Words across heading + paragraph text (image books count 0). */
    val wordCount: Int
        get() = blocks.sumOf { block ->
            when (block) {
                is ChapterBlock.Heading -> countWords(block.text)
                is ChapterBlock.Paragraph -> countWords(block.text)
                else -> 0
            }
        }

    /** True for image books (CBZ/CBR): content is pages, not prose. */
    val isImageBook: Boolean
        get() = blocks.isNotEmpty() && blocks.all { it is ChapterBlock.Image }

    /** All prose as plain text, blocks separated by blank lines. */
    fun plainText(): String = blocks.mapNotNull { block ->
        when (block) {
            is ChapterBlock.Heading -> block.text
            is ChapterBlock.Paragraph -> block.text
            else -> null
        }
    }.joinToString("\n\n")

    companion object {
        /** The word-count rule used everywhere (shelf stats, WPM base). */
        fun countWords(text: String): Int =
            if (text.isBlank()) 0 else text.trim().split(WHITESPACE).size

        private val WHITESPACE = Regex("\\s+")
    }
}
