package app.xreader.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A-121-1 (owner approval, 2026-11-24): chapter lists longer
 * than this group into ranges of [CHAPTER_RANGE_SIZE]. */
const val CHAPTER_RANGE_THRESHOLD = 100

/** A-121-1: the range size — ranges run 1–100, 101–200, … */
const val CHAPTER_RANGE_SIZE = 100

/**
 * A-121-1 — the chapter-range jump bar: a horizontally
 * scrollable row of range chips ("1–100", "101–200", …) at a
 * long chapter list's head, in the design's list language
 * (the same FilterChip the chapter-settings sheet uses).
 * Selecting a range JUMPS the hosting list to that range's
 * first row — the caller owns the list state and the scroll;
 * this bar only reports the pick and reflects [selectedRange]
 * (which hosts derive from the list's first visible row, so
 * the chip follows manual scrolling too).
 *
 * Hosts render it ONLY above the threshold
 * ([CHAPTER_RANGE_THRESHOLD]); at or under it the list renders
 * exactly as it did before this approval. Ranges are positions
 * in the list AS DISPLAYED (1-based), so the labels always
 * match the rows beneath them whatever the sort order.
 */
@Composable
fun ChapterRangeBar(
    totalCount: Int,
    selectedRange: Int,
    onSelectRange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val rangeCount = (totalCount + CHAPTER_RANGE_SIZE - 1) /
        CHAPTER_RANGE_SIZE
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (range in 0 until rangeCount) {
            val start = range * CHAPTER_RANGE_SIZE + 1
            val end = minOf(
                (range + 1) * CHAPTER_RANGE_SIZE, totalCount
            )
            FilterChip(
                selected = range == selectedRange,
                onClick = { onSelectRange(range) },
                label = { Text("$start–$end") }
            )
        }
    }
}
