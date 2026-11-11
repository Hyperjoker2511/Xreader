package app.xreader.ui.localreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xreader.data.engine.LocatorJson
import app.xreader.data.engine.OutlineEntry

/**
 * v1.5.8 (H2): ONE contents panel in Moon+'s shape for both
 * local readers — `Chapters | Bookmarks` tabs, the target page
 * number at each PDF outline row's end, and a footer carrying
 * the current chapter + the caller's actions (Search · Page
 * display / book settings). The EPUB contents sheet adopts the
 * same tabbed shape (Inv. E20's note). The book does NOT
 * auto-open with the panel up (DECIDED — our reader opens on
 * the page; the panel is one tap).
 *
 * The row/footer models are pure so the panel's shape is
 * testable without a composition.
 */

/** One Chapters-tab row: title, its target, and the trailing
 * label (the PDF target page number; null on the EPUB side). */
data class ContentsChapterRow(
    val title: String,
    val targetIndex: Int,
    val trailingLabel: String?,
    val isCurrent: Boolean
)

/** One Bookmarks-tab row. */
data class ContentsBookmarkRow(
    val label: String,
    val detail: String?
)

/** PDF chapter rows: trailing label = the outline entry's
 * target FILE page number (1-based), Moon+'s column. */
fun pdfChapterRows(
    outline: List<OutlineEntry>,
    currentPage: Int
): List<ContentsChapterRow> {
    val currentTarget = outline
        .mapNotNull { LocatorJson.targetIndex(it.targetJson) }
        .lastOrNull { it <= currentPage }
    return outline.mapNotNull { entry ->
        val target = LocatorJson.targetIndex(entry.targetJson) ?: return@mapNotNull null
        ContentsChapterRow(
            title = entry.title,
            targetIndex = target,
            trailingLabel = "${target + 1}",
            isCurrent = target == currentTarget
        )
    }
}

/** EPUB chapter rows: sections have no page numbers — the
 * trailing label is absent, honestly. */
fun epubChapterRows(
    outline: List<OutlineEntry>,
    currentSection: Int?
): List<ContentsChapterRow> = outline.mapNotNull { entry ->
    val target = LocatorJson.targetIndex(entry.targetJson) ?: return@mapNotNull null
    ContentsChapterRow(
        title = entry.title,
        targetIndex = target,
        trailingLabel = null,
        isCurrent = currentSection != null && target == currentSection
    )
}

/** The footer's current-chapter readout: "Title (n/of)" —
 * [position] is 1-based among [total] chapters. */
fun contentsFooterText(chapterTitle: String?, position: Int, total: Int): String =
    if (chapterTitle.isNullOrBlank() || total <= 0) {
        // No current chapter (e.g. an outline-less document):
        // the footer carries nothing rather than a placeholder
        // mark — the shape V158PdfPanelTextSpeechTest pins.
        ""
    } else {
        "$chapterTitle ($position/$total)"
    }

// ── v1.5.10 (F5): the sort toggle ────────────────────────────────

/** The contents panel's chapter order: the book's own outline
 * order, or reversed. Stored per reader in [ReaderBarStore]
 * (build-map F5 — the reader-chrome globals store), with the
 * panel itself stateless about persistence. */
enum class ContentsSortOrder(val key: String, val toggleLabel: String) {
    DOCUMENT_ORDER("document", "1..N"),
    REVERSED("reversed", "N..1");

    fun toggled(): ContentsSortOrder = when (this) {
        DOCUMENT_ORDER -> REVERSED
        REVERSED -> DOCUMENT_ORDER
    }

    companion object {
        fun fromKey(key: String?): ContentsSortOrder =
            entries.firstOrNull { it.key == key } ?: DOCUMENT_ORDER
    }
}

/** The rows in the order the panel renders them under [order].
 * Pure — the toggle's whole effect on the list, so the gate
 * test pins reversal without a composition. Bookmarks are NOT
 * reordered (the toggle is the outline's, Moon+'s shape). */
