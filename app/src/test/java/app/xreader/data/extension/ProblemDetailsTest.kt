package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * v1.0.5 (E5): the copied problem-details text is a fixed,
 * boring format — host, failing step, reason, app version —
 * pinned so a support conversation never has to guess what a
 * pasted summary contains.
 */
class ProblemDetailsTest {

    @Test
    fun fullDetails_haveEveryLineInOrder() {
        val text = ProblemDetails.format(
            host = "novelight.net",
            failingStep = "Chapter lists",
            reason = "The book page listed no chapters.",
            appVersion = "1.0.5"
        )
        assertEquals(
            """
            Xreader problem details
            Site: novelight.net
            Failing step: Chapter lists
            Problem: The book page listed no chapters.
            App version: 1.0.5
            """.trimIndent(),
            text
        )
    }

    @Test
    fun unknownHostAndStep_linesAreOmitted() {
        val text = ProblemDetails.format(
            host = null,
            failingStep = "  ",
            reason = "The site did not answer.",
            appVersion = "1.0.5"
        )
        assertFalse(text.contains("Site:"))
        assertFalse(text.contains("Failing step:"))
        assertEquals(
            """
            Xreader problem details
            Problem: The site did not answer.
            App version: 1.0.5
            """.trimIndent(),
            text
        )
    }

    @Test
    fun blankReason_getsAPlaceholderNotAnEmptyLine() {
        val text = ProblemDetails.format(
            host = "example.com",
            failingStep = null,
            reason = "   ",
            appVersion = "1.0.5"
        )
        assertEquals(
            """
            Xreader problem details
            Site: example.com
            Problem: No reason was given.
            App version: 1.0.5
            """.trimIndent(),
            text
        )
    }
}
