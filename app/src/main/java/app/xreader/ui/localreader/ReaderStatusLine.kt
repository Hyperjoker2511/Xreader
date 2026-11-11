package app.xreader.ui.localreader

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * v1.5.8 (G5 + H8): Moon+'s permanent slim status line — ONE
 * component, two payloads (Moon+'s own design). It is NOT part
 * of the tap chrome: it renders while the chrome is hidden,
 * always visible, slim and unobtrusive in the reader's glass
 * language. Battery from [BatteryManager], clock from a
 * one-minute tick.
 *
 * EPUB payload: battery · clock · chapter title ·
 * (chapter-in-book) · book %.
 * PDF payload: battery · clock · current chapter name (from
 * the outline) · N/Total — the FILE position, never a
 * renumbered count.
 */
data class ReaderStatusLineContent(
    val batteryPercent: Int?,
    val clockText: String,
    val chapterText: String?,
    val positionText: String
) {
    /** The single-line rendering the surfaces show. */
    fun render(): String = buildString {
        batteryPercent?.let { append("$it%  ·  ") }
        append(clockText)
        chapterText?.takeIf { it.isNotBlank() }?.let { append("  ·  ").append(it) }
        append("  ·  ").append(positionText)
    }
}

/** EPUB payload (G5): chapter title, (chapter x of y), book %. */
fun epubStatusLineContent(
    progress: EpubProgress?,
    batteryPercent: Int?,
    clockText: String
): ReaderStatusLineContent = ReaderStatusLineContent(
    batteryPercent = batteryPercent,
    clockText = clockText,
    chapterText = progress?.chapterTitle,
    positionText = if (progress == null) {
        "…"
    } else {
        "${progress.sectionIndex + 1}/${progress.sectionCount}" +
            " · ${(progress.fraction * 100).toInt()}%"
    }
)

// ── v1.5.10 (F4): position INSIDE the chapter ────────────────────

/**
 * F4's within-chapter position, (1-based position, count) —
 * Moon+'s `(23/49)` shape. Derived from the toolkit's own
 * pagination of the CURRENT resource, not from synthetic page
 * sizes: [positionIndexInResource] is the locator's position
 * index within its resource's positions and
 * [resourcePositionCount] is that resource's position count
 * (the navigator's positions for the resource — the seam
 * LocalDocReaderViewModel/L-DE supplies). Null when positions
 * are unavailable for the resource, in which case the caller
 * falls back to [withinChapterFromSpan] or omits the segment.
 * The result is clamped into the chapter: it never exceeds
 * the count and resets at resource boundaries by construction
 * (a new resource restarts its own position index).
 */
fun withinChapterPosition(
    positionIndexInResource: Int?,
    resourcePositionCount: Int?
): Pair<Int, Int>? {
    val count = resourcePositionCount ?: return null
    if (count <= 0) return null
    val index = positionIndexInResource ?: return null
    val position = (index + 1).coerceIn(1, count)
    return position to count
}

/**
 * F4's fallback where positions are unavailable: the
 * progression-span estimate. The resource spans
 * [resourceStartProgression]..[resourceEndProgression] of the
 * book; [inSectionFraction] (the tree's shipped
 * `inSectionFraction`, LocalDocReaderViewModel) places the
 * reader inside it, and the chapter's page count is estimated
 * as its progression span × [estimatedBookPages] (the same
 * estimate class Moon+ itself uses — shaped as an estimate,
 * never a fake exactitude). Returns null when the span or the
 * book estimate is unknown.
 */
fun withinChapterFromSpan(
    inSectionFraction: Float,
    resourceStartProgression: Double?,
    resourceEndProgression: Double?,
    estimatedBookPages: Int?
): Pair<Int, Int>? {
    val start = resourceStartProgression ?: return null
    val end = resourceEndProgression ?: return null
    val bookPages = estimatedBookPages ?: return null
    if (bookPages <= 0) return null
    val span = (end - start).coerceIn(0.0, 1.0)
    if (span <= 0.0) return null
    val rawPages = span * bookPages
    // A chapter estimating below one page cannot carry a
    // within-chapter position — omit rather than invent.
    if (rawPages < 1.0) return null
    // Rounded, not truncated: a 0.0999… span of a 100-page
    // book is a 10-page chapter, not 9.
    val pages = kotlin.math.round(rawPages).toInt().coerceAtLeast(1)
    val position = (inSectionFraction.coerceIn(0f, 1f) * pages)
        .toInt().plus(1).coerceIn(1, pages)
    return position to pages
}

/** EPUB payload WITH the within-chapter segment (F4): chapter
 * title · (position-in-chapter / pages-in-chapter) ·
 * chapter-in-book · book %. When [withinChapter] is null this
 * renders exactly the pre-F4 line. */
fun epubStatusLineContent(
    progress: EpubProgress?,
    withinChapter: Pair<Int, Int>?,
    batteryPercent: Int?,
    clockText: String
): ReaderStatusLineContent {
    val base = epubStatusLineContent(progress, batteryPercent, clockText)
    if (progress == null || withinChapter == null) return base
    val (position, count) = withinChapter
    return base.copy(
        positionText = "($position/$count) · " +
            "${progress.sectionIndex + 1}/${progress.sectionCount}" +
            " · ${(progress.fraction * 100).toInt()}%"
    )
}

/** PDF payload (H8): chapter name from the outline + N/Total
 * in FILE pages (1-based), exactly as Moon+ counts. */
fun pdfStatusLineContent(
    chapterTitle: String?,
    page: Int,
    pageCount: Int,
    batteryPercent: Int?,
    clockText: String
): ReaderStatusLineContent = ReaderStatusLineContent(
    batteryPercent = batteryPercent,
    clockText = clockText,
    chapterText = chapterTitle,
    positionText = "${page + 1}/$pageCount"
)

/** "HH:mm" for [nowMs] — the clock field's format. */
fun statusClockText(nowMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))