fun sortedChapterRows(
    rows: List<ContentsChapterRow>,
    order: ContentsSortOrder
): List<ContentsChapterRow> = when (order) {
    ContentsSortOrder.DOCUMENT_ORDER -> rows
    ContentsSortOrder.REVERSED -> rows.asReversed()
}

/** The footer's position for a PDF page: the outline entry
 * at-or-before [currentPage], 1-based; 0 when none applies. */
fun pdfFooterPosition(outline: List<OutlineEntry>, currentPage: Int): Int {
    var position = 0
    outline.forEachIndexed { index, entry ->
        val target = LocatorJson.targetIndex(entry.targetJson) ?: return@forEachIndexed
        if (target <= currentPage) position = index + 1
    }
    return position
}

@Composable
internal fun TabbedContentsPanel(
    chapterRows: List<ContentsChapterRow>,
    chaptersEmptyText: String,
    bookmarkRows: List<ContentsBookmarkRow>,
    bookmarksEmptyText: String,
    onChapterClick: (ContentsChapterRow) -> Unit,
    onBookmarkClick: (Int) -> Unit,
    onBookmarkDelete: (Int) -> Unit,
    footerText: String,
    initialTab: Int = 0,
    // v1.5.10 (F5): the sort toggle. Null (the default) = the
    // shipped panel, no toggle rendered — existing call sites
    // are untouched until their reader wires the store. When
    // present, [chapterRows] must already be in DISPLAY order
    // (the caller applies [sortedChapterRows]); the toggle
    // only reports the flip.
    sortOrder: ContentsSortOrder? = null,
    onSortOrderChange: ((ContentsSortOrder) -> Unit)? = null,
    footerActions: @Composable () -> Unit
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (sortOrder != null && onSortOrderChange != null) {
            // F5: the toggle rides ABOVE the tabs (the return's
            // interaction shape — a violet SwapVert pill reading
            // 1..N / N..1 — rewired to the tree's row model).
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                ReaderStadiumChip(
                    selected = sortOrder == ContentsSortOrder.REVERSED,
                    onClick = { onSortOrderChange(sortOrder.toggled()) },
                    text = sortOrder.toggleLabel,
                    leadingIcon = {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = "Sort order",
                            modifier = Modifier.padding(end = 2.dp)
                        )
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        // v1.5.9 (F-8e): the violet indicator + selected colour
        // (DESIGN's one accent), not the stock M3 tab treatment.
        TabRow(
            selectedTabIndex = tab,
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            indicator = { tabPositions ->
                if (tab in tabPositions.indices) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[tab]),
                        color = ReaderViolet
                    )
                }
            }
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                selectedContentColor = ReaderViolet,
                unselectedContentColor = ReaderMutedText,
                text = { Text("Chapters") }
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                selectedContentColor = ReaderViolet,
                unselectedContentColor = ReaderMutedText,
                text = { Text("Bookmarks") }
            )
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> {
                if (chapterRows.isEmpty()) {
                    Text(chaptersEmptyText, Modifier.padding(vertical = 16.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(chapterRows) { row ->
                            TextButton(
                                onClick = { onChapterClick(row) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        row.title,
                                        Modifier.weight(1f),
                                        color = if (row.isCurrent) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        fontWeight = if (row.isCurrent) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                    row.trailingLabel?.let { label ->
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                if (bookmarkRows.isEmpty()) {
                    Text(bookmarksEmptyText, Modifier.padding(vertical = 16.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(bookmarkRows.indices.toList()) { index ->
                            val row = bookmarkRows[index]
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { onBookmarkClick(index) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(row.label)
                                        row.detail?.let { detail ->
                                            Text(
                                                detail,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                TextButton(onClick = { onBookmarkDelete(index) }) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                footerText,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f, fill = false)
            )
            footerActions()
        }
    }
}
