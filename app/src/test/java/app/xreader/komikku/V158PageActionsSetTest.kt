package app.xreader.komikku

import app.xreader.komikku.presentation.PageAction
import app.xreader.komikku.presentation.pageActionItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v1.5.8 (I3): the long-press page-action set is EXACTLY the
 * donor's four — Set as cover · Copy to clipboard · Share ·
 * Save to device — each bound to its live handler (identity,
 * not just presence). No WebView/Browser action exists in the
 * set (invisible-engine rule).
 */
class V158PageActionsSetTest {

    @Test
    fun `exactly four actions in donor order, each with its handler`() {
        val cover: () -> Unit = {}
        val copy: () -> Unit = {}
        val share: () -> Unit = {}
        val save: () -> Unit = {}
        val items = pageActionItems(
            onSetAsCover = cover, onCopy = copy,
            onShare = share, onSave = save
        )
        assertEquals(4, items.size)
        assertEquals(
            listOf(
                PageAction.SET_AS_COVER, PageAction.COPY_TO_CLIPBOARD,
                PageAction.SHARE, PageAction.SAVE_TO_DEVICE
            ),
            items.map { it.action }
        )
        assertEquals(
            listOf("Set as cover", "Copy to clipboard", "Share", "Save to device"),
            items.map { it.label }
        )
        assertSame(cover, items[0].onInvoke)
        assertSame(copy, items[1].onInvoke)
        assertSame(share, items[2].onInvoke)
        assertSame(save, items[3].onInvoke)
    }
}
