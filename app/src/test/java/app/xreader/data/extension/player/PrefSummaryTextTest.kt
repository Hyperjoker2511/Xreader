package app.xreader.data.extension.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * K5: preference summary display — the pure string half of
 * the host's summary resolution (a SummaryProvider's output
 * needs the live preference and is exercised on device).
 */
class PrefSummaryTextTest {

    @Test
    fun nullRawStaysNull() {
        assertNull(PrefSummaryText.display(null, "English"))
        assertNull(PrefSummaryText.display(null, null))
    }

    @Test
    fun plainSummaryPassesThroughUnchanged() {
        assertEquals(
            "Use the mirror CDN",
            PrefSummaryText.display("Use the mirror CDN", "ignored")
        )
    }

    @Test
    fun placeholderTakesTheCurrentValue() {
        assertEquals(
            "Language: English",
            PrefSummaryText.display("Language: %s", "English")
        )
        assertEquals(
            "Token: abc",
            PrefSummaryText.display("Token: %s", "abc")
        )
    }

    @Test
    fun placeholderWithNoValueReadsEmptyNotNull() {
        assertEquals(
            "Language: ",
            PrefSummaryText.display("Language: %s", null)
        )
    }
}
