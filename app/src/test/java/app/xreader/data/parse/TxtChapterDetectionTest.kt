package app.xreader.data.parse

import app.xreader.data.prefs.TxtLanguage
import app.xreader.data.prefs.TxtPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR-6 Items 2–4 (SPEC-MR6-TXT-2026-11-25): the mode switch, the
 * two heuristic detectors and Moon+'s winner selection. The
 * detectors are re-implementations in kind — these fixtures are
 * the acceptance bar, exactly as the spec's fidelity notes say.
 */
class TxtChapterDetectionTest {

    private fun settings(tocReg: Boolean) =
        TxtPrefs.State(tocRegMode = tocReg)

    private val body = "The road went on through the hills and the hero " +
        "walked it without looking back at the village, and the wind " +
        "followed him all the way to the river crossing. "

    @Test fun regexModeUsesTheRegexOnly() {
        // Plain isolated titles exist in the text, but regex mode
        // must not see them — the mode switch is not a vote.
        val text = buildString {
            append("My Novel\n\n")
            append("Chapter 1\n\n").append(body.repeat(2)).append("\n\n")
            append("The Long Night\n\n").append(body.repeat(2)).append("\n\n")
            append("Chapter 2\n\n").append(body.repeat(2)).append("\n")
        }
        val found = TxtChapterDetection.detect(text, settings(tocReg = true))
        assertEquals(listOf("Chapter 1", "Chapter 2"), found.map { it.title })
    }

    @Test fun heuristicModeFindsPlainShortTitles() {
        // Item 2's proof shape: chapters titled with plain short
        // lines, no "Chapter N" marker anywhere.
        val text = buildString {
            append("The Long Night\n\n").append(body.repeat(2)).append("\n\n")
            append("Homecoming\n\n").append(body.repeat(2)).append("\n\n")
            append("The River Road\n\n").append(body.repeat(2)).append("\n")
        }
        val found = TxtChapterDetection.detect(text, settings(tocReg = false))
        assertEquals(
            listOf("The Long Night", "Homecoming", "The River Road"),
            found.map { it.title }
        )
    }

    @Test fun overlongIsolatedProseLineIsNotAChapter() {
        // Item 2's cap bar: an isolated line longer than the cap
        // (100 for English) is prose, never a heading.
        val longLine = "It was on that morning that the whole village " +
            "came down to the river to watch the boats, and nobody " +
            "who stood on the bank that day ever forgot the sight."
        val text = buildString {
            append("Chapter 1\n\n").append(body.repeat(2)).append("\n\n")
            append(longLine).append("\n\n")
            append("Chapter 2\n\n").append(body.repeat(2)).append("\n\n")
            append("Chapter 3\n\n").append(body.repeat(2)).append("\n")
        }
        val found = TxtChapterDetection.detect(text, settings(tocReg = false))
        assertEquals(
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            found.map { it.title }
        )
    }

    @Test fun detector2WinsForBareNumeralHeadings() {
        // Item 3's >110% case: a Chinese book whose sections are
        // headed by bare numerals — detector 1 sees only the
        // three 第…章 chapters; detector 2's lists see the
        // numerals and win by count.
        val cnBody = "他走在山路上，风吹过松林，远处传来钟声，" +
            "那是寺庙的晚钟，提醒着旅人天色已晚。"
        val text = buildString {
            append("第一章 出发\n\n").append(cnBody).append("\n\n")
            append("第二章 山路\n\n").append(cnBody).append("\n\n")
            append("第三章 晚钟\n\n").append(cnBody).append("\n\n")
            for (n in 1..25) {
                append(n.toString()).append("\n\n")
                append(cnBody).append("\n\n")
            }
        }
        val detector1 = TxtHeuristics.detector1(
            text, splitTxtLines(text), TxtLanguage.CN
        )
        assertEquals(3, detector1.size)
        val found = TxtChapterDetection.detect(text, settings(tocReg = false))
        assertTrue(
            "detector 2 should win big, got ${found.size}",
            found.size > 20
        )
        assertTrue(found.any { it.title == "1" })
        assertTrue(found.any { it.title == "25" })
    }

    @Test fun pruningRemovesTheOverlongFalsePositive() {
        // Item 4's pruning: over 10 chapters, a planted keyword
        // line far longer than the average title is removed;
        // the real chapters stay.
        val planted = "Chapter 7 was the one where the hero finally " +
            "understood the road home"
        val text = buildString {
            for (n in 1..12) {
                append("Chapter $n\n\n").append(body.repeat(2)).append("\n\n")
                if (n == 6) append(planted).append("\n\n").append(body.repeat(2)).append("\n\n")
            }
        }
        val found = TxtChapterDetection.detect(text, settings(tocReg = false))
        assertEquals(12, found.size)
        assertFalse(found.any { it.title == planted })
    }

    @Test fun duplicateConsecutiveTitlesAreDeduped() {
        val text = buildString {
            append("My Novel\n\n")
            append("Chapter 1\n\n").append(body.repeat(2)).append("\n\n")
            append("Chapter 1\n\n").append(body.repeat(2)).append("\n\n")
            append("Chapter 2\n\n").append(body.repeat(2)).append("\n")
        }
        val found = TxtChapterDetection.detect(text, settings(tocReg = true))
        assertEquals(listOf("Chapter 1", "Chapter 2"), found.map { it.title })
    }

    @Test fun blacklistDropsTitlesEndingWithABannedChar() {
        val text = buildString {
            append("My Novel\n\n")
            append("Chapter 1\n\n").append(body.repeat(2)).append("\n\n")
            append("Chapter 2,\n\n").append(body.repeat(2)).append("\n\n")
            append("Chapter 3\n\n").append(body.repeat(2)).append("\n")
        }
        val found = TxtChapterDetection.detect(text, settings(tocReg = true))
        assertEquals(listOf("Chapter 1", "Chapter 3"), found.map { it.title })
    }

    @Test fun languageChoiceFollowsMoonPlus() {
        assertEquals(
            TxtLanguage.CN,
            TxtChapterRegex.languageOf("这是一个中文小说的开头，讲述了主角的冒险旅程，还有很多很多的字。")
        )
        assertEquals(
            TxtLanguage.RU,
            TxtChapterRegex.languageOf("Это начало русского романа о приключениях главного героя и его друзей.")
        )
        assertEquals(
            TxtLanguage.EN,
            TxtChapterRegex.languageOf("This is the opening of an English novel about a long journey home.")
        )
    }
}
