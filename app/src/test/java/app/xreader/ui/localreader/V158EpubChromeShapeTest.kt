package app.xreader.ui.localreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v1.5.8 (G3): the Moon+ chrome shape — chrome-open state
 * exposes the six quick actions in Moon+'s order, each wired
 * to its PRE-EXISTING handler (handler identity asserted, not
 * just presence: G3 is placement, not new machinery).
 */
class V158EpubChromeShapeTest {

    @Test
    fun `quick row exposes the six actions bound to the existing handlers`() {
        val dayNight: () -> Unit = {}
        val contents: () -> Unit = {}
        val bookmarks: () -> Unit = {}
        val search: () -> Unit = {}
        val settings: () -> Unit = {}
        val readAloud: () -> Unit = {}
        val handlers = EpubChromeHandlers(
            onToggleDayNight = dayNight,
            onContents = contents,
            onBookmarks = bookmarks,
            onSearch = search,
            onSettings = settings,
            onReadAloud = readAloud
        )
        val actions = epubQuickActions(handlers)
        assertEquals(
            listOf(
                "dayNight", "contents", "bookmarks",
                "search", "settings", "readAloud"
            ),
            actions.map { it.id }
        )
        assertSame(dayNight, actions[0].onClick)
        assertSame(contents, actions[1].onClick)
        assertSame(bookmarks, actions[2].onClick)
        assertSame(search, actions[3].onClick)
        assertSame(settings, actions[4].onClick)
        assertSame(readAloud, actions[5].onClick)
        assertEquals(
            listOf(
                "Day / night", "Contents", "Bookmarks",
                "Search", "Book settings", "Read aloud"
            ),
            actions.map { it.label }
        )
    }
}

/**
 * v1.5.8 (G2): the book-mode sheet is reordered — the reading
 * mode row is the sheet's FIRST control (it was the last row
 * in v1.5.7), then theme, then typography.
 */
class V158EpubSheetOrderTest {

    @Test
    fun `mode row is the sheet's first section, theme second, typography third`() {
        val sections = epubSheetSections()
        assertEquals(EpubSheetSection.MODE, sections.first())
        assertEquals(EpubSheetSection.THEME, sections[1])
        assertEquals(EpubSheetSection.TYPOGRAPHY, sections[2])
    }
}
