package app.xreader.komikku

import app.xreader.komikku.presentation.settings.FilterPreviewState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (I4): the Custom filter tab's live preview — entering
 * the tab sets undim + chrome-hidden; leaving clears both.
 */
class V158FilterPreviewTest {

    @Test
    fun `entering the filter tab activates undim and chrome-hidden`() {
        val state = FilterPreviewState().onTabChanged(
            FilterPreviewState.FILTER_TAB_INDEX
        )
        assertTrue(state.active)
        assertTrue(state.sheetUndimmed)
        assertTrue(state.chromeHidden)
    }

    @Test
    fun `leaving the tab or closing the sheet clears both`() {
        val active = FilterPreviewState().onTabChanged(
            FilterPreviewState.FILTER_TAB_INDEX
        )
        val left = active.onTabChanged(0)
        assertFalse(left.active)
        assertFalse(left.sheetUndimmed)
        assertFalse(left.chromeHidden)
        val closed = active.onSheetClosed()
        assertFalse(closed.active)
        assertFalse(closed.sheetUndimmed)
        assertFalse(closed.chromeHidden)
    }

    @Test
    fun `other tabs never activate the preview`() {
        assertFalse(FilterPreviewState().onTabChanged(0).active)
        assertFalse(FilterPreviewState().onTabChanged(1).active)
    }
}
