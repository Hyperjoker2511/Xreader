package app.xreader.domain.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineWrapTest {

    private fun lines(text: String, width: Int): List<String> =
        LineWrap.breakLines(text, width).map { (s, e) -> text.substring(s, e) }

    @Test
    fun latinWordsAreNotSplit() {
        val result = lines("the quick brown fox jumps", 10)
        assertEquals(listOf("the quick", "brown fox", "jumps"), result)
    }

    @Test
    fun coverageSkipsOnlySpaces() {
        val text = "alpha beta gamma delta epsilon zeta"
        val ranges = LineWrap.breakLines(text, 9)
        val covered = BooleanArray(text.length)
        for ((s, e) in ranges) for (i in s until e) {
            assertTrue("char $i covered twice", !covered[i])
            covered[i] = true
        }
        for ((i, c) in text.withIndex()) {
            if (c != ' ') assertTrue("char $i ('$c') lost", covered[i])
        }
    }

    @Test
    fun cjkBreaksAnywhereAndCountsDoubleWidth() {
        val text = "天地玄黄宇宙洪荒" // 8 ideographs, width 2 each = 16 units
        val result = lines(text, 6) // 3 ideographs per line
        assertEquals(listOf("天地玄", "黄宇宙", "洪荒"), result)
        // No spaces in CJK: coverage is exact.
        val ranges = LineWrap.breakLines(text, 6)
        assertEquals(text, ranges.joinToString("") { (s, e) -> text.substring(s, e) })
    }

    @Test
    fun mixedCjkAndLatin() {
        // "ab 天地 cd" widths: a1 b1 sp1 天2 地2 sp1 c1 d1 = 10.
        // CJK may break between any two characters, so line 1 ends
        // after 天; the rest fits on line 2.
        val result = lines("ab 天地 cd", 5)
        assertEquals(listOf("ab 天", "地 cd"), result)
    }

    @Test
    fun hangulIsWide() {
        assertEquals(2, LineWrap.charWidth('한'))
        assertEquals(2, LineWrap.charWidth('。'))
        assertEquals(1, LineWrap.charWidth('a'))
    }

    @Test
    fun longWordHardSplits() {
        val result = lines("abcdefghijklmnop", 5)
        assertEquals(listOf("abcde", "fghij", "klmno", "p"), result)
    }

    @Test
    fun newlineForcesBreak() {
        val result = lines("one\ntwo three", 20)
        assertEquals(listOf("one", "two three"), result)
    }

    @Test
    fun emptyTextIsOneEmptyLine() {
        assertEquals(listOf(0 to 0), LineWrap.breakLines("", 10))
    }

    @Test
    fun wideCharOnNarrowLineStillProgresses() {
        val result = lines("天地", 1)
        assertEquals(listOf("天", "地"), result)
    }
}
