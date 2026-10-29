package app.xreader.core.error

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every AppError has plain words + a recovery action, and the
 * "Details:" line appears exactly when the error carries a detail
 * (PLAN §1.5). */
class ErrorTextTest {

    private val allErrors: List<AppError> = listOf(
        AppError.NoNetwork,
        AppError.ProviderDown("gemini"),
        AppError.ProviderDown("drive", httpCode = 503, detail = "HTTP 503: unavailable"),
        AppError.RateLimited("gemini", retryAfterSeconds = 30),
        AppError.AuthFailed("gemini", detail = "HTTP 400: API key not valid"),
        AppError.ParseFailed("EPUB", "The chapter list is missing."),
        AppError.DrmProtected,
        AppError.ExtensionFailed("Novel Source"),
        AppError.SyncConflict("reading progress"),
        AppError.StorageFull,
        AppError.StorageFailed("save chapter"),
        AppError.ExportFailed("The folder is read-only."),
        AppError.BadFile("The file is empty."),
        AppError.Unknown(IllegalStateException("boom"))
    )

    @Test
    fun `every error has plain words and a recovery action`() {
        allErrors.forEach { error ->
            val ui = ErrorText.of(error)
            assertTrue("${error} message blank", ui.message.isNotBlank())
            assertTrue("${error} recovery blank", ui.recoveryLabel.isNotBlank())
        }
    }

    @Test
    fun `details line appears only when a detail exists`() {
        val withDetail = AppError.ProviderDown("drive", httpCode = 503, detail = "HTTP 503: unavailable")
        val text = ErrorText.displayText(withDetail)
        assertTrue(text.contains("Details: HTTP 503: unavailable"))
        assertTrue(text.startsWith(ErrorText.of(withDetail).message))

        val without = ErrorText.displayText(AppError.NoNetwork)
        assertFalse(without.contains("Details:"))
    }

    @Test
    fun `unknown exposes only the exception class as detail`() {
        val error = AppError.Unknown(IllegalStateException("sensitive message"))
        assertTrue(ErrorText.displayText(error).contains("Details: IllegalStateException"))
        assertFalse(ErrorText.displayText(error).contains("sensitive"))
    }

    @Test
    fun `crash note carries time thread and class only`() {
        val note = CrashGuard.formatNote(0L, "main", IllegalArgumentException("secret text"))
        assertTrue(note.contains("Xreader crash note"))
        assertTrue(note.contains("java.lang.IllegalArgumentException"))
        assertFalse(note.contains("secret text"))
    }
}
