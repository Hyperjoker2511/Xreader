package app.xreader.ui.book

import app.xreader.data.extension.DiagnosticLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.8 (K5): Copy details tells the truth — it carries the
 * chaptersError when present, the latest `sync`-seam
 * DiagnosticLog lines (A3), and the `fenrir-unlock` lines for
 * Fenrir books only.
 */
class V158CopyDetailsTruthTest {

    private fun entry(tag: String, message: String, detail: String = "") =
        DiagnosticLog.Entry(
            atMillis = 0, tag = tag, message = message, detail = detail
        )

    @Test
    fun `chapters error and sync lines are in the copied text`() {
        val text = BookCopyDetails.build(
            title = "Some Book", author = "An Author",
            chapterCount = 0, downloadedCount = 0,
            statusLabel = null, description = "",
            chaptersError = "The chapter list couldn't be loaded",
            isFenrirBook = false,
            logEntries = listOf(
                entry("sync", "refresh failed", "HTTP 503"),
                entry("download", "unrelated", "")
            )
        )
        assertTrue(text.contains("Chapters error: The chapter list couldn't be loaded"))
        assertTrue(text.contains("refresh failed"))
        assertTrue(text.contains("HTTP 503"))
        assertFalse(text.contains("unrelated"))
    }

    @Test
    fun `a Fenrir book's copy carries the unlock lines`() {
        val entries = listOf(
            entry(
                "fenrir-unlock",
                "[FenrirUnlock] mintRoad=webview mintStatus=challenge"
            )
        )
        val fenrir = BookCopyDetails.build(
            title = "Fenrir Book", author = "",
            chapterCount = 3, downloadedCount = 0,
            statusLabel = null, description = "",
            chaptersError = null, isFenrirBook = true,
            logEntries = entries
        )
        assertTrue(fenrir.contains("mintRoad=webview"))
        val other = BookCopyDetails.build(
            title = "Other Book", author = "",
            chapterCount = 3, downloadedCount = 0,
            statusLabel = null, description = "",
            chaptersError = null, isFenrirBook = false,
            logEntries = entries
        )
        assertFalse(other.contains("mintRoad=webview"))
        assertTrue(BookCopyDetails.isFenrir("FenrirRealm"))
        assertFalse(BookCopyDetails.isFenrir("AsuraScans"))
    }
}
