package app.xreader.ui.book

import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.5 (B1/B2): the book page's pure decisions — which form the
 * always-present Chapters section renders (and which recovery
 * doors it may offer), and which chapters each Download choice
 * queues. Both feed the composables directly, so what is tested
 * here is what the page shows and enqueues.
 */
class BookPageSectionsTest {

    // ---------------------------------------------------------------
    // ChaptersSection.state
    // ---------------------------------------------------------------

    @Test
    fun `a non-empty list renders the list, whatever else is true`() {
        val s = ChaptersSection.state(
            chapterCount = 5, chaptersLoading = true,
            chaptersError = "boom", isExtensionBook = true,
            remoteId = "src|https://example.com/book/1", isLinkSource = true
        )
        assertEquals(ChaptersSectionKind.LIST, s.kind)
        assertEquals(5, s.chapterCount)
        // A healthy list offers no recovery doors.
        assertFalse(s.showRecoveryDoors)
        assertFalse(s.canOpenSourcePage)
        assertFalse(s.canGoToRepair)
    }

    @Test
    fun `empty and still loading renders the loading form`() {
        val s = ChaptersSection.state(
            chapterCount = 0, chaptersLoading = true,
            chaptersError = null, isExtensionBook = true,
            remoteId = "src|https://example.com/book/1", isLinkSource = true
        )
        assertEquals(ChaptersSectionKind.LOADING, s.kind)
        assertFalse(s.showRecoveryDoors)
    }

    @Test
    fun `empty with an error renders the error form with doors`() {
        val s = ChaptersSection.state(
            chapterCount = 0, chaptersLoading = false,
            chaptersError = "timeout", isExtensionBook = true,
            remoteId = "src|https://example.com/book/1", isLinkSource = true
        )
        assertEquals(ChaptersSectionKind.ERROR, s.kind)
        assertTrue(s.showRecoveryDoors)
        assertTrue(s.canOpenSourcePage)
        assertTrue(s.canGoToRepair)
    }

    @Test
    fun `a local book's load error is still the error form`() {
        val s = ChaptersSection.state(
            chapterCount = 0, chaptersLoading = false,
            chaptersError = "corrupt file", isExtensionBook = false,
            remoteId = null, isLinkSource = false
        )
        assertEquals(ChaptersSectionKind.ERROR, s.kind)
        assertTrue(s.showRecoveryDoors)
        assertFalse(s.canOpenSourcePage)
        assertFalse(s.canGoToRepair)
    }

    @Test
    fun `empty settled source book is the empty-source form`() {
        val s = ChaptersSection.state(
            chapterCount = 0, chaptersLoading = false,
            chaptersError = null, isExtensionBook = true,
            remoteId = "src|https://novelight.net/book/abc",
            isLinkSource = true
        )
        assertEquals(ChaptersSectionKind.EMPTY_SOURCE, s.kind)
        assertEquals("https://novelight.net/book/abc", s.sourcePageUrl)
        assertTrue(s.canOpenSourcePage)
        assertTrue(s.canGoToRepair)
    }

    @Test
    fun `empty settled local book is the quiet form, no doors`() {
        val s = ChaptersSection.state(
            chapterCount = 0, chaptersLoading = false,
            chaptersError = null, isExtensionBook = false,
            remoteId = null, isLinkSource = false
        )
        assertEquals(ChaptersSectionKind.EMPTY_LOCAL, s.kind)
        assertFalse(s.showRecoveryDoors)
        assertFalse(s.canOpenSourcePage)
        assertFalse(s.canGoToRepair)
    }

    @Test
    fun `repair door needs a link source, page door needs a real url`() {
        // Built-in (non-link) source with a URL: page yes, Repair no.
        val builtin = ChaptersSection.state(
            chapterCount = 0, chaptersLoading = false,
            chaptersError = null, isExtensionBook = true,
            remoteId = "src|https://example.com/book/1",
            isLinkSource = false
        )
        assertTrue(builtin.canOpenSourcePage)
        assertFalse(builtin.canGoToRepair)
        // Link source WITHOUT a URL-shaped book id: Repair yes,
        // page no.
        val noUrl = ChaptersSection.state(
            chapterCount = 0, chaptersLoading = false,
            chaptersError = null, isExtensionBook = true,
            remoteId = "src|novel-123", isLinkSource = true
        )
        assertFalse(noUrl.canOpenSourcePage)
        assertTrue(noUrl.canGoToRepair)
    }

