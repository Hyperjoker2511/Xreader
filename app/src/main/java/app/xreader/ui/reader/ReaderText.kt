package app.xreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xreader.di.AppContainer
import app.xreader.domain.content.ChapterBlock
import app.xreader.domain.content.TextSpan
import app.xreader.domain.reader.BionicReading
import app.xreader.domain.reader.ReaderConfig
import app.xreader.domain.reader.ReaderFontChoice
import app.xreader.ui.theme.InterSans
import app.xreader.ui.theme.JakartaSans
import app.xreader.ui.theme.LiterataSerif
import app.xreader.ui.theme.ReaderPalette
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

/**
 * D13: the footnote box's text style — Moon+'s box CSS sets
 * `font-size: 0.8em`, so the box text is the body size × 0.8,
 * with the body's line-height multiplier and the body's
 * hyphenation/line-break behaviour. No first-line indent:
 * Moon+'s box is a plain inset block.
 */
fun footnoteTextStyle(
    config: ReaderConfig,
    fontFamily: FontFamily
): TextStyle {
    val size = config.fontSizeSp * 0.8f
    return TextStyle(
        fontFamily = fontFamily,
        fontSize = size.sp,
        lineHeight = (size * config.lineHeightMultiplier).sp,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph
    )
}

/**
 * D13: Moon+'s footnote box, translated faithfully from its
 * verbatim CSS (`font-size:0.8em; background:
 * rgba(126,126,126,0.1); margin:0.5em; padding:0.5em;
 * border:1px dotted #888888; border-radius:10px`): the grey
 * is the literal 126, the border colour the literal #888888,
 * margin and padding are each half an em of the BOX text
 * size, the radius 10. One approximation, flagged in the
 * spec: Compose has no dotted stroke, so the border is a
 * 1 dp DASHED stroke (dash pattern 2 dp on / 2 dp off) —
 * visually a dot run at reading distance. The CSS sets no
 * text colour, so the ink is the palette's text colour.
 * Entries render in reference order, separated by a small
 * gap (the CSS's `<br>` between notes); each entry leads
 * with its bold linkName header, prepended to its first
 * paragraph exactly as Moon+ prepends it to the note HTML.
 */
@Composable
fun FootnoteBoxView(
    block: ChapterBlock.FootnoteBox,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    assetFile: (String) -> File,
    modifier: Modifier = Modifier,
    onZoomActiveChanged: (Boolean) -> Unit = {}
) {
    val em = (config.fontSizeSp * 0.8f).dp
    val shape = RoundedCornerShape(10.dp)
    // Code 122 (S9, audit #14): under Material 3 the box is the
    // spec's filled surfaceContainer card — no hard-coded grey,
    // no dashed stroke. Its ink stays the page palette's text
    // colour (the box is content furniture drawn from the page
    // palette). Nocturne keeps Moon+'s shipped dashed box above
    // exactly as before.
    val footnoteIsM3 = app.xreader.ui.theme.LocalDesignState.current.design ==
        app.xreader.data.prefs.AppDesign.MATERIAL3
    val boxModifier = if (footnoteIsM3) {
        modifier
            .padding(em * 0.5f)
            .background(
                androidx.compose.material3.MaterialTheme.colorScheme
                    .surfaceContainer,
                shape
            )
            .padding(em * 0.5f)
    } else {
        modifier
            .padding(em * 0.5f)
            .drawBehind {
                drawRoundRect(
                    color = Color(0xFF888888),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(2.dp.toPx(), 2.dp.toPx())
                        )
                    ),
                    cornerRadius = CornerRadius(10.dp.toPx())
                )
            }
            .background(Color(0xFF7E7E7E).copy(alpha = 0.1f), shape)
            .padding(em * 0.5f)
    }
    Column(
        modifier = boxModifier
    ) {
        block.entries.forEachIndexed { index, entry ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            FootnoteEntryView(
                entry, config, palette, fontFamily, assetFile,
                onZoomActiveChanged
            )
        }
    }
}

