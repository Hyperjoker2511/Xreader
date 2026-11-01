package app.xreader.data.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.4 (T2): the not-prose gate, multi-signal. The blob
 * fixture is built from the REAL failure shape (V133-CHECK
 * 1a: an encoded payload served as FenrirRealm's Ch.1, with
 * its "Translator: FenrirTL" credit line visible inside) —
 * long unbroken base64 tokens, almost no spaces, no
 * sentence texture, no language. The prose fixtures are
 * ordinary chapter text, one carrying a long URL and
 * compound words, one mostly dialogue, one short.
 */
class V134ContentIntegrityTest {

    /** Base64-shaped filler of the failure's texture:
     * unbroken 400+ char tokens from the base64 alphabet. */
    private fun blobToken(chars: Int): String {
        val alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var seed = 7
        repeat(chars) {
            seed = (seed * 31 + 17) % 61
            sb.append(alphabet[seed % alphabet.length])
        }
        return sb.toString()
    }

    private val gibberish: String = buildString {
        append("Translator: FenrirTL\n")
        append(blobToken(900)).append('\n')
        append(blobToken(700)).append('\n')
        append(blobToken(500))
    }

    private val proseOne: String = (1..8).joinToString("\n") {
        "The old swordsman walked the road to the capital with " +
            "nothing but the clothes on his back and a blade that " +
            "had not been drawn in twenty years, and the rain " +
            "that fell on the mountain pass was cold enough to " +
            "make his hands ache around the worn leather grip."
    }

    private val proseWithUrl: String =
        "She checked the archive at " +
            "https://static.example-cdn.net/assets/chapter-images/" +
            "vol-12/supercalifragilisticexpialidociouslongwordfile" +
            "-000123456789abcdef.png before she left, and then " +
            "the two of them went down to the river where the " +
            "boats were waiting in the grey light of the morning, " +
            "and neither of them spoke for a long time after that."

    private val dialogue: String = (1..6).joinToString("\n") {
        "\"You came back,\" he said. \"I told you that I would.\" " +
            "\"And the others?\" \"Gone. All of them, in the end.\" " +
            "She laughed, but it was not a happy sound, and the " +
            "room felt colder for it."
    }

    @Test
    fun `the real gibberish shape is refused`() {
        val signals = ProseSanity.signals(gibberish)
        assertTrue(
            "blob dominance, got ${signals.blobShare}",
            signals.blobShare >= ProseSanity.TOTAL_BLOB_SHARE ||
                signals.blobShare >= ProseSanity.BLOB_SHARE
        )
        assertTrue(
            "almost no spaces, got ${signals.spaceDensity}",
            signals.spaceDensity < ProseSanity.MAX_PROSELESS_SPACE_DENSITY
        )
        assertFalse(ProseSanity.isProse(gibberish))
        assertNotNull(ChapterServeGate.refusalFor(gibberish))
    }

    @Test
    fun `line wrapped base64 is refused too`() {
        // Wrapped every 64 chars: spaces appear, but the
        // texture signals (space density, language) still
        // agree it is not prose.
        val wrapped = blobToken(4000).chunked(64).joinToString(" ")
        assertFalse(ProseSanity.isProse(wrapped))
    }

    @Test
    fun `real prose passes on every fixture`() {
        for (prose in listOf(proseOne, proseWithUrl, dialogue)) {
            assertTrue(
                "prose refused: ${prose.take(40)}…",
                ProseSanity.isProse(prose)
            )
            assertNull(ChapterServeGate.refusalFor(prose))
        }
    }

    @Test
    fun `short text is never judged`() {
        assertTrue(ProseSanity.isProse("aGVsbG8gd29ybGQ="))
        assertNull(ChapterServeGate.refusalFor("aGVsbG8gd29ybGQ="))
    }

    @Test
    fun `the gate is not a lone threshold - blob share alone does not refuse prose`() {
        // A long technical appendix in real sentences, with
        // identifiers: blob share stays far under the line
        // AND language/texture are present.
        val technical = (1..6).joinToString("\n") {
            "The configuration value is set in the file and the " +
                "service reads it when the process starts, and if " +
                "the value is missing the default is used instead."
        }
        val signals = ProseSanity.signals(technical)
        assertTrue(signals.stopwordHits >= ProseSanity.MIN_STOPWORD_HITS)
        assertTrue(ProseSanity.isProse(technical))
        assertEquals(0.0, signals.blobShare, 0.001)
    }
}
