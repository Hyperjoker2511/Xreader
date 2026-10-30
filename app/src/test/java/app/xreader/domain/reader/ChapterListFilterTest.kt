package app.xreader.domain.reader

import app.xreader.data.db.Chapter
import app.xreader.data.db.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.7 (draw-check ask): chapter search + filters, pinned
 * on his real shape — Trash of the Count's Family, whose
 * Part 2 chapters carry "TCF Part 2" in their titles while
 * Part 1 carries nothing.
 */
class ChapterListFilterTest {

    private fun chapter(
        index: Int,
        title: String,
        read: Boolean = false,
        downloaded: Boolean = false
    ) = Chapter(
        bookId = 1,
        index = index,
        title = title,
        read = read,
        downloadState = if (downloaded) {
            DownloadState.READY
        } else {
            DownloadState.NONE
        },
        contentPath = if (downloaded) "content/1/$index.md" else null,
        updatedAt = 0
    )

    private val tcf = listOf(
        chapter(0, "1 chapter - Prologue"),
        chapter(1, "2 chapter - TCF Part 2 - No. I don't know"),
        chapter(2, "2 chapter - TCF Part 2 - A miracle"),
        chapter(3, "2 chapter - TCF Part 2 - Something is")
    )

    @Test
    fun `parts split at the first marker, unmarked before it are part one`() {
        assertEquals(
            listOf("Part 1", "Part 2"),
            ChapterListFilter.availableParts(tcf)
        )
        assertEquals(
            listOf("1 chapter - Prologue"),
            ChapterListFilter.apply(
                tcf, "", ChapterListFilter.State.ALL, "Part 1"
            ).map { it.title }
        )
        assertEquals(
            3,
            ChapterListFilter.apply(
                tcf, "", ChapterListFilter.State.ALL, "Part 2"
            ).size
        )
    }

    @Test
    fun `no markers or a single part means no part chips`() {
        val plain = listOf(
            chapter(0, "Chapter 1 - Start"),
            chapter(1, "Chapter 2 - Middle")
        )
        assertTrue(ChapterListFilter.availableParts(plain).isEmpty())
        val onePart = listOf(
            chapter(0, "Part 1 - Start"),
            chapter(1, "Part 1 - Middle")
        )
        assertTrue(ChapterListFilter.availableParts(onePart).isEmpty())
    }

    @Test
    fun `parting is not a part marker`() {
        assertEquals(
            null,
            ChapterListFilter.partLabelOf("Chapter 9 - The Parting Gift")
        )
        assertEquals(
            "Volume 3",
            ChapterListFilter.partLabelOf("Vol. 3 - Awakening")
        )
    }

    @Test
    fun `search matches title words and chapter numbers`() {
        assertEquals(
            listOf("2 chapter - TCF Part 2 - A miracle"),
            ChapterListFilter.apply(
                tcf, "miracle", ChapterListFilter.State.ALL, null
            ).map { it.title }
        )
        // Chapter number 1 = the first row (the prologue).
        assertEquals(
            listOf("1 chapter - Prologue"),
            ChapterListFilter.apply(
                tcf, "1", ChapterListFilter.State.ALL, null
            ).map { it.title }
        )
    }

    @Test
    fun `state filters keep unread and downloaded apart`() {
        // D is a READING CACHE: the text is on the phone but
        // the user never tapped Download (v1.0.7.1 rule) — it
        // must not count as downloaded.
        val cached = Chapter(
            bookId = 1, index = 3, title = "D",
            contentPath = "content/1/3.json", updatedAt = 0
        )
        val mixed = listOf(
            chapter(0, "A", read = true),
            chapter(1, "B", downloaded = true),
            chapter(2, "C"),
            cached
        )
        assertEquals(
            listOf("B", "C", "D"),
            ChapterListFilter.apply(
                mixed, "", ChapterListFilter.State.UNREAD, null
            ).map { it.title }
        )
        assertEquals(
            listOf("B"),
            ChapterListFilter.apply(
                mixed, "", ChapterListFilter.State.DOWNLOADED, null
            ).map { it.title }
        )
    }
}