@Composable
private fun FootnoteEntryView(
    entry: ChapterBlock.FootnoteBox.Entry,
    config: ReaderConfig,
    palette: ReaderPalette,
    fontFamily: FontFamily,
    assetFile: (String) -> File,
    onZoomActiveChanged: (Boolean) -> Unit = {}
) {
    val style = footnoteTextStyle(config, fontFamily)
    var headerPending = entry.linkName.isNotBlank()
    val first = entry.blocks.firstOrNull()
    if (headerPending && first !is ChapterBlock.Paragraph) {
        // The header leads on its own line when the note
        // does not open with a paragraph (or is empty) —
        // with a paragraph it prepends to that paragraph
        // below instead.
        Text(
            entry.linkName,
            style = style,
            fontWeight = FontWeight.Bold,
            color = palette.text
        )
        headerPending = false
    }
    for (inner in entry.blocks) {
        when (inner) {
            is ChapterBlock.Paragraph -> {
                val annotated = if (headerPending) {
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(fontWeight = FontWeight.Bold)
                        ) {
                            append(entry.linkName)
                        }
                        append(" ")
                        append(
                            annotatedForBlock(
                                inner, bionic = config.bionicReading
                            )
                        )
                    }
                } else {
                    annotatedForBlock(
                        inner, bionic = config.bionicReading
                    )
                }
                headerPending = false
                Text(annotated, style = style, color = palette.text)
            }
            is ChapterBlock.Heading -> {
                // A heading inside a note keeps Moon+'s
                // heading scale, applied to the box size.
                val scale = when (inner.level) {
                    1 -> 2.0f
                    2 -> 1.5f
                    else -> 1.17f
                }
                val size = config.fontSizeSp * 0.8f * scale
                Text(
                    inner.text,
                    style = TextStyle(
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = size.sp,
                        lineHeight =
                            (size * config.lineHeightMultiplier).sp,
                        textAlign = TextAlign.Center
                    ),
                    color = palette.text
                )
            }
            is ChapterBlock.Image -> ZoomableImage(
                model = assetFile(inner.fileName),
                contentDescription = inner.alt,
                contentScale = ContentScale.FillWidth,
                doubleTapToZoom = config.doubleTapToZoom,
                disableZoomOut = config.disableZoomOut,
                onZoomActiveChanged = onZoomActiveChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
            is ChapterBlock.Rule -> Text(
                "• • •",
                color = palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            )
            is ChapterBlock.FootnoteBox -> FootnoteBoxView(
                block = inner,
                config = config,
                palette = palette,
                fontFamily = fontFamily,
                assetFile = assetFile,
                onZoomActiveChanged = onZoomActiveChanged
            )
            // MR-4 (R7): a pagebreak marker inside a note
            // renders as nothing, as everywhere else.
            is ChapterBlock.PageBreak -> Unit
        }
    }
    if (headerPending) {
        // An empty note (no blocks at all): the header alone.
        Text(
            entry.linkName,
            style = style,
            fontWeight = FontWeight.Bold,
            color = palette.text
        )
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
    overlayColor: Color,
    /** Code 118: per-range wash colours, parallel to
     * [highlightRanges] when supplied — the core reader
     * passes each stored highlight's OWN colour (its token,
     * mapped at the call site). Null (the default, and
     * PagedReader's call) keeps the shipped behaviour:
     * every range in the single [highlightColor]. */
    highlightColors: List<Color>? = null
): List<Triple<Int, Int, Color>> {
    val out = ArrayList<Triple<Int, Int, Color>>()
    if (overlayActive) out += Triple(0, blockEnd - blockStart, overlayColor)
    for ((index, range) in highlightRanges.withIndex()) {
        val (start, end) = range
        val s = maxOf(start, blockStart) - blockStart
        val e = minOf(end, blockEnd) - blockStart
        if (s < e) out += Triple(
            s, e, highlightColors?.getOrNull(index) ?: highlightColor
        )
    }
    if (spokenRange != null) {
        val s = maxOf(spokenRange.first, blockStart) - blockStart
        val e = minOf(spokenRange.last + 1, blockEnd) - blockStart
        if (s < e) out += Triple(s, e, spokenColor)
    }
    return out
}
