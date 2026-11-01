package app.xreader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.TextSpan
import app.xreader.domain.reader.BionicReading
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderFontChoice
import app.xreader.ui.theme.InterSans
import app.xreader.ui.theme.JakartaSans
import app.xreader.ui.theme.LiterataSerif
import java.io.File

/** Resolves the configured reader font, incl. user-imported files. */
@Composable
fun rememberReaderFontFamily(config: ReaderConfig, container: AppContainer): FontFamily {
    val userFile: File? = remember(config.userFontName) {
        if (config.fontChoice == ReaderFontChoice.USER) {
            config.userFontName?.let { container.userFonts.file(it) }
        } else {
            null
        }
    }
    return remember(config.fontChoice, userFile) {
        when (config.fontChoice) {
            ReaderFontChoice.LITERATA -> LiterataSerif
            ReaderFontChoice.LORA -> app.xreader.ui.theme.LoraSerif
            ReaderFontChoice.MERRIWEATHER -> app.xreader.ui.theme.MerriweatherSerif
            ReaderFontChoice.NOTO_SERIF -> app.xreader.ui.theme.NotoSerif
            ReaderFontChoice.JAKARTA -> JakartaSans
            ReaderFontChoice.INTER -> InterSans
            ReaderFontChoice.USER ->
                userFile?.let { FontFamily(Font(it)) } ?: LiterataSerif
        }
    }
}

/**
 * Builds the [AnnotatedString] for one block (or a char sub-range of
 * it, in block-local coordinates) with three independent overlays:
 * the source spans (bold/italic), bionic-reading bold prefixes, and
 * background washes (highlights / spoken sentence). Backgrounds are
 * applied as additive styles so they never disturb the others.
 */
fun annotatedForBlock(
    block: ChapterBlock,
    fromChar: Int = 0,
    toChar: Int = Int.MAX_VALUE,
    bionic: Boolean = false,
    backgrounds: List<Triple<Int, Int, Color>> = emptyList()
): AnnotatedString {
    val fullText: String
    val spans: List<TextSpan>
    when (block) {
        is ChapterBlock.Paragraph -> {
            fullText = block.text
            spans = block.spans
        }
        is ChapterBlock.Heading -> {
            fullText = block.text
            spans = listOf(TextSpan(block.text, bold = true))
        }
        else -> return AnnotatedString("")
    }
    val from = fromChar.coerceIn(0, fullText.length)
    val to = toChar.coerceIn(from, fullText.length)
    val base = buildAnnotatedString {
        // Source spans, intersected with [from, to).
        var spanStart = 0
        for (span in spans) {
            val spanEnd = spanStart + span.text.length
            val overlapStart = maxOf(spanStart, from)
            val overlapEnd = minOf(spanEnd, to)
            if (overlapStart < overlapEnd) {
                withStyle(
                    SpanStyle(
                        fontWeight = if (span.bold) FontWeight.Bold else null,
                        fontStyle = if (span.italic) FontStyle.Italic else null
                    )
                ) {
                    append(fullText.substring(overlapStart, overlapEnd))
                }
            }
            spanStart = spanEnd
        }
        if (length == 0 && from < to) append(fullText.substring(from, to))
    }
    if (!bionic && backgrounds.isEmpty()) return base
    return buildAnnotatedString {
        append(base)
        if (bionic) {
            // Segments reassemble exactly; walk them with a cursor.
            var cursor = 0
            for ((segment, isBold) in BionicReading.segments(fullText.substring(from, to))) {
                if (isBold && segment.isNotEmpty()) {
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        cursor, cursor + segment.length
                    )
                }
                cursor += segment.length
            }
        }
        for ((start, end, color) in backgrounds) {
            val s = (start - from).coerceIn(0, length)
            val e = (end - from).coerceIn(s, length)
            if (s < e) addStyle(SpanStyle(background = color), s, e)
        }
    }
}

/** Background washes for a char range, in block-local coordinates. */
fun blockBackgrounds(
    blockStart: Int,
    blockEnd: Int,
    highlightRanges: List<Pair<Int, Int>>,
    spokenRange: IntRange?,
    overlayActive: Boolean,
    highlightColor: Color,
    spokenColor: Color,
    overlayColor: Color
): List<Triple<Int, Int, Color>> {
    val out = ArrayList<Triple<Int, Int, Color>>()
    if (overlayActive) out += Triple(0, blockEnd - blockStart, overlayColor)
    for ((start, end) in highlightRanges) {
        val s = maxOf(start, blockStart) - blockStart
        val e = minOf(end, blockEnd) - blockStart
        if (s < e) out += Triple(s, e, highlightColor)
    }
    if (spokenRange != null) {
        val s = maxOf(spokenRange.first, blockStart) - blockStart
        val e = minOf(spokenRange.last + 1, blockEnd) - blockStart
        if (s < e) out += Triple(s, e, spokenColor)
    }
    return out
}
