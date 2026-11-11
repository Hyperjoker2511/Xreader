package app.xreader.ui.localreader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.9 (F-10, Finding 4 P8): the search sweep's text memo —
 * the production [SearchTextMemo] the ViewModel's `search`
 * reads through. A counting provider stands in for the
 * session's `textAt`: the first sweep reads each section
 * once; the second sweep (a new query over the same session)
 * performs ZERO additional reads; clearing (a new session)
 * re-reads; and a text that would exceed the 64 MB char cap
 * is served but never retained (re-read fallback).
 */
class V159SearchMemoTest {

    @Test
    fun `second sweep performs zero additional textAt calls`() = runTest {
        val memo = SearchTextMemo()
        var reads = 0
        val provider: suspend (Int) -> String = { index ->
            reads++
            "section $index sentinel text"
        }
        // First query: sweep the spine.
        for (index in 0 until 5) memo.textAt(index, provider)
        assertEquals(5, reads)
        assertEquals(5, memo.size)
        // Second query: the whole sweep is served by the memo.
        for (index in 0 until 5) {
            val text = memo.textAt(index, provider)
            assertEquals("section $index sentinel text", text)
        }
        assertEquals("zero additional reads", 5, reads)
        // A new session clears the memo and reads again.
        memo.clear()
        assertEquals(0, memo.size)
        memo.textAt(0, provider)
        assertEquals(6, reads)
    }

    @Test
    fun `texts beyond the cap are served but not retained`() = runTest {
        val memo = SearchTextMemo(maxChars = 10L)
        var reads = 0
        val provider: suspend (Int) -> String = {
            reads++
            "a text longer than the ten-char cap"
        }
        val first = memo.textAt(0, provider)
        assertEquals("a text longer than the ten-char cap", first)
        assertEquals("over-cap text is not retained", 0, memo.size)
        memo.textAt(0, provider)
        assertEquals("over-cap section falls back to re-reading", 2, reads)
    }
}