/** Milliseconds from [nowMs] to the next minute boundary — the
 * tick the clock refreshes on. */
fun msToNextMinute(nowMs: Long): Long = 60_000L - (nowMs % 60_000L)

/**
 * v1.5.9 (F-8d/U-1): the line is a CENTRED FLOATING PILL on
 * the same glass primitive as the rest of the reader chrome
 * ([GlassCapsule]) — not the full-width flat band it was.
 * The payload computation above is unchanged.
 */
@Composable
internal fun ReaderStatusLine(content: ReaderStatusLineContent) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        GlassCapsule {
            Text(
                content.render(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * The live status line: owns the battery + clock state (one
 * read per minute tick) and renders [ReaderStatusLine] from the
 * caller's payload builder. Composed OUTSIDE the chrome
 * visibility gate in both readers — that is the point of the
 * item.
 *
 * v1.5.35 (item 4): [bare] renders the same payload as a
 * plain centred text row — NO capsule of its own — for a
 * caller that hosts the facts INSIDE its own bar (the PDF
 * reader's bottom capsule, per the uniform rule: no
 * permanent line over the page; the facts live inside the
 * open bar only). The default keeps the shipped pill,
 * byte-for-byte, for the EPUB legacy surface.
 */
@Composable
internal fun LiveReaderStatusLine(
    bare: Boolean = false,
    payload: (batteryPercent: Int?, clockText: String) -> ReaderStatusLineContent
) {
    val context = LocalContext.current
    var battery by remember { mutableIntStateOf(-1) }
    var clock by remember { mutableStateOf(statusClockText(System.currentTimeMillis())) }
    LaunchedEffect(context) {
        while (true) {
            battery = readBatteryPercent(context)
            clock = statusClockText(System.currentTimeMillis())
            delay(msToNextMinute(System.currentTimeMillis()) + 250L)
        }
    }
    val content = payload(if (battery >= 0) battery else null, clock)
    if (bare) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                content.render(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    } else {
        ReaderStatusLine(content)
    }
}

private fun readBatteryPercent(context: Context): Int = runCatching {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}.getOrDefault(-1)
