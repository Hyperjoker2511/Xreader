package app.xreader.data.library

import app.xreader.data.extension.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manga programme (M3): the Novels | Manga split's pure rules
 * — side filtering over a mixed library, the new-chapter
 * counts that ride on the switch labels, the label shapes,
 * and Explore's extension-side rule.
 */
class LibrarySplitTest {

    // A mixed shelf: books 1–2 novels (one implicit — no kind
    // entry — one explicit), book 3 manga, book 4 a local
    // import (no kind entry = Novels by the fallback law).
    private val kinds = mapOf(
        2L to MediaKind.TEXT,
        3L to MediaKind.MANGA
    )
    private val shelf = listOf(1L, 2L, 3L, 4L)

    @Test
    fun `each side lists only its own kind`() {
        assertEquals(
            listOf(1L, 2L, 4L),
            LibrarySplit.onSide(shelf, MediaKind.TEXT) {
                LibrarySplit.kindOf(it, kinds)
            }
        )
        assertEquals(
            listOf(3L),
            LibrarySplit.onSide(shelf, MediaKind.MANGA) {
                LibrarySplit.kindOf(it, kinds)
            }
        )
    }

    @Test
    fun `a book with no kind entry is a novel, never lost`() {
        assertEquals(MediaKind.TEXT, LibrarySplit.kindOf(99L, kinds))
        assertEquals(MediaKind.MANGA, LibrarySplit.kindOf(3L, kinds))
    }

    @Test
    fun `new chapter counts sum per side`() {
        val metas = mapOf(
            1L to BookMeta(newChapterCount = 2),
            2L to BookMeta(newChapterCount = 0),
            3L to BookMeta(newChapterCount = 5),
            4L to BookMeta(newChapterCount = 3),
            // A book with new chapters but no meta row at all
            // contributes nothing (the map is the feed).
            5L to BookMeta(newChapterCount = 0)
        )
        val counts = LibrarySplit.newChaptersBySide(metas, kinds)
        assertEquals(5, counts[MediaKind.TEXT])
        assertEquals(5, counts[MediaKind.MANGA])
    }

    @Test
    fun `switch labels carry the count only when there is one`() {
        assertEquals("Novels", LibrarySplit.switchLabel(MediaKind.TEXT, 0))
        assertEquals("Manga", LibrarySplit.switchLabel(MediaKind.MANGA, 0))
        assertEquals(
            "Novels · 7", LibrarySplit.switchLabel(MediaKind.TEXT, 7)
        )
        assertEquals(
            "Manga · 1", LibrarySplit.switchLabel(MediaKind.MANGA, 1)
        )
        assertEquals("Novels", LibrarySplit.sideName(MediaKind.TEXT))
        assertEquals("Manga", LibrarySplit.sideName(MediaKind.MANGA))
    }

    @Test
    fun `an extension stands on a side when any of its sources does`() {
        assertTrue(
            LibrarySplit.extensionOnSide(
                listOf(MediaKind.TEXT), MediaKind.TEXT
            )
        )
        assertFalse(
            LibrarySplit.extensionOnSide(
                listOf(MediaKind.TEXT), MediaKind.MANGA
            )
        )
        // A mixed package stands on both sides.
        assertTrue(
            LibrarySplit.extensionOnSide(
                listOf(MediaKind.TEXT, MediaKind.MANGA), MediaKind.MANGA
            )
        )
        assertTrue(
            LibrarySplit.extensionOnSide(
                listOf(MediaKind.TEXT, MediaKind.MANGA), MediaKind.TEXT
            )
        )
    }

    @Test
    fun `a kindless extension is novels side only`() {
        // JS/Dex packages carry no declarative kinds — prose by
        // construction, so Novels only, never Manga.
        assertTrue(
            LibrarySplit.extensionOnSide(emptyList(), MediaKind.TEXT)
        )
        assertFalse(
            LibrarySplit.extensionOnSide(emptyList(), MediaKind.MANGA)
        )
    }
}
