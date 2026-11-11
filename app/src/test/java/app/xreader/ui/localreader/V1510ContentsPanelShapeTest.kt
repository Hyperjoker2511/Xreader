package app.xreader.ui.localreader

import app.xreader.fixtures.FakePreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.10 (F5) gate: the contents panel's ONE addition — the
 * sort toggle. The footer already ships (pinned by
 * [V158PdfPanelTextSpeechTest]); here: chapter rows reverse
 * and restore exactly, bookmarks never reorder, and the store
 * keeps each reader's sort independently.
 */
class V1510ContentsPanelShapeTest {

    private fun row(title: String, position: Int) = ContentsChapterRow(
        title = title,
        targetIndex = position - 1,
        trailingLabel = "$position",
        isCurrent = false
    )

    private val rows = listOf(
        row("One", 1), row("Two", 2), row("Three", 3)
    )

    @Test
    fun `document order is identity`() {
        assertEquals(
            rows,
            sortedChapterRows(rows, ContentsSortOrder.DOCUMENT_ORDER)
        )
    }

    @Test
    fun `reversed flips rows and keeps models intact`() {
        val reversed = sortedChapterRows(rows, ContentsSortOrder.REVERSED)
        assertEquals(listOf("Three", "Two", "One"), reversed.map { it.title })
        // The rows themselves are the same models — labels
        // travel with their chapters.
        assertEquals(listOf("3", "2", "1"), reversed.map { it.trailingLabel })
    }

    @Test
    fun `toggle labels read both ways`() {
        assertEquals("1..N", ContentsSortOrder.DOCUMENT_ORDER.toggleLabel)
        assertEquals("N..1", ContentsSortOrder.REVERSED.toggleLabel)
        assertEquals(
            ContentsSortOrder.REVERSED,
            ContentsSortOrder.DOCUMENT_ORDER.toggled()
        )
        assertEquals(
            ContentsSortOrder.DOCUMENT_ORDER,
            ContentsSortOrder.REVERSED.toggled()
        )
    }

    @Test
    fun `fromKey falls back to document order`() {
        assertEquals(ContentsSortOrder.REVERSED, ContentsSortOrder.fromKey("reversed"))
        assertEquals(ContentsSortOrder.DOCUMENT_ORDER, ContentsSortOrder.fromKey("document"))
        assertEquals(ContentsSortOrder.DOCUMENT_ORDER, ContentsSortOrder.fromKey(null))
        assertEquals(ContentsSortOrder.DOCUMENT_ORDER, ContentsSortOrder.fromKey("junk"))
    }

    @Test
    fun `store keeps each reader's sort independently`() = runTest {
        val store = ReaderBarStore(FakePreferencesDataStore())
        assertEquals(
            ContentsSortOrder.DOCUMENT_ORDER,
            store.contentsSort(ReaderFormat.EPUB).first()
        )
        store.saveContentsSort(ReaderFormat.EPUB, ContentsSortOrder.REVERSED)
        assertEquals(
            ContentsSortOrder.REVERSED,
            store.contentsSort(ReaderFormat.EPUB).first()
        )
        // PDF's sort untouched by EPUB's choice.
        assertEquals(
            ContentsSortOrder.DOCUMENT_ORDER,
            store.contentsSort(ReaderFormat.PDF).first()
        )
        store.saveContentsSort(ReaderFormat.PDF, ContentsSortOrder.REVERSED)
        assertEquals(
            ContentsSortOrder.REVERSED,
            store.contentsSort(ReaderFormat.PDF).first()
        )
    }

    @Test
    fun `footer text pins carried here`() {
        // The footer shipped in v1.5.8; the map says F5 does
        // not rebuild it — these cases mirror its pins: the
        // current-chapter readout, blank when none applies.
        assertEquals(
            "Chapter 3 (3/12)",
            contentsFooterText("Chapter 3", 3, 12)
        )
        assertEquals("", contentsFooterText(null, 0, 12))
        assertEquals("", contentsFooterText("  ", 1, 12))
        assertEquals("", contentsFooterText("Chapter 3", 3, 0))
    }
}