    @Test
    fun `source page url decodes declarative book ids only`() {
        assertEquals(
            "https://example.com/book/1",
            ChaptersSection.sourcePageUrl("src|https://example.com/book/1")
        )
        assertEquals(
            "http://example.com/book/1",
            ChaptersSection.sourcePageUrl("src|http://example.com/book/1")
        )
        assertNull(ChaptersSection.sourcePageUrl(null))
        assertNull(ChaptersSection.sourcePageUrl("opaque-remote-id"))
        assertNull(ChaptersSection.sourcePageUrl("src|novel-123"))
        assertNull(ChaptersSection.sourcePageUrl(""))
    }

    // ---------------------------------------------------------------
    // BookDownloadRanges
    // ---------------------------------------------------------------

    private fun chapter(
        index: Int,
        state: DownloadState = DownloadState.NONE,
        read: Boolean = false,
        remoteId: String? = "r$index"
    ) = Chapter(
        bookId = 1L, index = index, title = "Chapter $index",
        remoteId = remoteId, downloadState = state, read = read,
        updatedAt = 0L
    )

    @Test
    fun `downloadable excludes ready chapters and missing remote ids`() {
        val chapters = listOf(
            chapter(0),
            chapter(1, state = DownloadState.READY),
            chapter(2, remoteId = null),
            chapter(3, remoteId = "  "),
            chapter(4, state = DownloadState.FAILED)
        )
        val got = BookDownloadRanges.downloadable(chapters)
        assertEquals(listOf(0, 4), got.map { it.index })
    }

    @Test
    fun `downloadable keeps queued downloading and failed, in order`() {
        val chapters = listOf(
            chapter(2, state = DownloadState.DOWNLOADING),
            chapter(0, state = DownloadState.QUEUED),
            chapter(1, state = DownloadState.FAILED)
        )
        val got = BookDownloadRanges.downloadable(chapters)
        assertEquals(listOf(2, 0, 1), got.map { it.index })
    }

    @Test
    fun `next ten caps at ten and takes the first ten in order`() {
        val chapters = (0 until 12).map { chapter(it) }
        val got = BookDownloadRanges.range(
            chapters, BookDownloadChoice.NEXT_10
        )
        assertEquals(10, got.size)
        assertEquals((0 until 10).toList(), got.map { it.index })
        // Fewer than ten downloadable → all of them.
        val few = (0 until 4).map { chapter(it) }
        assertEquals(
            4, BookDownloadRanges.range(few, BookDownloadChoice.NEXT_10).size
        )
    }

    @Test
    fun `whole book is every downloadable chapter`() {
        val chapters = listOf(
            chapter(0), chapter(1, state = DownloadState.READY), chapter(2)
        )
        val got = BookDownloadRanges.range(chapters, BookDownloadChoice.ALL)
        assertEquals(listOf(0, 2), got.map { it.index })
    }

    @Test
    fun `up to latest unread takes the unread downloadable chapters`() {
        val chapters = listOf(
            chapter(0, read = true),
            chapter(1),
            chapter(2, state = DownloadState.READY),
            chapter(3),
            chapter(4, read = true, state = DownloadState.FAILED)
        )
        val got = BookDownloadRanges.range(
            chapters, BookDownloadChoice.UP_TO_LATEST_UNREAD
        )
        // Read chapters are out even when their file is missing
        // (chapter 4); the READY unread chapter is already here.
        assertEquals(listOf(1, 3), got.map { it.index })
    }

    @Test
    fun `counts always equal the ranges they label`() {
        val chapters = listOf(
            chapter(0, read = true),
            chapter(1), chapter(2), chapter(3),
            chapter(4, state = DownloadState.READY),
            chapter(5, remoteId = null),
            chapter(6)
        )
        val counts = BookDownloadRanges.counts(chapters)
        for (choice in BookDownloadChoice.entries) {
            assertEquals(
                "count for $choice",
                BookDownloadRanges.range(chapters, choice).size,
                counts[choice]
            )
        }
        assertEquals(5, counts[BookDownloadChoice.ALL])
        assertEquals(5, counts[BookDownloadChoice.NEXT_10])
        assertEquals(4, counts[BookDownloadChoice.UP_TO_LATEST_UNREAD])
    }

    @Test
    fun `an empty book queues nothing for any choice`() {
        val chapters = emptyList<Chapter>()
        for (choice in BookDownloadChoice.entries) {
            assertTrue(BookDownloadRanges.range(chapters, choice).isEmpty())
        }
        val counts = BookDownloadRanges.counts(chapters)
        assertTrue(counts.values.all { it == 0 })
    }
}
