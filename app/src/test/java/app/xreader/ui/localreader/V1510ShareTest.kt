package app.xreader.ui.localreader

import app.xreader.data.db.Book
import app.xreader.data.db.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.10 (G5) gate: Share — the book's OWN file streams from
 * its registered document URI under a read grant, plus the
 * share-details text. A book with no registered URI offers
 * no share action at all (never a dead button).
 */
class V1510ShareTest {

    private fun book(
        documentUri: String?,
        format: BookFormat = BookFormat.PDF
    ) = Book(
        id = 7L,
        title = "The Grieving Soul",
        author = "Author A",
        localFormat = format,
        documentUri = documentUri,
        addedAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `share exists only with a registered document uri`() {
        assertFalse(bookShareAvailable(null))
        assertFalse(bookShareAvailable(""))
        assertFalse(bookShareAvailable("   "))
        assertTrue(bookShareAvailable("content://docs/book.pdf"))
    }

    @Test
    fun `spec carries the uri verbatim and the pdf mime`() {
        val spec = buildBookShareSpec(
            book("content://docs/book.pdf")
        )!!
        assertEquals("content://docs/book.pdf", spec.uriString)
        assertEquals("application/pdf", spec.mimeType)
        assertEquals("The Grieving Soul", spec.subject)
        assertEquals("The Grieving Soul — Author A", spec.text)
    }

    @Test
    fun `epub mime and authorless text`() {
        val spec = buildBookShareSpec(
            book("content://docs/book.epub", BookFormat.EPUB)
                .copy(author = "")
        )!!
        assertEquals("application/epub+zip", spec.mimeType)
        assertEquals("The Grieving Soul", spec.text)
    }

    @Test
    fun `no uri means no spec`() {
        assertNull(buildBookShareSpec(book(null)))
        assertNull(buildBookShareSpec(book("  ")))
    }
}
