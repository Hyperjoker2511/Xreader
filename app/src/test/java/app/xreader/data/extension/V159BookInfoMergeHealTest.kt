package app.xreader.data.extension

import app.xreader.data.db.Book
import app.xreader.data.db.SourceType
import app.xreader.extensionapi.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.5.9 (E-1, Finding 2 §5.1 storage persistence): the
 * walk-time heal returns the healed remote id on the details
 * read, and [BookInfoMerge] — the merge every refresh /
 * openBook details half lands through — must PERSIST it into
 * Book.remoteId, but only for the heal's exact output shape:
 * a pure re-host (both ids absolute http(s) URLs, equal
 * path + query, differing at most in scheme + authority).
 * Every other shape leaves the stored address untouched.
 * Drives the shipped pure seam directly (the
 * [BookInfoMergeTest] harness pattern).
 */
class V159BookInfoMergeHealTest {

    private companion object {
        const val SOURCE_ID = "bundled-brightnovel"
        const val STORED_URL =
            "https://stale.example/series/the-dullard?loaded=0&sort=desc"
        const val HEALED_URL =
            "https://brightnovels.com/series/the-dullard?loaded=0&sort=desc"
    }

    private fun storedBook(remoteId: String?) = Book(
        title = "Stored Title",
        author = "Stored Author",
        description = "Stored synopsis.",
        sourceType = SourceType.EXTENSION,
        extensionId = 7L,
        remoteId = remoteId,
        addedAt = 1L,
        updatedAt = 1L
    )

    private fun details(id: String) = SourceBook(
        id = id,
        title = "Stored Title",
        author = "Stored Author",
        description = "Stored synopsis."
    )

    @Test
    fun `healed refresh persists the re-hosted remoteId`() {
        val stored = storedBook(SourceBookIds.encode(SOURCE_ID, STORED_URL))
        val merged = BookInfoMerge.apply(stored, details(HEALED_URL))
        assertEquals(
            "the healed id must reach Room, re-encoded under the same source id",
            SourceBookIds.encode(SOURCE_ID, HEALED_URL),
            merged.remoteId
        )
        // The merge's other behaviour is untouched.
        assertEquals("Stored Title", merged.title)
        assertEquals("Stored synopsis.", merged.description)
    }

    @Test
    fun `scheme-only change is also a pure re-host and persists`() {
        val stored = storedBook(
            SourceBookIds.encode(
                SOURCE_ID, "http://brightnovels.com/series/the-dullard"
            )
        )
        val merged = BookInfoMerge.apply(
            stored, details("https://brightnovels.com/series/the-dullard")
        )
        assertEquals(
            SourceBookIds.encode(
                SOURCE_ID, "https://brightnovels.com/series/the-dullard"
            ),
            merged.remoteId
        )
    }

    @Test
    fun `path differing incoming id does not overwrite the stored remoteId`() {
        val encoded = SourceBookIds.encode(SOURCE_ID, STORED_URL)
        val stored = storedBook(encoded)
        val merged = BookInfoMerge.apply(
            stored,
            details("https://brightnovels.com/series/a-different-book?loaded=0&sort=desc")
        )
        assertEquals(encoded, merged.remoteId)
    }

    @Test
    fun `query differing incoming id does not overwrite the stored remoteId`() {
        val encoded = SourceBookIds.encode(SOURCE_ID, STORED_URL)
        val stored = storedBook(encoded)
        val merged = BookInfoMerge.apply(
            stored,
            details("https://brightnovels.com/series/the-dullard?loaded=99&sort=desc")
        )
        assertEquals(encoded, merged.remoteId)
    }

    @Test
    fun `player style incoming id never overwrites a stored URL remoteId`() {
        val encoded = SourceBookIds.encode(SOURCE_ID, STORED_URL)
        val stored = storedBook(encoded)
        val merged = BookInfoMerge.apply(stored, details("/manga/alpha"))
        assertEquals(encoded, merged.remoteId)
    }

    @Test
    fun `URL incoming id never overwrites a stored player style remoteId`() {
        val encoded = SourceBookIds.encode("keiyoushi:pkg:777", "/manga/alpha")
        val stored = storedBook(encoded)
        val merged = BookInfoMerge.apply(stored, details(HEALED_URL))
        assertEquals(encoded, merged.remoteId)
    }

    @Test
    fun `identical id is a no-op`() {
        val encoded = SourceBookIds.encode(SOURCE_ID, HEALED_URL)
        val stored = storedBook(encoded)
        val merged = BookInfoMerge.apply(stored, details(HEALED_URL))
        assertEquals(encoded, merged.remoteId)
        assertNull(BookInfoMerge.rehostedRemoteId(encoded, HEALED_URL))
    }

    @Test
    fun `a book with no stored remoteId gains none from a heal shaped fetch`() {
        val stored = storedBook(remoteId = null)
        val merged = BookInfoMerge.apply(stored, details(HEALED_URL))
        assertNull(merged.remoteId)
    }
}
